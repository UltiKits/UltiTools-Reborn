package com.ultikits.ultitools.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.examples.LogTransmissionExample;
import com.ultikits.ultitools.manager.LogStreamManager;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;

/**
 * 日志传输测试命令
 * 用于测试和验证UltiPanel日志传输功能
 * 
 * @author UltiKits
 * @version 1.0.0
 */
@CmdExecutor(alias = "logtest", permission = "ultitools.admin", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class LogTransmissionCommands extends AbstractCommandExecutor {

    private LogTransmissionExample example;

    public LogTransmissionCommands() {
        this.example = new LogTransmissionExample();
        this.example.initialize();
    }

    /**
     * 显示帮助信息
     */
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== UltiPanel 日志传输测试命令 ===");
        sender.sendMessage(ChatColor.YELLOW + "/logtest status" + ChatColor.WHITE + " - 查看日志传输状态");
        sender.sendMessage(ChatColor.YELLOW + "/logtest send <level> <message>" + ChatColor.WHITE + " - 发送测试日志");
        sender.sendMessage(ChatColor.YELLOW + "/logtest batch" + ChatColor.WHITE + " - 批量发送测试");
        sender.sendMessage(ChatColor.YELLOW + "/logtest sources" + ChatColor.WHITE + " - 测试不同日志源");
        sender.sendMessage(ChatColor.YELLOW + "/logtest config" + ChatColor.WHITE + " - 测试配置变更");
        sender.sendMessage(ChatColor.YELLOW + "/logtest toggle" + ChatColor.WHITE + " - 切换日志传输状态");
        sender.sendMessage(ChatColor.YELLOW + "/logtest flush" + ChatColor.WHITE + " - 立即发送队列中的日志");
    }

    /**
     * 查看日志传输状态
     */
    @CmdMapping(format = "status")
    public void showStatus(@CmdSender CommandSender sender) {
        String status = example.getLogTransmissionStatus();
        sender.sendMessage(ChatColor.GREEN + status);
    }

    /**
     * 发送测试日志
     */
    @CmdMapping(format = "send <level> <message>")
    public void sendTestLog(@CmdSender CommandSender sender, 
                           @CmdParam("level") String level, 
                           @CmdParam("message") String message) {
        
        LogStreamManager logManager = UltiTools.getInstance().getLogStreamManager();
        if (logManager == null || logManager.getLogTransmitter() == null) {
            sender.sendMessage(ChatColor.RED + "日志传输系统未初始化！");
            return;
        }

        UltiPanelLogTransmitter logTransmitter = logManager.getLogTransmitter();
        String source = "command:test";

        try {
            switch (level.toLowerCase()) {
                case "info":
                    logTransmitter.info(message, source);
                    break;
                case "warning":
                case "warn":
                    logTransmitter.warning(message, source);
                    break;
                case "error":
                    logTransmitter.error(message, source, new RuntimeException("测试异常"));
                    break;
                case "debug":
                    logTransmitter.debug(message, source);
                    break;
                default:
                    sender.sendMessage(ChatColor.RED + "无效的日志级别！支持: info, warning, error, debug");
                    return;
            }
            
            sender.sendMessage(ChatColor.GREEN + String.format("已发送 %s 级别的测试日志: %s", level, message));
            
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "发送日志失败: " + e.getMessage());
        }
    }

    /**
     * 批量发送测试
     */
    @CmdMapping(format = "batch")
    public void batchTest(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "开始批量日志发送测试...");
        
        // 在异步线程中执行，避免阻塞主线程
        UltiTools.getInstance().getServer().getScheduler().runTaskAsynchronously(
            UltiTools.getInstance(), 
            () -> {
                example.demonstrateBatchLogging();
                sender.sendMessage(ChatColor.GREEN + "批量日志发送测试完成！");
            }
        );
    }

    /**
     * 测试不同日志源
     */
    @CmdMapping(format = "sources")
    public void sourcesTest(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "开始不同日志源测试...");
        example.demonstrateLogSources();
        sender.sendMessage(ChatColor.GREEN + "日志源测试完成！");
    }

    /**
     * 测试配置变更
     */
    @CmdMapping(format = "config")
    public void configTest(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "开始配置变更测试...");
        
        UltiTools.getInstance().getServer().getScheduler().runTaskAsynchronously(
            UltiTools.getInstance(), 
            () -> {
                example.demonstrateConfigurationChange();
                sender.sendMessage(ChatColor.GREEN + "配置变更测试完成！");
            }
        );
    }

    /**
     * 切换日志传输状态
     */
    @CmdMapping(format = "toggle")
    public void toggleTransmission(@CmdSender CommandSender sender) {
        LogStreamManager logManager = UltiTools.getInstance().getLogStreamManager();
        if (logManager == null || logManager.getLogTransmitter() == null) {
            sender.sendMessage(ChatColor.RED + "日志传输系统未初始化！");
            return;
        }

        UltiPanelLogTransmitter logTransmitter = logManager.getLogTransmitter();
        boolean currentState = logTransmitter.isLogTransmissionEnabled();
        logTransmitter.setLogTransmissionEnabled(!currentState);
        
        String newState = logTransmitter.isLogTransmissionEnabled() ? "启用" : "禁用";
        sender.sendMessage(ChatColor.GREEN + "日志传输状态已切换为: " + ChatColor.BOLD + newState);
    }

    /**
     * 立即发送队列中的日志
     */
    @CmdMapping(format = "flush")
    public void flushLogs(@CmdSender CommandSender sender) {
        LogStreamManager logManager = UltiTools.getInstance().getLogStreamManager();
        if (logManager == null || logManager.getLogTransmitter() == null) {
            sender.sendMessage(ChatColor.RED + "日志传输系统未初始化！");
            return;
        }

        UltiPanelLogTransmitter logTransmitter = logManager.getLogTransmitter();
        int queueSize = logTransmitter.getQueueSize();
        
        if (queueSize == 0) {
            sender.sendMessage(ChatColor.YELLOW + "队列中没有待发送的日志");
            return;
        }
        
        logTransmitter.flushLogs();
        sender.sendMessage(ChatColor.GREEN + String.format("已立即发送队列中的 %d 条日志", queueSize));
    }

    /**
     * 发送玩家相关测试日志
     */
    @CmdMapping(format = "player <action>")
    public void playerTest(@CmdSender CommandSender sender, @CmdParam("action") String action) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "此命令只能由玩家执行！");
            return;
        }
        
        Player player = (Player) sender;
        LogStreamManager logManager = UltiTools.getInstance().getLogStreamManager();
        
        if (logManager == null) {
            sender.sendMessage(ChatColor.RED + "日志传输系统未初始化！");
            return;
        }
        
        switch (action.toLowerCase()) {
            case "join":
                logManager.sendPlayerEventLog("TEST_JOIN", player.getName(), "模拟玩家加入事件");
                break;
            case "quit":
                logManager.sendPlayerEventLog("TEST_QUIT", player.getName(), "模拟玩家离开事件");
                break;
            case "command":
                logManager.sendPlayerEventLog("TEST_COMMAND", player.getName(), "模拟玩家命令执行事件");
                break;
            default:
                sender.sendMessage(ChatColor.RED + "无效的动作！支持: join, quit, command");
                return;
        }
        
        sender.sendMessage(ChatColor.GREEN + String.format("已发送玩家 %s 事件日志", action));
    }

    /**
     * 性能测试
     */
    @CmdMapping(format = "performance <count>")
    public void performanceTest(@CmdSender CommandSender sender, @CmdParam("count") int count) {
        if (count <= 0 || count > 1000) {
            sender.sendMessage(ChatColor.RED + "数量必须在1-1000之间！");
            return;
        }
        
        LogStreamManager logManager = UltiTools.getInstance().getLogStreamManager();
        if (logManager == null || logManager.getLogTransmitter() == null) {
            sender.sendMessage(ChatColor.RED + "日志传输系统未初始化！");
            return;
        }
        
        sender.sendMessage(ChatColor.YELLOW + String.format("开始性能测试，将发送 %d 条日志...", count));
        
        UltiTools.getInstance().getServer().getScheduler().runTaskAsynchronously(
            UltiTools.getInstance(), 
            () -> {
                UltiPanelLogTransmitter logTransmitter = logManager.getLogTransmitter();
                long startTime = System.currentTimeMillis();
                
                for (int i = 1; i <= count; i++) {
                    String message = String.format("性能测试日志 #%d - 时间戳: %d", i, System.currentTimeMillis());
                    logTransmitter.info(message, "test:performance");
                    
                    // 每100条稍微暂停
                    if (i % 100 == 0) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                
                sender.sendMessage(ChatColor.GREEN + String.format(
                    "性能测试完成！发送 %d 条日志，耗时 %d 毫秒，平均 %.2f 条/秒", 
                    count, duration, count * 1000.0 / duration));
            }
        );
    }
}
