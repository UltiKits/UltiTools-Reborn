package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.gui.AttachmentSelectorPage;
import com.ultikits.plugins.mail.gui.MailboxGUI;
import com.ultikits.plugins.mail.gui.SentboxGUI;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Mail command executor.
 * <p>
 * Provides commands for inbox, sentbox, reading, claiming, deleting mails,
 * batch operations, and admin broadcast.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"mail", "inbox"},
    permission = "ultimail.use",
    description = "邮件系统"
)
public class MailCommand extends BaseCommandExecutor {
    
    @Autowired
    private UltiToolsPlugin plugin;

    private final MailService mailService;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public MailCommand(MailService mailService) {
        this.mailService = mailService;
    }
    
    // ==================== GUI Commands ====================
    
    /**
     * Open inbox GUI.
     */
    @CmdMapping(format = "read")
    public void openInboxGUI(@CmdSender Player player) {
        new MailboxGUI(player, mailService, plugin).open();
    }
    
    /**
     * Open sentbox GUI.
     */
    @CmdMapping(format = "sentgui")
    public void openSentboxGUI(@CmdSender Player player) {
        new SentboxGUI(player, mailService, plugin).open();
    }
    
    // ==================== Text Commands ====================
    
    @CmdMapping(format = "inbox")
    public void inbox(@CmdSender Player player) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (mails.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + i18n("inbox_empty"));
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + i18n("inbox_title").replace("{0}", String.valueOf(mails.size())));
        int index = 1;
        for (MailData mail : mails) {
            String status = mail.isRead() ? 
                ChatColor.GRAY + i18n("inbox_status_read") : 
                ChatColor.GREEN + i18n("inbox_status_unread");
            String hasItems = mail.hasItems() ? 
                (mail.isClaimed() ? ChatColor.GRAY + i18n("inbox_status_claimed") : ChatColor.YELLOW + i18n("inbox_status_has_items")) : "";
            
            player.sendMessage(String.format("%s%d. %s %s%s %s- %s%s",
                ChatColor.WHITE, index++,
                status,
                ChatColor.WHITE, mail.getSubject(),
                hasItems,
                ChatColor.GRAY, mail.getSenderName()
            ));
        }
        player.sendMessage(ChatColor.GRAY + i18n("inbox_hint"));
    }
    
    @CmdMapping(format = "sent")
    public void sent(@CmdSender Player player) {
        List<MailData> mails = mailService.getSentMails(player.getUniqueId());
        
        if (mails.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + i18n("sentbox_empty"));
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + i18n("sentbox_title").replace("{0}", String.valueOf(mails.size())));
        int index = 1;
        for (MailData mail : mails) {
            String status = mail.isRead() ? 
                ChatColor.GREEN + i18n("inbox_status_read") : 
                ChatColor.GRAY + i18n("inbox_status_unread");
            
            player.sendMessage(String.format("%s%d. %s %s%s %s- %s %s%s",
                ChatColor.WHITE, index++,
                status,
                ChatColor.WHITE, mail.getSubject(),
                ChatColor.GRAY,
                i18n("sentbox_to"),
                ChatColor.WHITE, mail.getReceiverName()
            ));
        }
    }
    
    @CmdMapping(format = "read <index>")
    public void readByIndex(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + i18n("error_invalid_index"));
            return;
        }
        
        MailData mail = mails.get(index - 1);
        mailService.markAsRead(mail);
        
        // Execute commands if any
        if (mail.hasCommands() && !mail.isCommandsExecuted()) {
            mailService.executeMailCommands(player, mail);
        }
        
        player.sendMessage(ChatColor.GOLD + i18n("mail_detail_title"));
        player.sendMessage(ChatColor.YELLOW + i18n("mail_detail_sender") + ChatColor.WHITE + mail.getSenderName());
        player.sendMessage(ChatColor.YELLOW + i18n("mail_detail_subject") + ChatColor.WHITE + mail.getSubject());
        player.sendMessage(ChatColor.YELLOW + i18n("mail_detail_time") + ChatColor.WHITE + DATE_FORMAT.format(new Date(mail.getSentTime())));
        player.sendMessage(ChatColor.YELLOW + i18n("mail_detail_content"));
        player.sendMessage(ChatColor.WHITE + mail.getContent());
        
        if (mail.hasItems()) {
            if (mail.isClaimed()) {
                player.sendMessage(ChatColor.GRAY + i18n("mail_detail_items_claimed"));
            } else {
                player.sendMessage(ChatColor.YELLOW + i18n("mail_detail_items_hint").replace("{0}", String.valueOf(index)));
            }
        }
        
        player.sendMessage(ChatColor.GRAY + i18n("mail_detail_delete_hint").replace("{0}", String.valueOf(index)));
    }
    
    @CmdMapping(format = "claim <index>")
    public void claim(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + i18n("error_invalid_index"));
            return;
        }
        
        MailData mail = mails.get(index - 1);
        
        if (mail.isClaimed()) {
            player.sendMessage(ChatColor.RED + i18n("claim_already_claimed"));
            return;
        }
        
        if (!mail.hasItems()) {
            player.sendMessage(ChatColor.RED + i18n("claim_no_items"));
            return;
        }
        
        // Check inventory space
        int requiredSlots = mailService.getItemCount(mail);
        int emptySlots = countEmptySlots(player);
        
        if (emptySlots < requiredSlots) {
            player.sendMessage(ChatColor.RED + i18n("claim_inventory_full")
                .replace("{0}", String.valueOf(requiredSlots)));
            return;
        }
        
        ItemStack[] items = mailService.claimItems(mail, player);
        player.sendMessage(ChatColor.GREEN + i18n("claim_success")
            .replace("{0}", String.valueOf(items.length)));
    }
    
    @CmdMapping(format = "delete <index>")
    public void delete(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + i18n("error_invalid_index"));
            return;
        }
        
        MailData mail = mails.get(index - 1);
        
        // Check if has unclaimed items
        if (mail.hasItems() && !mail.isClaimed()) {
            player.sendMessage(ChatColor.RED + i18n("delete_claim_first"));
            return;
        }
        
        mailService.deleteMail(mail, player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + i18n("delete_success"));
    }
    
    // ==================== Batch Operations ====================
    
    /**
     * Delete all mails.
     */
    @CmdMapping(format = "delall")
    public void deleteAll(@CmdSender Player player) {
        int count = mailService.deleteAllByReceiver(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + i18n("delete_all_success") + " (" + count + ")");
    }
    
    /**
     * Delete all read mails.
     */
    @CmdMapping(format = "delread")
    public void deleteRead(@CmdSender Player player) {
        int count = mailService.deleteReadByReceiver(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + i18n("delete_read_success") + " (" + count + ")");
    }
    
    // ==================== Admin Commands ====================
    
    /**
     * Send mail to all players (text only).
     */
    @CmdMapping(format = "sendall <content>", permission = "ultimail.admin.sendall")
    public void sendAll(@CmdSender Player player, @CmdParam("content") String content) {
        mailService.sendToAll(player, content, null);
    }
    
    /**
     * Send mail to all players with attachments from GUI.
     */
    @CmdMapping(format = "sendall <content> items", permission = "ultimail.admin.sendall")
    public void sendAllWithItems(@CmdSender Player player, @CmdParam("content") String content) {
        int maxItems = mailService.getConfig().getMaxItems();
        
        new AttachmentSelectorPage(player, maxItems, plugin,
            items -> {
                if (items != null && items.length > 0) {
                    mailService.sendToAll(player, content, items);
                    player.sendMessage(ChatColor.GREEN + i18n("send_attachment_added")
                        .replace("{0}", String.valueOf(items.length)));
                } else {
                    mailService.sendToAll(player, content, null);
                }
            },
            () -> player.sendMessage(ChatColor.YELLOW + i18n("send_cancelled"))
        ).open();
    }
    
    // ==================== Help ====================
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + i18n("help_title"));
        sender.sendMessage(ChatColor.YELLOW + "/mail read" + ChatColor.WHITE + " - " + i18n("help_read"));
        sender.sendMessage(ChatColor.YELLOW + "/mail inbox" + ChatColor.WHITE + " - " + i18n("help_inbox"));
        sender.sendMessage(ChatColor.YELLOW + "/mail sent" + ChatColor.WHITE + " - " + i18n("help_sent"));
        sender.sendMessage(ChatColor.YELLOW + "/mail read <编号>" + ChatColor.WHITE + " - " + i18n("help_read_index"));
        sender.sendMessage(ChatColor.YELLOW + "/mail claim <编号>" + ChatColor.WHITE + " - " + i18n("help_claim"));
        sender.sendMessage(ChatColor.YELLOW + "/mail delete <编号>" + ChatColor.WHITE + " - " + i18n("help_delete"));
        sender.sendMessage(ChatColor.YELLOW + "/mail delall" + ChatColor.WHITE + " - " + i18n("help_delall"));
        sender.sendMessage(ChatColor.YELLOW + "/mail delread" + ChatColor.WHITE + " - " + i18n("help_delread"));
        sender.sendMessage(ChatColor.YELLOW + "/sendmail <玩家> <标题>" + ChatColor.WHITE + " - " + i18n("help_sendmail"));
        
        if (sender.hasPermission("ultimail.admin.sendall")) {
            sender.sendMessage(ChatColor.RED + "/mail sendall <内容>" + ChatColor.WHITE + " - " + i18n("help_sendall"));
        }
    }
    
    // ==================== Utilities ====================
    
    private int countEmptySlots(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                count++;
            }
        }
        return count;
    }
    
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
