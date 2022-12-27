package com.ultikits.plugins.home.gui;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.enums.Colors;
import com.ultikits.inventoryapi.InventoryManager;
import com.ultikits.inventoryapi.ViewManager;
import com.ultikits.inventoryapi.ViewType;
import com.ultikits.manager.ItemStackManager;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.PluginManager;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class HomeListView {

    private static final List<Colors> beds = Arrays.asList(Colors.values());
    private static final HomeService home;

    static {
        Optional<HomeService> service = PluginManager.getService(HomeService.class);
        if (!service.isPresent()) {
            throw new RuntimeException("未找到家服务！");
        }
        home = service.get();
    }

    private HomeListView() {
    }

    public static Inventory setUp(Player player) {
        InventoryManager inventoryManager = new InventoryManager(null, 36, player.getName() + PluginMain.getPluginMain().i18n("的家列表"), true);
        inventoryManager.presetPage(ViewType.PREVIOUS_QUIT_NEXT);
        inventoryManager.create();
        ViewManager.registerView(inventoryManager);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ItemStackManager home : setUpItems(player)) {
                    inventoryManager.addItem(home);
                }
            }
        }.runTaskAsynchronously(UltiTools.getInstance());
        return inventoryManager.getInventory();
    }

    private static List<ItemStackManager> setUpItems(Player player) {
        List<ItemStackManager> itemStackManagers = new ArrayList<>();
        List<HomeEntity> homeList = home.getHomeList(player.getUniqueId());

        for (HomeEntity each : homeList) {
            List<String> lore = new ArrayList<>();
            String homeName = each.getName();
            Location location = each.getHomeLocation();
            String world = String.format(ChatColor.YELLOW + PluginMain.getPluginMain().i18n("所在世界 %s"), location.getWorld().getName());
            String xyz = String.format(ChatColor.GRAY + "X: %.2f Y: %.2f Z: %.2f", location.getX(), location.getY(), location.getZ());
            lore.add(world);
            lore.add(xyz);
            Random random = new Random();
            int i = random.nextInt(beds.size());
            ItemStackManager itemStackManager = new ItemStackManager(UltiTools.getInstance().getVersionWrapper().getBed(beds.get(i)), lore, homeName);
            itemStackManagers.add(itemStackManager);
        }
        return itemStackManagers;
    }
}
