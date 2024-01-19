package com.ultikits.plugins.basic.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
@Setter
@ConfigEntity("config/death.yml")
public class DeathPunishmentConfig extends AbstractConfigEntity {
    @ConfigEntry(path = "enable_item_drop", comment = "是否开启物品掉落")
    private boolean enableItemDrop = false;
    @ConfigEntry(path = "enable_money_drop", comment = "是否开启金币掉落")
    private boolean enableMoneyDrop = true;
    @ConfigEntry(path = "enable_punish_commands", comment = "是否开启死亡执行后台命令")
    private boolean enablePunishCommands = true;
    @ConfigEntry(path = "money_dropped_ondeath", comment = "死亡后掉落的金币数")
    private int moneyDropOnDeath = 100;
    @ConfigEntry(path = "item_dropped_ondeath", comment = "死亡后随机掉落的物品数")
    private int itemDropOnDeath = 3;
    @ConfigEntry(path = "punish_commands", comment = "死亡后后台执行的指令（{PLAYER}指代玩家名占位符）")
    private List<String> punishCommands = Collections.emptyList();
    @ConfigEntry(path = "worlds_enabled_item_drop", comment = "开启死亡随机物品掉落的世界")
    private List<String> worldEnabledItemDrop = Arrays.asList("world", "world_nether", "world_the_end");
    @ConfigEntry(path = "worlds_enabled_money_drop", comment = "开启死亡后金币掉落的世界")
    private List<String> worldEnabledMoneyDrop = Arrays.asList("world", "world_nether", "world_the_end");
    @ConfigEntry(path = "worlds_enabled_punish_commands", comment = "开启死亡后执行后台指令的世界")
    private List<String> worldEnabledPunishCommands = Arrays.asList("world", "world_nether", "world_the_end");
    @ConfigEntry(path = "item_drop_whitelist", comment = "物品掉落白名单")
    private List<String> itemDropWhiteList = Collections.emptyList();

    public DeathPunishmentConfig(String configFilePath) {
        super(configFilePath);
    }
}
