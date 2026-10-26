package com.ultikits.ultitools.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.AccessDecision;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.utils.PluginInitiationUtils;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * Command execution manager.
 * Handles command execution requests received over the WebSocket connection.
 */
@ApiStatus.Internal
public class CommandExecutionManager {
    @SuppressWarnings("removal") // the kept v1 sender holds the deprecated v1 client until 6.4.0 (S-67)
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
     * {@code command.result.data.output} maxItems in the frozen 2.0.0 contract.
     */
    private static final int MAX_OUTPUT_LINES = 1000;

    /**
     * {@code command.result.data.output} items maxLength, counted in Unicode code points as the
     * schema and {@link com.ultikits.ultitools.utils.PayloadReader} count it.
     */
    private static final int MAX_OUTPUT_LINE_CHARS = 4096;

    /**
     * {@code command.result} payload message maxLength, counted in Unicode code points.
     */
    private static final int MAX_RESULT_MESSAGE_CHARS = 500;

    /**
     * {@code command.result.data.executionMs} maximum in the frozen contract.
     */
    private static final long MAX_EXECUTION_MS = 86400000L;

    /**
     * How long after a v2 command's result was taken its late output is still written to the
     * server console — see {@link CapturedFeedback}. Long enough for asynchronous command bodies
     * that wait on a database or the network; it bounds how long the framework answers for a
     * capturing sender that a command or another plugin kept.
     */
    static final long LATE_OUTPUT_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(10);

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
     * The v1 {@code command_result} text for a command the server console did not accept
     * ({@code Bukkit.dispatchCommand} returned {@code false}: an unknown command, or a command
     * whose executor reported a usage error). The v1 path still dispatches as the real console, so
     * Paper writes the reply to the console and this sentence stays true there. The v2 path
     * captures that reply instead and sends it — see {@link #executeCaptured} and
     * {@link #NOT_ACCEPTED_WITHOUT_REPLY}.
     */
    static final String NOT_ACCEPTED_OUTPUT = "The server console did not accept the command. "
            + "Any message it printed appears in the server log stream.";

    /**
     * The v2 {@code command_result} text for a command the server did not accept while printing
     * nothing the capturing sender could read — an unknown command whose refusal goes elsewhere,
     * or an executor that reports a usage error silently. When the server did print something,
     * {@link #rejectionMessage} sends those lines instead of this sentence.
     */
    static final String NOT_ACCEPTED_WITHOUT_REPLY = "The server console did not accept the command.";

    /**
     * Blocklist of dangerous commands that should not be executed remotely. This is the shipped
     * default, kept when {@code ultipanel.commands.blocklist} is absent from config.yml or when
     * its present value is rejected as not a list of strings — see {@link #loadConfiguration()}.
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
     * A present value is validated as a whole before anything is installed: it must be a list
     * whose every element is a string. The raw value is read, not Bukkit's converting
     * {@code getStringList}, because that getter turns a non-list into an empty list, drops
     * mapping, nested-list and null elements, and stringifies numbers and booleans — so its output
     * cannot show what the operator wrote, and installing it would silently replace the policy
     * with an empty or partial one. Any other shape (a scalar, a map, or a list with any
     * non-string element) rejects the whole value: one warning naming the key and the rejected
     * shape (the value's type, or the first offending element's position and type) is logged,
     * never the value itself, and the current in-memory blocklist is kept — the shipped default on
     * a newly constructed manager, the previous valid list on a repeated call. A valid list is
     * trimmed and lower-cased with {@link Locale#ROOT} into one new set, installed through a single
     * {@link #setBlockedCommands(Set)} call.
     * <p>
     * This method never writes config.yml or the loaded configuration: it neither saves nor repairs
     * an invalid value, so the operator's file and the in-memory section keep exactly what the
     * operator wrote.
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
            Object raw = instance.getConfig().get(BLOCKLIST_CONFIG_KEY);
            String rejection = describeInvalidBlocklist(raw);
            if (rejection != null) {
                instance.getLogger().log(Level.WARNING,
                        "Ignoring " + BLOCKLIST_CONFIG_KEY + " in config.yml: " + rejection
                                + "; keeping the current blocklist");
                return;
            }
            Set<String> normalized = new HashSet<>();
            for (Object entry : (List<?>) raw) {
                normalized.add(((String) entry).trim().toLowerCase(Locale.ROOT));
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
     * Describes why a raw blocklist value is not a list of strings, naming only its shape.
     *
     * @param raw the raw value at {@code ultipanel.commands.blocklist}
     * @return {@code null} for a list whose every element is a string; otherwise the value's type,
     *         or the first non-string element's 1-based position and type — never the value itself
     */
    private static String describeInvalidBlocklist(Object raw) {
        if (!(raw instanceof List)) {
            return "the value is " + describeShape(raw) + ", expected a list of strings";
        }
        List<?> elements = (List<?>) raw;
        for (int i = 0; i < elements.size(); i++) {
            Object element = elements.get(i);
            if (!(element instanceof String)) {
                return "element " + (i + 1) + " is " + describeShape(element) + ", expected a string";
            }
        }
        return null;
    }

    /**
     * Names a raw configuration value's type for a diagnostic, without its content.
     */
    private static String describeShape(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Map || value instanceof ConfigurationSection) {
            return "a map";
        }
        if (value instanceof List) {
            return "a list";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        return "a value of type " + value.getClass().getSimpleName();
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
     * @deprecated There is no replacement: the framework never calls it, and its own UltiCloud session
     *     (protocol 2.0.0) carries the panel requests. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    @SuppressWarnings("removal") // takes the deprecated v1 client; kept until 6.4.0 (S-67)
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        V1CloudNotice.once(CommandExecutionManager.class, "setWebSocketClient");
        this.webSocketClient = client;
    }
    
    /**
     * Receives one v2 command outcome, independently of the transport framing.
     * Framework failures propagate to the caller; only dispatch failures call {@link #failed(String)}.
     * Messages are non-empty and at most
     * {@value CommandExecutionManager#MAX_RESULT_MESSAGE_CHARS} code points, the
     * {@code command.result} payload bound.
     *
     * @since 6.3.1
     */
    @ApiStatus.Internal
    public interface CommandResultSink {
        /** Reports a blocklist refusal with the normalized matched entry and existing policy message. */
        void blocked(String matched, String message);

        /**
         * Reports synchronous feedback captured before dispatch returned, and main-thread execution
         * time. Feedback the command prints later is not part of the result: it is written to the
         * server console, and so reaches the log stream.
         */
        void completed(List<String> lines, boolean truncated, long executionMs);

        /**
         * Reports an empty command, a disabled capability, a refused command's captured reply or a
         * dispatch exception's message — never a stack trace.
         */
        void failed(String message);
    }

    /**
     * Executes a v2 request using Paper's console-permission feedback sender.
     * The caller owns acknowledgement, deduplication, lifecycle checks and transport byte limits.
     * The 6.3.0 validation order runs on the calling thread, so a refusal is answered without
     * waiting for the main thread; only the dispatch and its feedback capture are handed to the
     * injected executor, which must enqueue work onto the server main thread in arrival order.
     * Output is cut to the {@code command.result} schema bounds — {@value #MAX_OUTPUT_LINES} lines
     * of {@value #MAX_OUTPUT_LINE_CHARS} code points — and the flag reports that cut.
     * Only feedback printed before the dispatch returns is in the result. A line the command prints
     * later, for example from an asynchronous body, is written to the server console instead, as
     * 6.3.0 printed every panel command's output there; the log stream carries it from there,
     * redacted like every other console line (see {@link CapturedFeedback}).
     * This method only reads operator configuration and never rewrites it.
     *
     * @param commandLine command text, optionally prefixed with a slash
     * @param actor audit actor, or null for the panel
     * @param mainThread ordered server-main-thread executor
     * @param sink receives the command outcome
     * @since 6.3.1
     */
    public void execute(String commandLine, String actor, Executor mainThread, CommandResultSink sink) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            sink.failed("Command cannot be empty");
            return;
        }
        AccessDecision decision = isCommandAllowed(commandLine);
        if (!decision.isAllowed()) {
            // Keep this WARNING line — the same control the v1 path carries.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    FrameworkText.format("[远程命令] 已拦截: %s", commandLine));
            recordCommandDecision(commandLine, actor, decision.getMessage());
            sink.blocked(baseCommandName(commandLine), resultMessage(decision.getMessage()));
            return;
        }
        // Defensive: the router gates the capability before this handler runs. A wiring mistake
        // must not turn into an execution path, so the switch is checked here as well.
        if (!Capability.COMMANDS.isEnabled()) {
            String refusal = resultMessage(Capability.COMMANDS.refusalMessage());
            recordCommandDecision(commandLine, actor, refusal);
            sink.failed(refusal);
            return;
        }
        UltiTools.getInstance().getLogger().log(Level.INFO,
                FrameworkText.format("[远程命令] > %s", commandLine));
        recordCommandDecision(commandLine, actor, null);
        mainThread.execute(() -> executeCaptured(commandLine, sink));
    }

