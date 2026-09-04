package com.ultikits.ultitools.abstracts.command;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;
import com.ultikits.ultitools.abstracts.command.parser.TypeParserRegistry;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.abstracts.data.AuditableDataEntity;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.utils.ReflectionUtil;

import lombok.Getter;

/**
 * Base command executor with improved architecture using Chain of Responsibility pattern.
 * This class provides a cleaner, more extensible command handling system.
 * <p>
 * The default validator chain applies four validators in order: sender type (driven by the
 * class-level {@code @CmdTarget}, overridable per method), permission and operator requirement
 * (driven by {@code @CmdExecutor}), cooldown (applied only after a successful invocation), and
 * usage locking (acquired and released around the call in a try-finally). Execution flow in
 * {@link #onCommand}: help short-circuit, immutable {@link CommandContext} construction, scored
 * method matching via {@link #matchMethod}, parameter parsing, the validator chain, an
 * argument-count check, then invocation. Subclasses MUST implement {@link #handleHelp}.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Invokes the mapped @CmdMapping method -- see 08-GATE05-TRIAGE.md
public abstract class BaseCommandExecutor implements TabExecutor {
    
    private final BiMap<String, Method> mappings = HashBiMap.create();
    
    @Getter
    private final ValidatorChain validatorChain;
    
    @Getter
    private final CooldownValidator cooldownValidator;
    
    @Getter
    private final UsageLockValidator lockValidator;
    
    private final TypeParserRegistry parserRegistry;
    
    /**
     * Creates a new command executor with default validators.
     */
    public BaseCommandExecutor() {
        this.parserRegistry = TypeParserRegistry.getInstance();
        this.cooldownValidator = new CooldownValidator();
        this.lockValidator = new UsageLockValidator();
        this.validatorChain = createDefaultValidatorChain();
        scanCommandMappings();
    }
    
    /**
     * Creates a new command executor with a custom validator chain.
     * <p>
     * This is the other half of the construction surface {@link #createDefaultValidatorChain()}
     * warns about: a chain supplied directly here is subject to the exact same load-time
     * contract. If any {@code @CmdMapping} method on this class -- or the class itself -- declares
     * {@code @CmdCD} while {@code validatorChain} holds no {@code CooldownValidator}, or
     * declares {@code @UsageLimit} (any scope but {@code NONE}) while it holds no
     * {@code UsageLockValidator}, the module is refused at load, naming this class and the
     * offending mapping method (SILENT-11 / D-01, D-04; enforced by
     * {@code PluginManager.validateCommandExecutorContract}). There is no opt-out: Phase 3 D-08's
     * module-granularity isolation is the accepted escape hatch -- only this module fails to
     * load, every other module still starts.
     *
     * @param validatorChain the custom validator chain
     */
    public BaseCommandExecutor(ValidatorChain validatorChain) {
        this.parserRegistry = TypeParserRegistry.getInstance();
        this.cooldownValidator = new CooldownValidator();
        this.lockValidator = new UsageLockValidator();
        this.validatorChain = validatorChain;
        scanCommandMappings();
    }
    
    /**
     * Creates the default validator chain with standard validators.
     * Override this method to customize the validation pipeline.
     * <p>
     * An override that drops {@code CooldownValidator} or {@code UsageLockValidator} from the
     * chain while a mapping on this class -- or the class itself -- declares {@code @CmdCD} or
     * {@code @UsageLimit} (any scope but {@code NONE}) respectively IS refused at load, naming
     * the offending class and, when known, the offending mapping method (SILENT-11 / D-01, D-04;
     * enforced by {@code PluginManager.validateCommandExecutorContract}, hooked at the end of
     * container assembly, before any command reaches Bukkit). There is no opt-out: no system
     * property, config key, or annotation attribute disables this check. Phase 3 D-08's
     * module-granularity isolation is the accepted escape hatch instead -- only the offending
     * module fails to load, every other module still starts.
     *
     * @return the validator chain
     */
    protected ValidatorChain createDefaultValidatorChain() {
        ValidatorChain.Builder builder = ValidatorChain.builder();
        
        // Add sender type validator from class annotation
        CmdTarget cmdTarget = this.getClass().getAnnotation(CmdTarget.class);
        builder.add(SenderTypeValidator.fromAnnotation(cmdTarget));
        
        // Add permission validator from class annotation
        CmdExecutor cmdExecutor = this.getClass().getAnnotation(CmdExecutor.class);
        builder.add(PermissionValidator.fromAnnotation(cmdExecutor));
        
        // Add cooldown and lock validators
        builder.add(cooldownValidator);
        builder.add(lockValidator);

        return builder.build();
    }

    /**
     * Releases {@link #cooldownValidator}/{@link #lockValidator} from {@link
     * PlayerCacheManager} tracking and bulk-clears {@link UsageLockValidator}'s locks when this
     * executor is torn down (module unload).
     * <p>
     * Reached through the framework's EXISTING {@code @PreDestroy} container lifecycle --
     * {@code PluginManager.unregister(plugin)} already calls {@code plugin.getContext().close()},
     * which invokes every singleton bean's {@code @PreDestroy} methods -- rather than a second,
     * purpose-built unload hook (GEN-08, D-03). {@link #cooldownValidator}/{@link #lockValidator}
     * are registered with {@link PlayerCacheManager} SEPARATELY from this executor bean itself
     * (their sweepable state lives on them, not on this class), so the pre-existing per-bean
     * {@code playerCacheManager.unregisterBean(bean)} loop already in {@code
     * PluginManager.unregister(...)} -- which only ever sees the beans a plugin's own container
     * constructed -- never reaches them on its own; this hook is what does.
     * <p>
     * {@link UsageLockValidator#clearAllLocks()} has no per-player analogue and, unlike {@link
     * UsageLockValidator#clearPlayerLocks(java.util.UUID)}/{@link
     * CooldownValidator#clearCooldowns(java.util.UUID)}, no quit-path call site either (GEN-08)
     * -- module unload is the correct point to bulk-release whatever this specific executor
     * instance's locks still held at teardown.
     *
     * @since 6.3.0
     */
    // @PreDestroy methods are invoked reflectively by SimpleContainer.invokePreDestroyMethods
    // (which calls setAccessible(true) before invoking), so PMD cannot see this private method's
    // only call site and misreports it as unused.
    @PreDestroy
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void unregisterValidatorsFromPlayerCache() {
        PlayerCacheManager.tryUnregister(cooldownValidator);
        PlayerCacheManager.tryUnregister(lockValidator);
        lockValidator.clearAllLocks();
    }

    /**
     * Scans methods for command mappings.
     */
    private void scanCommandMappings() {
        // Walk the hierarchy: on an AOP proxy, getDeclaredMethods() returns only the intercepted
        // overrides, so scanning it directly would drop every other @CmdMapping on the class and
        // silently disable those subcommands. See issue #190.
        // getAllMethods() lists subclass overrides before the methods they hide, so the first
        // insertion for a given format is always the most specific one. putIfAbsent (rather than
        // put) keeps that first entry: if a subclass declares its own @CmdMapping method reusing a
        // parent's format string, the subclass's method wins, matching normal override semantics.
        for (Method method : ReflectionUtil.getAllMethods(this.getClass())) {
            if (method.isAnnotationPresent(CmdMapping.class)) {
                CmdMapping mapping = method.getAnnotation(CmdMapping.class);
                mappings.putIfAbsent(mapping.format(), method);
            }
        }
    }
    
    /**
     * Handles the help command. Override to provide custom help.
     *
     * @param sender the command sender
     */
    protected abstract void handleHelp(CommandSender sender);

    /**
     * Gets the help command string.
     *
     * @return the help command
     */
    protected String getHelpCommand() {
        return "help";
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        // Handle help command
        if (args.length == 1 && getHelpCommand().equals(args[0])) {
            handleHelp(sender);
            return true;
        }
        
        // Create command context
        // WR-02 (05-REVIEW.md): this.getClass() is the concrete executor class -- the SAME class
        // PluginManager's load-time contract gate inspects (executor.getClass()) -- captured
        // here so CooldownValidator/UsageLockValidator can resolve a class-level @CmdCD/
        // @UsageLimit against it instead of only Method#getDeclaringClass(), which for an
        // inherited, unoverridden @CmdMapping method is an ancestor, not this class.
        CommandContext context = CommandContext.builder()
                .sender(sender)
                .command(command)
                .alias(alias)
                .rawArgs(args)
                .executorClass(this.getClass())
                .build();
        
        // Match method
        Method method = matchMethod(args);
        if (method == null) {
            sender.sendMessage(ChatColor.RED + String.format(
                    UltiTools.getInstance().i18n("未知指令，请使用/%s %s获取帮助"),
                    command.getName(), getHelpCommand()));
            handleHelp(sender);
            return true;
        }
        
        // Update context with matched method
        String format = mappings.inverse().get(method);
        context = context.withMatchedMethod(method, format);
        
        // Parse parameters
        Map<String, String[]> parsedParams = parseParameters(args, format);
        context = context.withParsedParams(parsedParams);
        
        // Validate
        ValidatorChain.ChainValidationResult validationResult = validatorChain.validate(context);
        if (!validationResult.isValid()) {
            String errorMsg = validationResult.getErrorMessage();
            if (errorMsg != null) {
                sender.sendMessage(errorMsg);
            }
            return true;
        }
        
        // Check parameter count
        if (!validateParameterCount(args, format, sender, command)) {
            return true;
        }
        
        // Build method parameters
        Object[] methodParams = buildMethodParams(context, method);
        if (methodParams == null) {
            return true;
        }
        
        // Execute command
        executeCommand(context, method, methodParams, validationResult);

        return true;
    }

    /**
     * Executes the command method.
     * Supports both synchronous and asynchronous execution via @AsyncCommand or @RunAsync.
     *
     * @param context          the command context
     * @param method           the method to execute
     * @param params           the method parameters
     * @param validationResult the chain result produced by {@code onCommand}'s validation step
     *                         for THIS invocation; its
     *                         {@link ValidatorChain.ChainValidationResult#getPassedValidators()}
     *                         is the sole source that drives each validator's
     *                         {@link CommandValidator#onComplete(CommandContext, boolean)} call --
     *                         see D-01. Never recomputed here: re-validating would run every
     *                         validator twice and re-trigger their gates (e.g. a second lock
     *                         acquisition attempt).
     */
    protected void executeCommand(CommandContext context, Method method, Object[] params,
                                   ValidatorChain.ChainValidationResult validationResult) {
        // Check for async annotations
        AsyncCommand asyncCommand = method.getAnnotation(AsyncCommand.class);
        boolean isAsync = asyncCommand != null || method.isAnnotationPresent(RunAsync.class);

        // Capture trigger context BEFORE async dispatch (player info as immutable strings)
        final TriggerContext triggerCtx = TriggerContext.command(context.getSender(),
                method.getName());

        // The validators that actually ran (and passed) for THIS invocation. Per D-01, "in the
        // chain" and "has side effects" are the same fact by construction: post-actions are
        // driven from this list instead of naming cooldownValidator/lockValidator by field, so a
        // custom ValidatorChain that omits a validator also omits its side effect.
        final List<CommandValidator> ranValidators = validationResult != null
                ? validationResult.getPassedValidators()
                : Collections.emptyList();

        // WIRE-12/D-13: shared between the command body's own completion and the timeout
        // watcher armed below. Whichever side wins the CAS is the ONLY side that reports to
        // the sender -- the loser is a no-op. Read/set from two different threads (the async
        // worker running the body and the delayed async watcher task), so this MUST be an
        // atomic, not a plain boolean. Harmless when timeout() <= 0: no watcher is armed, so
        // nothing else ever touches this flag and the body's own CAS below is a pure no-op.
        final AtomicBoolean reported = new AtomicBoolean(false);

        BukkitRunnable runnable = new BukkitRunnable() {
            @Override
            public void run() {
                invokeCommandBody(context, method, params, ranValidators, triggerCtx, reported);
            }
        };

        if (isAsync) {
            dispatchAsyncCommand(context, asyncCommand, runnable, reported);
        } else {
            runnable.runTask(UltiTools.getInstance());
        }
    }

    /**
     * Runs the matched command method plus its surrounding bookkeeping (audit-user context,
     * post-action validator hooks, error reporting, the WIRE-12 completion flag) -- the body of
     * the {@link BukkitRunnable} {@link #executeCommand} schedules.
     * <p>
     * Split out of {@code executeCommand} purely to bring the enclosing method's
     * NPathComplexity back under threshold; the control flow below, and therefore the runtime
     * behaviour, is unchanged from before the extraction (refactor only, per D-01/D-02/T-02-REP
     * design notes that used to live on the anonymous {@code run()} this replaces).
     *
     * @since 6.3.0
     */
    private void invokeCommandBody(CommandContext context, Method method, Object[] params,
                                    List<CommandValidator> ranValidators, TriggerContext triggerCtx,
                                    AtomicBoolean reported) {
        try {
            // T-02-REP-1/T-02-EOP-4 (02-08): the current-user context for
            // AuditableDataEntity's audit columns is set and cleared HERE, inside the
            // runnable that actually invokes the matched method -- not around the
            // runTask()/runTaskAsynchronously() call below that schedules this runnable.
            // Sync command bodies are deferred one tick and @AsyncCommand/@RunAsync bodies
            // run on another thread entirely; a ThreadLocal write made on the scheduling
            // thread would be invisible on whichever thread actually executes this run()
            // (T-02-REP-4). clearCurrentUser() -- not setCurrentUser(null) -- runs in a
            // finally around the whole body so a pooled Bukkit worker thread never carries
            // one command's user into the next, whether this command's sender was a Player
            // or not, and whether the handler returned normally or threw.
            if (context.isPlayer()) {
                AuditableDataEntity.setCurrentUser(context.getPlayer().getUniqueId());
            }
            try {
                boolean commandSucceeded = false;
                try {
                    method.setAccessible(true);
                    method.invoke(this, params);
                    commandSucceeded = true;
                } catch (Exception e) {
                    reportCommandExecutionError(context, method, e, triggerCtx);
                } finally {
                    // Post-actions run once per validator that passed for THIS invocation, in
                    // chain order, whether the mapped method succeeded or threw.
                    // UsageLockValidator's release is one of these post-actions as of D-02:
                    // acquisition happened inside its validate() step (acquire-as-you-validate),
                    // so it is no longer named by field here either -- lockValidator.releaseLock
                    // is reached only via onComplete, only for a validator that actually ran.
                    for (CommandValidator ranValidator : ranValidators) {
                        ranValidator.onComplete(context, commandSucceeded);
                    }
                }
            } finally {
                AuditableDataEntity.clearCurrentUser();
            }
        } finally {
            // WIRE-12: claim the flag so a watcher that fires later -- a stale delayed
            // task racing a body that already finished -- becomes a no-op (Test 5/6).
            // The body is NEVER interrupted to make this deadline; it always runs this
            // finally exactly once, win or lose the race.
            reported.compareAndSet(false, true);
        }
    }

    /**
     * Sends the user-facing failure message, logs the exception, and best-effort reports it to
     * {@link ErrorReportCollector} for a command method that threw. Split out of
     * {@link #invokeCommandBody} for the same NPathComplexity reason as that extraction.
     *
     * @since 6.3.0
     */
    private void reportCommandExecutionError(CommandContext context, Method method, Exception e,
                                              TriggerContext triggerCtx) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        context.getSender().sendMessage(ChatColor.RED + "命令执行出错: " + describe(cause));
        Logger.getLogger(BaseCommandExecutor.class.getName())
                .log(Level.SEVERE, "Command execution failed: " + method.getName(), e);
        // Report to error collector
        try {
            ErrorReportCollector erc = UltiTools.getInstance().getErrorReportCollector();
            if (erc != null) {
                erc.reportError(cause, extractModuleName(), triggerCtx);
            }
        } catch (Exception ignored) {
            // Never re-enter logging from error reporting
        }
    }

    /**
     * Renders a throwable for the user in a form that is never {@code "null"}.
     * <p>
     * Every command body is invoked reflectively, so anything it throws arrives wrapped in an
     * {@link java.lang.reflect.InvocationTargetException}, whose own {@code getMessage()} is
     * {@code null} by construction -- the detail belongs to the cause. Reporting the wrapper's
     * message therefore produced a literal {@code "命令执行出错: null"} for <em>every</em> command
     * failure, sending the user to look for a null-pointer bug that was not there and hiding the
     * real one in the server log (#385).
     * <p>
     * The cause's own message can also be null -- {@code NullPointerException} thrown by a bare
     * dereference is the common case -- so the class's simple name is used as the fallback. It is
     * a poor message, but {@code "NullPointerException"} tells the reader more than
     * {@code "null"} does, and the full stack trace is one line below in the log either way.
     *
     * @param cause the unwrapped throwable
     * @return its message, or its simple class name when the message is null or blank
     * @since 6.3.0
     */
    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName()
                : message;
    }

    /**
     * Sends the optional "processing" message, dispatches {@code runnable} asynchronously
     * exactly once, and arms the timeout watcher if configured. Split out of
     * {@link #executeCommand} for the same NPathComplexity reason as
     * {@link #invokeCommandBody}'s extraction.
     *
     * @since 6.3.0
     */
    private void dispatchAsyncCommand(CommandContext context, AsyncCommand asyncCommand,
                                       BukkitRunnable runnable, AtomicBoolean reported) {
        // Show processing message if enabled
        if (asyncCommand != null && asyncCommand.showProcessing()) {
            String processingKey = asyncCommand.processingMessageKey();
            String processingMsg = processingKey.isEmpty()
                    ? "处理中..."
                    : UltiTools.getInstance().i18n(processingKey);
            context.getSender().sendMessage(ChatColor.YELLOW + processingMsg);
        }

        // WIRE-12/D-13: schedule the command body asynchronously EXACTLY ONCE. A timeout
        // (if configured) is enforced by a SEPARATE watcher below, never by re-wrapping
        // this runnable in another one -- that "wrap and re-dispatch" shape is what
        // produced the double async dispatch this replaces, on the DEFAULT path of every
        // @AsyncCommand (timeout()'s default is 30).
        runnable.runTaskAsynchronously(UltiTools.getInstance());

        if (asyncCommand != null && asyncCommand.timeout() > 0) {
            armTimeoutWatcher(context, asyncCommand, reported);
        }
    }

    /**
     * Arms the delayed watcher that reports a timeout message to the sender if the command body
     * has not already completed by then. Split out of {@link #dispatchAsyncCommand} for the
     * same NPathComplexity reason as its sibling extractions.
     *
     * @since 6.3.0
     */
    private void armTimeoutWatcher(CommandContext context, AsyncCommand asyncCommand, AtomicBoolean reported) {
        // The watcher does NOT touch, cancel, or interrupt the body's task -- Bukkit's
        // async scheduler pool is shared, and interrupting a pooled thread is unsafe
        // (it can affect unrelated tasks). It only stops WAITING and reports, once,
        // via the CAS above; the body keeps running to completion regardless and its
        // result (success or exception) is discarded from the watcher's perspective.
        BukkitRunnable watcher = new BukkitRunnable() {
            @Override
            public void run() {
                if (reported.compareAndSet(false, true)) {
                    // Paper's AsyncCatcher governs what may be touched off the main
                    // thread; hop back through it to send the message, same mechanism
                    // CommandExecutionManager already uses for this purpose.
                    Bukkit.getScheduler().runTask(UltiTools.getInstance(), () ->
                            context.getSender().sendMessage(ChatColor.RED + UltiTools.getInstance()
                                    .i18n("命令执行超时，已停止等待，命令仍在后台继续执行")));
                }
            }
        };
        watcher.runTaskLaterAsynchronously(UltiTools.getInstance(), asyncCommand.timeout() * 20L);
    }


    /**
     * Matches arguments to a registered method.
     *
     * @param args the command arguments
     * @return the matched method, or null if not found
     */
    @Nullable
    protected Method matchMethod(String[] args) {
        if (args.length == 0) {
            return mappings.getOrDefault("", null);
        }
        
        Method bestMatch = null;
        int bestScore = -1;
        
        for (Map.Entry<String, Method> entry : mappings.entrySet()) {
            String format = entry.getKey();
            String[] formatArgs = format.split(" ");
            
            int score = calculateMatchScore(formatArgs, args);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry.getValue();
            }
        }
        
        return bestMatch;
    }
    
    /**
     * Calculates a match score for format vs actual args.
     * Weights: an exact literal match scores 10, a {@code <param>} placeholder scores 1, and an
     * exact-length match earns a +5 bonus. A format string is written against these three numbers.
     *
     * @param formatArgs the format arguments
     * @param actualArgs the actual arguments
     * @return the match score (-1 if no match)
     */
    private int calculateMatchScore(String[] formatArgs, String[] actualArgs) {
        if (formatArgs.length == 0 && actualArgs.length == 0) {
            return 100;
        }
        
        boolean hasVarargs = formatArgs.length > 0 && formatArgs[formatArgs.length - 1].endsWith("...>");
        
        // Check length compatibility
        if (!hasVarargs && formatArgs.length != actualArgs.length) {
            // Allow partial match for shorter actual args
            if (actualArgs.length > formatArgs.length) {
                return -1;
            }
        }
        
        int score = 0;
        int minLength = Math.min(formatArgs.length, actualArgs.length);
        
        for (int i = 0; i < minLength; i++) {
            String formatArg = formatArgs[i];
            String actualArg = actualArgs[i];
            
            if (isParameter(formatArg)) {
                score += 1; // Parameter match
            } else if (formatArg.equalsIgnoreCase(actualArg)) {
                score += 10; // Exact match (higher priority)
            } else {
                return -1; // No match
            }
        }
        
        // Bonus for exact length match
        if (formatArgs.length == actualArgs.length) {
            score += 5;
        }
        
        return score;
    }
    
    private boolean isParameter(String arg) {
        return arg.startsWith("<") && arg.endsWith(">");
    }
    
    /**
     * Parses command arguments into named parameters.
     *
     * @param args   the command arguments
     * @param format the command format
     * @return map of parameter names to values
     */
    protected Map<String, String[]> parseParameters(String[] args, String format) {
        if (args.length == 0 || format == null || format.isEmpty()) {
            return Collections.emptyMap();
        }
        
        String[] formatArgs = format.split(" ");
        Map<String, String[]> params = new HashMap<>();
        List<String> varargValues = new ArrayList<>();
        
        int formatIndex = 0;
        for (int i = 0; i < args.length && formatIndex < formatArgs.length; i++) {
            String formatArg = formatArgs[formatIndex];
            
            if (isParameter(formatArg)) {
                String paramName = extractParameterName(formatArg);
                
                if (formatArg.endsWith("...>")) {
                    // Varargs - collect remaining arguments
                    varargValues.add(args[i]);
                } else {
                    params.put(paramName, new String[]{args[i]});
                    formatIndex++;
                }
            } else {
                formatIndex++;
            }
        }
        
        // Handle varargs
        if (formatArgs.length > 0 && formatArgs[formatArgs.length - 1].endsWith("...>")) {
            String paramName = extractParameterName(formatArgs[formatArgs.length - 1]);
            params.put(paramName, varargValues.toArray(new String[0]));
        }
        
        return params;
    }
    
    private String extractParameterName(String formatArg) {
        if (formatArg.endsWith("...>")) {
            return formatArg.substring(1, formatArg.length() - 4);
        }
        return formatArg.substring(1, formatArg.length() - 1);
    }
    
    /**
     * Validates the parameter count.
     */
    protected boolean validateParameterCount(String[] args, String format, CommandSender sender, Command command) {
        // An empty format is a bare command taking no arguments, and must short-circuit
        // before the split: "".split(" ") returns a length-1 array holding one empty
        // string, so both comparisons below read the wrong thing -- with no arguments it
        // sees 1 != 0 and rejects, and with one argument it sees 1 == 1 and accepts.
        // The removed AbstractCommandExecutor.checkParameters (deleted 6.3.0) carried the
        // equivalent guard.
        if (format.isEmpty()) {
            if (args.length == 0) {
                return true;
            }
            sender.sendMessage(String.format(
                    UltiTools.getInstance().i18n("指令正确用法"),
                    command.getName(), format));
            return false;
        }

        String[] formatArgs = format.split(" ");
        
        boolean hasVarargs = formatArgs.length > 0 && formatArgs[formatArgs.length - 1].endsWith("...>");
        
        if (hasVarargs) {
            // Varargs allows any number of additional arguments
            if (args.length < formatArgs.length - 1) {
                sender.sendMessage(String.format(
                        UltiTools.getInstance().i18n("指令正确用法"),
                        command.getName(), format));
                return false;
            }
        } else if (formatArgs.length != args.length) {
            sender.sendMessage(String.format(
                    UltiTools.getInstance().i18n("指令正确用法"),
                    command.getName(), format));
            return false;
        }
        
        return true;
    }
    
    /**
     * Builds the method parameters from the command context.
     *
     * @param context the command context
     * @param method  the method to invoke
     * @return the method parameters, or null if parsing fails
     */
    @Nullable
    protected Object[] buildMethodParams(CommandContext context, Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }

        List<Object> paramList = new ArrayList<>();

        for (Parameter parameter : parameters) {
            Object resolved = resolveParameter(context, parameter);
            if (resolved == PARSE_FAILED_SENTINEL) {
                return null;
            }
            paramList.add(resolved);
        }

        return paramList.toArray();
    }

    private static final Object PARSE_FAILED_SENTINEL = new Object();

    private Object resolveParameter(CommandContext context, Parameter parameter) {
        Class<?> paramType = parameter.getType();

        if (parameter.isAnnotationPresent(CmdSender.class)) {
            return resolveSender(context, paramType);
        }

        if (isSenderType(paramType) && !parameter.isAnnotationPresent(CmdParam.class)) {
            return resolveSender(context, paramType);
        }

        if (parameter.isAnnotationPresent(CmdParam.class)) {
            return resolveCmdParam(context, parameter, paramType);
        }

        return null;
    }

    private static boolean isSenderType(Class<?> paramType) {
        return paramType.equals(Player.class) || paramType.equals(CommandSender.class);
    }

    private Object resolveSender(CommandContext context, Class<?> paramType) {
        if (paramType.equals(Player.class) && context.isPlayer()) {
            return context.getPlayer();
        }
        if (paramType.equals(CommandSender.class)) {
            return context.getSender();
        }
        return null;
    }

    private Object resolveCmdParam(CommandContext context, Parameter parameter, Class<?> paramType) {
        CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
        String[] values = context.getParam(cmdParam.value());
        try {
            return parseParameterValue(values, paramType);
        } catch (TypeParseException e) {
            context.getSender().sendMessage(ChatColor.RED + e.getMessage());
            return PARSE_FAILED_SENTINEL;
        }
    }
    
    /**
     * Parses a parameter value to the target type.
     *
     * @param values the string values
     * @param type   the target type
     * @return the parsed value
     */
    protected Object parseParameterValue(String[] values, Class<?> type) throws TypeParseException {
        if (values == null || values.length == 0) {
            if (type.equals(String.class)) {
                return "";
            }
            // An array parameter that legitimately received zero values gets the empty array, not
            // null (#396). validateParameterCount deliberately accepts a varargs command with no
            // trailing arguments -- `msg <player> <message...>` passes with just the player -- and
            // parseParameters hands this method the zero-length array that produces. Returning
            // null there meant every handler that touched its own varargs parameter threw:
            // `/friend msg <player>` with no message died in String.join with a
            // NullPointerException. This mirrors both the String case above and Java's own varargs
            // semantics, where a zero-argument call yields a zero-length array.
            if (type.isArray()) {
                return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
            }
            return null;
        }
        
        if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            return parserRegistry.parseArray(values, componentType);
        } else {
            if (type.equals(String.class) && values.length > 1) {
                return String.join(" ", values);
            }
            return parserRegistry.parse(values[0], type);
        }
    }
    
    @Override
    @Nullable
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return null;
        }
        
        // Check if this command is console-only
        CmdTarget cmdTarget = this.getClass().getAnnotation(CmdTarget.class);
        if (cmdTarget != null && cmdTarget.value() == CmdTarget.CmdTargetType.CONSOLE) {
            return null;
        }
        
        return suggest((Player) sender, command, args);
    }
    
    /**
     * Generates tab completion suggestions.
     * Override this method to provide custom suggestions.
     *
     * @param player  the player requesting completion
     * @param command the command
     * @param args    the current arguments
     * @return list of suggestions
     */
    protected List<String> suggest(Player player, Command command, String[] args) {
        return CommandTabCompletionDispatch.suggest(mappings, player, command, args, this);
    }
    
    /**
     * Gets all registered command mappings.
     *
     * @return unmodifiable map of format to method
     */
    public Map<String, Method> getMappings() {
        return Collections.unmodifiableMap(mappings);
    }
    
    /**
     * Adds a custom validator to the chain.
     *
     * @param validator the validator to add
     */
    public void addValidator(CommandValidator validator) {
        validatorChain.addValidator(validator);
    }
    
    /**
     * Removes a validator from the chain.
     *
     * @param validator the validator to remove
     */
    public void removeValidator(CommandValidator validator) {
        validatorChain.removeValidator(validator);
    }

    /**
     * Extract module name from the concrete command executor class.
     * Uses the simple class name as a reasonable approximation.
     */
    private String extractModuleName() {
        String className = this.getClass().getSimpleName();
        // Try to derive from CmdExecutor annotation description
        CmdExecutor annotation = this.getClass().getAnnotation(CmdExecutor.class);
        if (annotation != null && !annotation.description().isEmpty()) {
            return annotation.description();
        }
        return className;
    }
}
