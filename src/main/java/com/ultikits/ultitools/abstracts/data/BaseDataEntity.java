package com.ultikits.ultitools.abstracts.data;

import java.io.Serializable;

import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

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
     */
    public void onCreate() {
        // Override in subclass
    }
    
    /**
     * Called before the entity is updated.
     * Override to add custom pre-update logic.
     */
    public void onUpdate() {
        // Override in subclass
    }
    
    /**
     * Called before the entity is deleted.
     * Override to add custom pre-delete logic.
     */
    public void onDelete() {
        // Override in subclass
    }
    
    /**
     * Called after the entity is loaded from the data store.
     * Override to add custom post-load logic.
     */
    public void onLoad() {
        // Override in subclass
    }
    
    /**
     * Validates the entity before persistence operations.
     * Override to add custom validation logic.
     *
     * @return true if the entity is valid, false otherwise
     */
    public boolean validate() {
        return true;
    }
    
    /**
     * Gets validation error messages if validation fails.
     * Override along with {@link #validate()} to provide error messages.
     *
     * @return list of validation error messages, empty if valid
     */
    public java.util.List<String> getValidationErrors() {
        return java.util.Collections.emptyList();
    }
    
    /**
     * Checks if this entity is new (not yet persisted).
     *
     * @return true if the entity has no ID, false otherwise
     */
    public boolean isNew() {
        return getId() == null;
    }
    
    /**
     * Creates a copy of this entity without the ID.
     * Useful for creating new entities based on existing ones.
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
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- copying an
            // entity is a data operation, same category as SimpleJsonDataOperator's batch/
            // transaction failures.
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED, "Failed to copy entity", e);
        }
    }
    
    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
