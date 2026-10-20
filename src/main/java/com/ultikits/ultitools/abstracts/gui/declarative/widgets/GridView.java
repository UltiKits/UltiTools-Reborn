package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.SlotUtils;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * GridView is a grid-layout Widget that arranges its children in a grid.
 * <p>
 * Supports:
 * <ul>
 *   <li>a specified starting slot and row/column count</li>
 *   <li>automatic child Widget position calculation</li>
 *   <li>automatic mapping from a data list</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * // Manually specify child widgets
 * GridView.builder()
 *     .startSlot(10)
 *     .columns(7)
 *     .children(buttons)
 *     .build();
 *
 * // Auto-generate from a data list
 * GridView.<ItemStack>builder()
 *     .startSlot(10)
 *     .columns(7)
 *     .items(itemList)
 *     .itemBuilder(item -> ItemDisplay.builder(item).build())
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @param <T> the data type (when built from a data list)
 */
public class GridView<T> extends Widget {

    private final int startSlot;
    private final int columns;
    @NotNull
    private final List<Widget> children;

    private GridView(@NotNull Builder<T> builder) {
        super(builder.key);
        this.startSlot = builder.startSlot;
        this.columns = builder.columns;
        this.children = Collections.unmodifiableList(new ArrayList<>(builder.children));
    }

    /**
     * Creates a Builder.
     *
     * @param <T> the data type
     * @return the Builder
     */
    @NotNull
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public int getStartSlot() {
        return startSlot;
    }

    public int getColumns() {
        return columns;
    }

    @NotNull
    public List<Widget> getChildren() {
        return children;
    }

    /**
     * Calculates the actual slot of a child Widget.
     *
     * @param childIndex the child Widget's index
     * @return slot index
     */
    public int calculateSlotForChild(int childIndex) {
        int row = childIndex / columns;
        int col = childIndex % columns;
        return SlotUtils.toSlotIndex(startSlot, row, col);
    }

    @Override
    @NotNull
    public Element createElement() {
        return new GridViewElement(this);
    }

    /**
     * Builder for GridView.
     */
    public static class Builder<T> implements WidgetBuilder<GridView<T>> {
        private int startSlot = 0;
        private int columns = 9;
        @NotNull
        private List<Widget> children = new ArrayList<>();
        @Nullable
        private SlotKey key;

        public Builder<T> startSlot(int startSlot) {
            this.startSlot = startSlot;
            return this;
        }

        public Builder<T> columns(int columns) {
            this.columns = columns;
            return this;
        }

        public Builder<T> child(@NotNull Widget child) {
            this.children.add(child);
            return this;
        }

        public Builder<T> children(@NotNull List<Widget> children) {
            this.children.addAll(children);
            return this;
        }

        /**
         * Sets the data list and the item-builder function.
         * <p>
         * Each item is converted to a Widget and added to the child list as-is; position is no
         * longer computed here — as of 6.3.0, {@link GridViewElement} writes the position at
         * render time as parent data (D-11), applying uniformly to any Widget type rather than
         * special-casing {@link ItemDisplay}.
         *
         * @param items       the data list
         * @param itemBuilder the builder function
         * @return the Builder
         */
        public Builder<T> items(@NotNull List<T> items, @NotNull Function<T, Widget> itemBuilder) {
            for (T item : items) {
                this.children.add(itemBuilder.apply(item));
            }
            return this;
        }

        public Builder<T> key(@Nullable SlotKey key) {
            this.key = key;
            return this;
        }

        public Builder<T> key(@NotNull String key) {
            this.key = SlotKey.of(key);
            return this;
        }

        @Override
        @NotNull
        public GridView<T> build() {
            return new GridView<>(this);
        }
    }
}
