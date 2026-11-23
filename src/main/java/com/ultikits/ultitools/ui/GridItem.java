package com.ultikits.ultitools.ui;

import com.ultikits.ultitools.item.ItemSpec;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** What one slot shows and does. */
public final class GridItem {
    private final ItemSpec icon;
    private final @Nullable GridAction action;

    private GridItem(ItemSpec icon, @Nullable GridAction action) { this.icon = icon; this.action = action; }
    /** Creates an icon without an action. */
    public static GridItem of(ItemSpec icon) { return new GridItem(Objects.requireNonNull(icon, "icon"), null); }
    /** Creates an icon with an action. */
    public static GridItem of(ItemSpec icon, GridAction action) {
        return new GridItem(Objects.requireNonNull(icon, "icon"), Objects.requireNonNull(action, "action"));
    }

    /** The immutable icon description. */
    public ItemSpec icon() { return icon; }
    /** The optional entity-thread action. */
    public Optional<GridAction> action() { return Optional.ofNullable(action); }

    /** Compares icon equality and action identity, never an action's custom equality. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || o instanceof GridItem other && icon.equals(other.icon) && action == other.action;
    }

    @Override public int hashCode() { return 31 * icon.hashCode() + System.identityHashCode(action); }
    @Override public String toString() { return "GridItem[icon=" + icon + ", action=" + action + ']'; }
}
