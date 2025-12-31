package com.ultikits.ultitools.manager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * 命令执行管理器
 * 负责处理来自WebSocket的命令执行请求
 */
public class CommandExecutionManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands;
    
    public CommandExecutionManager() {
        this.pendingCommands = new ConcurrentHashMap<>();
    }
    
    /**
     * 设置WebSocket客户端
     * @param client WebSocket客户端
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }
    
    /**
     * 执行命令
     */
    public void executeCommand(JSONObject commandData) {
        try {
            String command = commandData.getString("command");
            String executor = commandData.getString("executor");
            boolean async = commandData.getBooleanValue("async");
            String commandId = commandData.getString("commandId");
            
            if (command == null || command.trim().isEmpty()) {
                sendCommandResult(commandId, false, "Command cannot be empty", 0);
                return;
            }
            
            // 记录命令执行开始时间
            long startTime = System.currentTimeMillis();
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("执行命令: %s (ID: %s, 执行者: %s, 异步: %s)", 
                command, commandId, executor, async));
            
            if (async) {
                // 异步执行
                Bukkit.getScheduler().runTaskAsynchronously(UltiTools.getInstance(), () -> {
                    executeCommandInternal(command, executor, commandId, startTime);
                });
            } else {
                // 同步执行
                Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                    executeCommandInternal(command, executor, commandId, startTime);
                });
            }
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "执行命令时发生错误: " + e.getMessage());
            String commandId = commandData.getString("commandId");
            sendCommandResult(commandId, false, "Internal error: " + e.getMessage(), 0);
        }
    }
    
    /**
     * 内部命令执行逻辑
     */
    private void executeCommandInternal(String command, String executor, String commandId, long startTime) {
        try {
            CommandSender sender;
            
            // 确定命令执行者
            if ("console".equals(executor)) {
                sender = Bukkit.getConsoleSender();
            } else {
                // 如果是玩家UUID，查找对应玩家（暂不实现）
                sender = Bukkit.getConsoleSender();
            }
            
            // 创建自定义CommandSender来捕获输出
            CommandOutputCapture outputCapture = new CommandOutputCapture(sender);
            
            // 执行命令
            boolean success = Bukkit.dispatchCommand(outputCapture, command);
            
            // 计算执行时间
            long executionTime = System.currentTimeMillis() - startTime;
            
            // 获取命令输出
            String output = outputCapture.getOutput();
            if (output.isEmpty()) {
                output = success ? "Command executed successfully" : "Command execution failed";
            }
            
            // 发送执行结果
            sendCommandResult(commandId, success, output, executionTime);
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            sendCommandResult(commandId, false, "Error executing command: " + e.getMessage(), executionTime);
        }
    }
    
    /**
     * 发送命令执行结果
     */
    private void sendCommandResult(String commandId, boolean success, String output, long executionTime) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "command_result");
            
            JSONObject data = new JSONObject();
            data.put("commandId", commandId);
            data.put("success", success);
            data.put("output", output);
            data.put("executionTime", executionTime);
            
            message.put("data", data);
            message.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("命令执行完成 (ID: %s, 成功: %s, 耗时: %dms)", 
                commandId, success, executionTime));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送命令结果失败: " + e.getMessage());
        }
    }
    
    /**
     * 命令结果数据类
     */
    public static class CommandResult {
        private final boolean success;
        private final String output;
        private final long executionTime;
        
        public CommandResult(boolean success, String output, long executionTime) {
            this.success = success;
            this.output = output;
            this.executionTime = executionTime;
        }
        
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public long getExecutionTime() { return executionTime; }
    }
    
    /**
     * 命令输出捕获器
     */
    private static class CommandOutputCapture implements CommandSender {
        private final CommandSender delegate;
        private final StringBuilder output;
        
        public CommandOutputCapture(CommandSender delegate) {
            this.delegate = delegate;
            this.output = new StringBuilder();
        }
        
        @Override
        public void sendMessage(String message) {
            output.append(message).append("\n");
            // 也发送到原始sender（可选）
            // delegate.sendMessage(message);
        }
        
        @Override
        public void sendMessage(String... messages) {
            for (String message : messages) {
                sendMessage(message);
            }
        }
        
        @Override
        public void sendMessage(java.util.UUID sender, String message) {
            sendMessage(message);
        }
        
        @Override
        public void sendMessage(java.util.UUID sender, String... messages) {
            for (String message : messages) {
                sendMessage(message);
            }
        }
        
        public String getOutput() {
            return output.toString().trim();
        }
        
        // 委托其他方法到原始sender
        @Override
        public org.bukkit.Server getServer() { return delegate.getServer(); }
        
        @Override
        public String getName() { return delegate.getName(); }

        @Override
        public net.kyori.adventure.text.Component name() {
            return delegate.name();
        }
        
        @Override
        public boolean isPermissionSet(String name) { return delegate.isPermissionSet(name); }
        
        @Override
        public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return delegate.isPermissionSet(perm); }
        
        @Override
        public boolean hasPermission(String name) { return delegate.hasPermission(name); }
        
        @Override
        public boolean hasPermission(org.bukkit.permissions.Permission perm) { return delegate.hasPermission(perm); }
        
        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
            return delegate.addAttachment(plugin, name, value);
        }
        
        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
            return delegate.addAttachment(plugin);
        }
        
        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) {
            return delegate.addAttachment(plugin, name, value, ticks);
        }
        
        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
            return delegate.addAttachment(plugin, ticks);
        }
        
        @Override
        public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {
            delegate.removeAttachment(attachment);
        }
        
        @Override
        public void recalculatePermissions() {
            delegate.recalculatePermissions();
        }
        
        @Override
        public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
            return delegate.getEffectivePermissions();
        }
        
        @Override
        public boolean isOp() { return delegate.isOp(); }
        
        @Override
        public void setOp(boolean value) { delegate.setOp(value); }
        
        @Override
        public org.bukkit.command.CommandSender.Spigot spigot() {
            return delegate.spigot();
        }
    }
}
