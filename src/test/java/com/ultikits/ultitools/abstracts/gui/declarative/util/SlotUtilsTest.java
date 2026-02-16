package com.ultikits.ultitools.abstracts.gui.declarative.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlotUtils 测试。
 */
public class SlotUtilsTest {

    @Test
    void testToSlotIndex() {
        // row 0, col 0 -> slot 0
        assertEquals(0, SlotUtils.toSlotIndex(0, 0));
        // row 0, col 8 -> slot 8
        assertEquals(8, SlotUtils.toSlotIndex(0, 8));
        // row 1, col 0 -> slot 9
        assertEquals(9, SlotUtils.toSlotIndex(1, 0));
        // row 5, col 4 -> slot 49
        assertEquals(49, SlotUtils.toSlotIndex(5, 4));
    }

    @Test
    void testToSlotIndexWithStartSlot() {
        // start at slot 10, offset (1, 2) -> slot 10 + 9 + 2 = 21
        assertEquals(21, SlotUtils.toSlotIndex(10, 1, 2));
    }

    @Test
    void testGetRow() {
        assertEquals(0, SlotUtils.getRow(0));
        assertEquals(0, SlotUtils.getRow(8));
        assertEquals(1, SlotUtils.getRow(9));
        assertEquals(5, SlotUtils.getRow(53));
    }

    @Test
    void testGetCol() {
        assertEquals(0, SlotUtils.getCol(0));
        assertEquals(8, SlotUtils.getCol(8));
        assertEquals(0, SlotUtils.getCol(9));
        assertEquals(8, SlotUtils.getCol(53));
    }

    @Test
    void testIsValidSlot() {
        assertTrue(SlotUtils.isValidSlot(0, 6));
        assertTrue(SlotUtils.isValidSlot(53, 6));
        assertFalse(SlotUtils.isValidSlot(54, 6));
        assertFalse(SlotUtils.isValidSlot(-1, 6));
    }

    @Test
    void testIsBorderSlot() {
        // 第0行
        assertTrue(SlotUtils.isBorderSlot(0, 6));
        assertTrue(SlotUtils.isBorderSlot(8, 6));
        // 最后一行
        assertTrue(SlotUtils.isBorderSlot(45, 6));
        assertTrue(SlotUtils.isBorderSlot(53, 6));
        // 左列
        assertTrue(SlotUtils.isBorderSlot(9, 6));
        assertTrue(SlotUtils.isBorderSlot(18, 6));
        // 右列
        assertTrue(SlotUtils.isBorderSlot(17, 6));
        assertTrue(SlotUtils.isBorderSlot(26, 6));
        // 中间
        assertFalse(SlotUtils.isBorderSlot(10, 6));
        assertFalse(SlotUtils.isBorderSlot(20, 6));
    }

    @Test
    void testGetRowStart() {
        assertEquals(0, SlotUtils.getRowStart(0));
        assertEquals(9, SlotUtils.getRowStart(1));
        assertEquals(45, SlotUtils.getRowStart(5));
    }

    @Test
    void testGetRowRange() {
        int[] range = SlotUtils.getRowRange(2);
        assertEquals(18, range[0]);
        assertEquals(27, range[1]);
    }

    @Test
    void testGetCenterSlot() {
        assertEquals(13, SlotUtils.getCenterSlot(3)); // 第2行中间
        assertEquals(31, SlotUtils.getCenterSlot(6)); // 第4行中间
    }

    @Test
    void testCreateRange() {
        int[] range = SlotUtils.createRange(10, 14);
        assertEquals(5, range.length);
        assertEquals(10, range[0]);
        assertEquals(14, range[4]);
    }
}
