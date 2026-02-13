package com.ultikits.plugins.trade.entity;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.UUID;

/**
 * Trade log entity for persistence.
 * Records complete information about a trade transaction.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("trade_logs")
public class TradeLogData extends BaseDataEntity<String> {
    
    /**
     * Unique trade ID
     */
    @Column("trade_id")
    private String tradeId;
    
    /**
     * Player 1 UUID
     */
    @Column("player1_uuid")
    private String player1Uuid;
    
    /**
     * Player 1 name (at time of trade)
     */
    @Column("player1_name")
    private String player1Name;
    
    /**
     * Player 2 UUID
     */
    @Column("player2_uuid")
    private String player2Uuid;
    
    /**
     * Player 2 name (at time of trade)
     */
    @Column("player2_name")
    private String player2Name;
    
    /**
     * Player 1's items as JSON
     */
    @Column(value = "player1_items", type = "TEXT")
    private String player1ItemsJson;
    
    /**
     * Player 2's items as JSON
     */
    @Column(value = "player2_items", type = "TEXT")
    private String player2ItemsJson;
    
    /**
     * Money offered by player 1
     */
    @Column("player1_money")
    private double player1Money;
    
    /**
     * Money offered by player 2
     */
    @Column("player2_money")
    private double player2Money;
    
    /**
     * Experience offered by player 1
     */
    @Column("player1_exp")
    private int player1Exp;
    
    /**
     * Experience offered by player 2
     */
    @Column("player2_exp")
    private int player2Exp;
    
    /**
     * Total tax collected (money)
     */
    @Column("money_tax_collected")
    private double moneyTaxCollected;
    
    /**
     * Total tax collected (experience)
     */
    @Column("exp_tax_collected")
    private int expTaxCollected;
    
    /**
     * Trade timestamp
     */
    @Column("trade_time")
    private long tradeTime;
    
    /**
     * Trade status: COMPLETED, CANCELLED
     */
    @Column("status")
    private String status;
    
    /**
     * Cancel reason (if cancelled)
     */
    @Column(value = "cancel_reason", type = "TEXT")
    private String cancelReason;
    
    /**
     * Create a new trade log entry.
     */
    public TradeLogData(UUID tradeId, UUID player1Uuid, String player1Name, 
                        UUID player2Uuid, String player2Name) {
        this.tradeId = tradeId.toString();
        this.player1Uuid = player1Uuid.toString();
        this.player1Name = player1Name;
        this.player2Uuid = player2Uuid.toString();
        this.player2Name = player2Name;
        this.tradeTime = System.currentTimeMillis();
        this.status = "PENDING";
    }
    
    /**
     * Set player 1's items from ItemStack collection.
     *
     * @param items Collection of ItemStacks
     */
    public void setPlayer1Items(Collection<ItemStack> items) {
        this.player1ItemsJson = SerializedItemStack.itemsToJson(items);
    }
    
    /**
     * Set player 2's items from ItemStack collection.
     *
     * @param items Collection of ItemStacks
     */
    public void setPlayer2Items(Collection<ItemStack> items) {
        this.player2ItemsJson = SerializedItemStack.itemsToJson(items);
    }
    
    /**
     * Mark trade as completed.
     */
    public void markCompleted() {
        this.status = "COMPLETED";
    }
    
    /**
     * Mark trade as cancelled.
     *
     * @param reason Cancellation reason
     */
    public void markCancelled(String reason) {
        this.status = "CANCELLED";
        this.cancelReason = reason;
    }
    
    /**
     * Check if the log is expired based on retention days.
     *
     * @param retentionDays Number of days to retain logs
     * @return true if expired
     */
    public boolean isExpired(int retentionDays) {
        long retentionMs = retentionDays * 24L * 60L * 60L * 1000L;
        return System.currentTimeMillis() - tradeTime > retentionMs;
    }
    
    /**
     * Get a brief summary of the trade.
     *
     * @return Summary string
     */
    public String getSummary() {
        return String.format("%s <-> %s | Money: %.2f/%.2f | Exp: %d/%d | Status: %s",
            player1Name, player2Name,
            player1Money, player2Money,
            player1Exp, player2Exp,
            status);
    }
}
