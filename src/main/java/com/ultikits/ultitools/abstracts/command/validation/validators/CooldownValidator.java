package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.utils.ReflectionUtil;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Validates and manages command cooldowns for players.
 * Thread-safe implementation using ConcurrentHashMap.
 * <p>
 * 验证和管理玩家的命令冷却。
 * 使用 ConcurrentHashMap 的线程安全实现。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class CooldownValidator implements CommandValidator, PlayerCacheManager.ExpiringPlayerCache,
        PlayerCacheSaver {

    private static final int ORDER = 300;

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
     * Creates a cooldown validator with no default cooldown.
     * 创建一个没有默认冷却的冷却验证器。
     */
    public CooldownValidator() {
        this.defaultCooldownSeconds = 0;
    }
    
    /**
     * Creates a cooldown validator with a default cooldown.
     * 创建具有默认冷却的冷却验证器。
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
        
        int cooldownSeconds = getCooldownSeconds(method);
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
     * 在命令执行后应用冷却。
     * 应在成功执行命令后调用。
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
        
        int cooldownSeconds = getCooldownSeconds(method);
        if (cooldownSeconds <= 0) {
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
     * <p>
     * 应用本次调用所需冷却的后置钩子。委托给 {@link #applyCooldown(CommandContext)}，仅由实际为本次
     * 分发运行了该验证器的责任链调用——参见 {@link CommandValidator#onComplete(CommandContext, boolean)}。
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
        if (playerCacheRegistered || UltiTools.getInstance() == null) {
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
     * 清除玩家的所有冷却。
     *
     * @param playerId the player's UUID
     */
    public void clearCooldowns(UUID playerId) {
        cooldowns.remove(playerId);
    }
    
    /**
     * Clears a specific cooldown for a player.
     * 清除玩家的特定冷却。
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
     * 获取剩余的冷却时间（秒）。
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
     * 清理过期的冷却以防止内存泄漏。
     * 应定期调用。
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
     * to a class-level {@code @CmdCD} on its declaring class, falling back to
     * {@link #defaultCooldownSeconds} when neither is present -- most-derived-wins, via
     * {@link ReflectionUtil#resolveMethodOrClassAnnotation}. This is the SAME resolution
     * {@code PluginManager}'s load-time refusal already treats as satisfying the contract
     * (SILENT-11 / D-01 follow-up): a class-level-only {@code @CmdCD} that passes the load-time
     * check now actually cools down every mapping that does not declare its own.
     * <p>
     * 解析 {@code method} 的冷却时间：方法自身的 {@code @CmdCD}，若无则回退到其声明类上的类级
     * {@code @CmdCD}，两者都不存在时回退到 {@link #defaultCooldownSeconds}——方法级优先，经由
     * {@link ReflectionUtil#resolveMethodOrClassAnnotation} 解析。这与 {@code PluginManager} 加载时
     * 拒绝检查已经采信的解析方式完全一致（SILENT-11 / D-01 追加任务）：一个仅在类级声明、通过了
     * 加载时检查的 {@code @CmdCD}，现在会真正冷却每一个未声明自己 {@code @CmdCD} 的映射。
     *
     * @param method the matched command mapping method <br> 已匹配的命令映射方法
     * @return the resolved cooldown in seconds <br> 解析出的冷却秒数
     * @since 6.3.0
     */
    private int getCooldownSeconds(Method method) {
        CmdCD cmdCD = ReflectionUtil.resolveMethodOrClassAnnotation(method, CmdCD.class);
        if (cmdCD != null) {
            return cmdCD.value();
        }
        return defaultCooldownSeconds;
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
