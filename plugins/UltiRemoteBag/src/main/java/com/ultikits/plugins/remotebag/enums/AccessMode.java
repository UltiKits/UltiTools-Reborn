package com.ultikits.plugins.remotebag.enums;

/**
 * 背包访问模式枚举
 * 
 * @author wisdomme
 * @version 1.0.0
 */
public enum AccessMode {
    /**
     * 编辑模式 - 可以修改物品并保存
     */
    EDIT,
    
    /**
     * 只读模式 - 仅能查看，不能修改
     * 当所有者正在使用背包时，管理员以此模式打开
     */
    READ_ONLY
}
