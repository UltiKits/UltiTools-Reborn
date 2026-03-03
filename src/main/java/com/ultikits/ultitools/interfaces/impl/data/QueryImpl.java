package com.ultikits.ultitools.interfaces.impl.data;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Default implementation of {@link Query} that wraps a {@link DataOperator}.
 * <p>
 * Equality conditions are delegated to the underlying DataOperator's getAll() method.
 * Non-equality conditions (ne, gt, lt, gte, lte, like, in) are applied as in-memory filters.
 * Ordering, limit, and offset are applied as in-memory post-processing.
 * <p>
 * Query接口的默认实现，包装了DataOperator。
 *
 * @param <T> the entity type
 */
public class QueryImpl<T extends BaseDataEntity<String>> implements Query<T> {

    private final DataOperator<T> operator;

    // Equality conditions delegated to DataOperator.getAll()
    private final List<WhereCondition> eqConditions = new ArrayList<>();

    // In-memory filter predicates for non-equality operators
    private final List<Predicate<T>> filters = new ArrayList<>();

    // Current column being set up (after where/and)
    private String currentColumn;

    // Ordering
    private String orderByColumn;
    private boolean orderDesc;

    // Pagination
    private int limitCount = -1;
    private int offsetStart = 0;

    public QueryImpl(DataOperator<T> operator) {
        this.operator = operator;
    }

    // === Conditions ===

    @Override
    public Query<T> where(String column) {
        this.currentColumn = column;
        return this;
    }

    @Override
    public Query<T> and(String column) {
        this.currentColumn = column;
        return this;
    }

    // === Operators ===

