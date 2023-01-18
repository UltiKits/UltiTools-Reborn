package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.proxy.InventoryProxy;
import lombok.SneakyThrows;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.*;

/**
 * ViewManager Utils
 * ViewManager 工具类
 */
public class ViewManager {
    private final Map<String, InventoryProxy> uuidViewsMap = new HashMap<>();
    private final Map<Inventory, InventoryProxy> inventoryViewsMap = new HashMap<>();
    private final Map<UUID, LinkedList<InventoryProxy>> linkedInventoryMap = new HashMap<>();
    private final Map<UUID, Map<String, List<InventoryProxy>>> playerGroupInventoryMap = new HashMap<>();

    /**
     * Register view.
     *
     * @param inventoryProxy the inventory manager
     */
    public void registerView(InventoryProxy inventoryProxy) {
        uuidViewsMap.put(inventoryProxy.getUuid(), inventoryProxy);
        inventoryViewsMap.put(inventoryProxy.getInventory(), inventoryProxy);
        Map<String, List<InventoryProxy>> groupInventoryProxyMap = playerGroupInventoryMap.get(inventoryProxy.getOwner().getUniqueId());
        if (groupInventoryProxyMap == null) {
            groupInventoryProxyMap = new HashMap<>();
            ArrayList<InventoryProxy> list = new ArrayList<>();
            list.add(inventoryProxy);
            groupInventoryProxyMap.put(inventoryProxy.getGroupTitle(), list);
        } else {
            List<InventoryProxy> inventoryProxies = groupInventoryProxyMap.get(inventoryProxy.getGroupTitle());
            if (inventoryProxies == null) {
                inventoryProxies = new ArrayList<>();
            }
            if (!inventoryProxies.contains(inventoryProxy)) {
                inventoryProxies.add(inventoryProxy);
            }
            groupInventoryProxyMap.put(inventoryProxy.getGroupTitle(), inventoryProxies);
        }
        playerGroupInventoryMap.put(inventoryProxy.getOwner().getUniqueId(), groupInventoryProxyMap);
    }


    @SneakyThrows
    public void unregisterView(Player player, InventoryProxy inventoryProxy) {
        if (inventoryProxy == null){
            return;
        }
        LinkedList<InventoryProxy> inventoryProxies = linkedInventoryMap.get(player.getUniqueId());
        if (inventoryProxies != null && !inventoryProxies.isEmpty()) {
            if (inventoryProxies.contains(inventoryProxy)){
                return;
            }
        }
        uuidViewsMap.remove(inventoryProxy.getUuid());
        inventoryViewsMap.remove(inventoryProxy.getInventory());
        Map<String, List<InventoryProxy>> stringListMap = playerGroupInventoryMap.get(player.getUniqueId());
        if (stringListMap != null) {
            List<InventoryProxy> inventoryProxies1 = stringListMap.get(inventoryProxy.getGroupTitle());
            if (inventoryProxies1 != null && inventoryProxies1.size() != 0) {
                inventoryProxies1.remove(inventoryProxy);
            }
        }
    }

    /**
     * Gets view by name.
     *
     * @param title the title
     * @return the view by name
     */
    public InventoryProxy getViewByUUID(String title) {
        return uuidViewsMap.get(title);
    }

    /**
     * Gets view by inventory.
     *
     * @param inventory the inventory
     * @return the view by inventory
     */
    public InventoryProxy getViewByInventory(Inventory inventory) {
        return inventoryViewsMap.get(inventory);
    }

    /**
     * Open inventory for player.
     *
     * @param player the player
     */
    public void openView(Player player, InventoryProxy inventoryProxy) {
        if (inventoryProxy == null){
            return;
        }
        LinkedList<InventoryProxy> inventoryFactories = linkedInventoryMap.get(player.getUniqueId());
        if (inventoryFactories == null){
            inventoryFactories = new LinkedList<>();
        }else if (inventoryFactories.contains(inventoryProxy)) {
            return;
        }
        inventoryFactories.add(inventoryProxy);
        linkedInventoryMap.put(player.getUniqueId(), inventoryFactories);
    }

    public void closeView(Player player, InventoryProxy inventoryProxy) {
        if (inventoryProxy == null){
            return;
        }
        unregisterView(player, inventoryProxy);
    }

    public void openLastView(Player player) {
        LinkedList<InventoryProxy> inventoryFactories = linkedInventoryMap.get(player.getUniqueId());
        if (inventoryFactories == null || inventoryFactories.size() <= 1) {
            return;
        }
        inventoryFactories.removeLast();
        InventoryProxy last = inventoryFactories.getLast();
        player.openInventory(last.getInventory());
    }

    public void closeAllViews(Player player) {
        player.closeInventory();
        linkedInventoryMap.put(player.getUniqueId(), new LinkedList<>());
    }

    public boolean clickOnValidView(Player player, InventoryProxy inventoryProxy, String groupName) {
        if (!inventoryProxy.getGroupTitle().equals(groupName)) return false;
        Map<String, List<InventoryProxy>> stringListMap = playerGroupInventoryMap.get(player.getUniqueId());
        if (stringListMap == null) {
            return false;
        }
        List<InventoryProxy> inventoryProxies = stringListMap.get(groupName);
        if (inventoryProxies == null || inventoryProxies.size() == 0) {
            return false;
        }
        return inventoryProxies.contains(inventoryProxy);
    }

}
