package com.ultikits.plugins.trade.service;

import com.ultikits.plugins.trade.config.TradeConfig;
import com.ultikits.plugins.trade.entity.TradeRequest;
import com.ultikits.plugins.trade.entity.TradeSession;
import com.ultikits.plugins.trade.gui.TradeConfirmPage;
import com.ultikits.plugins.trade.gui.TradeGUI;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player trades.
 * Includes blacklist, toggle, sounds, particles, BossBar, and trade logging.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class TradeService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private TradeConfig config;

    @Autowired
    private TradeLogService logService;
    
    // Pending trade requests
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();
    
    // Active trade sessions
    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();
    
    // Player to session mapping
    private final Map<UUID, UUID> playerSessionMap = new ConcurrentHashMap<>();
    
    // BossBar for trade requests
    private final Map<UUID, BossBar> requestBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> bossBarTasks = new ConcurrentHashMap<>();
    
    // Bukkit plugin instance for scheduler tasks
    private Plugin bukkitPlugin;

    // Economy integration
    private Economy economy;
    
    /**
     * Initialize the trade service.
     */
    public void init() {
        // Initialize Bukkit plugin reference for scheduler tasks
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

        // Setup economy
        if (config.isEnableMoneyTrade()) {
            setupEconomy();
        }
    }
    
    /**
     * Shutdown the service.
     */
    public void shutdown() {
        // Cancel all active sessions
        for (TradeSession session : activeSessions.values()) {
            cancelTrade(session, "插件关闭");
        }
        
        // Cleanup BossBars
        for (BossBar bar : requestBossBars.values()) {
            bar.removeAll();
        }
        for (BukkitTask task : bossBarTasks.values()) {
            task.cancel();
        }
        
        pendingRequests.clear();
        activeSessions.clear();
        playerSessionMap.clear();
        requestBossBars.clear();
        bossBarTasks.clear();
    }
    
    /**
     * Setup Vault economy.
     */
    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warn("Vault not found! Money trading disabled.");
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
        // Check if sender has trade enabled
        if (!logService.isTradeEnabled(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "你已关闭交易功能！使用 /trade toggle 开启");
            return false;
        }
        
        // Check if target has trade enabled
        if (!logService.isTradeEnabled(target.getUniqueId())) {
            String msg = config.getTradeDisabledMessage().replace("{PLAYER}", target.getName());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }
        
        // Check if sender is blocked by target
        if (logService.isBlocked(target.getUniqueId(), sender.getUniqueId())) {
            String msg = config.getPlayerBlockedMessage().replace("{PLAYER}", target.getName());
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }
        
        // Check if target is blocked by sender
        if (logService.isBlocked(sender.getUniqueId(), target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "你已将 " + target.getName() + " 加入黑名单！");
            return false;
        }
        
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
        
        // Check if there's already a pending request from sender
        TradeRequest existingRequest = pendingRequests.get(target.getUniqueId());
        if (existingRequest != null && existingRequest.getSender().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "你已经向该玩家发送过交易请求了！");
            return false;
        }
        
        // Check if target has sent a request to sender (auto-accept)
        TradeRequest reverseRequest = pendingRequests.get(sender.getUniqueId());
        if (reverseRequest != null && reverseRequest.getSender().equals(target.getUniqueId())) {
            removeBossBar(sender.getUniqueId());
            pendingRequests.remove(sender.getUniqueId());
            startTrade(target, sender);
            return true;
        }
        
        // Create and store request
        TradeRequest request = new TradeRequest(sender.getUniqueId(), target.getUniqueId());
        pendingRequests.put(target.getUniqueId(), request);
        
        // Notify sender
        String sentMsg = config.getRequestSentMessage().replace("{PLAYER}", target.getName());
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', sentMsg));
        playSound(sender, Sound.BLOCK_NOTE_BLOCK_PLING);
        
        // Notify target
        notifyTradeRequest(target, sender);
        
        // Show BossBar if enabled
        if (config.isEnableBossbar()) {
            showRequestBossBar(target, sender.getName());
        }
        
        return true;
    }
    
    /**
     * Notify player of trade request with clickable buttons.
     */
    private void notifyTradeRequest(Player target, Player sender) {
        if (config.isEnableClickableButtons()) {
            // Create clickable message
            TextComponent message = new TextComponent(ChatColor.YELLOW + sender.getName() + 
                ChatColor.WHITE + " 请求与你交易！ ");
            
            // Accept button
            TextComponent acceptBtn = new TextComponent(ChatColor.GREEN + "[接受]");
            acceptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade accept"));
            acceptBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                new Text(ChatColor.GREEN + "点击接受交易请求")));
            
            // Deny button
            TextComponent denyBtn = new TextComponent(ChatColor.RED + " [拒绝]");
            denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade deny"));
            denyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                new Text(ChatColor.RED + "点击拒绝交易请求")));
            
            message.addExtra(acceptBtn);
            message.addExtra(denyBtn);
            
            target.spigot().sendMessage(message);
        } else {
            String receivedMsg = config.getRequestReceivedMessage().replace("{PLAYER}", sender.getName());
            target.sendMessage(ChatColor.translateAlternateColorCodes('&', receivedMsg));
        }
        
        playSound(target, Sound.BLOCK_NOTE_BLOCK_BELL);
    }
    
    /**
     * Show BossBar for trade request countdown.
     */
    private void showRequestBossBar(Player target, String senderName) {
        // Remove existing BossBar if any
        removeBossBar(target.getUniqueId());
        
        BossBar bar = Bukkit.createBossBar(
            ChatColor.YELLOW + senderName + " 请求与你交易 (剩余 " + config.getRequestTimeout() + "秒)",
            BarColor.YELLOW,
            BarStyle.SOLID
        );
        bar.addPlayer(target);
        bar.setProgress(1.0);
        requestBossBars.put(target.getUniqueId(), bar);
        
        // Start countdown task
        final int[] remaining = {config.getRequestTimeout()};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(bukkitPlugin, () -> {
            remaining[0]--;
            if (remaining[0] <= 0) {
                removeBossBar(target.getUniqueId());
                return;
            }
            
            double progress = (double) remaining[0] / config.getRequestTimeout();
            bar.setProgress(Math.max(0, progress));
            bar.setTitle(ChatColor.YELLOW + senderName + " 请求与你交易 (剩余 " + remaining[0] + "秒)");
            
            // Change color when time is running out
            if (remaining[0] <= 5) {
                bar.setColor(BarColor.RED);
            } else if (remaining[0] <= 10) {
                bar.setColor(BarColor.PINK);
            }
        }, 20L, 20L);
        
        bossBarTasks.put(target.getUniqueId(), task);
    }
    
    /**
     * Remove BossBar for player.
     */
    private void removeBossBar(UUID playerUuid) {
        BossBar bar = requestBossBars.remove(playerUuid);
        if (bar != null) {
            bar.removeAll();
        }
        
        BukkitTask task = bossBarTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }
    
    /**
     * Accept a trade request.
     * 
     * @param player Player accepting
     * @return true if accepted
     */
    public boolean acceptRequest(Player player) {
        removeBossBar(player.getUniqueId());
        
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
        removeBossBar(player.getUniqueId());
        
        TradeRequest request = pendingRequests.remove(player.getUniqueId());
        if (request == null) {
            player.sendMessage(ChatColor.RED + "没有待处理的交易请求！");
            return false;
        }
        
        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatColor.RED + player.getName() + " 拒绝了你的交易请求！");
            playSound(sender, Sound.ENTITY_VILLAGER_NO);
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
        
        // Play sound
        playSound(player1, Sound.BLOCK_CHEST_OPEN);
        playSound(player2, Sound.BLOCK_CHEST_OPEN);
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
     * Handles large trade confirmation if threshold is exceeded.
     */
    public void confirmTrade(Player player) {
        TradeSession session = getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        
        // Check if large trade confirmation is needed
        double threshold = config.getConfirmThreshold();
        double totalMoney = session.getPlayerMoney(player.getUniqueId()) + 
                           session.getOtherPlayerMoney(player.getUniqueId());
        int totalExp = session.getPlayerExp(player.getUniqueId()) + 
                       session.getOtherPlayerExp(player.getUniqueId());
        
        // If already confirmed once (in session), proceed
        if (!session.isConfirmed(player.getUniqueId()) && 
            (totalMoney >= threshold || totalExp >= threshold)) {
            // Show confirmation page
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
                TradeConfirmPage confirmPage = new TradeConfirmPage(
                    this, session, player,
                    () -> {
                        // On confirm - mark as confirmed and reopen trade GUI
                        session.setConfirmed(player.getUniqueId(), true);
                        notifyConfirmation(session, player);
                        
                        // Reopen trade GUI
                        Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
                            if (isTrading(player.getUniqueId())) {
                                TradeGUI gui = new TradeGUI(this, session, player);
                                player.openInventory(gui.getInventory());
                            }
                        }, 1L);
                    },
                    () -> {
                        // On cancel - reopen trade GUI
                        Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
                            if (isTrading(player.getUniqueId())) {
                                TradeGUI gui = new TradeGUI(this, session, player);
                                player.openInventory(gui.getInventory());
                            }
                        }, 1L);
                    }
                );
                confirmPage.open();
            }, 1L);
            return;
        }
        
        session.setConfirmed(player.getUniqueId(), true);
        notifyConfirmation(session, player);
        
        // Check if both confirmed
        if (session.isBothConfirmed()) {
            completeTrade(session);
        }
    }
    
    /**
     * Notify other player of confirmation.
     */
    private void notifyConfirmation(TradeSession session, Player confirmer) {
        Player other = Bukkit.getPlayer(session.getOtherPlayer(confirmer.getUniqueId()));
        if (other != null) {
            other.sendMessage(ChatColor.GREEN + confirmer.getName() + " 已确认交易！");
            playSound(other, Sound.BLOCK_NOTE_BLOCK_PLING);
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
        
        double moneyTax = 0;
        int expTax = 0;
        
        // Handle money transfer
        if (hasEconomy()) {
            double money1 = session.getPlayerMoney(session.getPlayer1());
            double money2 = session.getPlayerMoney(session.getPlayer2());
            
            // Apply tax
            double taxRate = config.getTradeTax();
            double tax1 = money1 * taxRate;
            double tax2 = money2 * taxRate;
            moneyTax = tax1 + tax2;
            
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
        
        // Handle experience transfer
        if (config.isEnableExpTrade()) {
            int exp1 = session.getPlayerExp(session.getPlayer1());
            int exp2 = session.getPlayerExp(session.getPlayer2());
            
            // Apply tax
            double expTaxRate = config.getExpTaxRate();
            int tax1 = (int)(exp1 * expTaxRate);
            int tax2 = (int)(exp2 * expTaxRate);
            expTax = tax1 + tax2;
            
            // Check experience
            if (exp1 > 0 && getTotalExperience(player1) < exp1) {
                cancelTrade(session, player1.getName() + " 经验不足");
                return;
            }
            if (exp2 > 0 && getTotalExperience(player2) < exp2) {
                cancelTrade(session, player2.getName() + " 经验不足");
                return;
            }
            
            // Transfer experience
            if (exp1 > 0) {
                setTotalExperience(player1, getTotalExperience(player1) - exp1);
                player2.giveExp(exp1 - tax1);
            }
            if (exp2 > 0) {
                setTotalExperience(player2, getTotalExperience(player2) - exp2);
                player1.giveExp(exp2 - tax2);
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
        
        // Close inventories
        player1.closeInventory();
        player2.closeInventory();
        
        session.setState(TradeSession.TradeState.COMPLETED);
        
        // Log the trade
        logService.logCompletedTrade(session, player1, player2, moneyTax, expTax);
        
        cleanupSession(session);
        
        // Notify players
        String completeMsg = ChatColor.translateAlternateColorCodes('&', config.getTradeCompleteMessage());
        player1.sendMessage(completeMsg);
        player2.sendMessage(completeMsg);
        
        // Play success effects
        playSuccessEffects(player1);
        playSuccessEffects(player2);
    }
    
    /**
     * Cancel a trade.
     */
    public void cancelTrade(TradeSession session, String reason) {
        Player player1 = Bukkit.getPlayer(session.getPlayer1());
        Player player2 = Bukkit.getPlayer(session.getPlayer2());
        
        // Log cancelled trade
        logService.logCancelledTrade(session, reason);
        
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
            playFailEffects(player1);
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
            playFailEffects(player2);
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
     * Cleanup expired requests every 10 seconds.
     * Scheduled task using @Scheduled annotation.
     */
    @Scheduled(period = 200, async = false)
    public void cleanupExpiredRequests() {
        int timeout = config.getRequestTimeout();
        Iterator<Map.Entry<UUID, TradeRequest>> it = pendingRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TradeRequest> entry = it.next();
            if (entry.getValue().isExpired(timeout)) {
                it.remove();
                removeBossBar(entry.getKey());
                
                // Notify receiver
                Player receiver = Bukkit.getPlayer(entry.getKey());
                if (receiver != null) {
                    receiver.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        config.getRequestTimeoutMessage()));
                }
            }
        }
    }
    
    // ==================== Sound and Particle Effects ====================
    
    /**
     * Play a sound to player.
     */
    public void playSound(Player player, Sound sound) {
        if (config.isEnableSounds() && player != null) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }
    
    /**
     * Play success effects (sound + particles).
     */
    private void playSuccessEffects(Player player) {
        if (player == null) return;
        
        playSound(player, Sound.ENTITY_PLAYER_LEVELUP);
        
        if (config.isEnableParticles()) {
            Location loc = player.getLocation().add(0, 1, 0);
            player.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, loc, 30, 0.5, 0.5, 0.5, 0.1);
            player.getWorld().spawnParticle(Particle.END_ROD, loc, 15, 0.3, 0.5, 0.3, 0.05);
        }
    }
    
    /**
     * Play fail effects (sound + particles).
     */
    private void playFailEffects(Player player) {
        if (player == null) return;
        
        playSound(player, Sound.ENTITY_VILLAGER_NO);
        
        if (config.isEnableParticles()) {
            Location loc = player.getLocation().add(0, 1, 0);
            player.getWorld().spawnParticle(Particle.SMOKE_NORMAL, loc, 20, 0.3, 0.3, 0.3, 0.05);
        }
    }
    
    // ==================== Experience Utilities ====================
    
    /**
     * Get total experience points for a player.
     */
    public int getTotalExperience(Player player) {
        int level = player.getLevel();
        int exp = (int) (player.getExp() * player.getExpToLevel());
        
        // Calculate total exp from levels
        int totalFromLevels;
        if (level <= 16) {
            totalFromLevels = level * level + 6 * level;
        } else if (level <= 31) {
            totalFromLevels = (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            totalFromLevels = (int) (4.5 * level * level - 162.5 * level + 2220);
        }
        
        return totalFromLevels + exp;
    }
    
    /**
     * Set total experience points for a player.
     */
    public void setTotalExperience(Player player, int totalExp) {
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        
        if (totalExp > 0) {
            player.giveExp(totalExp);
        }
    }
    
    /**
     * Get config.
     */
    public TradeConfig getConfig() {
        return config;
    }
    
    /**
     * Get log service.
     */
    public TradeLogService getLogService() {
        return logService;
    }
}
