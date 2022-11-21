package com.ultikits.ultitools.entities;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class WhereCondition {
    private String column;
    private Object value;
}
