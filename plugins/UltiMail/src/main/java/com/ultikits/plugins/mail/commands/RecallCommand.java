package com.ultikits.plugins.mail.commands;

import com.ultikits.plugins.mail.config.MailConfig;
import com.ultikits.plugins.mail.entity.MailData;
import com.ultikits.plugins.mail.service.MailService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command to recall players back to the server.
 * Sends in-game mail and/or real email to all registered players.
 *
 * <p>Usage: /recall [message]
 * 
 * @author wisdomme
 * @version 1.0.0
 */
@CmdExecutor(
    alias = {"recall", "callback"},
    permission = "ultimail.recall",
    description = "召回玩家回归服务器"
)
public class RecallCommand extends BaseCommandExecutor {

    private Plugin bukkitPlugin;

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private MailConfig config;

    @Autowired
    private MailService mailService;
    
    /**
     * Send recall notifications to all registered players.
     * 
     * @param sender Command sender
     * @param message Optional custom message
     */
    @CmdMapping(format = "", permission = "ultimail.recall")
    public void sendRecall(
            @CmdSender CommandSender sender) {
        sendRecallWithMessage(sender, null);
    }
    
    /**
     * Send recall notifications with custom message.
     * 
     * @param sender Command sender
     * @param message Custom message
     */
    @CmdMapping(format = "<message>", permission = "ultimail.recall")
    public void sendRecallWithMessage(
            @CmdSender CommandSender sender,
            @CmdParam("message") String message) {
        
        if (!sender.isOp() && !sender.hasPermission("ultimail.recall.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行此命令！");
            return;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "正在发送召回通知...");

        // Lazy init bukkitPlugin
        if (bukkitPlugin == null) {
            bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");
        }

        // Run async to avoid blocking main thread
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            int[] results = sendRecallNotifications(sender.getName(), message);
            int totalPlayers = results[0];
            int gameMails = results[1];
            int emails = results[2];
            int failed = results[3];
            
            // Send result back on main thread
            Bukkit.getScheduler().runTask(bukkitPlugin, () -> {
                sender.sendMessage(ChatColor.GREEN + "召回通知发送完成！");
                sender.sendMessage(ChatColor.AQUA + "共找到 " + ChatColor.WHITE + totalPlayers + ChatColor.AQUA + " 名注册玩家");
                sender.sendMessage(ChatColor.AQUA + "游戏内邮件: " + ChatColor.WHITE + gameMails + ChatColor.AQUA + " 封");
                if (config.isEmailEnabled()) {
                    sender.sendMessage(ChatColor.AQUA + "电子邮件: " + ChatColor.WHITE + emails + ChatColor.AQUA + " 封");
                }
                if (failed > 0) {
                    sender.sendMessage(ChatColor.RED + "失败: " + failed + " 封");
                }
            });
        });
    }
    
    /**
     * Send recall notifications to all players.
     * 
     * @param senderName Name of the sender
     * @param customMessage Optional custom message
     * @return Array of [total, gameMails, emails, failed]
     */
    private int[] sendRecallNotifications(String senderName, String customMessage) {
        AtomicInteger total = new AtomicInteger(0);
        AtomicInteger gameMails = new AtomicInteger(0);
        AtomicInteger emails = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        
        // Get all registered players from mail data (unique receivers)
        Set<String> processedPlayers = new HashSet<>();
        
        // Try to get players from login plugin if available
        List<PlayerInfo> allPlayers = getAllRegisteredPlayers();
        
        for (PlayerInfo playerInfo : allPlayers) {
            if (processedPlayers.contains(playerInfo.uuid)) {
                continue;
            }
            processedPlayers.add(playerInfo.uuid);
            total.incrementAndGet();
            
            // Skip currently online players
            if (Bukkit.getPlayer(UUID.fromString(playerInfo.uuid)) != null) {
                continue;
            }
            
            // Send in-game mail
            try {
                sendGameMail(playerInfo.uuid, playerInfo.name, senderName, customMessage);
                gameMails.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
            }
            
            // Send real email if enabled and player has email
            if (config.isEmailEnabled() && playerInfo.email != null && !playerInfo.email.isEmpty()) {
                try {
                    sendRealEmail(playerInfo.email, playerInfo.name, senderName, customMessage);
                    emails.incrementAndGet();
                } catch (Exception e) {
                    // Email failed, but game mail may have succeeded
                    plugin.getLogger().warn(
                        "Failed to send email to " + playerInfo.email + ": " + e.getMessage()
                    );
                }
            }
        }
        
        return new int[]{total.get(), gameMails.get(), emails.get(), failed.get()};
    }
    
    /**
     * Send in-game mail to a player.
     */
    private void sendGameMail(String receiverUuid, String receiverName, String senderName, String customMessage) {
        String subject = config.getRecallSubject();
        String content = customMessage != null ? customMessage : config.getRecallContent();
        
        // Replace placeholders
        subject = subject.replace("{SERVER}", config.getServerName());
        content = content.replace("{SERVER}", config.getServerName())
                        .replace("{SENDER}", senderName);
        
        MailData mail = new MailData();
        mail.setSenderUuid("SYSTEM");
        mail.setSenderName(config.getServerName());
        mail.setReceiverUuid(receiverUuid);
        mail.setReceiverName(receiverName);
        mail.setSubject(subject);
        mail.setContent(content);
        mail.setSentTime(System.currentTimeMillis());
        
        DataOperator<MailData> dataOperator = plugin.getDataOperator(MailData.class);
        dataOperator.insert(mail);
    }
    
    /**
     * Send real email to a player using reflection to avoid hard dependency on javax.mail.
     * This allows the plugin to work even without the mail library.
     */
    private void sendRealEmail(String emailAddress, String playerName, String senderName, String customMessage) throws Exception {
        if (!config.isEmailEnabled()) {
            return;
        }
        
        String subject = config.getRecallEmailSubject()
            .replace("{SERVER}", config.getServerName());
        String content = (customMessage != null ? customMessage : config.getRecallEmailContent())
            .replace("{SERVER}", config.getServerName())
            .replace("{PLAYER}", playerName)
            .replace("{SENDER}", senderName);
        
        // Use reflection to load javax.mail classes to avoid ClassNotFoundException
        // when the library is not present
        try {
            // Setup mail properties
            Properties props = new Properties();
            props.put("mail.smtp.host", config.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
            props.put("mail.smtp.auth", "true");
            
            if (config.isSmtpSsl()) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else if (config.isSmtpStartTls()) {
                props.put("mail.smtp.starttls.enable", "true");
            }
            
            // Load classes via reflection
            Class<?> sessionClass = Class.forName("javax.mail.Session");
            Class<?> authenticatorClass = Class.forName("javax.mail.Authenticator");
            Class<?> passwordAuthClass = Class.forName("javax.mail.PasswordAuthentication");
            Class<?> mimeMessageClass = Class.forName("javax.mail.internet.MimeMessage");
            Class<?> internetAddressClass = Class.forName("javax.mail.internet.InternetAddress");
            Class<?> messageClass = Class.forName("javax.mail.Message");
            Class<?> recipientTypeClass = Class.forName("javax.mail.Message$RecipientType");
            Class<?> transportClass = Class.forName("javax.mail.Transport");
            
            // Create authenticator via dynamic proxy or anonymous class workaround
            final String username = config.getSmtpUsername();
            final String password = config.getSmtpPassword();

            // Note: PasswordAuthentication object created for potential future use with authenticator
            // Currently using direct SMTP credentials in session properties
            passwordAuthClass.getConstructor(String.class, String.class)
                .newInstance(username, password);

            // Create session with null authenticator first, then set auth manually
            Object session = sessionClass.getMethod("getInstance", Properties.class)
                .invoke(null, props);
            
            // Create message
            Object message = mimeMessageClass.getConstructor(sessionClass).newInstance(session);
            
            // Set from address
            Object fromAddress = internetAddressClass.getConstructor(String.class, String.class)
                .newInstance(config.getSmtpFromEmail(), config.getServerName());
            mimeMessageClass.getMethod("setFrom", Class.forName("javax.mail.Address"))
                .invoke(message, fromAddress);
            
            // Set recipient
            Object toField = recipientTypeClass.getField("TO").get(null);
            Object[] toAddresses = (Object[]) internetAddressClass.getMethod("parse", String.class)
                .invoke(null, emailAddress);
            mimeMessageClass.getMethod("setRecipients", recipientTypeClass, Class.forName("[Ljavax.mail.Address;"))
                .invoke(message, toField, toAddresses);
            
            // Set subject and content
            mimeMessageClass.getMethod("setSubject", String.class).invoke(message, subject);
            mimeMessageClass.getMethod("setText", String.class).invoke(message, content);
            
            // Send with auth
            transportClass.getMethod("send", messageClass, String.class, String.class)
                .invoke(null, message, username, password);
                
        } catch (ClassNotFoundException e) {
            throw new Exception("javax.mail library not found. Please add mail library to enable email functionality.");
        }
    }
    
    /**
     * Get all registered players.
     * Tries to get from login plugin, falls back to mail data.
     */
    private List<PlayerInfo> getAllRegisteredPlayers() {
        List<PlayerInfo> players = new ArrayList<>();
        
        // Try to get from UltiLogin plugin via reflection (avoid hard dependency)
        try {
            Class<?> loginClass = Class.forName("com.ultikits.plugins.login.UltiLogin");
            Object loginInstance = loginClass.getMethod("getInstance").invoke(null);
            
            // Get DataOperator for AccountData
            Class<?> accountDataClass = Class.forName("com.ultikits.plugins.login.entity.AccountData");
            Object dataOperator = loginClass.getMethod("getDataOperator", Class.class)
                .invoke(loginInstance, accountDataClass);
            
            // Get all accounts
            @SuppressWarnings("unchecked")
            List<Object> accounts = (List<Object>) dataOperator.getClass()
                .getMethod("getAll")
                .invoke(dataOperator);
            
            for (Object account : accounts) {
                String uuid = (String) account.getClass().getMethod("getPlayerUuid").invoke(account);
                String name = (String) account.getClass().getMethod("getPlayerName").invoke(account);
                String email = null;
                try {
                    email = (String) account.getClass().getMethod("getEmail").invoke(account);
                } catch (Exception e) {
                    // Email field may not exist
                }
                
                players.add(new PlayerInfo(uuid, name, email));
            }
        } catch (Exception e) {
            // UltiLogin not available, fall back to mail data
            plugin.getLogger().info(
                "UltiLogin not found, using mail data to find registered players"
            );
        }
        
        // Also check mail data for any receivers
        if (players.isEmpty()) {
            DataOperator<MailData> mailOperator = plugin.getDataOperator(MailData.class);
            List<MailData> allMails = mailOperator.getAll();
            Set<String> seen = new HashSet<>();
            
            for (MailData mail : allMails) {
                String uuid = mail.getReceiverUuid();
                if (!seen.contains(uuid) && !"SYSTEM".equals(uuid)) {
                    seen.add(uuid);
                    players.add(new PlayerInfo(uuid, mail.getReceiverName(), null));
                }
            }
        }
        
        // Also include all offline players that have played before
        for (org.bukkit.OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            String uuid = offlinePlayer.getUniqueId().toString();
            boolean exists = players.stream().anyMatch(p -> p.uuid.equals(uuid));
            if (!exists && offlinePlayer.getName() != null) {
                players.add(new PlayerInfo(uuid, offlinePlayer.getName(), null));
            }
        }
        
        return players;
    }
    
    /**
     * Simple data class for player info.
     */
    private static class PlayerInfo {
        final String uuid;
        final String name;
        final String email;
        
        PlayerInfo(String uuid, String name, String email) {
            this.uuid = uuid;
            this.name = name;
            this.email = email;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 召回系统帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/recall" + ChatColor.WHITE + " - 发送默认召回消息");
        sender.sendMessage(ChatColor.YELLOW + "/recall [自定义消息]" + ChatColor.WHITE + " - 发送自定义召回消息");
    }
}
