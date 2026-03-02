package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import mc.obliviate.inventory.Icon;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * IconWrapper 是对 Icon 的简单包装，用于在 Widget 树中传递。
 * <p>
 * 它不可变，可以在多个 Widget 之间安全共享。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class IconWrapper {

    @NotNull
    private final Icon icon;

    public IconWrapper(@NotNull ItemStack itemStack) {
        this.icon = new Icon(itemStack);
    }

    public IconWrapper(@NotNull Icon icon) {
        this.icon = icon;
    }

    @NotNull
    public Icon getIcon() {
        return icon;
    }

    /**
     * 创建 IconWrapper 的构建器。
     *
     * @param itemStack 物品堆
     * @return Builder
     */
    @NotNull
    public static Builder builder(@NotNull ItemStack itemStack) {
        return new Builder(itemStack);
    }

    /**
     * Builder for IconWrapper.
     */
    public static class Builder {
        private final Icon icon;

        Builder(@NotNull ItemStack itemStack) {
            this.icon = new Icon(itemStack);
        }

        public Builder name(@NotNull String name) {
            icon.setName(name);
            return this;
        }

        public Builder lore(@NotNull String... lore) {
            icon.setLore(lore);
            return this;
        }

        public Builder amount(int amount) {
            icon.getItem().setAmount(amount);
            return this;
        }

        public IconWrapper build() {
            return new IconWrapper(icon);
        }
    }
}
