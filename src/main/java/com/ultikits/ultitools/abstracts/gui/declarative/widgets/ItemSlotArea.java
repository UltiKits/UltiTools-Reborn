package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.SlotContainer;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Binds a consecutive row-major area of a grid to existing container indexes, read-only by default.
 * Paging changes firstIndex, never container contents or lifetime. A partial last page leaves the
 * remaining grid slots unbound. Use it as a layout child with its own startSlot, not as a single
 * GridView cell (a cell assigns one position to its render object).
 * <pre>{@code
 * ItemSlotArea.builder(container).firstIndex(page * 9).count(9).build(); // VIEW
 * ItemSlotArea.builder(container).count(9).mode(BindingMode.EDITABLE).build(); // Explicit opt-in
 * }</pre>
 * @since 7.0.0
 */
public class ItemSlotArea extends RenderObjectWidget {
    private final SlotContainer container;
    private final int firstIndex;
    private final int count;
    private final int startSlot;
    private final BindingMode mode;

    private ItemSlotArea(Builder builder) {
        super(builder.key);
        container = builder.container;
        firstIndex = builder.firstIndex;
        count = builder.count;
        startSlot = builder.startSlot;
        mode = builder.mode;
    }

    /** @param container the container whose contents are shown @return a new builder */
    public static Builder builder(SlotContainer container) { return new Builder(container); }
    /** @return the bound container */
    public SlotContainer getContainer() { return container; }
    /** @return the first visible container index */
    public int getFirstIndex() { return firstIndex; }
    /** @return the requested area size, possibly exceeding the remaining container indexes */
    public int getCount() { return count; }
    /** @return the first grid slot */
    public int getStartSlot() { return startSlot; }
    /** @return the binding's edit/view mode */
    public BindingMode getMode() { return mode; }
    @Override public @NotNull RenderObjectElement createElement() { return new ItemSlotAreaElement(this); }

    /** A non-thread-safe range builder; defaults to all container indexes at slot zero, in VIEW mode. */
    public static class Builder implements WidgetBuilder<ItemSlotArea> {
        private final SlotContainer container;
        private int firstIndex;
        private int count;
        private int startSlot;
        private BindingMode mode = BindingMode.VIEW;
        private @Nullable SlotKey key;

        Builder(SlotContainer container) {
            this.container = Objects.requireNonNull(container, "container");
            count = container.size();
        }
        /**
         * @param firstIndex first visible index; negative indexes and indexes at/after a nonempty
         *                   container's size are rejected by build, even for an empty area.
         *                   An empty container accepts only zero.
         * @return this builder
         */
        public Builder firstIndex(int firstIndex) { this.firstIndex = firstIndex; return this; }
        /** @param count requested area size (zero is empty); its tail may exceed the container @return this builder */
        public Builder count(int count) { this.count = count; return this; }
        /** @param startSlot first row-major grid index @return this builder */
        public Builder startSlot(int startSlot) { this.startSlot = startSlot; return this; }
        /** @param mode edit/view mode; moving items requires explicit EDITABLE @return this builder */
        public Builder mode(BindingMode mode) { this.mode = Objects.requireNonNull(mode, "mode"); return this; }
        /** @param key reconciliation key @return this builder */
        public Builder key(@Nullable SlotKey key) { this.key = key; return this; }
        /** @param key reconciliation key @return this builder */
        public Builder key(String key) { this.key = SlotKey.of(key); return this; }

        /**
         * @return the immutable range description; nonexistent tail indexes generate no bindings
         * @throws IllegalArgumentException if firstIndex is invalid, count is negative, or the
         *                                  requested area is outside a six-row grid
         */
        @Override public @NotNull ItemSlotArea build() {
            int size = container.size();
            if (firstIndex < 0 || count < 0 || (size == 0 ? firstIndex != 0 : firstIndex >= size)) {
                throw new IllegalArgumentException("binding range is outside the container");
            }
            if (startSlot < 0 || startSlot >= 54 || (long) startSlot + count > 54) {
                throw new IllegalArgumentException("binding range is outside the grid");
            }
            return new ItemSlotArea(this);
        }
    }
}
