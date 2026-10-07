package com.ultikits.ultitools.interfaces;

import java.util.List;
import java.util.concurrent.Callable;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.impl.data.QueryImpl;

/**
 * Data operation interface.
 *
 * @param <T> Data type inherited from BaseDataEntity
 */
public interface DataOperator<T extends BaseDataEntity<String>> {

    enum LikeType {
        START, END, CONTAINS
    }

    /**
     * Check if the data record exists.
     *
     * @param object Data record entity
     * @return Whether the record exists
     */
    boolean exist(T object);

    /**
     * Check if the data record exists.
     *
     * @param whereConditions Conditions
     * @return Whether the record exists
     */
    boolean exist(WhereCondition... whereConditions);

    /**
     * Get data record by ID.
     *
     * @param id Record ID
     * @return Data record
     */
    T getById(Object id);

    /**
     * Get all data record.
     *
     * @return Data record list
     */
    List<T> getAll();

    /**
     * Get all data record by conditions.
     *
     * @param whereConditions Conditions
     * @return Data record list
     */
    List<T> getAll(WhereCondition... whereConditions);

    /**
     * Fuzzy Query
     *
     * @param column   Column name
     * @param value    Query value
     * @param likeType Like type
     * @return Data record list
     */
    List<T> getLike(String column, String value, LikeType likeType);

    /**
     * Get data record by page.
     *
     * @param page            Page number
     * @param size            Page size
     * @param whereConditions Conditions
     * @return Data record list
     */
    List<T> page(int page, int size, WhereCondition... whereConditions);

    /**
     * Insert data record.
     *
     * @param obj Data record
     */
    void insert(T obj);

    /**
     * Delete data record by conditions.
     * <p>
     * Since 6.3.0, a call with no conditions (a {@code null} or zero-length
     * {@code whereConditions}) is rejected with a {@code DataAccessException} instead of
     * deleting every row of the table — this is a behavioral change with no migration period
     * (COMPATIBILITY.md's security-fix channel); see 02-CONTEXT.md D-12.
     *
     * @param whereConditions Conditions
     */
    void del(WhereCondition... whereConditions);

    /**
     * Delete data record by ID.
     *
     * @param id Record ID
     */
    void delById(Object id);

    /**
     * Update one field of one record.
     *
     * @param column Column name
     * @param value  New value
     * @param id     Record ID
     */
    void update(String column, Object value, Object id);

    /**
     * Update data record by object. It will not update the fields that the incoming entity does not have.
     *
     * @param obj Data record
     * @throws IllegalAccessException Please refer{@link IllegalAccessException}
     */
    void update(T obj) throws IllegalAccessException;

