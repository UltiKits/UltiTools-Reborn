package com.ultikits.ultitools.manager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * Command execution manager.
 * Handles command execution requests received over the WebSocket connection.
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
     * The {@link RemoteActionLog.Entry#getAction()} literal for every entry this class records.
     */
    private static final String ACTION_EXECUTE_COMMAND = "execute_command";

    /**
     * The {@code command_result} text for a command the server console accepted. A panel command
     * is typed into the server console (maintainer decision, 6.3.0): it runs as the console sender,
     * and Paper sends a console command's replies to the console itself, never to a sender the
     * framework could read, so the result cannot carry them. They reach the panel through the log
     * stream, which mirrors the console ({@code ConsoleMirror}).
     */
    static final String DISPATCHED_OUTPUT = "Command dispatched to the server console. "
            + "Its output appears in the server log stream.";

    /**
     * The {@code command_result} text for a command the server console did not accept
     * ({@code Bukkit.dispatchCommand} returned {@code false}: an unknown command, or a command
     * whose executor reported a usage error).
     */
    static final String NOT_ACCEPTED_OUTPUT = "The server console did not accept the command. "
            + "Any message it printed appears in the server log stream.";

    /**
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
                    normalized.add(entry.trim().toLowerCase(Locale.ROOT));
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
     * Extracts the base command (the first word) and strips any namespace prefix (e.g.
     * {@code bukkit:op} -> {@code op}, {@code minecraft:stop} -> {@code stop}) BEFORE checking it
     * against the operator-configured blocklist at {@code ultipanel.commands.blocklist} in
     * {@code plugins/UltiTools/config.yml} — so both the bare command and its namespaced form are
     * blocked; the stripping order is what makes that true. The blocklist is editable in both
     * directions — an operator may remove any of the ten shipped defaults, add to them, or clear
     * the list entirely — and there is deliberately no unoverridable floor beneath it (D-04): a
     * floor would constrain the operator without constraining an attacker who already holds the
     * operator's identity, since the same outcomes remain reachable through other commands or a
     * third-party plugin's own admin commands.
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
        String baseCommand = trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);

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
     * Sets the WebSocket client.
     * @param client the WebSocket client
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }
    
    /**
     * Executes a command received from the panel.
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

            // Security check: verify command is not blocked
            AccessDecision decision = isCommandAllowed(command);
            if (!decision.isAllowed()) {
                // Keep this WARNING line — it is the control that proves the interception path
                // was already observable before D-05/D-22. The panel-facing message no longer
                // builds its own truncated command string: decision.getMessage() already names
                // the resolved base command plus its config key and file (D-05).
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    FrameworkText.format("[远程命令] 已拦截: %s", command));
                RemoteActionLog deniedLog = UltiTools.getInstance().getRemoteActionLog();
                if (deniedLog != null) {
                    deniedLog.record(RemoteActionLog.Entry.denied(Capability.COMMANDS,
                            ACTION_EXECUTE_COMMAND, command, resolveActor(executor), decision.getMessage()));
                }
                sendCommandResult(commandId, false, decision.getMessage(), 0);
                return;
            }

            // Record command execution start time
            long startTime = System.currentTimeMillis();

            UltiTools.getInstance().getLogger().log(Level.INFO,
                FrameworkText.format("[远程命令] > %s", command));

            // Record the policy decision BEFORE the dispatch hop, not inside it or after it
            // (D-22). The log records the decision, not the execution result — a decision
            // recorded only after a successful Bukkit.dispatchCommand would omit exactly the
            // commands that crashed the server, which is the forensics case this log exists for.
            RemoteActionLog allowedLog = UltiTools.getInstance().getRemoteActionLog();
            if (allowedLog != null) {
                allowedLog.record(RemoteActionLog.Entry.allowed(Capability.COMMANDS,
                        ACTION_EXECUTE_COMMAND, command, resolveActor(executor)));
            }

            // Bukkit.dispatchCommand() MUST run on the main server thread.
            // Paper's AsyncCatcher will reject async dispatch.
            Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                executeCommandInternal(command, executor, commandId, startTime);
            });

        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, FrameworkText.format("执行命令时发生错误: %s", e.getMessage()));
            String commandId = commandData.has("commandId") && !commandData.get("commandId").isJsonNull() 
                ? commandData.get("commandId").getAsString() : null;
            sendCommandResult(commandId, false, "Internal error: " + e.getMessage(), 0);
        }
    }
    
    /**
     * Internal command execution logic.
     */
    private void executeCommandInternal(String command, String executor, String commandId, long startTime) {
        try {
            CommandSender sender;
            
            // Determine the command executor
            if ("console".equals(executor)) {
                sender = Bukkit.getConsoleSender();
            } else {
                // If it's a player UUID, look up the corresponding player (not yet implemented)
                sender = Bukkit.getConsoleSender();
            }
            
            // Dispatched as the console sender itself, exactly as if typed into the console. Paper
            // replaces any ConsoleCommandSender with the real console source before running the
            // command, so no wrapper could read the replies; they appear in the log stream.
            boolean success = Bukkit.dispatchCommand(sender, command);

            // Calculate execution time
            long executionTime = System.currentTimeMillis() - startTime;

            if (!success) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                    FrameworkText.format("[远程命令] 命令执行失败: %s", command));
            }

            String output = success ? DISPATCHED_OUTPUT : NOT_ACCEPTED_OUTPUT;

            // Send execution result
            sendCommandResult(commandId, success, output, executionTime);
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                FrameworkText.format("[远程命令] 命令执行异常: %s", command), e);
            sendCommandResult(commandId, false, "Error executing command: " + e.getMessage(), executionTime);
        }
    }
    
    /**
     * The action-log {@code actor} field — the inbound {@code executor} field verbatim, or the
     * literal {@code "panel"} when absent. Mirrors
     * {@code PluginInitiationUtils.resolveActor(JsonObject)} exactly: the framework cannot
     * attribute a remote command to an individual panel operator today (see
     * {@link RemoteActionLog.Entry}'s javadoc), so this never invents a per-operator identity.
     */
    private static String resolveActor(String executor) {
        return executor != null ? executor : "panel";
    }

    /**
     * Sends the command execution result.
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
            UltiTools.getInstance().getLogger().log(Level.WARNING, FrameworkText.format("发送命令结果失败: %s", e.getMessage()));
        }
    }
    
    /**
     * Command result data class.
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
}
