package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.CommandContext;
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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
     * Map of player UUID -> (method name -> cooldown end timestamp)
     * <p>
     * {@code saveBeforeRemove = true} so {@link #savePlayerData(UUID)} -- which delegates to
     * the pre-existing {@link #clearCooldowns(UUID)} -- fires on quit; see that method's
     * javadoc for why (GEN-08, D-03).
     */
    @PlayerCache(saveBeforeRemove = true)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

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
     * Resolved seconds for every config-bound {@code @CmdCD} this validator can meet, per concrete
     * executor class, then keyed by {@link #bindingKey(CmdCD)} (#531). Written on the main thread --
     * once when the module loads, then only by the {@code /ul reload} step after a successful
     * configuration reload -- and read on dispatch threads, so it is replaced as a whole immutable
     * map behind a {@code volatile} reference rather than mutated.
     */
    private volatile Map<Class<?>, Map<String, Integer>> boundCooldownSeconds = Collections.emptyMap();

    /**
     * Load-time value sources of the bindings in {@link #boundCooldownSeconds}, per owning module and
     * executor class, so a reload of one module re-reads only that module's config even when two
     * modules' executors share this validator (#531 gate-1 round 2, IN-01). Main thread only.
     */
    private volatile Map<UltiToolsPlugin, Map<Class<?>, Map<String, Supplier<Long>>>> boundCooldownSources =
            Collections.emptyMap();
    
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

        Integer resolvedSeconds = getCooldownSeconds(method, context.getExecutorClass());
        if (resolvedSeconds == null) {
            return unresolvedBinding(context, method);
        }
        int cooldownSeconds = resolvedSeconds;
        if (cooldownSeconds <= 0) {
            return ValidationResult.success();
        }
        
        UUID playerId = player.getUniqueId();
        String methodKey = method.toString();
        
        Map<String, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns != null) {
            Long endTime = playerCooldowns.get(methodKey);
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

        Integer cooldownSeconds = getCooldownSeconds(method, context.getExecutorClass());
        if (cooldownSeconds == null || cooldownSeconds <= 0) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        String methodKey = method.toString();
        long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cooldownSeconds);
        
        cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(methodKey, endTime);
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
     * Clears a specific cooldown for a player.
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key
     */
    public void clearCooldown(UUID playerId, String methodKey) {
        Map<String, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns != null) {
            playerCooldowns.remove(methodKey);
        }
    }
    
    /**
     * Gets the remaining cooldown time in seconds.
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key
     * @return remaining seconds, or 0 if not on cooldown
     */
    public long getRemainingCooldown(UUID playerId, String methodKey) {
        Map<String, Long> playerCooldowns = cooldowns.get(playerId);
        if (playerCooldowns == null) {
            return 0;
        }
        Long endTime = playerCooldowns.get(methodKey);
        if (endTime == null || System.currentTimeMillis() >= endTime) {
            return 0;
        }
        return TimeUnit.MILLISECONDS.toSeconds(endTime - System.currentTimeMillis()) + 1;
    }
    
    /**
     * Cleans up expired cooldowns to prevent memory leaks.
     * Should be called periodically.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        cooldowns.forEach((playerId, methods) -> {
            methods.entrySet().removeIf(entry -> entry.getValue() < now);
            if (methods.isEmpty()) {
                cooldowns.remove(playerId);
            }
        });
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
     * A config-bound {@code @CmdCD} (#531) returns the seconds cached by
     * {@link #mergeBoundCooldownSeconds(Class, Map)} for {@code executorClass} instead of its literal -- never the live config field,
     * because a refused {@code /ul reload} leaves the refused value in that field. A bound
     * annotation with no cached value returns {@code null}: it was never resolved by a module
     * load, and the caller refuses the command rather than treat it as "no cooldown".
     *
     * @param method        the matched command mapping method
     * @param executorClass the concrete executor class dispatching this command (WR-02,
     *                      05-REVIEW.md), or {@code null} when unavailable -- falls back to the
     *                      pre-WR-02, declaring-class-only resolution in that case
     * @return the resolved cooldown in seconds, or {@code null} for an unresolved binding
     * @since 6.3.0
     */
    private Integer getCooldownSeconds(Method method, Class<?> executorClass) {
        CmdCD cmdCD = ReflectionUtil.resolveMethodOrClassAnnotation(method, executorClass, CmdCD.class);
        if (cmdCD != null) {
            String bindingKey = bindingKey(cmdCD);
            if (bindingKey == null) {
                return cmdCD.value();
            }
            return getBoundCooldownSeconds(executorClass).get(bindingKey);
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
     * Adds resolved seconds for the config-bound {@code @CmdCD} annotations of {@code executorClass}
     * to this validator's cache, keeping every other entry. Values are kept <b>per executor
     * class</b>: two executors sharing this validator -- even from two modules that bind the same
     * config class and key to two different config instances -- each keep their own value (Codex
     * round 1 on #536), and merging rather than replacing keeps every executor's bindings (gate-1
     * WR-02).
     *
     * @param executorClass       the concrete executor class the bindings were declared on (the
     *                            class {@code BaseCommandExecutor} puts in its {@code CommandContext})
     * @param secondsByBindingKey resolved seconds keyed by {@link #bindingKey(CmdCD)}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void mergeBoundCooldownSeconds(Class<?> executorClass, Map<String, Integer> secondsByBindingKey) {
        Map<Class<?>, Map<String, Integer>> next = new HashMap<>(boundCooldownSeconds);
        Map<String, Integer> forExecutor = new HashMap<>(next.getOrDefault(executorClass, Collections.emptyMap()));
        forExecutor.putAll(secondsByBindingKey);
        next.put(executorClass, Collections.unmodifiableMap(forExecutor));
        this.boundCooldownSeconds = Collections.unmodifiableMap(next);
    }

    /**
     * Replaces the resolved seconds of {@code executorClass}'s config-bound {@code @CmdCD}
     * annotations. The framework itself uses {@link #mergeBoundCooldownSeconds(Class, Map)}; module
     * code has no reason to call either. A cooldown that is already running keeps the end time it
     * was stamped with.
     *
     * @param executorClass       the concrete executor class
     * @param secondsByBindingKey resolved seconds keyed by {@link #bindingKey(CmdCD)}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void setBoundCooldownSeconds(Class<?> executorClass, Map<String, Integer> secondsByBindingKey) {
        Map<Class<?>, Map<String, Integer>> next = new HashMap<>(boundCooldownSeconds);
        next.put(executorClass, Collections.unmodifiableMap(new HashMap<>(secondsByBindingKey)));
        this.boundCooldownSeconds = Collections.unmodifiableMap(next);
    }

    /**
     * @param executorClass the concrete executor class
     * @return resolved seconds of its config-bound {@code @CmdCD} annotations, keyed by
     *         {@link #bindingKey(CmdCD)}; never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Map<String, Integer> getBoundCooldownSeconds(Class<?> executorClass) {
        return boundCooldownSeconds.getOrDefault(executorClass, Collections.emptyMap());
    }

    /**
     * Diagnostic view of the whole cache.
     *
     * @return every executor's resolved seconds, keyed by {@code executorClassName|bindingKey};
     *         never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Map<String, Integer> getBoundCooldownSeconds() {
        Map<String, Integer> all = new HashMap<>();
        for (Map.Entry<Class<?>, Map<String, Integer>> executor : boundCooldownSeconds.entrySet()) {
            for (Map.Entry<String, Integer> binding : executor.getValue().entrySet()) {
                all.put(executor.getKey().getName() + "|" + binding.getKey(), binding.getValue());
            }
        }
        return Collections.unmodifiableMap(all);
    }

    /**
     * Adds the load-time sources of the config-bound {@code @CmdCD} values that {@code owner}'s
     * {@code executorClass} declared on this validator, keyed by {@link #bindingKey(CmdCD)}. Each
     * source reads the module's config field it was resolved to at load, so a reload re-reads that
     * same instance instead of resolving the binding again (gate-1 IN-01), and only the reloading
     * module's own sources are ever read (round 2).
     *
     * @param owner               the module whose executor declared the bindings
     * @param executorClass       that executor's concrete class
     * @param sourcesByBindingKey value sources keyed by {@link #bindingKey(CmdCD)}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void mergeBoundCooldownSources(UltiToolsPlugin owner, Class<?> executorClass,
                                          Map<String, Supplier<Long>> sourcesByBindingKey) {
        Map<UltiToolsPlugin, Map<Class<?>, Map<String, Supplier<Long>>>> next = new IdentityHashMap<>(boundCooldownSources);
        Map<Class<?>, Map<String, Supplier<Long>>> owned = new HashMap<>(next.getOrDefault(owner, Collections.emptyMap()));
        Map<String, Supplier<Long>> forExecutor = new HashMap<>(owned.getOrDefault(executorClass, Collections.emptyMap()));
        forExecutor.putAll(sourcesByBindingKey);
        owned.put(executorClass, Collections.unmodifiableMap(forExecutor));
        next.put(owner, Collections.unmodifiableMap(owned));
        this.boundCooldownSources = Collections.unmodifiableMap(next);
    }

    /**
     * @param owner         a module
     * @param executorClass one of its executors' concrete class
     * @return the load-time sources of that executor's config-bound {@code @CmdCD} values on this
     *         validator, keyed by {@link #bindingKey(CmdCD)}; never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public Map<String, Supplier<Long>> getBoundCooldownSources(UltiToolsPlugin owner, Class<?> executorClass) {
        return boundCooldownSources.getOrDefault(owner, Collections.emptyMap())
                .getOrDefault(executorClass, Collections.emptyMap());
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
