package com.ultikits.plugins.login.listener;

import com.ultikits.plugins.login.gui.LoginGUIPage;
import com.ultikits.plugins.login.gui.RegisterGUIPage;
import com.ultikits.plugins.login.service.LoginService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

/**
 * Listener for login protection.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@EventListener
public class LoginProtectionListener implements Listener {

    private final UltiToolsPlugin plugin;
    private final LoginService loginService;
    private final Plugin bukkitPlugin;

    public LoginProtectionListener(UltiToolsPlugin plugin, LoginService loginService) {
        this.plugin = plugin;
        this.loginService = loginService;
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loginService.onPlayerJoin(player);
        
        // Open GUI if enabled (with delay for proper loading)
        if (loginService.getConfig().isGuiModeEnabled()) {
            Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
                if (player.isOnline() && !loginService.isLoggedIn(player.getUniqueId())) {
                    // Check if already has valid session (handled in onPlayerJoin)
                    if (loginService.hasValidSession(player)) {
                        return;
                    }
                    
                    if (loginService.isRegistered(player.getUniqueId())) {
                        LoginGUIPage.open(player, plugin, loginService);
                    } else {
                        RegisterGUIPage.open(player, plugin, loginService);
                    }
                }
            }, 20L); // 1 second delay for compatibility with skin plugins
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        loginService.onPlayerQuit(event.getPlayer());
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only cancel if player should be blocked AND actually moved (not just looked around)
        if (shouldCancel(event.getPlayer()) &&
                (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                 event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                 event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {
            event.setTo(event.getFrom());
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (shouldCancel(event.getPlayer())) {
            event.setCancelled(true);
            sendLoginPrompt(event.getPlayer());
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!loginService.isLoggedIn(player.getUniqueId())) {
            if (!loginService.isCommandAllowed(event.getMessage())) {
                event.setCancelled(true);
                sendLoginPrompt(player);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            // Allow GUI interactions for login/register GUI
            if (!loginService.isLoggedIn(player.getUniqueId())) {
                String title = event.getView().getTitle();
                // Allow clicking in login/register GUI
                if (!title.contains("密码") && !title.contains("登录") && !title.contains("注册")) {
                    event.setCancelled(true);
                }
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            // Allow opening login/register GUI
            if (!loginService.isLoggedIn(player.getUniqueId())) {
                String title = event.getView().getTitle();
                if (!title.contains("密码") && !title.contains("登录") && !title.contains("注册")) {
                    event.setCancelled(true);
                }
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event);
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            cancelIfNotLoggedIn((Player) event.getEntity(), event);
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (shouldCancel(player)) {
                event.setCancelled(true);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            cancelIfNotLoggedIn((Player) event.getDamager(), event);
        }
    }
    
    /**
     * Check if player actions should be cancelled.
     */
    private boolean shouldCancel(Player player) {
        return !loginService.isLoggedIn(player.getUniqueId());
    }
    
    /**
     * Cancel event if player not logged in.
     */
    private void cancelIfNotLoggedIn(Player player, Cancellable event) {
        if (shouldCancel(player)) {
            event.setCancelled(true);
        }
    }
    
    /**
     * Send login prompt to player.
     */
    private void sendLoginPrompt(Player player) {
        if (loginService.getConfig().isGuiModeEnabled()) {
            // Reopen GUI
            Bukkit.getScheduler().runTask(bukkitPlugin, () -> {
                if (player.isOnline() && !loginService.isLoggedIn(player.getUniqueId())) {
                    if (loginService.isRegistered(player.getUniqueId())) {
                        LoginGUIPage.open(player, plugin, loginService);
                    } else {
                        RegisterGUIPage.open(player, plugin, loginService);
                    }
                }
            });
        } else {
            // Send text prompt
            if (loginService.isRegistered(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                    loginService.getConfig().getLoginPrompt()));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                    loginService.getConfig().getRegisterPrompt()));
            }
        }
    }
}
