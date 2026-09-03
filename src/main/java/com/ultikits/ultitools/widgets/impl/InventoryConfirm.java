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
 *
 * <p><strong>Example Usage:</strong></p>
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
    /** Custom confirm button text, null uses BaseConfirmationPage default */
    private String confirmText;
    /** Custom cancel button text, null uses BaseConfirmationPage default */
    private String cancelText;

    /** Callback executed when player clicks OK button */
    private final Runnable onConfirmCallback;
    /** Callback executed when player clicks Cancel button */
    private final Runnable onCancelCallback;

    /** The unique GUI identifier */
    private static final String GUI_ID = "confirm_gui";
    /** The number of rows in the GUI (3 rows = 27 slots) */
    private static final int GUI_ROWS = 3;

    /**
     * Creates an inventory confirmation dialog with default button text.
     *
     * @param player      the target player
     * @param title       the dialog title
     * @param description the dialog description (appended to title)
     * @param onConfirm   callback for confirm action
     * @param onCancel    callback for cancel action
     */
    public InventoryConfirm(Player player, String title, String description, Runnable onConfirm, Runnable onCancel) {
        super(player, GUI_ID, title + " - " + description, GUI_ROWS);
        this.onConfirmCallback = onConfirm;
        this.onCancelCallback = onCancel;
    }

    /**
     * Creates an inventory confirmation dialog with custom button text.
     *
     * @param player      the target player
     * @param title       the dialog title
     * @param description the dialog description (appended to title)
     * @param confirmText custom confirm button text
     * @param cancelText  custom cancel button text
     * @param onConfirm   callback for confirm action
     * @param onCancel    callback for cancel action
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
