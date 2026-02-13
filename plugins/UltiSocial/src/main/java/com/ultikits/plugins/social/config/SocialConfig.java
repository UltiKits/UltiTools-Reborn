package com.ultikits.plugins.social.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Social system configuration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/social.yml")
public class SocialConfig extends AbstractConfigEntity {

    @Range(min = 1, max = 500)
    @ConfigEntry(path = "max_friends", comment = "Maximum number of friends per player")
    private int maxFriends = 50;

    @Range(min = 10, max = 3600)
    @ConfigEntry(path = "request_timeout", comment = "Friend request timeout in seconds")
    private int requestTimeout = 60;

    @ConfigEntry(path = "notifications.friend_online", comment = "Notify when friend comes online")
    private boolean notifyFriendOnline = true;

    @ConfigEntry(path = "notifications.friend_offline", comment = "Notify when friend goes offline")
    private boolean notifyFriendOffline = true;

    @ConfigEntry(path = "notifications.friend_join_world", comment = "Notify when friend joins your world")
    private boolean notifyFriendJoinWorld = false;

    @ConfigEntry(path = "tp_to_friend.enabled", comment = "Allow teleporting to friends")
    private boolean tpToFriendEnabled = true;

    @Range(min = 0, max = 3600)
    @ConfigEntry(path = "tp_to_friend.cooldown", comment = "Teleport cooldown in seconds")
    private int tpCooldown = 30;
    
    @NotEmpty
    @ConfigEntry(path = "gui_title", comment = "Friend list GUI title")
    private String guiTitle = "&6好友列表 &7({COUNT}/{MAX})";

    @NotEmpty
    @ConfigEntry(path = "messages.friend_added", comment = "Friend added message")
    private String friendAddedMessage = "&a你和 {PLAYER} 成为了好友！";

    @NotEmpty
    @ConfigEntry(path = "messages.friend_removed", comment = "Friend removed message")
    private String friendRemovedMessage = "&c你已删除好友 {PLAYER}";

    @NotEmpty
    @ConfigEntry(path = "messages.friend_online", comment = "Friend online notification")
    private String friendOnlineMessage = "&a你的好友 {PLAYER} 上线了！";

    @NotEmpty
    @ConfigEntry(path = "messages.friend_offline", comment = "Friend offline notification")
    private String friendOfflineMessage = "&7你的好友 {PLAYER} 下线了";

    @NotEmpty
    @ConfigEntry(path = "messages.request_sent", comment = "Request sent message")
    private String requestSentMessage = "&a已向 {PLAYER} 发送好友请求！";

    @NotEmpty
    @ConfigEntry(path = "messages.request_received", comment = "Request received message")
    private String requestReceivedMessage = "&e{PLAYER} 想和你成为好友！输入 /friend accept {PLAYER} 接受";

    @NotEmpty
    @ConfigEntry(path = "messages.request_denied", comment = "Request denied message")
    private String requestDeniedMessage = "&c已拒绝 {PLAYER} 的好友请求";

    @NotEmpty
    @ConfigEntry(path = "messages.max_friends_reached", comment = "Max friends reached message")
    private String maxFriendsMessage = "&c你的好友数量已达上限！";

    @NotEmpty
    @ConfigEntry(path = "messages.already_friends", comment = "Already friends message")
    private String alreadyFriendsMessage = "&c你已经和 {PLAYER} 是好友了！";

    @NotEmpty
    @ConfigEntry(path = "messages.blocked", comment = "Blocked player message (bidirectional)")
    private String blockedMessage = "&c无法与 {PLAYER} 进行好友操作，因为存在黑名单关系";

    @NotEmpty
    @ConfigEntry(path = "messages.player_blocked", comment = "Player added to blacklist message")
    private String playerBlockedMessage = "&c已将 {PLAYER} 加入黑名单";

    @NotEmpty
    @ConfigEntry(path = "messages.player_unblocked", comment = "Player removed from blacklist message")
    private String playerUnblockedMessage = "&a已将 {PLAYER} 从黑名单移除";

    public SocialConfig(String configFilePath) {
        super(configFilePath);
    }
}
