package com.ultikits.ultitools.entities;

import com.ultikits.ultitools.UltiTools;
import org.bukkit.inventory.ItemStack;

/**
 * The enum Buttons.
 */
public enum Buttons {
    /**
     * Previous buttons.
     */
    PREVIOUS(UltiTools.getInstance().i18n("上一页"), UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
    /**
     * Next buttons.
     */
    NEXT(UltiTools.getInstance().i18n("下一页"), UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
    /**
     * Back buttons.
     */
    BACK(UltiTools.getInstance().i18n("返回"), UltiTools.getInstance().getVersionWrapper().getSign()),
    /**
     * Quit buttons.
     */
    QUIT(UltiTools.getInstance().i18n("退出"), UltiTools.getInstance().getVersionWrapper().getEndEye()),
    /**
     * Ok buttons.
     */
    OK(UltiTools.getInstance().i18n("确认"), UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN)),
    /**
     * Cancel buttons.
     */
    CANCEL(UltiTools.getInstance().i18n("取消"), UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED));

    /**
     * The Name.
     */
    String name;
    /**
     * The Item stack.
     */
    ItemStack itemStack;

    Buttons(String name, ItemStack itemStack) {
        this.name = name;
        this.itemStack = itemStack;
    }

    /**
     * Get name string.
     *
     * @return Button 's name 按钮名称
     */
    public String getName() {
        return name;
    }

    /**
     * Get item stack item stack.
     *
     * @return Button 's material 按钮材质
     */
    public ItemStack getItemStack() {
        return itemStack;
    }
}
