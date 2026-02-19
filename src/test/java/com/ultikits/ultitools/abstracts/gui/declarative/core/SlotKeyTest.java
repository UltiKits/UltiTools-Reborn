package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlotKey 测试。
 */
public class SlotKeyTest {

    @Test
    void testSlotKeyCreation() {
        SlotKey key = SlotKey.of("test");
        assertEquals("test", key.getValue());
    }

    @Test
    void testSlotKeyWithPrefix() {
        SlotKey key = SlotKey.of("prefix", "value");
        assertEquals("prefix:value", key.getValue());
    }

    @Test
    void testEmptyKeyThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> SlotKey.of(""));
    }

    @Test
    void testSlotKeyEquality() {
        SlotKey key1 = SlotKey.of("same");
        SlotKey key2 = SlotKey.of("same");
        SlotKey key3 = SlotKey.of("different");

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);
    }

    @Test
    void testSlotKeyToString() {
        SlotKey key = SlotKey.of("my-key");
        assertTrue(key.toString().contains("my-key"));
    }
}
