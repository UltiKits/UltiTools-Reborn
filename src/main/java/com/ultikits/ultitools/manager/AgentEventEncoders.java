package com.ultikits.ultitools.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.utils.ContractPrimitives;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure encoders for the outbound protocol 2 event payloads: {@code player.command},
 * {@code player.join}/{@code player.leave} and {@code error.report}.
 *
 * <p>Each method returns a payload {@link JsonObject} that the connection wraps in an envelope, or
 * {@code null} when the input cannot satisfy the contract. A {@code null} means "do not send":
 * the gateway answers a non-conforming frame with {@code invalid_message} and closes, so dropping
 * an unencodable event locally is the only safe answer. No method throws.</p>
 *
 * <p><b>Redaction.</b> Text handed to these encoders is already redacted by the phase's builders
 * (D-20): an error report's message, trigger detail and stack trace come from the collector's
 * serialisation path, which replaces a sensitive command's arguments with {@code ***}. A
 * {@code player.command} payload carries the command name only because the schema has no field for
 * an argument (D-20).</p>
 *
 * <p>Package-private: wired by the session plan (22-22), not part of the public API.</p>
 */
final class AgentEventEncoders {

    /** packages/contracts: player.command payload name. */
    private static final Pattern COMMAND_NAME = Pattern.compile("^[a-z0-9_.-]{1,64}$");
    /** packages/contracts: error.report fingerprint, at least eight lower-case hex digits. */
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{8,64}$");

    private static final int EXCEPTION_TYPE_MAX = 256;
    private static final int MESSAGE_MAX = 500;
    private static final int STACK_TRACE_MAX = 16384;
    private static final int MODULE_NAME_MAX = 64;
    private static final int DETAIL_MAX = 256;
    private static final int LOCATION_MAX = 256;
    private static final int OCCURRENCES_MAX = 1000000;
    private static final double TPS_MAX = 100.0;
    private static final long MEMORY_MB_MAX = 10000000L;
    private static final long ONLINE_PLAYERS_MAX = 100000L;
    private static final long SEEN_AT_MAX = 8640000000000000L;

    /** The fingerprint width the collector's {@code Integer.toHexString} output is padded to. */
    private static final int FINGERPRINT_WIDTH = 8;

    private AgentEventEncoders() {
    }

    /**
     * The values an {@code error.report} payload is built from, as the session plan maps them out
     * of the error-report collector's report.
     *
     * <p>The nullable accessors are nullable in the contract too: a missing module, player,
     * location or snapshot value is sent as JSON null rather than as an empty string or a zero. The
     * collector's memory percentage is deliberately absent — the contract carries absolute
     * megabytes, and nothing else from the v1 payload survives (no error code, no
     * {@code isFramework}, no player UUID).</p>
     */
    interface ErrorReportView {
        /** @return the collector's hexadecimal fingerprint, one to eight digits */
        String fingerprint();

        /** @return the exception's binary class name */
        String exceptionType();

        /** @return the already-redacted exception message */
        String message();

        /** @return the already-redacted stack trace */
        String stackTrace();

        /** @return the owning module's name, or null when the report is not a module's */
        String moduleName();

        /**
         * @return the collector's trigger type name ({@code COMMAND}, {@code EVENT},
         *         {@code SCHEDULED}, {@code AOP} or {@code UNCAUGHT}), or null
         */
        String triggerKind();

        /** @return the already-redacted trigger detail, or null */
        String triggerDetail();

        /** @return the player's name, or null when no player is involved */
        String playerName();

        /** @return where the error was raised, or null */
        String location();

        /** @return current server TPS, or null when unknown */
        Double tps();

        /** @return used heap in megabytes, or null when unknown */
        Long memUsedMb();

        /** @return maximum heap in megabytes, or null when unknown */
        Long memMaxMb();

        /** @return the online-player count, or null when unknown */
        Long onlinePlayers();

        /** @return how often the group has been seen */
        int occurrences();

        /** @return when the group was first seen, in milliseconds */
        long firstSeenAt();

        /** @return when the group was last seen, in milliseconds */
        long lastSeenAt();
    }

    /**
     * Encodes a {@code player.command} payload.
     *
     * @param normalisedName the command name after the framework's normalisation (leading slash and
     *                       namespace removed, lower-cased), or null
     * @return {@code { name }} when the name satisfies the schema, otherwise null
     */
    static JsonObject playerCommand(String normalisedName) {
        if (normalisedName == null || !COMMAND_NAME.matcher(normalisedName).matches()) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("name", normalisedName);
        return payload;
    }

    /**
     * Encodes the payload shared by {@code player.join} and {@code player.leave}.
     *
     * @param uuid the player's UUID in canonical lower-case hyphenated form, or null
     * @param name the player's name, including a Floodgate prefix, or null
     * @return {@code { playerUuid, playerName }} when both satisfy the contract, otherwise null
     */
    static JsonObject playerRef(String uuid, String name) {
        if (uuid == null || name == null) {
            return null;
        }
        if (!ContractPrimitives.MC_UUID.matcher(uuid).matches()
                || !ContractPrimitives.MC_NAME.matcher(name).matches()) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("playerUuid", uuid);
        payload.addProperty("playerName", name);
        return payload;
    }

