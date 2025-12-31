package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.logging.Level;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import cn.hutool.http.HttpResponse;

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
     * Login to UltiPanel account and register/update server information.
     * <br>
     * 登录UltiPanel账户并注册/更新服务器信息。
     *
     * @param username the account username <br> 账户用户名
     * @param password the account password <br> 账户密码
     * @return true if login successful, false otherwise <br> 登录是否成功
     * @throws IOException if an I/O error occurs during login <br> 如果登录过程中发生I/O错误
     */
    public static boolean loginAccount(String username, String password) throws IOException {
        boolean ssl = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable");
        token = HttpRequestUtils.getToken(username, password);
        String uuid = CommonUtils.getUltiToolsUUID();
        HttpResponse uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token);
        int port = UltiTools.getInstance().getConfig().getInt("web-editor.port");
        String domain = UltiTools.getInstance().getConfig().getString("web-editor.https.domain");
        if (uuidResponse.getStatus() == 404) {
            try (HttpResponse registerResponse = HttpRequestUtils.registerServer(uuid, port, domain, ssl, token)) {
                if (!registerResponse.isOk()) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, registerResponse.body());
                    return false;
                }
            }
        } else {
            try (HttpResponse registerResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token)) {
                if (!registerResponse.isOk()) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, registerResponse.body());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Initialize websocket.
     * <br>
     * 初始化websocket。
     */
    public static void initWebsocket() {
        panelWS = getPanelWebsocketClient();
        
        // 设置消息处理器
        panelWS.setMessageHandler(message -> {
            String type = message.getString("type");
            JSONObject data = message.getJSONObject("data");
            
            // 记录接收到的消息处理日志
            UltiTools.getInstance().getLogger().log(Level.INFO, 
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
                    
                    default:
                        UltiTools.getInstance().getLogger().log(Level.WARNING, 
                            String.format("未知的消息类型: %s，消息内容: %s", type, message.toJSONString()));
                        // 发送错误响应
                        sendErrorResponse("Unknown message type: " + type);
                        break;
                }
            } catch (Exception e) {
                UltiTools.getInstance().getLogger().log(Level.SEVERE, 
                    String.format("处理消息类型 %s 时发生错误: %s", type, e.getMessage()), e);
                // 发送错误响应
                sendErrorResponse("Error processing message: " + e.getMessage());
            }
            
            // 记录消息处理完成日志
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[WebSocket消息处理] 类型: %s, 处理完成", type));
        });

        // 设置连接成功处理器
        panelWS.setOnConnectHandler(() -> {
            UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("Websocket已连接!"));
            
            // 订阅当前服务器
            panelWS.subscribeToServer(panelWS.getServerId());
            
            // 初始化所有管理器
            initializeManagers();
            
            // 上传配置
            uploadConfig();
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
            
            // 初始化日志流管理器
            if (UltiTools.getInstance().getLogStreamManager() != null) {
                UltiTools.getInstance().getLogStreamManager().initialize(panelWS);
            }
            
            // 初始化玩家事件管理器
            if (UltiTools.getInstance().getPlayerEventManager() != null) {
                UltiTools.getInstance().getPlayerEventManager().initialize(panelWS);
            }
            
            UltiTools.getInstance().getLogger().log(Level.INFO, "所有WebSocket管理器已初始化并启动监控");
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "初始化管理器时出错: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理配置更新
     */
    private static void handleConfigUpdate(JSONObject data) {
        if (data != null) {
            // 只处理明确的配置更新请求（包含requestId），忽略服务器的确认消息
            if (data.containsKey("requestId")) {
                String requestId = data.getString("requestId");
                try {
                    ConfigEditorUtils.updateConfigMap(data.getString("config"));
                    // 发送确认消息
                    JSONObject response = new JSONObject();
                    response.put("type", "config_update_response");
                    response.put("status", "success");
                    response.put("serverId", panelWS.getServerId());
                    response.put("requestId", requestId);
                    panelWS.sendMessage(response);
                } catch (IOException e) {
                    // 发送错误消息
                    JSONObject response = new JSONObject();
                    response.put("type", "config_update_response");
                    response.put("status", "error");
                    response.put("error", e.getMessage());
                    response.put("serverId", panelWS.getServerId());
                    response.put("requestId", requestId);
                    panelWS.sendMessage(response);
                }
            } else {
                // 识别并忽略服务器确认消息
                if (data.containsKey("message")) {
                    String message = data.getString("message");
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
    private static void handlePing(JSONObject message) {
        // 发送pong响应
        JSONObject pongResponse = new JSONObject();
        pongResponse.put("type", "pong");
        pongResponse.put("timestamp", System.currentTimeMillis());
        
        JSONObject pongData = new JSONObject();
        pongData.put("timestamp", System.currentTimeMillis());
        pongResponse.put("data", pongData);
        
        panelWS.sendMessage(pongResponse);
        UltiTools.getInstance().getLogger().log(Level.FINE, "Responded to ping with pong");
    }
    
    /**
     * 处理pong消息
     */
    private static void handlePong(JSONObject data) {
        UltiTools.getInstance().getLogger().log(Level.FINE, "Received pong response");
        // 可以在这里更新连接状态或计算延迟
        if (data != null && data.containsKey("timestamp")) {
            long serverTimestamp = data.getLong("timestamp");
            long currentTime = System.currentTimeMillis();
            long latency = currentTime - serverTimestamp;
            UltiTools.getInstance().getLogger().log(Level.FINE, "WebSocket latency: " + latency + "ms");
        }
    }
    
    /**
     * 处理订阅消息
     */
    private static void handleSubscribe(JSONObject data) {
        if (data != null) {
            boolean subscribed = data.getBooleanValue("subscribed");
            String serverId = data.getString("serverId");
            String message = data.getString("message");
            
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
    private static void handleUnsubscribe(JSONObject data) {
        if (data != null) {
            String serverId = data.getString("serverId");
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("已取消订阅服务器: %s", serverId));
        }
    }
    
    /**
     * 处理通知消息
     */
    private static void handleNotification(JSONObject data) {
        if (data != null) {
            String message = data.getString("message");
            String clientId = data.getString("clientId");
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[服务器通知] %s (客户端ID: %s)", message, clientId));
        }
    }
    
    /**
     * 处理错误消息
     */
    private static void handleError(JSONObject data) {
        if (data != null) {
            String errorMessage = data.getString("message");
            UltiTools.getInstance().getLogger().log(Level.SEVERE, 
                String.format("[WebSocket错误] %s", errorMessage));
        }
    }
    
    // ========== 服务器监控消息处理器 ==========
    
    /**
     * 处理玩家事件
     */
    private static void handlePlayerEvent(JSONObject data) {
        if (data != null) {
            String eventType = data.getString("eventType");
            JSONObject player = data.getJSONObject("player");
            
            if (player != null) {
                String playerName = player.getString("name");
                UltiTools.getInstance().getLogger().log(Level.INFO, 
                    String.format("[玩家事件] %s: %s", eventType, playerName));
            }
            
            // 记录玩家事件信息，玩家事件管理器主要负责发送事件，而不是接收
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("收到玩家事件消息: %s", data.toJSONString()));
        }
    }
    
    // ========== 操作控制消息处理器 ==========
    
    /**
     * 处理命令执行结果
     */
    private static void handleCommandResult(JSONObject data) {
        if (data != null) {
            String commandId = data.getString("commandId");
            boolean success = data.getBooleanValue("success");
            String output = data.getString("output");
            long executionTime = data.getLongValue("executionTime");
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[命令执行结果] ID: %s, 成功: %s, 执行时间: %dms", 
                    commandId, success, executionTime));
            
            if (output != null && !output.trim().isEmpty()) {
                UltiTools.getInstance().getLogger().log(Level.INFO, 
                    String.format("[命令输出] %s", output));
            }
        }
    }
    
    /**
     * 处理文件操作结果
     */
    private static void handleFileOperationResult(JSONObject data) {
        if (data != null) {
            String operationId = data.getString("operationId");
            boolean success = data.getBooleanValue("success");
            String operation = data.getString("operation");
            String path = data.getString("path");
            String message = data.getString("message");
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[文件操作结果] ID: %s, 操作: %s, 路径: %s, 成功: %s, 消息: %s", 
                    operationId, operation, path, success, message));
            
            // 记录文件操作结果，文件操作管理器主要负责处理请求和发送结果
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
    private static void handleBackupOperation(JSONObject data) {
        if (data != null) {
            String operation = data.getString("operation");
            String operationId = data.getString("operationId");
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[备份操作] 操作类型: %s, ID: %s", operation, operationId));
            
            // 这里可以添加具体的备份操作逻辑
            // TODO: 实现备份操作管理器
        }
    }
    
    /**
     * 处理备份进度
     */
    private static void handleBackupProgress(JSONObject data) {
        if (data != null) {
            String operationId = data.getString("operationId");
            double progress = data.getDoubleValue("progress");
            String currentStep = data.getString("currentStep");
            boolean completed = data.getBooleanValue("completed");
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("[备份进度] ID: %s, 进度: %.1f%%, 当前步骤: %s, 完成: %s", 
                    operationId, progress, currentStep, completed));
        }
    }
    
    // ========== 配置管理消息处理器 ==========
    
    /**
     * 处理配置上传
     */
    private static void handleConfigUpload(JSONObject data) {
        if (data != null) {
            // 只处理明确的配置上传请求（包含requestId），忽略服务器的确认消息
            if (data.containsKey("requestId")) {
                String requestId = data.getString("requestId");
                String configType = data.getString("configType");
                String configName = data.getString("configName");
                
                if (configType == null || configType.trim().isEmpty()) {
                    sendErrorResponse("Valid configuration type is required");
                    return;
                }
                
                UltiTools.getInstance().getLogger().log(Level.INFO, 
                    String.format("[配置上传] 类型: %s, 名称: %s", configType, configName));
                
                try {
                    // 处理配置上传逻辑
                    handleConfigUploadLogic(data);
                    
                    // 发送成功响应
                    JSONObject response = new JSONObject();
                    response.put("type", "upload_config_response");
                    response.put("status", "success");
                    response.put("serverId", panelWS.getServerId());
                    response.put("requestId", requestId);
                    panelWS.sendMessage(response);
                    
                } catch (Exception e) {
                    sendErrorResponse("Failed to upload config: " + e.getMessage());
                }
            } else {
                // 识别并忽略服务器确认消息
                if (data.containsKey("message")) {
                    String message = data.getString("message");
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
    private static void handleConfigUploadLogic(JSONObject data) throws Exception {
        String configType = data.getString("configType");
        String configName = data.getString("configName");
        Object configContent = data.get("configContent");
        String format = data.getString("format");
        boolean backup = data.getBooleanValue("backup");
        
        UltiTools.getInstance().getLogger().log(Level.INFO, 
            String.format("处理配置上传: 类型=%s, 名称=%s, 格式=%s, 备份=%s", 
                configType, configName, format, backup));
        
        // 根据配置类型处理不同的配置文件
        switch (configType) {
            case "plugin_config":
                // 处理插件配置
                if (configContent instanceof JSONObject) {
                    ConfigEditorUtils.updateConfigMap(((JSONObject) configContent).toJSONString());
                }
                break;
            case "server_properties":
                // 处理服务器属性配置
                UltiTools.getInstance().getLogger().log(Level.INFO, "Processing server.properties config");
                break;
            case "permissions":
                // 处理权限配置
                UltiTools.getInstance().getLogger().log(Level.INFO, "Processing permissions config");
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
        JSONObject errorResponse = new JSONObject();
        errorResponse.put("type", "error");
        errorResponse.put("timestamp", System.currentTimeMillis());
        
        JSONObject errorData = new JSONObject();
        errorData.put("message", errorMessage);
        errorResponse.put("data", errorData);
        
        panelWS.sendMessage(errorResponse);
    }
    
    /**
     * 处理插件列表请求
     */
    private static void handlePluginListRequest(JSONObject data) {
        try {
            // 只处理明确的插件列表请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.containsKey("requestId")) {
                String requestId = data.getString("requestId");
                
                JSONObject response = new JSONObject();
                response.put("type", "plugin_list");
                response.put("serverId", panelWS.getServerId());
                response.put("timestamp", System.currentTimeMillis());
                response.put("requestId", requestId);
                
                JSONObject responseData = new JSONObject();
                JSONArray plugins = new JSONArray();
                
                // 获取所有插件信息
                for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                    JSONObject pluginInfo = new JSONObject();
                    pluginInfo.put("name", plugin.getName());
                    pluginInfo.put("version", plugin.getDescription().getVersion());
                    pluginInfo.put("enabled", plugin.isEnabled());
                    pluginInfo.put("author", String.join(", ", plugin.getDescription().getAuthors()));
                    pluginInfo.put("description", plugin.getDescription().getDescription());
                    plugins.add(pluginInfo);
                }
                
                responseData.put("plugins", plugins);
                responseData.put("totalCount", plugins.size());
                response.put("data", responseData);
                
                panelWS.sendMessage(response);
            } else {
                // 识别并忽略服务器确认消息
                if (data != null && data.containsKey("message")) {
                    String message = data.getString("message");
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
    private static void handleServerStatusRequest(JSONObject data) {
        try {
            // 只处理明确的状态请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.containsKey("requestId")) {
                String requestId = data.getString("requestId");
                UltiTools.getInstance().getLogger().log(Level.INFO, 
                    String.format("收到服务器状态请求，请求ID: %s", requestId));
                
                // 立即发送当前服务器状态，包含请求ID
                UltiTools.getInstance().getServerMonitorManager().sendServerStatusWithRequestId(requestId);
            } else {
                // 忽略服务器的确认消息和其他非请求消息
                if (data != null && data.containsKey("message")) {
                    String message = data.getString("message");
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
    private static void handleMetricsRequest(JSONObject data) {
        try {
            // 只处理明确的性能数据请求（包含requestId），忽略服务器的确认消息
            if (data != null && data.containsKey("requestId")) {
                String requestId = data.getString("requestId");
                UltiTools.getInstance().getServerMonitorManager().sendMetricsDataWithRequestId(requestId);
            } else {
                // 识别并忽略服务器确认消息
                if (data != null && data.containsKey("message")) {
                    String message = data.getString("message");
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
        JSONObject configMessage = new JSONObject();
        configMessage.put("type", "upload_config");
        
        JSONObject data = new JSONObject();
        data.put("configType", "plugin_config");  // 添加必需的配置类型
        data.put("configName", "UltiTools.yml");   // 添加配置文件名
        data.put("configContent", ConfigEditorUtils.getConfigMapString());
        data.put("format", "yaml");                // 添加格式信息
        data.put("backup", true);                  // 添加备份标志
        data.put("comment", ConfigEditorUtils.getCommentMapString());
        data.put("serverId", panelWS.getServerId());
        
        configMessage.put("data", data);
        configMessage.put("serverId", panelWS.getServerId());
        
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("正在上传本地配置..."));
        panelWS.sendMessage(configMessage);
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("配置上传成功!"));
    }

    public static void stopWebsocket() {
        if (panelWS == null){
            return;
        }
        panelWS.disconnect();
    }

    private static UltiPanelWebSocketClient getPanelWebsocketClient() {
        // 根据配置确定WebSocket URL
        boolean useHttps = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable", true);
        String wsUrl;
        if (useHttps) {
            wsUrl = "wss://api.ultikits.com/ws";
        } else {
            wsUrl = "ws://localhost:8787/ws";
        }
        
        return new UltiPanelWebSocketClient(wsUrl, CommonUtils.getUltiToolsUUID(), token.getAccess_token());
    }
}
