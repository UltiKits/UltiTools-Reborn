package com.ultikits.plugins.essentials.service;

import com.ultikits.plugins.essentials.config.EssentialsConfig;
import com.ultikits.plugins.essentials.entity.BanData;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import com.ultikits.ultitools.annotations.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing player bans.
 * <p>
 * 管理玩家封禁的服务。
 * <p>
 * Note: Login ban checks are handled by {@link com.ultikits.plugins.essentials.listener.BanListener}
 *
 * @author wisdomme
 * @version 1.1.0
 */
@Slf4j
@Service
public class BanService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private EssentialsConfig config;

    private DataOperator<BanData> banOperator;

    /**
     * Initializes the service with the data operator.
     * Automatically called by the IoC container after construction.
     */
    @PostConstruct
    public void init() {
        this.banOperator = plugin.getDataOperator(BanData.class);
    }
    
    /**
     * Bans a player permanently.
     *
     * @param targetUuid  the UUID of the player to ban
     * @param targetName  the name of the player
     * @param reason      the ban reason
     * @param operatorUuid UUID of the operator (null for console)
     * @param operatorName name of the operator
     * @return result of the ban operation
     */
    public BanResult banPlayer(UUID targetUuid, String targetName, String reason, 
                               @Nullable UUID operatorUuid, String operatorName) {
        return banPlayer(targetUuid, targetName, reason, operatorUuid, operatorName, -1, null);
    }
    
    /**
     * Bans a player temporarily.
     *
     * @param targetUuid  the UUID of the player to ban
     * @param targetName  the name of the player
     * @param reason      the ban reason
     * @param operatorUuid UUID of the operator (null for console)
     * @param operatorName name of the operator
     * @param duration    ban duration in milliseconds (-1 for permanent)
     * @param ipAddress   IP address to ban (optional)
     * @return result of the ban operation
     */
    public BanResult banPlayer(UUID targetUuid, String targetName, String reason,
                               @Nullable UUID operatorUuid, String operatorName,
                               long duration, @Nullable String ipAddress) {
        if (!config.isBanEnabled()) {
            return BanResult.DISABLED;
        }
        
        // Check if already banned
        BanData existingBan = getActiveBan(targetUuid);
        if (existingBan != null) {
            return BanResult.ALREADY_BANNED;
        }
        
        long now = System.currentTimeMillis();
        long expireTime = duration == -1 ? -1 : now + duration;
        
        BanData ban = BanData.builder()
            .uuid(UUID.randomUUID())
            .playerUuid(targetUuid.toString())
            .playerName(targetName)
            .reason(reason != null ? reason : "无理由")
            .bannedBy(operatorUuid != null ? operatorUuid.toString() : null)
            .bannedByName(operatorName)
            .banTime(now)
            .expireTime(expireTime)
            .active(true)
            .ipAddress(ipAddress)
            .build();
        
        banOperator.insert(ban);
        
        // Kick the player if online
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null) {
            target.kickPlayer(formatKickMessage(ban));
        }
        
        return BanResult.SUCCESS;
    }
    
    /**
     * Unbans a player.
     *
     * @param targetUuid the UUID of the player to unban
     * @return true if unbanned, false if not banned
     */
    public boolean unbanPlayer(UUID targetUuid) {
        List<BanData> activeBans = banOperator.query()
            .where("player_uuid").eq(targetUuid.toString())
            .list()
            .stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .collect(Collectors.toList());

        if (activeBans.isEmpty()) {
            return false;
        }

        for (BanData ban : activeBans) {
            ban.setActive(false);
            try {
                banOperator.update(ban);
            } catch (IllegalAccessException e) {
                log.error("Failed to update ban record", e);
            }
        }

        return true;
    }
    
    /**
     * Unbans a player by name.
     *
     * @param playerName the name of the player
     * @return true if unbanned, false if not banned
     */
    public boolean unbanPlayerByName(String playerName) {
        List<BanData> activeBans = banOperator.query()
            .where("player_name").eq(playerName)
            .list()
            .stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .collect(Collectors.toList());

        if (activeBans.isEmpty()) {
            return false;
        }

        for (BanData ban : activeBans) {
            ban.setActive(false);
            try {
                banOperator.update(ban);
            } catch (IllegalAccessException e) {
                log.error("Failed to update ban record", e);
            }
        }

        return true;
    }
    
    /**
     * Unbans an IP address.
     *
     * @param ipAddress the IP address to unban
     * @return true if unbanned, false if not banned
     */
    public boolean unbanIp(String ipAddress) {
        List<BanData> ipBans = banOperator.query()
            .where("ip_address").eq(ipAddress)
            .list()
            .stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .collect(Collectors.toList());

        if (ipBans.isEmpty()) {
            return false;
        }

        for (BanData ban : ipBans) {
            ban.setActive(false);
            try {
                banOperator.update(ban);
            } catch (IllegalAccessException e) {
                log.error("Failed to update ban record", e);
            }
        }

        return true;
    }
    
    /**
     * Gets the active ban for a player.
     */
    @Nullable
    public BanData getActiveBan(UUID playerUuid) {
        List<BanData> bans = banOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        return bans.stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Gets active IP ban.
     */
    @Nullable
    public BanData getActiveIpBan(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return null;
        }

        List<BanData> bans = banOperator.query()
            .where("ip_address").eq(ipAddress)
            .list();

        return bans.stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Gets all active bans.
     */
    public List<BanData> getActiveBans() {
        return banOperator.getAll().stream()
            .filter(b -> b.isActive() && !b.hasExpired())
            .collect(Collectors.toList());
    }
    
    /**
     * Gets ban history for a player.
     */
    public List<BanData> getBanHistory(UUID playerUuid) {
        return banOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();
    }
    
    /**
     * Formats the kick message for a banned player.
     * 
     * @param ban the ban data
     * @return the formatted kick message
     */
    public String formatKickMessage(BanData ban) {
        StringBuilder message = new StringBuilder();
        message.append("§c你已被封禁\n\n");
        message.append("§7原因: §f").append(ban.getReason()).append("\n");
        message.append("§7操作者: §f").append(ban.getBannedByName()).append("\n");
        
        if (ban.isPermanent()) {
            message.append("§7时长: §c永久封禁\n");
        } else {
            message.append("§7剩余时间: §f").append(formatDuration(ban.getRemainingTime())).append("\n");
        }
        
        message.append("\n§7如有异议，请联系服务器管理员");
        return message.toString();
    }
    
    /**
     * Formats duration in human-readable format.
     */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "已过期";
        }
        
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天 ");
        if (hours > 0) sb.append(hours).append("小时 ");
        if (minutes > 0) sb.append(minutes).append("分钟 ");
        if (seconds > 0 || sb.length() == 0) sb.append(seconds).append("秒");
        
        return sb.toString().trim();
    }
    
    /**
     * Parses duration string like "1d", "2h", "30m", "1d12h30m".
     * 
     * @param durationStr duration string
     * @return duration in milliseconds, or -1 if invalid
     */
    public static long parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isEmpty()) {
            return -1;
        }
        
        long total = 0;
        StringBuilder number = new StringBuilder();
        
        for (char c : durationStr.toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) {
                number.append(c);
            } else {
                if (number.length() == 0) {
                    return -1;
                }
                long value = Long.parseLong(number.toString());
                number.setLength(0);
                
                switch (c) {
                    case 'd':
                        total += TimeUnit.DAYS.toMillis(value);
                        break;
                    case 'h':
                        total += TimeUnit.HOURS.toMillis(value);
                        break;
                    case 'm':
                        total += TimeUnit.MINUTES.toMillis(value);
                        break;
                    case 's':
                        total += TimeUnit.SECONDS.toMillis(value);
                        break;
                    case 'w':
                        total += TimeUnit.DAYS.toMillis(value * 7);
                        break;
                    default:
                        return -1;
                }
            }
        }
        
        return total > 0 ? total : -1;
    }
    
    public enum BanResult {
        SUCCESS,
        ALREADY_BANNED,
        DISABLED
    }
}