    @Override
    public Query<T> eq(Object value) {
        eqConditions.add(WhereCondition.builder()
                .column(currentColumn)
                .value(value)
                .build());
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> ne(Object value) {
        final String col = currentColumn;
        filters.add(entity -> {
            Object fieldValue = getFieldValue(entity, col);
            return fieldValue != null && !fieldValue.equals(value);
        });
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> gt(Object value) {
        final String col = currentColumn;
        filters.add(entity -> compareField(entity, col, value) > 0);
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> lt(Object value) {
        final String col = currentColumn;
        filters.add(entity -> compareField(entity, col, value) < 0);
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> gte(Object value) {
        final String col = currentColumn;
        filters.add(entity -> compareField(entity, col, value) >= 0);
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> lte(Object value) {
        final String col = currentColumn;
        filters.add(entity -> compareField(entity, col, value) <= 0);
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> like(String pattern) {
        final String col = currentColumn;
        // Convert SQL LIKE pattern to regex
        final String regex = "(?i)" + pattern
                .replace("%", ".*")
                .replace("_", ".");
        filters.add(entity -> {
            Object fieldValue = getFieldValue(entity, col);
            return fieldValue != null && fieldValue.toString().matches(regex);
        });
        currentColumn = null;
        return this;
    }

    @Override
    public Query<T> in(Collection<?> values) {
        final String col = currentColumn;
        filters.add(entity -> {
            Object fieldValue = getFieldValue(entity, col);
            return fieldValue != null && values.contains(fieldValue);
        });
        currentColumn = null;
        return this;
    }

    // === Ordering ===

    @Override
    public Query<T> orderBy(String column) {
        this.orderByColumn = column;
        this.orderDesc = false;
        return this;
    }

    @Override
    public Query<T> orderByDesc(String column) {
        this.orderByColumn = column;
        this.orderDesc = true;
        return this;
    }

    // === Pagination ===

    @Override
    public Query<T> limit(int count) {
        this.limitCount = count;
        return this;
    }

    @Override
    public Query<T> offset(int start) {
        this.offsetStart = start;
        return this;
    }

    // === Terminal Operations ===

    @Override
    public List<T> list() {
        // Step 1: Fetch using equality conditions via DataOperator
        List<T> results;
        if (eqConditions.isEmpty()) {
            results = new ArrayList<>(operator.getAll());
        } else {
            results = new ArrayList<>(operator.getAll(
                    eqConditions.toArray(new WhereCondition[0])));
        }

        // Step 2: Apply in-memory filters
        if (!filters.isEmpty()) {
            Iterator<T> it = results.iterator();
            while (it.hasNext()) {
                T entity = it.next();
                for (Predicate<T> filter : filters) {
                    if (!filter.test(entity)) {
                        it.remove();
                        break;
                    }
                }
            }
        }

        // Step 3: Apply ordering
        if (orderByColumn != null) {
            final String sortCol = orderByColumn;
            results.sort((a, b) -> {
                int cmp = compareField(a, sortCol, getFieldValue(b, sortCol));
                // If a's value equals b's value, cmp = 0; if a > b, cmp > 0
                // But compareField compares entity's field against a raw value
                // We need direct comparison between two entities
                Object va = getFieldValue(a, sortCol);
                Object vb = getFieldValue(b, sortCol);
                int result = compareValues(va, vb);
                return orderDesc ? -result : result;
            });
        }

        // Step 4: Apply offset
        if (offsetStart > 0 && offsetStart < results.size()) {
            results = new ArrayList<>(results.subList(offsetStart, results.size()));
        } else if (offsetStart >= results.size()) {
            return Collections.emptyList();
        }

        // Step 5: Apply limit
        if (limitCount >= 0 && limitCount < results.size()) {
            results = new ArrayList<>(results.subList(0, limitCount));
        }

        return results;
    }

    @Override
    public T first() {
        // Optimize: use limit(1) to avoid loading all results
        this.limitCount = (this.limitCount < 0) ? 1 : Math.min(this.limitCount, 1);
        List<T> results = list();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public boolean exists() {
        if (eqConditions.isEmpty() && filters.isEmpty()) {
            return !operator.getAll().isEmpty();
        }
        if (filters.isEmpty()) {
            return operator.exist(eqConditions.toArray(new WhereCondition[0]));
        }
        return first() != null;
    }

    @Override
    public long count() {
        return list().size();
    }

    @Override
    public int delete() {
        List<T> toDelete = list();
        for (T entity : toDelete) {
            if (entity.getId() != null) {
                operator.delById(entity.getId());
            }
        }
        return toDelete.size();
    }

    // === Internal Helpers ===

    /**
     * Get a field value from an entity by column name.
     * Maps column name (snake_case) to Java field via @Column annotation.
     */
    private Object getFieldValue(T entity, String columnName) {
        for (Field field : ReflectionUtil.getFields(entity.getClass())) {
            Column col = field.getAnnotation(Column.class);
            if (col != null && col.value().equals(columnName)) {
                field.setAccessible(true);
                try {
                    return field.get(entity);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
            // Also match by field name
            if (field.getName().equals(columnName)) {
                field.setAccessible(true);
                try {
                    return field.get(entity);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        // Check for "id" field on parent
        if ("id".equals(columnName)) {
            return entity.getId();
        }
        return null;
    }

    /**
     * Compare an entity's field value against a reference value.
     * Returns negative, zero, or positive.
     */
    @SuppressWarnings("unchecked")
    private int compareField(T entity, String columnName, Object refValue) {
        Object fieldValue = getFieldValue(entity, columnName);
        return compareValues(fieldValue, refValue);
    }

    /**
     * Compare two values. Handles Comparable types and null safety.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        // Both are Comparable
        if (a instanceof Comparable && b instanceof Comparable) {
            // Handle numeric type mismatches (int vs long, etc.)
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            try {
                return ((Comparable) a).compareTo(b);
            } catch (ClassCastException e) {
                return a.toString().compareTo(b.toString());
            }
        }

        return a.toString().compareTo(b.toString());
    }

    /**
     * Simple functional interface for in-memory filtering (Java 8 compatible).
     */
    @FunctionalInterface
    private interface Predicate<T> {
        boolean test(T entity);
    }
}
