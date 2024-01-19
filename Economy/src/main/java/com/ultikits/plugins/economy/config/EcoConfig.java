package com.ultikits.plugins.economy.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class EcoConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "useThirdPartEconomy", comment = "是否使用其他的经济插件作为基础（即仅使用本插件的银行功能）")
    private boolean useThirdPartEconomy = false;
    @ConfigEntry(path = "enableInterest", comment = "是否开启利息")
    private boolean enableInterest = true;
    @ConfigEntry(path = "interestRate", comment = "利率，利息 = 利率 × 本金")
    private double interestRate = 0.0003;
    @ConfigEntry(path = "interestTime", comment = "利息发放间隔（分钟）")
    private int interestTime = 30;
    @ConfigEntry(path = "initial_money", comment = "玩家初始货币数量")
    private double initMoney = 1000;
    @ConfigEntry(path = "op_operate_money", comment = "服务器管理员是否能够增减玩家货币")
    private boolean opOperateMoney = false;
    @ConfigEntry(path = "currency_name", comment = "货币名称")
    private String currencyName = "金币";
    @ConfigEntry(path = "server_trade_log", comment = "是否开启服务器交易记录")
    private boolean enableTradeLog = false;

    public EcoConfig(String configFilePath) {
        super(configFilePath);
    }
}
