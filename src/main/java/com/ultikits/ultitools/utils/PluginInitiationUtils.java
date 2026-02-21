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

        panelWS = getPanelWebsocketClient();
        
        // 设置消息处理器
        panelWS.setMessageHandler(message -> {
            String type = message.get("type").getAsString();
            JsonObject data = message.has("data") && message.get("data").isJsonObject()
                ? message.getAsJsonObject("data") : null;
            
            // 记录接收到的消息处理日志
            UltiTools.getInstance().getLogger().log(Level.FINE, 
                String.format("[WebSocket消息处理] 类型: %s, 开始处理", type));
            
            try {
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
        });

        // 设置连接成功处理器
        panelWS.setOnConnectHandler(() -> {
            UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("Websocket已连接!"));
            
            // 订阅当前服务器
            panelWS.subscribeToServer(panelWS.getServerId());
            
            // 初始化所有管理器
            initializeManagers();
            
            // 上传配置
            uploadConfig();

            // 上传服务器属性到云端
            uploadServerProperties();
        });

        // 连接到WebSocket服务器
        panelWS.connect();
    }
    
    /**
     * 初始化所有管理器
     */
    private static void initializeManagers() {
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
     * 处理配置更新
     */
    private static void handleConfigUpdate(JsonObject data) {
        if (data != null) {
            // Route server_properties to dedicated manager
            String fileName = (data.has("fileName") && !data.get("fileName").isJsonNull())
                    ? data.get("fileName").getAsString() : null;
            if ("server_properties".equals(fileName)) {
                ServerPropertiesManager spm = UltiTools.getInstance().getServerPropertiesManager();
                if (spm != null) {
                    // Parse configData as JSON and forward as set_all action
                    String configDataStr = (data.has("configData") && !data.get("configData").isJsonNull())
                            ? data.get("configData").getAsString() : null;
                    if (configDataStr != null) {
                        JsonObject spData = new JsonObject();
                        spData.addProperty("action", "set_all");
                        spData.add("values", com.google.gson.JsonParser.parseString(configDataStr).getAsJsonObject());
                        spm.handleServerProperties(spData);
                    } else {
                        // No configData means "get" request
                        JsonObject spData = new JsonObject();
                        spData.addProperty("action", "get");
                        spm.handleServerProperties(spData);
                    }
                }
                return;
            }

            // 只处理明确的配置更新请求（包含requestId），忽略服务器的确认消息
            if (data.has("requestId")) {
                String requestId = data.get("requestId").getAsString();
                try {
                    ConfigEditorUtils.updateConfigMap(data.get("config").getAsString());
                    // 发送确认消息
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "config_update_response");
                    response.addProperty("status", "success");
                    response.addProperty("serverId", panelWS.getServerId());
                    response.addProperty("requestId", requestId);
                    panelWS.sendMessage(response);
                } catch (IOException e) {
                    // 发送错误消息
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "config_update_response");
                    response.addProperty("status", "error");
                    response.addProperty("error", e.getMessage());
                    response.addProperty("serverId", panelWS.getServerId());
                    response.addProperty("requestId", requestId);
                    panelWS.sendMessage(response);
                }
            } else {
                // 识别并忽略服务器确认消息
                if (data.has("message")) {
                    String message = data.get("message").getAsString();
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        String.format("收到服务器配置更新确认: %s", message));
                } else {
                    UltiTools.getInstance().getLogger().log(Level.FINE, 
                        "收到服务器配置更新消息，但不包含requestId，忽略处理");
                }
            }
        }
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
    private static void uploadConfig() {
        JsonObject configMessage = new JsonObject();
        configMessage.addProperty("type", "upload_config");
        
        JsonObject data = new JsonObject();
        data.addProperty("configType", "plugin_config");  // 添加必需的配置类型
        data.addProperty("configName", "UltiTools.yml");   // 添加配置文件名
        data.addProperty("configContent", ConfigEditorUtils.getConfigMapString());
        data.addProperty("format", "yaml");                // 添加格式信息
        data.addProperty("backup", true);                  // 添加备份标志
        data.addProperty("comment", ConfigEditorUtils.getCommentMapString());
        data.addProperty("serverId", panelWS.getServerId());
        
        configMessage.add("data", data);
        configMessage.addProperty("serverId", panelWS.getServerId());
        
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("正在上传本地配置..."));
        panelWS.sendMessage(configMessage);
        UltiTools.getInstance().getLogger().log(Level.FINE, UltiTools.getInstance().i18n("配置上传成功!"));
    }

    /**
     * Upload server.properties safe keys to cloud for panel editing.
     */
    private static void uploadServerProperties() {
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
        message.addProperty("serverId", panelWS.getServerId());

        message.add("data", propsJson);

        UltiTools.getInstance().getLogger().log(Level.FINE, "正在上传服务器属性配置...");
        panelWS.sendMessage(message);
        UltiTools.getInstance().getLogger().log(Level.FINE, "服务器属性配置上传成功!");
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
