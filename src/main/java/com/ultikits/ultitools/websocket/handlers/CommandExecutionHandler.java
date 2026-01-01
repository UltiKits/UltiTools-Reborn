package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.WebSocketMessageHandler;

/**
 * Handler for remote command execution requests.
 * <p>
 * 处理远程命令执行请求的处理器。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class CommandExecutionHandler implements WebSocketMessageHandler {
    
    private static final Logger logger = Logger.getLogger(CommandExecutionHandler.class.getName());
    
    private final CommandExecutionManager commandManager;
    
    /**
     * Creates a handler with the given command manager.
     * 使用给定的命令管理器创建处理器。
     *
     * @param commandManager the command execution manager
     */
    public CommandExecutionHandler(CommandExecutionManager commandManager) {
        this.commandManager = commandManager;
    }
    
    @Override
    public String getMessageType() {
        return "execute_command";
    }
    
    @Override
    public void handle(JSONObject message) {
        String command = message.getString("command");
        String executor = message.getString("executor");
        String requestId = message.getString("requestId");
        boolean async = message.getBooleanValue("async");
        
        if (command == null || command.isEmpty()) {
            logger.warning("Received execute_command with empty command");
            return;
        }
        
        logger.info("Executing remote command: " + command + " (executor: " + executor + ")");
        
        if (commandManager != null) {
            // The command manager handles the actual execution
            commandManager.executeCommand(message);
        } else {
            logger.warning("CommandExecutionManager not available");
        }
    }
    
    @Override
    public int getPriority() {
        return 10; // Higher priority for command execution
    }
}
