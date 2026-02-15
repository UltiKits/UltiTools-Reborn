package com.ultikits.ultitools.websocket.handlers;

import java.util.logging.Logger;

import com.google.gson.JsonObject;
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
    public void handle(JsonObject message) {
        String command = message.has("command") && !message.get("command").isJsonNull() 
            ? message.get("command").getAsString() : null;
        String executor = message.has("executor") && !message.get("executor").isJsonNull() 
            ? message.get("executor").getAsString() : null;
        String requestId = message.has("requestId") && !message.get("requestId").isJsonNull() 
            ? message.get("requestId").getAsString() : null;
        boolean async = message.has("async") && !message.get("async").isJsonNull() 
            && message.get("async").getAsBoolean();
        
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
