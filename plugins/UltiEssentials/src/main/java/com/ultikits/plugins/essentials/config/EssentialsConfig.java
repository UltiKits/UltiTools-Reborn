package com.ultikits.plugins.essentials.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

/**
 * Main configuration class for UltiEssentials.
 * Controls feature toggles and common parameters.
 */
@Getter
@Setter
@ConfigEntity("config/essentials.yml")
public class EssentialsConfig extends AbstractConfigEntity {

    // ============ 传送类功能 ============
    @ConfigEntry(path = "features.back.enabled", comment = "启用 /back 返回命令")
    private boolean backEnabled = true;

    @ConfigEntry(path = "features.spawn.enabled", comment = "启用 /spawn 出生点命令")
    private boolean spawnEnabled = true;

    @ConfigEntry(path = "features.lobby.enabled", comment = "启用 /lobby 主城命令")
    private boolean lobbyEnabled = true;

    @ConfigEntry(path = "features.wild.enabled", comment = "启用 /wild 随机传送")
    private boolean wildEnabled = true;

    @ConfigEntry(path = "features.wild.max-range", comment = "随机传送最大范围")
    private int wildMaxRange = 10000;

    @ConfigEntry(path = "features.wild.min-range", comment = "随机传送最小范围")
    private int wildMinRange = 100;

    @ConfigEntry(path = "features.wild.cooldown", comment = "随机传送冷却时间(秒)")
    private int wildCooldown = 60;

    @ConfigEntry(path = "features.recall.enabled", comment = "启用 /recall 召回命令")
    private boolean recallEnabled = true;

    // ============ 玩家状态功能 ============
    @ConfigEntry(path = "features.fly.enabled", comment = "启用 /fly 飞行命令")
    private boolean flyEnabled = true;

    @ConfigEntry(path = "features.heal.enabled", comment = "启用 /heal 治疗命令")
    private boolean healEnabled = true;

    @ConfigEntry(path = "features.speed.enabled", comment = "启用 /speed 速度命令")
    private boolean speedEnabled = true;

    @ConfigEntry(path = "features.speed.max-speed", comment = "最大速度倍数")
    private int speedMaxSpeed = 10;

    @ConfigEntry(path = "features.gamemode.enabled", comment = "启用 /gm 游戏模式命令")
    private boolean gamemodeEnabled = true;

    @ConfigEntry(path = "features.hide.enabled", comment = "启用 /hide 隐身命令")
    private boolean hideEnabled = true;

    // ============ 管理类功能 ============
    @ConfigEntry(path = "features.invsee.enabled", comment = "启用 /invsee 查看背包命令")
    private boolean invseeEnabled = true;

    @ConfigEntry(path = "features.whitelist.enabled", comment = "启用 /wl 白名单命令")
    private boolean whitelistEnabled = true;

    // ============ 监听器功能 ============
    @ConfigEntry(path = "features.motd.enabled", comment = "启用 MOTD 自定义")
    private boolean motdEnabled = true;

    @ConfigEntry(path = "features.join-welcome.enabled", comment = "启用入服欢迎消息")
    private boolean joinWelcomeEnabled = true;

    @ConfigEntry(path = "features.tab-bar.enabled", comment = "启用 Tab 栏自定义")
    private boolean tabBarEnabled = true;

    @ConfigEntry(path = "features.auto-reply.enabled", comment = "启用自动回复")
    private boolean autoReplyEnabled = true;

    public EssentialsConfig() {
        super("config/essentials.yml");
    }
}
