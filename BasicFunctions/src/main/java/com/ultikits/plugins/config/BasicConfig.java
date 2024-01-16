package com.ultikits.plugins.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity("config/config.yml")
public class BasicConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "enableWhitelist", comment = "是否启用白名单")
    private boolean enableWhitelist = false;
    @ConfigEntry(path = "enableHeal", comment = "是否启用治疗")
    private boolean enableHeal = true;
    @ConfigEntry(path = "enableBack", comment = "是否启用回到上一个位置")
    private boolean enableBack = true;
    @ConfigEntry(path = "enableFly", comment = "是否启用飞行")
    private boolean enableFly = true;
    @ConfigEntry(path = "enableGmChange", comment = "是否启用切换模式")
    private boolean enableGmChange = true;
    @ConfigEntry(path = "enableRandomTeleport", comment = "是否启用随机传送")
    private boolean enableRandomTeleport = true;
    @ConfigEntry(path = "enableJoinWelcome", comment = "是否启用入服欢迎")
    private boolean enableJoinWelcome = true;
    @ConfigEntry(path = "enableTpa", comment = "是否启用传送请求")
    private boolean enableTpa = true;
    @ConfigEntry(path = "enableSpeed", comment = "是否启用速度设置")
    private boolean enableSpeed = true;
    @ConfigEntry(path = "enableBan", comment = "是否启用封禁")
    private boolean enableBan = true;

    public BasicConfig(String configFilePath) {
        super(configFilePath);
    }
}
