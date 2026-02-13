package com.ultikits.plugins.cleaner.commands;

import com.ultikits.plugins.cleaner.service.ChunkUnloadService;
import com.ultikits.plugins.cleaner.service.CleanerService;
import com.ultikits.plugins.cleaner.service.TpsAwareScheduler;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Command for manual cleanup operations.
 * Supports item cleanup, entity cleanup, chunk unloading,
 * status checking, and server statistics.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@CmdExecutor(
    alias = {"clean", "cleaner", "clear"},
    permission = "ulticleaner.clean",
    description = "清理地面物品和实体"
)
public class CleanCommand extends BaseCommandExecutor {
    
    private final CleanerService cleanerService;
    private final ChunkUnloadService chunkUnloadService;
    
    public CleanCommand(CleanerService cleanerService, ChunkUnloadService chunkUnloadService) {
        this.cleanerService = cleanerService;
        this.chunkUnloadService = chunkUnloadService;
    }
    
    @CmdMapping(format = "items")
    public void cleanItems(@CmdSender CommandSender sender) {
        if (cleanerService.isCleaningInProgress()) {
            sender.sendMessage(ChatColor.YELLOW + "清理正在进行中，请稍候...");
            return;
        }
        int count = cleanerService.forceCleanItems();
        sender.sendMessage(ChatColor.GREEN + "已开始清理 " + count + " 个地面物品（分批处理中）...");
    }
    
    @CmdMapping(format = "entities")
    public void cleanEntities(@CmdSender CommandSender sender) {
        if (cleanerService.isCleaningInProgress()) {
            sender.sendMessage(ChatColor.YELLOW + "清理正在进行中，请稍候...");
            return;
        }
        int count = cleanerService.forceCleanEntities();
        sender.sendMessage(ChatColor.GREEN + "已开始清理 " + count + " 个实体（分批处理中）...");
    }
    
    @CmdMapping(format = "all")
    public void cleanAll(@CmdSender CommandSender sender) {
        if (cleanerService.isCleaningInProgress()) {
            sender.sendMessage(ChatColor.YELLOW + "清理正在进行中，请稍候...");
            return;
        }
        int itemCount = cleanerService.forceCleanItems();
        int entityCount = cleanerService.forceCleanEntities();
        sender.sendMessage(ChatColor.GREEN + "已开始清理 " + itemCount + " 个物品和 " + entityCount + " 个实体（分批处理中）...");
    }
    
    @CmdMapping(format = "chunks")
    public void cleanChunks(@CmdSender CommandSender sender) {
        if (chunkUnloadService == null) {
            sender.sendMessage(ChatColor.RED + "区块卸载服务未启用！");
            return;
        }
        int count = chunkUnloadService.forceUnloadChunks();
        sender.sendMessage(ChatColor.GREEN + "已卸载 " + count + " 个闲置区块！");
    }
    
    @CmdMapping(format = "check")
    public void check(@CmdSender CommandSender sender) {
        Map<String, Integer> counts = cleanerService.getEntityCounts();
        
        sender.sendMessage(ChatColor.GOLD + "=== 服务器实体统计 ===");
        sender.sendMessage(ChatColor.YELLOW + "地面物品: " + ChatColor.WHITE + counts.get("items"));
        sender.sendMessage(ChatColor.YELLOW + "可清理生物: " + ChatColor.WHITE + counts.get("mobs"));
        sender.sendMessage(ChatColor.YELLOW + "实体总数: " + ChatColor.WHITE + counts.get("total"));
        
        if (chunkUnloadService != null) {
            sender.sendMessage(ChatColor.YELLOW + "已加载区块: " + ChatColor.WHITE + chunkUnloadService.getTotalLoadedChunks());
            sender.sendMessage(ChatColor.YELLOW + "可卸载区块: " + ChatColor.WHITE + chunkUnloadService.getUnloadableChunkCount());
        }
        
        TpsAwareScheduler tpsScheduler = cleanerService.getTpsScheduler();
        if (tpsScheduler != null) {
            sender.sendMessage(ChatColor.YELLOW + "服务器TPS: " + tpsScheduler.getTpsStatus());
        }
    }
    
    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 清理状态 ===");
        sender.sendMessage(ChatColor.YELLOW + "下次物品清理: " + ChatColor.WHITE + cleanerService.getItemCountdown() + " 秒");
        sender.sendMessage(ChatColor.YELLOW + "下次实体清理: " + ChatColor.WHITE + cleanerService.getEntityCountdown() + " 秒");
        
        if (cleanerService.isCleaningInProgress()) {
            sender.sendMessage(ChatColor.YELLOW + "清理状态: " + ChatColor.GREEN + "进行中...");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "清理状态: " + ChatColor.GRAY + "空闲");
        }
        
        TpsAwareScheduler tpsScheduler = cleanerService.getTpsScheduler();
        if (tpsScheduler != null) {
            sender.sendMessage(ChatColor.YELLOW + "TPS: " + tpsScheduler.getTpsStatus());
            if (tpsScheduler.isCriticalTps()) {
                sender.sendMessage(ChatColor.RED + "⚠ TPS严重过低，智能清理阈值已降低50%");
            } else if (tpsScheduler.isLowTps()) {
                sender.sendMessage(ChatColor.YELLOW + "⚠ TPS较低，智能清理阈值已降低30%");
            }
        }
    }
    
    @CmdMapping(format = "")
    public void help(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiCleaner 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/clean items" + ChatColor.WHITE + " - 清理地面物品");
        sender.sendMessage(ChatColor.YELLOW + "/clean entities" + ChatColor.WHITE + " - 清理实体");
        sender.sendMessage(ChatColor.YELLOW + "/clean all" + ChatColor.WHITE + " - 清理所有");
        sender.sendMessage(ChatColor.YELLOW + "/clean chunks" + ChatColor.WHITE + " - 卸载闲置区块");
        sender.sendMessage(ChatColor.YELLOW + "/clean check" + ChatColor.WHITE + " - 查看实体统计");
        sender.sendMessage(ChatColor.YELLOW + "/clean status" + ChatColor.WHITE + " - 查看清理状态");
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        help(sender);
    }
}
