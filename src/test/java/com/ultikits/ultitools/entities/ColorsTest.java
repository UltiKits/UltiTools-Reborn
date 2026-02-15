package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorsTest {

    @Test
    void testEnumValues() {
        assertEquals(0, Colors.WHITE.getCode());
        assertEquals(15, Colors.BLACK.getCode());
        assertEquals(14, Colors.RED.getCode());
    }

    @Test
    void testEnumCount() {
        assertEquals(16, Colors.values().length);
    }
}
