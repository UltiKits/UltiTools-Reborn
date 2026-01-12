package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.AccessMode;
import lombok.Data;

import javax.annotation.Nullable;

/**
 * 背包打开结果实体
 * 封装尝试打开背包的结果信息
 * 
 * @author wisdomme
 * @version 1.0.0
 */
@Data
public class BagOpenResult {
    
    /**
     * 是否成功打开
     */
    private final boolean success;
    
    /**
     * 访问模式（成功时有效）
     */
    @Nullable
    private final AccessMode accessMode;
    
    /**
     * 提示消息（失败或只读模式时显示）
     */
    @Nullable
    private final String message;
    
    /**
     * 现有锁信息（被阻止时提供）
     */
    @Nullable
    private final BagLockInfo existingLock;
    
    /**
     * 私有构造函数
     */
    private BagOpenResult(boolean success, AccessMode accessMode, String message, BagLockInfo existingLock) {
        this.success = success;
        this.accessMode = accessMode;
        this.message = message;
        this.existingLock = existingLock;
    }
    
    /**
     * 创建编辑模式成功结果
     * 
     * @return 编辑模式的成功结果
     */
    public static BagOpenResult editMode() {
        return new BagOpenResult(true, AccessMode.EDIT, null, null);
    }
    
    /**
     * 创建只读模式结果
     * 
     * @param ownerLock 所有者锁信息
     * @return 只读模式的结果
     */
    public static BagOpenResult readOnlyMode(BagLockInfo ownerLock) {
        String message = "§e该背包正被 " + ownerLock.getHolderName() + " 使用中，当前为只读模式";
        return new BagOpenResult(true, AccessMode.READ_ONLY, message, ownerLock);
    }
    
    /**
     * 创建被阻止的结果
     * 
     * @param lock 阻止的锁信息
     * @return 被阻止的结果
     */
    public static BagOpenResult blocked(BagLockInfo lock) {
        String message;
        if (lock.getLockType() == com.ultikits.plugins.remotebag.enums.LockType.ADMIN) {
            message = "§c该背包正被管理员 " + lock.getHolderName() + " 编辑中，请稍后再试";
        } else {
            message = "§c该背包正被 " + lock.getHolderName() + " 使用中，请稍后再试";
        }
        return new BagOpenResult(false, null, message, lock);
    }
    
    /**
     * 判断是否为编辑模式
     * 
     * @return 如果是编辑模式返回 true
     */
    public boolean isEditMode() {
        return success && accessMode == AccessMode.EDIT;
    }
    
    /**
     * 判断是否为只读模式
     * 
     * @return 如果是只读模式返回 true
     */
    public boolean isReadOnlyMode() {
        return success && accessMode == AccessMode.READ_ONLY;
    }
}
