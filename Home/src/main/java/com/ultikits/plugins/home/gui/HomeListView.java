package com.ultikits.plugins.home.gui;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.ViewType;
import com.ultikits.ultitools.proxy.InventoryProxy;
import com.ultikits.ultitools.proxy.ItemStackProxy;
import com.ultikits.ultitools.manager.ViewManager;
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
        Optional<HomeService> service = UltiToolsPlugin.getPluginManager().getService(HomeService.class);
        if (!service.isPresent()) {
            throw new RuntimeException("未找到家服务！");
        }
        home = service.get();
    }

    private HomeListView() {
    }

    public static InventoryProxy setUp(Player player) {
        InventoryProxy inventoryProxy = new InventoryProxy(player, 36, PluginMain.getPluginMain().i18n("====家列表===="), true);
        inventoryProxy.presetPage(ViewType.PREVIOUS_QUIT_NEXT);
        inventoryProxy.create();
        PluginMain.getViewManager().registerView(inventoryProxy);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (ItemStackProxy home : setUpItems(player)) {
                    inventoryProxy.addItem(home);
                }
            }
        }.runTaskAsynchronously(UltiTools.getInstance());
        return inventoryProxy;
    }

    private static List<ItemStackProxy> setUpItems(Player player) {
        List<ItemStackProxy> itemStackProxies = new ArrayList<>();
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
            ItemStackProxy itemStackProxy = new ItemStackProxy(UltiTools.getInstance().getVersionWrapper().getBed(beds.get(i)), lore, homeName);
            itemStackProxies.add(itemStackProxy);
        }
        return itemStackProxies;
    }
}
