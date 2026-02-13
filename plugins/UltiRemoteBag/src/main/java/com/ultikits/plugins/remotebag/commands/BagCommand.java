package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.gui.RemoteBagContentGUI;
import com.ultikits.plugins.remotebag.gui.RemoteBagMainGUI;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.plugins.remotebag.util.SoundUtil;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * 远程背包命令执行器
 * <p>
 * 支持普通玩家和管理员命令：
 * <ul>
 *   <li>普通命令：打开背包、保存背包</li>
 *   <li>管理命令：查看/创建/删除/清空其他玩家背包</li>
 * </ul>
 *
 * @author wisdomme
 * @version 2.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"bag", "remotebag", "rb", "yunbag"},
    permission = "ultibag.use",
    description = "远程背包系统"
)
public class BagCommand extends BaseCommandExecutor {

    private final UltiToolsPlugin plugin;
    private final RemoteBagService bagService;
    private final BagLockService lockService;
    private final RemoteBagConfig config;

    public BagCommand(UltiToolsPlugin plugin, RemoteBagService bagService, BagLockService lockService, RemoteBagConfig config) {
        this.plugin = plugin;
        this.bagService = bagService;
        this.lockService = lockService;
        this.config = config;
    }
    
    // ==================== 玩家命令 ====================
    
    /**
     * 打开背包主页
     */
    @CmdMapping(format = "")
    public void openMainPage(@CmdSender Player player) {
        new RemoteBagMainGUI(player, plugin, bagService, lockService, config).open();
    }
    
    /**
     * 打开指定页码的背包
     */
    @CmdMapping(format = "<page>")
    public void openPage(@CmdSender Player player, @CmdParam("page") int page) {
        int maxPages = bagService.getPlayerMaxPages(player);
        
        if (page < 1 || page > maxPages) {
            player.sendMessage(ChatColor.RED + i18n("page_out_of_range")
                    .replace("{0}", String.valueOf(page))
                    .replace("{1}", String.valueOf(maxPages)));
            return;
        }
        
        // 检查背包是否存在
        bagService.loadBagIfNeeded(player.getUniqueId());
        List<Integer> existingPages = bagService.getPlayerBagPages(player.getUniqueId());
        
        if (!existingPages.contains(page)) {
            player.sendMessage(ChatColor.RED + i18n("bag_not_exist").replace("{0}", String.valueOf(page)));
            return;
        }
        
        // 尝试打开
        BagOpenResult result = lockService.ownerOpen(player.getUniqueId(), page, player);
        if (result.isSuccess()) {
            new RemoteBagContentGUI(player, plugin, player.getUniqueId(), page,
                    bagService, lockService, config, result.getAccessMode()).open();
        } else {
            SoundUtil.playErrorSound(player, config);
            player.sendMessage(result.getMessage());
        }
    }
    
    /**
     * 手动保存背包
     */
    @CmdMapping(format = "save")
    public void saveBag(@CmdSender Player player) {
        bagService.saveBag(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + i18n("bag_saved_manually"));
    }
    
    // ==================== 管理员命令 ====================
    
    /**
     * 查看其他玩家的背包（管理员）
     */
    @CmdMapping(format = "see <player>", permission = "ultibag.admin.see")
    public void seePlayerBag(@CmdSender Player admin, @CmdParam("player") String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        // 加载目标玩家背包
        bagService.loadBagIfNeeded(target.getUniqueId());
        List<Integer> pages = bagService.getPlayerBagPages(target.getUniqueId());
        
        if (pages.isEmpty()) {
            admin.sendMessage(ChatColor.YELLOW + i18n("player_no_bags").replace("{0}", playerName));
            return;
        }
        
        // 打开第一页
        openAdminBagPage(admin, target.getUniqueId(), pages.get(0), playerName);
    }
    
