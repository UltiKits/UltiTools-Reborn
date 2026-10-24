package com.ultikits.ultitools.manager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.ContractPrimitives;
import com.ultikits.ultitools.utils.PayloadReader;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * Answers the UltiCloud protocol 2.0.0 {@code properties.get} and {@code properties.update}
 * requests for one connection; internal, not for module use.
 *
 * <p>This class carries the contract's type knowledge — which of the fourteen readable keys is a
 * string, an integer within which bounds, a boolean, or one of the difficulty and gamemode
 * spellings — and {@link ServerPropertiesManager} carries the file knowledge: the line-preserving
 * rewrite, the write lock and the version comparison. Splitting it that way keeps the frozen
 * contract's vocabulary out of the manager and the file's grammar out of the handler.</p>
 *
 * <p><b>Authentication and the whitelist are never remotely writable.</b> The strict payload shape
 * rejects any key outside the contract's twelve with {@code invalid_message} before the manager is
 * reached, and the manager refuses the same keys again per key if a future caller bypasses the
 * payload check. Both paths are pinned by tests (maintainer decision: user decision 15).</p>
 *
 * <p>Per-key results follow the contract: {@code ok} when every requested key was written,
 * {@code partial} when at least one was refused or is absent from the file while the others were
 * written, and {@code error} with {@code file_changed}, {@code not_found} or {@code write_denied}
 * when nothing could be written.</p>
 */
final class RemotePropertiesHandler implements CloudRequestHandler {
    private static final Logger LOGGER = Logger.getLogger(RemotePropertiesHandler.class.getName());

    private static final String PROPERTIES_GET = "properties.get";
    private static final String PROPERTIES_GET_RESULT = "properties.get.result";
    private static final String PROPERTIES_UPDATE = "properties.update";
    private static final String PROPERTIES_UPDATE_RESULT = "properties.update.result";

    /** The contract's upper bound on a result message, in code points. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** The contract's upper bound on {@code motd}, and on every other string-typed property value. */
    private static final int MAX_TEXT_LENGTH = 256;

    /** What each key's value looks like in the frozen contract's {@code properties.get} schema. */
    private static final Map<String, KeySpec> READABLE = readableSpecs();

    private final ServerPropertiesManager properties;

    /**
     * Creates the handler over the manager that owns the file.
     *
     * @param properties the manager whose read and update operations this handler exposes
     */
    RemotePropertiesHandler(ServerPropertiesManager properties) {
        this.properties = properties;
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        if (PROPERTIES_GET.equals(request.type())) {
            get(request, channel);
        } else if (PROPERTIES_UPDATE.equals(request.type())) {
            update(request, channel);
        } else {
            throw new IllegalArgumentException("Unhandled properties message type");
        }
    }

    /**
     * Answers {@code properties.get}. The payload has no fields, so any field at all is
     * {@code invalid_message}; the reply carries the typed values, the writable key set (which is
     * where the panel learns that {@code online-mode} and {@code white-list} are read-only) and the
     * version of the bytes the values were read from.
     */
    private void get(CloudRequest request, CloudChannel channel) {
        new PayloadReader(request.id(), request.payload()).keys(new String[0]);

        ServerPropertiesManager.PropertiesSnapshot snapshot =
                properties.readVersioned(channel.limits().fileMaxBytes());
        ServerPropertiesManager.PropertiesSnapshot.Outcome outcome = snapshot.getOutcome();
        if (outcome == ServerPropertiesManager.PropertiesSnapshot.Outcome.NOT_FOUND) {
            error(channel, request.id(), PROPERTIES_GET_RESULT, "not_found", "There is no server.properties.");
            return;
        }
        if (outcome == ServerPropertiesManager.PropertiesSnapshot.Outcome.FILE_NOT_EDITABLE) {
            error(channel, request.id(), PROPERTIES_GET_RESULT, "file_not_editable",
                    "server.properties cannot be shown or edited through the panel.");
            return;
        }
        if (outcome != ServerPropertiesManager.PropertiesSnapshot.Outcome.OK) {
            error(channel, request.id(), PROPERTIES_GET_RESULT, "internal_error",
                    "server.properties could not be read.");
            return;
        }

        JsonObject values = new JsonObject();
        for (Map.Entry<String, String> entry : snapshot.getValues().entrySet()) {
            KeySpec spec = READABLE.get(entry.getKey());
            if (spec == null) {
                continue;
            }
            Object typed = typedValue(spec, entry.getValue());
            if (typed == null) {
                LOGGER.fine("UltiCloud: a server.properties value does not match its contract type and is omitted.");
                continue;
            }
            addTyped(values, entry.getKey(), typed);
        }
        JsonArray writable = new JsonArray();
        for (String key : snapshot.getWritable()) {
            writable.add(key);
        }

        JsonObject data = new JsonObject();
        data.add("values", values);
        data.add("writable", writable);
        data.addProperty("version", snapshot.getVersion());
        channel.replyOk(request.id(), PROPERTIES_GET_RESULT, data);
    }