    /**
     * The blocklist key from a command line: first word, leading slash and namespace prefix
     * stripped, lower-cased — the same normalization {@link #isCommandAllowed(String)} applies.
     */
    private static String baseCommandName(String commandLine) {
        String name = commandLine.trim();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        name = name.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return name.substring(name.indexOf(':') + 1);
    }

    /**
     * Fits a result message inside the schema bound and never returns a blank one.
     */
    private static String resultMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "The command could not be completed.";
        }
        if (message.codePointCount(0, message.length()) <= MAX_RESULT_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, MAX_RESULT_MESSAGE_CHARS));
    }

    private void recordCommandDecision(String command, String actor, String refusal) {
        RemoteActionLog log = UltiTools.getInstance().getRemoteActionLog();
        if (log != null) {
            log.record(refusal == null
                    ? RemoteActionLog.Entry.allowed(Capability.COMMANDS, ACTION_EXECUTE_COMMAND,
                            command, resolveActor(actor))
                    : RemoteActionLog.Entry.denied(Capability.COMMANDS, ACTION_EXECUTE_COMMAND,
                            command, resolveActor(actor), refusal));
        }
    }

    private void executeCaptured(String commandLine, CommandResultSink sink) {
        // Paper's feedback sender shares the console's PermissibleBase, so the command runs with
        // console-equivalent permissions; both sendMessage(String) and sendMessage(Component)
        // arrive at the feedback object, which splits a component into one entry per printed line.
        // That sender never echoes to the console, so the feedback object writes whatever arrives
        // after the result was taken to the console itself.
        CapturedFeedback feedback = new CapturedFeedback(baseCommandName(commandLine),
                LATE_OUTPUT_WINDOW_NANOS, System::nanoTime, CommandExecutionManager::writeToConsole);
        CommandSender sender = Bukkit.getServer().createCommandSender(feedback);
        String command = commandLine.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        long started = System.nanoTime();
        boolean accepted;
        try {
            accepted = Bukkit.dispatchCommand(sender, command);
        } catch (Exception ex) {
            feedback.take();
            // The panel receives the message only, so the stack trace lives here or nowhere. The
            // command name is logged without its arguments: an argument can carry a secret.
            UltiTools.getInstance().getLogger().log(Level.WARNING,
                    FrameworkText.format("[远程命令] 命令执行异常: %s", baseCommandName(commandLine)), ex);
            sink.failed(resultMessage(ex.getMessage()));
            return;
        }
        List<String> lines = feedback.take();
        long elapsed = Math.min(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), MAX_EXECUTION_MS);
        if (!accepted) {
            sink.failed(rejectionMessage(lines));
            return;
        }
        BoundedLines bounded = boundLines(lines);
        sink.completed(bounded.lines, bounded.truncated, elapsed);
    }

    /**
     * Writes one late output line of a panel command to the server console: the server's own
     * logger, at {@code INFO}, with the text unchanged. The local console and log files keep the
     * text as printed, like every console line; the log stream picks the record up from the
     * {@code java.util.logging} root logger and redacts registered sensitive commands
     * ({@link com.ultikits.ultitools.websocket.SensitiveCommandRegistry}) on its way to UltiCloud,
     * exactly as for any other console line.
     */
    static void writeToConsole(String line) {
        Bukkit.getLogger().log(Level.INFO, line);
    }

    /**
     * The feedback of one v2 panel command, handed to Paper's capturing sender.
     * <p>
     * Until {@link #take()} every printed line is kept for the {@code command.result}. From then on
     * a line is written to the console through {@code console} instead, so output a command prints
     * after its dispatch returned (an asynchronous body, a scheduled reply) is not lost: it lands in
     * the result or on the console, exactly once, whichever thread prints it. The lock makes the
     * hand-over atomic; the console is written outside it.
     * <p>
     * <b>Bounded lifetime.</b> Taking the result releases the captured lines, so a sender that a
     * command or another plugin keeps holds only this small object. Late lines are forwarded for
     * {@code windowNanos} after the result was taken; after that they are dropped, and one WARNING
     * names the command (never its arguments, which can carry a secret) so the operator knows why.
     */
    static final class CapturedFeedback implements Consumer<Component> {
        private final String commandName;
        private final long windowNanos;
        private final LongSupplier clock;
        private final Consumer<String> console;
        private final Object lock = new Object();
        /** The lines kept for the result, or {@code null} once taken. Guarded by {@link #lock}. */
        private List<String> captured = new ArrayList<>();
        /** The {@link #clock} reading when the result was taken. Guarded by {@link #lock}. */
        private long takenAt;
        /** Whether the dropped-output warning was logged. Guarded by {@link #lock}. */
        private boolean expiryReported;

        CapturedFeedback(String commandName, long windowNanos, LongSupplier clock, Consumer<String> console) {
            this.commandName = commandName;
            this.windowNanos = windowNanos;
            this.clock = clock;
            this.console = console;
        }

        @Override
        public void accept(Component component) {
            String text = PlainTextComponentSerializer.plainText().serialize(component);
            if (text.isEmpty()) {
                return;
            }
            String[] lines = text.split("\\r\\n|\\r|\\n");
            boolean reportExpiry = false;
            synchronized (lock) {
                if (captured != null) {
                    Collections.addAll(captured, lines);
                    return;
                }
                if (clock.getAsLong() - takenAt > windowNanos) {
                    if (expiryReported) {
                        return;
                    }
                    expiryReported = true;
                    reportExpiry = true;
                }
            }
            if (reportExpiry) {
                UltiTools.getInstance().getLogger().log(Level.WARNING,
                        "[Remote command] Output of the panel command '" + commandName + "' arrived more than "
                                + TimeUnit.NANOSECONDS.toMinutes(windowNanos) + " minutes after it returned and "
                                + "was not written to the console.");
                return;
            }
            for (String line : lines) {
                console.accept(line);
            }
        }

        /**
         * Takes the lines printed so far for the result and switches later lines to the console.
         *
         * @return the captured lines; empty when they were already taken
         */
        List<String> take() {
            synchronized (lock) {
                List<String> taken = captured;
                if (taken == null) {
                    return new ArrayList<>();
                }
                captured = null;
                takenAt = clock.getAsLong();
                return taken;
            }
        }
    }

    /**
     * Turns the feedback a refused command printed into the failure message.
     * <p>
     * With the capturing sender the server's own reply — Paper's unknown-command line, a Bukkit
     * command's usage text — arrives here instead of reaching the console, so it is the only reply
     * the panel can get and it must not be dropped. The lines are bounded exactly as successful
     * output is ({@value #MAX_OUTPUT_LINE_CHARS} code points per line, at most
     * {@value #MAX_OUTPUT_LINES} lines) and then cut to the message bound, which is the schema
     * slot they travel in.
     *
     * @param lines feedback captured before {@code dispatchCommand} returned
     * @return the joined reply, or {@link #NOT_ACCEPTED_WITHOUT_REPLY} when nothing was printed
     */
    private static String rejectionMessage(List<String> lines) {
        if (lines.isEmpty()) {
            return NOT_ACCEPTED_WITHOUT_REPLY;
        }
        return resultMessage(String.join("\n", boundLines(lines).lines));
    }

    /**
     * Copies {@code lines} under the {@code command.result} output caps: every line cut to
     * {@value #MAX_OUTPUT_LINE_CHARS} code points and the list to {@value #MAX_OUTPUT_LINES}
     * entries, reporting whether anything was cut.
     */
    private static BoundedLines boundLines(List<String> lines) {
        boolean truncated = false;
        List<String> bounded = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.codePointCount(0, line.length()) > MAX_OUTPUT_LINE_CHARS) {
                bounded.add(line.substring(0, line.offsetByCodePoints(0, MAX_OUTPUT_LINE_CHARS)));
                truncated = true;
            } else {
                bounded.add(line);
            }
        }
        if (bounded.size() > MAX_OUTPUT_LINES) {
            return new BoundedLines(new ArrayList<>(bounded.subList(0, MAX_OUTPUT_LINES)), true);
        }
        return new BoundedLines(bounded, truncated);
    }

    /** Output lines inside the schema caps, plus whether the caps forced a cut. */
    private static final class BoundedLines {
        private final List<String> lines;
        private final boolean truncated;

        private BoundedLines(List<String> lines, boolean truncated) {
            this.lines = lines;
            this.truncated = truncated;
        }
    }

    /**
     * Executes a command received from the panel.
     * @deprecated Use execute(String, String, Executor, CommandResultSink), which the protocol 2.0.0
     *     session calls for a panel command; this v1 handler replies only over the client handed to
     *     setWebSocketClient. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
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
            // Paper's AsyncCatcher will reject async dispatch. While UltiTools is disabling the
            // hand-off is dropped with one FINE line instead (#621).
            PluginInitiationUtils.scheduleUnlessDisabling("remote command",
                () -> Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                    executeCommandInternal(command, executor, commandId, startTime);
                }));

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
     * @deprecated There is no replacement: the protocol 2.0.0 command path reports through
     *     CommandResultSink. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
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
