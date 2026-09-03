package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * Safe server.properties manager for remote editing via WebSocket.
 * Only exposes a curated whitelist of non-sensitive keys.
 */
@ApiStatus.Internal
public class ServerPropertiesManager {
    private final File serverRoot;
    private UltiPanelWebSocketClient webSocketClient;

    private static final Set<String> SAFE_KEYS = new HashSet<>(Arrays.asList(
        "motd", "max-players", "view-distance", "simulation-distance",
        "spawn-protection", "difficulty", "gamemode", "pvp",
        "allow-nether", "allow-flight", "spawn-animals", "spawn-monsters",
        "spawn-npcs", "enable-command-block"
    ));

    public ServerPropertiesManager(File serverRoot) {
        this.serverRoot = serverRoot;
    }

    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    public Map<String, String> getSafeProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        File propsFile = new File(serverRoot, "server.properties");
        if (!propsFile.exists()) return result;

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            props.load(fis);
        } catch (IOException e) {
            return result;
        }

        for (String key : SAFE_KEYS) {
            String value = props.getProperty(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    public boolean setProperty(String key, String value) {
        return writeProperty(key, value) == WriteOutcome.WRITTEN;
    }

    /**
     * Why this exists next to {@link #setProperty(String, String)}: the boolean is lossy.
     * A {@code false} could mean "the key is not on the whitelist", "there is no
     * server.properties to write to", or "the write itself failed" — three situations
     * with three different fixes, collapsed into one value. The batch path has to tell
     * the caller which one happened, so the real outcome is produced here and
     * {@code setProperty} keeps its original contract by narrowing it.
     */
    private WriteOutcome writeProperty(String key, String value) {
        if (!SAFE_KEYS.contains(key)) return WriteOutcome.REJECTED;

        File propsFile = new File(serverRoot, "server.properties");
        if (!propsFile.exists()) return WriteOutcome.FAILED;

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propsFile)) {
            props.load(fis);
        } catch (IOException e) {
            return WriteOutcome.FAILED;
        }

        props.setProperty(key, value);

        try (FileOutputStream fos = new FileOutputStream(propsFile)) {
            props.store(fos, null);
        } catch (IOException e) {
            return WriteOutcome.FAILED;
        }
        return WriteOutcome.WRITTEN;
    }

    /** What actually happened to one key. */
    private enum WriteOutcome {
        /** Written to disk. */
        WRITTEN,
        /** Not on {@link #SAFE_KEYS}; never attempted. */
        REJECTED,
        /** On the whitelist, but reading or writing the file failed. */
        FAILED
    }

    /**
     * Outcome of one {@code set_all} batch.
     * <p>
     * {@link #isSuccess()} means <em>every key the caller asked for is now on disk</em>,
     * which is the batch analogue of what {@code action: "set"} already reports for a
     * single key. Keys carrying an explicit JSON {@code null} are not part of that
     * promise — a {@code null} reads as "leave this one alone", so it is recorded in
     * {@link #getSkipped()} for visibility but never fails the batch.
     */
    @ApiStatus.Internal
    public static final class SetAllResult {
        private final List<String> updated;
        private final List<String> rejected;
        private final List<String> failed;
        private final List<String> skipped;
        private final List<String> malformed;

        /**
         * Public so callers outside this package can build one. Each list is copied before
         * being wrapped — {@code unmodifiableList} is a view, so wrapping the caller's list
         * directly would leave this "immutable" object mutable through the original reference.
         */
        public SetAllResult(List<String> updated, List<String> rejected, List<String> failed,
                            List<String> skipped, List<String> malformed) {
            this.updated = Collections.unmodifiableList(new ArrayList<>(updated));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(rejected));
            this.failed = Collections.unmodifiableList(new ArrayList<>(failed));
            this.skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
            this.malformed = Collections.unmodifiableList(new ArrayList<>(malformed));
        }

        /** Keys written to disk. */
        public List<String> getUpdated() { return updated; }

        /** Keys refused because they are not on the whitelist. */
        public List<String> getRejected() { return rejected; }

        /** Whitelisted keys whose write failed. */
        public List<String> getFailed() { return failed; }

        /** Keys whose value was an explicit JSON null. */
        public List<String> getSkipped() { return skipped; }

        /** Keys whose value was not a JSON primitive. */
        public List<String> getMalformed() { return malformed; }

        /** Every requested key was written. */
        public boolean isSuccess() {
            return rejected.isEmpty() && failed.isEmpty() && malformed.isEmpty();
        }

        /**
         * One line naming what went wrong, for a log record or an error response.
         * Returns {@code null} when nothing went wrong.
         * <p>
         * The three categories stay separate because they need three different actions:
         * a rejected key means stop asking for it, a failed key means look at the disk,
         * and a malformed key means fix the payload. Collapsing them into one label would
         * send the reader looking in the wrong place — which is the failure mode this
         * whole change exists to remove.
         */
        public String describeFailure() {
            if (isSuccess()) return null;
            StringBuilder sb = new StringBuilder("server.properties 批量设置未完全生效");
            if (!rejected.isEmpty()) {
                sb.append("；不在白名单因而被拒的键: ").append(String.join(", ", rejected));
            }
            if (!failed.isEmpty()) {
                sb.append("；写入失败的键: ").append(String.join(", ", failed));
            }
            if (!malformed.isEmpty()) {
                sb.append("；值不是字符串或数字因而无法写入的键: ").append(String.join(", ", malformed));
            }
            return sb.toString();
        }
    }

    /**
     * Handle WebSocket message for server properties operations.
     *
     * @param data the message data
     */
    public void handleServerProperties(JsonObject data) {
        if (data == null) return;

        String action = data.has("action") && !data.get("action").isJsonNull()
            ? data.get("action").getAsString() : "get";

        if ("get".equals(action)) {
            handleGet();
        } else if ("set".equals(action)) {
            handleSet(data);
        } else if ("set_all".equals(action)) {
            handleSetAll(data);
        }
    }

    private void handleGet() {
        Map<String, String> props = getSafeProperties();
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        JsonObject payload = new JsonObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            payload.addProperty(entry.getKey(), entry.getValue());
        }
        response.add("data", payload);
        sendResponse(response);
    }

    private void handleSet(JsonObject data) {
        String key = data.has("key") ? data.get("key").getAsString() : null;
        String value = data.has("value") ? data.get("value").getAsString() : null;
        if (key == null || value == null) return;

        boolean success = setProperty(key, value);
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set");
        response.addProperty("success", success);
        response.addProperty("key", key);
        sendResponse(response);
    }

    private void handleSetAll(JsonObject data) {
        JsonObject values = data.has("values") && data.get("values").isJsonObject()
            ? data.getAsJsonObject("values") : null;
        if (values == null) return;

        applySetAll(values);
    }

    /**
     * Apply a batch of properties, report it over the socket, and hand the outcome back.
     * <p>
     * The return value is the point. {@code handleSetAll} does not need it — it only ever
     * builds a message — but {@code update_config} with
     * {@code fileName: server_properties} routes through here too, and its
     * {@code config_update_response} is supposed to distinguish "delivered" from
     * "took effect". Before this returned anything, that path had no way to learn the
     * answer and reported success unconditionally.
     *
     * @param values key → value, an explicit JSON null meaning "leave this key alone"
     * @return what happened to each key
     */
    public SetAllResult applySetAll(JsonObject values) {
        List<String> updated = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> malformed = new ArrayList<>();

        for (String key : values.keySet()) {
            JsonElement value = values.get(key);
            if (value.isJsonNull()) {
                skipped.add(key);
                continue;
            }
            // server.properties is a flat string table, so anything that is not a JSON
            // primitive is a malformed request rather than a value. Gson does not fail
            // uniformly on those, which is why the guard has to come first:
            // a JsonObject throws UnsupportedOperationException, an empty or multi-element
            // JsonArray throws IllegalStateException, and a single-element JsonArray does
            // not throw at all — it silently unwraps, so {"motd": ["Hello"]} would have
            // been written as motd=Hello. One malformed key used to abort the whole batch
            // before any response was built, which is the same "nobody can tell why"
            // shape this issue is about.
            if (!value.isJsonPrimitive()) {
                malformed.add(key);
                continue;
            }
            switch (writeProperty(key, value.getAsString())) {
                case WRITTEN:
                    updated.add(key);
                    break;
                case REJECTED:
                    rejected.add(key);
                    break;
                default:
                    failed.add(key);
                    break;
            }
        }

        SetAllResult result = new SetAllResult(updated, rejected, failed, skipped, malformed);
        warnIfIncomplete(result);
        sendResponse(buildSetAllResponse(result));
        return result;
    }

    private JsonObject buildSetAllResponse(SetAllResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set_all");
        response.addProperty("success", result.isSuccess());
        // "updated" stays a number — the panel has always read this field; blocked keys
        // go through the newly added array fields.
        response.addProperty("updated", result.getUpdated().size());
        response.add("rejected", toJsonArray(result.getRejected()));
        response.add("failed", toJsonArray(result.getFailed()));
        response.add("skipped", toJsonArray(result.getSkipped()));
        response.add("malformed", toJsonArray(result.getMalformed()));
        return response;
    }

    private static JsonArray toJsonArray(List<String> keys) {
        JsonArray array = new JsonArray();
        for (String key : keys) {
            array.add(key);
        }
        return array;
    }

    /**
     * The response above is what the panel reads; this is what a server operator reads.
     * Both are needed: someone changing a setting that silently does not apply otherwise
     * finds nothing on either side.
     */
    private void warnIfIncomplete(SetAllResult result) {
        String failure = result.describeFailure();
        if (failure == null) return;
        // This manager is constructed by initWebSocketManagers() in onEnable, so in
        // production the singleton is always ready by the time this runs; the null check
        // exists so a pure-file-logic unit test does not need to stand up a global
        // singleton first.
        UltiTools instance = UltiTools.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().log(Level.WARNING, failure);
        }
    }

    private void sendResponse(JsonObject response) {
        if (webSocketClient != null) {
            response.addProperty("serverId", webSocketClient.getServerId());
            webSocketClient.sendMessage(response);
        }
    }
}
