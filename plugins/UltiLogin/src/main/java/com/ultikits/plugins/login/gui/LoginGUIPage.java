package com.ultikits.plugins.login.gui;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI page for player login with numeric keypad.
 * 54-slot GUI with:
 * - Numbers 1-9 (wool blocks with quantity representing number)
 * - Confirm button (green)
 * - Clear button (red)
 * - Exit button (orange)
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class LoginGUIPage extends Gui {
    
    private final LoginService loginService;
    private final LoginConfig config;
    private final StringBuilder passwordInput = new StringBuilder();
    private final int passwordLength;
    
    // Number slots in 54-slot inventory (arranged as keypad)
    private static final int[] NUMBER_SLOTS = {
        10, 11, 12,  // 1, 2, 3
        19, 20, 21,  // 4, 5, 6
        28, 29, 30   // 7, 8, 9
    };
    
    // Control button slots
    private static final int CONFIRM_SLOT = 24;
    private static final int CLEAR_SLOT = 33;
    private static final int EXIT_SLOT = 42;
    private static final int DISPLAY_SLOT = 4;
    
    public LoginGUIPage(Player player, LoginService loginService) {
        super(player, "login-gui", ChatColor.translateAlternateColorCodes('&', 
            loginService.getConfig().getGuiLoginTitle()), 6);
        this.loginService = loginService;
        this.config = loginService.getConfig();
        this.passwordLength = config.getGuiPasswordLength();
    }
    
    @Override
    public void onOpen(InventoryOpenEvent event) {
        // Fill background
        Icon background = new Icon(XVersionUtils.getColoredPlaneGlass(Colors.BLACK));
        background.setName(" ");
        fillGui(background);
        
        // Add number buttons
        for (int i = 0; i < 9; i++) {
            int number = i + 1;
            Icon numberIcon = createNumberIcon(number);
            numberIcon.onClick(e -> onNumberClick(number));
            addItem(NUMBER_SLOTS[i], numberIcon);
        }
        
        // Add control buttons
        addItem(CONFIRM_SLOT, createConfirmIcon());
        addItem(CLEAR_SLOT, createClearIcon());
        addItem(EXIT_SLOT, createExitIcon());
        
        // Add password display
        updatePasswordDisplay();
    }
    
    @Override
    public void onClose(InventoryCloseEvent event) {
        // If not logged in, reopen GUI after a short delay
        if (!loginService.isLoggedIn(player.getUniqueId())) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.ultikits.ultitools.UltiTools.getInstance(),
                () -> {
                    if (player.isOnline() && !loginService.isLoggedIn(player.getUniqueId())) {
                        new LoginGUIPage(player, loginService).open();
                    }
                },
                10L
            );
        }
    }
    
    /**
     * Handle number button click.
     */
    private void onNumberClick(int number) {
        if (passwordInput.length() < passwordLength) {
            passwordInput.append(number);
            updatePasswordDisplay();
            
            // Auto-submit if password is complete
            if (passwordInput.length() == passwordLength) {
                // Small delay before auto-submit
                org.bukkit.Bukkit.getScheduler().runTaskLater(
                    com.ultikits.ultitools.UltiTools.getInstance(),
                    this::attemptLogin,
                    5L
                );
            }
        }
    }
    
    /**
     * Attempt login with current password.
     */
    private void attemptLogin() {
        String password = passwordInput.toString();
        LoginService.LoginResult result = loginService.login(player, password);
        
        if (result.isSuccess()) {
            player.closeInventory();
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', result.getMessage()));
        } else {
            // Clear password and show error
            passwordInput.setLength(0);
            updatePasswordDisplay();
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', result.getMessage()));
        }
    }
    
    /**
     * Update password display.
     */
    private void updatePasswordDisplay() {
        ItemStack display = new ItemStack(Material.PAPER);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "密码输入");
            
            List<String> lore = new ArrayList<>();
            lore.add("");
            
            // Show masked password
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < passwordLength; i++) {
                if (i < passwordInput.length()) {
                    masked.append("●");
                } else {
                    masked.append("○");
                }
                if (i < passwordLength - 1) {
                    masked.append(" ");
                }
            }
            lore.add(ChatColor.WHITE + masked.toString());
            lore.add("");
            lore.add(ChatColor.GRAY + "已输入 " + passwordInput.length() + "/" + passwordLength + " 位");
            
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        
        Icon displayIcon = new Icon(display);
        addItem(DISPLAY_SLOT, displayIcon);
    }
    
    /**
     * Create number icon with wool.
     */
    private Icon createNumberIcon(int number) {
        Colors[] colors = {
            Colors.WHITE, Colors.ORANGE, Colors.MAGENTA,
            Colors.LIGHT_BLUE, Colors.YELLOW, Colors.LIME,
            Colors.PINK, Colors.CYAN, Colors.PURPLE
        };
        
        ItemStack wool = XVersionUtils.getColoredWool(colors[number - 1]);
        wool.setAmount(number);
        
        ItemMeta meta = wool.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + String.valueOf(number));
            wool.setItemMeta(meta);
        }
        
        return new Icon(wool);
    }
    
    /**
     * Create confirm button.
     */
    private Icon createConfirmIcon() {
        ItemStack glass = XVersionUtils.getColoredPlaneGlass(Colors.GREEN);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "确认登录");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "点击确认登录");
            meta.setLore(lore);
            glass.setItemMeta(meta);
        }
        
        Icon icon = new Icon(glass);
        icon.onClick(e -> attemptLogin());
        return icon;
    }
    
    /**
     * Create clear button.
     */
    private Icon createClearIcon() {
        ItemStack glass = XVersionUtils.getColoredPlaneGlass(Colors.RED);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "清空");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "点击清空已输入的密码");
            meta.setLore(lore);
            glass.setItemMeta(meta);
        }
        
        Icon icon = new Icon(glass);
        icon.onClick(e -> {
            passwordInput.setLength(0);
            updatePasswordDisplay();
        });
        return icon;
    }
    
    /**
     * Create exit button.
     */
    private Icon createExitIcon() {
        ItemStack glass = XVersionUtils.getColoredPlaneGlass(Colors.ORANGE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "退出");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "点击退出登录界面");
            lore.add(ChatColor.RED + "注意: 你仍需要登录才能游玩");
            meta.setLore(lore);
            glass.setItemMeta(meta);
        }
        
        Icon icon = new Icon(glass);
        icon.onClick(e -> {
            player.closeInventory();
            // Send command mode prompt as fallback
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getLoginPrompt()));
        });
        return icon;
    }
    
    /**
     * Open the login GUI for a player.
     */
    public static void open(Player player, LoginService loginService) {
        new LoginGUIPage(player, loginService).open();
    }
}
