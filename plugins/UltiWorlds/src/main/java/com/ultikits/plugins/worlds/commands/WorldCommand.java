package com.ultikits.plugins.worlds.commands;

import com.ultikits.plugins.worlds.conversation.WorldCreateConversation;
import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.gui.WorldListPage;
import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * World management command executor.
 * Migrated to BaseCommandExecutor with improved annotations.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"world", "worlds", "w"},
    permission = "ultiworlds.use",
    description = "世界管理系统"
)
public class WorldCommand extends BaseCommandExecutor {
    
    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private WorldService worldService;
    
    // ==================== Basic Commands ====================
    
    @CmdMapping(format = "")
    public void openWorldList(@CmdSender Player player) {
        new WorldListPage(player, worldService, plugin).open();
    }
    
    @CmdMapping(format = "list")
    public void listWorlds(@CmdSender Player player) {
        List<World> worlds = worldService.getAllWorlds();
        
        player.sendMessage(i18n("world.list.header").replace("{COUNT}", String.valueOf(worlds.size())));
        for (World world : worlds) {
            WorldSettings settings = worldService.getOrCreateSettings(world.getName());
            String displayName = settings.getDisplayName() != null ? settings.getDisplayName() : world.getName();
            player.sendMessage(i18n("world.list.item")
                .replace("{NAME}", displayName)
                .replace("{PLAYERS}", String.valueOf(world.getPlayers().size())));
        }
    }
    
    @CmdMapping(format = "tp <world>")
    @CmdCD(5)
    public void teleportToWorld(@CmdSender Player player, @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!worldService.getConfig().isTpToWorldEnabled()) {
            player.sendMessage(i18n("world.tp.disabled"));
            return;
        }
        
