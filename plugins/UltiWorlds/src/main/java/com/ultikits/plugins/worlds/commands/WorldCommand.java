package com.ultikits.plugins.worlds.commands;

import com.ultikits.plugins.worlds.entity.WorldSettings;
import com.ultikits.plugins.worlds.gui.WorldListGUI;
import com.ultikits.plugins.worlds.service.WorldService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * World management command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"world", "worlds", "w"},
    permission = "ultiworlds.use",
    description = "世界管理系统"
)
public class WorldCommand extends AbstractCommendExecutor {
    
    private final WorldService worldService;
    
    public WorldCommand(WorldService worldService) {
        this.worldService = worldService;
    }
    
    @CmdMapping(format = "")
    public void openWorldList(@CmdSender Player player) {
        WorldListGUI gui = new WorldListGUI(worldService, player);
        player.openInventory(gui.getInventory());
    }
    
    @CmdMapping(format = "list")
    public void listWorlds(@CmdSender Player player) {
        List<World> worlds = worldService.getAllWorlds();
        
        player.sendMessage(ChatColor.GOLD + "=== 世界列表 (" + worlds.size() + ") ===");
        for (World world : worlds) {
            WorldSettings settings = worldService.getOrCreateSettings(world.getName());
            String displayName = settings.getDisplayName() != null ? settings.getDisplayName() : world.getName();
            player.sendMessage(ChatColor.GREEN + "- " + ChatColor.WHITE + displayName + 
                ChatColor.GRAY + " (" + world.getPlayers().size() + " 玩家)");
        }
    }
    
    @CmdMapping(format = "tp <world>")
    public void teleportToWorld(@CmdSender Player player, @CmdParam("world") String worldName) {
        if (!worldService.getConfig().isTpToWorldEnabled()) {
            player.sendMessage(ChatColor.RED + "世界传送功能已禁用！");
            return;
        }
        
        worldService.teleportToWorld(player, worldName);
    }
    
