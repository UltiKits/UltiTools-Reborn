package com.ultikits.plugins.basic.config;

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
    @ConfigEntry(path = "enableBan", comment = "是否启用传送点")
    private boolean enableWarp = true;
    @ConfigEntry(path = "enableSpawn", comment = "是否启用重生点设置")
    private boolean enableSpawn = true;
    @ConfigEntry(path = "enableLoreEditor", comment = "是否启用Lore编辑器")
    private boolean enableLoreEditor = true;
    @ConfigEntry(path = "enableHide", comment = "是否启用隐身功能")
    private boolean enableHide = true;
    @ConfigEntry(path = "enableTitle", comment = "是否启用头顶显示功能")
    private boolean enableTitle = true;
    @ConfigEntry(path = "enableDeathPunishment", comment = "是否启用死亡惩罚功能")
    private boolean enableDeathPunishment = true;
    @ConfigEntry(path = "enableChat", comment = "是否启用聊天功能")
    private boolean enableChat= true;
    @ConfigEntry(path = "enableAt", comment = "是否启用@功能")
    private boolean enableAt = true;

    public BasicConfig(String configFilePath) {
        super(configFilePath);
    }
}
