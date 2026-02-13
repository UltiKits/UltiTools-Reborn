package com.ultikits.plugins.trade.entity;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Player trade settings entity for persistence.
 * Stores trade toggle state and blocked players list.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("trade_player_settings")
public class PlayerTradeSettings extends BaseDataEntity<String> {
    
    private static final Gson GSON = new Gson();
    
    /**
     * Player UUID
     */
    @Column("player_uuid")
    private String playerUuid;
    
    /**
     * Player name (for display purposes)
     */
    @Column("player_name")
    private String playerName;
    
    /**
     * Whether trade is enabled for this player
     */
    @Column("trade_enabled")
    private boolean tradeEnabled = true;
    
    /**
     * JSON array of blocked player UUIDs
     */
    @Column(value = "blocked_players", type = "TEXT")
    private String blockedPlayersJson = "[]";
    
    /**
     * Total number of completed trades
     */
    @Column("total_trades")
    private int totalTrades = 0;
    
    /**
     * Total money traded (given)
     */
    @Column("total_money_traded")
    private double totalMoneyTraded = 0.0;
    
    /**
     * Total experience traded (given)
     */
    @Column("total_exp_traded")
    private int totalExpTraded = 0;
    
    /**
     * Last trade timestamp
     */
    @Column("last_trade_time")
    private long lastTradeTime = 0;
    
    public PlayerTradeSettings(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid.toString();
        this.playerName = playerName;
    }
    
    /**
     * Get blocked players as a list of UUIDs.
     *
     * @return List of blocked player UUIDs
     */
    public List<String> getBlockedPlayers() {
        if (blockedPlayersJson == null || blockedPlayersJson.isEmpty()) {
            return new ArrayList<>();
        }
        Type listType = new TypeToken<List<String>>(){}.getType();
        return GSON.fromJson(blockedPlayersJson, listType);
    }
    
    /**
     * Set blocked players from a list.
     *
     * @param blockedPlayers List of blocked player UUIDs
     */
    public void setBlockedPlayers(List<String> blockedPlayers) {
        this.blockedPlayersJson = GSON.toJson(blockedPlayers);
    }
    
    /**
     * Add a player to the blocked list.
     *
     * @param playerUuid UUID of player to block
     * @return true if added, false if already blocked
     */
    public boolean blockPlayer(String playerUuid) {
        List<String> blocked = getBlockedPlayers();
        if (blocked.contains(playerUuid)) {
            return false;
        }
        blocked.add(playerUuid);
        setBlockedPlayers(blocked);
        return true;
    }
    
    /**
     * Remove a player from the blocked list.
     *
     * @param playerUuid UUID of player to unblock
     * @return true if removed, false if not in list
     */
    public boolean unblockPlayer(String playerUuid) {
        List<String> blocked = getBlockedPlayers();
        boolean removed = blocked.remove(playerUuid);
        if (removed) {
            setBlockedPlayers(blocked);
        }
        return removed;
    }
    
    /**
     * Check if a player is blocked.
     *
     * @param playerUuid UUID of player to check
     * @return true if blocked
     */
    public boolean isBlocked(String playerUuid) {
        return getBlockedPlayers().contains(playerUuid);
    }
    
    /**
     * Increment trade statistics.
     *
     * @param moneyTraded Amount of money traded
     * @param expTraded Amount of exp traded
     */
    public void incrementTradeStats(double moneyTraded, int expTraded) {
        this.totalTrades++;
        this.totalMoneyTraded += moneyTraded;
        this.totalExpTraded += expTraded;
        this.lastTradeTime = System.currentTimeMillis();
    }
}
