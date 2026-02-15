package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.manager.ServerMonitorManager;
import com.ultikits.ultitools.websocket.WebSocketMessageHandler;

/**
 * Handler for server status request messages from the panel.
 * <p>
 * 处理来自面板的服务器状态请求消息的处理器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class ServerStatusHandler implements WebSocketMessageHandler {
    
    private static final Logger logger = Logger.getLogger(ServerStatusHandler.class.getName());
    
    private final ServerMonitorManager monitorManager;
    
    /**
     * Creates a handler with the given monitor manager.
     * 使用给定的监控管理器创建处理器。
     *
     * @param monitorManager the server monitor manager
     */
    public ServerStatusHandler(ServerMonitorManager monitorManager) {
        this.monitorManager = monitorManager;
    }
    
    @Override
    public String getMessageType() {
        return "server_status_request";
    }
    
    @Override
    public void handle(JsonObject message) {
        logger.fine("Received server status request");
        
        String requestId = message.has("requestId") && !message.get("requestId").isJsonNull() 
            ? message.get("requestId").getAsString() : null;
        
        if (monitorManager != null) {
            if (requestId != null && !requestId.isEmpty()) {
                monitorManager.sendServerStatusWithRequestId(requestId);
            } else {
                monitorManager.sendServerStatus();
            }
        } else {
            logger.warning("ServerMonitorManager not available");
        }
    }
}
