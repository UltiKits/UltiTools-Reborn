package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * An item-centred screen addressed by slot: a snapshot of what each slot shows and what a click does.
 * Each slot is empty, an icon ({@link GridItem}: shown, never takeable) or a binding to a {@code SlotContainer}
 * index ({@code bindings()}; EDITABLE or VIEW). Never completes with a value; it ends with
 * {@link UiResult.Closed}. Refresh by pushing a new snapshot through {@link GridSession#update(Grid)}.
 */
public final class Grid implements UiIntent<Ack> {
    /** The fixed number of columns. */
    public static final int COLUMNS = 9;
    private final Text title;
    private final int rows;
    private final Map<GridSlot, GridItem> items;

    private Grid(Builder builder) {
        title = builder.title;
        rows = builder.rows;
        items = Collections.unmodifiableMap(new LinkedHashMap<>(builder.items));
    }

    /** @throws IllegalArgumentException if {@code rows} is outside 1..6 */
    public static Builder builder(Text title, int rows) {
        Objects.requireNonNull(title, "title");
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be in 1..6");
        }
        return new Builder(title, rows);
    }

    @Override public Text title() { return title; }
    /** The number of visible rows. */
    public int rows() { return rows; }
    /** Icon slots; unmodifiable. A slot with neither an icon nor a binding ({@code bindings()}) is empty. */
    public Map<GridSlot, GridItem> items() { return items; }
    /** The icon at a slot, if any. */
    public Optional<GridItem> item(GridSlot slot) { return Optional.ofNullable(items.get(Objects.requireNonNull(slot, "slot"))); }

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof Grid other && rows == other.rows
                && title.equals(other.title) && items.equals(other.items);
    }

    @Override public int hashCode() { return Objects.hash(title, rows, items); }
    @Override public String toString() { return "Grid[title=" + title + ", rows=" + rows + ", items=" + items + ']'; }

    /** A reusable, non-thread-safe snapshot builder. */
    public static final class Builder {
        private final Text title;
        private final int rows;
        private final Map<GridSlot, GridItem> items = new LinkedHashMap<>();

        private Builder(Text title, int rows) { this.title = title; this.rows = rows; }

        /** Sets or replaces an icon. @throws IllegalArgumentException if {@code slot.row() >= rows} */
        public Builder set(GridSlot slot, GridItem item) {
            requireSlot(slot);
            items.put(slot, Objects.requireNonNull(item, "item"));
            return this;
        }

        /** Sets an icon with an optional action at zero-based coordinates. */
        public Builder set(int row, int column, ItemSpec icon, @Nullable GridAction action) {
            Objects.requireNonNull(icon, "icon");
            return set(GridSlot.of(row, column), action == null ? GridItem.of(icon) : GridItem.of(icon, action));
        }

        /** Removes an icon from this snapshot. */
        public Builder clear(GridSlot slot) {
            requireSlot(slot);
            items.remove(slot);
            return this;
        }

        private void requireSlot(GridSlot slot) {
            Objects.requireNonNull(slot, "slot");
            if (slot.row() >= rows) {
                throw new IllegalArgumentException("slot is outside the grid's rows: " + slot);
            }
        }

        private void validate() {
            // Seam: the editable-slot change adds binding checks here.
            items.keySet().forEach(this::requireSlot);
        }

        /** Validates the complete snapshot and copies its icon table. */
        public Grid build() {
            validate();
            return new Grid(this);
        }
    }
}
