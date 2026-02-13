package com.ultikits.plugins.mail.gui;

import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;
import com.ultikits.ultitools.entities.Colors;
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
 * GUI for displaying sent mails with pagination.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class SentboxGUI extends BasePaginationPage {
    
    private final MailService mailService;
    private final UltiToolsPlugin plugin;
    private final List<MailData> mails;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public SentboxGUI(@NotNull Player player, MailService mailService, UltiToolsPlugin plugin) {
        super(player, "sentbox-gui", plugin.i18n("sentbox_gui_title").replace("{0}", player.getName()), 6);
        this.mailService = mailService;
        this.plugin = plugin;
        this.mails = mailService.getSentMails(player.getUniqueId());
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
     * Creates an icon for a sent mail item.
     */
    private Icon createMailIcon(MailData mail) {
        // Sent mails use paper icon
        Material material = mail.isRead() ? Material.MAP : Material.PAPER;
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        
        if (meta != null) {
            // Title with read status (by receiver)
            String status = mail.isRead() ? 
                ChatColor.GREEN + i18n("inbox_status_read") : 
                ChatColor.GRAY + i18n("inbox_status_unread");
            meta.setDisplayName(status + " " + ChatColor.WHITE + mail.getSubject());
            
            // Lore
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + i18n("lore_to").replace("{0}", mail.getReceiverName()));
            lore.add(ChatColor.GRAY + i18n("lore_time").replace("{0}", DATE_FORMAT.format(new Date(mail.getSentTime()))));
            lore.add("");
            
            // Content preview
            String contentPreview = mail.getContent();
            if (contentPreview.length() > 30) {
                contentPreview = contentPreview.substring(0, 30) + "...";
            }
            lore.add(ChatColor.YELLOW + i18n("lore_content"));
            lore.add(ChatColor.WHITE + contentPreview);
            
            // Items info
            if (mail.hasItems()) {
                lore.add("");
                if (mail.isClaimed()) {
                    lore.add(ChatColor.GREEN + i18n("inbox_status_claimed"));
                } else {
                    lore.add(ChatColor.GOLD + i18n("inbox_status_has_items"));
                }
            }
            
            meta.setLore(lore);
            itemStack.setItemMeta(meta);
        }
        
        Icon icon = new Icon(itemStack);
        // Sent mails are read-only in GUI
        icon.onClick(e -> {
            // Just show message, no action
            player.sendMessage(ChatColor.GRAY + "---");
            player.sendMessage(ChatColor.YELLOW + i18n("lore_to").replace("{0}", mail.getReceiverName()));
            player.sendMessage(ChatColor.WHITE + mail.getContent());
            player.sendMessage(ChatColor.GRAY + "---");
        });
        
        return icon;
    }
    
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
