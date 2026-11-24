package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.ui.GridAction;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable item description displayed at a grid slot; its icon is never takeable.
 * Names and lore belong to the {@link ItemSpec} and are rendered for the viewer by the UI backend.
 * @since 6.2.0
 */
public class ItemDisplay extends RenderObjectWidget {
    private final ItemSpec icon;
    private final int slot;
    private final @Nullable GridAction clickHandler;

    private ItemDisplay(Builder builder) {
        super(builder.key);
        icon = builder.icon;
        slot = builder.slot;
        clickHandler = builder.clickHandler;
    }

    /** @param icon the immutable item description @return a new builder */
    public static Builder builder(ItemSpec icon) { return new Builder(icon); }
    /** @return the immutable item description */
    public ItemSpec getIcon() { return icon; }
    /** @return the zero-based grid slot */
    public int getSlot() { return slot; }
    /** @return the optional action, independent of inventory events */
    public @Nullable GridAction getClickHandler() { return clickHandler; }

    @Override public @NotNull RenderObjectElement createElement() { return new ItemDisplayElement(this); }

    /** A reusable builder for an item display. */
    public static class Builder implements WidgetBuilder<ItemDisplay> {
        private final ItemSpec icon;
        private int slot;
        private @Nullable GridAction clickHandler;
        private @Nullable SlotKey key;

        Builder(ItemSpec icon) { this.icon = Objects.requireNonNull(icon, "icon"); }
        /** @param slot the grid index @return this builder */
        public Builder slot(int slot) { this.slot = slot; return this; }
        /** @param handler the action, or null to remove it @return this builder */
        public Builder onClick(@Nullable GridAction handler) { clickHandler = handler; return this; }
        /** @param action a parameterless action, or null to remove it @return this builder */
        public Builder onClick(@Nullable Runnable action) {
            clickHandler = action == null ? null : click -> action.run();
            return this;
        }
        /** @param key the reconciliation key @return this builder */
        public Builder key(@Nullable SlotKey key) { this.key = key; return this; }
        /** @param key the reconciliation key @return this builder */
        public Builder key(String key) { this.key = SlotKey.of(key); return this; }
        @Override public @NotNull ItemDisplay build() { return new ItemDisplay(this); }
    }
}
