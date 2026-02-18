package com.ultikits.ultitools.interfaces;

import java.util.Collection;
import java.util.List;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;

/**
 * Fluent query builder for data entities.
 * Provides a readable DSL for filtering, ordering, and paginating results.
 * <p>
 * 数据实体的流式查询构建器。提供可读的DSL用于过滤、排序和分页。
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
     * <p>开始一个列的查询条件。
     *
     * @param column the column name
     * @return this query for chaining
     */
    Query<T> where(String column);

    /**
     * Add an AND condition on a column.
     * <p>添加一个AND条件。
     *
     * @param column the column name
     * @return this query for chaining
     */
    Query<T> and(String column);

    // === Operators (called after where/and) ===

    /**
     * Equal to.
     * <p>等于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> eq(Object value);

    /**
     * Not equal to.
     * <p>不等于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> ne(Object value);

    /**
     * Greater than.
     * <p>大于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> gt(Object value);

    /**
     * Less than.
     * <p>小于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> lt(Object value);

    /**
     * Greater than or equal to.
     * <p>大于等于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> gte(Object value);

    /**
     * Less than or equal to.
     * <p>小于等于。
     *
     * @param value the value to compare
     * @return this query for chaining
     */
    Query<T> lte(Object value);

    /**
     * SQL LIKE pattern matching.
     * <p>SQL LIKE模式匹配。
     *
     * @param pattern the LIKE pattern (use % for wildcard)
     * @return this query for chaining
     */
    Query<T> like(String pattern);

    /**
     * Value is in the given collection.
     * <p>值在给定集合中。
     *
     * @param values the collection of allowed values
     * @return this query for chaining
     */
    Query<T> in(Collection<?> values);

    // === Ordering ===

    /**
     * Order results by column ascending.
     * <p>按列升序排列结果。
     *
     * @param column the column to order by
     * @return this query for chaining
     */
    Query<T> orderBy(String column);

    /**
     * Order results by column descending.
     * <p>按列降序排列结果。
     *
     * @param column the column to order by
     * @return this query for chaining
     */
    Query<T> orderByDesc(String column);

    // === Pagination ===

    /**
     * Limit the number of results.
     * <p>限制结果数量。
     *
     * @param count the maximum number of results
     * @return this query for chaining
     */
    Query<T> limit(int count);

    /**
     * Skip a number of results.
     * <p>跳过指定数量的结果。
     *
     * @param start the number of results to skip
     * @return this query for chaining
     */
    Query<T> offset(int start);

    // === Terminal operations ===

    /**
     * Execute the query and return all matching results.
     * <p>执行查询并返回所有匹配结果。
     *
     * @return list of matching entities
     */
    List<T> list();

    /**
     * Execute the query and return the first matching result, or null if none.
     * <p>执行查询并返回第一个匹配结果，如果没有则返回null。
     *
     * @return the first matching entity, or null
     */
    T first();

    /**
     * Check if any results match the query.
     * <p>检查是否有匹配查询的结果。
     *
     * @return true if at least one result exists
     */
    boolean exists();

    /**
     * Count the number of matching results.
     * <p>计算匹配结果的数量。
     *
     * @return the count of matching results
     */
    long count();

    /**
     * Delete all matching results and return the count of deleted rows.
     * <p>删除所有匹配结果并返回已删除的行数。
     *
     * @return the count of deleted rows
     */
    int delete();
}
