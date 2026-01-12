package com.ultikits.plugins.remotebag.enums;

/**
 * 背包锁类型枚举
 * 
 * @author wisdomme
 * @version 1.0.0
 */
public enum LockType {
    /**
     * 所有者锁 - 最高优先级
     * 当背包所有者正在使用时获取此锁
     */
    OWNER,
    
    /**
     * 管理员锁 - 低优先级
     * 当管理员查看/编辑他人背包时获取此锁
     */
    ADMIN
}
