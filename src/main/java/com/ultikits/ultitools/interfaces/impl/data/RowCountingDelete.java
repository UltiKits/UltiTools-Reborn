package com.ultikits.ultitools.interfaces.impl.data;

import org.jetbrains.annotations.ApiStatus;

/**
 * A data operator that can report how many rows a delete by id actually removed (#521).
 * <p>
 * {@code DataOperator#delById} returns {@code void}, and its signature is part of the published
 * API, so the affected-row count travels through this internal side interface instead.
 * {@link QueryImpl#delete()} uses it to return the number of rows the backend removed rather than
 * the number the query matched. The framework's own operators implement it; a third-party
 * {@code DataOperator} that does not is counted by checking, after its {@code delById}, whether the
 * row is still there.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public interface RowCountingDelete {

    /**
     * Deletes the row with this id, exactly as {@code DataOperator#delById} does, and returns the
     * number of rows the backend removed.
     *
     * @param id the row id, not {@code null}
     * @return the number of rows removed: {@code 0} when no row had this id (for example because
     *         another writer removed it first), otherwise {@code 1}
     */
    int deleteByIdCounted(Object id);
}
