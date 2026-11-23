package com.ultikits.ultitools.ui;

/**
 * A cell; zero-based.
 * @param row row 0..5
 * @param column column 0..8
 */
public record GridSlot(int row, int column) {
    /** @throws IllegalArgumentException if {@code row} is outside 0..5 or {@code column} outside 0..8 */
    public GridSlot {
        if (row < 0 || row > 5 || column < 0 || column >= Grid.COLUMNS) {
            throw new IllegalArgumentException("row must be in 0..5 and column in 0..8");
        }
    }

    /** Same as the canonical constructor. */
    public static GridSlot of(int row, int column) { return new GridSlot(row, column); }
    /** {@code row * 9 + column}. */
    public int index() { return row * Grid.COLUMNS + column; }

    /** @throws IllegalArgumentException if {@code index} is outside 0..53 */
    public static GridSlot ofIndex(int index) {
        if (index < 0 || index >= 6 * Grid.COLUMNS) {
            throw new IllegalArgumentException("index must be in 0..53");
        }
        return new GridSlot(index / Grid.COLUMNS, index % Grid.COLUMNS);
    }
}
