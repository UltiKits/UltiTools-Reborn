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
     * 需要查询的字段
     */
    private String column;
    /**
     * 需要匹配的值
     */
    private Object value;
    /**
     * 查询的运算符
     */
    @Builder.Default
    private Comparison comparison = Comparison.EQUAL;
}
