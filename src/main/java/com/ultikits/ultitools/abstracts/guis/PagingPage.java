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

/**
 * This abstract class represents a GUI page with pagination.
 * It extends the Gui class from mc.obliviate.inventory package.
 * <p>
 * 这个抽象类代表了一个带有分页的GUI页面。它继承了mc.obliviate.inventory包中的Gui类。
 */
public abstract class PagingPage extends Gui {
    private final PaginationManager paginationManager = new PaginationManager(this);

    /**
     * Constructor with player, id, title and number of rows as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题和行数。
     *
     * @param player The player who will see the GUI. 玩家
     * @param id     The id of the GUI. GUI的ID
     * @param title  The title of the GUI. GUI的标题
     * @param rows   The number of rows in the GUI. GUI的行数
     */
    public PagingPage(@NotNull Player player, @NotNull String id, String title, int rows) {
        super(player, id, title, rows);
        init();
    }

    /**
     * Constructor with player, id, title and inventory type as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题和背包类型。
     *
     * @param player        The player who will see the GUI. 玩家
     * @param id            The id of the GUI. GUI的ID
     * @param title         The title of the GUI. GUI的标题
     * @param inventoryType The type of the inventory. 背包类型
     */
    public PagingPage(@NotNull Player player, @NotNull String id, String title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        init();
    }

    /**
     * Constructor with player, id, title component and number of rows as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题组件和行数。
     *
     * @param player The player who will see the GUI. 玩家
     * @param id     The id of the GUI. GUI的ID
     * @param title  The title of the GUI as a Component. GUI的标题
     * @param rows   The number of rows in the GUI. GUI的行数
     */
    public PagingPage(@NotNull Player player, @NotNull String id, Component title, int rows) {
        super(player, id, title, rows);
        init();
    }

    /**
     * Constructor with player, id, title component and inventory type as parameters.
     * <p>
     * 构造函数，参数为玩家、id、标题组件和背包类型。
     *
     * @param player        The player who will see the GUI. 玩家
     * @param id            The id of the GUI. GUI的ID
     * @param title         The title of the GUI as a Component. GUI的标题
     * @param inventoryType The type of the inventory. 背包类型
     */
    public PagingPage(@NotNull Player player, @NotNull String id, Component title, InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        init();
    }

    /**
     * This method initializes the PaginationManager and registers the page slots.
     * <p>
     * 这个方法初始化了PaginationManager并注册了页面的槽位。
     */
    private void init() {
        this.paginationManager.registerPageSlotsBetween(0, getLastSlot() - 9);
    }

    /**
     * This method is called when the GUI is opened.
     * It updates the items in the GUI.
     * <p>
     * 这个方法在GUI被打开时调用。它更新了GUI中的物品。
     *
     * @param event The InventoryOpenEvent that triggers this method. <p> 触发此方法的InventoryOpenEvent。
     */
    @Override
    public void onOpen(InventoryOpenEvent event) {
        updateItems();
    }

    /**
     * This method updates the items in the GUI.
     * It clears the current items, sets the new items and updates the pagination.
     * <p>
     * 这个方法更新了GUI中的物品。它清除了当前的物品，设置了新的物品并更新了分页。
     */
    private void updateItems() {
        paginationManager.getItems().clear();
        Icon lastRowBackground = new Icon(UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GRAY));
        lastRowBackground.setName(" ");
        this.fillRow(lastRowBackground, getSize() / 9 - 1);
        for (Icon icon : setAllItems()) {
            paginationManager.addItem(icon);
        }
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
            updateItems();
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
            updateItems();
        });
        this.addItem(getLastSlot() - 3, next);
        this.addItem(getLastSlot() - 5, last);
        paginationManager.update();
    }

    /**
     * This method should be overridden in subclasses to provide the items for the GUI.
     * <p>
     * 此方法应该在子类中被重写以提供GUI的物品。
     *
     * @return A list of Icons that represent the items in the GUI. <p> 代表GUI中物品的Icon列表。
     */
    public abstract List<Icon> setAllItems();
}
