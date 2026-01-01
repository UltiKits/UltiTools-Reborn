package com.ultikits.ultitools.abstracts.gui;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

/**
 * Base class for confirmation dialogs with OK and Cancel buttons.
 * <p>
 * 带有确定和取消按钮的确认对话框基类。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public abstract class BaseConfirmationPage extends BaseInventoryPage {
    
    /**
     * Default column for the cancel button
     */
    protected static final int CANCEL_BUTTON_COLUMN = 3;
    
    /**
     * Default column for the OK button
     */
    protected static final int OK_BUTTON_COLUMN = 5;
    
    public BaseConfirmationPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
    }
    
    public BaseConfirmationPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }
    
    public BaseConfirmationPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
    }
    
    public BaseConfirmationPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }
    
    @Override
    protected final void setupContent(InventoryOpenEvent event) {
        // Setup custom content
        setupDialogContent(event);
        
        // Setup OK/Cancel buttons
        setupConfirmationButtons();
    }
    
    /**
     * Sets up the dialog content. Override to add custom content.
     * <p>
     * 设置对话框内容。重写以添加自定义内容。
     *
     * @param event the inventory open event
     */
    protected void setupDialogContent(InventoryOpenEvent event) {
        // Override in subclass to add custom content
    }
    
    /**
     * Sets up the OK and Cancel buttons.
     * <p>
     * 设置确定和取消按钮。
     */
    protected void setupConfirmationButtons() {
        // Cancel button
        Icon cancelButton = createCancelButton();
        addToBottomRow(CANCEL_BUTTON_COLUMN, cancelButton);
        
        // OK button
        Icon okButton = createOkButton();
        addToBottomRow(OK_BUTTON_COLUMN, okButton);
    }
    
    /**
     * Creates the OK button.
     * Override to customize.
     * <p>
     * 创建确定按钮。
     * 重写以自定义。
     *
     * @return the OK button icon
     */
    protected Icon createOkButton() {
        return createActionButton(Colors.GREEN, coloredMsg(getOkButtonName()), e -> {
            onConfirm(e);
            player.closeInventory();
        });
    }
    
    /**
     * Creates the Cancel button.
     * Override to customize.
     * <p>
     * 创建取消按钮。
     * 重写以自定义。
     *
     * @return the Cancel button icon
     */
    protected Icon createCancelButton() {
        return createActionButton(Colors.RED, coloredMsg(getCancelButtonName()), e -> {
            onCancel(e);
            player.closeInventory();
        });
    }
    
    /**
     * Gets the OK button display name.
     * Override to customize.
     * <p>
     * 获取确定按钮的显示名称。
     * 重写以自定义。
     *
     * @return the OK button name
     */
    protected String getOkButtonName() {
        return UltiTools.getInstance().i18n("OK");
    }
    
    /**
     * Gets the Cancel button display name.
     * Override to customize.
     * <p>
     * 获取取消按钮的显示名称。
     * 重写以自定义。
     *
     * @return the Cancel button name
     */
    protected String getCancelButtonName() {
        return UltiTools.getInstance().i18n("取消");
    }
    
    /**
     * Called when the OK button is clicked.
     * <p>
     * 当点击确定按钮时调用。
     *
     * @param event the click event
     */
    protected abstract void onConfirm(InventoryClickEvent event);
    
    /**
     * Called when the Cancel button is clicked.
     * Default implementation does nothing.
     * <p>
     * 当点击取消按钮时调用。
     * 默认实现不做任何事情。
     *
     * @param event the click event
     */
    protected void onCancel(InventoryClickEvent event) {
        // Override in subclass if needed
    }
    
    /**
     * Builder for creating confirmation dialogs.
     * <p>
     * 用于创建确认对话框的构建器。
     */
    public static class Builder {
        private final Player player;
        private String id = "confirmation";
        private String title = "Confirm";
        private int rows = 3;
        private java.util.function.Consumer<InventoryClickEvent> onConfirm;
        private java.util.function.Consumer<InventoryClickEvent> onCancel;
        private java.util.function.Consumer<InventoryOpenEvent> contentSetup;
        private String okName;
        private String cancelName;
        
        public Builder(@NotNull Player player) {
            this.player = player;
        }
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder title(String title) {
            this.title = title;
            return this;
        }
        
        public Builder rows(int rows) {
            this.rows = rows;
            return this;
        }
        
        public Builder onConfirm(java.util.function.Consumer<InventoryClickEvent> handler) {
            this.onConfirm = handler;
            return this;
        }
        
        public Builder onCancel(java.util.function.Consumer<InventoryClickEvent> handler) {
            this.onCancel = handler;
            return this;
        }
        
        public Builder content(java.util.function.Consumer<InventoryOpenEvent> setup) {
            this.contentSetup = setup;
            return this;
        }
        
        public Builder okButton(String name) {
            this.okName = name;
            return this;
        }
        
        public Builder cancelButton(String name) {
            this.cancelName = name;
            return this;
        }
        
        public BaseConfirmationPage build() {
            return new BaseConfirmationPage(player, id, title, rows) {
                @Override
                protected void setupDialogContent(InventoryOpenEvent event) {
                    if (contentSetup != null) {
                        contentSetup.accept(event);
                    }
                }
                
                @Override
                protected void onConfirm(InventoryClickEvent event) {
                    if (onConfirm != null) {
                        onConfirm.accept(event);
                    }
                }
                
                @Override
                protected void onCancel(InventoryClickEvent event) {
                    if (onCancel != null) {
                        onCancel.accept(event);
                    }
                }
                
                @Override
                protected String getOkButtonName() {
                    return okName != null ? okName : super.getOkButtonName();
                }
                
                @Override
                protected String getCancelButtonName() {
                    return cancelName != null ? cancelName : super.getCancelButtonName();
                }
            };
        }
        
        public void open() {
            build().open();
        }
    }
    
    /**
     * Creates a new builder for confirmation dialogs.
     * <p>
     * 创建确认对话框的新构建器。
     *
     * @param player the player to show the dialog to
     * @return a new builder
     */
    public static Builder builder(@NotNull Player player) {
        return new Builder(player);
    }
}
