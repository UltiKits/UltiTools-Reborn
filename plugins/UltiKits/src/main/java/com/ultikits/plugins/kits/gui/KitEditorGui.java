package com.ultikits.plugins.kits.gui;

import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GUI for editing kit contents.
 * 礼包内容编辑界面。
 */
public class KitEditorGui extends Gui {

    private static final int SAVE_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int CANCEL_SLOT = 53;
    private static final int ITEM_SLOTS = 45; // Slots 0-44 for items

    private final Player player;
    private final UltiToolsPlugin plugin;
    private final KitService kitService;
    private final KitDefinition kit;

    public KitEditorGui(Player player, UltiToolsPlugin plugin, KitService kitService, KitDefinition kit) {
        super(player, "kit_editor_" + kit.getName(),
                ChatColor.translateAlternateColorCodes('&', "&6&l" + ChatColor.stripColor(
                        ChatColor.translateAlternateColorCodes('&', kit.getDisplayName()))),
                6);
        this.player = player;
        this.plugin = plugin;
        this.kitService = kitService;
        this.kit = kit;
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        // Pre-fill with existing kit items
        if (kit.hasItems()) {
            ItemStack[] existingItems = kitService.deserializeItems(kit.getItems());
            if (existingItems != null) {
                for (int i = 0; i < existingItems.length && i < ITEM_SLOTS; i++) {
                    if (existingItems[i] != null && existingItems[i].getType() != Material.AIR) {
                        // Place directly in inventory without Icon wrapper so items are moveable
                        event.getInventory().setItem(i, existingItems[i].clone());
                    }
                }
            }
        }

        // Row 6 control buttons

        // Save button (slot 45)
        ItemStack saveItem = new ItemStack(Material.EMERALD);
        ItemMeta saveMeta = saveItem.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setDisplayName(ChatColor.GREEN + plugin.i18n("保存"));
            saveMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Click to save kit contents"));
            saveItem.setItemMeta(saveMeta);
        }
        Icon saveIcon = new Icon(saveItem);
        saveIcon.onClick(e -> {
            e.setCancelled(true);
            handleSave();
        });
        addItem(SAVE_SLOT, saveIcon);

        // Info button (slot 49)
        ItemStack infoItem = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(ChatColor.GOLD + kit.getName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', kit.getDisplayName()));
            for (String desc : kit.getDescription()) {
                lore.add(ChatColor.translateAlternateColorCodes('&', desc));
            }
            infoMeta.setLore(lore);
            infoItem.setItemMeta(infoMeta);
        }
        Icon infoIcon = new Icon(infoItem);
        infoIcon.onClick(e -> e.setCancelled(true));
        addItem(INFO_SLOT, infoIcon);

        // Cancel button (slot 53)
        ItemStack cancelItem = new ItemStack(Material.BARRIER);
        ItemMeta cancelMeta = cancelItem.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(ChatColor.RED + plugin.i18n("取消"));
            cancelItem.setItemMeta(cancelMeta);
        }
        Icon cancelIcon = new Icon(cancelItem);
        cancelIcon.onClick(e -> {
            e.setCancelled(true);
            player.closeInventory();
        });
        addItem(CANCEL_SLOT, cancelIcon);

        // Fill remaining control row slots with glass
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
        }
        for (int i = 45; i <= 53; i++) {
            if (i != SAVE_SLOT && i != INFO_SLOT && i != CANCEL_SLOT) {
                Icon glassIcon = new Icon(glass);
                glassIcon.onClick(e -> e.setCancelled(true));
                addItem(i, glassIcon);
            }
        }
    }

    void handleSave() {
        // Collect items from slots 0-44
        List<ItemStack> collectedItems = new ArrayList<>();
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = player.getOpenInventory().getTopInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                collectedItems.add(item);
            }
        }

        ItemStack[] itemsArray = collectedItems.toArray(new ItemStack[0]);
        boolean success = kitService.saveKitItems(kit.getName(), itemsArray);

        if (success) {
            player.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("已保存礼包: %s"), kit.getName()));
        } else {
            player.sendMessage(ChatColor.RED + plugin.i18n("领取礼包时发生错误"));
        }

        player.closeInventory();
    }
}
