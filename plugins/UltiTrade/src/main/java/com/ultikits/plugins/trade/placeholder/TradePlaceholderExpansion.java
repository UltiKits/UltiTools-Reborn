package com.ultikits.plugins.trade.placeholder;

import com.ultikits.plugins.trade.entity.PlayerTradeSettings;
import com.ultikits.plugins.trade.service.TradeLogService;
import com.ultikits.plugins.trade.service.TradeService;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for UltiTrade.
 * Provides trade-related placeholders.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TradePlaceholderExpansion extends PlaceholderExpansion {
    
    private final TradeService tradeService;
    private final TradeLogService logService;
    
    public TradePlaceholderExpansion(TradeService tradeService, TradeLogService logService) {
        this.tradeService = tradeService;
        this.logService = logService;
    }
    
    @Override
    public @NotNull String getIdentifier() {
        return "ultitrade";
    }
    
    @Override
    public @NotNull String getAuthor() {
        return "wisdomme";
    }
    
    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public boolean persist() {
        return true; // This expansion will not be cleared on PlaceholderAPI reload
    }
    
    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        
        // Get player stats
        PlayerTradeSettings stats = logService.getPlayerStats(player.getUniqueId());
        
        switch (params.toLowerCase()) {
            // Total number of completed trades
            case "total_trades":
                return String.valueOf(stats.getTotalTrades());
            
            // Total money traded (given to others)
            case "total_money":
            case "total_money_traded":
                return String.format("%.2f", stats.getTotalMoneyTraded());
            
            // Total experience traded (given to others)
            case "total_exp":
            case "total_exp_traded":
                return String.valueOf(stats.getTotalExpTraded());
            
            // Whether trade is enabled for this player
            case "trade_enabled":
            case "enabled":
                return stats.isTradeEnabled() ? "true" : "false";
            
            // Whether trade is enabled (localized)
            case "trade_enabled_display":
            case "enabled_display":
                return stats.isTradeEnabled() ? "开启" : "关闭";
            
            // Whether player is currently in a trade
            case "is_trading":
            case "in_trade":
                return tradeService.isTrading(player.getUniqueId()) ? "true" : "false";
            
            // Last trade time (formatted)
            case "last_trade_time":
                long lastTime = stats.getLastTradeTime();
                if (lastTime == 0) {
                    return "从未交易";
                }
                return formatTimestamp(lastTime);
            
            // Time since last trade
            case "last_trade_ago":
                long last = stats.getLastTradeTime();
                if (last == 0) {
                    return "从未";
                }
                return formatTimeAgo(System.currentTimeMillis() - last);
            
            // Number of blocked players
            case "blocked_count":
                return String.valueOf(stats.getBlockedPlayers().size());
            
            default:
                return null;
        }
    }
    
    /**
     * Format timestamp to readable date.
     */
    private String formatTimestamp(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date(timestamp));
    }
    
    /**
     * Format time duration to readable string.
     */
    private String formatTimeAgo(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return days + "天前";
        } else if (hours > 0) {
            return hours + "小时前";
        } else if (minutes > 0) {
            return minutes + "分钟前";
        } else {
            return "刚刚";
        }
    }
}
