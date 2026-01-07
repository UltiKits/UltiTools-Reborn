package com.ultikits.plugins.cleaner.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for UltiCleaner.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/cleaner.yml")
public class CleanerConfig extends AbstractConfigEntity {
    
    // ============ Item Cleanup ============
    @ConfigEntry(path = "item.enabled", comment = "启用物品清理")
    private boolean itemCleanEnabled = true;
    
    @ConfigEntry(path = "item.interval", comment = "清理间隔（秒）")
    private int itemCleanInterval = 300;
    
    @ConfigEntry(path = "item.warn-times", comment = "清理前警告时间点（秒）")
    private List<Integer> itemWarnTimes = Arrays.asList(60, 30, 10, 5, 3, 2, 1);
    
    @ConfigEntry(path = "item.whitelist", comment = "物品白名单（不会被清理的物品）")
    private List<String> itemWhitelist = Arrays.asList(
        "DIAMOND",
        "EMERALD",
        "NETHER_STAR",
        "BEACON",
        "ELYTRA"
    );
    
    @ConfigEntry(path = "item.ignore-named", comment = "忽略有自定义名称的物品")
    private boolean itemIgnoreNamed = true;
    
    @ConfigEntry(path = "item.ignore-recent", comment = "忽略刚掉落的物品（秒）")
    private int itemIgnoreRecentSeconds = 30;
    
    // ============ Entity Cleanup ============
    @ConfigEntry(path = "entity.enabled", comment = "启用实体清理")
    private boolean entityCleanEnabled = true;
    
    @ConfigEntry(path = "entity.interval", comment = "实体清理间隔（秒）")
    private int entityCleanInterval = 600;
    
    @ConfigEntry(path = "entity.types", comment = "要清理的实体类型")
    private List<String> entityTypes = Arrays.asList(
        "ZOMBIE",
        "SKELETON",
        "CREEPER",
        "SPIDER",
        "CAVE_SPIDER",
        "ENDERMAN",
        "WITCH",
        "SLIME",
        "PHANTOM"
    );
    
    @ConfigEntry(path = "entity.whitelist-named", comment = "不清理有自定义名称的实体")
    private boolean entityWhitelistNamed = true;
    
    @ConfigEntry(path = "entity.whitelist-leashed", comment = "不清理被拴绳栓住的实体")
    private boolean entityWhitelistLeashed = true;
    
    @ConfigEntry(path = "entity.whitelist-tamed", comment = "不清理被驯服的实体")
    private boolean entityWhitelistTamed = true;
    
    // ============ World Settings ============
    @ConfigEntry(path = "worlds.blacklist", comment = "不进行清理的世界")
    private List<String> worldBlacklist = Arrays.asList(
        "world_creative"
    );
    
    // ============ Messages ============
    @ConfigEntry(path = "messages.warn", comment = "清理警告消息 ({TIME}为剩余秒数)")
    private String warnMessage = "&c[清理] &f地面物品将在 &e{TIME} &f秒后清理！";
    
    @ConfigEntry(path = "messages.item-cleaned", comment = "物品清理完成消息 ({COUNT}为清理数量)")
    private String itemCleanedMessage = "&a[清理] &f已清理 &e{COUNT} &f个地面物品！";
    
    @ConfigEntry(path = "messages.entity-cleaned", comment = "实体清理完成消息 ({COUNT}为清理数量)")
    private String entityCleanedMessage = "&a[清理] &f已清理 &e{COUNT} &f个实体！";
    
    public CleanerConfig() {
        super("config/cleaner.yml");
    }
}
