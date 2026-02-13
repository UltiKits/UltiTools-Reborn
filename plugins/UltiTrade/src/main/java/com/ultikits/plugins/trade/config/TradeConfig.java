package com.ultikits.plugins.trade.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for UltiTrade.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/trade.yml")
public class TradeConfig extends AbstractConfigEntity {
    
    // ==================== Basic Settings ====================

    @Range(min = 5, max = 600)
    @ConfigEntry(path = "request-timeout", comment = "交易请求超时时间（秒）")
    private int requestTimeout = 30;

    @Range(min = 30, max = 600)
    @ConfigEntry(path = "trade-timeout", comment = "交易窗口超时时间（秒）")
    private int tradeTimeout = 120;

    @Range(min = 0, max = 1000)
    @ConfigEntry(path = "max-distance", comment = "交易最大距离（格），0为无限制")
    private int maxDistance = 50;
    
    @ConfigEntry(path = "allow-cross-world", comment = "允许跨世界交易")
    private boolean allowCrossWorld = false;
    
    // ==================== Trade Features ====================
    
    @ConfigEntry(path = "enable-money-trade", comment = "启用金币交易（需要Vault）")
    private boolean enableMoneyTrade = true;
    
    @ConfigEntry(path = "enable-exp-trade", comment = "启用经验交易")
    private boolean enableExpTrade = true;
    
    @ConfigEntry(path = "enable-shift-click", comment = "启用Shift+右键玩家发起交易")
    private boolean enableShiftClick = true;
    
    // ==================== Tax Settings ====================

    @Range(min = 0.0, max = 1.0)
    @ConfigEntry(path = "trade-tax", comment = "金币交易税率（0-1之间，0为不收税）")
    private double tradeTax = 0.0;

    @Range(min = 0.0, max = 1.0)
    @ConfigEntry(path = "exp-tax-rate", comment = "经验交易税率（0-1之间，0为不收税）")
    private double expTaxRate = 0.0;

    // ==================== Confirmation Settings ====================

    @Range(min = 0.0, max = 1000000000.0)
    @ConfigEntry(path = "confirm-threshold", comment = "大额交易确认阈值（金币或经验超过此值需二次确认）")
    private double confirmThreshold = 10000;
    
    // ==================== Log Settings ====================

    @ConfigEntry(path = "enable-trade-log", comment = "启用交易日志记录")
    private boolean enableTradeLog = true;

    @Range(min = 1, max = 365)
    @ConfigEntry(path = "log-retention-days", comment = "日志保留天数")
    private int logRetentionDays = 30;

    @Range(min = 1, max = 168)
    @ConfigEntry(path = "cleanup-interval-hours", comment = "日志清理间隔（小时）")
    private int cleanupIntervalHours = 24;
    
    // ==================== Effect Settings ====================
    
    @ConfigEntry(path = "enable-sounds", comment = "启用交易音效")
    private boolean enableSounds = true;
    
    @ConfigEntry(path = "enable-particles", comment = "启用交易粒子效果")
    private boolean enableParticles = true;
    
    @ConfigEntry(path = "enable-bossbar", comment = "启用BossBar请求倒计时")
    private boolean enableBossbar = true;
    
    @ConfigEntry(path = "enable-clickable-buttons", comment = "启用可点击的聊天按钮")
    private boolean enableClickableButtons = true;
    
    // ==================== GUI Settings ====================

    @NotEmpty
    @ConfigEntry(path = "gui-title", comment = "交易界面标题")
    private String guiTitle = "&6与 {PLAYER} 交易";
    
    // ==================== Messages ====================

    @NotEmpty
    @ConfigEntry(path = "messages.request-sent", comment = "发送交易请求")
    private String requestSentMessage = "&a已向 &f{PLAYER} &a发送交易请求！";

    @NotEmpty
    @ConfigEntry(path = "messages.request-received", comment = "收到交易请求")
    private String requestReceivedMessage = "&e{PLAYER} &f请求与你交易！输入 /trade accept 接受";

    @NotEmpty
    @ConfigEntry(path = "messages.request-timeout", comment = "请求超时")
    private String requestTimeoutMessage = "&c交易请求已超时！";

    @NotEmpty
    @ConfigEntry(path = "messages.trade-complete", comment = "交易完成")
    private String tradeCompleteMessage = "&a交易完成！";

    @NotEmpty
    @ConfigEntry(path = "messages.trade-cancelled", comment = "交易取消")
    private String tradeCancelledMessage = "&c交易已取消！";

    @NotEmpty
    @ConfigEntry(path = "messages.trade-disabled", comment = "交易已关闭")
    private String tradeDisabledMessage = "&c对方已关闭交易功能！";

    @NotEmpty
    @ConfigEntry(path = "messages.player-blocked", comment = "被拉黑")
    private String playerBlockedMessage = "&c对方已将你加入黑名单！";

    @NotEmpty
    @ConfigEntry(path = "messages.toggle-on", comment = "开启交易")
    private String toggleOnMessage = "&a你已开启交易功能！";

    @NotEmpty
    @ConfigEntry(path = "messages.toggle-off", comment = "关闭交易")
    private String toggleOffMessage = "&c你已关闭交易功能！";

    @NotEmpty
    @ConfigEntry(path = "messages.block-success", comment = "拉黑成功")
    private String blockSuccessMessage = "&a已将 {PLAYER} 加入交易黑名单！";

    @NotEmpty
    @ConfigEntry(path = "messages.unblock-success", comment = "取消拉黑")
    private String unblockSuccessMessage = "&a已将 {PLAYER} 移出交易黑名单！";

    @NotEmpty
    @ConfigEntry(path = "messages.already-blocked", comment = "已经拉黑")
    private String alreadyBlockedMessage = "&c{PLAYER} 已在你的黑名单中！";

    @NotEmpty
    @ConfigEntry(path = "messages.not-blocked", comment = "未拉黑")
    private String notBlockedMessage = "&c{PLAYER} 不在你的黑名单中！";
    
    public TradeConfig() {
        super("config/trade.yml");
    }
}
