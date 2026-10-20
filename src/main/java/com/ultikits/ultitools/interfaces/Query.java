package com.ultikits.ultitools.interfaces;

import java.util.Collection;
import java.util.List;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;

/**
 * Fluent query builder for data entities.
 * Provides a readable DSL for filtering, ordering, and paginating results.
 *
 * <p>Usage example:
 * <pre>{@code
 * HomeEntity home = plugin.getDataOperator(HomeEntity.class)
 *     .query()
 *     .where("playerId").eq(uuid)
 *     .and("name").eq("base")
 *     .first();
 * }</pre>
 *
 * @param <T> the entity type
 */
public interface Query<T extends BaseDataEntity<String>> {

    // === Conditions ===

    /**
     * Start a where condition on a column.
     *
     * @param column the column name
     * @return this query for chaining
     */
    Query<T> where(String column);

    /**
     * Add an AND condition on a column.
     *
     * @param column the column name
     * @return this query for chaining
     */
    Query<T> and(String column);

    // === Operators (called after where/and) ===

    /**
     * Equal to.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> eq(Object value);

    /**
     * Not equal to.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> ne(Object value);

    /**
     * Greater than.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> gt(Object value);

    /**
     * Less than.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> lt(Object value);

    /**
     * Greater than or equal to.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> gte(Object value);

    /**
     * Less than or equal to.
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> lte(Object value);

    /**
     * SQL LIKE pattern matching.
     *
     * @param pattern the LIKE pattern (use % for wildcard)
     * @return this query for chaining
     */
    Query<T> like(String pattern);

    /**
     * Value is in the given collection.
     *
     * @param values the collection of allowed values
     * @return this query for chaining
     */
    Query<T> in(Collection<?> values);

    // === Ordering ===

    /**
     * Order results by column ascending.
     *
     * @param column the column to order by
     * @return this query for chaining
     */
    Query<T> orderBy(String column);

    /**
     * Order results by column descending.
     *
     * @param column the column to order by
     * @return this query for chaining
     */
    Query<T> orderByDesc(String column);

    // === Pagination ===

    /**
     * Limit the number of results.
     *
     * @param count the maximum number of results
     * @return this query for chaining
     */
    Query<T> limit(int count);

    /**
     * Skip a number of results.
     *
     * @param start the number of results to skip
     * @return this query for chaining
     */
    Query<T> offset(int start);

    // === Terminal operations ===

    /**
     * Execute the query and return all matching results.
     *
     * @return list of matching entities
     */
    List<T> list();

    /**
     * Execute the query and return the first matching result, or null if none.
     *
     * @return the first matching entity, or null
     */
    T first();

    /**
     * Check if any results match the query.
     *
     * @return true if at least one result exists
     */
    boolean exists();

    /**
     * Count the number of matching results.
     *
     * @return the count of matching results
     */
    long count();

    /**
     * Delete all matching results and return the count of deleted rows.
     *
     * @return the count of deleted rows
     */
    int delete();
}
