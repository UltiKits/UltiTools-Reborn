package com.ultikits.plugins.home.gui;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.plugins.home.entity.HomeEntity;
import com.ultikits.plugins.home.services.HomeService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.guis.PagingPage;
import com.ultikits.ultitools.entities.Colors;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class HomeListGui extends PagingPage {
    private final List<Colors> beds = Arrays.asList(Colors.values());
    private final HomeService home;

    public HomeListGui(Player player) {
        super(
                player,
                "Home-list",
                Component.text(player.getDisplayName() + PluginMain.getPluginMain().i18n("的家列表"))
                        .color(TextColor.color(0xFF00A6)),
                3
        );
        home = PluginMain.getPluginMain().getContext().getBean(HomeService.class);
    }

    @Override
    public List<Icon> setAllItems() {
        List<Icon> icons = new ArrayList<>();
        List<HomeEntity> homeList = home.getHomeList(player.getUniqueId());
        homeList.sort(Comparator.comparing(HomeEntity::getId));
        int i = 0;
        for (HomeEntity homeEntity : homeList) {
            Location location = homeEntity.getHomeLocation();
            Icon icon = new Icon(UltiTools.getInstance().getVersionWrapper().getBed(beds.get(i)));
            TextComponent textComponent = Component.text(homeEntity.getName()).color(TextColor.color(0xFF00A6));
            icon.toComp().setName(textComponent);
            String world = String.format(ChatColor.YELLOW + PluginMain.getPluginMain().i18n("所在世界 %s"), location.getWorld().getName());
            String xyz = String.format(ChatColor.GRAY + "X: %.2f Y: %.2f Z: %.2f", location.getX(), location.getY(), location.getZ());
            icon.setLore(world, xyz);
            icon.onClick((e) -> {
                player.performCommand("home tp " + homeEntity.getName());
                player.closeInventory();
            });
            icons.add(icon);
            if (i == beds.size() - 1) {
                i = 0;
            } else {
                i++;
            }
        }
        return icons;
    }
}
