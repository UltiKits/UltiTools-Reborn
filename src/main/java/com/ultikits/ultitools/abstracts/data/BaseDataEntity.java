package com.ultikits.ultitools.abstracts.data;

import com.ultikits.ultitools.annotations.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * Enhanced abstract data entity with generic ID type and lifecycle hooks.
 * Provides a type-safe foundation for persistent data entities.
 * <p>
 * 带有泛型 ID 类型和生命周期钩子的增强型抽象数据实体。
 * 为持久化数据实体提供类型安全的基础。
 *
 * @param <ID> the type of the entity identifier
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@Data
@EqualsAndHashCode(of = "id")
public abstract class BaseDataEntity<ID extends Serializable> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    @Column("id")
    private ID id;
    
    /**
     * Called before the entity is persisted for the first time.
     * Override to add custom pre-insert logic.
     * <p>
     * 在实体首次持久化之前调用。
     * 重写以添加自定义预插入逻辑。
     */
    public void onCreate() {
        // Override in subclass
    }
    
    /**
     * Called before the entity is updated.
     * Override to add custom pre-update logic.
     * <p>
     * 在实体更新之前调用。
     * 重写以添加自定义预更新逻辑。
     */
    public void onUpdate() {
        // Override in subclass
    }
    
    /**
     * Called before the entity is deleted.
     * Override to add custom pre-delete logic.
     * <p>
     * 在实体删除之前调用。
     * 重写以添加自定义预删除逻辑。
     */
    public void onDelete() {
        // Override in subclass
    }
    
    /**
     * Called after the entity is loaded from the data store.
     * Override to add custom post-load logic.
     * <p>
     * 在从数据存储加载实体后调用。
     * 重写以添加自定义后加载逻辑。
     */
    public void onLoad() {
        // Override in subclass
    }
    
    /**
     * Validates the entity before persistence operations.
     * Override to add custom validation logic.
     * <p>
     * 在持久化操作之前验证实体。
     * 重写以添加自定义验证逻辑。
     *
     * @return true if the entity is valid, false otherwise
     */
    public boolean validate() {
        return true;
    }
    
    /**
     * Gets validation error messages if validation fails.
     * Override along with {@link #validate()} to provide error messages.
     * <p>
     * 如果验证失败，获取验证错误消息。
     * 与 {@link #validate()} 一起重写以提供错误消息。
     *
     * @return list of validation error messages, empty if valid
     */
    public java.util.List<String> getValidationErrors() {
        return java.util.Collections.emptyList();
    }
    
    /**
     * Checks if this entity is new (not yet persisted).
     * 检查此实体是否是新的（尚未持久化）。
     *
     * @return true if the entity has no ID, false otherwise
     */
    public boolean isNew() {
        return id == null;
    }
    
    /**
     * Creates a copy of this entity without the ID.
     * Useful for creating new entities based on existing ones.
     * <p>
     * 创建此实体的副本（不含 ID）。
     * 对于基于现有实体创建新实体很有用。
     *
     * @return a new entity with the same data but no ID
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseDataEntity<ID>> T copyWithoutId() {
        try {
            T copy = (T) this.clone();
            copy.setId(null);
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to copy entity", e);
        }
    }
    
    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
