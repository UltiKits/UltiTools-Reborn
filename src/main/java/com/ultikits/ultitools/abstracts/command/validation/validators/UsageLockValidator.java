package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates and manages command usage locks to prevent concurrent execution.
 * Supports sender-specific and server-wide locks.
 * <p>
 * 验证和管理命令使用锁以防止并发执行。
 * 支持发送者特定锁和服务器范围锁。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class UsageLockValidator implements CommandValidator {
    
    private static final int ORDER = 250;
    
    /**
     * Locks per sender: player UUID -> set of locked method keys
     */
    private final Map<UUID, Set<String>> senderLocks = new ConcurrentHashMap<>();
    
    /**
     * Server-wide locks: method key -> locking player UUID
     */
    private final Map<String, UUID> serverLocks = new ConcurrentHashMap<>();
    
    @Override
    public ValidationResult validate(CommandContext context) {
        Method method = context.getMatchedMethod();
        if (method == null || !method.isAnnotationPresent(UsageLimit.class)) {
            return ValidationResult.success();
        }
        
        UsageLimit limit = method.getAnnotation(UsageLimit.class);
        String methodKey = method.toString();
        
        // Check if sender must be a player for this limit
        if (!context.isPlayer() && !limit.ContainConsole()) {
            return ValidationResult.success();
        }
        
        if (limit.value() == UsageLimit.LimitType.SENDER) {
            return validateSenderLock(context, methodKey);
        } else if (limit.value() == UsageLimit.LimitType.ALL) {
            return validateServerLock(context, methodKey);
        }
        
        return ValidationResult.success();
    }
    
    private ValidationResult validateSenderLock(CommandContext context, String methodKey) {
        if (!context.isPlayer()) {
            return ValidationResult.success();
        }
        
        UUID playerId = context.getPlayer().getUniqueId();
        Set<String> locks = senderLocks.get(playerId);
        
        if (locks != null && locks.contains(methodKey)) {
            return ValidationResult.failure(
                    ChatColor.RED + UltiTools.getInstance().i18n("请先等待上一条命令执行完毕！"),
                    "command.error.sender_locked"
            );
        }
        
        return ValidationResult.success();
    }
    
    private ValidationResult validateServerLock(CommandContext context, String methodKey) {
        if (serverLocks.containsKey(methodKey)) {
            return ValidationResult.failure(
                    ChatColor.RED + UltiTools.getInstance().i18n("请先等待其他玩家发送的命令执行完毕！"),
                    "command.error.server_locked"
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Acquires a lock for command execution.
     * Should be called before executing the command.
     * 获取命令执行的锁。
     * 应在执行命令之前调用。
     *
     * @param context the command context
     * @return true if lock was acquired, false if already locked
     */
    public boolean acquireLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        if (method == null || !method.isAnnotationPresent(UsageLimit.class)) {
            return true;
        }
        
        UsageLimit limit = method.getAnnotation(UsageLimit.class);
        String methodKey = method.toString();
        
        if (!context.isPlayer() && !limit.ContainConsole()) {
            return true;
        }
        
        if (limit.value() == UsageLimit.LimitType.SENDER && context.isPlayer()) {
            UUID playerId = context.getPlayer().getUniqueId();
            return senderLocks.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                    .add(methodKey);
        } else if (limit.value() == UsageLimit.LimitType.ALL && context.isPlayer()) {
            return serverLocks.putIfAbsent(methodKey, context.getPlayer().getUniqueId()) == null;
        }
        
        return true;
    }
    
    /**
     * Releases a lock after command execution.
     * Should be called after command execution completes (success or failure).
     * 在命令执行后释放锁。
     * 应在命令执行完成（成功或失败）后调用。
     *
     * @param context the command context
     */
    public void releaseLock(CommandContext context) {
        Method method = context.getMatchedMethod();
        if (method == null || !method.isAnnotationPresent(UsageLimit.class)) {
            return;
        }
        
        UsageLimit limit = method.getAnnotation(UsageLimit.class);
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
        } else if (limit.value() == UsageLimit.LimitType.ALL) {
            serverLocks.remove(methodKey);
        }
    }
    
    /**
     * Clears all locks for a player.
     * Useful when a player disconnects.
     * 清除玩家的所有锁。
     * 当玩家断开连接时很有用。
     *
     * @param playerId the player's UUID
     */
    public void clearPlayerLocks(UUID playerId) {
        senderLocks.remove(playerId);
        serverLocks.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }
    
    /**
     * Clears all locks.
     * 清除所有锁。
     */
    public void clearAllLocks() {
        senderLocks.clear();
        serverLocks.clear();
    }
    
    /**
     * Checks if a specific method is locked for a player.
     * 检查特定方法是否对玩家锁定。
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
