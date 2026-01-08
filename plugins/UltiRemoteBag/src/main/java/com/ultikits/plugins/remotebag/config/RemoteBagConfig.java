package com.ultikits.plugins.remotebag.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

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
    
    public RemoteBagConfig(String configFilePath) {
        super(configFilePath);
    }
    
    @ConfigEntry(path = "default_pages", comment = "Default number of bag pages for new players")
    private int defaultPages = 1;
    
    @ConfigEntry(path = "max_pages", comment = "Maximum number of bag pages a player can have")
    private int maxPages = 10;
    
    @ConfigEntry(path = "rows_per_page", comment = "Number of rows per page (1-6, each row = 9 slots)")
    private int rowsPerPage = 6;
    
    @ConfigEntry(path = "gui_title", comment = "Title of the bag GUI")
    private String guiTitle = "&6远程背包 &7第 {PAGE}/{MAX} 页";
    
    @ConfigEntry(path = "permission_based_pages", comment = "Enable permission-based page limits")
    private boolean permissionBasedPages = true;
    
    @ConfigEntry(path = "permission_prefix", comment = "Permission prefix for page limits (e.g., ultibag.pages.3)")
    private String permissionPrefix = "ultibag.pages.";
    
    @ConfigEntry(path = "auto_save_interval", comment = "Auto save interval in seconds (0 to disable)")
    private int autoSaveInterval = 300;
    
    @ConfigEntry(path = "save_on_close", comment = "Save bag when player closes the GUI")
    private boolean saveOnClose = true;
    
    @ConfigEntry(path = "messages.no_permission", comment = "No permission message")
    private String noPermissionMessage = "&c你没有权限使用远程背包！";
    
    @ConfigEntry(path = "messages.page_locked", comment = "Page locked message")
    private String pageLockedMessage = "&c你没有权限访问第 {PAGE} 页！";
    
    @ConfigEntry(path = "messages.bag_saved", comment = "Bag saved message")
    private String bagSavedMessage = "&a远程背包已保存！";
}
