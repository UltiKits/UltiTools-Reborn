package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.gui.AttachmentSelectorPage;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.conversations.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

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
public class SendMailCommand extends BaseCommandExecutor {
    
    private static final String ADMIN_PERMISSION = "ultimail.admin.multiattach";

    @Autowired
    private UltiToolsPlugin ultiPlugin;

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
            .thatExcludesNonPlayersWithMessage(i18n("error_player_only"))
            .addConversationAbandonedListener(event -> {
                if (!event.gracefulExit()) {
                    event.getContext().getForWhom().sendRawMessage(ChatColor.RED + i18n("send_cancelled"));
                }
            });
        
        Conversation conversation = factory.buildConversation(sender);
        conversation.getContext().setSessionData("mailService", mailService);
        conversation.getContext().setSessionData("ultiPlugin", ultiPlugin);
        conversation.begin();
    }
    
    @CmdMapping(format = "<player> <subject> attach")
    public void sendMailWithItems(@CmdSender Player sender, @CmdParam("player") String receiver, @CmdParam("subject") String subject) {
        // Check if admin has multi-attach permission
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            // Open multi-attachment GUI for admins
            sender.sendMessage(ChatColor.GREEN + i18n("attachment_gui_hint"));
            
            int maxItems = 45; // Default max items for GUI
            AttachmentSelectorPage gui = new AttachmentSelectorPage(sender, maxItems, ultiPlugin,
                items -> {
                    // Filter out null items
                    if (items == null) {
                        startContentConversation(sender, receiver, subject, null);
                        return;
                    }
                    ItemStack[] validItems = Arrays.stream(items)
                        .filter(item -> item != null && !item.getType().isAir())
                        .toArray(ItemStack[]::new);
                    
                    if (validItems.length == 0) {
                        // No items selected, send without attachment
                        startContentConversation(sender, receiver, subject, null);
                    } else {
                        startContentConversation(sender, receiver, subject, validItems);
                    }
                },
                () -> {
                    sender.sendMessage(ChatColor.YELLOW + i18n("send_cancelled"));
                });
            gui.open();
        } else {
            // Regular players: use main hand item only
            ItemStack item = sender.getInventory().getItemInMainHand();
            if (item == null || item.getType().isAir()) {
                sender.sendMessage(ChatColor.RED + i18n("error_no_item_in_hand"));
                return;
            }
            
            // Start conversation with single attachment
            ItemStack[] items = new ItemStack[]{item.clone()};
            sender.getInventory().setItemInMainHand(null);
            
            startContentConversation(sender, receiver, subject, items);
        }
    }
    
    /**
     * Start conversation to input mail content.
     */
    private void startContentConversation(Player sender, String receiver, String subject, ItemStack[] items) {
        ConversationFactory factory = new ConversationFactory(plugin)
            .withFirstPrompt(new ContentPrompt(receiver, subject))
            .withEscapeSequence("cancel")
            .withTimeout(120)
            .thatExcludesNonPlayersWithMessage(i18n("error_player_only"))
            .addConversationAbandonedListener(event -> {
                if (!event.gracefulExit()) {
                    event.getContext().getForWhom().sendRawMessage(ChatColor.RED + i18n("send_cancelled"));
                    // Return items if conversation cancelled
                    ItemStack[] attachedItems = (ItemStack[]) event.getContext().getSessionData("attachItems");
                    if (attachedItems != null) {
                        Player player = (Player) event.getContext().getForWhom();
                        for (ItemStack item : attachedItems) {
                            if (item != null && !item.getType().isAir()) {
                                player.getInventory().addItem(item);
                            }
                        }
                    }
                }
            });
        
        Conversation conversation = factory.buildConversation(sender);
        conversation.getContext().setSessionData("mailService", mailService);
        conversation.getContext().setSessionData("ultiPlugin", ultiPlugin);
        conversation.getContext().setSessionData("attachItems", items);
        conversation.begin();
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender Player player) {
        handleHelp(player);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== " + i18n("help_sendmail_title") + " ===");
        sender.sendMessage(ChatColor.YELLOW + "/sendmail <" + i18n("arg_player") + "> <" + i18n("arg_subject") + ">" 
            + ChatColor.WHITE + " - " + i18n("help_sendmail_text"));
        sender.sendMessage(ChatColor.YELLOW + "/sendmail <" + i18n("arg_player") + "> <" + i18n("arg_subject") + "> attach" 
            + ChatColor.WHITE + " - " + i18n("help_sendmail_attach"));
        sender.sendMessage(ChatColor.GRAY + i18n("help_cancel_hint"));
    }
    
    private String i18n(String key) {
        return ultiPlugin.i18n(key);
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
            UltiToolsPlugin p = (UltiToolsPlugin) context.getSessionData("ultiPlugin");
            return ChatColor.YELLOW + p.i18n("input_content_prompt");
        }

        @Override
        public Prompt acceptInput(ConversationContext context, String input) {
            if (input == null || input.equalsIgnoreCase("cancel")) {
                return Prompt.END_OF_CONVERSATION;
            }

            Player sender = (Player) context.getForWhom();
            MailService service = (MailService) context.getSessionData("mailService");
            ItemStack[] items = (ItemStack[]) context.getSessionData("attachItems");
            UltiToolsPlugin p = (UltiToolsPlugin) context.getSessionData("ultiPlugin");

            boolean success = service.sendMail(sender, receiver, subject, input, items);

            if (success) {
                String msg = p.i18n("mail_sent_success")
                    .replace("{RECEIVER}", receiver);
                sender.sendMessage(ChatColor.GREEN + msg);
            }

            return Prompt.END_OF_CONVERSATION;
        }
    }
}
