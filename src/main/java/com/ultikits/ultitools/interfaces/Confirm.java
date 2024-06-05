package com.ultikits.ultitools.interfaces;

import cn.hutool.core.lang.func.VoidFunc0;
import com.ultikits.ultitools.interfaces.impl.InventoryConfirm;
import org.bukkit.entity.Player;

public interface Confirm {
    /**
     * Show the confirm dialog.
     * <br>
     * 显示确认对话框。
     */
    void show();
    /**
     * Get the confirm text.
     * <br>
     * 获取确认文本。
     */
    String getConfirmText();
    /**
     * Get the cancel text.
     * <br>
     * 获取取消文本。
     */
    String getCancelText();

    /**
     * Create a confirm dialog with GUI.
     * <br>
     * 创建一个带有 GUI 的确认对话框。
     *
     * @param player     Player <br> 玩家
     * @param title      Title <br> 标题
     * @param description Description <br> 描述
     * @param onConfirm  On confirm <br> 确认时
     * @param onCancel   On cancel <br> 取消时
     * @return Confirm <br> 确认对话框
     */
    static Confirm gui(Player player, String title, String description, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        return new InventoryConfirm(player, title, description, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with GUI.
     * You can set the confirm and cancel text.
     * <br>
     * 创建一个带有 GUI 的确认对话框。
     * 你可以设置确认和取消文本。
     *
     * @param player      Player <br> 玩家
     * @param title       Title <br> 标题
     * @param description Description <br> 描述
     * @param confirmText Confirm text <br> 确认文本
     * @param cancelText  Cancel text <br> 取消文本
     * @param onConfirm   On confirm <br> 确认时
     * @param onCancel    On cancel <br> 取消时
     * @return Confirm <br> 确认对话框
     */
    static Confirm gui(Player player, String title, String description, String confirmText, String cancelText, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        return new  InventoryConfirm(player, title, description, confirmText, cancelText, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with chat.
     * <br>
     * 创建一个带有聊天的确认对话框。
     *
     * @param player     Player <br> 玩家
     * @param title      Title <br> 标题
     * @param description Description <br> 描述
     * @param onConfirm  On confirm <br> 确认时
     * @param onCancel   On cancel <br> 取消时
     * @return Confirm <br> 确认对话框
     */
    static Confirm chat(Player player, String title, String description, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        return new ChatConfirm(player, title, description, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with chat.
     * You can set the confirm and cancel text.
     * <br>
     * 创建一个带有聊天的确认对话框。
     * 你可以设置确认和取消文本。
     *
     * @param player      Player <br> 玩家
     * @param title       Title <br> 标题
     * @param description Description <br> 描述
     * @param confirmText Confirm text <br> 确认文本
     * @param cancelText  Cancel text <br> 取消文本
     * @param onConfirm   On confirm <br> 确认时
     * @param onCancel    On cancel <br> 取消时
     * @return Confirm <br> 确认对话框
     */
    static Confirm chat(Player player, String title, String description, String confirmText, String cancelText, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        return new ChatConfirm(player, title, description, confirmText, cancelText, onConfirm, onCancel);
    }
}
