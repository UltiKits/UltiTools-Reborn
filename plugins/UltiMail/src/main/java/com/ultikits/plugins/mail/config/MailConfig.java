package com.ultikits.plugins.mail.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for UltiMail.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/mail.yml")
public class MailConfig extends AbstractConfigEntity {
    
    @ConfigEntry(path = "max-items", comment = "每封邮件最多附带物品数量")
    @Range(min = 1, max = 54)
    private int maxItems = 27;

    @ConfigEntry(path = "mail-expire-days", comment = "邮件过期天数（0为永不过期）")
    @Range(min = 0, max = 365)
    private int mailExpireDays = 30;

    @ConfigEntry(path = "notify-on-join", comment = "玩家登录时通知未读邮件")
    private boolean notifyOnJoin = true;

    @ConfigEntry(path = "notify-delay", comment = "登录通知延迟（秒）")
    @Range(min = 0, max = 60)
    private int notifyDelay = 3;

    @ConfigEntry(path = "max-subject-length", comment = "邮件标题最大长度")
    @Range(min = 10, max = 200)
    private int maxSubjectLength = 50;

    @ConfigEntry(path = "max-content-length", comment = "邮件内容最大长度")
    @Range(min = 50, max = 5000)
    private int maxContentLength = 500;

    @ConfigEntry(path = "send-cooldown", comment = "发送邮件冷却时间（秒）")
    @Range(min = 0, max = 300)
    private int sendCooldown = 10;
    
    @ConfigEntry(path = "messages.new-mail", comment = "新邮件通知")
    @NotEmpty
    private String newMailMessage = "&e[邮件] &f你有 &a{COUNT} &f封未读邮件！使用 /mail inbox 查看";

    @ConfigEntry(path = "messages.mail-sent", comment = "邮件发送成功")
    @NotEmpty
    private String mailSentMessage = "&a邮件已发送给 {PLAYER}！";

    @ConfigEntry(path = "messages.mail-received", comment = "收到新邮件")
    @NotEmpty
    private String mailReceivedMessage = "&e[邮件] &f你收到了来自 &a{SENDER} &f的新邮件！";
    
    // ========== 召回玩家功能配置 ==========
    
    @ConfigEntry(path = "recall.server-name", comment = "服务器名称，用于召回邮件显示")
    @NotEmpty
    private String serverName = "Minecraft服务器";

    @ConfigEntry(path = "recall.subject", comment = "游戏内召回邮件标题")
    @NotEmpty
    private String recallSubject = "[{SERVER}] 回归召唤";

    @ConfigEntry(path = "recall.content", comment = "游戏内召回邮件内容")
    @NotEmpty
    private String recallContent = "亲爱的玩家，{SERVER}想念你了！\n\n快回来看看吧，我们期待与你重逢！\n\n发送者: {SENDER}";
    
    // ========== 真实邮件发送配置 ==========
    
    @ConfigEntry(path = "email.enabled", comment = "是否启用真实邮件发送功能")
    private boolean emailEnabled = false;
    
    @ConfigEntry(path = "email.smtp-host", comment = "SMTP服务器地址")
    @NotEmpty
    private String smtpHost = "smtp.example.com";

    @ConfigEntry(path = "email.smtp-port", comment = "SMTP端口")
    @Range(min = 1, max = 65535)
    private int smtpPort = 587;

    @ConfigEntry(path = "email.smtp-username", comment = "SMTP用户名")
    private String smtpUsername = "";

    @ConfigEntry(path = "email.smtp-password", comment = "SMTP密码")
    private String smtpPassword = "";

    @ConfigEntry(path = "email.smtp-from-email", comment = "发件人邮箱地址")
    @NotEmpty
    private String smtpFromEmail = "noreply@example.com";
    
    @ConfigEntry(path = "email.smtp-ssl", comment = "是否使用SSL加密")
    private boolean smtpSsl = false;
    
    @ConfigEntry(path = "email.smtp-starttls", comment = "是否使用STARTTLS加密")
    private boolean smtpStartTls = true;
    
    @ConfigEntry(path = "email.recall-subject", comment = "召回电子邮件标题")
    @NotEmpty
    private String recallEmailSubject = "[{SERVER}] 我们想念你！";

    @ConfigEntry(path = "email.recall-content", comment = "召回电子邮件内容")
    @NotEmpty
    private String recallEmailContent = "亲爱的 {PLAYER}，\n\n{SERVER} 服务器想念你了！快回来看看吧，我们期待与你重逢！\n\n发送者: {SENDER}";
    
    public MailConfig() {
        super("config/mail.yml");
    }
}
