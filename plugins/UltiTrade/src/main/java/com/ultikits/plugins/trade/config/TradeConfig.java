package com.ultikits.plugins.trade.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
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
    
    @ConfigEntry(path = "request-timeout", comment = "交易请求超时时间（秒）")
    private int requestTimeout = 30;
    
    @ConfigEntry(path = "trade-timeout", comment = "交易窗口超时时间（秒）")
    private int tradeTimeout = 120;
    
    @ConfigEntry(path = "max-distance", comment = "交易最大距离（格），0为无限制")
    private int maxDistance = 50;
    
    @ConfigEntry(path = "allow-cross-world", comment = "允许跨世界交易")
    private boolean allowCrossWorld = false;
    
    @ConfigEntry(path = "enable-money-trade", comment = "启用金币交易（需要Vault）")
    private boolean enableMoneyTrade = true;
    
    @ConfigEntry(path = "trade-tax", comment = "交易税率（0-1之间，0为不收税）")
    private double tradeTax = 0.0;
    
    @ConfigEntry(path = "gui-title", comment = "交易界面标题")
    private String guiTitle = "&6与 {PLAYER} 交易";
    
    @ConfigEntry(path = "messages.request-sent", comment = "发送交易请求")
    private String requestSentMessage = "&a已向 &f{PLAYER} &a发送交易请求！";
    
    @ConfigEntry(path = "messages.request-received", comment = "收到交易请求")
    private String requestReceivedMessage = "&e{PLAYER} &f请求与你交易！输入 /trade accept 接受";
    
    @ConfigEntry(path = "messages.request-timeout", comment = "请求超时")
    private String requestTimeoutMessage = "&c交易请求已超时！";
    
    @ConfigEntry(path = "messages.trade-complete", comment = "交易完成")
    private String tradeCompleteMessage = "&a交易完成！";
    
    @ConfigEntry(path = "messages.trade-cancelled", comment = "交易取消")
    private String tradeCancelledMessage = "&c交易已取消！";
    
    public TradeConfig() {
        super("config/trade.yml");
    }
}
