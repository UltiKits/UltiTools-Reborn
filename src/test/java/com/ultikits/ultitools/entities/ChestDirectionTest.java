package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ChestDirectionTest {

    @Test
    void testEnumValues() {
        assertNotNull(ChestDirection.LEFT);
        assertNotNull(ChestDirection.RIGHT);
    }
}
