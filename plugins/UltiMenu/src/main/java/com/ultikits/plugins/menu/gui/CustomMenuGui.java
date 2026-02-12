package com.ultikits.plugins.menu.gui;

import com.ultikits.plugins.menu.model.ButtonDefinition;
import com.ultikits.plugins.menu.model.MenuDefinition;
import com.ultikits.plugins.menu.services.MenuService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.EconomyUtils;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.ultikits.plugins.menu.config.MenuConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GUI implementation for custom menus using obliviate-invs framework.
 * 使用 obliviate-invs 框架的自定义菜单 GUI 实现
 */
public class CustomMenuGui extends Gui {

    private final Player player;
    private final UltiToolsPlugin plugin;
    private final MenuDefinition menuDefinition;
    private final MenuService menuService;
    private final long clickCooldownMs;
    private long lastClickTime = 0;

    /**
     * Constructor for CustomMenuGui.
     * CustomMenuGui 构造函数
     *
     * @param player         the player viewing the menu
     * @param plugin         the UltiTools plugin instance
     * @param menuDefinition the menu definition to display
     * @param menuService    the menu service for accessing other menus
     */
    public CustomMenuGui(Player player, UltiToolsPlugin plugin, MenuDefinition menuDefinition, MenuService menuService) {
        super(player,
              "custom_menu_" + menuDefinition.getFileName(),
              ChatColor.translateAlternateColorCodes('&', parsePlaceholders(player, menuDefinition.getTitle())),
              menuDefinition.getSize() / 9);
        this.player = player;
        this.plugin = plugin;
        this.menuDefinition = menuDefinition;
        this.menuService = menuService;
        // Read cooldown from config, default 200ms
        MenuConfig config = plugin.getConfig(MenuConfig.class);
        this.clickCooldownMs = config != null ? config.getClickCooldownMs() : 200;
    }

    @Override
    public void onOpen(InventoryOpenEvent event) {
        // Iterate through all buttons and place them in the GUI
        // 遍历所有按钮并将它们放置在 GUI 中
        Map<String, ButtonDefinition> buttons = menuDefinition.getButtons();
        if (buttons == null || buttons.isEmpty()) {
            return;
        }

        for (Map.Entry<String, ButtonDefinition> entry : buttons.entrySet()) {
            ButtonDefinition button = entry.getValue();
            if (button == null) {
                continue;
            }

            // Create ItemStack from button definition
            // 从按钮定义创建 ItemStack
            ItemStack itemStack = new ItemStack(button.getItem());
            ItemMeta meta = itemStack.getItemMeta();

            if (meta != null) {
                // Set display name with color codes and PlaceholderAPI
                // 设置带颜色代码和 PlaceholderAPI 的显示名称
                String displayName = button.getName();
                if (displayName != null && !displayName.isEmpty()) {
                    displayName = ChatColor.translateAlternateColorCodes('&', parsePlaceholders(player, displayName));
                    meta.setDisplayName(displayName);
                }

                // Set lore with color codes and PlaceholderAPI
                // 设置带颜色代码和 PlaceholderAPI 的描述
                List<String> lore = button.getLore();
                if (lore != null && !lore.isEmpty()) {
                    List<String> translatedLore = new ArrayList<>();
                    for (String line : lore) {
                        translatedLore.add(ChatColor.translateAlternateColorCodes('&', parsePlaceholders(player, line)));
                    }
                    meta.setLore(translatedLore);
                }

                // Set custom model data if specified
                // 如果指定，设置自定义模型数据
                if (button.getCustomModelData() > 0) {
                    meta.setCustomModelData(button.getCustomModelData());
                }

                itemStack.setItemMeta(meta);
            }

            // Create Icon with click handler
            // 创建带点击处理器的 Icon
            Icon icon = new Icon(itemStack);
            icon.onClick(clickEvent -> {
                clickEvent.setCancelled(true);
                handleButtonClick(button);
            });

            // Add item to GUI at specified position
            // 将物品添加到 GUI 的指定位置
            addItem(button.getPosition(), icon);
        }
    }

