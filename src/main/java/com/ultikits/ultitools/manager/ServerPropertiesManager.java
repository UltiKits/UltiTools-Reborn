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
     * <p>
     * 为什么要在 {@link #setProperty(String, String)} 旁边多这一个：那个布尔值是有损的。
     * {@code false} 可能是「键不在白名单」「没有 server.properties 可写」或「写入本身失败」，
     * 三种处置完全不同的情况被压成了同一个值。批量路径必须告诉调用方是哪一种，
     * 所以真实结果在这里产生，{@code setProperty} 通过收窄它来保持原有契约。
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

    /** What actually happened to one key. 一个键的真实去向。 */
    private enum WriteOutcome {
        /** Written to disk. 已写入。 */
        WRITTEN,
        /** Not on {@link #SAFE_KEYS}; never attempted. 不在白名单，根本没尝试。 */
        REJECTED,
        /** On the whitelist, but reading or writing the file failed. 在白名单里，但读写失败。 */
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
     * <p>
     * 一次 {@code set_all} 的结果。{@link #isSuccess()} 的含义是
     * <em>调用方要求写的每一个键现在都在磁盘上</em>，也就是单键 {@code set} 早就在报的那件事的批量版。
     * 值为 JSON {@code null} 的键不在这个承诺范围内 —— {@code null} 读作「这个别动」，
     * 因此它只被记进 {@link #getSkipped()} 以便可见，不会让整批失败。
     */
    @ApiStatus.Internal
    public static final class SetAllResult {
        private final List<String> updated;
        private final List<String> rejected;
        private final List<String> failed;
        private final List<String> skipped;

        /**
         * Public so callers outside this package can build one. Each list is copied before
         * being wrapped — {@code unmodifiableList} is a view, so wrapping the caller's list
         * directly would leave this "immutable" object mutable through the original reference.
         * <p>
         * 公开是为了包外也能构造。每个列表都先复制再包装 —— {@code unmodifiableList} 是视图，
         * 直接包装调用方的列表会让这个「不可变」对象仍能通过原引用被改。
         */
        public SetAllResult(List<String> updated, List<String> rejected, List<String> failed, List<String> skipped) {
            this.updated = Collections.unmodifiableList(new ArrayList<>(updated));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(rejected));
            this.failed = Collections.unmodifiableList(new ArrayList<>(failed));
            this.skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
        }

        /** Keys written to disk. 已写入磁盘的键。 */
        public List<String> getUpdated() { return updated; }

        /** Keys refused because they are not on the whitelist. 因不在白名单而被拒的键。 */
        public List<String> getRejected() { return rejected; }

        /** Whitelisted keys whose write failed. 在白名单里但写入失败的键。 */
        public List<String> getFailed() { return failed; }

        /** Keys whose value was an explicit JSON null. 值为 JSON null 因而被跳过的键。 */
        public List<String> getSkipped() { return skipped; }

        /** Every requested key was written. 要求写的键全部写成功。 */
        public boolean isSuccess() {
            return rejected.isEmpty() && failed.isEmpty();
        }

        /**
         * One line naming what went wrong, for a log record or an error response.
         * Returns {@code null} when nothing went wrong.
         * <p>
         * 一句话说明哪里没成，用于日志或错误响应；全部成功时返回 {@code null}。
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
     * <p>
     * 应用一批属性、把结果发回面板，并把结果交还给调用方。
     * <b>返回值才是重点</b>：{@code handleSetAll} 用不到它（它只负责拼一条消息），
     * 但 {@code fileName: server_properties} 的 {@code update_config} 也走这里，
     * 而它的 {@code config_update_response} 要区分「已下发」和「已生效」。
     * 在这个方法有返回值之前，那条路径没有任何途径知道答案，只能无条件报成功。
     *
     * @param values key → value, an explicit JSON null meaning "leave this key alone"
     * @return what happened to each key
     */
    public SetAllResult applySetAll(JsonObject values) {
        List<String> updated = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String key : values.keySet()) {
            if (values.get(key).isJsonNull()) {
                skipped.add(key);
                continue;
            }
            switch (writeProperty(key, values.get(key).getAsString())) {
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

        SetAllResult result = new SetAllResult(updated, rejected, failed, skipped);
        warnIfIncomplete(result);
        sendResponse(buildSetAllResponse(result));
        return result;
    }

    private JsonObject buildSetAllResponse(SetAllResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set_all");
        response.addProperty("success", result.isSuccess());
        // updated 保持数字，面板一直读的是这个字段；被挡下的键走新增的数组字段。
        response.addProperty("updated", result.getUpdated().size());
        response.add("rejected", toJsonArray(result.getRejected()));
        response.add("failed", toJsonArray(result.getFailed()));
        response.add("skipped", toJsonArray(result.getSkipped()));
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
     * <p>
     * 上面那条响应是给面板读的，这条是给服主读的。两边都要有：
     * 否则改了个设置没生效的人，面板和服务器日志两头都查不到原因。
     */
    private void warnIfIncomplete(SetAllResult result) {
        String failure = result.describeFailure();
        if (failure == null) return;
        // 这个管理器由 initWebSocketManagers() 在 onEnable 里构造，生产环境下单例一定就绪；
        // 判空是为了让纯文件逻辑的单元测试不必先装一个全局单例。
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
