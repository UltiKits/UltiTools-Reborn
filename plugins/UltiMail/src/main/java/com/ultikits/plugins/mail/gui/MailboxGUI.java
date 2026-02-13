package com.ultikits.plugins.mail.gui;

import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;
import mc.obliviate.inventory.Icon;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * GUI for displaying inbox with pagination.
 * <p>
 * Features:
 * - Paginated mail list
 * - Different icons for read/unread mails
 * - Click to read mail and claim items
 * - Shows mail info in lore
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class MailboxGUI extends BasePaginationPage {
    
    private final MailService mailService;
    private final UltiToolsPlugin plugin;
    private final List<MailData> mails;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public MailboxGUI(@NotNull Player player, MailService mailService, UltiToolsPlugin plugin) {
        super(player, "mailbox-gui", plugin.i18n("inbox_gui_title").replace("{0}", player.getName()), 6);
        this.mailService = mailService;
        this.plugin = plugin;
        this.mails = mailService.getInbox(player.getUniqueId());
    }
    
    @Override
    protected List<Icon> provideItems() {
        List<Icon> icons = new ArrayList<>();
        
        for (MailData mail : mails) {
            Icon icon = createMailIcon(mail);
            icons.add(icon);
        }
        
        return icons;
    }
    
    /**
     * Creates an icon for a mail item.
     */
    private Icon createMailIcon(MailData mail) {
        // Use different materials for read/unread
        Material material = mail.isRead() ? Material.BOOK : Material.WRITABLE_BOOK;
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        
        if (meta != null) {
            // Title: subject with read status
            String status = mail.isRead() ? 
                ChatColor.GRAY + i18n("lore_read") : 
                ChatColor.GREEN + i18n("lore_unread");
            meta.setDisplayName(status + " " + ChatColor.WHITE + mail.getSubject());
            
            // Lore: mail details
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + i18n("lore_from").replace("{0}", mail.getSenderName()));
            lore.add(ChatColor.GRAY + i18n("lore_time").replace("{0}", DATE_FORMAT.format(new Date(mail.getSentTime()))));
            lore.add("");
            
            // Content preview (first 30 chars)
            String contentPreview = mail.getContent();
            if (contentPreview.length() > 30) {
                contentPreview = contentPreview.substring(0, 30) + "...";
            }
            lore.add(ChatColor.YELLOW + i18n("lore_content"));
            lore.add(ChatColor.WHITE + contentPreview);
            lore.add("");
            
            // Items info
            if (mail.hasItems()) {
                if (mail.isClaimed()) {
                    lore.add(ChatColor.GRAY + i18n("inbox_status_claimed"));
                } else {
                    lore.add(ChatColor.GOLD + i18n("inbox_status_has_items"));
                    lore.add(ChatColor.YELLOW + i18n("lore_click_to_claim"));
                }
            }
            
            // Click hint
            if (!mail.isRead()) {
                lore.add(ChatColor.GREEN + i18n("lore_click_to_read"));
            }
            
            meta.setLore(lore);
            itemStack.setItemMeta(meta);
        }
        
        Icon icon = new Icon(itemStack);
        icon.onClick(e -> handleMailClick(mail));
        
        return icon;
    }
    
    /**
     * Handles click on a mail item.
     */
    private void handleMailClick(MailData mail) {
        // Mark as read
        if (!mail.isRead()) {
            mailService.markAsRead(mail);
            
            // Execute commands if any (only once)
            if (mail.hasCommands() && !mail.isCommandsExecuted()) {
                mailService.executeMailCommands(player, mail);
            }
        }
        
        // Try to claim items if has unclaimed items
        if (mail.hasItems() && !mail.isClaimed()) {
            // Check inventory space
            int requiredSlots = mailService.getItemCount(mail);
            int emptySlots = countEmptySlots(player);
            
            if (emptySlots < requiredSlots) {
                player.sendMessage(ChatColor.RED + i18n("claim_inventory_full")
                    .replace("{0}", String.valueOf(requiredSlots)));
            } else {
                ItemStack[] items = mailService.claimItems(mail, player);
                if (items.length > 0) {
                    player.sendMessage(ChatColor.GREEN + i18n("claim_success")
                        .replace("{0}", String.valueOf(items.length)));
                }
            }
        }
        
        // Refresh GUI
        updatePaginatedContent();
    }
    
    /**
     * Counts empty inventory slots.
     */
    private int countEmptySlots(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Shortcut for i18n.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
    
    @Override
    protected void setupNavigationButtons() {
        super.setupNavigationButtons();
        
        // Add close button at center
        Icon closeButton = createActionButton(Colors.RED, 
            ChatColor.RED + i18n("gui_close"), 
            e -> player.closeInventory());
        addToBottomRow(4, closeButton);
    }
}
