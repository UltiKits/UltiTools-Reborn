package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.GridAction;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A coloured glass-pane button with deferred text and an optional grid action. @since 6.2.0 */
public class TextButton extends RenderObjectWidget {
    private final Text text;
    private final String color;
    private final int slot;
    private final List<Text> lore;
    private final @Nullable GridAction clickHandler;

    private TextButton(Builder builder) {
        super(builder.key);
        text = builder.text;
        color = builder.color;
        slot = builder.slot;
        lore = builder.lore;
        clickHandler = builder.clickHandler;
    }

    /** @return a new builder */
    public static Builder builder() { return new Builder(); }
    /** @return the deferred button label */
    public Text getText() { return text; }
    /** @return the glass colour name */
    public String getColor() { return color; }
    /** @return the zero-based grid slot */
    public int getSlot() { return slot; }
    /** @return unmodifiable deferred lore */
    public List<Text> getLore() { return lore; }
    /** @return the optional grid action */
    public @Nullable GridAction getClickHandler() { return clickHandler; }
    @Override public @NotNull RenderObjectElement createElement() { return new TextButtonElement(this); }

    /** A reusable builder; text and lore remain immutable after building. */
    public static class Builder implements WidgetBuilder<TextButton> {
        private Text text = Text.plain("");
        private String color = "WHITE";
        private int slot;
        private List<Text> lore = List.of();
        private @Nullable GridAction clickHandler;
        private @Nullable SlotKey key;

        /** @param text the deferred label @return this builder */
        public Builder text(Text text) { this.text = Objects.requireNonNull(text, "text"); return this; }
        /** @param color a colour name; unknown names render plain glass @return this builder */
        public Builder color(String color) { this.color = Objects.requireNonNull(color, "color"); return this; }
        /** @param slot the grid index @return this builder */
        public Builder slot(int slot) { this.slot = slot; return this; }
        /** @param lore deferred lore lines @return this builder */
        public Builder lore(List<Text> lore) { this.lore = List.copyOf(lore); return this; }
        /** @param handler the grid action, or null to remove it @return this builder */
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
        @Override public @NotNull TextButton build() { return new TextButton(this); }
    }
}
