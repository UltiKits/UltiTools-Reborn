package com.ultikits.ultitools.entities;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class SoundsTest {

    @Test
    void testEnumValues() {
        assertNotNull(Sounds.BLOCK_NOTE_BLOCK_HAT);
        assertNotNull(Sounds.BLOCK_CHEST_OPEN);
        assertNotNull(Sounds.BLOCK_CHEST_LOCKED);
        assertNotNull(Sounds.ENTITY_ENDERMAN_TELEPORT);
        assertNotNull(Sounds.BLOCK_WET_GRASS_BREAK);
        assertNotNull(Sounds.UI_TOAST_OUT);
        assertNotNull(Sounds.BLOCK_NOTE_BLOCK_CHIME);
        assertNotNull(Sounds.BLOCK_NOTE_BLOCK_BELL);
        assertNotNull(Sounds.ITEM_BOOK_PAGE_TURN);
        assertNotNull(Sounds.BLOCK_CHEST_CLOSE);
    }
}
