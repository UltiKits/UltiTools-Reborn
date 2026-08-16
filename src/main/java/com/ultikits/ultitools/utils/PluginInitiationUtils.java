package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
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

        // 上传服务器属性到云端
        uploadServerProperties(client);
    }
    
    /**
     * 初始化所有管理器
     */
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
        try {
            if (message.has("type") && message.get("type").isJsonPrimitive()) {
                type = message.get("type").getAsString();
            }
            if (type == null || type.isEmpty()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("[WebSocket消息处理] 消息缺少有效的 type 字段，已忽略: %s",
                        new Gson().toJson(message)));
                return;
            }

            JsonObject data = message.has("data") && message.get("data").isJsonObject()
                ? message.getAsJsonObject("data") : null;

            // 记录接收到的消息处理日志
            UltiTools.getInstance().getLogger().log(Level.FINE,
                String.format("[WebSocket消息处理] 类型: %s, 开始处理", type));

            switch (type) {
                // 系统基础消息
                case "ping":
                    handlePing(message);
                    break;
                case "pong":
                    handlePong(data);
                    break;
                case "subscribe":
                    handleSubscribe(data);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(data);
                    break;
                case "notification":
                    handleNotification(data);
                    break;
                case "error":
                    handleError(data);
                    break;
                
                // 服务器监控消息
                case "server_status":
                    handleServerStatusRequest(data);
                    break;
                case "plugin_list":
                    handlePluginListRequest(data);
                    break;
                case "player_event":
                    handlePlayerEvent(data);
                    break;
                case "metrics_data":
                    handleMetricsRequest(data);
                    break;
                
                // 操作控制消息
                case "execute_command":
                    UltiTools.getInstance().getCommandExecutionManager().executeCommand(data);
                    break;
                case "command_result":
                    handleCommandResult(data);
                    break;
                case "file_operation":
                    UltiTools.getInstance().getFileOperationManager().handleFileOperation(data);
                    break;
                case "file_operation_result":
                    handleFileOperationResult(data);
                    break;
                
                // 数据流消息
                case "log_stream":
                case "log_stream_control":
                    UltiTools.getInstance().getLogStreamManager().handleLogStreamMessage(data);
                    break;
                case "backup_operation":
                    handleBackupOperation(data);
                    break;
                case "backup_progress":
                    handleBackupProgress(data);
                    break;
                
                // 配置管理消息
                case "upload_config":
                    handleConfigUpload(data);
                    break;
                case "update_config":
                    handleConfigUpdate(data);
                    break;
                case "server_properties":
                    if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                        UltiTools.getInstance().getServerPropertiesManager().handleServerProperties(data);
                    }
                    break;
                case "server_properties_result":
                    // Response from this plugin forwarded back by DO — ignore silently
                    UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received server_properties_result echo — ignoring");
                    break;

                // Magic link auth messages (completion handled by HTTP polling in UltiLogin)
                case "auth_complete":
                    UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received auth_complete message: " + (data != null ? data.toString() : "null"));
                    break;
                case "magic_link_response":
                    UltiTools.getInstance().getLogger().log(Level.FINE,
                        "Received magic_link_response message: " + (data != null ? data.toString() : "null"));
                    break;

                default:
                    UltiTools.getInstance().getLogger().log(Level.WARNING,
                        String.format("未知的消息类型: %s，消息内容: %s", type, new Gson().toJson(message)));
                    // Don't send error responses to avoid feedback loops with server
                    break;
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.SEVERE,
                String.format("处理消息类型 %s 时发生错误: %s", type, e.getMessage()), e);
            // Don't send error responses to avoid feedback loops with server
        }
        
        // 记录消息处理完成日志
        UltiTools.getInstance().getLogger().log(Level.FINE, 
            String.format("[WebSocket消息处理] 类型: %s, 处理完成", type));
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

    /** {@link #initializeManagers()} 的实际接线动作。调用方必须持有 {@link #cloudLifecycleLock}。 */
    private static void wireManagers() {
        try {
            // 初始化服务器监控管理器
            UltiTools.getInstance().getServerMonitorManager().setWebSocketClient(panelWS);
            // 启动监控（会立即发送状态并开始定期发送）
            UltiTools.getInstance().getServerMonitorManager().startMonitoring();
            
            // 初始化命令执行管理器
            UltiTools.getInstance().getCommandExecutionManager().setWebSocketClient(panelWS);
            
            // 初始化文件操作管理器
            UltiTools.getInstance().getFileOperationManager().setWebSocketClient(panelWS);

            // 初始化服务器属性管理器
            if (UltiTools.getInstance().getServerPropertiesManager() != null) {
                UltiTools.getInstance().getServerPropertiesManager().setWebSocketClient(panelWS);
            }
            
            // 初始化日志流管理器
            if (UltiTools.getInstance().getLogStreamManager() != null) {
                UltiTools.getInstance().getLogStreamManager().initialize(panelWS);
            }
            
            // 初始化玩家事件管理器
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                UltiTools.getInstance().getPlayerEventManager().initialize(panelWS);
            }
            
            UltiTools.getInstance().getLogger().log(Level.FINE, "所有WebSocket管理器已初始化并启动监控");
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "初始化管理器时出错: " + e.getMessage(), e);
        }
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
        cloudEnabled.set(false);
        reinitBackoff.reset();

        // 顺序有讲究：先关日志传输器，再断开 socket。
        // 反过来的话，传输器 flush 时 socket 已经断了，sendBatch() 会一条都发不出去。
        // （flushLogs 本身也已改成有界，两层都要有——顺序对了是让排队的日志还有机会送出去，
        //  有界是为了 socket 本来就断着的情况。）
        try {
            UltiTools.getInstance().getLogStreamManager().shutdown();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error shutting down log stream manager: " + e.getMessage());
        }

        // 停掉服务器监控。它自带一个 ScheduledExecutorService（每 5 秒 batch_update）
        // 外加两个主线程 Bukkit 定时任务（1Hz 的 TPS/CPU、5 秒一次的世界/玩家/插件快照）。
        // 在此之前 stopMonitoring() 在整个 src/main 里没有任何调用方：写好了、测过了，
        // 就是没接线。不停的话，「云功能已关闭」之后主线程仍在按 5 秒遍历所有世界和区块。
        try {
            if (UltiTools.getInstance().getServerMonitorManager() != null) {
                UltiTools.getInstance().getServerMonitorManager().stopMonitoring();
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error stopping server monitor: " + e.getMessage());
        }

        // 摘掉玩家事件监听器。云关掉之后再收玩家事件是纯浪费——事件处理器里那句
        // isConnected() 判断只是让它不发消息，监听本身还在跑。见 issue #180。
        try {
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                UltiTools.getInstance().getPlayerEventManager().shutdown();
            }
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error shutting down player event manager: " + e.getMessage());
        }

        stopWebsocket();
        panelWS = null;

        // 清掉本类持有的 token。它与 CloudAuthManager 清掉的凭证是**两份**，
        // 不清的话，一个正在途中的 reinit 仍然握着可用的 refresh token。
        token = null;

        try {
            CloudAuthManager.stopTokenRefreshScheduler();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error stopping token refresh scheduler: " + e.getMessage());
        }

        // 停掉还没走完的 magic-link 轮询。不停的话，一次「login 之后马上改主意 logout」
        // 会在轮询下一周期拿到 completed 时把服务器悄悄登回去——那条分支自己会
        // enableCloud() 加 initWebsocket()。
        try {
            CloudAuthManager.stopPolling();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error stopping magic-link polling: " + e.getMessage());
        }

        // 最后推进凭证代际，让一切**已经在途**的凭证操作作废。
        // 上面那些 stop* 用的都是 cancel(false)，只承诺不再调度新的执行，拦不住一个已经
        // 进了 HTTP 请求的任务；而刷新与轮询都会在返回前写 currentToken 与 data.json。
        // 没有这一步，logout 会被一个迟到几秒的刷新原地撤销，重启后自动重连。
        try {
            CloudAuthManager.invalidateCredentialOperations();
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.FINE,
                "Error invalidating in-flight credential operations: " + e.getMessage());
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
