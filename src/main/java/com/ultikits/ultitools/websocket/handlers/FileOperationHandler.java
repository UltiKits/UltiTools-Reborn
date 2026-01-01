package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.manager.FileOperationManager;
import com.ultikits.ultitools.websocket.WebSocketMessageHandler;

/**
 * Handler for file operation messages from the panel.
 * <p>
 * 处理来自面板的文件操作消息的处理器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class FileOperationHandler implements WebSocketMessageHandler {
    
    private static final Logger logger = Logger.getLogger(FileOperationHandler.class.getName());
    
    private final FileOperationManager fileManager;
    
    /**
     * Creates a handler with the given file operation manager.
     * 使用给定的文件操作管理器创建处理器。
     *
     * @param fileManager the file operation manager
     */
    public FileOperationHandler(FileOperationManager fileManager) {
        this.fileManager = fileManager;
    }
    
    @Override
    public String getMessageType() {
        return "file_operation";
    }
    
    @Override
    public void handle(JSONObject message) {
        String operation = message.getString("operation");
        String path = message.getString("path");
        String requestId = message.getString("requestId");
        
        if (operation == null) {
            logger.warning("Received file_operation without operation type");
            return;
        }
        
        logger.fine("Handling file operation: " + operation + " on " + path);
        
        if (fileManager != null) {
            fileManager.handleFileOperation(message);
        } else {
            logger.warning("FileOperationManager not available");
        }
    }
}
