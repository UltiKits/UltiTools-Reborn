package com.ultikits.plugins.mail.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing mail system.
 * <p>
 * Provides mail sending, receiving, claiming items, executing commands,
 * and batch operations.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@Service
public class MailService {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private MailConfig config;

    private Plugin bukkitPlugin;
    private DataOperator<MailData> dataOperator;

    // Cooldown tracking
    private final Map<UUID, Long> sendCooldowns = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>(){}.getType();

    /**
     * Initialize the mail service.
     */
    @PostConstruct
    public void init() {
        dataOperator = plugin.getDataOperator(MailData.class);
        bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
    }
    
    /**
     * Get the mail configuration.
     */
    public MailConfig getConfig() {
        return config;
    }
    
    /**
     * Send a mail to a player.
     * 
     * @param sender Sender player
     * @param receiverName Receiver name
     * @param subject Mail subject
     * @param content Mail content
     * @param items Attached items (can be null)
     * @return true if sent successfully
     */
    public boolean sendMail(Player sender, String receiverName, String subject, String content, ItemStack[] items) {
        return sendMail(sender, receiverName, subject, content, items, null);
    }
    
    /**
     * Send a mail to a player with optional commands.
     * This is the full API method for third-party plugins.
     * 
     * @param sender Sender player
     * @param receiverName Receiver name
     * @param subject Mail subject
     * @param content Mail content
     * @param items Attached items (can be null)
     * @param commands Commands to execute when read (can be null)
     * @return true if sent successfully
     */
    public boolean sendMail(Player sender, String receiverName, String subject, String content, 
                           ItemStack[] items, List<String> commands) {
        // Check cooldown
        if (isOnCooldown(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + i18n("send_cooldown"));
            return false;
        }
        
        // Validate subject and content
        if (subject.length() > config.getMaxSubjectLength()) {
            sender.sendMessage(ChatColor.RED + i18n("send_subject_too_long")
                .replace("{0}", String.valueOf(config.getMaxSubjectLength())));
            return false;
        }
        if (content.length() > config.getMaxContentLength()) {
            sender.sendMessage(ChatColor.RED + i18n("send_content_too_long")
                .replace("{0}", String.valueOf(config.getMaxContentLength())));
            return false;
        }
        
        // Get receiver UUID (may be offline)
        String receiverUuid = getPlayerUuid(receiverName);
        if (receiverUuid == null) {
            sender.sendMessage(ChatColor.RED + i18n("send_player_not_found")
                .replace("{0}", receiverName));
            return false;
        }
        
        // Create mail data
        MailData mail = createMailData(sender.getUniqueId().toString(), sender.getName(),
            receiverUuid, receiverName, subject, content, items, commands);
        
        if (mail == null) {
            sender.sendMessage(ChatColor.RED + i18n("send_items_too_many")
                .replace("{0}", String.valueOf(config.getMaxItems())));
            return false;
        }
        
        // Save to database
        dataOperator.insert(mail);
        
        // Set cooldown
        sendCooldowns.put(sender.getUniqueId(), System.currentTimeMillis());
        
        // Notify receiver if online
        notifyReceiver(receiverName, sender.getName());
        
        return true;
    }
    
