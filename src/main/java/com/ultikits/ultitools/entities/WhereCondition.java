package com.ultikits.ultitools.entities;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Condition class.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@Builder
public class WhereCondition {
    /**
     * Whether it is empty.
     */
    private boolean empty;
    /**
     * The field to query.
     */
    private String column;
    /**
     * The value to match.
     */
    private Object value;
    /**
     * The query operator.
     * <p>
     * Always non-null: {@code @Builder.Default} guarantees {@link Comparison#EQUAL} when the
     * builder does not set it explicitly, so no relational WHERE builder has to null-check this
     * field before mapping it to a SQL operator.
     */
    @Builder.Default
    private Comparison comparison = Comparison.EQUAL;

    public static WhereCondition empty() {
        return WhereCondition.builder().empty(true).build();
    }
}
