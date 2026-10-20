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
     * integer through {@code CAST(column AS BIGINT)}, a {@code double} field (or its wrapper) as a
     * 64-bit floating-point number through {@code CAST(column AS DOUBLE)}, and a {@code float} field
     * (or its wrapper) through {@code CAST(CAST(CAST(column AS REAL) AS VARCHAR) AS DOUBLE)}, which
     * {@link #orderedComparisonPlaceholder(Class, Object)} applies to the value as well. Every other type is
     * compared as stored.
     * <p>
     * A field left on the default {@code @Column} type is a {@code VARCHAR(255)} column, which SQLite
     * gives TEXT affinity, so without the cast it compares a number as text and {@code 2 > 10} is
     * true (#675). The cast follows the field's Java type, not the column's declared type, so tables
     * that already exist need no change. A cast target whose name contains {@code INT} has INTEGER
     * affinity (64-bit) in SQLite and one containing {@code DOUB} has REAL affinity (64-bit);
     * {@code BIGINT} and {@code DOUBLE} are also 64-bit types on the H2 stand-in, whose
     * {@code INTEGER} is 32-bit and {@code REAL} single precision. Integral and floating fields get
     * separate casts because a single {@code REAL} cast would lose whole numbers beyond 2^53.
     * <p>
     * A {@code float} value is bound as the {@code double} it widens to ({@code 0.1f} as
     * {@code 0.10000000149011612}), and a default column keeps SQLite's 15-significant-digit text of
     * that double ({@code 0.100000001490116}). The text reads back as exactly {@code 0.1f}, but
     * compared with the bound value it is smaller, so {@code LESS 0.1f} would select the row holding
     * {@code 0.1f}. Passing both sides through the same conversion to text compares them the way Java
     * compares the values as they read back: two different finite floats differ well before the 15th
     * significant digit, so the conversion keeps their order and never makes them equal. The
     * conversion is applied to the value only when it is a number whose {@code double} value is finite
     * (see {@link #orderedComparisonPlaceholder(Class, Object)}): SQLite writes an infinite value as
     * the text {@code Inf}, which converts back to 0, and converts text that is not a number to 0. Every
     * other value (an infinity, NaN, a {@code BigDecimal} or {@code BigInteger} beyond the range of a
     * double, or any text) is bound as it is and compared with the converted column, as before the
     * conversion existed. A finite number of another type is converted too, so a whole number or an
     * exact decimal equal to a stored float, such as 2^60 as a {@code Long}, compares as equal to it.
     * One case stays approximate: a number (not only a {@code double}) that agrees with a stored float to 15
     * significant digits but differs after them, such as a whole number of 10^15 or more like
     * {@code (1L << 53) + 1} or a decimal with 16 or more significant digits, compares as equal to it, in a default column and also
     * in a column declared {@code REAL}, where the stored value is exact but the conversion still
     * rounds it to 15 digits. The inner
     * {@code REAL} cast changes nothing on SQLite, whose {@code REAL} is 64-bit; on the H2 stand-in,
     * where {@code REAL} is single precision, it first rounds the stored or bound value to the float
     * it holds. A {@code double} field keeps the plain bound value: the value it reads back is the
     * stored text read as a {@code double}, which is what {@code CAST(column AS DOUBLE)} compares.
     *
     * @since 6.3.0
     */
    @Override
    @ApiStatus.Internal
    protected String orderedComparisonOperand(String column, Class<?> fieldType) {
        if (isIntegral(fieldType)) {
            return "CAST(" + column + " AS BIGINT)";
        }
        if (isFloat(fieldType)) {
            return throughStoredFloatText(column);
        }
        if (isDouble(fieldType)) {
            return "CAST(" + column + " AS DOUBLE)";
        }
        return column;
    }

    /**
     * Passes the value of a {@code GREATER} or {@code LESS} condition on a {@code float} field (or
     * its wrapper) through the same conversion as the column,
     * {@code CAST(CAST(CAST(? AS REAL) AS VARCHAR) AS DOUBLE)}, so both sides keep the same 15
     * significant digits (see {@link #orderedComparisonOperand(String, Class)}), when the value is a
     * {@link Number} whose {@code double} value is finite. Any other value on a {@code float} field,
     * and every value on a field of another type, is bound unchanged: the conversion would turn an
     * infinity, a number beyond the range of a double, or text that is not a number into 0.
     *
     * @since 6.3.0
     */
    @Override
    @ApiStatus.Internal
    protected String orderedComparisonPlaceholder(Class<?> fieldType, Object value) {
        return isFloat(fieldType) && isFiniteNumber(value) ? throughStoredFloatText("?") : "?";
    }

    /** Whether {@code value} is a number whose {@code double} value is finite (not infinite, not NaN). */
    private static boolean isFiniteNumber(Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double asDouble = ((Number) value).doubleValue();
        return !Double.isInfinite(asDouble) && !Double.isNaN(asDouble);
    }

    /** The float conversion both sides share; {@code operand} is a column name or {@code ?}. */
    private static String throughStoredFloatText(String operand) {
        return "CAST(CAST(CAST(" + operand + " AS REAL) AS VARCHAR) AS DOUBLE)";
    }

    private static boolean isIntegral(Class<?> type) {
        return type == byte.class || type == Byte.class || type == short.class || type == Short.class
                || type == int.class || type == Integer.class || type == long.class || type == Long.class;
    }

    private static boolean isFloat(Class<?> type) {
        return type == float.class || type == Float.class;
    }

    private static boolean isDouble(Class<?> type) {
        return type == double.class || type == Double.class;
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
