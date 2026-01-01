package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.annotations.Column;
import lombok.Data;

import java.io.Serializable;

/**
 * Abstract class representing a persistent data entity.
 * <p>
 * 持久化数据抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 * @see com.ultikits.ultitools.abstracts.data.BaseDataEntity
 * @deprecated This class uses Object type for ID which is not type-safe.
 *             Use {@link com.ultikits.ultitools.abstracts.data.BaseDataEntity} instead,
 *             which provides generic ID type and lifecycle hooks.
 *             <p>
 *             此类使用 Object 类型作为 ID，这不是类型安全的。
 *             请使用 {@link com.ultikits.ultitools.abstracts.data.BaseDataEntity}，
 *             它提供泛型 ID 类型和生命周期钩子。
 * @since 6.2.0 deprecated
 */
@Data
@Deprecated(since = "6.2.0", forRemoval = true)
public abstract class AbstractDataEntity implements Serializable {
    @Column("id")
    private Object id;
}
