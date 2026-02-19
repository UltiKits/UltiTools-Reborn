package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * TextButton 是一个显示文本的按钮 Widget。
 * <p>
 * 它使用彩色玻璃板作为按钮背景，上面显示文本。
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * TextButton.builder()
 *     .text("Confirm")
 *     .color(Colors.GREEN)
 *     .slot(13)
 *     .onClick(() -> {
 *         player.sendMessage("Confirmed!");
 *         player.closeInventory();
 *     })
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class TextButton extends RenderObjectWidget {

    @NotNull
    private final String text;
    @NotNull
    private final String color;
    private final int slot;
    @Nullable
    private final String[] lore;
    @Nullable
    private final Consumer<InventoryClickEvent> clickHandler;

    private TextButton(@NotNull Builder builder) {
        super(builder.key);
        this.text = builder.text;
        this.color = builder.color;
        this.slot = builder.slot;
        this.lore = builder.lore;
        this.clickHandler = builder.clickHandler;
    }

    /**
     * 创建 Builder。
     *
     * @return Builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    @NotNull
    public String getText() {
        return text;
    }

    @NotNull
    public String getColor() {
        return color;
    }

    public int getSlot() {
        return slot;
    }

    @Nullable
    public String[] getLore() {
        return lore;
    }

    @Nullable
    public Consumer<InventoryClickEvent> getClickHandler() {
        return clickHandler;
    }

    @Override
    @NotNull
    public RenderObjectElement createElement() {
        return new TextButtonElement(this);
    }

    /**
     * Builder for TextButton.
     */
    public static class Builder implements WidgetBuilder<TextButton> {
        @NotNull
        private String text = "";
        @NotNull
        private String color = "WHITE";
        private int slot = 0;
        @Nullable
        private String[] lore;
        @Nullable
        private Consumer<InventoryClickEvent> clickHandler;
        @Nullable
        private SlotKey key;

        public Builder text(@NotNull String text) {
            this.text = text;
            return this;
        }

        public Builder color(@NotNull String color) {
            this.color = color;
            return this;
        }

        public Builder slot(int slot) {
            this.slot = slot;
            return this;
        }

        public Builder lore(@Nullable String... lore) {
            this.lore = lore;
            return this;
        }

        public Builder onClick(@Nullable Consumer<InventoryClickEvent> handler) {
            this.clickHandler = handler;
            return this;
        }

        public Builder onClick(@Nullable Runnable action) {
            if (action != null) {
                this.clickHandler = e -> action.run();
            } else {
                this.clickHandler = null;
            }
            return this;
        }

        public Builder key(@Nullable SlotKey key) {
            this.key = key;
            return this;
        }

        public Builder key(@NotNull String key) {
            this.key = SlotKey.of(key);
            return this;
        }

        @Override
        @NotNull
        public TextButton build() {
            return new TextButton(this);
        }
    }
}
