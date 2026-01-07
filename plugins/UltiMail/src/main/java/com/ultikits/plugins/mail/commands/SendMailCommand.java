package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Send mail command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"sendmail", "sm"},
    permission = "ultimail.send",
    description = "发送邮件"
)
public class SendMailCommand extends AbstractCommendExecutor {
    
    private final MailService mailService;
    private final Plugin plugin;
    
    public SendMailCommand(MailService mailService, Plugin plugin) {
        this.mailService = mailService;
        this.plugin = plugin;
    }
    
    @CmdMapping(format = "<player> <subject>")
    public void sendMail(@CmdSender Player sender, @CmdParam("player") String receiver, @CmdParam("subject") String subject) {
        // Start conversation for content
        ConversationFactory factory = new ConversationFactory(plugin)
            .withFirstPrompt(new ContentPrompt(receiver, subject))
            .withEscapeSequence("cancel")
            .withTimeout(120)
            .thatExcludesNonPlayersWithMessage("只有玩家可以发送邮件")
            .addConversationAbandonedListener(event -> {
                if (!event.gracefulExit()) {
                    event.getContext().getForWhom().sendRawMessage(ChatColor.RED + "邮件发送已取消。");
                }
            });
        
        Conversation conversation = factory.buildConversation(sender);
        conversation.getContext().setSessionData("mailService", mailService);
        conversation.begin();
    }
    
    @CmdMapping(format = "<player> <subject> attach")
    public void sendMailWithItems(@CmdSender Player sender, @CmdParam("player") String receiver, @CmdParam("subject") String subject) {
        // Get items from main hand
        ItemStack item = sender.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "请在主手拿着要附加的物品！");
            return;
        }
        
        // Start conversation with attachment
        ConversationFactory factory = new ConversationFactory(plugin)
            .withFirstPrompt(new ContentPrompt(receiver, subject))
            .withEscapeSequence("cancel")
            .withTimeout(120)
            .thatExcludesNonPlayersWithMessage("只有玩家可以发送邮件");
        
        Conversation conversation = factory.buildConversation(sender);
        conversation.getContext().setSessionData("mailService", mailService);
        conversation.getContext().setSessionData("attachItem", item.clone());
        conversation.begin();
        
        // Remove item from inventory after starting conversation
        sender.getInventory().setItemInMainHand(null);
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 发送邮件帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/sendmail <玩家> <标题>" + ChatColor.WHITE + " - 发送文字邮件");
        player.sendMessage(ChatColor.YELLOW + "/sendmail <玩家> <标题> attach" + ChatColor.WHITE + " - 发送带附件邮件（主手物品）");
        player.sendMessage(ChatColor.GRAY + "输入 'cancel' 可取消发送");
    }
    
    /**
     * Content input prompt.
     */
    private static class ContentPrompt extends StringPrompt {
        private final String receiver;
        private final String subject;
        
        public ContentPrompt(String receiver, String subject) {
            this.receiver = receiver;
            this.subject = subject;
        }
        
        @Override
        public String getPromptText(ConversationContext context) {
            return ChatColor.YELLOW + "请输入邮件内容 (输入 'cancel' 取消):";
        }
        
        @Override
        public Prompt acceptInput(ConversationContext context, String input) {
            if (input == null || input.equalsIgnoreCase("cancel")) {
                return Prompt.END_OF_CONVERSATION;
            }
            
            Player sender = (Player) context.getForWhom();
            MailService service = (MailService) context.getSessionData("mailService");
            ItemStack attachItem = (ItemStack) context.getSessionData("attachItem");
            
            ItemStack[] items = attachItem != null ? new ItemStack[]{attachItem} : null;
            
            boolean success = service.sendMail(sender, receiver, subject, input, items);
            
            if (success) {
                sender.sendMessage(ChatColor.GREEN + "邮件已发送给 " + receiver + "！");
            }
            
            return Prompt.END_OF_CONVERSATION;
        }
    }
}
