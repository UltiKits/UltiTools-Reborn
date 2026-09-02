package com.ultikits.ultitools.widgets.impl;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage;
import com.ultikits.ultitools.widgets.Confirm;

/**
 * A GUI inventory-based confirmation dialog implementation.
 * <p>
 * This widget displays a 3-row inventory GUI with OK and Cancel buttons.
 * Extends {@link BaseConfirmationPage} to inherit the standard OK/Cancel button layout.
 * </p>
 * <p>
 * 基于 GUI 物品栏的确认对话框实现。
 * 该组件显示一个带有确定和取消按钮的 3 行物品栏界面。
 * 继承 {@link BaseConfirmationPage} 以获得标准的确定/取消按钮布局。
 * </p>
 *
 * <p><strong>Example Usage / 使用示例:</strong></p>
 * <pre>{@code
 * Confirm.gui(player, "Confirm Purchase", "Buy Diamond Sword for 100 coins?",
 *     () -> completePurchase(),
 *     () -> cancelPurchase()
 * ).show();
 * }</pre>
 *
 * @author wisdomme
 * @see Confirm
 * @see ChatConfirm
 * @see BaseConfirmationPage
 * @since 6.0.0
 */
public class InventoryConfirm extends BaseConfirmationPage implements Confirm {
    /** Custom confirm button text, null uses BaseConfirmationPage default / 自定义确认按钮文本，null 使用 BaseConfirmationPage 默认值 */
    private String confirmText;
    /** Custom cancel button text, null uses BaseConfirmationPage default / 自定义取消按钮文本，null 使用 BaseConfirmationPage 默认值 */
    private String cancelText;

    /** Callback executed when player clicks OK button / 玩家点击确定按钮时执行的回调 */
    private final Runnable onConfirmCallback;
    /** Callback executed when player clicks Cancel button / 玩家点击取消按钮时执行的回调 */
    private final Runnable onCancelCallback;

    /** The unique GUI identifier / GUI 唯一标识符 */
    private static final String GUI_ID = "confirm_gui";
    /** The number of rows in the GUI (3 rows = 27 slots) / GUI 行数（3 行 = 27 格） */
    private static final int GUI_ROWS = 3;

    /**
     * Creates an inventory confirmation dialog with default button text.
     * <br>
     * 使用默认按钮文本创建物品栏确认对话框。
     *
     * @param player      the target player / 目标玩家
     * @param title       the dialog title / 对话框标题
     * @param description the dialog description (appended to title) / 对话框描述（附加到标题后）
     * @param onConfirm   callback for confirm action / 确认操作的回调
     * @param onCancel    callback for cancel action / 取消操作的回调
     */
    public InventoryConfirm(Player player, String title, String description, Runnable onConfirm, Runnable onCancel) {
        super(player, GUI_ID, title + " - " + description, GUI_ROWS);
        this.onConfirmCallback = onConfirm;
        this.onCancelCallback = onCancel;
    }

    /**
     * Creates an inventory confirmation dialog with custom button text.
     * <br>
     * 使用自定义按钮文本创建物品栏确认对话框。
     *
     * @param player      the target player / 目标玩家
     * @param title       the dialog title / 对话框标题
     * @param description the dialog description (appended to title) / 对话框描述（附加到标题后）
     * @param confirmText custom confirm button text / 自定义确认按钮文本
     * @param cancelText  custom cancel button text / 自定义取消按钮文本
     * @param onConfirm   callback for confirm action / 确认操作的回调
     * @param onCancel    callback for cancel action / 取消操作的回调
     */
    public InventoryConfirm(Player player, String title, String description, String confirmText, String cancelText, Runnable onConfirm, Runnable onCancel) {
        this(player, title, description, onConfirm, onCancel);
        this.confirmText = confirmText;
        this.cancelText = cancelText;
    }

    @Override
    protected String getOkButtonName() {
        return getConfirmText();
    }

    @Override
    protected String getCancelButtonName() {
        return getCancelText();
    }

    @Override
    public String getConfirmText() {
        return confirmText == null ? super.getOkButtonName() : confirmText;
    }

    @Override
    public String getCancelText() {
        return cancelText == null ? super.getCancelButtonName() : cancelText;
    }

    @Override
    public void show() {
        this.open();
    }

    @Override
    protected void onConfirm(InventoryClickEvent clickEvent) {
        onConfirmCallback.run();
    }

    @Override
    protected void onCancel(InventoryClickEvent clickEvent) {
        onCancelCallback.run();
    }
}
