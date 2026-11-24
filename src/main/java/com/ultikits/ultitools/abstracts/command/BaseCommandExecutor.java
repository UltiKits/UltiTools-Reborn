package com.ultikits.ultitools.abstracts.command;

import org.jetbrains.annotations.ApiStatus;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.ultikits.ultitools.scheduling.TaskContext;
import com.ultikits.ultitools.scheduling.TaskHandle;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.ultikits.ultitools.UltiTools;
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
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.MessageKey;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Getter;

/**
 * Base command executor with improved architecture using Chain of Responsibility pattern.
 * This class provides a cleaner, more extensible command handling system.
 * <p>
 * The default validator chain applies four validators in order: sender type (driven by the
 * class-level {@code @CmdTarget}, overridable per method), permission and operator requirement
 * (driven by {@code @CmdExecutor}), cooldown (applied when the mapped invocation completes,
 * whether it returns normally or throws, but not when dispatch is refused before invocation), and
 * usage locking (acquired and released around the call in a try-finally). Brigadier matches
 * and parses the command; the typed invocation then runs this executor's validator chain and
 * its existing synchronous or asynchronous body. Subclasses implement {@link #handleHelp}.
 * <p>
 * <b>Validator release at unload (case 48, @since 6.3.0).</b> {@code CooldownValidator} and
 * {@code UsageLockValidator} register themselves with {@link PlayerCacheManager} at their first
 * dispatch. When this executor is torn down (module unload), the framework validators of the
 * chain it dispatches through -- and its two field validators -- are released (unregistered, and
 * a lock validator's locks cleared) unless another loaded executor that has dispatched still uses
 * them; the last such executor's unload releases them, in whatever order the executors unload. A
 * sharer that had not dispatched yet holds no state through them and simply registers them again
 * on its first dispatch.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Invokes the mapped @CmdMapping method -- see 08-GATE05-TRIAGE.md
public abstract class BaseCommandExecutor {
    
    private final BiMap<String, Method> mappings = HashBiMap.create();
    private final Set<String> invalidProcessingKeys = ConcurrentHashMap.newKeySet();
    
    @Getter
    private final ValidatorChain validatorChain;

    /**
     * Resolved seconds of this executor's config-bound {@code @CmdCD} annotations, keyed by
     * {@code CooldownValidator.bindingKey}, and their load-time value sources (#531). Kept on the
     * executor -- the exact owner of a binding -- so the state lives and dies with it and nothing
     * shared (such as a {@code CooldownValidator} several executors use) has to release it on any
     * exit path. Replaced as whole immutable maps; written on the main thread, read on dispatch
     * threads. Reached only through {@link ConfigBoundCooldownState}.
     */
    volatile Map<String, Integer> configBoundCooldownSeconds = Collections.emptyMap();
    volatile Map<String, Supplier<Long>> configBoundCooldownSources = Collections.emptyMap();
    
    @Getter
    private final CooldownValidator cooldownValidator;
    
    @Getter
    private final UsageLockValidator lockValidator;
    

    /**
     * Executors that have dispatched at least once and have not been torn down (case 48): the
     * sharers an unloading executor must not release a validator from under. Held weakly so the
     * registry never keeps a module's executor -- or its class loader -- reachable; an entry
     * joins at the executor's first dispatch and leaves at its teardown, which also prunes
     * cleared references. Guarded by {@link #DISPATCHED_EXECUTORS_LOCK}.
     */
    private static final List<WeakReference<BaseCommandExecutor>> DISPATCHED_EXECUTORS = new ArrayList<>();

    private static final Object DISPATCHED_EXECUTORS_LOCK = new Object();

    /** Whether this executor is in {@link #DISPATCHED_EXECUTORS}; written under its lock. */
    private volatile boolean dispatchedExecutorMember;
    
    /**
     * Creates a new command executor with default validators.
     * <p>
     * The chain's framework validators are released at this executor's unload unless another
     * loaded executor that has dispatched still uses them (one built over {@code
     * getValidatorChain()}, for example); a sharer that had not dispatched simply registers them
     * again on its first dispatch (case 48).
     */
    public BaseCommandExecutor() {
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
     * <p>
     * At this executor's unload, the {@code CooldownValidator}/{@code UsageLockValidator}
     * instances in {@code validatorChain} are released -- unregistered from {@link
     * PlayerCacheManager}, a lock validator's locks cleared -- unless another loaded executor
     * that has dispatched still uses them, as when several executors or modules share one chain.
     * A sharer that had not dispatched simply registers them again on its first dispatch
     * (case 48).
     *
     * @param validatorChain the custom validator chain
     */
    public BaseCommandExecutor(ValidatorChain validatorChain) {
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
     * Releases the self-registering validators this executor dispatched through from {@link
     * PlayerCacheManager} tracking and bulk-clears every {@link UsageLockValidator}'s locks when
     * this executor is torn down (module unload).
     * <p>
     * The validators released are those of the chain this executor dispatches through -- a chain
     * passed to {@link #BaseCommandExecutor(ValidatorChain)} or returned by an overridden {@link
     * #createDefaultValidatorChain()} -- together with {@link #cooldownValidator}/{@link
     * #lockValidator}, taken as one identity set (case 48). Before, only the two fields were
     * released; a custom chain that did not contain them left its own validators, which register
     * themselves with {@link PlayerCacheManager} at their first dispatch, strongly held there after
     * the module unloaded -- one more per module update, and a module's own validator subclass
     * pinned its old class loader. Only {@link CooldownValidator} and {@link UsageLockValidator}
     * (and their subclasses) register themselves, so only they are released.
     * <p>
     * A candidate that another loaded executor still uses is left untouched: still registered,
     * its locks held, its cooldowns enforced and its quit cleanup running. "Still uses" means an
     * executor that has dispatched at least once, has not been torn down, is still reachable and
     * whose current chain contains the candidate by identity ({@link #DISPATCHED_EXECUTORS}).
     * The last such executor's unload releases it. Release goes through each validator's
     * {@code releasePlayerCacheRegistration()}, which also re-arms its lazy registration, so a
     * sharer that had not dispatched yet -- or a later executor over the same chain -- registers
     * it again on its next dispatch.
     * <p>
     * Reached through the framework's EXISTING {@code @PreDestroy} container lifecycle --
     * {@code PluginManager.unregister(plugin)} already calls {@code plugin.getContext().close()},
     * which invokes every singleton bean's {@code @PreDestroy} methods -- rather than a second,
     * purpose-built unload hook (GEN-08, D-03). These validators are registered with {@link
     * PlayerCacheManager} SEPARATELY from this executor bean itself (their sweepable state lives
     * on them, not on this class), so the pre-existing per-bean {@code
     * playerCacheManager.unregisterBean(bean)} loop already in {@code PluginManager.unregister(...)}
     * -- which only ever sees the beans a plugin's own container constructed -- never reaches them
     * on its own; this hook is what does.
     * <p>
     * {@link UsageLockValidator#clearAllLocks()} has no per-player analogue and, unlike {@link
     * UsageLockValidator#clearPlayerLocks(java.util.UUID)}/{@link
     * CooldownValidator#clearCooldowns(java.util.UUID)}, no quit-path call site either (GEN-08)
     * -- module unload is the correct point to bulk-release whatever locks a released lock
     * validator still held at teardown.
     *
     * @since 6.3.0
     */
    // @PreDestroy methods are invoked reflectively by SimpleContainer.invokePreDestroyMethods
    // (which calls setAccessible(true) before invoking), so PMD cannot see this private method's
    // only call site and misreports it as unused.
    @PreDestroy
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private void unregisterValidatorsFromPlayerCache() {
        List<BaseCommandExecutor> otherActive = leaveDispatchedExecutors(this);
        // Identity, not equals: a module's own validator subclass may define equals, and two
        // distinct instances must each be released.
        Set<CommandValidator> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        if (validatorChain != null) {
            candidates.addAll(validatorChain.getValidators());
        }
        candidates.add(cooldownValidator);
        candidates.add(lockValidator);
        for (CommandValidator candidate : candidates) {
            boolean selfRegistering = candidate instanceof CooldownValidator
                    || candidate instanceof UsageLockValidator;
            if (!selfRegistering || isUsedByAnotherExecutor(candidate, otherActive)) {
                continue;
            }
            if (candidate instanceof CooldownValidator) {
                ((CooldownValidator) candidate).releasePlayerCacheRegistration();
            } else {
                UsageLockValidator lock = (UsageLockValidator) candidate;
                lock.releasePlayerCacheRegistration();
                lock.clearAllLocks();
            }
        }
    }

    /**
     * Records this executor in {@link #DISPATCHED_EXECUTORS} at its first dispatch, so an unloading
     * executor sharing a validator with it leaves that validator registered (case 48).
     */
    private void joinDispatchedExecutors() {
        if (dispatchedExecutorMember) {
            return;
        }
        synchronized (DISPATCHED_EXECUTORS_LOCK) {
            if (!dispatchedExecutorMember) {
                DISPATCHED_EXECUTORS.add(new WeakReference<>(this));
                dispatchedExecutorMember = true;
            }
        }
    }

    /**
     * Removes {@code leaving} and every cleared reference from {@link #DISPATCHED_EXECUTORS} and
     * returns the executors that remain. The lock is released before any caller reads another
     * executor's chain.
     */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // registry membership is by identity; an executor may define equals
    private static List<BaseCommandExecutor> leaveDispatchedExecutors(BaseCommandExecutor leaving) {
        List<BaseCommandExecutor> remaining = new ArrayList<>();
        synchronized (DISPATCHED_EXECUTORS_LOCK) {
            Iterator<WeakReference<BaseCommandExecutor>> it = DISPATCHED_EXECUTORS.iterator();
            while (it.hasNext()) {
                BaseCommandExecutor executor = it.next().get();
                if (executor == null || executor == leaving) {
                    it.remove();
                } else {
                    remaining.add(executor);
                }
            }
            leaving.dispatchedExecutorMember = false;
        }
        return remaining;
    }

    /** Whether one of {@code others} still dispatches through {@code validator} (the same instance). */
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // sharing means the same validator instance; a validator may define equals
    private static boolean isUsedByAnotherExecutor(CommandValidator validator, List<BaseCommandExecutor> others) {
        for (BaseCommandExecutor other : others) {
            ValidatorChain chain = other.getValidatorChain();
            if (chain == null) {
                continue;
            }
            for (CommandValidator used : chain.getValidators()) {
                if (used == validator) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans methods for command mappings.
     */
    private void scanCommandMappings() {
        // The discovery rule lives in ReflectionUtil.getEffectiveCommandMappings; see its javadoc.
        mappings.putAll(ReflectionUtil.getEffectiveCommandMappings(this.getClass()));
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
    
    /**
     * Runs the validation and invocation stage after Brigadier has produced typed arguments.
     * This is an internal bridge, not a second command matching entry point.
     *
     * @param context the complete, typed invocation context
     * @return true after handling the invocation
     */
    @ApiStatus.Internal
    public final boolean invokeTyped(CommandContext context) {
        joinDispatchedExecutors();
        ValidatorChain.ChainValidationResult validationResult = validatorChain.validate(context);
        if (!validationResult.isValid()) {
            sendValidationFailure(context.getSender(), validationResult.getErrorText(), validationResult.getErrorMessage());
            notifyRefused(context, validationResult);
            return true;
        }
        Object[] methodParams;
        try {
            methodParams = buildMethodParams(context, context.getMatchedMethod());
        } catch (RuntimeException exception) {
            notifyRefusedAndRethrow(context, validationResult, exception);
            throw exception;
        }
        if (methodParams == null) {
            notifyRefused(context, validationResult);
            return true;
        }
        executeCommand(context, context.getMatchedMethod(), methodParams, validationResult);
        return true;
    }

    /**
     * Gives the compiler this executor's help literal.
     * @return the help literal
     */
    @ApiStatus.Internal
    public final String helpCommand() { return getHelpCommand(); }

    /**
     * Invokes the completion hook without a Bukkit command object.
     * @param sender the requesting sender
     * @param label the command label
     * @param args the input arguments
     * @return custom suggestions, or null to retain native argument suggestions
     */
    @Nullable
    @ApiStatus.Internal
    public final List<String> suggestions(CommandSender sender, String label, String[] args) {
        return suggest(sender, label, args);
    }

    /**
     * Tells each validator that passed for this dispatch that it was refused before the mapped
     * method ran, in chain order, so a validator that acquired something in {@code validate} --
     * {@code UsageLockValidator}'s lock -- releases it (#568). Driven from the chain's own
     * passed-validator list, like {@code onComplete}, never from a named field.
     */
    private static void notifyRefused(CommandContext context, ValidatorChain.ChainValidationResult validationResult) {
        ValidatorChain.notifyRefused(context, validationResult.getPassedValidators());
    }

    /**
     * {@link #notifyRefused} for a dispatch leaving by {@code cause}: every hook still runs, and a hook
     * that throws is attached to {@code cause} as suppressed rather than replacing it.
     */
    private static void notifyRefusedAndRethrow(CommandContext context,
                                                ValidatorChain.ChainValidationResult validationResult,
                                                RuntimeException cause) {
        try {
            notifyRefused(context, validationResult);
        } catch (RuntimeException hookFailure) {
            cause.addSuppressed(hookFailure);
        }
    }

    /**
     * Executes the command method.
     * Supports both synchronous and asynchronous execution via @AsyncCommand or @RunAsync.
     * <p>
     * A synchronous body runs at once, inside the dispatch, when this is called on the server's
     * primary thread -- which is where Bukkit dispatches every command a player, the console, a
     * command block or the panel sends -- and is handed to {@code global().run} only when it is not
     * (as of 6.3.0, #541; before, every synchronous body was deferred one tick). An
     * {@code @AsyncCommand}/{@code @RunAsync} body always runs asynchronously.
     *
     * @param context          the command context
     * @param method           the method to execute
     * @param params           the method parameters
     * @param validationResult the chain result produced by {@code invokeTyped}'s validation step
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
        // worker running the body and the delayed sender-context watcher task), so this MUST be an
        // atomic, not a plain boolean. Harmless when timeout() <= 0: no watcher is armed, so
        // nothing else ever touches this flag and the body's own CAS below is a pure no-op.
        final AtomicBoolean reported = new AtomicBoolean(false);

        final AtomicReference<TaskHandle> timeoutWatcher = new AtomicReference<>();
        Runnable runnable = () -> invokeCommandBody(context, method, params, ranValidators,
                triggerCtx, reported, timeoutWatcher);

        if (isAsync) {
            dispatchAsyncCommand(context, asyncCommand, runnable, reported, timeoutWatcher, ranValidators);
        } else if (CommandRuntime.scheduler().global().isCurrentThread()) {
            // #541 (maintainer's answer of 2026-09-29): on the server thread the body runs now,
            // inside the dispatch, as Bukkit's own commands do -- so a command block, a command
            // minecart, the panel's remote command and RCON receive its replies while their
            // output capture is still open, and a @CmdCD cooldown is recorded before the
            // dispatch returns. Applies to every sender; no sender-type branch.
            runnable.run();
        } else {
            // Off the server thread the body must not touch the Bukkit API directly, so it is
            // handed to the main thread, exactly as before 6.3.0.
            dispatchScheduled(context, ranValidators, () -> CommandRuntime.scheduler().global().run(runnable));
        }
    }

    /**
     * Hands a body to the scheduler; if the scheduler refuses it (the plugin is disabling, for
     * example), the body never runs and so never reaches its {@code onComplete} hooks, so the
     * validators that passed are told of the refusal instead (#568) before the exception leaves.
     */
    private static void dispatchScheduled(CommandContext context, List<CommandValidator> ranValidators,
                                          Runnable schedule) {
        try {
            schedule.run();
        } catch (RuntimeException e) {
            try {
                ValidatorChain.notifyRefused(context, ranValidators);
            } catch (RuntimeException hookFailure) {
                e.addSuppressed(hookFailure);
            }
            throw e;
        }
    }

    /**
     * Runs the matched command method plus its surrounding bookkeeping (audit-user context,
     * post-action validator hooks, error reporting, the WIRE-12 completion flag) -- the body of
     * the {@link Runnable} {@link #executeCommand} schedules.
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
                                    AtomicBoolean reported, AtomicReference<TaskHandle> timeoutWatcher) {
        try {
            // T-02-REP-1/T-02-EOP-4 (02-08): the current-user context for
            // AuditableDataEntity's audit columns is set and restored HERE, inside the
            // runnable that actually invokes the matched method -- not around the
            // global/async submission call that may schedule this runnable. An
            // off-primary-thread sync body and every @AsyncCommand/@RunAsync body run on
            // another thread; a ThreadLocal write made on the scheduling thread would be
            // invisible on whichever thread actually executes this run() (T-02-REP-4).
            // #541: on the primary thread a body runs at dispatch, so a body that dispatches
            // another command runs the nested body right here, on the same thread, before its
            // own has finished. The user current before this body is therefore saved and put
            // back in the finally -- the nested body sees its own sender (none for a sender
            // that is not a player), the outer body sees its own again afterwards, and a thread
            // that carried no user before the outermost command (a pooled worker included)
            // carries none after it, whether the handler returned normally or threw.
            final UUID previousUser = AuditableDataEntity.swapCurrentUser(
                    context.isPlayer() ? context.getPlayer().getUniqueId() : null);
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
                    completeAll(context, ranValidators, commandSucceeded);
                }
            } finally {
                AuditableDataEntity.swapCurrentUser(previousUser);
            }
        } finally {
            // WIRE-12: claim the flag so a watcher that fires later -- a stale delayed
            // task racing a body that already finished -- becomes a no-op (Test 5/6).
            // The body is NEVER interrupted to make this deadline; it always runs this
            // finally exactly once, win or lose the race.
            reported.compareAndSet(false, true);
            cancelTimeoutWatcher(timeoutWatcher);
        }
    }

    /**
     * Calls every validator's {@code onComplete} in chain order. Each runs even when an earlier one
     * throws, so one validator's failing hook cannot keep a later {@code UsageLockValidator} from
     * releasing its lock (#568); the first exception is rethrown afterwards, later ones suppressed.
     */
    private static void completeAll(CommandContext context, List<CommandValidator> ranValidators,
                                    boolean commandSucceeded) {
        RuntimeException first = null;
        for (CommandValidator ranValidator : ranValidators) {
            try {
                ranValidator.onComplete(context, commandSucceeded);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * Runs the sender-type and permission validators, then shows help if they pass.
     * <p>
     * Before 6.3.0 the help short-circuit sat at the very top of the old Bukkit executor, ahead of the
     * {@code CommandContext} being built, so it bypassed the entire validator chain (#381). Two
     * consequences were measured on a real server: a console received the full help of a
     * {@code @CmdTarget(PLAYER)} command -- including, in one module, an admin section gated only
     * on {@code hasPermission}, which a console always satisfies -- and modules had begun working
     * around the absent guarantee in incompatible ways. One tested {@code sender instanceof
     * Player} and silently printed nothing for a console; another printed everything. Neither is
     * wrong against a contract that promised nothing.
     * <p>
     * <b>Cooldown and usage-limit are deliberately not applied here.</b> Help is not the guarded
     * action: withholding an explanation because the command itself is on cooldown would be
     * perverse, and {@code UsageLockValidator} acquires a lock that only the invocation path
     * releases. This is a choice, stated so it cannot later be mistaken for the same oversight in
     * a different place.
     * <p>
     * <b>Applicability is consulted, same as the normal dispatch path (#413).</b> Before invoking
     * a sender-type or permission validator here, {@link ValidatorChain#isApplicable} is called --
     * the exact same static method {@link ValidatorChain#validate}/{@code #validateAll} call
     * internally, not a second, independent copy of the one-line condition it wraps (WR-02). A
     * validator that reports it does not apply to this context is skipped, not invoked and
     * happened to pass. This does not weaken enforcement: a validator that DOES apply and denies
     * still denies help, exactly as before.
     *
     * @param context the command context, already built
     * @return {@code true} always, indicating that the help invocation was handled
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean handleGatedHelp(CommandContext context) {
        for (CommandValidator validator : validatorChain.getValidators()) {
            if (!(validator instanceof SenderTypeValidator) && !(validator instanceof PermissionValidator)) {
                continue;
            }
            if (!ValidatorChain.isApplicable(validator, context)) {
                continue;
            }
            CommandValidator.ValidationResult result = validator.validate(context);
            if (!result.isValid()) {
                sendValidationFailure(context.getSender(), result.getErrorText(), result.getErrorMessage());
                return true;
            }
        }
        handleHelp(context.getSender());
        return true;
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
        CommandRuntime.messages().send(context.getSender(), Text.of(CommandKeys.EXECUTION_FAILED,
                Arg.string("error", describe(cause))));
        Logger.getLogger(BaseCommandExecutor.class.getName())
                .log(Level.SEVERE, "Command execution failed: " + method.getName(), e);
        // Report to error collector
        try {
            ErrorReportCollector erc = UltiTools.getInstance().getErrorReportCollector();
            if (erc != null) {
                erc.reportErrorFromClass(cause, getClass(), triggerCtx);
            }
        } catch (Exception ignored) {
            // Never re-enter logging from error reporting
        }
    }

    static void sendValidationFailure(CommandSender sender, @Nullable Text text, @Nullable String legacyMessage) {
        if (text != null) {
            CommandRuntime.messages().send(sender, text);
        } else if (legacyMessage != null) {
            sender.sendMessage(legacyMessage);
        }
    }

    private Text processingText(String key) {
        if (key.isEmpty()) {
            return Text.of(CommandKeys.PROCESSING);
        }
        try {
            return Text.of(MessageKey.parse(key));
        } catch (IllegalArgumentException invalid) {
            if (invalidProcessingKeys.add(key)) {
                Logger.getLogger(BaseCommandExecutor.class.getName()).log(Level.WARNING,
                        "Invalid processingMessageKey on {0}: {1}; using the default processing message",
                        new Object[] {getClass().getName(), key});
            }
            return Text.of(CommandKeys.PROCESSING);
        }
    }

    /**
     * Renders a throwable for the user in a form that is never {@code "null"}.
     * <p>
     * Every command body is invoked reflectively, so anything it throws arrives wrapped in an
     * {@link java.lang.reflect.InvocationTargetException}, whose own {@code getMessage()} is
     * {@code null} by construction -- the detail belongs to the cause. Reporting the wrapper's
     * message therefore appended a literal {@code "null"} to the error prefix for <em>every</em>
     * command failure, sending the user to look for a null-pointer bug that was not there and
     * hiding the real one in the server log (#385).
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
                                       Runnable runnable, AtomicBoolean reported,
                                       AtomicReference<TaskHandle> timeoutWatcher,
                                       List<CommandValidator> ranValidators) {
        // The refusal hooks (#568) cover only what happens before the body is accepted: the
        // processing notice and the body's submission. Once the scheduler has accepted the body,
        // the body owns the validators' post-actions (onComplete), so a failure to arm the timeout
        // watcher below must not release what the pending body still holds (local Codex review of
        // #570, run 2).
        dispatchScheduled(context, ranValidators, () -> {
            // Show processing message if enabled
            if (asyncCommand != null && asyncCommand.showProcessing()) {
                CommandRuntime.messages().send(context.getSender(), processingText(asyncCommand.processingMessageKey()));
            }

            // WIRE-12/D-13: schedule the command body asynchronously EXACTLY ONCE. A timeout
            // (if configured) is enforced by a SEPARATE watcher below, never by re-wrapping
            // this runnable in another one -- that "wrap and re-dispatch" shape is what
            // produced the double async dispatch this replaces, on the DEFAULT path of every
            // @AsyncCommand (timeout()'s default is 30).
            CommandRuntime.scheduler().async().run(runnable);
        });

        if (asyncCommand != null && asyncCommand.timeout() > 0) {
            armTimeoutWatcher(context, asyncCommand, reported, timeoutWatcher);
        }
    }

    /**
     * Arms the delayed watcher that reports a timeout message to the sender if the command body
     * has not already completed by then. Split out of {@link #dispatchAsyncCommand} for the
     * same NPathComplexity reason as its sibling extractions.
     *
     * @since 6.3.0
     */
    private static void armTimeoutWatcher(CommandContext context, AsyncCommand asyncCommand,
                                          AtomicBoolean reported, AtomicReference<TaskHandle> timeoutWatcher) {
        // A tick-affine watcher only stops waiting. It never cancels or interrupts the command body.
        TaskHandle watcher = replyContext(context.getSender()).runLater(Duration.ofSeconds(asyncCommand.timeout()), () -> {
            if (reported.compareAndSet(false, true)) {
                CommandRuntime.messages().send(context.getSender(), Text.of(CommandKeys.TIMEOUT));
            }
        });
        timeoutWatcher.set(watcher);
        // The async body can finish before submission of this watcher returns.
        if (reported.get()) {
            cancelTimeoutWatcher(timeoutWatcher);
        }
    }

    private static TaskContext replyContext(CommandSender sender) {
        return sender instanceof Player player
                ? CommandRuntime.scheduler().entity(player) : CommandRuntime.scheduler().global();
    }

    private static void cancelTimeoutWatcher(AtomicReference<TaskHandle> timeoutWatcher) {
        TaskHandle watcher = timeoutWatcher.getAndSet(null);
        if (watcher != null) {
            watcher.cancel();
        }
    }


    /**
     * Builds the mapped method's arguments from already typed values.
     * @param context the invocation context
     * @param method the mapped method
     * @return the arguments, or null when an override refuses the invocation
     */
    @Nullable
    protected Object[] buildMethodParams(CommandContext context, Method method) {
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            CmdParam annotation = parameter.getAnnotation(CmdParam.class);
            if (annotation != null) values[index] = context.getParam(annotation.value());
            else if (parameter.isAnnotationPresent(CmdSender.class) || CommandSender.class.isAssignableFrom(parameter.getType())) {
                values[index] = parameter.getType().isInstance(context.getSender()) ? context.getSender() : null;
            }
        }
        return values;
    }

    /**
     * Optional completion fallback for parameters with no explicit suggestion source.
     * Literal suggestions are always supplied by Brigadier.
     * @param sender the requesting sender
     * @param label the command label
     * @param args the current input including its partial token
     * @return custom suggestions, or null for native argument suggestions
     */
    @Nullable
    protected List<String> suggest(CommandSender sender, String label, String[] args) {
        return null;
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

}
