package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.PanelMessageEvent;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.manager.ServerPropertiesManager;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;
import com.ultikits.ultitools.websocket.ExponentialBackoffStrategy;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * Utility class for plugin initialization and WebSocket communication.
 * Handles account login, WebSocket connection, and message processing
 * for UltiPanel integration.
 * <br>
 * 插件初始化和WebSocket通信的实用工具类。
 * 处理UltiPanel集成的账户登录、WebSocket连接和消息处理。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class PluginInitiationUtils {
    /** WebSocket client for panel communication */
    private static UltiPanelWebSocketClient panelWS;
    /** Authentication token for API requests */
    private static TokenEntity token;

    /** {@code server_properties} 走独立管理器，不是一个真正的配置文件路径。 */
    private static final String SERVER_PROPERTIES_FILE = "server_properties";

    /**
     * 云连接是否处于「应当保持连接」的状态。
     * <p>
     * 这是整条重连链的<b>唯一开关</b>。在它存在之前，有四个地方各自独立地决定「要不要继续
     * 重连」，而谁都不是所有者：{@code UltiPanelWebSocketClient.onClose} 按每实例 5 次算、
     * {@code reinitWebSocket} 造新实例把计数清零、{@code ulticloud logout} 只清凭证根本不碰
     * 状态机、只有 {@code onDisable} 真正拆得干净。结果是 logout 之后插件仍在拿已作废的凭证
     * 持续敲面板。见 issue #181 与 #223。
     * <p>
     * 现在的规则只有一条：<b>{@code reinitWebSocket} 只在本标志为 true 时才会重建连接。</b>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean cloudEnabled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 云管理器「接线」与「拆线」的互斥锁。
     * <p>
     * {@link #cloudEnabled} 单独用是不够的：它只能表达状态，表达不了「检查与动作之间不许有人插队」。
     * {@code initializeManagers()} 读到 true 之后、真正接线之前，{@code disableCloud()} 完全可以
     * 插进来把开关置否并把监听器拆干净，随后前者继续往下又把它们装回去——logout 之后监听器
     * 还在，甚至还能继续往面板发事件。见 PR #264 的两轮评审。
     * <p>
     * 两边都持这把锁之后，二者只能整体先后发生：要么先接线再拆掉（干净），要么先拆再接而接线
     * 一侧在持锁复查时看到 false 直接返回（也干净）。
     */
    private static final Object cloudLifecycleLock = new Object();

    /** 外层 reinit 的全局上限。超过之后进入终态，需要 {@code /ulticloud login} 或重启才恢复。 */
    private static final int MAX_REINIT_ATTEMPTS = 10;

    /**
     * 外层重连（reinit 循环）的全局预算与退避。
     * <p>
     * 客户端自身那 5 次是<b>每实例</b>的上限，而 {@code reinitWebSocket} 每次都造一个新实例，
     * 于是每实例上限对整体毫无约束——这正是无界循环的成因。本策略是跨实例的，
     * 只有一次成功的 {@code onOpen} 能把它重置。
     * <p>
     * 顺带启用了 {@link ExponentialBackoffStrategy}——它此前是同包内零引用的死代码。
     */
    private static final ExponentialBackoffStrategy reinitBackoff =
            ExponentialBackoffStrategy.withMaxAttempts(MAX_REINIT_ATTEMPTS);

    /**
     * Login to UltiPanel using an existing token (from magic-link or saved token).
     * Registers or updates the server without needing username/password.
     * <br>
     * 使用现有令牌登录UltiPanel（来自魔法链接或保存的令牌）。
     *
     * @param existingToken the pre-authenticated token
     * @return true if server registration/update succeeded
     * @throws IOException if an I/O error occurs
     */
    public static boolean loginWithToken(TokenEntity existingToken) throws IOException {
        token = existingToken;
        String uuid = CommonUtils.getUltiToolsUUID();
        int port = org.bukkit.Bukkit.getServer().getPort();
        String domain = "";
        boolean ssl = true;

        try (Response uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token)) {
            if (uuidResponse.getStatus() == 404) {
                String serverName = org.bukkit.Bukkit.getServer().getName();
                if (serverName == null || serverName.trim().isEmpty()) {
                    serverName = "MC Server";
                }
                if (serverName.length() > 64) {
                    serverName = serverName.substring(0, 64);
                }
                try (Response registerResponse = HttpRequestUtils.registerServer(uuid, serverName, port, domain, ssl, token)) {
                    if (!registerResponse.isOk()) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "Server registration failed: HTTP " + registerResponse.getStatus() + " - " + registerResponse.body());
                        return false;
                    }
                }
            } else if (uuidResponse.isOk()) {
                try (Response updateResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token)) {
                    if (!updateResponse.isOk()) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING,
                            "Server update failed: HTTP " + updateResponse.getStatus() + " - " + updateResponse.body());
                        return false;
                    }
                }
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "Failed to check server status: HTTP " + uuidResponse.getStatus() + " - " + uuidResponse.body());
                return false;
            }
        }
        return true;
    }

    /**
     * Initialize websocket.
     * <br>
     * 初始化websocket。
     */
    public static void initWebsocket() throws IOException {
        if (token == null || token.getAccess_token() == null) {
            throw new IOException("Cannot initialize WebSocket: no auth token available");
        }
        if (token.isExpired()) {
            throw new IOException("Cannot initialize WebSocket: auth token has expired");
        }

        // 这里刻意**不**置位 cloudEnabled。
        //
        // 曾经写成在这里 set(true)，那是错的：reinitWebSocket 也会走到这里，于是一个
        // 正在途中的重连能把刚被 logout 关掉的状态机重新拉起来——两者跑在不同线程上，
        // 中间还隔着一次 token 刷新的网络调用，窗口可能有数秒。
        //
        // 现在只有显式动作才开启：启动时的 UltiTools.onEnable、以及 magic-link 登录成功后
        // 的 CloudAuthManager，两处都调 enableCloud()。见 issue #223 的 PR 评审。

        // 全程用局部引用挂接线，不要边写静态字段边读它：下面注册的回调是异步触发的，
        // 触发时静态 panelWS 可能已经不是这一个了。
        UltiPanelWebSocketClient client = getPanelWebsocketClient();
        panelWS = client;

        // 设置消息处理器
        client.setMessageHandler(PluginInitiationUtils::handleInboundMessage);

        // 设置连接成功处理器
        client.setOnConnectHandler(() -> onWebSocketOpened(client));

        // 设置重连耗尽处理器 — 尝试刷新令牌并重新建立连接
        client.setOnReconnectExhaustedHandler(PluginInitiationUtils::reinitWebSocket);

        // 连接到WebSocket服务器
        client.connect();
    }

    /**
     * 握手成功之后的接线动作。
     * <p>
     * <b>参数就是这次握手自己的客户端，方法体内绝不重读静态 {@code panelWS}。</b>
     * onOpen 是异步回调：跑到这里时 {@code disableCloud()} 可能已经把静态字段置空
     * （{@code /ulticloud logout}，或重连预算耗尽——后者跑在 WebSocket 线程上），
     * 也可能 {@code reinitWebSocket} 已经把它换成了另一个实例。重读静态字段的话，
     * {@code subscribeToServer} / {@code uploadConfig} / {@code uploadServerProperties}
     * 三处都会踩空——{@link #initializeManagers()} 有持锁复查护着，它前后的代码没有。
     * <p>
     * 往一个已断开的客户端发消息是安全的：{@code sendMessage} 在未连接时打一条 WARNING
     * 就返回。真正危险的是空引用，所以这里解决的是引用稳定性，不是连接状态。
     * <p>
     * 包级可见而非 private —— 只为可测。要在测试里触发它，否则得有真实的鉴权 token
     * 与真实的 WebSocket 握手；与 {@link #handleInboundMessage} 同一处理。
     */
    static void onWebSocketOpened(UltiPanelWebSocketClient client) {
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("Websocket已连接!"));

        // 握手真正成功了，这里才是「重连成功」这句话唯一站得住的位置。
        // 外层预算也只在这里清零——若在 reinitWebSocket 里清，「造出了一个客户端」
        // 就会被当成成功，预算永远用不完。见 issue #181 / #223。
        onWebSocketConnected();
        UltiTools.getInstance().getLogger().log(Level.INFO,
            "WebSocket connected to UltiPanel");

        // 订阅当前服务器
        client.subscribeToServer(client.getServerId());

        // 初始化所有管理器
        initializeManagers();

        // 上传配置
        uploadConfig(client);

        // 上传服务器属性到云端 —— 由 SERVER_PROPERTIES 能力开关决定（D-11/D-12）
        if (Capability.SERVER_PROPERTIES.isEnabled()) {
            uploadServerProperties(client);
        } else {
            logSkippedCapability(Capability.SERVER_PROPERTIES);
        }
    }
    
    /**
     * 初始化所有管理器
     */
    /**
     * The inbound-message dispatch table: message {@code type} string to the {@link InboundHandlerEntry}
     * that serves it.
     * <p>
     * Replaces what used to be a 24-case {@code switch} inside {@link #handleInboundMessage}
     * (NPath complexity 1514 against a threshold of 200 — see issue #234's coupled complexity
     * finding). A switch multiplies independent path counts by the number of branches; a lookup
     * does not, so the paths through {@link #handleInboundMessage} are now bounded by its guards
     * rather than by how many message types exist. Built once, statically, and never mutated after
     * construction — see {@link #buildInboundHandlers()}.
     * <p>
     * Not module-visible and never will be: this is framework-internal routing for the fixed set of
     * panel protocol messages. Module-facing panel messaging is a separate, deliberately narrower
     * surface (EventBus broadcast plus a single-owner request/response responder) that a later phase
     * owns. A second module-visible dispatch mechanism grown out of this table would repeat a mistake
     * this repository already has twice, in its command-executor and GUI generations.
     * <p>
     * 入站消息 {@code type} 到处理器的分发表，取代原先 24 分支的 {@code switch}
     * （NPath 复杂度 1514，阈值 200）。Switch 把独立路径数相乘，查表则不会。
     */
    private static final Map<String, InboundHandlerEntry> INBOUND_HANDLERS =
            Collections.unmodifiableMap(buildInboundHandlers());

    /**
     * A dispatch-table entry pairing a handler with the {@link Capability} that must be enabled
     * before it runs (D-10).
     * <p>
     * Exposes exactly two static factories and no capability-free construction path — this is the
     * whole point of D-10: adding a message type to {@link #INBOUND_HANDLERS} without declaring a
     * capability must fail to compile, so no single-argument overload, default, or null-tolerant
     * constructor is ever added here. {@link #of(Capability, BiConsumer)} covers the 23 entries whose
     * capability is fixed by the message {@code type} alone; {@link #resolved(Function, BiConsumer)}
     * covers {@code file_operation}, the one entry whose capability depends on the message's
     * {@code operation} field rather than its {@code type}.
     * <p>
     * 分发表条目，把处理器与「必须先启用才能运行」的 {@link Capability} 绑在一起（D-10）。只
     * 暴露两个静态工厂，没有任何绕开能力声明的构造路径——新增消息类型若不声明能力就无法编译。
     */
    static final class InboundHandlerEntry {
        private final Capability capability;
        private final Function<JsonObject, Capability> resolver;
        private final BiConsumer<JsonObject, JsonObject> handler;

        private InboundHandlerEntry(Capability capability, Function<JsonObject, Capability> resolver,
                                     BiConsumer<JsonObject, JsonObject> handler) {
            this.capability = capability;
            this.resolver = resolver;
            this.handler = handler;
        }

        /**
         * An entry whose capability is a fixed constant.
         *
         * @param capability the required capability — use {@link Capability#NONE} for protocol-level
         *                   and echo messages that carry no operator-facing policy
         * @param handler    the handler to invoke once the gate clears
         * @return the entry
         */
        static InboundHandlerEntry of(Capability capability, BiConsumer<JsonObject, JsonObject> handler) {
            if (capability == null) {
                throw new IllegalArgumentException("capability must not be null — declare Capability.NONE explicitly");
            }
            return new InboundHandlerEntry(capability, null, handler);
        }

        /**
         * An entry whose capability depends on the inbound message's own {@code data} — the
         * {@code file_operation} case, whose true capability depends on the {@code operation} field
         * (D-10's resolver case, D-09).
         *
         * @param resolver a function from the message's {@code data} to the {@link Capability} it requires
         * @param handler  the handler to invoke once the gate clears
         * @return the entry
         */
        static InboundHandlerEntry
                resolved(Function<JsonObject, Capability> resolver, BiConsumer<JsonObject, JsonObject> handler) {
            if (resolver == null) {
                throw new IllegalArgumentException("resolver must not be null — declare a Capability.of(...) entry instead");
            }
            return new InboundHandlerEntry(null, resolver, handler);
        }

        /**
         * Resolves this entry's required capability against one message's {@code data}.
         *
         * @param data the message's {@code data} object, possibly {@code null}
         * @return the required {@link Capability}
         */
        Capability resolveCapability(JsonObject data) {
            return capability != null ? capability : resolver.apply(data);
        }

        BiConsumer<JsonObject, JsonObject> getHandler() {
            return handler;
        }
    }

    /**
     * Builds {@link #INBOUND_HANDLERS}. Each entry invokes exactly the same target its former
     * {@code case} label invoked — this method is the byte-for-byte routing record of the switch it
     * replaces, not a redesign of it. {@code log_stream} and {@code log_stream_control} share one
     * {@link BiConsumer} instance, preserving the fall-through the two case labels used to express.
     *
     * @return a table from message {@code type} to the {@link InboundHandlerEntry} that serves it
     */
    private static Map<String, InboundHandlerEntry> buildInboundHandlers() {
        Map<String, InboundHandlerEntry> handlers = new HashMap<>();

        // 系统基础消息 —— 协议层/回声消息，显式声明 Capability.NONE（D-10）：从不拦截，从不记录。
        handlers.put("ping", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handlePing(message)));
        handlers.put("pong", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handlePong(data)));
        handlers.put("subscribe", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleSubscribe(data)));
        handlers.put("unsubscribe", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleUnsubscribe(data)));
        handlers.put("notification", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleNotification(data)));
        handlers.put("error", InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleError(data)));

        // 服务器监控消息
        handlers.put("server_status",
                InboundHandlerEntry.of(Capability.MONITORING, (message, data) -> handleServerStatusRequest(data)));
        handlers.put("plugin_list",
                InboundHandlerEntry.of(Capability.MONITORING, (message, data) -> handlePluginListRequest(data)));
        handlers.put("player_event",
                InboundHandlerEntry.of(Capability.PLAYER_EVENTS, (message, data) -> handlePlayerEvent(data)));
        handlers.put("metrics_data",
                InboundHandlerEntry.of(Capability.MONITORING, (message, data) -> handleMetricsRequest(data)));

        // 操作控制消息
        handlers.put("execute_command", InboundHandlerEntry.of(Capability.COMMANDS,
                (message, data) -> UltiTools.getInstance().getCommandExecutionManager().executeCommand(data)));
        handlers.put("command_result",
                InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleCommandResult(data)));
        // file_operation 的能力取决于 data.operation，不是常量 —— D-10 的 resolver 场景（D-09）。
        handlers.put("file_operation", InboundHandlerEntry.resolved(
                PluginInitiationUtils::resolveFileOperationCapability,
                (message, data) -> UltiTools.getInstance().getFileOperationManager().handleFileOperation(data)));
        handlers.put("file_operation_result",
                InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleFileOperationResult(data)));

        // 数据流消息 —— log_stream 与 log_stream_control 共享同一个处理器，
        // 这是原先两个 case 标签之间 fall-through 的等价写法。
        BiConsumer<JsonObject, JsonObject> logStreamHandler =
                (message, data) -> UltiTools.getInstance().getLogStreamManager().handleLogStreamMessage(data);
        handlers.put("log_stream", InboundHandlerEntry.of(Capability.LOGS, logStreamHandler));
        handlers.put("log_stream_control", InboundHandlerEntry.of(Capability.LOGS, logStreamHandler));
        // backup_operation 是今天的纯日志占位符，但其声明意图是产出文件的操作 —— 因此按更严格
        // 的一侧声明为 FILE_WRITE，而不是等桩实现落地时才回头改声明。
        handlers.put("backup_operation",
                InboundHandlerEntry.of(Capability.FILE_WRITE, (message, data) -> handleBackupOperation(data)));
        handlers.put("backup_progress",
                InboundHandlerEntry.of(Capability.NONE, (message, data) -> handleBackupProgress(data)));

        // 配置管理消息
        handlers.put("upload_config",
                InboundHandlerEntry.of(Capability.FILE_WRITE, (message, data) -> handleConfigUpload(data)));
        handlers.put("update_config",
                InboundHandlerEntry.of(Capability.FILE_WRITE, (message, data) -> handleConfigUpdate(data)));
        handlers.put("server_properties", InboundHandlerEntry.of(Capability.SERVER_PROPERTIES, (message, data) -> {
            if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                UltiTools.getInstance().getServerPropertiesManager().handleServerProperties(data);
            }
        }));
        handlers.put("server_properties_result", InboundHandlerEntry.of(Capability.NONE, (message, data) ->
                // Response from this plugin forwarded back by DO — ignore silently
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received server_properties_result echo — ignoring")));

        // Magic link auth messages (completion handled by HTTP polling in UltiLogin)
        handlers.put("auth_complete", InboundHandlerEntry.of(Capability.NONE, (message, data) ->
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received auth_complete message: " + (data != null ? data.toString() : "null"))));
        handlers.put("magic_link_response", InboundHandlerEntry.of(Capability.NONE, (message, data) ->
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received magic_link_response message: " + (data != null ? data.toString() : "null"))));

        return handlers;
    }

    /**
     * Resolves {@code file_operation}'s required capability from the message's {@code operation}
     * field (D-09, D-10's Pitfall 4). Delegates to {@link #resolveFileOperationCapability(String)}.
     *
     * @param data the message's {@code data} object, possibly {@code null}
     * @return the required capability
     */
    private static Capability resolveFileOperationCapability(JsonObject data) {
        String operation = data != null ? readString(data, "operation") : null;
        return resolveFileOperationCapability(operation);
    }

    /**
     * The single {@code operation} name to {@link Capability} mapping, shared between this class's
     * D-10 dispatch-table resolver above and {@code FileOperationManager}'s own action-log
     * recording (D-22, Plan 06-04 Task 1) — so the two mappings cannot drift apart. {@code list}
     * resolves to {@link Capability#FILE_READ} — listing is reading. An unrecognised or absent
     * operation also resolves to {@link Capability#FILE_READ}, the most-permitted of the three, so
     * an unknown verb reaching the dispatch table is still gated and still reaches
     * {@code handleFileOperation}'s own unsupported-operation branch.
     *
     * @param operation the {@code operation} field's value, possibly {@code null}
     * @return the required capability
     */
    public static Capability resolveFileOperationCapability(String operation) {
        if ("write".equals(operation)) {
            return Capability.FILE_WRITE;
        }
        if ("delete".equals(operation)) {
            return Capability.FILE_DELETE;
        }
        return Capability.FILE_READ;
    }

    /**
     * Package-private accessor for {@link #INBOUND_HANDLERS}, exposed only so
     * {@code PluginInitiationUtilsDispatchTableTest} can assert the table's key set and entry
     * identities without duplicating {@link #buildInboundHandlers()}'s routing record in a second
     * place. Not a registration point — the returned map is already unmodifiable.
     * <p>
     * 包级可见而非 public —— 只为可测，不是注册入口。
     *
     * @return the unmodifiable inbound dispatch table
     */
    static Map<String, InboundHandlerEntry> inboundDispatchTable() {
        return INBOUND_HANDLERS;
    }

    /**
     * Whether {@code messageType} is one of the framework's own {@link #INBOUND_HANDLERS} entries
     * — the single source {@code PanelResponderRegistry.registerResponder} consults before letting
     * a module claim a message type (D-26, WIRE-16, Plan 06-08 Task 1). Deliberately a separate,
     * narrower predicate rather than widening {@link #inboundDispatchTable()}'s visibility: that
     * accessor's own javadoc says it is test-only and not a registration point, and a boolean
     * membership check is exactly the narrower thing a registration point actually needs — it
     * cannot read, mutate, or iterate the table itself.
     * <p>
     * Exact {@code String} key membership, matching {@link #INBOUND_HANDLERS}'s own
     * {@code HashMap} key semantics: no case folding, no Unicode normalization.
     *
     * @param messageType the message type to check, possibly {@code null}
     * @return {@code true} if the framework's own dispatch table already serves this exact type
     */
    public static boolean isFrameworkOwnedType(String messageType) {
        return messageType != null && INBOUND_HANDLERS.containsKey(messageType);
    }

    /**
     * 处理面板下发的入站 WebSocket 消息。
     * <p>
     * 从 {@code initWebsocket()} 的 lambda 中提取出来，唯一目的是让它可以被单元测试直接调用：
     * 原先它是 {@code setMessageHandler} 的匿名 lambda，要构造它需要真实的鉴权 token 与真实的
     * WebSocket 客户端，畸形输入这条路径因此完全没有测试覆盖。见 issue #234。
     * <p>
     * 包级可见而非 public —— 它不是对外 API，只是为了可测。
     *
     * @param message 面板下发的消息，允许为 null
     */
    static void handleInboundMessage(JsonObject message) {
        if (message == null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[WebSocket消息处理] 收到 null 消息，已忽略");
            return;
        }

        // type 与 data 使用同一套守卫。原先这里是 message.get("type").getAsString()：
        // 缺 type 字段时 get 返回 null，.getAsString() 随即抛 NPE，而它写在 try 之外，
        // 下面那个 catch 接不住。
        //
        // 该 NPE 不会中断接收循环 —— UltiPanelWebSocketClient.onMessage 把
        // messageHandler.accept 包在自己的 try 里。但它会被记成「WebSocket消息解析失败」，
        // 而解析其实是成功的：消息被静默丢弃，诊断指向错误的方向，且那里只传了
        // e.getMessage() 没有堆栈。
        //
        // 用 isJsonPrimitive 而不是 !isJsonNull：后者只挡 JSON null，挡不住 type 是对象
        // 或数组——那种情况下 getAsString() 抛 UnsupportedOperationException。非基本类型
        // 的 type 和缺字段、JSON null、空串属于同一类畸形，应当走同一条 WARNING 分支，
        // 而不是被当成「处理消息时发生错误」记成 SEVERE。
        //
        // MessageHandlerRegistry.dispatch 用的是 !isJsonNull()，那份是死代码（见 #233），
        // 这里没有照抄它的这一点。
        String type = null;
        JsonObject data = null;
        // Tracks whether this message should reach PanelMessageEvent subscribers (WIRE-16).
        // Stays false — the safe default — unless the dispatch below explicitly earns it: an
        // entry-less (unknown) type earns it after its warning, and dispatchWithCapabilityGate's
        // return value earns it for a known type (true for Capability.NONE and for an enabled
        // capability, false for a denied one). Carrying the gate's own outcome here means the
        // gate and the publish can never disagree — there is no second, independent check.
        boolean shouldPublishEvent = false;
        try {
            if (message.has("type") && message.get("type").isJsonPrimitive()) {
                type = message.get("type").getAsString();
            }
            if (type == null || type.isEmpty()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("[WebSocket消息处理] 消息缺少有效的 type 字段，已忽略: %s",
                        new Gson().toJson(message)));
                // Early return — the type never resolved, so there is nothing a subscriber could
                // filter on. This also means the trailing publish call below is never reached.
                return;
            }

            data = message.has("data") && message.get("data").isJsonObject()
                ? message.getAsJsonObject("data") : null;

            // 记录接收到的消息处理日志
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket消息处理] 类型: %s, 开始处理", type));

            // Lookup replaces the former 24-case switch — see INBOUND_HANDLERS. Every entry
            // invokes the same target its former case label invoked; an absent entry is the same
            // "unknown type" outcome the former default branch produced.
            InboundHandlerEntry entry = INBOUND_HANDLERS.get(type);
            if (entry != null) {
                shouldPublishEvent = dispatchWithCapabilityGate(type, message, data, entry);
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("未知的消息类型: %s，消息内容: %s", type, new Gson().toJson(message)));
                // Don't send error responses to avoid feedback loops with server
                // Unknown to the framework's own dispatch table is exactly the case WIRE-16
                // exists to serve — a module's own responder for a type the framework does not
                // own. No capability gate applies (there is no entry to resolve one from), so
                // this is unconditionally publishable.
                shouldPublishEvent = true;
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.SEVERE,
                String.format("处理消息类型 %s 时发生错误: %s", type, e.getMessage()), e);
            // Don't send error responses to avoid feedback loops with server
            // shouldPublishEvent stays at its default (false): an exception mid-dispatch means
            // the framework cannot say the message was actually handled, so this conservatively
            // does not publish rather than guessing.
        }

        // 记录消息处理完成日志
        UltiTools.getInstance().getLogger().log(Level.FINE,
            String.format("[WebSocket消息处理] 类型: %s, 处理完成", type));

        // One added statement at the end of the bridge (D-29, issue #237, WIRE-16). Appended
        // rather than inserted: removing this call must leave the 24 pre-existing message types
        // working exactly as they do today. Only reached when type resolved (the early return
        // above skips it for a malformed type) and shouldPublishEvent was earned above.
        if (shouldPublishEvent) {
            publishPanelMessageEvent(type, message, data);
        }
    }

    /**
     * Bridges an inbound panel message the framework has already handled onto the module-facing
     * {@link EventBus} (WIRE-16). This is the single publish site — see the dispatch-table call
     * site in {@link #handleInboundMessage} for the only place this is invoked.
     * <p>
     * {@link EventBus#publishAsync} was considered and rejected: it submits to an async worker
     * pool and never reaches the main thread, so it does not address Paper's AsyncCatcher at
     * all — it only keeps the WebSocket I/O thread unblocked. A Minecraft module's handler
     * touches Bukkit API by definition, so the real choice here was main-thread versus
     * not-main-thread, not sync-dispatch versus async-dispatch; only
     * {@code Bukkit.getScheduler().runTask(...)} puts a handler on the main thread. The whole
     * helper body is wrapped in a catch so a missing scheduler (no Bukkit server booted, as in a
     * plain unit test) or a missing {@link EventBus} can never break the inbound message path —
     * both are logged no-ops.
     *
     * @param type    the resolved message type
     * @param message the full inbound envelope
     * @param data    the message's {@code data} object, possibly {@code null}
     */
    private static void publishPanelMessageEvent(String type, JsonObject message, JsonObject data) {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            EventBus eventBus = instance.getEventBus();
            if (eventBus == null) {
                return;
            }
            Bukkit.getScheduler().runTask(instance, () -> {
                // Two long reads and a comparison on the fast path — no allocation, no logging,
                // until the slow branch below is actually taken.
                long startNanos = System.nanoTime();
                eventBus.publish(new PanelMessageEvent(type, data, message));
                long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
                if (elapsedMillis > SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS) {
                    // Times the whole publish, not an individual handler: EventBus.publish
                    // iterates its subscriber list internally and this bridge cannot see inside
                    // that loop without changing EventBus, a shared class this plan does not
                    // touch. This warning can therefore only say that some subscriber to this
                    // event type is slow — never which one, and it fires once per slow publish
                    // regardless of how many subscribers contributed to the elapsed time.
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        String.format("[PanelMessageEvent] Subscriber(s) to type '%s' took %dms "
                            + "to run (threshold %dms) — a slow handler on the main thread can "
                            + "drag server tick rate",
                            type, elapsedMillis, SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS));
                }
            });
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "[PanelMessageEvent] Failed to publish event for type " + type, e);
        }
    }

    /**
     * The elapsed-time threshold above which a {@link PanelMessageEvent} publish is considered
     * slow enough to warn about, in milliseconds. Set below one server tick (50ms at the nominal
     * 20 TPS) so a subscriber costing a visible fraction of the tick budget is named before
     * players feel it — this constant is the runtime half of D-24's mitigation; {@link
     * PanelMessageEvent}'s javadoc is the other half, stating the contract a reader sees before
     * ever hitting this warning at runtime.
     */
    private static final long SLOW_PANEL_EVENT_HANDLER_THRESHOLD_MILLIS = 20L;

    /**
     * The single enforcement point for every inbound capability (D-10). Resolves the entry's
     * required capability against {@code data}; {@link Capability#NONE} runs the handler with no
     * check and no action-log entry. Otherwise: enabled runs the handler and records one
     * {@link RemoteActionLog.Verdict#ALLOWED} entry; disabled sends one {@code capability_denied}
     * reply and records one {@link RemoteActionLog.Verdict#DENIED} entry — the handler is never
     * invoked on the denied path.
     *
     * @param type    the message type, used for the action-log {@code action} and the refusal payload
     * @param message the full inbound message
     * @param data    the message's {@code data} object, possibly {@code null}
     * @param entry   the dispatch-table entry that serves this type
     * @return whether the message should also reach {@link PanelMessageEvent} subscribers —
     *         {@code true} for {@link Capability#NONE} and for an enabled capability, {@code
     *         false} for a denied one. The caller carries this straight into the publish decision
     *         so the gate and the publish can never disagree (see {@link #handleInboundMessage}).
     */
    private static boolean dispatchWithCapabilityGate(String type, JsonObject message, JsonObject data,
                                                     InboundHandlerEntry entry) {
        Capability capability = entry.resolveCapability(data);
        if (capability == Capability.NONE) {
            entry.getHandler().accept(message, data);
            return true;
        }
        if (capability.isEnabled()) {
            entry.getHandler().accept(message, data);
            recordAction(capability, type, data, RemoteActionLog.Verdict.ALLOWED, null);
            return true;
        }
        sendCapabilityRefusal(type, data, capability);
        recordAction(capability, type, data, RemoteActionLog.Verdict.DENIED, capability.refusalMessage());
        return false;
    }

    /**
     * Sends one {@code capability_denied} outbound message naming the config key, the config file,
     * the refusal reason, and echoing whichever correlation id the inbound message carried
     * ({@code commandId}, {@code operationId} or {@code requestId}) so the panel can correlate the
     * refusal with the request that caused it. Not reusing the existing {@code error} message type
     * — see {@link #handleInboundMessage}'s own comment on why unsolicited {@code error} replies are
     * avoided on this path. A logged no-op when no client is connected.
     *
     * @param type       the inbound message type that was refused
     * @param data       the message's {@code data} object, possibly {@code null}
     * @param capability the capability that refused it
     */
    private static void sendCapabilityRefusal(String type, JsonObject data, Capability capability) {
        if (panelWS == null) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Capability refusal for " + type + " not sent — no WebSocket client connected");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("capability", capability.name());
        payload.addProperty("configKey", capability.getConfigPath());
        payload.addProperty("configFile", "plugins/UltiTools/config.yml");
        payload.addProperty("reason", capability.refusalMessage());

        if (data != null) {
            copyIfPresent(data, payload, "commandId");
            copyIfPresent(data, payload, "operationId");
            copyIfPresent(data, payload, "requestId");
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "capability_denied");
        response.add("data", payload);
        response.addProperty("serverId", panelWS.getServerId());
        panelWS.sendMessage(response);
    }

    /** Copies {@code field} from {@code source} to {@code target} only when present and non-null. */
    private static void copyIfPresent(JsonObject source, JsonObject target, String field) {
        String value = readString(source, field);
        if (value != null) {
            target.addProperty(field, value);
        }
    }

    /**
     * Records one action-log entry for a capability-gated inbound message. A {@code null}
     * {@code UltiTools.getInstance().getRemoteActionLog()} is a silent no-op — the existing
     * inbound-message tests mock {@code UltiTools} and return null for it.
     */
    private static void recordAction(Capability capability, String type, JsonObject data,
                                      RemoteActionLog.Verdict verdict, String reason) {
        RemoteActionLog log = UltiTools.getInstance().getRemoteActionLog();
        if (log == null) {
            return;
        }
        String action = resolveActionLogAction(type, data);
        String target = resolveActionLogTarget(type, data);
        String actor = resolveActor(data);
        RemoteActionLog.Entry entry = verdict == RemoteActionLog.Verdict.ALLOWED
                ? RemoteActionLog.Entry.allowed(capability, action, target, actor)
                : RemoteActionLog.Entry.denied(capability, action, target, actor, reason);
        log.record(entry);
    }

    /** The action-log {@code action} field — the message type, extended with the resolved sub-operation for {@code file_operation}. */
    private static String resolveActionLogAction(String type, JsonObject data) {
        if ("file_operation".equals(type) && data != null) {
            String operation = readString(data, "operation");
            if (operation != null) {
                return type + ":" + operation;
            }
        }
        return type;
    }

    /** The action-log {@code target} field — the command text, file path, or message type otherwise. */
    private static String resolveActionLogTarget(String type, JsonObject data) {
        if (data == null) {
            return type;
        }
        if ("execute_command".equals(type)) {
            String command = readString(data, "command");
            return command != null ? command : type;
        }
        if ("file_operation".equals(type)) {
            String path = readString(data, "path");
            return path != null ? path : type;
        }
        return type;
    }

    /**
     * The action-log {@code actor} field — the inbound {@code executor} field verbatim, or the
     * literal {@code "panel"} when absent. The framework cannot attribute a remote command to an
     * individual panel operator today (see {@link RemoteActionLog.Entry}'s javadoc), so this never
     * invents a per-operator identity.
     */
    private static String resolveActor(JsonObject data) {
        String executor = data != null ? readString(data, "executor") : null;
        return executor != null ? executor : "panel";
    }

    /**
     * 把所有 WebSocket 管理器接到当前连接上。
     * <p>
     * 本方法挂在 {@code onConnectHandler} 上，而 {@code /ulticloud logout} 之后仍可能有一次
     * 在途的握手落地。不设防的话，{@code disableCloud()} 刚摘掉的监听器会被这次迟到的 onOpen
     * 原样装回去——这正是 #181/#223 里「谁都不是所有者」那个毛病换个地方重现。
     * <p>
     * <b>光检查 {@link #cloudEnabled} 是不够的。</b>那只是一次锁外的读：读到 true 之后、
     * 真正接线之前，{@code disableCloud()} 完全可以插进来把开关置否并拆干净，然后本方法
     * 继续往下把监听器又装回去。所以接线与拆线必须落在同一把 {@link #cloudLifecycleLock} 上，
     * 并在<b>持锁期间</b>复查开关。见 PR #264 的两轮评审。
     * <p>
     * 包级可见而非 private —— 只为可测。
     */
    static void initializeManagers() {
        synchronized (cloudLifecycleLock) {
            // 持锁复查：disableCloud() 拿的是同一把锁，所以到这里为止它要么还没开始、
            // 要么已经整个跑完，不可能卡在中间。
            if (!cloudEnabled.get()) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "云连接已关闭，跳过管理器初始化（这是一次登出之后迟到的握手）");
                return;
            }
            wireManagers();
        }
    }

    /**
     * {@link #initializeManagers()} 的实际接线动作。调用方必须持有 {@link #cloudLifecycleLock}。
     * <p>
     * D-11/D-12：四个出站能力（{@code monitoring}/{@code logs}/{@code player-events}/
     * {@code server-properties}）在这里由 {@link Capability#isEnabled()} 决定是否<b>开始采集</b>，
     * 而不是采集之后在发送出口丢弃——后者会让数据已经被收集进内存，只是没被传走，D-12 明确否决
     * 这种「exposed but not transmitted」的形状。每一处 client 引用装配调用都刻意保持无条件：
     * 装一个 client 引用本身不启动任何采集，让它无条件执行才能保证每个管理器 getter
     * 永远非空、每个管理器永远存在（D-11）——分发表里有两处对管理器 getter 的解引用没有空判断。
     */
    private static void wireManagers() {
        try {
            // 初始化服务器监控管理器 —— 引用装配与「是否开始监控」分离，见方法javadoc
            UltiTools.getInstance().getServerMonitorManager().setWebSocketClient(panelWS);
            if (Capability.MONITORING.isEnabled()) {
                // 启动监控（会立即发送状态并开始定期发送）
                UltiTools.getInstance().getServerMonitorManager().startMonitoring();
            } else {
                logSkippedCapability(Capability.MONITORING);
            }

            // 初始化命令执行管理器
            UltiTools.getInstance().getCommandExecutionManager().setWebSocketClient(panelWS);

            // 初始化文件操作管理器
            UltiTools.getInstance().getFileOperationManager().setWebSocketClient(panelWS);

            // 初始化服务器属性管理器
            if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                UltiTools.getInstance().getServerPropertiesManager().setWebSocketClient(panelWS);
            }

            // 初始化日志流管理器 —— logs 关闭时 SystemLogHandler 从不挂上根 logger
            if (UltiTools.getInstance().getLogStreamManager() != null) {
                if (Capability.LOGS.isEnabled()) {
                    UltiTools.getInstance().getLogStreamManager().initialize(panelWS);
                } else {
                    logSkippedCapability(Capability.LOGS);
                }
            }

            // 初始化玩家事件管理器 —— player-events 关闭时 Bukkit 监听器从不被注册
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                if (Capability.PLAYER_EVENTS.isEnabled()) {
                    UltiTools.getInstance().getPlayerEventManager().initialize(panelWS);
                } else {
                    logSkippedCapability(Capability.PLAYER_EVENTS);
                }
            }

            UltiTools.getInstance().getLogger().log(Level.FINE, "所有WebSocket管理器已初始化并启动监控");
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "初始化管理器时出错: " + e.getMessage(), e);
        }
    }

    /**
     * 为一个被跳过的出站能力记一条 INFO，说明是哪个能力、哪个配置键导致的跳过。
     * <p>
     * 这一条日志尤其对 {@link Capability#MONITORING} 重要：{@code sendBatchUpdate} 每 5 秒一次是
     * 面板判断「服务器是否在线」的唯一依据，关掉 monitoring 会让升级后的服务器在面板上显示为离线
     * ——这是最糟的失败形状，症状指向了错误的方向（运维会去查网络和令牌，而不是配置）。D-08 已经
     * 把 monitoring 的出厂默认设为开启作为第一层缓解，这条日志是第二层。
     *
     * @param capability 被跳过的能力
     */
    private static void logSkippedCapability(Capability capability) {
        UltiTools.getInstance().getLogger().log(Level.INFO, String.format(
                "[UltiPanel] Skipped %s wiring — capability disabled (%s)",
                capability.name(), capability.getConfigPath()));
    }
    
    /**
     * 处理配置更新。
     *
     * <p>这条路径过去有三处与面板对不上，而且失败是静默的（issue #236）：面板在
     * {@code data.configData} 里发内容、这里读 {@code data.config}；面板用
     * {@code data.fileName} 指定文件、这里除 {@code server_properties} 外从不读它；
     * 面板不发 {@code requestId}、这里以 {@code requestId} 存在与否作为「是不是一条请求」
     * 的判据，缺了就只记一行 {@code Level.FINE} 然后丢弃——而 {@code FINE} 在默认日志
     * 配置下不打印。于是面板收到 HTTP 200、服务器上什么也没变、两端都不报错。
     *
     * <p>现在的判据换成「有没有配置内容」：没有内容才是回声/回执，有内容就是一条请求，
     * 缺 {@code requestId} 也照样应用，只是记 WARNING 说明结果无法回报。
     *
     * <p>包级可见而非 private —— 只为可测。
     */
    static void handleConfigUpdate(JsonObject data) {
        if (data == null) {
            return;
        }

        String fileName = readString(data, "fileName");
        String requestId = readString(data, "requestId");
        String configContent = readConfigContent(data);

        // server_properties 的「取」请求：没有内容也是一条正当请求，不是回声。
        if (SERVER_PROPERTIES_FILE.equals(fileName) && configContent == null) {
            ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
            if (spm != null) {
                JsonObject spData = new JsonObject();
                spData.addProperty("action", "get");
                spm.handleServerProperties(spData);
            }
            return;
        }

        // 没有配置内容 = 转发层回来的回执或本机自己发出去的回声。这是唯一一种
        // 「什么都不做」还算正常的情况，所以保持 FINE。
        if (configContent == null) {
            if (data.has("message") && !data.get("message").isJsonNull()) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        String.format("收到服务器配置更新确认: %s", data.get("message").getAsString()));
            } else {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                        "收到不含配置内容的 update_config 消息，按回声处理");
            }
            return;
        }

        if (requestId == null) {
            // 以前这里直接 return。缺 requestId 是对端的协议缺陷，不是「这条消息不必处理」，
            // 把它当成后者就是把缺陷伪装成正常路径。
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "收到不含 requestId 的配置更新请求，仍会应用，但无法向面板回报结果");
        }

        try {
            applyConfigUpdate(fileName, configContent);
            sendConfigUpdateResponse(requestId, true, null);
        } catch (IOException | RuntimeException e) {
            // RuntimeException 也接：JsonParser 解析畸形 configData 抛的是它，
            // 过去这类失败会一路冒到 handleInboundMessage 的 catch，记成
            // 「处理消息类型 update_config 时发生错误」而面板永远等不到回复。
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("应用配置更新失败（文件: %s）: %s", fileName, e.getMessage()), e);
            sendConfigUpdateResponse(requestId, false, e.getMessage());
        }
    }

    /** 读一个可能缺失、也可能是 JSON null 的字符串字段。 */
    private static String readString(JsonObject data, String field) {
        return (data.has(field) && !data.get(field).isJsonNull())
                ? data.get(field).getAsString() : null;
    }

    /**
     * 取配置内容，优先 {@code data.configData}。
     *
     * <p>{@code data.config} 是本方法过去唯一读的字段，但在树内找不到任何生产者——
     * 面板一直发的是 {@code configData}。保留它只是为了兼容可能存在的第三方面板，
     * 读到就记废弃日志。
     */
    private static String readConfigContent(JsonObject data) {
        String configData = readString(data, "configData");
        if (configData != null) {
            return configData;
        }
        String legacy = readString(data, "config");
        if (legacy != null) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "update_config 使用了已废弃的 data.config 字段，请改用 data.configData");
        }
        return legacy;
    }

    /**
     * 按 {@code fileName} 决定写到哪里。
     *
     * <p>三条分支对应三种载荷形状，这是原来「fileName 从不读」掩盖掉的东西：
     * {@code server_properties} 是一份扁平的属性表，交给专用管理器；
     * 指定了文件名就是那一个配置文件自己的 {@code {配置项: 值}}；
     * 没有文件名才是 {@link com.ultikits.ultitools.manager.ConfigManager#toJson()}
     * 那种全量嵌套结构。
     */
    private static void applyConfigUpdate(String fileName, String configContent) throws IOException {
        if (SERVER_PROPERTIES_FILE.equals(fileName)) {
            ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
            if (spm == null) {
                throw new IOException("ServerPropertiesManager is not available");
            }
            // 走 applySetAll 而不是 handleServerProperties，是为了拿到返回值。
            // 后者是 void，于是这条路径过去只能无条件报成功——面板拿到的 status
            // 表达的是「消息处理完了」，不是「配置生效了」，而这两件事在
            // SAFE_KEYS 白名单挡下某个键时就分岔了。见 issue #281。
            // 两条消息都还在发：applySetAll 自己发 server_properties_result，
            // 这里抛出的异常由调用方转成 config_update_response 的 error。
            ServerPropertiesManager.SetAllResult result = spm.applySetAll(
                    com.google.gson.JsonParser.parseString(configContent).getAsJsonObject());
            if (!result.isSuccess()) {
                throw new IOException(result.describeFailure());
            }
            return;
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            ConfigEditorUtils.updateConfigMap(configContent);
            return;
        }
        ConfigEditorUtils.updateConfigMap(fileName, configContent);
    }

    /**
     * 回一条 {@code config_update_response}。
     *
     * <p>载荷放在 {@code data} 里，与其余所有 插件→Worker 的消息一致
     * （见 {@code CommandExecutionManager.sendCommandResult}）。此前这一条是扁平写法，
     * 字段直接挂在顶层；Worker 侧两种都读（ultipanel-api-worker#30），所以这次改动
     * 不需要和面板同时上线。
     */
    private static void sendConfigUpdateResponse(String requestId, boolean success, String error) {
        if (requestId == null || panelWS == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", requestId);
        payload.addProperty("status", success ? "success" : "error");
        if (error != null) {
            payload.addProperty("error", error);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "config_update_response");
        response.add("data", payload);
        response.addProperty("serverId", panelWS.getServerId());
        panelWS.sendMessage(response);
    }
    
    // ========== 系统基础消息处理器 ==========
    
    /**
     * 处理ping消息
     */
    private static void handlePing(JsonObject message) {
        // 发送pong响应
        JsonObject pongResponse = new JsonObject();
        pongResponse.addProperty("type", "pong");
        pongResponse.addProperty("timestamp", System.currentTimeMillis());
        
        JsonObject pongData = new JsonObject();
        pongData.addProperty("timestamp", System.currentTimeMillis());
        pongResponse.add("data", pongData);
        
        panelWS.sendMessage(pongResponse);
        UltiTools.getInstance().getLogger().log(Level.FINE, "Responded to ping with pong");
    }
    
    /**
     * 处理pong消息
     */
    private static void handlePong(JsonObject data) {
        UltiTools.getInstance().getLogger().log(Level.FINE, "Received pong response");
        if (data != null && data.has("timestamp") && !data.get("timestamp").isJsonNull()) {
            long serverTimestamp = data.get("timestamp").getAsLong();
            long currentTime = System.currentTimeMillis();
            long latency = currentTime - serverTimestamp;
            UltiTools.getInstance().getLogger().log(Level.FINE, "WebSocket latency: " + latency + "ms");
        }
    }
    
    /**
     * 处理订阅消息
     */
    private static void handleSubscribe(JsonObject data) {
        if (data != null) {
            boolean subscribed = safeGetBoolean(data, "subscribed", false);
            String serverId = safeGetString(data, "serverId");
            String message = safeGetString(data, "message");
            if (subscribed) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    String.format("成功订阅服务器: %s - %s", serverId, message));
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("订阅服务器失败: %s - %s", serverId, message));
            }
        }
    }
    
    /**
     * 处理取消订阅消息
     */
    private static void handleUnsubscribe(JsonObject data) {
        if (data != null) {
            String serverId = safeGetString(data, "serverId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("已取消订阅服务器: %s", serverId));
        }
    }
    
    /**
     * 处理通知消息
     */
    private static void handleNotification(JsonObject data) {
        if (data != null) {
            String message = safeGetString(data, "message");
            String clientId = safeGetString(data, "clientId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[服务器通知] %s (客户端ID: %s)", message, clientId));
        }
    }
    
    /**
     * 处理错误消息
     */
    private static void handleError(JsonObject data) {
        if (data != null) {
            String errorMessage = safeGetString(data, "message");
            UltiTools.getInstance().getLogger().log(Level.SEVERE,
                String.format("[WebSocket错误] %s", errorMessage));
        }
    }
    
    // ========== 服务器监控消息处理器 ==========
    
    /**
     * 处理玩家事件
     */
    private static void handlePlayerEvent(JsonObject data) {
        if (data != null) {
            String eventType = safeGetString(data, "eventType");
            JsonObject player = data.has("player") && data.get("player").isJsonObject()
                ? data.getAsJsonObject("player") : null;
            if (player != null) {
                String playerName = safeGetString(player, "name");
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    String.format("[玩家事件] %s: %s", eventType, playerName));
            }
        }
    }
    
    // ========== 操作控制消息处理器 ==========
    
    /**
     * 处理命令执行结果
     */
    private static void handleCommandResult(JsonObject data) {
        // command_result messages are echoed back from DO — already logged by
        // CommandExecutionManager, so we only log at FINE (debug) level here.
        if (data != null) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[命令执行结果] %s", data));
        }
    }
    
    /**
     * 处理文件操作结果
     */
    private static void handleFileOperationResult(JsonObject data) {
        if (data != null) {
            String operationId = safeGetString(data, "operationId");
            boolean success = safeGetBoolean(data, "success", false);
            String operation = safeGetString(data, "operation");
            String path = safeGetString(data, "path");
            String message = safeGetString(data, "message");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[文件操作结果] ID: %s, 操作: %s, 路径: %s, 成功: %s, 消息: %s",
                    operationId, operation, path, success, message));
            if (!success && message != null) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("文件操作失败: %s", message));
            }
        }
    }
    
    // ========== 数据流消息处理器 ==========
    
    /**
     * 处理备份操作
     */
    private static void handleBackupOperation(JsonObject data) {
        if (data != null) {
            String operation = safeGetString(data, "operation");
            String operationId = safeGetString(data, "operationId");
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[备份操作] 操作类型: %s, ID: %s", operation, operationId));
        }
    }
    
    /**
     * 处理备份进度
     */
    private static void handleBackupProgress(JsonObject data) {
        if (data != null) {
            String operationId = safeGetString(data, "operationId");
            double progress = safeGetDouble(data, "progress", 0.0);
            String currentStep = safeGetString(data, "currentStep");
            boolean completed = safeGetBoolean(data, "completed", false);
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[备份进度] ID: %s, 进度: %.1f%%, 当前步骤: %s, 完成: %s",
                    operationId, progress, currentStep, completed));
        }
    }
    
    // ========== 配置管理消息处理器 ==========
    
    /**
     * 处理配置上传
     */
    private static void handleConfigUpload(JsonObject data) {
        if (data != null) {
            // 只处理明确的配置上传请求（包含requestId），忽略服务器的确认消息
            if (data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                String configType = data.get("configType").getAsString();
                String configName = data.get("configName").getAsString();
                
                if (configType == null || configType.trim().isEmpty()) {
                    sendErrorResponse("Valid configuration type is required");
                    return;
                }
                
                UltiTools.getInstance().getLogger().log(Level.FINE, 
                    String.format("[配置上传] 类型: %s, 名称: %s", configType, configName));
                
                try {
                    // 处理配置上传逻辑
                    handleConfigUploadLogic(data);
                    
                    // 发送成功响应
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "upload_config_response");
                    response.addProperty("status", "success");
                    response.addProperty("serverId", panelWS.getServerId());
                    response.addProperty("requestId", requestId);
                    panelWS.sendMessage(response);
                    
                } catch (Exception e) {
                    sendErrorResponse("Failed to upload config: " + e.getMessage());
                }
            } else {
                // 识别并忽略服务器确认消息
                if (data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器配置上传确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器配置上传消息，但不包含requestId，忽略处理");
                }
            }
        }
    }
    
    /**
     * 处理配置上传逻辑
     */
    private static void handleConfigUploadLogic(JsonObject data) throws Exception {
        String configType = data.get("configType").getAsString();
        String configName = data.get("configName").getAsString();
        Object configContent = data.get("configContent");
        String format = data.get("format").getAsString();
        boolean backup = data.get("backup").getAsBoolean();
        
        UltiTools.getInstance().getLogger().log(Level.FINE, 
            String.format("处理配置上传: 类型=%s, 名称=%s, 格式=%s, 备份=%s", 
                configType, configName, format, backup));
        
        // 根据配置类型处理不同的配置文件
        switch (configType) {
            case "plugin_config":
                // 处理插件配置
                if (configContent instanceof JsonObject) {
                    ConfigEditorUtils.updateConfigMap(new Gson().toJson(configContent));
                }
                break;
            case "server_properties":
                // 处理服务器属性配置
                UltiTools.getInstance().getLogger().log(Level.FINE, "Processing server.properties config");
                break;
            case "permissions":
                // 处理权限配置
                UltiTools.getInstance().getLogger().log(Level.FINE, "Processing permissions config");
                break;
            default:
                throw new IllegalArgumentException("Unsupported config type: " + configType);
        }
    }
    
    // ========== 工具方法 ==========
    
    /**
     * 发送错误响应
     */
    private static void sendErrorResponse(String errorMessage) {
        JsonObject errorResponse = new JsonObject();
        errorResponse.addProperty("type", "error");
        errorResponse.addProperty("timestamp", System.currentTimeMillis());
        
        JsonObject errorData = new JsonObject();
        errorData.addProperty("message", errorMessage);
        errorResponse.add("data", errorData);
        
        panelWS.sendMessage(errorResponse);
    }
    
    /**
     * 处理插件列表请求
     */
    private static void handlePluginListRequest(JsonObject data) {
        try {
            // 只处理明确的插件列表请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                
                JsonObject response = new JsonObject();
                response.addProperty("type", "plugin_list");
                response.addProperty("serverId", panelWS.getServerId());
                response.addProperty("timestamp", System.currentTimeMillis());
                response.addProperty("requestId", requestId);
                
                JsonObject responseData = new JsonObject();
                JsonArray plugins = new JsonArray();
                
                // 获取所有插件信息
                for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                    JsonObject pluginInfo = new JsonObject();
                    pluginInfo.addProperty("name", plugin.getName());
                    pluginInfo.addProperty("version", plugin.getDescription().getVersion());
                    pluginInfo.addProperty("enabled", plugin.isEnabled());
                    pluginInfo.addProperty("author", String.join(", ", plugin.getDescription().getAuthors()));
                    pluginInfo.addProperty("description", plugin.getDescription().getDescription());
                    plugins.add(pluginInfo);
                }
                
                responseData.add("plugins", plugins);
                responseData.addProperty("totalCount", plugins.size());
                response.add("data", responseData);
                
                panelWS.sendMessage(response);
            } else {
                // 识别并忽略服务器确认消息
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器插件列表确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器插件列表消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Error handling plugin list request: " + e.getMessage());
        }
    }
    
    /**
     * 处理服务器状态请求
     */
    private static void handleServerStatusRequest(JsonObject data) {
        try {
            // 只处理明确的状态请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                UltiTools.getInstance().getLogger().log(Level.FINE, 
                    String.format("收到服务器状态请求，请求ID: %s", requestId));
                
                // 立即发送当前服务器状态，包含请求ID
                UltiTools.getInstance().getServerMonitorManager().sendServerStatusWithRequestId(requestId);
            } else {
                // 忽略服务器的确认消息和其他非请求消息
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器状态确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器状态消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "处理服务器状态请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理性能数据请求
     */
    private static void handleMetricsRequest(JsonObject data) {
        try {
            // 只处理明确的性能数据请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                UltiTools.getInstance().getServerMonitorManager().sendMetricsDataWithRequestId(requestId);
            } else {
                // 识别并忽略服务器确认消息
                if (data != null && data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器性能数据确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器性能数据消息，但不包含requestId，忽略处理");
                }
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "Error handling metrics request: " + e.getMessage());
        }
    }

    /**
     * 上传本地配置到服务器
     */
    private static void uploadConfig(UltiPanelWebSocketClient client) {
        JsonObject configMessage = new JsonObject();
        configMessage.addProperty("type", "upload_config");
        
        JsonObject data = new JsonObject();
        data.addProperty("configType", "plugin_config");  // 添加必需的配置类型
        data.addProperty("configName", "UltiTools.yml");   // 添加配置文件名
        data.addProperty("configContent", ConfigEditorUtils.getConfigMapString());
        data.addProperty("format", "yaml");                // 添加格式信息
        data.addProperty("backup", true);                  // 添加备份标志
        data.addProperty("comment", ConfigEditorUtils.getCommentMapString());
        data.addProperty("serverId", client.getServerId());
        
        configMessage.add("data", data);
        configMessage.addProperty("serverId", client.getServerId());
        
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("正在上传本地配置..."));
        client.sendMessage(configMessage);
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("配置上传成功!"));
    }

    /**
     * Upload server.properties safe keys to cloud for panel editing.
     */
    private static void uploadServerProperties(UltiPanelWebSocketClient client) {
        ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
        if (spm == null) return;

        Map<String, String> props = spm.getSafeProperties();
        if (props.isEmpty()) return;

        JsonObject propsJson = new JsonObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            propsJson.addProperty(entry.getKey(), entry.getValue());
        }

        JsonObject message = new JsonObject();
        message.addProperty("type", "server_properties_result");
        message.addProperty("serverId", client.getServerId());

        message.add("data", propsJson);

        UltiTools.getInstance().getLogger().log(Level.FINE, "正在上传服务器属性配置...");
        client.sendMessage(message);
        UltiTools.getInstance().getLogger().log(Level.FINE, "服务器属性配置上传成功!");
    }

    /**
     * Re-initialize the WebSocket connection with a fresh token.
     * Disconnects the old client (if any), refreshes the token if needed,
     * and creates a new WebSocket client.
     * <br>
     * 使用新令牌重新初始化WebSocket连接。
     */
    public static void reinitWebSocket() {
        // 闸门一：logout 之后不再重连。
        // 这是让 `/ulticloud logout` 真正生效的那一行——在它存在之前，logout 只清凭证，
        // 这条链会继续拿着已作废的 token 重连，401 循环照跑，实测只有重新 login 或重启
        // 服务器才停得下来。见 issue #223。
        if (!cloudEnabled.get()) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Cloud features are disabled — skipping WebSocket re-initialization");
            return;
        }

        // 闸门二：全局预算。客户端自身的 5 次上限是每实例的，而这里每次都造新实例，
        // 所以那个上限对整体等于不存在。见 issue #181。
        if (!reinitBackoff.shouldContinue()) {
            // 先把话说完再拆线：下面的 disableCloud() 会关掉日志上传通道，这句得赶在那之前发出去。
            UltiTools.getInstance().getLogger().log(Level.WARNING, String.format(
                "WebSocket re-initialization gave up after %d attempts. Cloud features are now idle. "
                    + "Run /ulticloud login to retry, or restart the server.",
                MAX_REINIT_ATTEMPTS));
            // 「now idle」必须是真的。曾经这里只有一句 cloudEnabled.set(false)：状态机确实停了，
            // 但心跳线程、日志传输器与 root logger handler、玩家事件监听器、token 刷新调度
            // 以及静态 panelWS/token 引用全都留着继续跑——日志宣告空转，实际在漏。
            // 终态与 logout 是同一件事，就该走同一条拆线路径。
            //
            // 复用 disableCloud() 是安全的：它第一件事就是把 cloudEnabled 置否，所以其中的
            // stopWebsocket() 即使触发 onClose→重连链，也会被本方法开头的闸门一挡回去；
            // 它顺带做的 reinitBackoff.reset() 同样无害——闸门一已经拦死，预算再也消耗不到，
            // 而恢复只能靠 /ulticloud login，那条路本来就会 reset。
            disableCloud();
            return;
        }

        UltiTools.getInstance().getLogger().log(Level.INFO, String.format(
            "Re-initializing WebSocket connection (attempt %d/%d)...",
            reinitBackoff.getAttemptCount() + 1, MAX_REINIT_ATTEMPTS));
        reinitBackoff.getNextDelay();   // 记一次尝试；实际的等待由客户端侧的调度承担

        // Disconnect old client
        if (panelWS != null) {
            try {
                panelWS.disconnect();
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.FINE,
                    "Error disconnecting old WebSocket: " + e.getMessage());
            }
            panelWS = null;
        }

        // Ensure token is valid — refresh if needed
        if (token == null || token.isExpired()) {
            if (token != null && token.getRefresh_token() != null && !token.getRefresh_token().isEmpty()) {
                TokenEntity refreshed = CloudAuthManager.refreshToken(token.getRefresh_token());
                if (refreshed != null) {
                    token = refreshed;
                    UltiTools.getInstance().getLogger().log(Level.INFO,
                        "Token refreshed for WebSocket re-initialization");
                } else {
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        "Token refresh failed — cannot re-initialize WebSocket");
                    return;
                }
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    "No valid token available — cannot re-initialize WebSocket");
                return;
            }
        }

        // 二次确认。从方法开头那次 cloudEnabled 检查到这里，中间隔了一次 token 刷新
        // ——那是网络调用，窗口可能有数秒。logout 若发生在这个窗口内，必须在这里被看见，
        // 否则我们会造出一个新的已认证客户端，把刚关掉的状态机重新拉起来。
        if (!cloudEnabled.get()) {
            UltiTools.getInstance().getLogger().log(Level.INFO,
                "Cloud features were disabled during re-initialization — aborting");
            return;
        }

        // Create new WebSocket connection
        try {
            initWebsocket();
            // 这里刻意不打「re-initialized successfully」。
            // initWebsocket() 返回只说明客户端被造出来、connect() 被发起了——connect() 是异步的，
            // 握手与认证都还没发生。实测这句之后紧跟着的就是一条 401。成功的那句现在由
            // onOpen 打（见 initWebsocket 里的 onConnectHandler），那才是真的连上了。
            // 见 issue #223。
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "WebSocket re-initialization dispatched — awaiting handshake");
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                "WebSocket re-initialization failed: " + e.getMessage());
        }
    }

    /**
     * 关闭云连接并让重连状态机进入明确的 disabled 态。
     * <p>
     * 供 {@code /ulticloud logout} 调用。与 {@link #stopWebsocket()} 的区别是：后者只断开当前
     * 客户端，而重连链会把它重新拉起来；本方法先把 {@link #cloudEnabled} 置否，因此
     * {@link #reinitWebSocket()} 之后会直接返回，状态机不会自我复活。
     * <p>
     * 顺带摘掉 root logger 上的日志 handler 与传输线程，并停掉 token 刷新调度——
     * 都是「云功能已关闭」这句话应当为真的组成部分。
     * <p>
     * 整个方法持有 {@link #cloudLifecycleLock}，与 {@code initializeManagers()} 互斥。
     * 不然的话，一次在途的 onOpen 可以在「置否」与「拆线」之间挤进来，把刚要拆的东西
     * 又装回去。见 PR #264 的两轮评审。
     */
    public static void disableCloud() {
        synchronized (cloudLifecycleLock) {
            doDisableCloud();
        }
    }

    /** {@link #disableCloud()} 的实际拆线动作。调用方必须持有 {@link #cloudLifecycleLock}。 */
    private static void doDisableCloud() {
        // 前三步的顺序是这条方法里最容易写反的地方，写反了两个方向都会漏：
        //
        //   1. 关闸——cloudEnabled 置否，reinit 链不再产生新的刷新。
        //   2. 停生产者——停掉刷新调度与 magic-link 轮询，不再有新任务被派出去。
        //   3. 作废在途——推进凭证代际，让已经在 HTTP 请求里的那些结果一律过期。
        //
        // 作废**必须**排在停生产者之后。反过来（先作废再停）的话，两者之间启动的新任务
        // 会快照到已经递增的那一代，于是它反而「合法」了，延迟返回的响应照样把凭证写回
        // data.json——这正是把作废提到最前面时踩到的坑。
        //
        // 而作废也不能等到整个拆线跑完：拆线还要关日志流、停监控、摘监听器、断 socket，
        // 是个不短的过程，这段时间里一次在途的轮询完全可以先提交成功，调用方（logout
        // 命令）就会看到一份「拆线之前不存在、拆线之后存在」的凭证。
        //
        // 最后一道保险在 clearToken() 里：它自己也推进一次代际，那一次发生在生产者全部
        // 停掉之后，任何仍在途的结果到那里都已过期。
        cloudEnabled.set(false);
        reinitBackoff.reset();

        teardownStep("stopping token refresh scheduler",
            CloudAuthManager::stopTokenRefreshScheduler);

        // 停掉还没走完的 magic-link 轮询。不停的话，一次「login 之后马上改主意 logout」
        // 会在轮询下一周期拿到 completed 时把服务器悄悄登回去——那条分支自己会
        // enableCloud() 加 initWebsocket()。
        teardownStep("stopping magic-link polling", CloudAuthManager::stopPolling);

        teardownStep("invalidating in-flight credential operations",
            CloudAuthManager::invalidateCredentialOperations);

        // 顺序有讲究：先关日志传输器，再断开 socket。
        // 反过来的话，传输器 flush 时 socket 已经断了，sendBatch() 会一条都发不出去。
        // （flushLogs 本身也已改成有界，两层都要有——顺序对了是让排队的日志还有机会送出去，
        //  有界是为了 socket 本来就断着的情况。）
        teardownStep("shutting down log stream manager",
            () -> UltiTools.getInstance().getLogStreamManager().shutdown());

        // 停掉服务器监控。它自带一个 ScheduledExecutorService（每 5 秒 batch_update）
        // 外加两个主线程 Bukkit 定时任务（1Hz 的 TPS/CPU、5 秒一次的世界/玩家/插件快照）。
        // 在此之前 stopMonitoring() 在整个 src/main 里没有任何调用方：写好了、测过了，
        // 就是没接线。不停的话，「云功能已关闭」之后主线程仍在按 5 秒遍历所有世界和区块。
        teardownStep("stopping server monitor", () -> {
            if (UltiTools.getInstance().getServerMonitorManager() != null) {
                UltiTools.getInstance().getServerMonitorManager().stopMonitoring();
            }
        });

        // 摘掉玩家事件监听器。云关掉之后再收玩家事件是纯浪费——事件处理器里那句
        // isConnected() 判断只是让它不发消息，监听本身还在跑。见 issue #180。
        teardownStep("shutting down player event manager", () -> {
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                UltiTools.getInstance().getPlayerEventManager().shutdown();
            }
        });

        stopWebsocket();
        panelWS = null;

        // 清掉本类持有的 token。它与 CloudAuthManager 清掉的凭证是**两份**，
        // 不清的话，一个正在途中的 reinit 仍然握着可用的 refresh token。
        token = null;
    }

    /**
     * 跑一步拆线动作，失败只记 FINE 不向外抛。
     * <p>
     * 拆线的每一步都必须尽力执行完：任何一步抛出去都会让它后面的步骤被跳过，而那些
     * 步骤正是「云功能已关闭」这句话的组成部分。原先这是六段一模一样的 try/catch，
     * 提取出来只是把那个不变量说清楚一次，行为不变。
     *
     * @param what 失败时写进日志的动作描述
     * @param action 拆线动作
     */
    private static void teardownStep(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error " + what + ": " + e.getMessage());
        }
    }

    /**
     * 重连成功时调用：把外层预算清零。
     * <p>
     * 只有<b>真正握手成功</b>才配重置预算。若在 {@code reinitWebSocket} 里重置，
     * 那么「造出了一个客户端」就会被当成成功，预算永远用不完，闸门等于没加。
     */
    static void onWebSocketConnected() {
        reinitBackoff.reset();
    }

    /**
     * 在云生命周期锁内，原子地「复查代际 → 开启状态机 → 建连 → 起刷新调度」。
     * <p>
     * 只让写凭证那一步对 logout 原子是不够的：magic-link 轮询在提交凭证之后还要做
     * {@code enableCloud()} + {@code initWebsocket()} + {@code startTokenRefreshScheduler()}，
     * 这一串才是真正把服务器连回去的动作。logout 挤在「提交成功」与「开始激活」之间的话，
     * 拆线拆的是一个还没建起来的连接，随后轮询线程照样把它建起来——logout 于是被撤销。
     * <p>
     * 这里与 {@code disableCloud()} 抢同一把 {@link #cloudLifecycleLock}，因此二者只能整体
     * 先后发生：要么先激活再被拆掉（干净），要么先拆线、本方法持锁复查代际时看到已变而
     * 直接返回 false（也干净）。
     * <p>
     * 锁内刻意<b>不</b>做 {@code loginWithToken()} —— 那是一次 HTTP 往返，持锁做会让
     * {@code /ulticloud logout} 在主线程上阻塞数秒。它只向面板注册服务器，不改本地状态，
     * 放在锁外重复执行也无害。
     *
     * @param generation 调用方出发时记下的凭证代际
     * @return 已激活返回 true；代际已变、激活被放弃则返回 false
     * @throws IOException 建连失败
     */
    public static boolean activateCloudIfCurrent(long generation) throws IOException {
        synchronized (cloudLifecycleLock) {
            if (generation != CloudAuthManager.currentCredentialGeneration()) {
                UltiTools.getInstance().getLogger().log(Level.INFO,
                    "Cloud activation aborted — a logout happened while this login was completing");
                return false;
            }
            // 显式开启：logout 之后重新 login 必须能把状态机拉回来。
            enableCloud();
            initWebsocket();
            CloudAuthManager.startTokenRefreshScheduler();
            return true;
        }
    }

    /**
     * 把状态机置为「应当保持连接」，并清零外层重连预算。
     * <p>
     * <b>只有显式动作才应当调用它</b>：服务器启动时的云登录，以及 {@code /ulticloud login}
     * 成功之后。{@link #initWebsocket()} 刻意不调——它同时被 {@link #reinitWebSocket()} 复用，
     * 在那里置位会让一个正在途中的重连把刚被 logout 关掉的状态机重新拉起来。
     */
    public static void enableCloud() {
        cloudEnabled.set(true);
        reinitBackoff.reset();
    }

    /** 供测试断言状态机是否处于启用态。 */
    static boolean isCloudEnabled() {
        return cloudEnabled.get();
    }

    public static void stopWebsocket() {
        if (panelWS == null){
            return;
        }
        panelWS.disconnect();
    }

    private static UltiPanelWebSocketClient getPanelWebsocketClient() throws IOException {
        String apiUrl = UltiTools.getEnv().getString("api-url");
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new IOException("API URL not configured in env.yml");
        }
        apiUrl = apiUrl.trim();

        // Derive WebSocket URL from the API base URL
        // 从API基础URL派生WebSocket URL
        String wsUrl;
        if (apiUrl.startsWith("https://")) {
            wsUrl = "wss://" + apiUrl.substring("https://".length()) + "/ws";
        } else if (apiUrl.startsWith("http://")) {
            wsUrl = "ws://" + apiUrl.substring("http://".length()) + "/ws";
        } else {
            wsUrl = "wss://" + apiUrl + "/ws";
        }

        try {
            return new UltiPanelWebSocketClient(wsUrl, CommonUtils.getUltiToolsUUID(), token.getAccess_token());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid WebSocket URL: " + wsUrl, e);
        }
    }

    // ========== Null-safe JSON accessors ==========

    private static String safeGetString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static boolean safeGetBoolean(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }

    private static long safeGetLong(JsonObject obj, String key, long defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsLong();
    }

    private static double safeGetDouble(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsDouble();
    }
}