    @CmdMapping(format = "create <name>")
    public void createWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.create")) {
            player.sendMessage(ChatColor.RED + "你没有权限创建世界！");
            return;
        }
        
        if (Bukkit.getWorld(name) != null) {
            player.sendMessage(ChatColor.RED + "世界 " + name + " 已存在！");
            return;
        }
        
        player.sendMessage(ChatColor.YELLOW + "正在创建世界 " + name + "...");
        
        if (worldService.createWorld(name, World.Environment.NORMAL, WorldType.NORMAL, null)) {
            player.sendMessage(worldService.getConfig().getWorldCreatedMessage()
                .replace("{WORLD}", name)
                .replace("&", "§"));
        } else {
            player.sendMessage(ChatColor.RED + "创建世界失败！");
        }
    }
    
    @CmdMapping(format = "create <name> <type>")
    public void createWorldWithType(@CmdSender Player player, 
                                    @CmdParam("name") String name,
                                    @CmdParam("type") String type) {
        if (!player.hasPermission("ultiworlds.admin.create")) {
            player.sendMessage(ChatColor.RED + "你没有权限创建世界！");
            return;
        }
        
        World.Environment environment;
        try {
            environment = World.Environment.valueOf(type.toUpperCase());
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "无效的世界类型！可用: NORMAL, NETHER, THE_END");
            return;
        }
        
        player.sendMessage(ChatColor.YELLOW + "正在创建世界 " + name + "...");
        
        if (worldService.createWorld(name, environment, WorldType.NORMAL, null)) {
            player.sendMessage(worldService.getConfig().getWorldCreatedMessage()
                .replace("{WORLD}", name)
                .replace("&", "§"));
        } else {
            player.sendMessage(ChatColor.RED + "创建世界失败！");
        }
    }
    
    @CmdMapping(format = "load <name>")
    public void loadWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.load")) {
            player.sendMessage(ChatColor.RED + "你没有权限加载世界！");
            return;
        }
        
        if (worldService.loadWorld(name)) {
            player.sendMessage(ChatColor.GREEN + "世界 " + name + " 已加载！");
        } else {
            player.sendMessage(ChatColor.RED + "加载世界失败！世界可能不存在。");
        }
    }
    
    @CmdMapping(format = "unload <name>")
    public void unloadWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.unload")) {
            player.sendMessage(ChatColor.RED + "你没有权限卸载世界！");
            return;
        }
        
        if (name.equals(worldService.getConfig().getDefaultWorld())) {
            player.sendMessage(ChatColor.RED + "不能卸载主世界！");
            return;
        }
        
        if (worldService.unloadWorld(name, true)) {
            player.sendMessage(ChatColor.GREEN + "世界 " + name + " 已卸载！");
        } else {
            player.sendMessage(ChatColor.RED + "卸载世界失败！");
        }
    }
    
    @CmdMapping(format = "delete <name>")
    public void deleteWorld(@CmdSender Player player, @CmdParam("name") String name) {
        if (!player.hasPermission("ultiworlds.admin.delete")) {
            player.sendMessage(ChatColor.RED + "你没有权限删除世界！");
            return;
        }
        
        if (name.equals(worldService.getConfig().getDefaultWorld())) {
            player.sendMessage(ChatColor.RED + "不能删除主世界！");
            return;
        }
        
        player.sendMessage(ChatColor.YELLOW + "正在删除世界 " + name + "...");
        
        if (worldService.deleteWorld(name)) {
            player.sendMessage(worldService.getConfig().getWorldDeletedMessage()
                .replace("{WORLD}", name)
                .replace("&", "§"));
        } else {
            player.sendMessage(ChatColor.RED + "删除世界失败！");
        }
    }
    
    @CmdMapping(format = "setspawn")
    public void setWorldSpawn(@CmdSender Player player) {
        if (!player.hasPermission("ultiworlds.admin.setspawn")) {
            player.sendMessage(ChatColor.RED + "你没有权限设置世界出生点！");
            return;
        }
        
        worldService.setWorldSpawn(player.getWorld().getName(), player.getLocation());
        player.sendMessage(ChatColor.GREEN + "已设置世界 " + player.getWorld().getName() + " 的出生点！");
    }
    
    @CmdMapping(format = "info")
    public void worldInfo(@CmdSender Player player) {
        World world = player.getWorld();
        WorldSettings settings = worldService.getOrCreateSettings(world.getName());
        
        player.sendMessage(ChatColor.GOLD + "=== 世界信息 ===");
        player.sendMessage(ChatColor.YELLOW + "名称: " + ChatColor.WHITE + world.getName());
        player.sendMessage(ChatColor.YELLOW + "显示名: " + ChatColor.WHITE + 
            (settings.getDisplayName() != null ? settings.getDisplayName() : world.getName()));
        player.sendMessage(ChatColor.YELLOW + "环境: " + ChatColor.WHITE + world.getEnvironment());
        player.sendMessage(ChatColor.YELLOW + "种子: " + ChatColor.WHITE + world.getSeed());
        player.sendMessage(ChatColor.YELLOW + "玩家数: " + ChatColor.WHITE + world.getPlayers().size());
        player.sendMessage(ChatColor.YELLOW + "PVP: " + (settings.isPvpEnabled() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
        player.sendMessage(ChatColor.YELLOW + "怪物生成: " + (settings.isMonstersEnabled() ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭"));
    }
    
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 世界管理帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/world" + ChatColor.WHITE + " - 打开世界列表");
        player.sendMessage(ChatColor.YELLOW + "/world list" + ChatColor.WHITE + " - 列出所有世界");
        player.sendMessage(ChatColor.YELLOW + "/world tp <世界>" + ChatColor.WHITE + " - 传送到世界");
        player.sendMessage(ChatColor.YELLOW + "/world info" + ChatColor.WHITE + " - 当前世界信息");
        if (player.hasPermission("ultiworlds.admin")) {
            player.sendMessage(ChatColor.YELLOW + "/world create <名称> [类型]" + ChatColor.WHITE + " - 创建世界");
            player.sendMessage(ChatColor.YELLOW + "/world load <名称>" + ChatColor.WHITE + " - 加载世界");
            player.sendMessage(ChatColor.YELLOW + "/world unload <名称>" + ChatColor.WHITE + " - 卸载世界");
            player.sendMessage(ChatColor.YELLOW + "/world delete <名称>" + ChatColor.WHITE + " - 删除世界");
            player.sendMessage(ChatColor.YELLOW + "/world setspawn" + ChatColor.WHITE + " - 设置出生点");
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
}
