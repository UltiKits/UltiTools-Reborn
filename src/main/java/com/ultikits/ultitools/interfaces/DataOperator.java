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
     * Pattern query on one column: finds rows whose value at {@code column} starts with
     * ({@link LikeType#START}), ends with ({@link LikeType#END}) or contains
     * ({@link LikeType#CONTAINS}) {@code value}.
     * <p>
     * The built-in backends interpret the arguments differently. This describes their current
     * behaviour, not a cross-backend contract.
     * <ul>
     *   <li>SQLite and MySQL bind {@code value%}, {@code %value} or {@code %value%} to SQL
     *       {@code LIKE} without an {@code ESCAPE} clause, so {@code %} and {@code _} in {@code value}
     *       act as wildcards under the database's rules, and letter case follows the database.
     *       {@code column} is put into the SQL as given, without checking the entity's
     *       {@code @Column} mappings: pass only a column name fixed by your code, never user input.
     *       A name the database rejects throws {@link DataAccessException} with
     *       {@link ErrorCode#DATA_OPERATION_FAILED}.</li>
     *   <li>JSON resolves a mapped column name or a dot-separated field path, turns each non-null
     *       value into text with {@code toString()} after a Gson round trip (an integer {@code 15}
     *       is matched as {@code 15.0}), skips rows without a value there, and compares with
     *       {@link String#startsWith(String)}, {@link String#endsWith(String)} or
     *       {@link String#contains(CharSequence)}. Matching is case-sensitive, and {@code %} and
     *       {@code _} are ordinary characters.</li>
     * </ul>
     * Do not pass {@code null} for {@code value} or {@code likeType}: SQL turns a {@code null} value
     * into the text {@code null}, and a {@code null} {@code likeType} throws {@link NullPointerException}
     * on SQL. JSON throws it for either null argument once a row has a value at {@code column}.
     * For a literal prefix, suffix or substring match, use a {@link WhereCondition} with
     * {@link com.ultikits.ultitools.entities.Comparison#STARTSWITH},
     * {@link com.ultikits.ultitools.entities.Comparison#ENDSWITH} or
     * {@link com.ultikits.ultitools.entities.Comparison#INCLUDE}.
     *
     * @param column   the column name, or on JSON a mapped column name or field path
     * @param value    the text to match
     * @param likeType where {@code value} must occur
     * @return detached copies of the matching rows
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
     * (COMPATIBILITY.md's security-fix channel).
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
     * {@link #del(WhereCondition...)} and {@link #page(int, int, WhereCondition...)} refuse a
     * {@code null} condition value with a {@code DataAccessException} as of 6.4.0, as they refuse an
     * infinite or NaN {@code Double} or {@code Float} value, which {@code updateIf} refuses too. (6.3.0 snapshots published before this
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
     *         gives {@code null} a meaning; under any other the write could never apply) or is an
     *         infinite or NaN {@code Double} or {@code Float} (as of 6.4.0) -- on every
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
     * or roll back on exception.
     * <p>
     * Every backend this framework ships (SQLite, MySQL, JSON) runs the action in a
     * transaction: it joins the transaction already active on the calling thread, if any,
     * and any {@link Throwable} the action throws rolls it back and is rethrown. SQLite and
     * MySQL use a database transaction. On JSON, the action's writes are staged until commit
     * and are invisible to other threads until then; the commit applies them together, so
     * other threads see all of them at once; a rollback discards them.
     * <p>
     * On JSON, a transaction this method begins locks each table at its first read or write and
     * holds the lock until it commits or rolls back, so a read-modify-write inside the action
     * cannot lose an update, as in 6.2.5, where each JSON {@code transaction(...)} callback ran
     * alone per operator. A read inside the action can therefore wait for another transaction
     * that holds the table: at most 5 seconds, then it fails with a
     * {@link com.ultikits.ultitools.exceptions.DataAccessException} and the transaction rolls
     * back. A call that joins an active transaction keeps that transaction's isolation: inside a
     * {@code @Transactional} method at the default isolation, JSON reads take no table lock.
     * <p>
     * When the commit itself fails: on SQLite and MySQL, the exception does not tell whether the
     * database stored the transaction (the outcome is unknown; see
     * {@link TransactionManager#commit()}), and nothing is retried, so code that must not apply a
     * write twice re-reads the data, or uses {@link #updateIf}, before trying again. On JSON a
     * failed commit commits nothing. An
     * {@link com.ultikits.ultitools.exceptions.UnexpectedRollbackException} from the commit means
     * the transaction was rolled back, none of its writes stored, on every backend.
     * <p>
     * This default implementation, used only by a third-party operator that does not override
     * it, runs the action directly without a transaction.
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
     * Execute operations within a transaction (void variant), with the same guarantees as
     * {@link #transaction(Callable)} on every shipped backend, including the JSON backend's table
     * lock at the first read of a transaction this method begins. The same commit outcomes apply:
     * see {@link #transaction(Callable)}.
     * <p>
     * This default implementation, used only by a third-party operator that does not override
     * it, runs the action directly without a transaction.
     *
     * @param action the operations to execute
     */
    default void transaction(Runnable action) {
        action.run();
    }

    /**
     * Insert multiple entities atomically: on every shipped backend either all of them are
     * stored or, if one fails, none is. Uses JDBC batch for SQL backends; on JSON the batch is
     * one transaction. Inside an active transaction the batch joins it.
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
     * Update multiple entities atomically: on every shipped backend either all of them are
     * updated or, if one fails, none is. Uses JDBC batch for SQL backends; on JSON the batch is
     * one transaction. Inside an active transaction the batch joins it.
     * <p>
     * This default implementation, used only by a third-party operator that does not override
     * it, updates the entities one by one without a transaction.
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
