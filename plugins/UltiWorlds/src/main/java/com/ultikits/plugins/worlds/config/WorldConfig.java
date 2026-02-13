package com.ultikits.plugins.worlds.config;

import java.util.Arrays;
import java.util.List;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;

import lombok.Getter;
import lombok.Setter;

/**
 * World management configuration.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Getter
@Setter
@ConfigEntity("config/worlds.yml")
public class WorldConfig extends AbstractConfigEntity {

    @NotEmpty
    @ConfigEntry(path = "default_world", comment = "Default world name")
    private String defaultWorld = "world";

    @ConfigEntry(path = "protected_worlds", comment = "Worlds that cannot be auto-unloaded or deleted")
    private List<String> protectedWorlds = Arrays.asList("world", "world_nether", "world_the_end");

    @ConfigEntry(path = "load_worlds_on_start", comment = "Worlds to load automatically on server start")
    private List<String> loadWorldsOnStart = Arrays.asList();

    // ==================== Auto-Unload Settings ====================

    @ConfigEntry(path = "auto_unload.enabled", comment = "Enable auto-unloading of empty worlds")
    private boolean autoUnloadEmptyWorlds = false;

    @Range(min = 10, max = 3600)
    @ConfigEntry(path = "auto_unload.check_interval", comment = "Check interval in seconds")
    private int emptyWorldCheckInterval = 60;

    @Range(min = 60, max = 86400)
    @ConfigEntry(path = "auto_unload.unload_after", comment = "Unload world after being empty for this many seconds")
    private int emptyWorldUnloadAfter = 300;
    
    // Legacy compat
    @ConfigEntry(path = "unload_empty_worlds", comment = "Deprecated: use auto_unload.enabled instead")
    private boolean unloadEmptyWorlds = false;
    
    @ConfigEntry(path = "unload_delay", comment = "Deprecated: use auto_unload.unload_after instead")
    private int unloadDelay = 300;
    
    // ==================== GUI Settings ====================

    @NotEmpty
    @Size(min = 1, max = 32)
    @ConfigEntry(path = "gui_title", comment = "World list GUI title")
    private String guiTitle = "&6世界列表";

    // ==================== Teleport Settings ====================

    @ConfigEntry(path = "tp_to_world.enabled", comment = "Allow players to teleport between worlds")
    private boolean tpToWorldEnabled = true;

    @ConfigEntry(path = "tp_to_world.permission_per_world", comment = "Require permission for each world")
    private boolean permissionPerWorld = false;

    @Range(min = 0, max = 300)
    @ConfigEntry(path = "tp_to_world.cooldown", comment = "World teleport cooldown in seconds")
    private int tpCooldown = 10;
    
    @ConfigEntry(path = "world_spawn.use_spawn_location", comment = "Teleport to world spawn instead of last location")
    private boolean useSpawnLocation = true;

    @ConfigEntry(path = "tp_to_world.show_description", comment = "Show world description to player on teleport")
    private boolean showDescriptionOnTeleport = true;

    // ==================== Inventory Isolation Settings ====================
    
    @ConfigEntry(path = "world_isolation.enabled", comment = "Enable per-world inventory isolation")
    private boolean inventoryIsolation = false;
    
    @ConfigEntry(path = "world_isolation.separate_inventory", comment = "Separate inventory per world")
    private boolean separateInventory = true;
    
    @ConfigEntry(path = "world_isolation.separate_ender_chest", comment = "Separate ender chest per world")
    private boolean separateEnderChest = true;
    
    @ConfigEntry(path = "world_isolation.separate_experience", comment = "Separate XP levels per world")
    private boolean separateExperience = false;
    
    @ConfigEntry(path = "world_isolation.separate_health", comment = "Separate health per world")
    private boolean separateHealth = false;
    
    @ConfigEntry(path = "world_isolation.separate_hunger", comment = "Separate hunger per world")
    private boolean separateHunger = false;
    
    @ConfigEntry(path = "world_isolation.separate_effects", comment = "Separate potion effects per world")
    private boolean separateEffects = false;
    
    @ConfigEntry(path = "world_isolation.shared_worlds", comment = "Worlds that share inventory (comma separated groups)")
    private List<String> sharedWorldGroups = Arrays.asList("world,world_nether,world_the_end");
    
    // ==================== Messages (legacy, prefer i18n) ====================
    
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

    public WorldConfig(String configFilePath) {
        super(configFilePath);
    }
}