    /**
     * Counted update: {@link #update(BaseDataEntity)}, returning how many rows it wrote, so the
     * caller can tell that nothing was written (#558).
     * <p>
     * An update by a non-null id that matches no row writes nothing on every backend: SQLite,
     * MySQL and JSON each log one WARNING naming the table and the id, and return normally.
     * {@code update(T)} gives the caller no way to see that; this method returns {@code 0}
     * instead of {@code 1}. Use it wherever "the row was gone" must be treated as a failure --
     * for example a write that credits an account that another server has just removed. For a
     * write that should apply only while the stored row still has the values you read, use
     * {@link #updateIf} instead.
     * <p>
     * The framework's operators return the backend's own affected-row count. This default, which
     * a third-party implementation inherits unless it overrides it, checks with
     * {@link #exist(WhereCondition...)} whether a row with the entity's id exists, returns
     * {@code 0} without writing if it does not, and otherwise calls {@code update(T)} and returns
     * {@code 1}. It cannot see what that {@code update} wrote, so one case remains in which it
     * reports {@code 1} although nothing was written: another writer deleting the row during that
     * one call.
     *
     * @param entity the new state of the row, carrying the id of the row to write
     * @return the number of rows written: {@code 1}, or {@code 0} when no row has that id
     * @throws DataAccessException if {@code entity}'s id is {@code null}, or the write fails
     * @since 6.3.0
     */
    default int updateCounted(T entity) {
        Object id = entity.getId();
        if (id == null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to update a " + entity.getClass().getName() + " by a null id: no row can be addressed by it.");
        }
        if (!exist(WhereCondition.builder().column("id").value(id).build())) {
            return 0;
        }
        try {
            update(entity);
        } catch (IllegalAccessException e) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID, "Failed to access entity fields", e);
        }
        return 1;
    }

    /**
     * Conditional update: writes {@code entity} over the stored row with the same id only if
     * that row still matches every one of {@code expected}, and reports whether it did (#543).
     * <p>
     * The check and the write are one step: on SQLite and MySQL a single
     * {@code UPDATE ... WHERE id = ? AND <expected>} whose affected-row count decides the result,
     * so it holds across servers sharing one database; on the JSON backend the check and the write
     * run under the operator's own lock (a JSON store is local to one server). The conditions mean
     * exactly what they mean in {@link #getAll(WhereCondition...)} on the same backend, values are
     * bound as parameters, and a condition whose {@code isEmpty()} is true is ignored.
     * <p>
     * Typical use is compare-and-set: read a row, compute the new state, and write it conditioned
     * on the value that was read; if another writer changed the row in between, nothing is
     * written and {@code false} comes back, so the caller re-reads and decides again rather than
     * writing on top. UltiEconomy's one-time wallet merge uses it this way, conditioned on the
     * balance it read:
     * <pre>{@code
     * Account read = accounts.getById(id);
     * double seen = read.getBalance();
     * read.setBalance(seen + amount);
     * if (!accounts.updateIf(read, WhereCondition.builder().column("balance").value(seen).build())) {
     *     // someone else wrote first: re-read and decide again
     * }
     * }</pre>
     * Like {@link #update(BaseDataEntity)}, every mapped field of {@code entity} is written, and
     * {@code onUpdate()} fires on {@code entity} before the fields are read, whether or not the
     * write then applies.
     * <p>
     * <b>A {@code null} expected value means {@code IS NULL}</b> under the default
     * {@link com.ultikits.ultitools.entities.Comparison#EQUAL} comparison: the condition holds only
     * while the stored column is unset -- on SQLite and MySQL {@code <column> IS NULL} inside the
     * same single statement, on JSON an entry whose field is absent or JSON null. A compare-and-set
     * against a column that was unset when it was read therefore misses, and keeps the value, when
     * another writer filled the column in between. This meaning belongs to {@code updateIf} alone:
     * {@link #getAll(WhereCondition...)}, {@link #exist(WhereCondition...)},
     * {@link #del(WhereCondition...)} and {@link #page(int, int, WhereCondition...)} treat a
     * {@code null} condition value as they always have. (6.3.0 snapshots published before this
     * change refused a {@code null} expected value; no release did.)
     *
     * @param entity   the new state of the row, carrying the id of the row to write
     * @param expected the conditions the stored row must still meet
     * @return {@code true} if the row matched and was written; {@code false} if no row with that
     *         id matched every condition, in which case nothing was written
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code entity}'s id is
     *         {@code null}, a condition is {@code null}, a condition names a column the entity does
     *         not map with {@code @Column} (also when its value is {@code null}), or a condition's
     *         value is {@code null} under a comparison other than {@code EQUAL} (only {@code EQUAL}
     *         gives {@code null} a meaning; under any other the write could never apply) -- on every
     *         backend, so a misspelt column cannot turn a retry loop into an endless one
     * @throws UnsupportedOperationException if this implementation does not provide conditional
     *         writes -- the default, so a third-party implementation is never silently
     *         unconditional
     * @since 6.3.0
     */
    default boolean updateIf(T entity, WhereCondition... expected) {
        throw new UnsupportedOperationException(getClass().getName()
                + " does not implement DataOperator#updateIf, so it cannot perform a conditional write.");
    }

    /**
     * Returns a new fluent query builder for this data operator.
     *
     * @return a new Query builder
     */
    default Query<T> query() {
        return new QueryImpl<>(this);
    }

    /**
     * Execute operations within a transaction. All operations commit together
     * or roll back on exception. For SQL backends, uses database transactions.
     * For JSON, uses snapshot-based rollback.
     *
     * @param action the operations to execute
     * @param <R> the return type
     * @return the result of the action
     * @throws Exception if the action fails
     */
    default <R> R transaction(Callable<R> action) throws Exception {
        return action.call();
    }

    /**
     * Execute operations within a transaction (void variant).
     *
     * @param action the operations to execute
     */
    default void transaction(Runnable action) {
        action.run();
    }

    /**
     * Insert multiple entities atomically. Uses JDBC batch for SQL backends.
     *
     * @param entities the entities to insert
     */
    default void insertAll(List<T> entities) {
        transaction(() -> {
            for (T entity : entities) {
                insert(entity);
            }
        });
    }

    /**
     * Update multiple entities atomically. Uses JDBC batch for SQL backends.
     *
     * @param entities the entities to update
     * @throws IllegalAccessException if field access fails
     */
    default void updateAll(List<T> entities) throws IllegalAccessException {
        for (T entity : entities) {
            update(entity);
        }
    }
}
