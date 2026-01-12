package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.LockType;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 背包锁信息实体
 * 记录背包锁的持有者和相关信息
 * 
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@Builder
public class BagLockInfo {
    
    /**
     * 锁持有者 UUID
     */
    private final UUID holderUuid;
    
    /**
     * 锁持有者名称（用于显示）
     */
    private final String holderName;
    
    /**
     * 锁类型
     */
    private final LockType lockType;
    
    /**
     * 锁获取时间（毫秒时间戳）
     */
    private final long acquiredAt;
    
    /**
     * 检查锁是否已过期
     * 
     * @param timeoutMillis 超时时间（毫秒）
     * @return 如果已过期返回 true
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - acquiredAt > timeoutMillis;
    }
}
