package com.ultikits.ultitools.entities;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 条件类
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@Builder
public class WhereCondition {
    /**
     * 是否为空
     */
    private boolean empty;
    /**
     * 需要查询的字段
     */
    private String column;
    /**
     * 需要匹配的值
     */
    private Object value;
    /**
     * 查询的运算符
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
