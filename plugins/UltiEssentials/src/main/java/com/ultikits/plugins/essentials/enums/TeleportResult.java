package com.ultikits.plugins.essentials.enums;

/**
 * Result of a teleport operation.
 * <p>
 * 传送操作的结果枚举，用于统一各种传送功能的返回值。
 *
 * @author wisdomme
 * @version 1.0.0
 */
public enum TeleportResult {
    /**
     * Teleport completed successfully (instant).
     * 传送成功（即时）
     */
    SUCCESS,
    
    /**
     * Warmup teleport started, player needs to wait.
     * 预热传送已开始，玩家需要等待
     */
    WARMUP_STARTED,
    
    /**
     * Target location not found (home/warp doesn't exist).
     * 目标位置未找到（家/地标不存在）
     */
    NOT_FOUND,
    
    /**
     * Target world doesn't exist or isn't loaded.
     * 目标世界不存在或未加载
     */
    WORLD_NOT_FOUND,
    
    /**
     * Player doesn't have permission to teleport.
     * 玩家没有权限传送
     */
    NO_PERMISSION,
    
    /**
     * Player is already in a teleport warmup.
     * 玩家已在传送预热中
     */
    ALREADY_TELEPORTING,
    
    /**
     * The teleport feature is disabled.
     * 传送功能已禁用
     */
    DISABLED,
    
    /**
     * Teleport was cancelled (e.g., player moved).
     * 传送被取消（如：玩家移动）
     */
    CANCELLED
}
