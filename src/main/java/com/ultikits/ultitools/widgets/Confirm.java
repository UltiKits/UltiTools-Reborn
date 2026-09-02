package com.ultikits.ultitools.widgets;

import org.bukkit.entity.Player;

import com.ultikits.ultitools.widgets.impl.ChatConfirm;
import com.ultikits.ultitools.widgets.impl.InventoryConfirm;

public interface Confirm {
    /**
     * Show the confirm dialog.
     */
    void show();
    /**
     * Get the confirm text.
     */
    String getConfirmText();
    /**
     * Get the cancel text.
     */
    String getCancelText();

    /**
     * Create a confirm dialog with GUI.
     *
     * @param player     Player
     * @param title      Title
     * @param description Description
     * @param onConfirm  On confirm
     * @param onCancel   On cancel
     * @return Confirm
     */
    static Confirm gui(Player player, String title, String description, Runnable onConfirm, Runnable onCancel) {
        return new InventoryConfirm(player, title, description, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with GUI.
     * You can set the confirm and cancel text.
     *
     * @param player      Player
     * @param title       Title
     * @param description Description
     * @param confirmText Confirm text
     * @param cancelText  Cancel text
     * @param onConfirm   On confirm
     * @param onCancel    On cancel
     * @return Confirm
     */
    static Confirm gui(Player player, String title, String description, String confirmText, String cancelText, Runnable onConfirm, Runnable onCancel) {
        return new InventoryConfirm(player, title, description, confirmText, cancelText, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with chat.
     *
     * @param player     Player
     * @param title      Title
     * @param description Description
     * @param onConfirm  On confirm
     * @param onCancel   On cancel
     * @return Confirm
     */
    static Confirm chat(Player player, String title, String description, Runnable onConfirm, Runnable onCancel) {
        return new ChatConfirm(player, title, description, onConfirm, onCancel);
    }

    /**
     * Create a confirm dialog with chat.
     * You can set the confirm and cancel text.
     *
     * @param player      Player
     * @param title       Title
     * @param description Description
     * @param confirmText Confirm text
     * @param cancelText  Cancel text
     * @param onConfirm   On confirm
     * @param onCancel    On cancel
     * @return Confirm
     */
    static Confirm chat(Player player, String title, String description, String confirmText, String cancelText, Runnable onConfirm, Runnable onCancel) {
        return new ChatConfirm(player, title, description, confirmText, cancelText, onConfirm, onCancel);
    }
}
