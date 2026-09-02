package com.ultikits.ultitools.widgets.impl;

import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ChatCallbackManager;
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.widgets.Confirm;

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
 *
 * <p><strong>Example Usage:</strong></p>
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
    /** Custom confirm button text, null uses default i18n "OK" */
    private String confirmText;
    /** Custom cancel button text, null uses default i18n("取消") */
    private String cancelText;

    /** The target player to show the dialog */
    private final Player player;
    /** The title text displayed in bold gold */
    private final String title;
    /** The description text displayed below title */
    private final String description;
    /** Callback executed when player clicks confirm */
    private final Runnable onConfirm;
    /** Callback executed when player clicks cancel */
    private final Runnable onCancel;

    /**
     * Creates a chat confirmation dialog with default button text.
     *
     * @param player      the target player
     * @param title       the dialog title
     * @param description the dialog description
     * @param onConfirm   callback for confirm action
     * @param onCancel    callback for cancel action
     */
    public ChatConfirm(Player player, String title, String description, Runnable onConfirm, Runnable onCancel) {
        this.player = player;
        this.title = title;
        this.description = description;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * Creates a chat confirmation dialog with custom button text.
     *
     * @param player      the target player
     * @param title       the dialog title
     * @param description the dialog description
     * @param confirmText custom confirm button text
     * @param cancelText  custom cancel button text
     * @param onConfirm   callback for confirm action
     * @param onCancel    callback for cancel action
     */
    public ChatConfirm(Player player, String title, String description, String confirmText, String cancelText, Runnable onConfirm, Runnable onCancel) {
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
