package com.ultikits.ultitools.interfaces.impl;

import cn.hutool.core.lang.func.VoidFunc0;
import com.ultikits.ultitools.abstracts.guis.OkCancelPage;
import com.ultikits.ultitools.interfaces.Confirm;
import lombok.SneakyThrows;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public class InventoryConfirm extends OkCancelPage implements Confirm {
    private String confirmText;
    private String cancelText;

    private final VoidFunc0 onConfirm;
    private final VoidFunc0 onCancel;

    private static final String GUI_ID = "confirm_gui";
    private static final int GUI_ROWS = 3;

    public InventoryConfirm(Player player, String title, String description, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        super(player, GUI_ID, title + " - " + description, GUI_ROWS);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    public InventoryConfirm(Player player, String title, String description, String confirmText, String cancelText, VoidFunc0 onConfirm, VoidFunc0 onCancel) {
        this(player, title, description, onConfirm, onCancel);
        this.confirmText = confirmText;
        this.cancelText = cancelText;
    }

    @Override
    public String getOkName() {
        return getConfirmText();
    }

    @Override
    public String getCancelName() {
        return getCancelText();
    }

    @Override
    public String getConfirmText() {
        return confirmText == null ? super.getOkName() : confirmText;
    }

    @Override
    public String getCancelText() {
        return cancelText == null ? super.getCancelName() : cancelText;
    }

    @Override
    public void show() {
        this.open();
    }

    @SneakyThrows
    @Override
    public void onOk(InventoryClickEvent clickEvent) {
        onConfirm.call();
    }

    @SneakyThrows
    @Override
    public void onCancel(InventoryClickEvent clickEvent) {
        onCancel.call();
    }
}
