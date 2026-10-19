package com.ultikits.ultitools.entities;

/**
 * How a {@link WhereCondition} compares a stored column with its value.
 * <p>
 * The comparison means the same in {@code getAll}, {@code exist}, {@code page}, {@code del} and
 * {@code updateIf} of the SQLite, MySQL and JSON data operators, and no table is changed to make it
 * hold. {@code DataOperator#getLike} and {@code Query#like} are separate pattern operators and do not
 * use this enum.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public enum Comparison {
    /**
     * The stored value equals the value. Its meaning is unchanged; in {@code DataOperator#updateIf}
     * a {@code null} value means the column is unset ({@code IS NULL}).
     */
    EQUAL,
    /**
     * The stored value is greater than the value.
     * <p>
     * On a {@code byte}, {@code short}, {@code int}, {@code long}, {@code float} or {@code double}
     * field, or its wrapper, the two are compared as numbers of that type. On SQLite this holds also
     * in a column left on the default {@code VARCHAR(255)} type, with 64-bit integer precision. On
     * MySQL such a default text column is compared the way MySQL compares text with a number, in
     * double precision, so whole numbers beyond plus or minus 9,007,199,254,740,992 (2^53) compare
     * approximately; declare the column with a numeric {@code type} (for example {@code BIGINT}) for
     * exact results there. On JSON the values are compared as {@code double}, also approximate beyond
     * plus or minus 9,007,199,254,740,992. A field of any other type is compared as the database
     * compares the stored text.
     */
    GREATER,
    /**
     * The stored value is less than the value. It follows the same rules as {@link #GREATER}: typed
     * numeric comparison on numeric fields (on SQLite also in default text columns, with 64-bit
     * integer precision; on MySQL default text columns and on JSON in double precision, approximate
     * beyond plus or minus 9,007,199,254,740,992), and the database's text comparison otherwise.
     */
    LESS,
    /**
     * The stored text contains the value.
     * <p>
     * The value is matched literally as text, on SQLite, MySQL and JSON alike: every character,
     * including percent, underscore, exclamation mark and backslash, stands for itself. Letter case
     * follows the backend: case-insensitive on SQLite and on MySQL's default collation, case-sensitive
     * on JSON.
     */
    INCLUDE,
    /**
     * The stored text starts with the value, matched literally as text with the same rules as
     * {@link #INCLUDE}.
     */
    STARTSWITH,
    /**
     * The stored text ends with the value, matched literally as text with the same rules as
     * {@link #INCLUDE}.
     */
    ENDSWITH
}
