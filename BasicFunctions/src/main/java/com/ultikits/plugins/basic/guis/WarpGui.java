package com.ultikits.plugins.basic.guis;

import com.ultikits.plugins.basic.BasicFunctions;
import com.ultikits.plugins.basic.data.WarpData;
import com.ultikits.plugins.basic.services.WarpService;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.guis.PagingPage;
import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class WarpGui extends PagingPage {
    private final WarpService warpService = new WarpService();

    public WarpGui(Player player) {
        super(
                player,
                "Warp-list",
                Component.text(BasicFunctions.getInstance().i18n("传送点列表"))
                        .color(TextColor.color(0xFF00A6)),
                3
        );
    }

    @Override
    public List<Icon> setAllItems() {
        List<Icon> icons = new ArrayList<>();
        List<WarpData> allWarps = warpService.getAllWarps();
        for (WarpData warpData : allWarps) {
            Location location = WarpService.toLocation(warpData.getLocation());
            Icon icon = new Icon(UltiTools.getInstance().getVersionWrapper().getEndEye());
            TextComponent textComponent = Component.text(warpData.getName()).color(TextColor.color(0xFF00A6));
            icon.toComp().setName(textComponent);
            String world = String.format(ChatColor.YELLOW + BasicFunctions.getInstance().i18n("所在世界 %s"), location.getWorld().getName());
            String xyz = String.format(ChatColor.GRAY + "X: %.2f Y: %.2f Z: %.2f", location.getX(), location.getY(), location.getZ());
            icon.setLore(world, xyz);
            icon.onClick((e) -> {
                player.performCommand("warp tp " + warpData.getName());
                player.closeInventory();
            });
            icons.add(icon);
        }
        return icons;
    }
}
