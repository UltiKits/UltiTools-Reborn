package com.ultikits.ultitools.abstracts.data;

import java.io.Serializable;

import com.ultikits.ultitools.annotations.Column;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Enhanced abstract data entity with generic ID type and lifecycle hooks.
 * Provides a type-safe foundation for persistent data entities.
 * <p>
 * This class declares its own {@code id} field, typed {@code ID} rather than {@code Object}.
 * Before 6.3.0 this class extended the now-removed {@code AbstractDataEntity} and reused its
 * {@code Object}-typed {@code id} field instead of declaring one directly, specifically to avoid
 * Gson's "declares multiple JSON fields named 'id'" error, which fires when two classes in the
 * same hierarchy both declare a field mapped to the same JSON key. With {@code
 * AbstractDataEntity} deleted this class is now the only declarer of {@code id} in its own
 * hierarchy, so that collision cannot occur and the extra indirection is no longer needed.
 * <p>
 * 带有泛型 ID 类型和生命周期钩子的增强型抽象数据实体。
 * 为持久化数据实体提供类型安全的基础。
 * <p>
 * 此类直接声明自己的 {@code id} 字段，类型为 {@code ID} 而非 {@code Object}。
 * 6.3.0 之前，本类继承已删除的 {@code AbstractDataEntity} 并复用其 {@code Object} 类型的
 * {@code id} 字段，而不是自己声明——专门用来规避 Gson 的
 * "declares multiple JSON fields named 'id'" 报错（当同一继承链上的两个类都声明了映射到
 * 同一 JSON 键的字段时触发）。{@code AbstractDataEntity} 删除后，本类成为其自身继承链中
 * {@code id} 的唯一声明者，该冲突已不可能发生，这层额外的间接也就不再需要。
 *
 * @param <ID> the type of the entity identifier
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@ToString
@EqualsAndHashCode
// PMD.GenericsNaming wants a single uppercase letter. ID is kept: it is the conventional
// name for an identifier type parameter (cf. Spring Data's Repository<T, ID>), it appears
// in this published type's generic signature, and it is unchanged from 6.2.0 -- the rule
// only re-fired here because GEN-04 removed `extends AbstractDataEntity` from this same line.
@SuppressWarnings("PMD.GenericsNaming")
public abstract class BaseDataEntity<ID extends Serializable> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The entity's identifier, owned directly by this class (see the class javadoc for why this
     * moved here from the now-removed {@code AbstractDataEntity}).
     */
    @Column("id")
    private ID id;

    /**
     * Gets the entity ID.
     *
     * @return the typed entity ID
     */
    public ID getId() {
        return id;
    }

    /**
     * Sets the entity ID.
     *
     * @param id the entity ID to set
     */
    public void setId(ID id) {
        this.id = id;
    }

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
        return getId() == null;
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
