package com.ultikits.plugins.kits.gui;

import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.EconomyUtils;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for browsing and claiming kits.
 * 礼包浏览界面。
 */
public class KitBrowserGui extends Gui {

    private final Player player;
    private final UltiToolsPlugin plugin;
    private final KitService kitService;
    private final int page;
    private final int kitsPerPage;
    private long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 200;

    public KitBrowserGui(Player player, UltiToolsPlugin plugin, KitService kitService, int page) {
        super(player, "kit_browser_" + page,
                ChatColor.translateAlternateColorCodes('&', "&6&l" + plugin.i18n("礼包列表")),
                6);
        this.player = player;
        this.plugin = plugin;
        this.kitService = kitService;
        this.page = page;
        this.kitsPerPage = 28;
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        List<KitDefinition> availableKits = kitService.getAvailableKits(player);
        int totalPages = Math.max(1, (int) Math.ceil((double) availableKits.size() / kitsPerPage));
        int startIndex = page * kitsPerPage;
        int endIndex = Math.min(startIndex + kitsPerPage, availableKits.size());

        // Fill separator row (row 5, slots 36-44)
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(" ");
            glass.setItemMeta(glassMeta);
        }
        for (int i = 36; i <= 44; i++) {
            Icon separator = new Icon(glass);
            separator.onClick(e -> e.setCancelled(true));
            addItem(i, separator);
        }

        // Add kit items (slots 0-35, up to 28 per page based on kitsPerPage)
        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            if (slot >= 36) break;