        worldService.teleportToWorld(player, worldName);
    }
    
    // ==================== Create Commands ====================
    
    @CmdMapping(format = "wizard")
    public void startWizard(@CmdSender Player player) {
        if (!player.hasPermission("ultiworlds.admin.create")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        WorldCreateConversation.start(player, worldService, plugin);
    }
    
    @CmdMapping(format = "create <name>")
    @RunAsync
    public void createWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.create")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        if (Bukkit.getWorld(name) != null) {
            player.sendMessage(i18n("world.create.exists").replace("{WORLD}", name));
            return;
        }
        
        player.sendMessage(i18n("world.create.creating").replace("{WORLD}", name));
        
        if (worldService.createWorld(name, World.Environment.NORMAL, WorldType.NORMAL, null)) {
            player.sendMessage(i18n("world.create.success").replace("{WORLD}", name));
        } else {
            player.sendMessage(i18n("world.create.failed"));
        }
    }
    
    @CmdMapping(format = "create <name> <type>")
    @RunAsync
    public void createWorldWithType(@CmdSender Player player, 
                                    @CmdParam("name") String name,
                                    @CmdParam(value = "type", suggest = "suggestWorldTypes") String type) {
        if (!player.hasPermission("ultiworlds.admin.create")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World.Environment environment;
        try {
            environment = World.Environment.valueOf(type.toUpperCase());
        } catch (Exception e) {
            player.sendMessage(i18n("world.create.invalid_type"));
            return;
        }
        
        player.sendMessage(i18n("world.create.creating").replace("{WORLD}", name));
        
        if (worldService.createWorld(name, environment, WorldType.NORMAL, null)) {
            player.sendMessage(i18n("world.create.success").replace("{WORLD}", name));
        } else {
            player.sendMessage(i18n("world.create.failed"));
        }
    }
    
    // ==================== Load/Unload Commands ====================
    
    @CmdMapping(format = "load <name>")
    public void loadWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.load")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        if (worldService.loadWorld(name)) {
            player.sendMessage(i18n("world.load.success").replace("{WORLD}", name));
        } else {
            player.sendMessage(i18n("world.load.failed"));
        }
    }
    
    @CmdMapping(format = "unload <name>")
    public void unloadWorld(@CmdSender Player player, @CmdParam(value = "name", suggest = "suggestWorlds") String name) {
        if (!player.hasPermission("ultiworlds.admin.unload")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        if (name.equals(worldService.getConfig().getDefaultWorld())) {
            player.sendMessage(i18n("world.unload.default"));
            return;
        }
        
        if (worldService.unloadWorld(name, true)) {
            player.sendMessage(i18n("world.unload.success").replace("{WORLD}", name));
        } else {
            player.sendMessage(i18n("world.unload.failed"));
        }
    }
    
    @CmdMapping(format = "delete <name>")
    public void deleteWorld(@CmdSender Player player, @CmdParam(value = "name", suggest = "suggestWorlds") String name) {
        if (!player.hasPermission("ultiworlds.admin.delete")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        if (name.equals(worldService.getConfig().getDefaultWorld())) {
            player.sendMessage(i18n("world.delete.default"));
            return;
        }
        
        player.sendMessage(i18n("world.delete.deleting").replace("{WORLD}", name));
        
        if (worldService.deleteWorld(name)) {
            player.sendMessage(i18n("world.delete.success").replace("{WORLD}", name));
        } else {
            player.sendMessage(i18n("world.delete.failed"));
        }
    }
    
    // ==================== Settings Commands ====================
    
    @CmdMapping(format = "set <world> <option> <value>")
    public void setWorldOption(@CmdSender Player player,
                               @CmdParam(value = "world", suggest = "suggestWorlds") String worldName,
                               @CmdParam(value = "option", suggest = "suggestOptions") String option,
                               @CmdParam(value = "value", suggest = "suggestBooleans") String value) {
        if (!player.hasPermission("ultiworlds.admin.settings")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("world.not_found").replace("{WORLD}", worldName));
            return;
        }
        
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        boolean boolValue = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equals("1");
        
        switch (option.toLowerCase()) {
            case "pvp":
                settings.setPvpEnabled(boolValue);
                world.setPVP(boolValue);
                break;
            case "monsters":
                settings.setMonstersEnabled(boolValue);
                break;
            case "animals":
                settings.setAnimalsEnabled(boolValue);
                break;
            case "weather":
                settings.setWeatherEnabled(boolValue);
                break;
            case "hidden":
                settings.setHidden(boolValue);
                break;
            case "locked":
                settings.setLocked(boolValue);
                break;
            case "blocked":
                settings.setBlocked(boolValue);
                break;
            case "displayname":
            case "name":
                settings.setDisplayName(value);
                break;
            case "description":
            case "desc":
                settings.setDescription(value);
                break;
            case "icon":
                settings.setIcon(value.toUpperCase());
                break;
            case "difficulty":
                try {
                    Difficulty diff = Difficulty.valueOf(value.toUpperCase());
                    settings.setDifficulty(diff.name());
                    World w = Bukkit.getWorld(worldName);
                    if (w != null) {
                        w.setDifficulty(diff);
                    }
                } catch (IllegalArgumentException e) {
                    player.sendMessage(i18n("error.invalid_difficulty"));
                    return;
                }
                break;
            default:
                player.sendMessage(i18n("world.set.invalid_option"));
                return;
        }
        
        worldService.updateSettings(settings);
        player.sendMessage(i18n("world.set.success")
            .replace("{OPTION}", option)
            .replace("{VALUE}", value)
            .replace("{WORLD}", worldName));
    }
    
    // ==================== Protection Commands ====================
    
    @CmdMapping(format = "protect <world>")
    public void protectWorld(@CmdSender Player player, @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!player.hasPermission("ultiworlds.admin.protect")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("world.not_found").replace("{WORLD}", worldName));
            return;
        }
        
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.enableFullProtection();
        worldService.updateSettings(settings);
        
        player.sendMessage(i18n("world.protect.enabled").replace("{WORLD}", worldName));
    }
    
    @CmdMapping(format = "unprotect <world>")
    public void unprotectWorld(@CmdSender Player player, @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!player.hasPermission("ultiworlds.admin.protect")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("world.not_found").replace("{WORLD}", worldName));
            return;
        }
        
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.disableAllProtection();
        worldService.updateSettings(settings);
        
        player.sendMessage(i18n("world.protect.disabled").replace("{WORLD}", worldName));
    }
    
    // ==================== Block Commands ====================
    
    @CmdMapping(format = "block <world>")
    public void blockWorld(@CmdSender Player player, @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!player.hasPermission("ultiworlds.admin.block")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("world.not_found").replace("{WORLD}", worldName));
            return;
        }
        
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.setBlocked(true);
        worldService.updateSettings(settings);
        
        // Kick all players from the world
        World defaultWorld = Bukkit.getWorld(worldService.getConfig().getDefaultWorld());
        if (defaultWorld == null) {
            defaultWorld = Bukkit.getWorlds().get(0);
        }
        
        for (Player p : world.getPlayers()) {
            p.teleport(defaultWorld.getSpawnLocation());
            p.sendMessage(i18n("world.block.kicked").replace("{WORLD}", worldName));
        }
        
        player.sendMessage(i18n("world.block.enabled").replace("{WORLD}", worldName));
    }
    
    @CmdMapping(format = "unblock <world>")
    public void unblockWorld(@CmdSender Player player, @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!player.hasPermission("ultiworlds.admin.block")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("world.not_found").replace("{WORLD}", worldName));
            return;
        }
        
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.setBlocked(false);
        worldService.updateSettings(settings);
        
        player.sendMessage(i18n("world.block.disabled").replace("{WORLD}", worldName));
    }
    
    // ==================== Other Commands ====================
    
    @CmdMapping(format = "setspawn")
    public void setWorldSpawn(@CmdSender Player player) {
        if (!player.hasPermission("ultiworlds.admin.setspawn")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }
        
        worldService.setWorldSpawn(player.getWorld().getName(), player.getLocation());
        player.sendMessage(i18n("world.setspawn.success").replace("{WORLD}", player.getWorld().getName()));
    }
    
    // ==================== Difficulty Command ====================

    @CmdMapping(format = "difficulty <world> <level>")
    public void setDifficulty(@CmdSender Player player,
                              @CmdParam(value = "world", suggest = "suggestWorlds") String worldName,
                              @CmdParam(value = "level", suggest = "suggestDifficulties") String level) {
        if (!player.hasPermission("ultiworlds.admin.settings")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(i18n("error.world_not_found").replace("%world%", worldName));
            return;
        }

        Difficulty difficulty;
        try {
            difficulty = Difficulty.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(i18n("error.invalid_difficulty"));
            return;
        }

        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.setDifficulty(difficulty.name());
        worldService.updateSettings(settings);
        world.setDifficulty(difficulty);

        player.sendMessage(i18n("success.difficulty_set")
            .replace("%value%", difficulty.name())
            .replace("%world%", worldName));
    }

    // ==================== Post-Teleport Command Management ====================

    @CmdMapping(format = "postcmd <world> add <command>")
    public void addPostCmd(@CmdSender Player player,
                           @CmdParam(value = "world", suggest = "suggestWorlds") String worldName,
                           @CmdParam("command") String command) {
        if (!player.hasPermission("ultiworlds.admin.settings")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }

        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        String existing = settings.getPostTeleportCommands();
        if (existing == null || existing.isEmpty()) {
            settings.setPostTeleportCommands(command);
        } else {
            settings.setPostTeleportCommands(existing + "\n" + command);
        }
        worldService.updateSettings(settings);

        player.sendMessage(i18n("success.post_cmd_added").replace("%world%", worldName));
    }

    @CmdMapping(format = "postcmd <world> list")
    public void listPostCmd(@CmdSender Player player,
                            @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        String commands = settings.getPostTeleportCommands();

        player.sendMessage(i18n("success.post_cmd_list_header").replace("%world%", worldName));
        if (commands == null || commands.isEmpty()) {
            player.sendMessage(i18n("success.post_cmd_empty"));
        } else {
            for (String cmd : commands.split("\\n")) {
                player.sendMessage(i18n("success.post_cmd_list_item").replace("%command%", cmd.trim()));
            }
        }
    }

    @CmdMapping(format = "postcmd <world> clear")
    public void clearPostCmd(@CmdSender Player player,
                             @CmdParam(value = "world", suggest = "suggestWorlds") String worldName) {
        if (!player.hasPermission("ultiworlds.admin.settings")) {
            player.sendMessage(i18n("error.no_permission"));
            return;
        }

        WorldSettings settings = worldService.getOrCreateSettings(worldName);
        settings.setPostTeleportCommands(null);
        worldService.updateSettings(settings);

        player.sendMessage(i18n("success.post_cmd_cleared").replace("%world%", worldName));
    }

    // ==================== Info Command ====================

    @CmdMapping(format = "info")
    public void worldInfo(@CmdSender Player player) {
        World world = player.getWorld();
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        player.sendMessage(i18n("world.info.header"));
        player.sendMessage(i18n("world.info.name").replace("{VALUE}", world.getName()));
        player.sendMessage(i18n("world.info.displayname").replace("{VALUE}", 
            settings.getDisplayName() != null ? settings.getDisplayName() : world.getName()));
        player.sendMessage(i18n("world.info.environment").replace("{VALUE}", world.getEnvironment().name()));
        player.sendMessage(i18n("world.info.seed").replace("{VALUE}", String.valueOf(world.getSeed())));
        player.sendMessage(i18n("world.info.players").replace("{VALUE}", String.valueOf(world.getPlayers().size())));
        player.sendMessage(i18n("world.info.pvp").replace("{VALUE}", 
            settings.isPvpEnabled() ? i18n("common.enabled") : i18n("common.disabled")));
        player.sendMessage(i18n("world.info.monsters").replace("{VALUE}", 
            settings.isMonstersEnabled() ? i18n("common.enabled") : i18n("common.disabled")));
        player.sendMessage(i18n("world.info.protection").replace("{VALUE}", 
            settings.hasProtection() ? i18n("common.enabled") : i18n("common.disabled")));
        player.sendMessage(i18n("world.info.blocked").replace("{VALUE}", 
            settings.isBlocked() ? i18n("common.yes") : i18n("common.no")));
    }
    
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        player.sendMessage(i18n("help.header"));
        player.sendMessage(i18n("help.list"));
        player.sendMessage(i18n("help.tp"));
        player.sendMessage(i18n("help.info"));
        player.sendMessage(i18n("help.wizard"));
        
        if (player.hasPermission("ultiworlds.admin")) {
            player.sendMessage(i18n("help.admin_header"));
            player.sendMessage(i18n("help.create"));
            player.sendMessage(i18n("help.load"));
            player.sendMessage(i18n("help.unload"));
            player.sendMessage(i18n("help.delete"));
            player.sendMessage(i18n("help.setspawn"));
            player.sendMessage(i18n("help.set"));
            player.sendMessage(i18n("help.protect"));
            player.sendMessage(i18n("help.block"));
            player.sendMessage(i18n("command.help.difficulty"));
            player.sendMessage(i18n("command.help.postcmd"));
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
    
    // ==================== Suggestion Methods ====================
    
    /**
     * Suggest world names for tab completion.
     */
    public List<String> suggestWorlds(Player player, String input) {
        return Bukkit.getWorlds().stream()
            .map(World::getName)
            .filter(name -> name.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }
    
    /**
     * Suggest world types for tab completion.
     */
    public List<String> suggestWorldTypes(Player player, String input) {
        return Arrays.asList("NORMAL", "NETHER", "THE_END").stream()
            .filter(type -> type.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }
    
    /**
     * Suggest setting options for tab completion.
     */
    public List<String> suggestOptions(Player player, String input) {
        return Arrays.asList("pvp", "monsters", "animals", "weather", "hidden",
            "locked", "blocked", "displayname", "description", "icon", "difficulty").stream()
            .filter(opt -> opt.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }
    
    /**
     * Suggest boolean values for tab completion.
     */
    public List<String> suggestBooleans(Player player, String input) {
        return Arrays.asList("true", "false", "on", "off").stream()
            .filter(val -> val.startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }

    /**
     * Suggest difficulty levels for tab completion.
     */
    public List<String> suggestDifficulties(Player player, String input) {
        return Arrays.asList("PEACEFUL", "EASY", "NORMAL", "HARD").stream()
            .filter(d -> d.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }
    
    /**
     * Get i18n message from plugin.
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
