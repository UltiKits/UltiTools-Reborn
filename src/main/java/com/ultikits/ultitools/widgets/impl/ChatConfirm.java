package com.ultikits.ultitools.widgets.impl;

import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ChatCallbackManager;
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.widgets.Confirm;

import cn.hutool.core.lang.func.VoidFunc0;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A chat-based confirmation dialog implementation.
 * <p>
 * This widget displays a confirmation prompt in the player's chat with clickable
 * confirm and cancel buttons. Uses Adventure API for rich text formatting.
 * </p>
 * <p>
 * 基于聊天的确认对话框实现。
 * 该组件在玩家聊天中显示带有可点击确认和取消按钮的确认提示。
 * 使用 Adventure API 进行富文本格式化。
 * </p>
 *
 * <p><strong>Example Usage / 使用示例:</strong></p>
 * <pre>{@code
 * Confirm.chat(player, "Delete Item", "Are you sure?",
 *     () -> deleteItem(),
 *     () -> player.sendMessage("Cancelled")
 * ).show();
 * }</pre>
 *
 * @author wisdomme
 * @see Confirm
 * @see InventoryConfirm
 * @see ChatCallbackManager
 * @since 6.0.0
 */
public class ChatConfirm implements Confirm {
    /** Custom confirm button text, null uses default i18n "OK" / 自定义确认按钮文本，null 使用默认 i18n "OK" */
    private String confirmText;
    /** Custom cancel button text, null uses default i18n "取消" / 自定义取消按钮文本，null 使用默认 i18n "取消" */
    private String cancelText;

    /** The target player to show the dialog / 显示对话框的目标玩家 */
    private final Player player;
    /** The title text displayed in bold gold / 以粗体金色显示的标题文本 */
    private final String title;
    /** The description text displayed below title / 显示在标题下方的描述文本 */
    private final String description;
    /** Callback executed when player clicks confirm / 玩家点击确认时执行的回调 */
    private final VoidFunc0 onConfirm;
    /** Callback executed when player clicks cancel / 玩家点击取消时执行的回调 */
    private final VoidFunc0 onCancel;

    /**
     * Creates a chat confirmation dialog with default button text.
     * <br>
     * 使用默认按钮文本创建聊天确认对话框。
     *
     * @param player      the target player / 目标玩家
     * @param title       the dialog title / 对话框标题
     * @param description the dialog description / 对话框描述
     * @param onConfirm   callback for confirm action / 确认操作的回调
     * @param onCancel    callback for cancel action / 取消操作的回调
     */
    public ChatConfirm(Player player, String title, String description, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        this.player = player;
        this.title = title;
        this.description = description;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * Creates a chat confirmation dialog with custom button text.
     * <br>
     * 使用自定义按钮文本创建聊天确认对话框。
     *
     * @param player      the target player / 目标玩家
     * @param title       the dialog title / 对话框标题
     * @param description the dialog description / 对话框描述
     * @param confirmText custom confirm button text / 自定义确认按钮文本
     * @param cancelText  custom cancel button text / 自定义取消按钮文本
     * @param onConfirm   callback for confirm action / 确认操作的回调
     * @param onCancel    callback for cancel action / 取消操作的回调
     */
    public ChatConfirm(Player player, String title, String description, String confirmText, String cancelText, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        this(player, title, description, onConfirm, onCancel);
        this.confirmText = confirmText;
        this.cancelText = cancelText;
    }

    /**
     * {@inheritDoc}
     * Displays the confirmation dialog in player's chat with:
     * <ul>
     *   <li>Bold gold title text</li>
     *   <li>Description text</li>
     *   <li>Clickable green confirm button</li>
     *   <li>Clickable red cancel button</li>
     * </ul>
     * <p>
     * 在玩家聊天中显示确认对话框，包含：
     * </p>
     * <ul>
     *   <li>粗体金色标题文本</li>
     *   <li>描述文本</li>
     *   <li>可点击的绿色确认按钮</li>
     *   <li>可点击的红色取消按钮</li>
     * </ul>
     */
    @Override
    public void show() {
        TextComponent titleText = Component
                .text(title)
                .decorate(TextDecoration.BOLD)
                .color(TextColor.color(255, 222, 55));
        TextComponent descText = Component
                .text(description);
        TextComponent actionBtn = Component.empty()
                .append(Component
                        .text("[ " + getConfirmText() + " ]")
                        .color(TextColor.color(0, 255, 0))
                        .clickEvent(ClickEvent.runCommand("/ultitools_callback " + ChatCallbackManager.registerCallback(onConfirm)))
                )
                .append(Component.text("     "))
                .append(Component
                        .text("[ " + getCancelText() + " ]")
                        .color(TextColor.color(255, 0, 0))
                        .clickEvent(ClickEvent.runCommand("/ultitools_callback " + ChatCallbackManager.registerCallback(onCancel)))
                );

        MessageUtils.sendMessage(player, titleText);
        MessageUtils.sendMessage(player, descText);
        MessageUtils.sendMessage(player, actionBtn);
    }

    @Override
    public String getConfirmText() {
        return confirmText == null ? UltiTools.getInstance().i18n("OK") : confirmText;
    }

    @Override
    public String getCancelText() {
        return cancelText == null ? UltiTools.getInstance().i18n("取消") : cancelText;
    }
}
