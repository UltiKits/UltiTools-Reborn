package com.ultikits.ultitools.manager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * 命令执行管理器
 * 负责处理来自WebSocket的命令执行请求
 */
@ApiStatus.Internal
public class CommandExecutionManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final ConcurrentHashMap<String, CompletableFuture<CommandResult>> pendingCommands;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * The literal config path {@link #loadConfiguration()} reads and every configurable refusal
     * this class produces names — {@code ultipanel.commands.blocklist}.
     */
    private static final String BLOCKLIST_CONFIG_KEY = "ultipanel.commands.blocklist";

    /**
     * 远程命令执行黑名单
     * Blocklist of dangerous commands that should not be executed remotely. This is the shipped
     * default, used only when {@code ultipanel.commands.blocklist} is absent from config.yml —
     * see {@link #loadConfiguration()}.
     */
    private Set<String> blockedCommands = new HashSet<>(Arrays.asList(
        "op", "deop", "stop", "restart", "reload", "ban-ip",
        "pardon-ip", "whitelist", "save-off", "save-all"
    ));

    public CommandExecutionManager() {
        this.pendingCommands = new ConcurrentHashMap<>();
        loadConfiguration();
    }

    /**
     * Loads {@code ultipanel.commands.blocklist} from config.yml, copying
     * {@code ErrorReportCollector.loadConfiguration()}'s exact shape: a null-guard on
     * {@link UltiTools#getInstance()}, one try/catch whose failure branch keeps the current
     * blocklist and logs a warning naming the key.
     * <p>
     * Distinguishes an absent key from an explicit empty list via {@code isSet(path)} — Bukkit's
     * {@code getStringList} returns an empty list for both, and collapsing that distinction would
     * make D-03's honest "block nothing" escape value silently impossible (T-06-12). The shipped
     * ten-command default applies only when the key is absent; an explicit empty list is honored
     * as the operator deliberately granting every command.
     * <p>
     * 从 config.yml 加载 {@code ultipanel.commands.blocklist}，复刻
     * {@code ErrorReportCollector.loadConfiguration()} 的结构：对 {@link UltiTools#getInstance()}
     * 判空，单个 try/catch，失败分支保留当前黑名单并记录警告。通过 {@code isSet(path)} 区分「键缺失」
     * 与「显式空列表」——Bukkit 的 {@code getStringList} 对二者都返回空列表，若不加区分，D-03 的
     * 「清空即放行一切」这一诚实的逃生阀就会被悄悄堵死。出厂十条默认值仅在键缺失时生效。
     */
    public final void loadConfiguration() {
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance == null) {
                return;
            }
            if (!instance.getConfig().isSet(BLOCKLIST_CONFIG_KEY)) {
                // Absent key — keep the shipped ten-command default this field was constructed with.
                return;
            }
            List<String> configured = instance.getConfig().getStringList(BLOCKLIST_CONFIG_KEY);
            Set<String> normalized = new HashSet<>();
            for (String entry : configured) {
                if (entry != null) {
                    normalized.add(entry.trim().toLowerCase());
                }
            }
            setBlockedCommands(normalized);
        } catch (Exception e) {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                instance.getLogger().log(Level.WARNING,
                        "Failed to load " + BLOCKLIST_CONFIG_KEY + ", keeping current blocklist: "
                                + e.getMessage());
            }
        }
    }

    /**
     * 设置命令黑名单
     * Set the blocklist of commands that should not be executed remotely. This is now
     * {@link #loadConfiguration()}'s config-load target, not just a test-only seam — it has zero
     * production call sites of its own, but every operator-configured blocklist reaches this
     * class through it.
     *
     * @param commands Set of command names to block (case-insensitive)
     */
    public void setBlockedCommands(Set<String> commands) {
        this.blockedCommands = commands;
    }

    /**
     * Whether a command is allowed for remote execution.
     * <p>
     * Extracts the base command (first word, namespace-prefix stripped) and checks it against the
     * operator-configured blocklist at {@code ultipanel.commands.blocklist} in
     * {@code plugins/UltiTools/config.yml}. The blocklist is editable in both directions — an
     * operator may remove any of the ten shipped defaults, add to them, or clear the list
     * entirely — and there is deliberately no unoverridable floor beneath it (D-04): a floor
     * would constrain the operator without constraining an attacker who already holds the
     * operator's identity, since the same outcomes remain reachable through other commands or a
     * third-party plugin's own admin commands.
     * <p>
     * 检查某条命令是否允许远程执行。提取基础命令（去除命名空间前缀后的第一个词）并对照操作员可
     * 配置的黑名单——{@code plugins/UltiTools/config.yml} 中的
     * {@code ultipanel.commands.blocklist}。该黑名单双向可编辑：操作员可以移除任意一条出厂默认
     * 项、添加新项，或将其完全清空。这里刻意不设置任何不可覆盖的底线（D-04）——设置底线只会约束
     * 操作员本人，而不会约束已经掌握了操作员身份的攻击者，因为攻击者仍可以通过其他命令或第三方
     * 插件自己的管理命令达到同样的效果。
     *
     * @param command The command to check (with or without leading slash)
     * @return an {@link AccessDecision} naming why a refused command was refused
     */
    public AccessDecision isCommandAllowed(String command) {
        if (command == null || command.trim().isEmpty()) {
            return AccessDecision.deniedNonConfigurable("command is empty");
        }

        String trimmed = command.trim();
        // Strip leading slash
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }

        // Extract base command (first word before space)
        String baseCommand = trimmed.split("\\s+")[0].toLowerCase();

        // Strip namespace prefix (e.g., "bukkit:op" -> "op", "minecraft:stop" -> "stop")
        int colonIndex = baseCommand.indexOf(':');
        if (colonIndex >= 0) {
            baseCommand = baseCommand.substring(colonIndex + 1);
        }

        if (blockedCommands.contains(baseCommand)) {
            return AccessDecision.deniedConfigurable(
                    "command '" + baseCommand + "' is on the remote command blocklist",
                    BLOCKLIST_CONFIG_KEY);
        }
        return AccessDecision.allowed();
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
    public void executeCommand(JsonObject commandData) {
        try {
            String command = commandData.has("command") && !commandData.get("command").isJsonNull() 
                ? commandData.get("command").getAsString() : null;
            String executor = commandData.has("executor") && !commandData.get("executor").isJsonNull()
                ? commandData.get("executor").getAsString() : null;
            String commandId = commandData.has("commandId") && !commandData.get("commandId").isJsonNull()
                ? commandData.get("commandId").getAsString() : null;

            if (command == null || command.trim().isEmpty()) {
                sendCommandResult(commandId, false, "Command cannot be empty", 0);
                return;
            }

            // 安全检查：检查命令是否在黑名单中
            // Security check: verify command is not blocked
            AccessDecision decision = isCommandAllowed(command);
            if (!decision.isAllowed()) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("[远程命令] 已拦截: %s", command));
                String blockedCmd = command.trim().startsWith("/")
                    ? command.trim().substring(1).split("\\s+")[0]
                    : command.trim().split("\\s+")[0];
                sendCommandResult(commandId, false, "Command blocked by security policy: " + blockedCmd, 0);
                return;
            }
            
            // 记录命令执行开始时间
            long startTime = System.currentTimeMillis();
            
            UltiTools.getInstance().getLogger().log(Level.INFO,
                String.format("[远程命令] > %s", command));
            
            // Bukkit.dispatchCommand() MUST run on the main server thread.
            // Paper's AsyncCatcher will reject async dispatch.
            Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                executeCommandInternal(command, executor, commandId, startTime);
            });
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "执行命令时发生错误: " + e.getMessage());
            String commandId = commandData.has("commandId") && !commandData.get("commandId").isJsonNull() 
                ? commandData.get("commandId").getAsString() : null;
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
            
            // 执行命令 (CommandOutputCapture implements ConsoleCommandSender
            // so Paper's Brigadier dispatcher recognizes the sender type)
            boolean success = Bukkit.dispatchCommand(outputCapture, command);

            // 计算执行时间
            long executionTime = System.currentTimeMillis() - startTime;

            if (!success) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    String.format("[远程命令] 命令执行失败: %s", command));
            }
            
            // 获取命令输出
            String output = outputCapture.getOutput();
            if (output.isEmpty()) {
                output = success ? "Command executed successfully" : "Command execution failed";
            }
            
            // 发送执行结果
            sendCommandResult(commandId, success, output, executionTime);
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                String.format("[远程命令] 命令执行异常: %s", command), e);
            sendCommandResult(commandId, false, "Error executing command: " + e.getMessage(), executionTime);
        }
    }
    
    /**
     * 发送命令执行结果
     */
    private void sendCommandResult(String commandId, boolean success, String output, long executionTime) {
        try {
            JsonObject message = new JsonObject();
            message.addProperty("type", "command_result");
            
            JsonObject data = new JsonObject();
            data.addProperty("commandId", commandId);
            data.addProperty("success", success);
            data.addProperty("output", output);
            data.addProperty("executionTime", executionTime);
            
            message.add("data", data);
            message.addProperty("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(message);

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
     * Implements ConsoleCommandSender (not just CommandSender) because Paper 1.21+
     * requires the sender to be a recognized type when dispatching commands through
     * Brigadier. Paper's VanillaCommandWrapper.getListener() checks instanceof for
     * ConsoleCommandSender, Player, etc. A plain CommandSender implementation would
     * cause dispatchCommand() to return false.
     */
    private static class CommandOutputCapture implements org.bukkit.command.ConsoleCommandSender {
        private final CommandSender delegate;
        private final StringBuilder output;

        public CommandOutputCapture(CommandSender delegate) {
            this.delegate = delegate;
            this.output = new StringBuilder();
        }

        @Override
        public void sendMessage(String message) {
            output.append(message).append("\n");
            // Log via Bukkit root logger (no [UltiTools] prefix) so it appears
            // in both server console and web console (via SystemLogHandler → JUL)
            String clean = org.bukkit.ChatColor.stripColor(message);
            if (clean != null && !clean.isEmpty()) {
                Bukkit.getLogger().info(clean);
            }
        }

        @Override
        public void sendMessage(net.kyori.adventure.text.Component message) {
            // Paper 1.21+ commands use Adventure Component API
            try {
                String plain = net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(message);
                output.append(plain).append("\n");
                if (!plain.isEmpty()) {
                    Bukkit.getLogger().info(plain);
                }
            } catch (Exception e) {
                String fallback = message.toString();
                output.append(fallback).append("\n");
                Bukkit.getLogger().info(fallback);
            }
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

        // Conversable interface methods (required by ConsoleCommandSender)
        @Override
        public boolean isConversing() {
            if (delegate instanceof org.bukkit.conversations.Conversable) {
                return ((org.bukkit.conversations.Conversable) delegate).isConversing();
            }
            return false;
        }

        @Override
        public void acceptConversationInput(String input) {
            if (delegate instanceof org.bukkit.conversations.Conversable) {
                ((org.bukkit.conversations.Conversable) delegate).acceptConversationInput(input);
            }
        }

        @Override
        public boolean beginConversation(org.bukkit.conversations.Conversation conversation) {
            if (delegate instanceof org.bukkit.conversations.Conversable) {
                return ((org.bukkit.conversations.Conversable) delegate).beginConversation(conversation);
            }
            return false;
        }

        @Override
        public void abandonConversation(org.bukkit.conversations.Conversation conversation) {
            if (delegate instanceof org.bukkit.conversations.Conversable) {
                ((org.bukkit.conversations.Conversable) delegate).abandonConversation(conversation);
            }
        }

        @Override
        public void abandonConversation(org.bukkit.conversations.Conversation conversation, org.bukkit.conversations.ConversationAbandonedEvent details) {
            if (delegate instanceof org.bukkit.conversations.Conversable) {
                ((org.bukkit.conversations.Conversable) delegate).abandonConversation(conversation, details);
            }
        }

        @Override
        public void sendRawMessage(String message) {
            output.append(message).append("\n");
            String clean = org.bukkit.ChatColor.stripColor(message);
            if (clean != null && !clean.isEmpty()) {
                Bukkit.getLogger().info(clean);
            }
        }

        @Override
        public void sendRawMessage(java.util.UUID sender, String message) {
            sendRawMessage(message);
        }

        // CommandSender delegate methods
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