    /**
     * 查看其他玩家的指定页背包（管理员）
     */
    @CmdMapping(format = "see <player> <page>", permission = "ultibag.admin.see")
    public void seePlayerBagPage(@CmdSender Player admin, 
                                  @CmdParam("player") String playerName,
                                  @CmdParam("page") int page) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        openAdminBagPage(admin, target.getUniqueId(), page, playerName);
    }
    
    /**
     * 管理员打开背包页
     */
    private void openAdminBagPage(Player admin, UUID ownerUuid, int page, String ownerName) {
        bagService.loadBagIfNeeded(ownerUuid);
        List<Integer> pages = bagService.getPlayerBagPages(ownerUuid);
        
        if (!pages.contains(page)) {
            admin.sendMessage(ChatColor.RED + i18n("bag_not_exist").replace("{0}", String.valueOf(page)));
            return;
        }
        
        // 尝试以管理员身份打开
        BagOpenResult result = lockService.adminOpen(ownerUuid, page, admin);
        
        if (result.isSuccess()) {
            AccessMode mode = result.getAccessMode();
            if (mode == AccessMode.READ_ONLY) {
                admin.sendMessage(result.getMessage());
            }
            new RemoteBagContentGUI(admin, plugin, ownerUuid, page,
                    bagService, lockService, config, mode).open();
        } else {
            SoundUtil.playErrorSound(admin, config);
            admin.sendMessage(result.getMessage());
        }
    }
    
    /**
     * 为玩家创建新背包页（管理员）
     */
    @CmdMapping(format = "create <player>", permission = "ultibag.admin.create")
    public void createBag(@CmdSender Player admin, @CmdParam("player") String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        int newPage = bagService.createBagPage(target.getUniqueId());
        if (newPage > 0) {
            admin.sendMessage(ChatColor.GREEN + i18n("admin_bag_created")
                    .replace("{0}", playerName)
                    .replace("{1}", String.valueOf(newPage)));
        } else {
            admin.sendMessage(ChatColor.RED + i18n("admin_bag_create_failed").replace("{0}", playerName));
        }
    }
    
    /**
     * 删除玩家的背包页（管理员）
     */
    @CmdMapping(format = "delete <player> <page>", permission = "ultibag.admin.delete")
    public void deleteBag(@CmdSender Player admin,
                          @CmdParam("player") String playerName,
                          @CmdParam("page") int page) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        // 检查背包是否被锁定
        if (!lockService.canUpgradeToEdit(target.getUniqueId(), page)) {
            admin.sendMessage(ChatColor.RED + i18n("bag_in_use_cannot_delete"));
            return;
        }
        
        if (bagService.deleteBagPage(target.getUniqueId(), page)) {
            admin.sendMessage(ChatColor.GREEN + i18n("admin_bag_deleted")
                    .replace("{0}", playerName)
                    .replace("{1}", String.valueOf(page)));
        } else {
            admin.sendMessage(ChatColor.RED + i18n("admin_bag_delete_failed")
                    .replace("{0}", playerName)
                    .replace("{1}", String.valueOf(page)));
        }
    }
    
    /**
     * 清空玩家的背包页内容（管理员）
     */
    @CmdMapping(format = "clear <player> <page>", permission = "ultibag.admin.clear")
    public void clearBag(@CmdSender Player admin,
                         @CmdParam("player") String playerName,
                         @CmdParam("page") int page) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        // 检查背包是否被锁定
        if (!lockService.canUpgradeToEdit(target.getUniqueId(), page)) {
            admin.sendMessage(ChatColor.RED + i18n("bag_in_use_cannot_clear"));
            return;
        }
        
        if (bagService.clearBagPage(target.getUniqueId(), page)) {
            admin.sendMessage(ChatColor.GREEN + i18n("admin_bag_cleared")
                    .replace("{0}", playerName)
                    .replace("{1}", String.valueOf(page)));
        } else {
            admin.sendMessage(ChatColor.RED + i18n("admin_bag_clear_failed")
                    .replace("{0}", playerName)
                    .replace("{1}", String.valueOf(page)));
        }
    }
    
    /**
     * 列出玩家的所有背包（管理员）
     */
    @CmdMapping(format = "list <player>", permission = "ultibag.admin.list")
    public void listBags(@CmdSender Player admin, @CmdParam("player") String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null || !target.hasPlayedBefore()) {
            admin.sendMessage(ChatColor.RED + i18n("player_not_found").replace("{0}", playerName));
            return;
        }
        
        bagService.loadBagIfNeeded(target.getUniqueId());
        List<Integer> pages = bagService.getPlayerBagPages(target.getUniqueId());
        
        admin.sendMessage(ChatColor.GOLD + "=== " + playerName + " " + i18n("bag_list_title") + " ===");
        
        if (pages.isEmpty()) {
            admin.sendMessage(ChatColor.GRAY + i18n("no_bags"));
        } else {
            for (int pageNum : pages) {
                int itemCount = bagService.getItemCount(target.getUniqueId(), pageNum);
                int stackCount = bagService.getStackCount(target.getUniqueId(), pageNum);
                admin.sendMessage(ChatColor.YELLOW + "  #" + pageNum + ChatColor.WHITE + " - " +
                        i18n("items_stacks")
                                .replace("{0}", String.valueOf(itemCount))
                                .replace("{1}", String.valueOf(stackCount)));
            }
        }
        admin.sendMessage(ChatColor.GOLD + i18n("total_bags").replace("{0}", String.valueOf(pages.size())));
    }
    
    // ==================== 帮助命令 ====================
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.sendMessage(ChatColor.GOLD + "=== UltiRemoteBag " + i18n("help_title") + " ===");
            player.sendMessage(ChatColor.YELLOW + "/bag" + ChatColor.WHITE + " - " + i18n("help_open"));
            player.sendMessage(ChatColor.YELLOW + "/bag <" + i18n("page") + ">" + ChatColor.WHITE + " - " + i18n("help_open_page"));
            player.sendMessage(ChatColor.YELLOW + "/bag save" + ChatColor.WHITE + " - " + i18n("help_save"));
            
            if (player.hasPermission("ultibag.admin.see")) {
                player.sendMessage("");
                player.sendMessage(ChatColor.RED + "=== " + i18n("admin_commands") + " ===");
                player.sendMessage(ChatColor.YELLOW + "/bag see <" + i18n("player") + "> [" + i18n("page") + "]" + 
                        ChatColor.WHITE + " - " + i18n("help_see"));
                player.sendMessage(ChatColor.YELLOW + "/bag create <" + i18n("player") + ">" + 
                        ChatColor.WHITE + " - " + i18n("help_create"));
                player.sendMessage(ChatColor.YELLOW + "/bag delete <" + i18n("player") + "> <" + i18n("page") + ">" + 
                        ChatColor.WHITE + " - " + i18n("help_delete"));
                player.sendMessage(ChatColor.YELLOW + "/bag clear <" + i18n("player") + "> <" + i18n("page") + ">" + 
                        ChatColor.WHITE + " - " + i18n("help_clear"));
                player.sendMessage(ChatColor.YELLOW + "/bag list <" + i18n("player") + ">" + 
                        ChatColor.WHITE + " - " + i18n("help_list"));
            }
        }
    }
    
    /**
     * i18n 快捷方法
     */
    private String i18n(String key) {
        return plugin.i18n(key);
    }
}
