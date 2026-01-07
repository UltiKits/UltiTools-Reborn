package com.ultikits.plugins.cleaner.commands;

import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Command for manual cleanup operations.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdExecutor(
    alias = {"clean", "cleaner", "clear"},
    permission = "ulticleaner.clean",
    description = "清理地面物品和实体"
)
public class CleanCommand extends AbstractCommendExecutor {
    
    private final CleanerService cleanerService;
    
    public CleanCommand(CleanerService cleanerService) {
        this.cleanerService = cleanerService;
    }
    
    @CmdMapping(format = "items")
    public void cleanItems(@CmdSender CommandSender sender) {
        int count = cleanerService.forceCleanItems();
        sender.sendMessage(ChatColor.GREEN + "已清理 " + count + " 个地面物品！");
    }
    
    @CmdMapping(format = "entities")
    public void cleanEntities(@CmdSender CommandSender sender) {
        int count = cleanerService.forceCleanEntities();
        sender.sendMessage(ChatColor.GREEN + "已清理 " + count + " 个实体！");
    }
    
    @CmdMapping(format = "all")
    public void cleanAll(@CmdSender CommandSender sender) {
        int itemCount = cleanerService.forceCleanItems();
        int entityCount = cleanerService.forceCleanEntities();
        sender.sendMessage(ChatColor.GREEN + "已清理 " + itemCount + " 个物品和 " + entityCount + " 个实体！");
    }
    
    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 清理状态 ===");
        sender.sendMessage(ChatColor.YELLOW + "下次物品清理: " + ChatColor.WHITE + cleanerService.getItemCountdown() + " 秒");
        sender.sendMessage(ChatColor.YELLOW + "下次实体清理: " + ChatColor.WHITE + cleanerService.getEntityCountdown() + " 秒");
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiCleaner 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/clean items" + ChatColor.WHITE + " - 清理地面物品");
        sender.sendMessage(ChatColor.YELLOW + "/clean entities" + ChatColor.WHITE + " - 清理实体");
        sender.sendMessage(ChatColor.YELLOW + "/clean all" + ChatColor.WHITE + " - 清理所有");
        sender.sendMessage(ChatColor.YELLOW + "/clean status" + ChatColor.WHITE + " - 查看清理状态");
    }
}
