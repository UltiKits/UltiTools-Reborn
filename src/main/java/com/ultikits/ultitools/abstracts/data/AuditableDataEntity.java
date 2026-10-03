package com.ultikits.ultitools.abstracts.data;

import com.ultikits.ultitools.annotations.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.ApiStatus;

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
     * {@code BaseCommandExecutor} sets the command sender's UUID around the body that actually
     * invokes a command's matched method, when the resolved sender is a {@code Player} -- through
     * {@link #swapCurrentUser(UUID)} as of 6.3.0, which also restores the previous user
     * afterwards -- so a command handler's data operations record that player's UUID in
     * {@code createdBy}/{@code updatedBy}. Nothing else in the framework sets it: a module performing data operations from outside a command
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
     * This removes the {@link ThreadLocal} entry entirely rather than leaving a {@code null}
     * mapping behind, which matters on a pooled Bukkit worker thread that gets reused for a later,
     * unrelated command. {@code BaseCommandExecutor} no longer calls it after a command body (as of
     * 6.3.0, #541): it restores the user that was current before the body through
     * {@link #swapCurrentUser(UUID)}, which removes the entry the same way when there was none.
     */
    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    /**
     * Replaces the current user and returns the one it replaces, so a caller can put it back.
     * <p>
     * {@code BaseCommandExecutor} calls this around every command body (as of 6.3.0, #541): once
     * with the sender's UUID (or {@code null} for a sender that is not a player) before the body,
     * and once with the returned value in a {@code finally} after it. A command body runs at
     * dispatch on the server thread, so a body that dispatches another command runs the nested
     * body on the same thread before its own has finished; saving and restoring keeps each body's
     * own user, and a thread that carried no user before the outermost command carries none after
     * it. A {@code null} argument removes the {@link ThreadLocal} entry, as
     * {@link #clearCurrentUser()} does, rather than storing {@code null}.
     *
     * @param userId the user to make current, or {@code null} for none
     * @return the user that was current before, or {@code null} if there was none
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static UUID swapCurrentUser(UUID userId) {
        UUID previous = CURRENT_USER.get();
        if (userId == null) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(userId);
        }
        return previous;
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
