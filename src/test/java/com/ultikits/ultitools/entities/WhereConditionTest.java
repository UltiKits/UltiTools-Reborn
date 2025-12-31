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
