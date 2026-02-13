package com.ultikits.plugins.remotebag.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Remote Bag configuration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/remotebag.yml")
public class RemoteBagConfig extends AbstractConfigEntity {

    @Range(min = 1, max = 100)
    @ConfigEntry(path = "default_pages", comment = "Default number of bag pages for new players")
    private int defaultPages = 1;

    @Range(min = 1, max = 100)
    @ConfigEntry(path = "max_pages", comment = "Maximum number of bag pages a player can have")
    private int maxPages = 10;

    @Range(min = 1, max = 6)
    @ConfigEntry(path = "rows_per_page", comment = "Number of rows per page (1-6, each row = 9 slots)")
    private int rowsPerPage = 6;
    
    @NotEmpty
    @ConfigEntry(path = "gui_title", comment = "Title of the bag GUI")
    private String guiTitle = "&6远程背包 &7第 {PAGE}/{MAX} 页";

    @ConfigEntry(path = "permission_based_pages", comment = "Enable permission-based page limits")
    private boolean permissionBasedPages = true;

    @NotEmpty
    @ConfigEntry(path = "permission_prefix", comment = "Permission prefix for page limits (e.g., ultibag.pages.3)")
    private String permissionPrefix = "ultibag.pages.";

    @Range(min = 0, max = 3600)
    @ConfigEntry(path = "auto_save_interval", comment = "Auto save interval in seconds (0 to disable)")
    private int autoSaveInterval = 300;
    
    @ConfigEntry(path = "save_on_close", comment = "Save bag when player closes the GUI")
    private boolean saveOnClose = true;
    
    @NotEmpty
    @ConfigEntry(path = "messages.no_permission", comment = "No permission message")
    private String noPermissionMessage = "&c你没有权限使用远程背包！";

    @NotEmpty
    @ConfigEntry(path = "messages.page_locked", comment = "Page locked message")
    private String pageLockedMessage = "&c你没有权限访问第 {PAGE} 页！";

    @NotEmpty
    @ConfigEntry(path = "messages.bag_saved", comment = "Bag saved message")
    private String bagSavedMessage = "&a远程背包已保存！";

    // ==================== 经济设置 ====================

    @ConfigEntry(path = "economy.enabled", comment = "是否启用购买背包功能（需要 Vault）")
    private boolean economyEnabled = true;

    @Range(min = 0, max = 1000000000)
    @ConfigEntry(path = "economy.base_price", comment = "购买背包的基础价格")
    private int basePrice = 10000;

    @ConfigEntry(path = "economy.price_increase_enabled", comment = "是否启用价格递增（每购买一个背包价格增加）")
    private boolean priceIncreaseEnabled = true;

    @Range(min = 0.0, max = 10.0)
    @ConfigEntry(path = "economy.price_increase_rate", comment = "价格递增比率（0.1 = 每个背包增加10%）")
    private double priceIncreaseRate = 0.1;
    
    // ==================== 音效设置 ====================

    @ConfigEntry(path = "sound.enabled", comment = "是否启用音效")
    private boolean soundEnabled = true;

    @NotEmpty
    @ConfigEntry(path = "sound.open", comment = "打开背包音效")
    private String openSound = "BLOCK_CHEST_OPEN";

    @NotEmpty
    @ConfigEntry(path = "sound.close", comment = "关闭背包音效")
    private String closeSound = "BLOCK_CHEST_CLOSE";

    @NotEmpty
    @ConfigEntry(path = "sound.purchase", comment = "购买成功音效")
    private String purchaseSound = "ENTITY_PLAYER_LEVELUP";

    @NotEmpty
    @ConfigEntry(path = "sound.error", comment = "错误提示音效")
    private String errorSound = "ENTITY_VILLAGER_NO";

    @Range(min = 0.0, max = 1.0)
    @ConfigEntry(path = "sound.volume", comment = "音量 (0.0-1.0)")
    private double soundVolume = 1.0;

    @Range(min = 0.5, max = 2.0)
    @ConfigEntry(path = "sound.pitch", comment = "音调 (0.5-2.0)")
    private double soundPitch = 1.0;

    // ==================== 锁定设置 ====================

    @Range(min = 10, max = 3600)
    @ConfigEntry(path = "lock.timeout_seconds", comment = "背包锁超时时间（秒），超时后自动释放")
    private int lockTimeout = 300;
    
    @ConfigEntry(path = "lock.notify_readonly_viewers", comment = "所有者开始使用背包时是否通知只读查看者")
    private boolean notifyReadonlyViewers = true;

    public RemoteBagConfig(String configFilePath) {
        super(configFilePath);
    }
}
