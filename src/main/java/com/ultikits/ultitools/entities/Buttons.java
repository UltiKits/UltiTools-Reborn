package com.ultikits.ultitools.entities;

import java.util.function.Supplier;

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
    PREVIOUS("上一页", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
    /**
     * Next buttons.
     */
    NEXT("下一页", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
    /**
     * Back buttons.
     */
    BACK("返回", () -> UltiTools.getInstance().getVersionWrapper().getSign()),
    /**
     * Quit buttons.
     */
    QUIT("退出", () -> UltiTools.getInstance().getVersionWrapper().getEndEye()),
    /**
     * Ok buttons.
     */
    OK("确认", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN)),
    /**
     * Cancel buttons.
     */
    CANCEL("取消", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED));

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
     * @return Button 's name 按钮名称
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
     * @return Button 's material 按钮材质
     */
    public ItemStack getItemStack() {
        if (cachedItemStack == null) {
            cachedItemStack = itemStackSupplier.get();
        }
        return cachedItemStack;
    }
}
