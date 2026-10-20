package com.ultikits.ultitools.entities;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WhereConditionTest {

    @Test
    void testBuilder() {
        WhereCondition condition = WhereCondition.builder()
                .column("name")
                .value("test")
                .comparison(Comparison.EQUAL)
                .build();

        assertEquals("name", condition.getColumn());
        assertEquals("test", condition.getValue());
        assertEquals(Comparison.EQUAL, condition.getComparison());
        assertFalse(condition.isEmpty());
    }

    @Test
    void testEmpty() {
        WhereCondition condition = WhereCondition.empty();
        assertTrue(condition.isEmpty());
    }

    @Test
    void testComparisonDefaultsToEqualWhenUnspecified() {
        // WIRE-04: every WhereCondition must carry a non-null Comparison so relational
        // builders never have to null-check it. Pins WhereCondition.comparison's
        // @Builder.Default rather than assuming it stays that way.
        WhereCondition condition = WhereCondition.builder().column("name").value("test").build();
        assertEquals(Comparison.EQUAL, condition.getComparison());
    }

    @Test
    void testSetters() {
        WhereCondition condition = WhereCondition.builder().build();
        condition.setColumn("age");
        condition.setValue(18);
        condition.setComparison(Comparison.GREATER);
        condition.setEmpty(false);

        assertEquals("age", condition.getColumn());
        assertEquals(18, condition.getValue());
        assertEquals(Comparison.GREATER, condition.getComparison());
        assertFalse(condition.isEmpty());
    }
}
