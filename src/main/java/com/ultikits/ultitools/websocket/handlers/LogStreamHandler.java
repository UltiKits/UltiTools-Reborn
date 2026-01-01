package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.websocket.WebSocketMessageHandler;

/**
 * Handler for log stream control messages.
 * <p>
 * 处理日志流控制消息的处理器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class LogStreamHandler implements WebSocketMessageHandler {
    
    private static final Logger logger = Logger.getLogger(LogStreamHandler.class.getName());
    
    private final LogStreamManager logStreamManager;
    
    /**
     * Creates a handler with the given log stream manager.
     * 使用给定的日志流管理器创建处理器。
     *
     * @param logStreamManager the log stream manager
     */
    public LogStreamHandler(LogStreamManager logStreamManager) {
        this.logStreamManager = logStreamManager;
    }
    
    @Override
    public String getMessageType() {
        return "log_stream";
    }
    
    @Override
    public void handle(JSONObject message) {
        String action = message.getString("action");
        
        if (action == null) {
            logger.warning("Received log_stream message without action");
            return;
        }
        
        logger.fine("Handling log stream action: " + action);
        
        if (logStreamManager != null) {
            logStreamManager.handleLogStreamMessage(message);
        } else {
            logger.warning("LogStreamManager not available");
        }
    }
}
