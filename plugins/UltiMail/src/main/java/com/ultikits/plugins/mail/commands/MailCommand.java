package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Mail command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"mail", "inbox"},
    permission = "ultimail.use",
    description = "邮件系统"
)
public class MailCommand extends AbstractCommendExecutor {
    
    private final MailService mailService;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    
    public MailCommand(MailService mailService) {
        this.mailService = mailService;
    }
    
    @CmdMapping(format = "inbox")
    public void inbox(@CmdSender Player player) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (mails.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "收件箱为空！");
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== 收件箱 (" + mails.size() + " 封) ===");
        int index = 1;
        for (MailData mail : mails) {
            String status = mail.isRead() ? ChatColor.GRAY + "[已读]" : ChatColor.GREEN + "[未读]";
            String hasItems = mail.getItems() != null && !mail.getItems().isEmpty() ? 
                (mail.isClaimed() ? ChatColor.GRAY + "[已领取]" : ChatColor.YELLOW + "[有附件]") : "";
            
            player.sendMessage(String.format("%s%d. %s %s%s %s- %s%s",
                ChatColor.WHITE, index++,
                status,
                ChatColor.WHITE, mail.getSubject(),
                hasItems,
                ChatColor.GRAY, mail.getSenderName()
            ));
        }
        player.sendMessage(ChatColor.GRAY + "使用 /mail read <编号> 查看邮件");
    }
    
    @CmdMapping(format = "sent")
    public void sent(@CmdSender Player player) {
        List<MailData> mails = mailService.getSentMails(player.getUniqueId());
        
        if (mails.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "发件箱为空！");
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== 发件箱 (" + mails.size() + " 封) ===");
        int index = 1;
        for (MailData mail : mails) {
            String status = mail.isRead() ? ChatColor.GREEN + "[已读]" : ChatColor.GRAY + "[未读]";
            
            player.sendMessage(String.format("%s%d. %s %s%s %s- 发给 %s%s",
                ChatColor.WHITE, index++,
                status,
                ChatColor.WHITE, mail.getSubject(),
                ChatColor.GRAY,
                ChatColor.WHITE, mail.getReceiverName()
            ));
        }
    }
    
    @CmdMapping(format = "read <index>")
    public void read(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + "无效的邮件编号！");
            return;
        }
        
        MailData mail = mails.get(index - 1);
        mailService.markAsRead(mail);
        
        player.sendMessage(ChatColor.GOLD + "=== 邮件详情 ===");
        player.sendMessage(ChatColor.YELLOW + "发件人: " + ChatColor.WHITE + mail.getSenderName());
        player.sendMessage(ChatColor.YELLOW + "标题: " + ChatColor.WHITE + mail.getSubject());
        player.sendMessage(ChatColor.YELLOW + "时间: " + ChatColor.WHITE + DATE_FORMAT.format(new Date(mail.getSentTime())));
        player.sendMessage(ChatColor.YELLOW + "内容: ");
        player.sendMessage(ChatColor.WHITE + mail.getContent());
        
        if (mail.getItems() != null && !mail.getItems().isEmpty()) {
            if (mail.isClaimed()) {
                player.sendMessage(ChatColor.GRAY + "[附件已领取]");
            } else {
                player.sendMessage(ChatColor.YELLOW + "[有附件] 使用 /mail claim " + index + " 领取");
            }
        }
        
        player.sendMessage(ChatColor.GRAY + "使用 /mail delete " + index + " 删除此邮件");
    }
    
    @CmdMapping(format = "claim <index>")
    public void claim(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + "无效的邮件编号！");
            return;
        }
        
        MailData mail = mails.get(index - 1);
        
        if (mail.isClaimed()) {
            player.sendMessage(ChatColor.RED + "附件已经领取过了！");
            return;
        }
        
        if (mail.getItems() == null || mail.getItems().isEmpty()) {
            player.sendMessage(ChatColor.RED + "这封邮件没有附件！");
            return;
        }
        
        ItemStack[] items = mailService.claimItems(mail, player);
        player.sendMessage(ChatColor.GREEN + "成功领取了 " + items.length + " 个物品！");
    }
    
    @CmdMapping(format = "delete <index>")
    public void delete(@CmdSender Player player, @CmdParam("index") int index) {
        List<MailData> mails = mailService.getInbox(player.getUniqueId());
        
        if (index < 1 || index > mails.size()) {
            player.sendMessage(ChatColor.RED + "无效的邮件编号！");
            return;
        }
        
        MailData mail = mails.get(index - 1);
        
        // Check if has unclaimed items
        if (mail.getItems() != null && !mail.getItems().isEmpty() && !mail.isClaimed()) {
            player.sendMessage(ChatColor.RED + "请先领取附件再删除邮件！");
            return;
        }
        
        mailService.deleteMail(mail, player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "邮件已删除！");
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiMail 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/mail inbox" + ChatColor.WHITE + " - 查看收件箱");
        sender.sendMessage(ChatColor.YELLOW + "/mail sent" + ChatColor.WHITE + " - 查看发件箱");
        sender.sendMessage(ChatColor.YELLOW + "/mail read <编号>" + ChatColor.WHITE + " - 阅读邮件");
        sender.sendMessage(ChatColor.YELLOW + "/mail claim <编号>" + ChatColor.WHITE + " - 领取附件");
        sender.sendMessage(ChatColor.YELLOW + "/mail delete <编号>" + ChatColor.WHITE + " - 删除邮件");
        sender.sendMessage(ChatColor.YELLOW + "/sendmail <玩家> <标题>" + ChatColor.WHITE + " - 发送邮件");
    }
}
