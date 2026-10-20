package com.ultikits.ultitools.entities;

import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.utils.XVersionUtils;

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
    PREVIOUS("上一页", () -> XVersionUtils.getColoredPlaneGlass(Colors.RED)),
    /**
     * Next buttons.
     */
    NEXT("下一页", () -> XVersionUtils.getColoredPlaneGlass(Colors.RED)),
    /**
     * Back buttons.
     */
    BACK("返回", XVersionUtils::getSign),
    /**
     * Quit buttons.
     */
    QUIT("退出", XVersionUtils::getEndEye),
    /**
     * Ok buttons.
     */
    OK("确认", () -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN)),
    /**
     * Cancel buttons.
     */
    CANCEL("取消", () -> XVersionUtils.getColoredPlaneGlass(Colors.RED));

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
