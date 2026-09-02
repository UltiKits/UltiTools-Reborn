package com.ultikits.ultitools.abstracts.data;

import com.ultikits.ultitools.annotations.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Data entity with audit fields for tracking creation and modification.
 * Automatically manages audit timestamps and user information.
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
     * {@code BaseCommandExecutor} (Phase 5's file; the current-user wrapper was added there in
     * 02-08) calls this once, inside the runnable that actually invokes a command's matched
     * method, when the resolved sender is a {@code Player} -- so a command handler's data
     * operations record that player's UUID in {@code createdBy}/{@code updatedBy}. Nothing else
     * in the framework calls this: a module performing data operations from outside a command
     * handler (a scheduled task, a listener, an external plugin via the External Plugin API) is
     * responsible for setting the context itself, or its writes record {@code null} actors.
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
     * {@code BaseCommandExecutor} calls this in a {@code finally} around the same invocation it
     * wraps with {@link #setCurrentUser(UUID)}, so it runs whether the handler returns normally
     * or throws. It calls this method -- not {@code setCurrentUser(null)} -- specifically because
     * this removes the {@link ThreadLocal} entry entirely rather than leaving a {@code null}
     * mapping behind, which matters on a pooled Bukkit worker thread that gets reused for a later,
     * unrelated command.
     */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }
    
    /**
     * Gets the current user from the thread-local context.
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