    /**
     * Encodes an {@code error.report} payload.
     *
     * <p>Bounds are applied, not rejected: text is cut to its contract length, occurrence counts
     * below one become one, and a player name that cannot satisfy the schema becomes JSON null,
     * because losing one optional field is better than losing the report. Only a payload that could
     * never be valid — a fingerprint that is not hexadecimal, an empty exception type — returns
     * null.</p>
     *
     * @param view the report's values, or null
     * @return the payload, or null when it cannot be made valid
     */
    static JsonObject errorReport(ErrorReportView view) {
        if (view == null) {
            return null;
        }
        String fingerprint = fingerprint(view.fingerprint());
        if (fingerprint == null) {
            return null;
        }
        String exceptionType = optionalText(cut(view.exceptionType(), EXCEPTION_TYPE_MAX));
        if (exceptionType == null) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("fingerprint", fingerprint);
        payload.addProperty("exceptionType", exceptionType);
        payload.addProperty("message", cut(view.message(), MESSAGE_MAX));
        payload.addProperty("stackTrace", cut(view.stackTrace(), STACK_TRACE_MAX));
        addNullableText(payload, "moduleName", optionalText(cut(view.moduleName(), MODULE_NAME_MAX)));

        JsonObject trigger = new JsonObject();
        trigger.addProperty("kind", triggerKind(view.triggerKind()));
        trigger.add("detail", jsonOf(optionalText(cut(view.triggerDetail(), DETAIL_MAX))));
        payload.add("trigger", trigger);

        payload.add("playerName", jsonOf(playerName(view.playerName())));
        addNullableText(payload, "location", optionalText(cut(view.location(), LOCATION_MAX)));
        payload.add("snapshot", snapshot(view));
        payload.addProperty("occurrences", Math.max(1, Math.min(OCCURRENCES_MAX, view.occurrences())));
        payload.addProperty("firstSeenAt", seenAt(view.firstSeenAt()));
        payload.addProperty("lastSeenAt", seenAt(view.lastSeenAt()));
        return payload;
    }

    /**
     * Left-pads the collector's fingerprint with zeros to the contract's minimum width without
     * changing it otherwise, so a panel error group keeps the same id across restarts and versions.
     *
     * @param raw the collector's fingerprint, or null
     * @return a lower-case hexadecimal fingerprint of at least eight digits, or null
     */
    private static String fingerprint(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        StringBuilder padded = new StringBuilder(FINGERPRINT_WIDTH);
        for (int index = value.length(); index < FINGERPRINT_WIDTH; index++) {
            padded.append('0');
        }
        padded.append(value);
        String candidate = padded.toString();
        return FINGERPRINT.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * Clamps a snapshot to the contract's shape: four keys, each a number or JSON null.
     *
     * @param view the report's values
     * @return the snapshot object
     */
    private static JsonObject snapshot(ErrorReportView view) {
        JsonObject snapshot = new JsonObject();
        snapshot.add("tps", tps(view.tps()));
        snapshot.add("memUsedMb", megabytes(view.memUsedMb()));
        snapshot.add("memMaxMb", megabytes(view.memMaxMb()));
        snapshot.add("onlinePlayers", onlinePlayers(view.onlinePlayers()));
        return snapshot;
    }

    private static JsonElement tps(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive(Math.max(0.0, Math.min(TPS_MAX, value.doubleValue())));
    }

    private static JsonElement megabytes(Long value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive(Math.max(0L, Math.min(MEMORY_MB_MAX, value.longValue())));
    }

    private static JsonElement onlinePlayers(Long value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        return new JsonPrimitive(Math.max(0L, Math.min(ONLINE_PLAYERS_MAX, value.longValue())));
    }

    /**
     * Maps a trigger type onto the contract's enumeration.
     *
     * <p>The collector stores {@code TriggerContext.TriggerType#name()}: {@code COMMAND},
     * {@code EVENT}, {@code SCHEDULED}, {@code AOP} and {@code UNCAUGHT}. The contract knows only
     * {@code event}, {@code command}, {@code scheduler} and {@code other}, so an AOP interception
     * and an uncaught exception report as {@code other}, and so does anything else unknown.
     * Matching ignores case, so the contract's own names pass through as they are.</p>
     *
     * @param raw the collector's trigger type, or null
     * @return one of {@code event}, {@code command}, {@code scheduler}, {@code other}
     */
    private static String triggerKind(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("command".equals(value) || "event".equals(value)) {
            return value;
        }
        if ("scheduler".equals(value) || "scheduled".equals(value)) {
            return "scheduler";
        }
        return "other";
    }

    /**
     * Keeps a player name only when the contract's name pattern accepts it.
     *
     * @param raw the player's name, or null
     * @return the name, or null when it cannot be sent
     */
    private static String playerName(String raw) {
        if (raw == null || !ContractPrimitives.MC_NAME.matcher(raw).matches()) {
            return null;
        }
        return raw;
    }

    /**
     * Cuts a string to a number of code points, so a supplementary character is never split into an
     * unpaired surrogate.
     *
     * @param value the text, possibly null
     * @param maxCodePoints the largest allowed length in code points
     * @return the text, cut when longer; an empty string for null
     */
    private static String cut(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * Trims a short field and turns a blank one into null, which the contract allows where an empty
     * string would fail its {@code minLength}.
     *
     * @param value the already cut text, possibly null
     * @return the trimmed text, or null when blank
     */
    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void addNullableText(JsonObject payload, String key, String value) {
        payload.add(key, jsonOf(value));
    }

    private static JsonElement jsonOf(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    private static long seenAt(long value) {
        return Math.max(0L, Math.min(SEEN_AT_MAX, value));
    }
}
