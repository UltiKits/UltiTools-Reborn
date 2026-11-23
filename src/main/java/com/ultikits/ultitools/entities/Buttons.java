package com.ultikits.ultitools.entities;

import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.ultikits.ultitools.UltiTools;

/**
 * The enum Buttons.
 * <p>
 * Uses lazy initialization to avoid calling UltiTools.getInstance() during class loading,
 * which allows for proper testing and prevents NullPointerException when the plugin is not initialized.
 */
public enum Buttons {
    /**
     * Previous buttons.
     */
    PREVIOUS("上一页", () -> new ItemStack(Material.RED_STAINED_GLASS_PANE)),
    /**
     * Next buttons.
     */
    NEXT("下一页", () -> new ItemStack(Material.RED_STAINED_GLASS_PANE)),
    /**
     * Back buttons.
     */
    BACK("返回", () -> new ItemStack(Material.OAK_SIGN)),
    /**
     * Quit buttons.
     */
    QUIT("退出", () -> new ItemStack(Material.ENDER_EYE)),
    /**
     * Ok buttons.
     */
    OK("确认", () -> new ItemStack(Material.GREEN_STAINED_GLASS_PANE)),
    /**
     * Cancel buttons.
     */
    CANCEL("取消", () -> new ItemStack(Material.RED_STAINED_GLASS_PANE));

    /**
     * The i18n key for the button name.
     */
    private final String nameKey;
    /**
     * The supplier for lazy initialization of ItemStack.
     */
    private final Supplier<ItemStack> itemStackSupplier;
    /**
     * Cached translated name.
     */
    private String cachedName;
    /**
     * Cached ItemStack.
     */
    private ItemStack cachedItemStack;

    Buttons(String nameKey, Supplier<ItemStack> itemStackSupplier) {
        this.nameKey = nameKey;
        this.itemStackSupplier = itemStackSupplier;
    }

    /**
     * Get name string.
     *
     * @return Button 's name
     */
    public String getName() {
        if (cachedName == null) {
            cachedName = UltiTools.getInstance().i18n(nameKey);
        }
        return cachedName;
    }

    /**
     * Get item stack item stack.
     *
     * @return Button 's material
     */
    public ItemStack getItemStack() {
        if (cachedItemStack == null) {
            cachedItemStack = itemStackSupplier.get();
        }
        return cachedItemStack;
    }
}
