package com.ultikits.ultitools.abstracts;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.CancelResult;
import com.ultikits.ultitools.proxy.InventoryProxy;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.NoSuchElementException;

public abstract class AbstractPagesListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        InventoryProxy inventoryProxy = UltiTools.getInstance().getViewManager().getViewByInventory(event.getClickedInventory());
        if (inventoryProxy == null) {
            return;
        }
        if (event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD || event.getAction() == InventoryAction.HOTBAR_SWAP) {
            event.setResult(Event.Result.DENY);
            return;
        }
        if (clicked != null && clicked.getType() != Material.AIR) {
            if (event.getSlot() < inventoryProxy.getSize() && !inventoryProxy.isBackGround(clicked)) {
                CancelResult cancelled = onItemClick(event, player, inventoryProxy, clicked);
                cancelEvent(event, cancelled);
            } else if (clicked.getItemMeta() != null) {
                if (!inventoryProxy.isLastLineDisabled()) {
                    return;
                }
                if (inventoryProxy.isBackGround(clicked)) {
                    event.setResult(Event.Result.DENY);
                    return;
                }
                String itemName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
                if (itemName.equals(UltiTools.getInstance().i18n("下一页"))) {
                    event.setResult(Event.Result.DENY);
                    InventoryProxy nextInventory = inventoryProxy.getNextPage();
                    if (nextInventory != null) {
                        player.openInventory(nextInventory.getInventory());
                    }
                } else if (itemName.equals(UltiTools.getInstance().i18n("退出"))) {
                    event.setResult(Event.Result.DENY);
                    UltiTools.getInstance().getViewManager().closeAllViews(player);
                } else if (itemName.equals(UltiTools.getInstance().i18n("上一页")) ||
                        itemName.equals(UltiTools.getInstance().i18n("返回")) ||
                        itemName.equals(UltiTools.getInstance().i18n("确认")) ||
                        itemName.equals(UltiTools.getInstance().i18n("取消"))) {
                    event.setResult(Event.Result.DENY);
                    try {
                        UltiTools.getInstance().getViewManager().openLastView(player);
                    }catch (NoSuchElementException ignored){
                    }
                } else {
                    CancelResult cancelled = onItemClick(event, player, inventoryProxy, clicked);
                    cancelEvent(event, cancelled);
                }
            }
        }
    }

    private void cancelEvent(InventoryClickEvent event, CancelResult cancelled) {
        switch (cancelled) {
            case TRUE:
                event.setCancelled(true);
                break;
            case FALSE:
                event.setCancelled(false);
                break;
            case NONE:
                break;
        }
    }

    /**
     * You need to complete the event when item clicked.
     * You don't need to worry about the last line if you create the page with preset page.
     * 你需要实现当物品被点击后的方法。
     * 你不必处理最后一行的点击事件如果此界面是你使用预设界面创建的。
     *
     * @param event            InventoryClickEvent
     * @param player           Player who clicked the item
     * @param inventoryProxy The InventoryProxy that response to this inventory
     * @param clickedItem      the item that been clicked
     * @return 是否需要被取消点击事件 {@link CancelResult}
     */
    public abstract CancelResult onItemClick(InventoryClickEvent event, Player player, InventoryProxy inventoryProxy, ItemStack clickedItem);

}
