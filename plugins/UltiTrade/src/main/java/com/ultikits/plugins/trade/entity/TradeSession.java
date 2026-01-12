package com.ultikits.plugins.trade.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Represents an active trade session between two players.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TradeSession {
    
    private final UUID sessionId;
    private final UUID player1;
    private final UUID player2;
    private final long startTime;
    
    // Items offered by each player
    private final Map<Integer, ItemStack> player1Items = new HashMap<>();
    private final Map<Integer, ItemStack> player2Items = new HashMap<>();
    
    // Money offered by each player
    private double player1Money = 0;
    private double player2Money = 0;
    
    // Experience offered by each player
    private int player1Exp = 0;
    private int player2Exp = 0;
    
    // Confirmation status
    private boolean player1Confirmed = false;
    private boolean player2Confirmed = false;
    
    // Trade state
    private TradeState state = TradeState.TRADING;
    
    public TradeSession(Player p1, Player p2) {
        this.sessionId = UUID.randomUUID();
        this.player1 = p1.getUniqueId();
        this.player2 = p2.getUniqueId();
        this.startTime = System.currentTimeMillis();
    }
    
    public UUID getSessionId() {
        return sessionId;
    }
    
    public UUID getPlayer1() {
        return player1;
    }
    
    public UUID getPlayer2() {
        return player2;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public UUID getOtherPlayer(UUID player) {
        return player.equals(player1) ? player2 : player1;
    }
    
    public boolean isParticipant(UUID player) {
        return player.equals(player1) || player.equals(player2);
    }
    
    // Items management
    public void setItem(UUID player, int slot, ItemStack item) {
        resetConfirmation();
        if (player.equals(player1)) {
            if (item == null) {
                player1Items.remove(slot);
            } else {
                player1Items.put(slot, item);
            }
        } else {
            if (item == null) {
                player2Items.remove(slot);
            } else {
                player2Items.put(slot, item);
            }
        }
    }
    
    public Map<Integer, ItemStack> getPlayerItems(UUID player) {
        return player.equals(player1) ? player1Items : player2Items;
    }
    
    public Map<Integer, ItemStack> getOtherPlayerItems(UUID player) {
        return player.equals(player1) ? player2Items : player1Items;
    }
    
    // Money management
    public void setMoney(UUID player, double amount) {
        resetConfirmation();
        if (player.equals(player1)) {
            player1Money = amount;
        } else {
            player2Money = amount;
        }
    }
    
    public double getPlayerMoney(UUID player) {
        return player.equals(player1) ? player1Money : player2Money;
    }
    
    public double getOtherPlayerMoney(UUID player) {
        return player.equals(player1) ? player2Money : player1Money;
    }
    
    // Experience management
    public void setExp(UUID player, int amount) {
        resetConfirmation();
        if (player.equals(player1)) {
            player1Exp = amount;
        } else {
            player2Exp = amount;
        }
    }
    
    public int getPlayerExp(UUID player) {
        return player.equals(player1) ? player1Exp : player2Exp;
    }
    
    public int getOtherPlayerExp(UUID player) {
        return player.equals(player1) ? player2Exp : player1Exp;
    }
    
    // Confirmation
    public void setConfirmed(UUID player, boolean confirmed) {
        if (player.equals(player1)) {
            player1Confirmed = confirmed;
        } else {
            player2Confirmed = confirmed;
        }
    }
    
    public boolean isConfirmed(UUID player) {
        return player.equals(player1) ? player1Confirmed : player2Confirmed;
    }
    
    public boolean isBothConfirmed() {
        return player1Confirmed && player2Confirmed;
    }
    
    public void resetConfirmation() {
        player1Confirmed = false;
        player2Confirmed = false;
    }
    
    // State management
    public TradeState getState() {
        return state;
    }
    
    public void setState(TradeState state) {
        this.state = state;
    }
    
    public enum TradeState {
        TRADING,
        CONFIRMED,
        COMPLETED,
        CANCELLED
    }
}
