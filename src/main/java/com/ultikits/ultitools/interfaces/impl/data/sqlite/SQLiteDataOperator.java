package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import javax.sql.DataSource;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.impl.data.AbstractRelationalDataOperator;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * SQLite implementation of the data operator.
 * <p>
 * Extends the abstract relational operator, providing SQLite-specific
 * table creation without engine specification.
 *
 * @param <T> the entity type
 * @author wisdomme
 * @since 6.0.0
 */
public class SQLiteDataOperator<T extends BaseDataEntity<String>> extends AbstractRelationalDataOperator<T> {

    /**
     * Creates a new SQLite data operator.
     *
     * @param dataSource the SQLite data source
     * @param type the entity class
     */
    public SQLiteDataOperator(DataSource dataSource, Class<T> type) {
        super(dataSource, type);
        // SQLite's DDL accepted a NULL id, and UltiTools-API 6.2.0 inserted rows without one
        // (#546): give them ids now. _rowid_ is SQLite's built-in row identifier.
        backfillNullIds("_rowid_");
    }

    /**
     * Compares {@code GREATER} and {@code LESS} as numbers of the field's Java type: a
     * {@code byte}, {@code short}, {@code int} or {@code long} field (or its wrapper) as a 64-bit
     * integer through {@code CAST(column AS BIGINT)}, a {@code float} or {@code double} field (or its
     * wrapper) as a 64-bit floating-point number through {@code CAST(column AS DOUBLE)}. Every other
     * type is compared as stored.
     * <p>
     * A field left on the default {@code @Column} type is a {@code VARCHAR(255)} column, which SQLite
     * gives TEXT affinity, so without the cast it compares a number as text and {@code 2 > 10} is
     * true (#675). The cast follows the field's Java type, not the column's declared type, so tables
     * that already exist need no change. A cast target whose name contains {@code INT} has INTEGER
     * affinity (64-bit) in SQLite and one containing {@code DOUB} has REAL affinity (64-bit);
     * {@code BIGINT} and {@code DOUBLE} are also 64-bit types on the H2 stand-in, whose
     * {@code INTEGER} is 32-bit and {@code REAL} single precision. Integral and floating fields get
     * separate casts because a single {@code REAL} cast would lose whole numbers beyond 2^53.
     *
     * @since 6.3.0
     */
    @Override
    @ApiStatus.Internal
    protected String orderedComparisonOperand(String column, Class<?> fieldType) {
        if (isIntegral(fieldType)) {
            return "CAST(" + column + " AS BIGINT)";
        }
        if (isFloating(fieldType)) {
            return "CAST(" + column + " AS DOUBLE)";
        }
        return column;
    }

    private static boolean isIntegral(Class<?> type) {
        return type == byte.class || type == Byte.class || type == short.class || type == Short.class
                || type == int.class || type == Integer.class || type == long.class || type == Long.class;
    }

    private static boolean isFloating(Class<?> type) {
        return type == float.class || type == Float.class || type == double.class || type == Double.class;
    }

    @Override
    protected String createTableSqlFromClazz(Class<T> type) {
        Table table = ReflectionUtil.getAnnotation(type, Table.class);
        String tableName = table.value();
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("`(");
        stringBuilder.append(buildColumnDefinitions(type));
        stringBuilder.append("PRIMARY KEY (`id`))");
        return stringBuilder.toString();
    }
}
