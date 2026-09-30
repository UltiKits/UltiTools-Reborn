package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.ConfigBoundCooldownState;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.TriggerContext;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Validates and manages command cooldowns for players.
 * Thread-safe implementation using ConcurrentHashMap.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class CooldownValidator implements CommandValidator, PlayerCacheManager.ExpiringPlayerCache,
        PlayerCacheSaver {

    private static final int ORDER = 300;

    private static final Logger LOGGER = Logger.getLogger(CooldownValidator.class.getName());

    /**
     * Map of player UUID -> (cooldown key -> cooldown end timestamp).
     * <p>
     * The cooldown key is an {@link ExecutorMethodKey} -- the dispatching executor instance, held
     * weakly and compared by identity, plus {@code method.toString()} -- whenever the context
     * carries an executor, which every dispatch through {@code BaseCommandExecutor} does (#539).
     * A context without an executor (a validator driven directly, outside any executor) keys by
     * the {@code method.toString()} string alone, exactly as before 6.3.0.
     * <p>
     * {@code saveBeforeRemove = true} so {@link #savePlayerData(UUID)} -- which delegates to
     * the pre-existing {@link #clearCooldowns(UUID)} -- fires on quit; see that method's
     * javadoc for why (GEN-08, D-03).
     */
    @PlayerCache(saveBeforeRemove = true)
    private final Map<UUID, Map<Object, Long>> cooldowns = new ConcurrentHashMap<>();

    /**
     * True once this instance has registered {@link #cooldowns} with the live {@link
     * PlayerCacheManager} for quit-based and time-based sweeping (GEN-08, D-03).
     * <p>
     * Set from {@link #validate(CommandContext)} -- the first production call this validator
     * receives after construction -- rather than from the constructor: a bare {@code new
     * CooldownValidator()} (the shape a unit test, or {@code BaseCommandExecutor}'s own
     * constructor, uses) must never even attempt contact with a core plugin that may not exist
     * yet. Guarding on a live {@link UltiTools#getInstance()} rather than an unconditional
     * "did I try" flag means a first attempt made before the core plugin is up is retried on
     * the next call instead of being permanently abandoned -- {@link PlayerCacheManager#tryRegister}
     * is itself idempotent per instance, so a redundant retry after the flag is already true is
     * simply never made.
     */
    private volatile boolean playerCacheRegistered = false;

    private final int defaultCooldownSeconds;
    
    /**
     * Creates a cooldown validator with no default cooldown.
     */
    public CooldownValidator() {
        this.defaultCooldownSeconds = 0;
    }
    
    /**
     * Creates a cooldown validator with a default cooldown.
     *
     * @param defaultCooldownSeconds the default cooldown in seconds
     */
    public CooldownValidator(int defaultCooldownSeconds) {
        this.defaultCooldownSeconds = defaultCooldownSeconds;
    }
    
    @Override
    public ValidationResult validate(CommandContext context) {
        ensurePlayerCacheRegistered();

        if (!context.isPlayer()) {
            return ValidationResult.success();
        }
        
        Player player = context.getPlayer();
        Method method = context.getMatchedMethod();

        if (method == null) {
            return ValidationResult.success();
        }

        Integer resolvedSeconds = getCooldownSeconds(method, context);
        if (resolvedSeconds == null) {
            return unresolvedBinding(context, method);
        }
        // A literal (or absent) @CmdCD cannot change at run time, so a value of 0 there never stamped
        // anything and the unbound path stays exactly as before.
        if (resolvedSeconds <= 0 && !isBoundMapping(method, context)) {
            return ValidationResult.success();
        }
        // A bound value can change at run time (#531), so for a bound mapping a stored, unexpired end
        // time is honoured whatever the current value is: a refresh to 0 means that no NEW cooldown
        // is stamped (see applyCooldown), not that running ones are lifted -- they expire on their
        // own. Checking the value first would free every player at once and bring the old stamps
        // back on a later non-zero value.
        UUID playerId = player.getUniqueId();
        Object cooldownKey = cooldownKey(context.getExecutor(), method.toString());

        Map<Object, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns != null) {
            Long endTime = playerCooldowns.get(cooldownKey);
            if (endTime != null && System.currentTimeMillis() < endTime) {
                long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(endTime - System.currentTimeMillis()) + 1;
                return ValidationResult.failure(
                        ChatColor.RED + String.format(
                                UltiTools.getInstance().i18n("操作频繁，请 %d 秒后再试"),
                                remainingSeconds
                        ),
                        "command.error.cooldown"
                );
            }
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Applies cooldown after command execution.
     * Should be called after successful command execution.
     *
     * @param context the command context
     */
    public void applyCooldown(CommandContext context) {
        if (!context.isPlayer()) {
            return;
        }
        
        Player player = context.getPlayer();
        Method method = context.getMatchedMethod();

        if (method == null) {
            return;
        }

        Integer cooldownSeconds = getCooldownSeconds(method, context);
        if (cooldownSeconds == null || cooldownSeconds <= 0) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cooldownSeconds);

        cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(cooldownKey(context.getExecutor(), method.toString()), endTime);
    }

    /**
     * The key an active cooldown is stored under (#539): the executor instance and the method
     * when the dispatch names an executor, the method string alone when it does not.
     */
    private static Object cooldownKey(Object executor, String methodKey) {
        return executor == null ? methodKey : new ExecutorMethodKey(executor, methodKey);
    }

    /**
     * Whether a stored cooldown key belongs to {@code methodKey}, whichever executor it names --
     * the match the string-keyed accessors use.
     */
    private static boolean isKeyFor(Object key, String methodKey) {
        return key instanceof ExecutorMethodKey
                ? ((ExecutorMethodKey) key).methodKey.equals(methodKey)
                : methodKey.equals(key);
    }
    
    /**
     * Post-action hook that applies the cooldown recorded for this invocation. Delegates to
     * {@link #applyCooldown(CommandContext)} and is invoked only by a chain that actually ran
     * this validator for the current dispatch -- see
     * {@link CommandValidator#onComplete(CommandContext, boolean)}.
     *
     * @param context          the command context
     * @param commandSucceeded ignored -- the cooldown applies whether the mapped method
     *                         succeeded or threw
     * @since 6.3.0
     */
    @Override
    public void onComplete(CommandContext context, boolean commandSucceeded) {
        applyCooldown(context);
    }

    /**
     * Attempts lazy first-use registration of this instance with the live {@link
     * PlayerCacheManager} singleton. Safe to call unconditionally on every {@link
     * #validate(CommandContext)} invocation: a no-op once {@link #playerCacheRegistered} is
     * true, and a cheap, safely-no-op-on-failure retry otherwise (see that field's javadoc).
     */
    private void ensurePlayerCacheRegistered() {
        if (playerCacheRegistered) {
            return;
        }
        UltiTools instance = UltiTools.getInstance();
        // Checking getPluginManager() too, not just getInstance(), matters: a mock/test double
        // that stands up UltiTools.getInstance() without yet wiring getPluginManager() would
        // otherwise latch this flag true on a no-op attempt, permanently skipping the retry that
        // would have succeeded once the chain was genuinely live.
        if (instance == null || instance.getPluginManager() == null) {
            return;
        }
        PlayerCacheManager.tryRegister(this);
        playerCacheRegistered = true;
    }

    /**
     * {@link PlayerCacheManager.ExpiringPlayerCache} opt-in: delegates to the pre-existing
     * {@link #cleanupExpired()} time-based half. Invoked once per {@link
     * PlayerCacheManager#sweepExpiredEntries()} pass -- the periodic sweep this instance only
     * receives once registered (see {@link #ensurePlayerCacheRegistered()}) -- independently of
     * whether any player has quit (GEN-08, D-03).
     *
     * @since 6.3.0
     */
    @Override
    public void sweepExpired() {
        cleanupExpired();
    }

    /**
     * {@link PlayerCacheSaver} hook: fired by {@link PlayerCacheManager#onPlayerQuit(UUID)}
     * ("the quit sweep") for the quitting player -- before the generic {@code @PlayerCache}
     * field sweep removes the (by then already-empty) {@link #cooldowns} entry. Delegates to
     * the pre-existing {@link #clearCooldowns(UUID)} rather than re-deriving its predicate,
     * giving that previously zero-caller method a real, quit-path-reached production call site
     * (GEN-08). The interface is named for persistence, but its contract is simply "run this
     * before the generic removal" -- there is nothing here to persist, and reusing the hook for
     * an idempotent, already-correct cleanup method is the same predicate the generic sweep
     * would otherwise perform standalone.
     *
     * @param playerId the UUID of the player quitting
     * @since 6.3.0
     */
    @Override
    public void savePlayerData(UUID playerId) {
        clearCooldowns(playerId);
    }

    /**
     * Clears all cooldowns for a player.
     *
     * @param playerId the player's UUID
     */
    public void clearCooldowns(UUID playerId) {
        cooldowns.remove(playerId);
    }
    
    /**
     * Clears a specific cooldown for a player, on every executor this validator serves.
     * <p>
     * As of 6.3.0 an active cooldown is keyed by the executor instance as well as the method
     * (#539). With one executor per validator -- the shape both {@code BaseCommandExecutor}
     * constructors create -- this clears exactly that executor's cooldown, as before. When one
     * validator serves several executors, it clears the method's cooldown on all of them; use
     * {@link #clearCooldown(UUID, Object, String)} to address one.
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key, {@code method.toString()} of the mapping method
     */
    public void clearCooldown(UUID playerId, String methodKey) {
        Map<Object, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns != null) {
            playerCooldowns.keySet().removeIf(key -> isKeyFor(key, methodKey));
        }
    }

    /**
     * Clears one executor's cooldown of a method for a player (#539).
     *
     * @param playerId  the player's UUID
     * @param executor  the executor instance the cooldown was recorded on
     * @param methodKey the method key, {@code method.toString()} of the mapping method
     * @since 6.3.0
     */
    public void clearCooldown(UUID playerId, Object executor, String methodKey) {
        Map<Object, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns != null) {
            playerCooldowns.remove(cooldownKey(executor, methodKey));
        }
    }

    /**
     * Gets the remaining cooldown time in seconds, on any executor this validator serves.
     * <p>
     * With one executor per validator this is that executor's remaining time, as before 6.3.0.
     * When one validator serves several executors it is the longest remaining time among them;
     * use {@link #getRemainingCooldown(UUID, Object, String)} to address one (#539).
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key, {@code method.toString()} of the mapping method
     * @return remaining seconds, or 0 if not on cooldown
     */
    public long getRemainingCooldown(UUID playerId, String methodKey) {
        Map<Object, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns == null) {
            return 0;
        }
        long latestEnd = 0;
        for (Map.Entry<Object, Long> entry : playerCooldowns.entrySet()) {
            if (isKeyFor(entry.getKey(), methodKey) && isLive(entry.getKey())) {
                latestEnd = Math.max(latestEnd, entry.getValue());
            }
        }
        return remainingSeconds(latestEnd);
    }

    /**
     * Gets one executor's remaining cooldown of a method, in seconds (#539).
     *
     * @param playerId  the player's UUID
     * @param executor  the executor instance the cooldown was recorded on
     * @param methodKey the method key, {@code method.toString()} of the mapping method
     * @return remaining seconds, or 0 if not on cooldown
     * @since 6.3.0
     */
    public long getRemainingCooldown(UUID playerId, Object executor, String methodKey) {
        Map<Object, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns == null) {
            return 0;
        }
        Long endTime = playerCooldowns.get(cooldownKey(executor, methodKey));
        return endTime == null ? 0 : remainingSeconds(endTime);
    }

    private static long remainingSeconds(long endTime) {
        long now = System.currentTimeMillis();
        if (now >= endTime) {
            return 0;
        }
        return TimeUnit.MILLISECONDS.toSeconds(endTime - now) + 1;
    }

    /** A key whose executor has been garbage collected can never be matched again. */
    private static boolean isLive(Object key) {
        return !(key instanceof ExecutorMethodKey) || ((ExecutorMethodKey) key).executor.get() != null;
    }

    /**
     * Cleans up expired cooldowns to prevent memory leaks.
     * Should be called periodically. Also drops the cooldowns of an executor that has since been
     * garbage collected (a module that unloaded), which no dispatch can reach any more.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        cooldowns.forEach((playerId, methods) -> {
            methods.entrySet().removeIf(entry -> entry.getValue() < now || !isLive(entry.getKey()));
            if (methods.isEmpty()) {
                cooldowns.remove(playerId);
            }
        });
    }

    /**
     * An executor-scoped cooldown key (#539): the executor instance, compared by identity and held
     * weakly so an active cooldown never keeps an unloaded module's executor -- and with it the
     * module's class loader -- reachable, plus {@code method.toString()}. Identity rather than
     * {@code equals}: two executors of one class are two owners, whatever their class declares.
     */
    private static final class ExecutorMethodKey {
        private final WeakReference<Object> executor;
        private final String methodKey;
        private final int hash;

        ExecutorMethodKey(Object executor, String methodKey) {
            this.executor = new WeakReference<>(executor);
            this.methodKey = methodKey;
            this.hash = 31 * System.identityHashCode(executor) + methodKey.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ExecutorMethodKey)) {
                return false;
            }
            ExecutorMethodKey that = (ExecutorMethodKey) other;
            Object mine = executor.get();
            return mine != null && mine == that.executor.get() && methodKey.equals(that.methodKey);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
    
    /**
     * Resolves the cooldown for {@code method}: the method's own {@code @CmdCD}, falling back
     * to a class-level {@code @CmdCD} on the CONCRETE executor class dispatching this command,
     * falling back to a class-level {@code @CmdCD} on the method's declaring class, falling back
     * to {@link #defaultCooldownSeconds} when none is present -- most-derived-wins, via {@link
     * ReflectionUtil#resolveMethodOrClassAnnotation(Method, Class, Class)}. This is the SAME
     * resolution {@code PluginManager}'s load-time refusal treats as satisfying the contract
     * (SILENT-11 / D-01 follow-up, WR-02 / 05-REVIEW.md fix): a class-level {@code @CmdCD} that
     * passes the load-time check -- whether declared on a shared abstract base or on the
     * concrete executor class itself -- now actually cools down every inherited mapping that
     * does not declare its own.
     * <p>
     * A config-bound {@code @CmdCD} (#531) returns the seconds the framework resolved onto the
     * dispatching executor ({@code CommandContext.getExecutor()}, read through
     * {@link ConfigBoundCooldownState}) instead of its literal -- never the live config field,
     * because a refused {@code /ul reload} leaves the refused value in that field. A bound
     * annotation with no cached value returns {@code null}: it was never resolved by a module
     * load, and the caller refuses the command rather than treat it as "no cooldown".
     *
     * @param method  the matched command mapping method
     * @param context the dispatch context; its executor class resolves a class-level
     *                {@code @CmdCD} (WR-02, 05-REVIEW.md -- {@code null} falls back to the
     *                declaring class), its executor instance selects a bound value
     * @return the resolved cooldown in seconds, or {@code null} for an unresolved binding
     * @since 6.3.0
     */
    /**
     * @return whether the {@code @CmdCD} that governs {@code method} is bound to a config key
     */
    private static boolean isBoundMapping(Method method, CommandContext context) {
        CmdCD cmdCD = ReflectionUtil.resolveMethodOrClassAnnotation(method, context.getExecutorClass(), CmdCD.class);
        return cmdCD != null && bindingKey(cmdCD) != null;
    }

    private Integer getCooldownSeconds(Method method, CommandContext context) {
        CmdCD cmdCD = ReflectionUtil.resolveMethodOrClassAnnotation(method, context.getExecutorClass(), CmdCD.class);
        if (cmdCD != null) {
            String bindingKey = bindingKey(cmdCD);
            if (bindingKey == null) {
                return cmdCD.value();
            }
            Object executor = context.getExecutor();
            return executor instanceof BaseCommandExecutor
                    ? ConfigBoundCooldownState.seconds((BaseCommandExecutor) executor).get(bindingKey)
                    : null;
        }
        return defaultCooldownSeconds;
    }
    
    /**
     * Fails the command closed for a config-bound {@code @CmdCD} this validator never had resolved
     * -- reachable only when a {@code CooldownValidator} is added after the module loaded, or an
     * executor is registered outside the module loader. Treating it as "no cooldown" would be a
     * silent no-op, and throwing would escape {@code onCommand} as Bukkit's generic internal
     * error; instead the player gets the same message shape as a failed command, and the cause is
     * logged at SEVERE and sent to the error report collector like any other command failure
     * (#531 gate-1 WR-02).
     */
    private ValidationResult unresolvedBinding(CommandContext context, Method method) {
        CmdCD cmdCD = ReflectionUtil.resolveMethodOrClassAnnotation(method, context.getExecutorClass(), CmdCD.class);
        IllegalStateException cause = new IllegalStateException("@CmdCD on "
                + method.getDeclaringClass().getSimpleName() + "." + method.getName() + " is bound to "
                + cmdCD.config().getSimpleName() + " key '" + cmdCD.key() + "', but the framework never resolved "
                + "that binding for this CooldownValidator; bound cooldowns are resolved when an UltiTools module "
                + "loads, so a validator added later cannot enforce one");
        LOGGER.log(Level.SEVERE, cause.getMessage(), cause);
        reportUnresolvedBinding(context, cause);
        return ValidationResult.failure(ChatColor.RED + "命令执行出错: " + cause.getMessage(),
                "command.error.cooldown-unresolved");
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // never let error reporting break the command path
    private static void reportUnresolvedBinding(CommandContext context, IllegalStateException cause) {
        try {
            UltiTools instance = UltiTools.getInstance();
            ErrorReportCollector collector = instance == null ? null : instance.getErrorReportCollector();
            if (collector != null) {
                collector.reportError(cause, null,
                        TriggerContext.command(context.getSender(), context.getCommand().getName()));
            }
        } catch (RuntimeException ignored) {
            // Never re-enter logging from error reporting, matching BaseCommandExecutor's own path.
        }
    }

    /**
     * The cache key of a config-bound {@code @CmdCD}: its config class and key.
     *
     * @param cmdCD the annotation
     * @return {@code configClassName#key}, or {@code null} when {@code cmdCD} is unbound
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static String bindingKey(CmdCD cmdCD) {
        if (cmdCD.config() == AbstractConfigEntity.class && cmdCD.key().isEmpty()) {
            return null;
        }
        return cmdCD.config().getName() + "#" + cmdCD.key();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public String getName() {
        return "CooldownValidator";
    }
}