    /**
     * Answers {@code properties.update}. The payload is validated against the frozen schema first —
     * only the contract's twelve keys, each with its own type and bounds — so an unwritable key or a
     * string where a number belongs never reaches the file.
     */
    private void update(CloudRequest request, CloudChannel channel) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[]{"baseVersion", "changes"});
        String baseVersion = reader.string("baseVersion", 64, 64, ContractPrimitives.SHA256_HEX);
        JsonObject changes = reader.object("changes");
        List<String> writable = ServerPropertiesManager.writableKeys();
        new PayloadReader(request.id(), changes).keys(new String[0], writable.toArray(new String[writable.size()]));

        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> change : changes.entrySet()) {
            KeySpec spec = READABLE.get(change.getKey());
            if (spec == null) {
                throw new IllegalArgumentException("A writable key has no contract type");
            }
            values.put(change.getKey(), textValue(request.id(), spec, change.getKey(), change.getValue()));
        }

        ServerPropertiesManager.PropertiesUpdateResult result =
                properties.updateWithBaseVersion(baseVersion, values);
        ServerPropertiesManager.PropertiesUpdateResult.Outcome outcome = result.getOutcome();
        if (outcome == ServerPropertiesManager.PropertiesUpdateResult.Outcome.FILE_CHANGED) {
            error(channel, request.id(), PROPERTIES_UPDATE_RESULT, "file_changed",
                    "server.properties changed after the panel read it; reload it and try again.");
            return;
        }
        if (outcome == ServerPropertiesManager.PropertiesUpdateResult.Outcome.NOT_FOUND) {
            error(channel, request.id(), PROPERTIES_UPDATE_RESULT, "not_found", "There is no server.properties.");
            return;
        }
        if (outcome == ServerPropertiesManager.PropertiesUpdateResult.Outcome.REFUSED) {
            error(channel, request.id(), PROPERTIES_UPDATE_RESULT, "write_denied", result.getReason());
            return;
        }

        JsonArray keys = new JsonArray();
        for (ServerPropertiesManager.PropertyKeyResult key : result.getKeys()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("key", key.getKey());
            entry.addProperty("ok", key.isOk());
            if (key.getMessage() == null) {
                entry.add("message", JsonNull.INSTANCE);
            } else {
                entry.addProperty("message", key.getMessage());
            }
            keys.add(entry);
        }
        JsonObject data = new JsonObject();
        data.add("keys", keys);
        data.addProperty("version", result.getVersion());
        if (outcome == ServerPropertiesManager.PropertiesUpdateResult.Outcome.APPLIED) {
            channel.replyOk(request.id(), PROPERTIES_UPDATE_RESULT, data);
        } else {
            channel.replyPartial(request.id(), PROPERTIES_UPDATE_RESULT, data, null, null);
        }
    }

    /**
     * Converts one raw {@code server.properties} value to the JSON type its key carries.
     *
     * @param spec the key's contract type
     * @param text the value as the file holds it
     * @return the typed value, or {@code null} when the text does not parse to that type
     */
    private static Object typedValue(KeySpec spec, String text) {
        String trimmed = text.trim();
        switch (spec.kind) {
            case TEXT:
                return text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH ? null : text;
            case NUMBER:
                try {
                    long number = Long.parseLong(trimmed);
                    return number < spec.min || number > spec.max ? null : Long.valueOf(number);
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            case FLAG:
                if ("true".equalsIgnoreCase(trimmed)) {
                    return Boolean.TRUE;
                }
                return "false".equalsIgnoreCase(trimmed) ? Boolean.FALSE : null;
            case CHOICE:
                String lowered = trimmed.toLowerCase(Locale.ROOT);
                for (String allowed : spec.allowed) {
                    if (allowed.equals(lowered)) {
                        return lowered;
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * Validates one requested value against its key's contract type and returns the text to write.
     * Validation goes through {@link PayloadReader}, so a mismatch leaves as the router's
     * {@code invalid_message} rather than reaching the file.
     *
     * @param id    the request id for the validation error
     * @param spec  the key's contract type
     * @param key   the key being changed
     * @param value the JSON value the panel sent
     * @return the value in the form {@code server.properties} stores
     */
    private static String textValue(String id, KeySpec spec, String key, JsonElement value) {
        JsonObject single = new JsonObject();
        single.add(key, value);
        PayloadReader reader = new PayloadReader(id, single).keys(new String[]{key});
        switch (spec.kind) {
            case TEXT:
                return reader.string(key, 0, MAX_TEXT_LENGTH, null);
            case NUMBER:
                return Long.toString(reader.integer(key, spec.min, spec.max));
            case FLAG:
                return Boolean.toString(reader.bool(key));
            default:
                return reader.enumValue(key, spec.allowed);
        }
    }

    private static void addTyped(JsonObject values, String key, Object typed) {
        if (typed instanceof Boolean) {
            values.addProperty(key, (Boolean) typed);
        } else if (typed instanceof Long) {
            values.addProperty(key, (Long) typed);
        } else {
            values.addProperty(key, String.valueOf(typed));
        }
    }

    /**
     * The contract's type for each of the fourteen readable keys, in contract order. The bounds and
     * the two enumerations are copied from the vendored {@code properties.get} and
     * {@code properties.update} schemas.
     *
     * @return an unmodifiable key to type map
     */
    private static Map<String, KeySpec> readableSpecs() {
        Map<String, KeySpec> specs = new LinkedHashMap<>();
        specs.put("motd", new KeySpec(Kind.TEXT));
        specs.put("max-players", new KeySpec(Kind.NUMBER, 0L, Integer.MAX_VALUE));
        specs.put("difficulty", new KeySpec(Kind.CHOICE, "peaceful", "easy", "normal", "hard"));
        specs.put("gamemode", new KeySpec(Kind.CHOICE, "survival", "creative", "adventure", "spectator"));
        specs.put("spawn-protection", new KeySpec(Kind.NUMBER, 0L, Integer.MAX_VALUE));
        specs.put("view-distance", new KeySpec(Kind.NUMBER, 2L, 32L));
        specs.put("simulation-distance", new KeySpec(Kind.NUMBER, 2L, 32L));
        specs.put("pvp", new KeySpec(Kind.FLAG));
        specs.put("allow-flight", new KeySpec(Kind.FLAG));
        specs.put("spawn-monsters", new KeySpec(Kind.FLAG));
        specs.put("allow-nether", new KeySpec(Kind.FLAG));
        specs.put("enable-command-block", new KeySpec(Kind.FLAG));
        specs.put("online-mode", new KeySpec(Kind.FLAG));
        specs.put("white-list", new KeySpec(Kind.FLAG));
        return Collections.unmodifiableMap(specs);
    }

    /**
     * Sends a correlated error for one result type, capped at the contract's 500 code points.
     */
    private static void error(CloudChannel channel, String replyTo, String type, String code, String message) {
        String safe = message == null || message.isEmpty() ? "The request was refused." : message;
        if (safe.codePointCount(0, safe.length()) > MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_MESSAGE_LENGTH));
        }
        channel.replyError(replyTo, type, code, safe, null);
    }

    /** The JSON shape one property key's value takes. */
    private enum Kind {
        /** A free string, {@link #MAX_TEXT_LENGTH} code points at most. */
        TEXT,
        /** An integer between the key's bounds. */
        NUMBER,
        /** A JSON boolean. */
        FLAG,
        /** One of a fixed set of lower-case spellings. */
        CHOICE
    }

    /** One key's contract type: its kind and, for numbers and enumerations, its bounds. */
    private static final class KeySpec {
        private final Kind kind;
        private final long min;
        private final long max;
        private final String[] allowed;

        private KeySpec(Kind kind) {
            this(kind, 0L, 0L, new String[0]);
        }

        private KeySpec(Kind kind, long min, long max) {
            this(kind, min, max, new String[0]);
        }

        private KeySpec(Kind kind, String... allowed) {
            this(kind, 0L, 0L, allowed);
        }

        private KeySpec(Kind kind, long min, long max, String[] allowed) {
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.allowed = allowed.clone();
        }
    }
}
