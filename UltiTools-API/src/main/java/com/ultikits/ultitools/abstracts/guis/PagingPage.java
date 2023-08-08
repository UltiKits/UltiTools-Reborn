package com.ultikits.ultitools.abstracts.guis;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import mc.obliviate.inventory.pagination.PaginationManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.ultikits.ultitools.utils.MessageUtils.info;
import static com.ultikits.ultitools.utils.MessageUtils.warning;

public abstract class PagingPage extends Gui {
    private final PaginationManager paginationManager = new PaginationManager(this);

    public PagingPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
        init();
    }

    public PagingPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        init();
    }

    public PagingPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
        init();
    }

    public PagingPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        init();
    }

    private void init() {
        this.paginationManager.registerPageSlotsBetween(0, getLastSlot() - 9);
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        Icon lastRowBackground = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GRAY));
        lastRowBackground.setName(" ");
        this.fillRow(lastRowBackground, getSize() / 9 - 1);
        Icon next = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN));
        next.setName(info(UltiTools.getInstance().i18n("下一页")));
        if (this.paginationManager.isLastPage()) {
            next.setLore(warning(UltiTools.getInstance().i18n("已经是最后一页了")));
        }
        next.onClick((e) -> {
            if (this.paginationManager.isLastPage()) {
                return;
            }
            this.paginationManager.goNextPage();
        });
        Icon last = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN));
        last.setName(info(UltiTools.getInstance().i18n("上一页")));
        if (this.paginationManager.isFirstPage()) {
            last.setLore(warning(UltiTools.getInstance().i18n("已经是第一页了")));
        }
        last.onClick((e) -> {
            if (this.paginationManager.isFirstPage()) {
                return;
            }
            this.paginationManager.goPreviousPage();
        });
        this.addItem(getLastSlot() - 3, next);
        this.addItem(getLastSlot() - 5, last);
    }

    public void updateItems() {
        paginationManager.getItems().clear();
        for (Icon icon : setAllItems()) {
            paginationManager.addItem(icon);
        }
        paginationManager.update();
    }

    public abstract List<Icon> setAllItems();
}
