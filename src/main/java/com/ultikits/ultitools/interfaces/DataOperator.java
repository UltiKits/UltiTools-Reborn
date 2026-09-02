package com.ultikits.ultitools.interfaces;

import java.util.List;
import java.util.concurrent.Callable;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;
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
