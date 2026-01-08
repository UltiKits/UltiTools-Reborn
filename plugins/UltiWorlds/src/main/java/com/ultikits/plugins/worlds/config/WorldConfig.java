package com.ultikits.plugins.worlds.config;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

import lombok.Getter;
import lombok.Setter;

/**
 * World management configuration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Getter
@Setter
@ConfigEntity("config/worlds.yml")
public class WorldConfig extends AbstractConfigEntity {
    
    public WorldConfig(String configFilePath) {
        super(configFilePath);
    }
    
    @ConfigEntry(path = "default_world", comment = "Default world name")
    private String defaultWorld = "world";
    
    @ConfigEntry(path = "load_worlds_on_start", comment = "Worlds to load automatically on server start")
    private List<String> loadWorldsOnStart = Arrays.asList();
    
    @ConfigEntry(path = "unload_empty_worlds", comment = "Unload worlds with no players after delay")
    private boolean unloadEmptyWorlds = false;
    
    @ConfigEntry(path = "unload_delay", comment = "Delay before unloading empty world (seconds)")
    private int unloadDelay = 300;
    
    @ConfigEntry(path = "gui_title", comment = "World list GUI title")
    private String guiTitle = "&6世界列表";
    
    @ConfigEntry(path = "tp_to_world.enabled", comment = "Allow players to teleport between worlds")
    private boolean tpToWorldEnabled = true;
    
    @ConfigEntry(path = "tp_to_world.permission_per_world", comment = "Require permission for each world")
    private boolean permissionPerWorld = false;
    
    @ConfigEntry(path = "tp_to_world.cooldown", comment = "World teleport cooldown in seconds")
    private int tpCooldown = 10;
    
    @ConfigEntry(path = "world_spawn.use_spawn_location", comment = "Teleport to world spawn instead of last location")
    private boolean useSpawnLocation = true;
    
    @ConfigEntry(path = "world_isolation.enabled", comment = "Enable per-world inventory isolation")
    private boolean inventoryIsolation = false;
    
    @ConfigEntry(path = "world_isolation.shared_worlds", comment = "Worlds that share inventory (groups)")
    private List<String> sharedWorldGroups = Arrays.asList("world,world_nether,world_the_end");
    
    @ConfigEntry(path = "messages.world_teleport", comment = "World teleport message")
    private String worldTeleportMessage = "&a已传送到世界: {WORLD}";
    
    @ConfigEntry(path = "messages.world_not_found", comment = "World not found message")
    private String worldNotFoundMessage = "&c世界 {WORLD} 不存在！";
    
    @ConfigEntry(path = "messages.no_permission", comment = "No permission for world message")
    private String noPermissionMessage = "&c你没有权限进入世界 {WORLD}！";
    
    @ConfigEntry(path = "messages.world_created", comment = "World created message")
    private String worldCreatedMessage = "&a世界 {WORLD} 已创建！";
    
    @ConfigEntry(path = "messages.world_deleted", comment = "World deleted message")
    private String worldDeletedMessage = "&c世界 {WORLD} 已删除！";
}