    /**
     * Send mail to all players (broadcast).
     * 
     * @param sender Sender player
     * @param content Mail content
     * @param items Attached items (can be null, will be cloned for each player)
     */
    public void sendToAll(Player sender, String content, ItemStack[] items) {
        String subject = i18n("sendall_success");
        String senderUuid = sender.getUniqueId().toString();
        String senderName = sender.getName();
        
        new BukkitRunnable() {
            @Override
            public void run() {
                OfflinePlayer[] players = Bukkit.getOfflinePlayers();
                int total = players.length;
                int sent = 0;
                
                for (OfflinePlayer offline : players) {
                    if (offline.getUniqueId().equals(sender.getUniqueId())) {
                        continue; // Skip sender
                    }
                    
                    MailData mail = createMailData(senderUuid, senderName,
                        offline.getUniqueId().toString(), 
                        offline.getName() != null ? offline.getName() : "Unknown",
                        subject, content, items, null);
                    
                    if (mail != null) {
                        dataOperator.insert(mail);
                        sent++;
                        
                        // Notify if online
                        if (offline.isOnline()) {
                            Player onlinePlayer = offline.getPlayer();
                            if (onlinePlayer != null) {
                                notifyReceiver(onlinePlayer.getName(), senderName);
                            }
                        }
                    }
                    
                    // Progress update every 50 players
                    if (sent % 50 == 0) {
                        final int currentSent = sent;
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                sender.sendMessage(ChatColor.YELLOW + i18n("sendall_progress")
                                    .replace("{0}", String.valueOf(currentSent))
                                    .replace("{1}", String.valueOf(total)));
                            }
                        }.runTask(bukkitPlugin);
                    }
                }
                
                // Final notification
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        sender.sendMessage(ChatColor.GREEN + i18n("sendall_success"));
                    }
                }.runTask(bukkitPlugin);
            }
        }.runTaskAsynchronously(bukkitPlugin);
    }
    
    /**
     * Creates a MailData object with the given parameters.
     */
    private MailData createMailData(String senderUuid, String senderName, 
                                    String receiverUuid, String receiverName,
                                    String subject, String content,
                                    ItemStack[] items, List<String> commands) {
        MailData mail = new MailData();
        mail.setSenderUuid(senderUuid);
        mail.setSenderName(senderName);
        mail.setReceiverUuid(receiverUuid);
        mail.setReceiverName(receiverName);
        mail.setSubject(subject);
        mail.setContent(content);
        mail.setSentTime(System.currentTimeMillis());
        
        // Serialize items if any
        if (items != null && items.length > 0) {
            List<ItemStack> validItems = new ArrayList<>();
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    validItems.add(item);
                }
            }
            if (!validItems.isEmpty()) {
                if (validItems.size() > config.getMaxItems()) {
                    return null; // Too many items
                }
                mail.setItems(serializeItems(validItems.toArray(new ItemStack[0])));
            }
        }
        
        // Serialize commands if any
        if (commands != null && !commands.isEmpty()) {
            mail.setCommands(GSON.toJson(commands));
        }
        
        return mail;
    }
    
    /**
     * Notify receiver about new mail.
     */
    private void notifyReceiver(String receiverName, String senderName) {
        Player receiver = Bukkit.getPlayerExact(receiverName);
        if (receiver != null && receiver.isOnline()) {
            String message = config.getMailReceivedMessage().replace("{SENDER}", senderName);
            receiver.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }
    
    /**
     * Get inbox mails for a player.
     *
     * @param playerUuid Player UUID
     * @return List of received mails
     */
    public List<MailData> getInbox(UUID playerUuid) {
        List<MailData> mails = dataOperator.query()
            .where("receiver_uuid").eq(playerUuid.toString())
            .list();

        // Filter out deleted
        List<MailData> result = new ArrayList<>();
        for (MailData mail : mails) {
            if (!mail.isDeletedByReceiver()) {
                result.add(mail);
            }
        }

        // Sort by time descending
        result.sort((a, b) -> Long.compare(b.getSentTime(), a.getSentTime()));
        return result;
    }
    
    /**
     * Get sent mails for a player.
     *
     * @param playerUuid Player UUID
     * @return List of sent mails
     */
    public List<MailData> getSentMails(UUID playerUuid) {
        List<MailData> mails = dataOperator.query()
            .where("sender_uuid").eq(playerUuid.toString())
            .list();

        // Filter out deleted
        List<MailData> result = new ArrayList<>();
        for (MailData mail : mails) {
            if (!mail.isDeletedBySender()) {
                result.add(mail);
            }
        }

        result.sort((a, b) -> Long.compare(b.getSentTime(), a.getSentTime()));
        return result;
    }
    
    /**
     * Get unread mail count.
     */
    public int getUnreadCount(UUID playerUuid) {
        List<MailData> inbox = getInbox(playerUuid);
        int count = 0;
        for (MailData mail : inbox) {
            if (!mail.isRead()) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Mark mail as read.
     */
    public void markAsRead(MailData mail) {
        mail.setRead(true);
        try {
            dataOperator.update(mail);
        } catch (IllegalAccessException e) {
            plugin.getLogger().error("Failed to mark mail as read: " + e.getMessage());
        }
    }
    
    /**
     * Get number of items in a mail.
     */
    public int getItemCount(MailData mail) {
        if (!mail.hasItems()) {
            return 0;
        }
        ItemStack[] items = deserializeItems(mail.getItems());
        return items != null ? items.length : 0;
    }
    
    /**
     * Claim items from mail.
     * Does NOT check for inventory space - caller should check first.
     * 
     * @return claimed items, or empty array if already claimed
     */
    public ItemStack[] claimItems(MailData mail, Player player) {
        if (mail.isClaimed() || mail.getItems() == null || mail.getItems().isEmpty()) {
            return new ItemStack[0];
        }
        
        ItemStack[] items = deserializeItems(mail.getItems());
        if (items == null || items.length == 0) {
            return new ItemStack[0];
        }
        
        // Give items to player
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(items);
        
        // Drop overflow items (should not happen if caller checked space)
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        
        // Mark as claimed
        mail.setClaimed(true);
        try {
            dataOperator.update(mail);
        } catch (IllegalAccessException e) {
            plugin.getLogger().error("Failed to claim items: " + e.getMessage());
        }
        
        return items;
    }
    
    /**
     * Execute commands attached to a mail.
     * Supports mixed mode: normal commands run as player, console: prefixed commands run as console.
     * Supports %player% placeholder.
     */
    public void executeMailCommands(Player player, MailData mail) {
        if (!mail.hasCommands() || mail.isCommandsExecuted()) {
            return;
        }
        
        try {
            List<String> commands = GSON.fromJson(mail.getCommands(), STRING_LIST_TYPE);
            if (commands == null || commands.isEmpty()) {
                return;
            }
            
            for (String command : commands) {
                // Replace placeholders
                String processedCmd = command.replace("%player%", player.getName());
                
                // Check if console command
                if (processedCmd.toLowerCase().startsWith("console:")) {
                    String consoleCmd = processedCmd.substring(8).trim();
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd);
                } else {
                    player.performCommand(processedCmd);
                }
            }
            
            // Mark commands as executed
            mail.setCommandsExecuted(true);
            dataOperator.update(mail);
            
        } catch (Exception e) {
            plugin.getLogger().error("Failed to execute mail commands: " + e.getMessage());
        }
    }
    
    /**
     * Delete mail (soft delete).
     */
    public void deleteMail(MailData mail, UUID playerUuid) {
        if (mail.getSenderUuid().equals(playerUuid.toString())) {
            mail.setDeletedBySender(true);
        }
        if (mail.getReceiverUuid().equals(playerUuid.toString())) {
            mail.setDeletedByReceiver(true);
        }
        
        // If both deleted, really delete
        if (mail.isDeletedBySender() && mail.isDeletedByReceiver()) {
            dataOperator.delById(mail.getId());
        } else {
            try {
                dataOperator.update(mail);
            } catch (IllegalAccessException e) {
                plugin.getLogger().error("Failed to update mail: " + e.getMessage());
            }
        }
    }
    
    /**
     * Delete all mails for a player (receiver side).
     * 
     * @return number of mails deleted
     */
    public int deleteAllByReceiver(UUID playerUuid) {
        List<MailData> mails = getInbox(playerUuid);
        int count = 0;
        
        for (MailData mail : mails) {
            // Skip if has unclaimed items
            if (mail.hasItems() && !mail.isClaimed()) {
                continue;
            }
            deleteMail(mail, playerUuid);
            count++;
        }
        
        return count;
    }
    
    /**
     * Delete all read mails for a player (receiver side).
     * 
     * @return number of mails deleted
     */
    public int deleteReadByReceiver(UUID playerUuid) {
        List<MailData> mails = getInbox(playerUuid);
        int count = 0;
        
        for (MailData mail : mails) {
            if (!mail.isRead()) {
                continue;
            }
            // Skip if has unclaimed items
            if (mail.hasItems() && !mail.isClaimed()) {
                continue;
            }
            deleteMail(mail, playerUuid);
            count++;
        }
        
        return count;
    }
    
    /**
     * Get mail by ID.
     */
    public MailData getMail(String id) {
        return dataOperator.getById(id);
    }
    
    /**
     * Internal method for sending mail programmatically without player sender.
     * Used by GameMailService integration for cross-module mail.
     * 
     * @param senderUuid Sender UUID (can be null for system mail)
     * @param senderName Sender name
     * @param receiverName Receiver name
     * @param subject Mail subject
     * @param content Mail content
     * @param items Attached items (can be null)
     * @return true if sent successfully
     */
    public boolean sendMailInternal(UUID senderUuid, String senderName, String receiverName, 
                                    String subject, String content, ItemStack[] items) {
        // Get receiver UUID (may be offline)
        String receiverUuid = getPlayerUuid(receiverName);
        if (receiverUuid == null) {
            return false;
        }
        
        // Create mail data
        MailData mail = createMailData(
            senderUuid != null ? senderUuid.toString() : null,
            senderName,
            receiverUuid, 
            receiverName, 
            subject, 
            content, 
            items, 
            null
        );
        
        if (mail == null) {
            return false;
        }
        
        // Save to database
        dataOperator.insert(mail);
        
        // Notify receiver if online
        notifyReceiver(receiverName, senderName);
        
        return true;
    }
    
    /**
     * Check if player is on send cooldown.
     */
    private boolean isOnCooldown(UUID playerUuid) {
        Long lastSend = sendCooldowns.get(playerUuid);
        if (lastSend == null) {
            return false;
        }
        return System.currentTimeMillis() - lastSend < config.getSendCooldown() * 1000L;
    }
    
    /**
     * Get player UUID by name (handles offline players).
     */
    private String getPlayerUuid(String name) {
        // Check online first
        Player player = Bukkit.getPlayerExact(name);
        if (player != null) {
            return player.getUniqueId().toString();
        }
        
        // Check offline
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId().toString();
        }
        
        return null;
    }
    
    /**
     * Serialize ItemStack array to Base64.
     */
    private String serializeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to serialize items: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Deserialize ItemStack array from Base64.
     */
    private ItemStack[] deserializeItems(String data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            
            return items;
        } catch (Exception e) {
            plugin.getLogger().warn("Failed to deserialize items: " + e.getMessage());
            return new ItemStack[0];
        }
    }
    
    /**
     * Shortcut for i18n.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
