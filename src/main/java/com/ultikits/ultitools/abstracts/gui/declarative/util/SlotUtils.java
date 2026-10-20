package com.ultikits.ultitools.abstracts.gui.declarative.util;

/**
 * Slot-index calculation utilities.
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public final class SlotUtils {

    private SlotUtils() {
        // Utility class — instantiation forbidden
    }

    /**
     * Converts a row/column pair into a slot index.
     *
     * @param row row (0-based)
     * @param col column (0-based)
     * @return slot index
     */
    public static int toSlotIndex(int row, int col) {
        return row * 9 + col;
    }

    /**
     * Computes the slot index of a position relative to a starting slot.
     *
     * @param startSlot starting slot
     * @param rowOffset row offset
     * @param colOffset column offset
     * @return slot index
     */
    public static int toSlotIndex(int startSlot, int rowOffset, int colOffset) {
        int startRow = startSlot / 9;
        int startCol = startSlot % 9;
        return (startRow + rowOffset) * 9 + (startCol + colOffset);
    }

    /**
     * Gets the row of a slot index.
     *
     * @param slot slot index
     * @return row (0-based)
     */
    public static int getRow(int slot) {
        return slot / 9;
    }

    /**
     * Gets the column of a slot index.
     *
     * @param slot slot index
     * @return column (0-based)
     */
    public static int getCol(int slot) {
        return slot % 9;
    }

    /**
     * Checks whether a slot is within the valid GUI range.
     *
     * @param slot slot index
     * @param rows number of GUI rows
     * @return true if valid
     */
    public static boolean isValidSlot(int slot, int rows) {
        return slot >= 0 && slot < rows * 9;
    }

    /**
     * Checks whether a slot sits on the border (the outermost ring).
     *
     * @param slot slot index
     * @param rows number of GUI rows
     * @return true if on the border
     */
    public static boolean isBorderSlot(int slot, int rows) {
        int row = getRow(slot);
        int col = getCol(slot);
        return row == 0 || row == rows - 1 || col == 0 || col == 8;
    }

    /**
     * Gets the starting slot of the given row.
     *
     * @param row row (0-based)
     * @return starting slot
     */
    public static int getRowStart(int row) {
        return row * 9;
    }

    /**
     * Gets the slot range of the given row.
     *
     * @param row row (0-based)
     * @return starting slot (inclusive) and ending slot (exclusive)
     */
    public static int[] getRowRange(int row) {
        int start = row * 9;
        return new int[]{start, start + 9};
    }

    /**
     * Gets the center slot.
     *
     * @param rows number of GUI rows
     * @return center slot index
     */
    public static int getCenterSlot(int rows) {
        return (rows / 2) * 9 + 4;
    }

    /**
     * Creates a slot range.
     *
     * @param startSlot starting slot
     * @param endSlot   ending slot (inclusive)
     * @return array of slots
     */
    public static int[] createRange(int startSlot, int endSlot) {
        int[] slots = new int[endSlot - startSlot + 1];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = startSlot + i;
        }
        return slots;
    }
}
