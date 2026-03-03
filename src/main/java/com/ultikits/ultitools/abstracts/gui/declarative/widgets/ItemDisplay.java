package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * ItemDisplay 是一个展示物品的 Widget。
 * <p>
 * 它是最基本的渲染 Widget，将一个 ItemStack 显示在指定的槽位。
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>{@code
 * ItemDisplay.builder(itemStack)
 *     .slot(10)
 *     .key("my-item")
 *     .onClick(event -> player.sendMessage("Clicked!"))
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class ItemDisplay extends RenderObjectWidget {

    @NotNull
    private final ItemStack itemStack;
    private final int slot;
    @Nullable
    private final String displayName;
    @Nullable
    private final String[] lore;
    @Nullable
    private final Consumer<InventoryClickEvent> clickHandler;

    private ItemDisplay(@NotNull Builder builder) {
        super(builder.key);
        this.itemStack = builder.itemStack.clone();
        this.slot = builder.slot;
        this.displayName = builder.displayName;
        this.lore = builder.lore;
        this.clickHandler = builder.clickHandler;
    }

    /**
     * 创建 Builder。
     *
     * @param itemStack 物品
     * @return Builder
     */
    @NotNull
    public static Builder builder(@NotNull ItemStack itemStack) {
        return new Builder(itemStack);
    }

    @NotNull
    public ItemStack getItemStack() {
        return itemStack;
    }

    public int getSlot() {
        return slot;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
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
        return new ItemDisplayElement(this);
    }

    /**
     * Builder for ItemDisplay.
     */
    public static class Builder implements WidgetBuilder<ItemDisplay> {
        @NotNull
        private final ItemStack itemStack;
        private int slot = 0;
        @Nullable
        private String displayName;
        @Nullable
        private String[] lore;
        @Nullable
        private Consumer<InventoryClickEvent> clickHandler;
        @Nullable
        private SlotKey key;

        Builder(@NotNull ItemStack itemStack) {
            this.itemStack = itemStack;
        }

        public Builder slot(int slot) {
            this.slot = slot;
            return this;
        }

        public Builder name(@Nullable String name) {
            this.displayName = name;
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
        public ItemDisplay build() {
            return new ItemDisplay(this);
        }
    }
}
