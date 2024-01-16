package com.ultikits.ultitools.abstracts.guis;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import static com.ultikits.ultitools.utils.MessageUtils.coloredMsg;

public abstract class OkCancelPage extends Gui {
    public OkCancelPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
    }

    public OkCancelPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }

    public OkCancelPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
    }

    public OkCancelPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        Icon lastRowBackground = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GRAY));
        lastRowBackground.setName(" ");
        this.fillRow(lastRowBackground, getSize() / 9 - 1);
        Icon ok = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN));
        ok.setName(coloredMsg(UltiTools.getInstance().i18n("OK")));
        ok.onClick((e) -> {
            onOk(e);
            player.closeInventory();
        });
        Icon cancel = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED));
        cancel.setName(coloredMsg(UltiTools.getInstance().i18n("取消")));
        cancel.onClick((e) -> {
            onCancel(e);
            player.closeInventory();
        });
        this.addItem(getLastSlot() - 3, ok);
        this.addItem(getLastSlot() - 5, cancel);
    }

    public abstract void onOk(InventoryClickEvent clickEvent);

    public abstract void onCancel(InventoryClickEvent clickEvent);
}
