package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ComparisonTest {

    @Test
    void testEnumValues() {
        assertNotNull(Comparison.EQUAL);
        assertNotNull(Comparison.GREATER);
        assertNotNull(Comparison.LESS);
        assertNotNull(Comparison.INCLUDE);
        assertNotNull(Comparison.STARTSWITH);
        assertNotNull(Comparison.ENDSWITH);
    }
    
    @Test
    void testValueOf() {
        assertEquals(Comparison.EQUAL, Comparison.valueOf("EQUAL"));
    }
}
