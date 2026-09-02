package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.ChatColor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates and manages command usage locks to prevent concurrent execution.
 * Supports sender-specific and server-wide locks.
 * <p>
 * <b>Acquire-then-execute (D-02, GEN-09, @since 6.3.0):</b> acquisition is no longer a
 * separate step called by field from {@code BaseCommandExecutor.executeCommand}. It happens
 * inside {@link #validate(CommandContext)} itself -- "acquire-as-you-validate" -- so that on
 * the synchronous dispatch path there is no scheduling point between deciding the lock is free
 * and taking it. A failed acquisition surfaces as an ordinary {@link ValidationResult#failure}
 * through the normal validation-rejection path; the mapped method is never invoked. Release
 * happens in {@link #onComplete(CommandContext, boolean)}, driven by the validator chain that
 * actually ran this validator (see {@link CommandValidator#onComplete(CommandContext, boolean)}) --
 * never called twice, and never called for an invocation whose acquisition failed.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class UsageLockValidator implements CommandValidator, PlayerCacheSaver {

    private static final int ORDER = 250;

    /**
     * Locks per sender: player UUID -> set of locked method keys
     * <p>
     * {@code saveBeforeRemove = true} so {@link #savePlayerData(UUID)} -- which delegates to
     * the pre-existing {@link #clearPlayerLocks(UUID)}, clearing BOTH this field and {@link
     * #serverLocks} in one call -- fires exactly once per quit (GEN-08, D-03). {@link
     * #serverLocks} deliberately does NOT also carry {@code saveBeforeRemove = true}: doing so
     * would fire {@link #savePlayerData(UUID)} a second, redundant time per quit.
     */
    @PlayerCache(saveBeforeRemove = true)
    private final Map<UUID, Set<String>> senderLocks = new ConcurrentHashMap<>();

    /**
     * Server-wide locks: method key -> locking player UUID
     */
    @PlayerCache
    private final Map<String, UUID> serverLocks = new ConcurrentHashMap<>();

    /**
     * True once this instance has registered {@link #senderLocks}/{@link #serverLocks} with the
     * live {@link PlayerCacheManager} for quit-based sweeping (GEN-08, D-03). Set lazily from
     * {@link #validate(CommandContext)} rather than the constructor, mirroring {@code
     * CooldownValidator}'s identical field of the same name and rationale: a bare {@code new
     * UsageLockValidator()} must never attempt contact with a core plugin that may not exist
     * yet, and a failed attempt is retried on the next call rather than permanently abandoned.
     */
    private volatile boolean playerCacheRegistered = false;

    /**
     * Validates -- and, per the acquire-then-execute contract on this class, ATTEMPTS TO
     * ACQUIRE -- the lock for this invocation. Delegates entirely to {@link #acquireLock(CommandContext)}:
     * a successful acquisition is a successful validation; a failed acquisition is reported as
     * an ordinary validation failure carrying the scope-appropriate i18n key.
     *
     * @param context the command context
     * @return the validation result
     */
    @Override
    public ValidationResult validate(CommandContext context) {
        ensurePlayerCacheRegistered();

        if (acquireLock(context)) {
            return ValidationResult.success();
        }

        // acquireLock() only returns false when the method (or its declaring class, per D-01
        // follow-up most-derived-wins resolution) carries @UsageLimit with a SENDER or ALL
        // scope, so this resolves non-null here.
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit.value() == UsageLimit.LimitType.SENDER) {
            return ValidationResult.failure(
                    ChatColor.RED + UltiTools.getInstance().i18n("请先等待上一条命令执行完毕！"),
                    "command.error.sender_locked"
            );
        }
        return ValidationResult.failure(
                ChatColor.RED + UltiTools.getInstance().i18n("请先等待其他玩家发送的命令执行完毕！"),
                "command.error.server_locked"
        );
    }

    /**
     * Post-action hook that releases the lock acquired by {@link #validate(CommandContext)} for
     * this invocation. Delegates to {@link #releaseLock(CommandContext)} and is invoked only by
     * a chain that actually ran this validator for the current dispatch -- see
     * {@link CommandValidator#onComplete(CommandContext, boolean)}. An invocation whose
     * acquisition failed never reaches this hook (it is absent from the chain's
     * passed-validator list), so there is nothing here to release on that path.
     *
     * @param context          the command context
     * @param commandSucceeded ignored -- the lock releases whether the mapped method
     *                         succeeded or threw
     * @since 6.3.0
     */
    @Override
    public void onComplete(CommandContext context, boolean commandSucceeded) {
        releaseLock(context);
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
     * {@link PlayerCacheSaver} hook: fired by {@link PlayerCacheManager#onPlayerQuit(UUID)}
     * ("the quit sweep") for the quitting player -- before the generic {@code @PlayerCache}
     * field sweep removes the (by then already-empty) {@link #senderLocks}/{@link #serverLocks}
     * entries. Delegates to the pre-existing {@link #clearPlayerLocks(UUID)} rather than
     * re-deriving its predicate -- the SAME predicate {@code PlayerCacheManager}'s own
     * value-side sweep branch was built from -- giving that previously zero-caller method a
     * real, quit-path-reached production call site (GEN-08). Only {@link #senderLocks} carries
     * {@code saveBeforeRemove = true}, so this fires exactly once per quit despite clearing both
     * maps.
     *
     * @param playerId the UUID of the player quitting
     * @since 6.3.0
     */
    @Override
    public void savePlayerData(UUID playerId) {
        clearPlayerLocks(playerId);
    }

    /**
     * Acquires a lock for command execution.
     * Should be called before executing the command. As of 6.3.0 this is also the method
     * {@link #validate(CommandContext)} itself calls -- see the class-level acquire-then-execute
     * note.
     *
     * @param context the command context
     * @return true if lock was acquired, false if already locked
     */
    public boolean acquireLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit == null) {
            return true;
        }

        String methodKey = method.toString();
        
        if (!context.isPlayer() && !limit.ContainConsole()) {
            return true;
        }
        
        if (limit.value() == UsageLimit.LimitType.SENDER) {
            if (!context.isPlayer()) {
                // senderLocks is keyed by player UUID -- a non-player sender permitted through
                // by ContainConsole() can never OWN a SENDER-scope lock, so it acquires nothing.
                return true;
            }
            UUID playerId = context.getPlayer().getUniqueId();
            return senderLocks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                    .add(methodKey);
        } else if (limit.value() == UsageLimit.LimitType.ALL) {
            if (!context.isPlayer()) {
                // serverLocks' value is a player UUID -- a non-player sender permitted through
                // by ContainConsole() can never OWN an ALL-scope lock either, but it is still
                // gated by whatever player currently holds it.
                return !serverLocks.containsKey(methodKey);
            }
            return serverLocks.putIfAbsent(methodKey, context.getPlayer().getUniqueId()) == null;
        }
        
        return true;
    }
    
    /**
     * Releases a lock after command execution.
     * Should be called after command execution completes (success or failure). As of 6.3.0 this
     * is also the method {@link #onComplete(CommandContext, boolean)} calls -- see the
     * class-level acquire-then-execute note.
     *
     * @param context the command context
     */
    public void releaseLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        UsageLimit limit = resolveLimit(method, context.getExecutorClass());
        if (limit == null) {
            return;
        }

        String methodKey = method.toString();
        
        if (limit.value() == UsageLimit.LimitType.SENDER && context.isPlayer()) {
            UUID playerId = context.getPlayer().getUniqueId();
            Set<String> locks = senderLocks.get(playerId);
            if (locks != null) {
                locks.remove(methodKey);
                if (locks.isEmpty()) {
                    senderLocks.remove(playerId);
                }
            }
        } else if (limit.value() == UsageLimit.LimitType.ALL && context.isPlayer()) {
            // Ownership-gated, atomic conditional removal (Pitfall 5 / T-05-03): only the
            // sender recorded as the holder at acquisition time may release an ALL-scope lock.
            // A prior build removed unconditionally here, letting any sender free any other
            // sender's server-wide lock.
            serverLocks.remove(methodKey, context.getPlayer().getUniqueId());
        }
    }
    
    /**
     * Resolves the effective {@code @UsageLimit} for {@code method}: the method's own
     * declaration, falling back to a class-level one on the CONCRETE executor class dispatching
     * this command, falling back to a class-level one on the method's declaring class --
     * most-derived-wins, via {@link ReflectionUtil#resolveMethodOrClassAnnotation(Method, Class,
     * Class)}. This is the SAME resolution {@code PluginManager}'s load-time refusal treats as
     * satisfying the contract (SILENT-11 / D-01 follow-up, WR-02 / 05-REVIEW.md fix): a
     * class-level {@code @UsageLimit} that passes the load-time check -- whether declared on a
     * shared abstract base or on the concrete executor class itself -- now actually locks every
     * inherited mapping that does not declare its own.
     *
     * @param method        the matched command mapping method, or {@code null}
     * @param executorClass the concrete executor class dispatching this command (WR-02,
     *                      05-REVIEW.md), or {@code null} when unavailable -- falls back to the
     *                      pre-WR-02, declaring-class-only resolution in that case
     * @return the resolved annotation, or {@code null} when {@code method} is {@code null} or
     *         neither the method, {@code executorClass}, nor its declaring class carries one
     * @since 6.3.0
     */
    private static UsageLimit resolveLimit(Method method, Class<?> executorClass) {
        if (method == null) {
            return null;
        }
        return ReflectionUtil.resolveMethodOrClassAnnotation(method, executorClass, UsageLimit.class);
    }

    /**
     * Clears all locks for a player.
     * Useful when a player disconnects.
     *
     * @param playerId the player's UUID
     */
    public void clearPlayerLocks(UUID playerId) {
        senderLocks.remove(playerId);
        serverLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }
    
    /**
     * Clears all locks.
     */
    public void clearAllLocks() {
        senderLocks.clear();
        serverLocks.clear();
    }
    
    /**
     * Checks if a specific method is locked for a player.
     *
     * @param playerId  the player's UUID
     * @param methodKey the method key
     * @return true if locked
     */
    public boolean isLocked(UUID playerId, String methodKey) {
        Set<String> locks = senderLocks.get(playerId);
        if (locks != null && locks.contains(methodKey)) {
            return true;
        }
        return serverLocks.containsKey(methodKey);
    }
    
    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public String getName() {
        return "UsageLockValidator";
    }
}