    /**
     * Handle button click with all logic including debounce, permissions, economy, and commands.
     * 处理按钮点击，包括防抖、权限、经济和命令等所有逻辑
     *
     * @param button the button that was clicked
     */
    private void handleButtonClick(ButtonDefinition button) {
        // Click debounce (200ms)
        // 点击防抖（200毫秒）
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClickTime < clickCooldownMs) {
            return;
        }
        lastClickTime = currentTime;

        // Permission check
        // 权限检查
        String permission = button.getPermission();
        if (permission != null && !permission.isEmpty() && !player.hasPermission(permission)) {
            player.sendMessage(ChatColor.RED + plugin.i18n("你没有权限使用此按钮！"));
            return;
        }

        // Economy check
        // 经济检查
        double price = button.getPrice();
        if (price > 0) {
            if (!EconomyUtils.isAvailable()) {
                player.sendMessage(ChatColor.RED + plugin.i18n("经济系统不可用！"));
                return;
            }

            if (!EconomyUtils.has(player, price)) {
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("余额不足！需要 %s"), EconomyUtils.format(price)));
                return;
            }

            if (!EconomyUtils.withdraw(player, price)) {
                player.sendMessage(ChatColor.RED + plugin.i18n("扣款失败！"));
                return;
            }

            player.sendMessage(ChatColor.GREEN + String.format(plugin.i18n("已扣除 %s"), EconomyUtils.format(price)));
        }

        // Execute player commands
        // 执行玩家命令
        List<String> playerCommands = button.getPlayerCommands();
        if (playerCommands != null && !playerCommands.isEmpty()) {
            for (String cmd : playerCommands) {
                String processedCmd = cmd.replace("{player}", player.getName());
                processedCmd = parsePlaceholders(player, processedCmd);
                player.performCommand(processedCmd);
            }
        }

        // Execute console commands on main thread
        // 在主线程执行控制台命令
        List<String> consoleCommands = button.getConsoleCommands();
        if (consoleCommands != null && !consoleCommands.isEmpty()) {
            org.bukkit.plugin.Plugin ultiToolsPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
            if (ultiToolsPlugin != null) {
                for (String cmd : consoleCommands) {
                    String processedCmd = cmd.replace("{player}", player.getName());
                    processedCmd = parsePlaceholders(player, processedCmd);
                    String finalCmd = processedCmd;
                    Bukkit.getScheduler().runTask(ultiToolsPlugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd)
                    );
                }
            }
        }

        // Open sub-menu if specified
        // 如果指定，打开子菜单
        String openMenu = button.getOpenMenu();
        if (openMenu != null && !openMenu.isEmpty()) {
            MenuDefinition subMenu = menuService.getMenu(openMenu);
            if (subMenu == null) {
                player.sendMessage(ChatColor.RED + String.format(plugin.i18n("菜单 '%s' 不存在！"), openMenu));
                return;
            }

            // Close current menu and open sub-menu on next tick
            // 关闭当前菜单并在下一个 tick 打开子菜单
            player.closeInventory();
            org.bukkit.plugin.Plugin ultiToolsPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
            if (ultiToolsPlugin != null) {
                Bukkit.getScheduler().runTask(ultiToolsPlugin, () -> {
                    CustomMenuGui subMenuGui = new CustomMenuGui(player, plugin, subMenu, menuService);
                    subMenuGui.open();
                });
            }
            return;
        }

        // Close inventory if closeOnClick is true and no sub-menu
        // 如果 closeOnClick 为 true 且没有子菜单，关闭背包
        if (button.isCloseOnClick()) {
            player.closeInventory();
        }
    }

    /**
     * Parse placeholders in text including {player} and PlaceholderAPI placeholders.
     * 解析文本中的占位符，包括 {player} 和 PlaceholderAPI 占位符
     *
     * @param player the player for placeholder context
     * @param text   the text to process
     * @return processed text with placeholders replaced
     */
    private static String parsePlaceholders(Player player, String text) {
        if (text == null) {
            return "";
        }

        // Replace {player} placeholder
        // 替换 {player} 占位符
        String result = text.replace("{player}", player.getName());

        // Try to use PlaceholderAPI if available
        // 如果可用，尝试使用 PlaceholderAPI
        try {
            Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
        } catch (NoClassDefFoundError | ExceptionInInitializerError | Exception e) {
            // PlaceholderAPI not installed or not initialized, skip
            // PlaceholderAPI 未安装或未初始化，跳过
        }

        return result;
    }
}
