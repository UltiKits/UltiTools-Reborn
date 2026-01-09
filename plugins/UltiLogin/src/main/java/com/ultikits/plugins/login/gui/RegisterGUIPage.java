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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI page for player registration with numeric keypad.
 * Two-phase registration:
 * 1. Enter password
 * 2. Confirm password
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class RegisterGUIPage extends Gui {
    
    private final LoginService loginService;
    private final LoginConfig config;
    private final StringBuilder passwordInput = new StringBuilder();
    private final int passwordLength;
    
    // Registration state
    private String firstPassword = null;
    private boolean confirmPhase = false;
    
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
    
    public RegisterGUIPage(Player player, LoginService loginService) {
        super(player, "register-gui", ChatColor.translateAlternateColorCodes('&', 
            loginService.getConfig().getGuiRegisterTitle()), 6);
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
        // If not registered, reopen GUI after a short delay
        if (!loginService.isRegistered(player.getUniqueId())) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.ultikits.ultitools.UltiTools.getInstance(),
                () -> {
                    if (player.isOnline() && !loginService.isRegistered(player.getUniqueId())) {
                        new RegisterGUIPage(player, loginService).open();
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
            
            // Auto-proceed if password is complete
            if (passwordInput.length() == passwordLength) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(
                    com.ultikits.ultitools.UltiTools.getInstance(),
                    this::processInput,
                    5L
                );
            }
        }
    }
    
    /**
     * Process the current input.
     */
    private void processInput() {
        String password = passwordInput.toString();
        
        if (!confirmPhase) {
            // First phase: set password
            firstPassword = password;
            confirmPhase = true;
            passwordInput.setLength(0);
            
            // Update title
            setTitle(ChatColor.translateAlternateColorCodes('&', config.getGuiConfirmTitle()));
            
            updatePasswordDisplay();
            player.sendMessage(ChatColor.YELLOW + "请再次输入密码进行确认");
        } else {
            // Second phase: confirm password
            if (password.equals(firstPassword)) {
                // Passwords match, register
                if (loginService.register(player, password)) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getRegisterSuccess()));
                } else {
                    player.sendMessage(ChatColor.RED + "注册失败！请联系管理员。");
                    resetToFirstPhase();
                }
            } else {
                // Passwords don't match
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getPasswordMismatch()));
                resetToFirstPhase();
            }
        }
    }
    
    /**
     * Reset to first password input phase.
     */
    private void resetToFirstPhase() {
        firstPassword = null;
        confirmPhase = false;
        passwordInput.setLength(0);
        setTitle(ChatColor.translateAlternateColorCodes('&', config.getGuiRegisterTitle()));
        updatePasswordDisplay();
    }
    
    /**
     * Update password display.
     */
    private void updatePasswordDisplay() {
        ItemStack display = new ItemStack(Material.PAPER);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            if (confirmPhase) {
                meta.setDisplayName(ChatColor.GOLD + "确认密码");
            } else {
                meta.setDisplayName(ChatColor.GOLD + "设置密码");
            }
            
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
            
            if (confirmPhase) {
                lore.add("");
                lore.add(ChatColor.YELLOW + "请再次输入刚才的密码");
            } else {
                lore.add("");
                lore.add(ChatColor.YELLOW + "请输入 " + passwordLength + " 位数字密码");
            }
            
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
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "确认");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "点击确认当前输入");
            meta.setLore(lore);
            glass.setItemMeta(meta);
        }
        
        Icon icon = new Icon(glass);
        icon.onClick(e -> processInput());
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
            lore.add(ChatColor.GRAY + "点击退出注册界面");
            lore.add(ChatColor.RED + "注意: 你需要注册才能游玩");
            meta.setLore(lore);
            glass.setItemMeta(meta);
        }
        
        Icon icon = new Icon(glass);
        icon.onClick(e -> {
            player.closeInventory();
            // Send command mode prompt as fallback
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getRegisterPrompt()));
        });
        return icon;
    }
    
    /**
     * Open the register GUI for a player.
     */
    public static void open(Player player, LoginService loginService) {
        new RegisterGUIPage(player, loginService).open();
    }
}
