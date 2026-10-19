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
     * field, or its wrapper, the two are compared as numbers of that type. Pass the value as a number
     * of that type (for example {@code 10}, not {@code "10"}): a text value is not converted, and
     * MySQL compares it with a default text column as text, so {@code "10"} sorts below {@code "2"}.
     * On SQLite the comparison holds also in a column left on the default {@code VARCHAR(255)} type,
     * with 64-bit integer precision, and compares a stored value the way it reads back: a
     * {@code float} such as {@code 0.1f} is neither greater nor less than the same value, and a
     * {@code double} that needs 16 or 17 significant digits is stored and compared with 15. That holds
     * for a value that is a finite number; an infinite value, a number beyond the range of a
     * {@code double} and text are compared as bound, and a NaN value matches no row. On MySQL
     * such a default text column is compared the way MySQL compares text with a number, in double
     * precision, so whole numbers beyond plus or minus 9,007,199,254,740,992 (2^53) compare
     * approximately; declare the column with a numeric {@code type} (for example {@code BIGINT}) for
     * exact results there. On JSON the values are compared as {@code double}, also approximate beyond
     * plus or minus 9,007,199,254,740,992, and an infinite or NaN value throws
     * {@code IllegalArgumentException}, because it cannot be written as JSON. A field of any other
     * type is compared as the database compares the stored text.
     */
    GREATER,
    /**
     * The stored value is less than the value. It follows the same rules as {@link #GREATER}: typed
     * numeric comparison on numeric fields, for a value passed as a number of the field's type (on
     * SQLite also in default text columns, with 64-bit integer precision and as the stored value reads
     * back; on MySQL default text columns and on JSON in double precision, approximate beyond plus or
     * minus 9,007,199,254,740,992), and the database's text comparison otherwise.
     */
    LESS,
    /**
     * The stored text contains the value.
     * <p>
     * The value is matched literally as text, on SQLite, MySQL and JSON alike: percent, underscore,
     * exclamation mark and backslash are ordinary characters, not wildcards or escapes. Letter case and
     * accents follow the backend: SQLite ignores the case of ASCII letters only; MySQL compares under
     * the {@code utf8mb3_general_ci} collation of the tables the framework creates, which ignores case
     * and accents, so {@code e} matches <code>&eacute;</code>; JSON compares exactly.
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
