package com.ultikits.ultitools.interfaces.impl.data;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;

/**
 * The one rule every storage backend applies to the value of a {@link WhereCondition} before it
 * compares anything (#815, #696): a {@code null} value and a non-finite number (an infinite or NaN
 * {@code double} or {@code float}) are refused with a {@link DataAccessException} carrying
 * {@link ErrorCode#DATA_ENTITY_INVALID}.
 * <p>
 * Neither has a comparison meaning the three backends share. Before 6.4.0 SQLite and MySQL bound
 * such a value as it was (a {@code null} {@code EQUAL} matched no row, a {@code null}
 * {@code INCLUDE} matched the text {@code null}, a NaN matched no row), while JSON threw a raw
 * {@code NullPointerException} or Gson's {@code IllegalArgumentException}. Refusing both on every
 * backend with one typed exception makes the caller's mistake visible instead of letting the same
 * call answer differently per backend.
 * <p>
 * The single exception is the documented meaning of a {@code null} value under {@code EQUAL} in
 * {@code DataOperator#updateIf} ({@code IS NULL}, #640). That caller checks it before calling this
 * class and never passes such a condition here.
 *
 * @since 6.4.0
 */
@ApiStatus.Internal
public final class ConditionValues {

    private ConditionValues() {
        // Static rule only; not instantiated.
    }

    /**
     * Refuses a condition whose value cannot be compared on every backend.
     *
     * @param condition a non-empty condition
     * @param store     the table or store name, for the message
     * @throws DataAccessException ({@link ErrorCode#DATA_ENTITY_INVALID}) if the value is
     *                             {@code null}, or a {@code Double} or {@code Float} that is infinite or NaN
     */
    public static void requireComparable(WhereCondition condition, String store) {
        Object value = condition.getValue();
        if (value == null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "The condition on column '" + condition.getColumn() + "' of '" + store + "' has a null value, "
                            + "which no backend can compare (" + condition.getComparison() + "). Pass a value; "
                            + "to require an unset column, use updateIf with a null EQUAL value.");
        }
        if (isNonFinite(value)) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "The condition on column '" + condition.getColumn() + "' of '" + store + "' has the non-finite value "
                            + value + ", which no backend can compare (" + condition.getComparison() + "). "
                            + "Pass a finite number.");
        }
    }

    /**
     * Refuses a non-finite number only; a {@code null} value is left to the caller.
     *
     * @param condition a non-empty condition
     * @param store     the table or store name, for the message
     * @throws DataAccessException ({@link ErrorCode#DATA_ENTITY_INVALID}) if the value is a
     *                             {@code Double} or {@code Float} that is infinite or NaN
     */
    public static void requireFinite(WhereCondition condition, String store) {
        if (condition.getValue() != null) {
            requireComparable(condition, store);
        }
    }

    private static boolean isNonFinite(Object value) {
        if (value instanceof Double) {
            double d = (Double) value;
            return Double.isNaN(d) || Double.isInfinite(d);
        }
        if (value instanceof Float) {
            float f = (Float) value;
            return Float.isNaN(f) || Float.isInfinite(f);
        }
        return false;
    }
}
