package com.ultikits.ultitools.abstracts.gui.declarative.util;

/**
 * 槽位计算工具类。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public final class SlotUtils {

    private SlotUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 将行列转换为槽位索引。
     *
     * @param row 行（0-based）
     * @param col 列（0-based）
     * @return 槽位索引
     */
    public static int toSlotIndex(int row, int col) {
        return row * 9 + col;
    }

    /**
     * 从起始槽位计算相对位置的槽位索引。
     *
     * @param startSlot 起始槽位
     * @param rowOffset 行偏移
     * @param colOffset 列偏移
     * @return 槽位索引
     */
    public static int toSlotIndex(int startSlot, int rowOffset, int colOffset) {
        int startRow = startSlot / 9;
        int startCol = startSlot % 9;
        return (startRow + rowOffset) * 9 + (startCol + colOffset);
    }

    /**
     * 从槽位索引获取行。
     *
     * @param slot 槽位索引
     * @return 行（0-based）
     */
    public static int getRow(int slot) {
        return slot / 9;
    }

    /**
     * 从槽位索引获取列。
     *
     * @param slot 槽位索引
     * @return 列（0-based）
     */
    public static int getCol(int slot) {
        return slot % 9;
    }

    /**
     * 检查槽位是否在有效的 GUI 范围内。
     *
     * @param slot 槽位索引
     * @param rows GUI 行数
     * @return 如果有效返回 true
     */
    public static boolean isValidSlot(int slot, int rows) {
        return slot >= 0 && slot < rows * 9;
    }

    /**
     * 检查槽位是否在边框上（最外圈）。
     *
     * @param slot 槽位索引
     * @param rows GUI 行数
     * @return 如果在边框上返回 true
     */
    public static boolean isBorderSlot(int slot, int rows) {
        int row = getRow(slot);
        int col = getCol(slot);
        return row == 0 || row == rows - 1 || col == 0 || col == 8;
    }

    /**
     * 获取指定行的起始槽位。
     *
     * @param row 行（0-based）
     * @return 起始槽位
     */
    public static int getRowStart(int row) {
        return row * 9;
    }

    /**
     * 获取指定行的槽位范围。
     *
     * @param row 行（0-based）
     * @return 起始槽位（包含）和结束槽位（不包含）
     */
    public static int[] getRowRange(int row) {
        int start = row * 9;
        return new int[]{start, start + 9};
    }

    /**
     * 获取中心槽位。
     *
     * @param rows GUI 行数
     * @return 中心槽位索引
     */
    public static int getCenterSlot(int rows) {
        return (rows / 2) * 9 + 4;
    }

    /**
     * 创建槽位范围。
     *
     * @param startSlot 起始槽位
     * @param endSlot   结束槽位（包含）
     * @return 槽位数组
     */
    public static int[] createRange(int startSlot, int endSlot) {
        int[] slots = new int[endSlot - startSlot + 1];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = startSlot + i;
        }
        return slots;
    }
}
