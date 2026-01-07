package com.ultikits.plugins.trade.service;

import com.ultikits.plugins.trade.UltiTrade;
import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.TradeRequest;
import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.gui.TradeGUI;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player trades.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class TradeService {
    
    @Autowired
    private TradeConfig config;
    
    // Pending trade requests
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();
    
    // Active trade sessions
    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();
    
    // Player to session mapping
    private final Map<UUID, UUID> playerSessionMap = new ConcurrentHashMap<>();
    
    // Economy integration
    private Economy economy;
    
    // Cleanup task
    private BukkitTask cleanupTask;
    
    /**
     * Initialize the trade service.
     */
    public void init() {
        // Setup economy
        if (config.isEnableMoneyTrade()) {
            setupEconomy();
        }
        
        // Start cleanup task
        cleanupTask = Bukkit.getScheduler().runTaskTimer(
            UltiTrade.getInstance().getPluginInstance(),
            this::cleanupExpiredRequests,
            20L * 10, 20L * 10 // Every 10 seconds
        );
    }
    
    /**
     * Shutdown the service.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        
        // Cancel all active sessions
        for (TradeSession session : activeSessions.values()) {
            cancelTrade(session, "插件关闭");
        }
        
        pendingRequests.clear();
        activeSessions.clear();
        playerSessionMap.clear();
    }
    
    /**
     * Setup Vault economy.
     */
    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            UltiTrade.getInstance().getLogger().warning("Vault not found! Money trading disabled.");
            return;
        }
        
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
        }
    }
    
    /**
     * Check if economy is available.
     */
    public boolean hasEconomy() {
        return economy != null && config.isEnableMoneyTrade();
    }
    
    /**
     * Get economy instance.
     */
    public Economy getEconomy() {
        return economy;
    }
    
    /**
     * Send a trade request.
     * 
     * @param sender Request sender
     * @param target Request target
     * @return true if request sent
     */
    public boolean sendRequest(Player sender, Player target) {
        // Check if sender is already trading
        if (isTrading(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "你已经在交易中！");
            return false;
        }
        
        // Check if target is already trading
        if (isTrading(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + target.getName() + " 正在交易中！");
            return false;
        }
        
        // Check distance
        if (config.getMaxDistance() > 0) {
            if (!config.isAllowCrossWorld() && !sender.getWorld().equals(target.getWorld())) {
                sender.sendMessage(ChatColor.RED + "不能跨世界交易！");
                return false;
            }
            
            if (sender.getWorld().equals(target.getWorld()) && 
                sender.getLocation().distance(target.getLocation()) > config.getMaxDistance()) {
                sender.sendMessage(ChatColor.RED + "距离太远，无法交易！");
                return false;
            }
        }
        
        // Check if there's already a pending request
        TradeRequest existingRequest = pendingRequests.get(target.getUniqueId());
        if (existingRequest != null && existingRequest.getSender().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "你已经向该玩家发送过交易请求了！");
            return false;
        }
        
        // Check if target has sent a request to sender (auto-accept)
        TradeRequest reverseRequest = pendingRequests.get(sender.getUniqueId());
        if (reverseRequest != null && reverseRequest.getSender().equals(target.getUniqueId())) {
            pendingRequests.remove(sender.getUniqueId());
            startTrade(target, sender);
            return true;
        }
        
        // Create and store request
        TradeRequest request = new TradeRequest(sender.getUniqueId(), target.getUniqueId());
        pendingRequests.put(target.getUniqueId(), request);
        
        // Notify players
        String sentMsg = config.getRequestSentMessage().replace("{PLAYER}", target.getName());
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', sentMsg));
        
        String receivedMsg = config.getRequestReceivedMessage().replace("{PLAYER}", sender.getName());
        target.sendMessage(ChatColor.translateAlternateColorCodes('&', receivedMsg));
        
        return true;
    }
    
    /**
     * Accept a trade request.
     * 
     * @param player Player accepting
     * @return true if accepted
     */
    public boolean acceptRequest(Player player) {
        TradeRequest request = pendingRequests.remove(player.getUniqueId());
        if (request == null || request.isExpired(config.getRequestTimeout())) {
            player.sendMessage(ChatColor.RED + "没有待处理的交易请求！");
            return false;
        }
        
        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender == null || !sender.isOnline()) {
            player.sendMessage(ChatColor.RED + "对方已离线！");
            return false;
        }
        
        startTrade(sender, player);
        return true;
    }
    
    /**
     * Deny a trade request.
     * 
     * @param player Player denying
     * @return true if denied
     */
    public boolean denyRequest(Player player) {
        TradeRequest request = pendingRequests.remove(player.getUniqueId());
        if (request == null) {
            player.sendMessage(ChatColor.RED + "没有待处理的交易请求！");
            return false;
        }
        
        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatColor.RED + player.getName() + " 拒绝了你的交易请求！");
        }
        
        player.sendMessage(ChatColor.YELLOW + "已拒绝交易请求！");
        return true;
    }
    
    /**
     * Start a trade between two players.
     */
    public void startTrade(Player player1, Player player2) {
        TradeSession session = new TradeSession(player1, player2);
        
        activeSessions.put(session.getSessionId(), session);
        playerSessionMap.put(player1.getUniqueId(), session.getSessionId());
        playerSessionMap.put(player2.getUniqueId(), session.getSessionId());
        
        // Open trade GUI for both players
        TradeGUI gui1 = new TradeGUI(this, session, player1);
        TradeGUI gui2 = new TradeGUI(this, session, player2);
        
        player1.openInventory(gui1.getInventory());
        player2.openInventory(gui2.getInventory());
    }
    
    /**
     * Get active session for player.
     */
    public TradeSession getSession(UUID playerUuid) {
        UUID sessionId = playerSessionMap.get(playerUuid);
        if (sessionId == null) {
            return null;
        }
        return activeSessions.get(sessionId);
    }
    
    /**
     * Check if player is in trade.
     */
    public boolean isTrading(UUID playerUuid) {
        return playerSessionMap.containsKey(playerUuid);
    }
    
    /**
     * Confirm trade for player.
     */
    public void confirmTrade(Player player) {
        TradeSession session = getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        
        session.setConfirmed(player.getUniqueId(), true);
        
        // Notify other player
        Player other = Bukkit.getPlayer(session.getOtherPlayer(player.getUniqueId()));
        if (other != null) {
            other.sendMessage(ChatColor.GREEN + player.getName() + " 已确认交易！");
        }
        
        // Check if both confirmed
        if (session.isBothConfirmed()) {
            completeTrade(session);
        }
    }
    
    /**
     * Cancel confirmation.
     */
    public void cancelConfirmation(Player player) {
        TradeSession session = getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        
        session.setConfirmed(player.getUniqueId(), false);
    }
    
    /**
     * Complete the trade.
     */
    public void completeTrade(TradeSession session) {
        Player player1 = Bukkit.getPlayer(session.getPlayer1());
        Player player2 = Bukkit.getPlayer(session.getPlayer2());
        
        if (player1 == null || player2 == null) {
            cancelTrade(session, "玩家离线");
            return;
        }
        
        // Handle money transfer
        if (hasEconomy()) {
            double money1 = session.getPlayerMoney(session.getPlayer1());
            double money2 = session.getPlayerMoney(session.getPlayer2());
            
            // Apply tax
            double taxRate = config.getTradeTax();
            double tax1 = money1 * taxRate;
            double tax2 = money2 * taxRate;
            
            // Check balances
            if (money1 > 0 && economy.getBalance(player1) < money1) {
                cancelTrade(session, player1.getName() + " 余额不足");
                return;
            }
            if (money2 > 0 && economy.getBalance(player2) < money2) {
                cancelTrade(session, player2.getName() + " 余额不足");
                return;
            }
            
            // Transfer money
            if (money1 > 0) {
                economy.withdrawPlayer(player1, money1);
                economy.depositPlayer(player2, money1 - tax1);
            }
            if (money2 > 0) {
                economy.withdrawPlayer(player2, money2);
                economy.depositPlayer(player1, money2 - tax2);
            }
        }
        
        // Transfer items
        Map<Integer, ItemStack> items1 = session.getPlayerItems(session.getPlayer1());
        Map<Integer, ItemStack> items2 = session.getPlayerItems(session.getPlayer2());
        
        // Give player1's items to player2
        for (ItemStack item : items1.values()) {
            if (item != null) {
                HashMap<Integer, ItemStack> overflow = player2.getInventory().addItem(item);
                for (ItemStack drop : overflow.values()) {
                    player2.getWorld().dropItemNaturally(player2.getLocation(), drop);
                }
            }
        }
        
        // Give player2's items to player1
        for (ItemStack item : items2.values()) {
            if (item != null) {
                HashMap<Integer, ItemStack> overflow = player1.getInventory().addItem(item);
                for (ItemStack drop : overflow.values()) {
                    player1.getWorld().dropItemNaturally(player1.getLocation(), drop);
                }
            }
        }
        
        // Close inventories and clean up
        player1.closeInventory();
        player2.closeInventory();
        
        session.setState(TradeSession.TradeState.COMPLETED);
        cleanupSession(session);
        
        // Notify players
        String completeMsg = ChatColor.translateAlternateColorCodes('&', config.getTradeCompleteMessage());
        player1.sendMessage(completeMsg);
        player2.sendMessage(completeMsg);
    }
    
    /**
     * Cancel a trade.
     */
    public void cancelTrade(TradeSession session, String reason) {
        Player player1 = Bukkit.getPlayer(session.getPlayer1());
        Player player2 = Bukkit.getPlayer(session.getPlayer2());
        
        // Return items to original owners
        if (player1 != null) {
            for (ItemStack item : session.getPlayerItems(session.getPlayer1()).values()) {
                if (item != null) {
                    HashMap<Integer, ItemStack> overflow = player1.getInventory().addItem(item);
                    for (ItemStack drop : overflow.values()) {
                        player1.getWorld().dropItemNaturally(player1.getLocation(), drop);
                    }
                }
            }
            player1.closeInventory();
            String msg = config.getTradeCancelledMessage();
            if (reason != null) {
                msg += " (" + reason + ")";
            }
            player1.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
        
        if (player2 != null) {
            for (ItemStack item : session.getPlayerItems(session.getPlayer2()).values()) {
                if (item != null) {
                    HashMap<Integer, ItemStack> overflow = player2.getInventory().addItem(item);
                    for (ItemStack drop : overflow.values()) {
                        player2.getWorld().dropItemNaturally(player2.getLocation(), drop);
                    }
                }
            }
            player2.closeInventory();
            String msg = config.getTradeCancelledMessage();
            if (reason != null) {
                msg += " (" + reason + ")";
            }
            player2.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
        
        session.setState(TradeSession.TradeState.CANCELLED);
        cleanupSession(session);
    }
    
    /**
     * Cancel trade by player.
     */
    public void cancelTrade(Player player) {
        TradeSession session = getSession(player.getUniqueId());
        if (session != null) {
            cancelTrade(session, player.getName() + " 取消了交易");
        }
    }
    
    /**
     * Cleanup session.
     */
    private void cleanupSession(TradeSession session) {
        activeSessions.remove(session.getSessionId());
        playerSessionMap.remove(session.getPlayer1());
        playerSessionMap.remove(session.getPlayer2());
    }
    
    /**
     * Cleanup expired requests.
     */
    private void cleanupExpiredRequests() {
        int timeout = config.getRequestTimeout();
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().isExpired(timeout));
    }
    
    /**
     * Get config.
     */
    public TradeConfig getConfig() {
        return config;
    }
}
