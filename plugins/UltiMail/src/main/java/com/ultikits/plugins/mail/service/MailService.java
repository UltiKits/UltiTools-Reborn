package com.ultikits.plugins.mail.service;

import com.ultikits.plugins.mail.UltiMail;
import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.WhereCondition;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing mail system.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class MailService {
    
    @Autowired
    private MailConfig config;
    
    private DataOperator<MailData> dataOperator;
    
    // Cooldown tracking
    private final Map<UUID, Long> sendCooldowns = new ConcurrentHashMap<>();
    
    /**
     * Initialize the mail service.
     */
    public void init() {
        dataOperator = UltiMail.getInstance().getDataOperator(MailData.class);
    }
    
    /**
     * Send a mail to a player.
     * 
     * @param sender Sender player
     * @param receiverName Receiver name
     * @param subject Mail subject
     * @param content Mail content
     * @param items Attached items
     * @return true if sent successfully
     */
    public boolean sendMail(Player sender, String receiverName, String subject, String content, ItemStack[] items) {
        // Check cooldown
        if (isOnCooldown(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "发送邮件太频繁，请稍后再试！");
            return false;
        }
        
        // Validate subject and content
        if (subject.length() > config.getMaxSubjectLength()) {
            sender.sendMessage(ChatColor.RED + "邮件标题过长！最多 " + config.getMaxSubjectLength() + " 字符");
            return false;
        }
        if (content.length() > config.getMaxContentLength()) {
            sender.sendMessage(ChatColor.RED + "邮件内容过长！最多 " + config.getMaxContentLength() + " 字符");
            return false;
        }
        
        // Get receiver UUID (may be offline)
        String receiverUuid = getPlayerUuid(receiverName);
        if (receiverUuid == null) {
            sender.sendMessage(ChatColor.RED + "找不到玩家 " + receiverName + "！");
            return false;
        }
        
        // Create mail data
        MailData mail = new MailData();
        mail.setSenderUuid(sender.getUniqueId().toString());
        mail.setSenderName(sender.getName());
        mail.setReceiverUuid(receiverUuid);
        mail.setReceiverName(receiverName);
        mail.setSubject(subject);
        mail.setContent(content);
        mail.setSentTime(System.currentTimeMillis());
        
        // Serialize items if any
        if (items != null && items.length > 0) {
            // Filter out null/air items
            List<ItemStack> validItems = new ArrayList<>();
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    validItems.add(item);
                }
            }
            if (!validItems.isEmpty()) {
                if (validItems.size() > config.getMaxItems()) {
                    sender.sendMessage(ChatColor.RED + "附件物品过多！最多 " + config.getMaxItems() + " 个");
                    return false;
                }
                mail.setItems(serializeItems(validItems.toArray(new ItemStack[0])));
            }
        }
        
        // Save to database
        dataOperator.insert(mail);
        
        // Set cooldown
        sendCooldowns.put(sender.getUniqueId(), System.currentTimeMillis());
        
        // Notify receiver if online
        Player receiver = Bukkit.getPlayerExact(receiverName);
        if (receiver != null && receiver.isOnline()) {
            String message = config.getMailReceivedMessage().replace("{SENDER}", sender.getName());
            receiver.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
        
        return true;
    }
    
    /**
     * Get inbox mails for a player.
     * 
     * @param playerUuid Player UUID
     * @return List of received mails
     */
    public List<MailData> getInbox(UUID playerUuid) {
        List<MailData> mails = dataOperator.getAll(
            WhereCondition.builder()
                .column("receiver_uuid")
                .value(playerUuid.toString())
                .build()
        );
        
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
        List<MailData> mails = dataOperator.getAll(
            WhereCondition.builder()
                .column("sender_uuid")
                .value(playerUuid.toString())
                .build()
        );
        
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
        dataOperator.update(mail);
    }
    
    /**
     * Claim items from mail.
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
        
        // Drop overflow items
        for (ItemStack item : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        
        // Mark as claimed
        mail.setClaimed(true);
        dataOperator.update(mail);
        
        return items;
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
            dataOperator.delete(mail);
        } else {
            dataOperator.update(mail);
        }
    }
    
    /**
     * Get mail by ID.
     */
    public MailData getMail(String id) {
        return dataOperator.getById(id);
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
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
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
            UltiMail.getInstance().getLogger().warning("Failed to serialize items: " + e.getMessage());
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
            UltiMail.getInstance().getLogger().warning("Failed to deserialize items: " + e.getMessage());
            return new ItemStack[0];
        }
    }
}
