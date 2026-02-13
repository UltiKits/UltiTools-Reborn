package com.ultikits.plugins.chat.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@ConfigEntity("config/chat.yml")
public class ChatConfig extends AbstractConfigEntity {

    // Chat format
    @ConfigEntry(path = "chat.format-enabled", comment = "Enable chat formatting / 启用聊天格式化")
    private boolean chatFormatEnabled = true;

    @NotEmpty
    @ConfigEntry(path = "chat.format", comment = "Chat format (PlaceholderAPI supported) / 聊天格式")
    private String chatFormat = "&7[&f%player_world%&7] &f{player}&7: &f{message}";

    // Join/quit
    @ConfigEntry(path = "join-quit.join-message-enabled", comment = "Enable custom join message / 启用自定义进入消息")
    private boolean joinMessageEnabled = true;

    @ConfigEntry(path = "join-quit.join-message-format", comment = "Join message format / 进入消息格式")
    private String joinMessageFormat = "&a[+] &e%player_name% &7joined the server";

    @ConfigEntry(path = "join-quit.quit-message-enabled", comment = "Enable custom quit message / 启用自定义离开消息")
    private boolean quitMessageEnabled = true;

    @ConfigEntry(path = "join-quit.quit-message-format", comment = "Quit message format / 离开消息格式")
    private String quitMessageFormat = "&c[-] &e%player_name% &7left the server";

    @ConfigEntry(path = "join-quit.welcome-enabled", comment = "Enable welcome message on join / 启用入服欢迎消息")
    private boolean welcomeEnabled = true;

    @ConfigEntry(path = "join-quit.welcome-lines", comment = "Welcome message lines / 欢迎消息内容")
    private List<String> welcomeLines = Arrays.asList(
            "&7========================================",
            "&6Welcome, &e%player_name%&6!",
            "&7Online: &f%online_players%&7/&f%max_players%",
            "&7========================================"
    );

    @ConfigEntry(path = "join-quit.title.enabled", comment = "Show title on join / 显示进入标题")
    private boolean titleEnabled = true;

    @ConfigEntry(path = "join-quit.title.main", comment = "Title main text / 标题主文本")
    private String titleMain = "&6Welcome Back";

    @ConfigEntry(path = "join-quit.title.sub", comment = "Title subtitle / 标题副文本")
    private String titleSub = "&7%player_name%";

    @ConfigEntry(path = "join-quit.first-join-message", comment = "First join broadcast / 首次加入广播")
    private String firstJoinMessage = "&6Welcome new player &e%player_name%&6!";

    // Mentions
    @ConfigEntry(path = "mentions.enabled", comment = "Enable @player mentions / 启用@提及")
    private boolean mentionsEnabled = true;

    @ConfigEntry(path = "mentions.format", comment = "Mention highlight format / 提及高亮格式")
    private String mentionFormat = "&e@{player}&r";

    @ConfigEntry(path = "mentions.sound", comment = "Sound when mentioned / 被提及时的音效")
    private String mentionSound = "ENTITY_EXPERIENCE_ORB_PICKUP";

    @ConfigEntry(path = "mentions.self-mention", comment = "Allow self-mention / 允许自我提及")
    private boolean selfMention = false;

    // Anti-spam
    @ConfigEntry(path = "anti-spam.enabled", comment = "Enable anti-spam / 启用反刷屏")
    private boolean antiSpamEnabled = true;

    @Range(min = 0, max = 60)
    @ConfigEntry(path = "anti-spam.cooldown", comment = "Cooldown between messages (seconds) / 消息间隔(秒)")
    private int antiSpamCooldown = 2;

    @Range(min = 1, max = 20)
    @ConfigEntry(path = "anti-spam.max-duplicate", comment = "Max identical messages / 最大重复消息数")
    private int antiSpamMaxDuplicate = 3;

    @Range(min = 10, max = 600)
    @ConfigEntry(path = "anti-spam.duplicate-window", comment = "Duplicate detection window (seconds) / 重复检测窗口(秒)")
    private int antiSpamDuplicateWindow = 60;

    @Range(min = 5, max = 600)
    @ConfigEntry(path = "anti-spam.mute-duration", comment = "Auto-mute duration (seconds) / 自动禁言时长(秒)")
    private int antiSpamMuteDuration = 30;

    @Range(min = 0, max = 100)
    @ConfigEntry(path = "anti-spam.caps-limit", comment = "Max uppercase percentage / 最大大写百分比")
    private int antiSpamCapsLimit = 70;

    public ChatConfig() {
        super("config/chat.yml");
    }
}
