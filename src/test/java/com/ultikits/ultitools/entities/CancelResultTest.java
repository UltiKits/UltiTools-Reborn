package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class CancelResultTest {

    @Test
    void testEnumValues() {
        assertNotNull(CancelResult.TRUE);
        assertNotNull(CancelResult.FALSE);
        assertNotNull(CancelResult.NONE);
    }
}
