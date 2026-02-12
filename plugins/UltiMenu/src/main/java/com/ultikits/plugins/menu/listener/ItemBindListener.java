package com.ultikits.plugins.menu.listener;

import com.ultikits.plugins.menu.gui.CustomMenuGui;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Listener for menu item binding events.
 * <p>
 * Listens for player right-click interactions with items that are bound to custom menus.
 * When a player right-clicks with an item matching a menu's bind configuration,
 * the corresponding menu GUI is opened.
 * </p>
 * <p>
 * 监听菜单物品绑定事件。
 * <p>
 * 当玩家右键点击绑定到自定义菜单的物品时，打开对应的菜单GUI。
 * </p>
 *
 * @author wisdomme
 * @since 1.0.0
 */
@EventListener
public class ItemBindListener implements Listener {

    private final UltiToolsPlugin plugin;
    private final MenuService menuService;

    /**
     * Constructor with dependency injection.
     * <p>
     * 构造器依赖注入。
     * </p>
     *
     * @param plugin      the plugin instance / 插件实例
     * @param menuService the menu service / 菜单服务
     */
    public ItemBindListener(UltiToolsPlugin plugin, MenuService menuService) {
        this.plugin = plugin;
        this.menuService = menuService;
    }

    /**
     * Handles player interact events for menu item bindings.
     * <p>
     * 处理玩家交互事件以检测菜单物品绑定。
     * </p>
     *
     * @param event the player interact event / 玩家交互事件
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        // 只处理右键点击
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();

        // Check main hand item first, then off-hand item
        // 先检查主手物品，再检查副手物品
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();
        ItemStack offHandItem = player.getInventory().getItemInOffHand();

        // Try main hand first
        // 先尝试主手
        if (mainHandItem != null && mainHandItem.getType() != Material.AIR) {
            if (tryOpenMenuForItem(player, mainHandItem, event)) {
                return;
            }
        }

        // Try off-hand if main hand didn't match
        // 如果主手没有匹配，尝试副手
        if (offHandItem != null && offHandItem.getType() != Material.AIR) {
            tryOpenMenuForItem(player, offHandItem, event);
        }
    }

    /**
     * Attempts to find and open a menu matching the given item.
     * <p>
     * 尝试查找并打开与给定物品匹配的菜单。
     * </p>
     *
     * @param player the player / 玩家
     * @param item   the item to check / 要检查的物品
     * @param event  the interaction event / 交互事件
     * @return true if a matching menu was found and opened / 如果找到并打开了匹配的菜单则返回true
     */
    private boolean tryOpenMenuForItem(Player player, ItemStack item, PlayerInteractEvent event) {
        Material itemType = item.getType();
        ItemMeta meta = item.getItemMeta();

        // Search all menus for a match
        // 搜索所有菜单以查找匹配项
        for (MenuDefinition menu : menuService.getAllMenus()) {
            // Check if bind item matches
            // 检查绑定物品是否匹配
            if (menu.getBindItem() == null || menu.getBindItem() != itemType) {
                continue;
            }

            // Check bind name if specified
            // 如果指定了绑定名称，进行检查
            if (menu.getBindName() != null && !menu.getBindName().isEmpty()) {
                if (meta == null || !meta.hasDisplayName()) {
                    continue;
                }

                String itemDisplayName = normalizeText(meta.getDisplayName());
                String expectedName = normalizeText(menu.getBindName());

                if (!itemDisplayName.equals(expectedName)) {
                    continue;
                }
            }

            // Check bind lore if specified
            // 如果指定了绑定Lore，进行检查
            if (menu.getBindLore() != null && !menu.getBindLore().isEmpty()) {
                if (meta == null || !meta.hasLore()) {
                    continue;
                }

                List<String> lore = meta.getLore();
                String expectedLore = normalizeText(menu.getBindLore());
                boolean loreMatches = false;

                for (String loreLine : lore) {
                    if (normalizeText(loreLine).contains(expectedLore)) {
                        loreMatches = true;
                        break;
                    }
                }

                if (!loreMatches) {
                    continue;
                }
            }

            // Found a matching menu
            // 找到匹配的菜单
            event.setCancelled(true);

            // Check permission
            // 检查权限
            if (menu.getPermission() != null && !menu.getPermission().isEmpty()) {
                if (!player.hasPermission(menu.getPermission())) {
                    player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限打开此菜单！"));
                    return true;
                }
            }

            // Open the menu GUI
            // 打开菜单GUI
            new CustomMenuGui(player, plugin, menu, menuService).open();
            return true;
        }

        return false;
    }

    /**
     * Normalizes text by translating color codes and stripping colors for comparison.
     * <p>
     * 通过转换颜色代码并去除颜色来规范化文本以进行比较。
     * </p>
     *
     * @param text the text to normalize / 要规范化的文本
     * @return the normalized text / 规范化后的文本
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        // Translate alternate color codes first, then strip all colors
        // 先转换备用颜色代码，然后去除所有颜色
        String translated = ChatColor.translateAlternateColorCodes('&', text);
        return ChatColor.stripColor(translated);
    }
}
