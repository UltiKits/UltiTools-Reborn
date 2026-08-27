package com.ultikits.ultitools.abstracts.data;

import com.ultikits.ultitools.annotations.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Data entity with audit fields for tracking creation and modification.
 * Automatically manages audit timestamps and user information.
 * <p>
 * 带有审计字段的数据实体，用于跟踪创建和修改。
 * 自动管理审计时间戳和用户信息。
 *
 * @param <ID> the type of the entity identifier
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AuditableDataEntity<ID extends java.io.Serializable> extends BaseDataEntity<ID> {
    
    private static final long serialVersionUID = 1L;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
    
    @Column("created_by")
    private UUID createdBy;
    
    @Column("updated_by")
    private UUID updatedBy;
    
    /**
     * Sets the current user context for audit purposes.
     * Thread-local storage for the current operation's user.
     */
    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();
    
    /**
     * Sets the current user for audit tracking.
     * Call this before performing data operations.
     * <p>
     * 设置用于审计跟踪的当前用户。
     * 在执行数据操作之前调用此方法。
     *
     * @param userId the current user's UUID
     */
    public static void setCurrentUser(UUID userId) {
        CURRENT_USER.set(userId);
    }

    /**
     * Clears the current user context.
     * Call this after completing data operations.
     * <p>
     * 清除当前用户上下文。
     * 在完成数据操作后调用此方法。
     */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }
    
    /**
     * Gets the current user from the thread-local context.
     * 从线程本地上下文获取当前用户。
     *
     * @return the current user's UUID, or null if not set
     */
    protected static UUID getCurrentUser() {
        return CURRENT_USER.get();
    }
    
    /**
     * Sets {@code createdAt}/{@code updatedAt} to now and, if a {@link #setCurrentUser current
     * user} is set, {@code createdBy}/{@code updatedBy} to it.
     * <p>
     * As of 6.3.0 (02-08), every relational and JSON data operator in this framework actually
     * calls this before an entity is inserted -- previously it was reachable only by calling it
     * directly, which nothing in the framework did, so {@code createdAt}/{@code createdBy} stayed
     * {@code null} on every persisted row regardless of this method's own correctness. A later
     * {@link #onUpdate()} does <strong>not</strong> touch {@code createdAt}/{@code createdBy}, so
     * the values this method writes on insert persist unchanged through every subsequent update.
     * <p>
     * 将 {@code createdAt}/{@code updatedAt} 设为当前时间；若已设置{@link #setCurrentUser 当前用户}，
     * 同时将 {@code createdBy}/{@code updatedBy} 设为该用户。
     * <p>
     * 自 6.3.0（02-08）起，框架内每个关系型和 JSON 数据操作器在插入实体前都会真正调用本方法——
     * 此前只能被直接调用，而框架内没有任何地方会这样做，因此无论本方法自身是否正确，每一行
     * 持久化数据的 {@code createdAt}/{@code createdBy} 都始终为 {@code null}。后续的
     * {@link #onUpdate()} 不会改动 {@code createdAt}/{@code createdBy}，因此本方法在插入时
     * 写入的值会在此后每一次更新中保持不变。
     */
    @Override
    public void onCreate() {
        super.onCreate();
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        UUID currentUser = getCurrentUser();
        if (currentUser != null) {
            this.createdBy = currentUser;
            this.updatedBy = currentUser;
        }
    }

    /**
     * Sets {@code updatedAt} to now and, if a {@link #setCurrentUser current user} is set,
     * {@code updatedBy} to it. Deliberately does not touch {@code createdAt}/{@code createdBy} --
     * see {@link #onCreate()}.
     * <p>
     * As of 6.3.0 (02-08), every relational and JSON data operator in this framework actually
     * calls this before an entity is updated; see {@link #onCreate()}'s note on why that was not
     * previously true.
     * <p>
     * 将 {@code updatedAt} 设为当前时间；若已设置{@link #setCurrentUser 当前用户}，同时将
     * {@code updatedBy} 设为该用户。刻意不改动 {@code createdAt}/{@code createdBy}——见
     * {@link #onCreate()}。
     * <p>
     * 自 6.3.0（02-08）起，框架内每个关系型和 JSON 数据操作器在更新实体前都会真正调用本方法；
     * 此前为何并非如此，见 {@link #onCreate()} 中的说明。
     */
    @Override
    public void onUpdate() {
        super.onUpdate();
        this.updatedAt = LocalDateTime.now();
        
        UUID currentUser = getCurrentUser();
        if (currentUser != null) {
            this.updatedBy = currentUser;
        }
    }
    
    /**
     * Gets the age of this entity since creation.
     * 获取此实体自创建以来的年龄。
     *
     * @return the duration since creation, or null if not persisted
     */
    public java.time.Duration getAge() {
        if (createdAt == null) {
            return null;
        }
        return java.time.Duration.between(createdAt, LocalDateTime.now());
    }
    
    /**
     * Gets the time since the last modification.
     * 获取自上次修改以来的时间。
     *
     * @return the duration since last update, or null if not persisted
     */
    public java.time.Duration getTimeSinceUpdate() {
        if (updatedAt == null) {
            return null;
        }
        return java.time.Duration.between(updatedAt, LocalDateTime.now());
    }
    
    /**
     * Checks if this entity was modified after creation.
     * 检查此实体是否在创建后被修改。
     *
     * @return true if modified after creation
     */
    public boolean wasModified() {
        if (createdAt == null || updatedAt == null) {
            return false;
        }
        return updatedAt.isAfter(createdAt);
    }
}