            KitDefinition kit = availableKits.get(i);
            Icon kitIcon = buildKitIcon(kit);
            addItem(slot, kitIcon);
        }

        // Navigation - Previous page (slot 45)
        if (page > 0) {
            ItemStack prevItem = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevItem.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(ChatColor.YELLOW + plugin.i18n("上一页"));
                prevItem.setItemMeta(prevMeta);
            }
            Icon prevIcon = new Icon(prevItem);
            prevIcon.onClick(e -> {
                e.setCancelled(true);
                player.closeInventory();
                org.bukkit.plugin.Plugin ultiTools = Bukkit.getPluginManager().getPlugin("UltiTools");
                if (ultiTools != null) {
                    Bukkit.getScheduler().runTask(ultiTools, () ->
                            new KitBrowserGui(player, plugin, kitService, page - 1).open());
                }
            });
            addItem(45, prevIcon);
        }

        // Page indicator (slot 49)
        ItemStack pageItem = new ItemStack(Material.PAPER);
        ItemMeta pageMeta = pageItem.getItemMeta();
        if (pageMeta != null) {
            pageMeta.setDisplayName(ChatColor.WHITE + String.format(plugin.i18n("第 %d/%d 页"), page + 1, totalPages));
            pageItem.setItemMeta(pageMeta);
        }
        Icon pageIcon = new Icon(pageItem);
        pageIcon.onClick(e -> e.setCancelled(true));
        addItem(49, pageIcon);

        // Navigation - Next page (slot 53)
        if (page < totalPages - 1) {
            ItemStack nextItem = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextItem.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(ChatColor.YELLOW + plugin.i18n("下一页"));
                nextItem.setItemMeta(nextMeta);
            }
            Icon nextIcon = new Icon(nextItem);
            nextIcon.onClick(e -> {
                e.setCancelled(true);
                player.closeInventory();
                org.bukkit.plugin.Plugin ultiTools = Bukkit.getPluginManager().getPlugin("UltiTools");
                if (ultiTools != null) {
                    Bukkit.getScheduler().runTask(ultiTools, () ->
                            new KitBrowserGui(player, plugin, kitService, page + 1).open());
                }
            });
            addItem(53, nextIcon);
        }
    }

    Icon buildKitIcon(KitDefinition kit) {
        Material material;
        try {
            material = Material.valueOf(kit.getIcon().toUpperCase());
        } catch (IllegalArgumentException e) {
            material = Material.CHEST;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Display name
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', kit.getDisplayName()));

            // Build lore
            List<String> lore = new ArrayList<>();

            // Description lines
            for (String desc : kit.getDescription()) {
                lore.add(ChatColor.translateAlternateColorCodes('&', desc));
            }

            if (!kit.getDescription().isEmpty()) {
                lore.add("");
            }

            // Price
            if (kit.isFree()) {
                lore.add(ChatColor.GRAY + plugin.i18n("价格") + ": " + ChatColor.GREEN + plugin.i18n("免费"));
            } else {
                String priceStr = EconomyUtils.isAvailable() ? EconomyUtils.format(kit.getPrice()) : String.valueOf(kit.getPrice());
                lore.add(ChatColor.GRAY + plugin.i18n("价格") + ": " + ChatColor.GOLD + priceStr);
            }

            // Level requirement
            if (kit.hasLevelRequirement()) {
                lore.add(ChatColor.GRAY + plugin.i18n("等级要求") + ": " + ChatColor.YELLOW + kit.getLevelRequired());
            }

            // Status
            lore.add("");
            lore.add(ChatColor.GRAY + plugin.i18n("状态") + ": " + getStatusText(kit));

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        Icon icon = new Icon(item);
        icon.onClick(e -> {
            e.setCancelled(true);
            handleKitClick(kit);
        });

        return icon;
    }

    void handleKitClick(KitDefinition kit) {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            return;
        }
        lastClickTime = now;

        KitService.ClaimResult result = kitService.claimKit(player, kit.getName());

        switch (result) {
            case SUCCESS:
                player.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("成功领取礼包: %s"), kit.getDisplayName()));
                if (!kit.isFree() && EconomyUtils.isAvailable()) {
                    player.sendMessage(ChatColor.YELLOW + String.format(plugin.i18n("已扣除 %s"), EconomyUtils.format(kit.getPrice())));
                }
                player.closeInventory();
                break;
            case NOT_FOUND:
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包 '%s' 不存在"), kit.getName()));
                break;
            case NO_PERMISSION:
                player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限使用此礼包"));
                break;
            case INSUFFICIENT_LEVEL:
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("等级不足，需要 %d 级"), kit.getLevelRequired()));
                break;
            case INSUFFICIENT_FUNDS:
                player.sendMessage(ChatColor.RED + plugin.i18n("余额不足"));
                break;
            case ALREADY_CLAIMED:
                player.sendMessage(ChatColor.RED + plugin.i18n("你已经领取过此礼包"));
                break;
            case ON_COOLDOWN:
                long remaining = kitService.getRemainingCooldown(player, kit);
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("礼包冷却中，剩余: %s"), kitService.formatCooldown(remaining)));
                break;
            case INVENTORY_FULL:
                player.sendMessage(ChatColor.RED + plugin.i18n("背包空间不足"));
                break;
            case EMPTY_KIT:
                player.sendMessage(ChatColor.RED + plugin.i18n("礼包内容为空"));
                break;
            default:
                player.sendMessage(ChatColor.RED + plugin.i18n("领取礼包时发生错误"));
                break;
        }
    }

    String getStatusText(KitDefinition kit) {
        // Check level
        if (kit.hasLevelRequirement() && player.getLevel() < kit.getLevelRequired()) {
            return ChatColor.RED + plugin.i18n("等级不足");
        }

        // Check economy
        if (!kit.isFree()) {
            if (!EconomyUtils.isAvailable() || !EconomyUtils.has(player, kit.getPrice())) {
                return ChatColor.RED + plugin.i18n("余额不足");
            }
        }

        // Check cooldown / one-time
        long remaining = kitService.getRemainingCooldown(player, kit);
        if (remaining < 0) {
            return ChatColor.RED + plugin.i18n("已领取");
        }
        if (remaining > 0) {
            return ChatColor.YELLOW + plugin.i18n("冷却中") + ": " + kitService.formatCooldown(remaining);
        }

        return ChatColor.GREEN + plugin.i18n("可领取");
    }
}
