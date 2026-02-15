package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdCD;
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
public class CooldownValidator implements CommandValidator {
    
    private static final int ORDER = 300;
    
    /**
     * Map of player UUID -> (method name -> cooldown end timestamp)
     */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    
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
    
    private int getCooldownSeconds(Method method) {
        if (method.isAnnotationPresent(CmdCD.class)) {
            return method.getAnnotation(CmdCD.class).value();
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
