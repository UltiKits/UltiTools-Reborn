package com.ultikits.plugins.essentials.commands;

import com.ultikits.plugins.essentials.entity.BanData;
import com.ultikits.plugins.essentials.service.BanService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.command.CommandSender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Command for listing bans.
 * <p>
 * Usage: /banlist [page]
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"banlist", "bans"},
    permission = "ultiessentials.banlist",
    description = "查看封禁列表"
)
public class BanListCommand extends BaseEssentialsCommand {
    
    private static final int PAGE_SIZE = 10;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    
    @Autowired
    private BanService banService;
    
    @CmdMapping(format = "")
    public void banlist(@CmdSender CommandSender sender) {
        showBanList(sender, 1);
    }
    
    @CmdMapping(format = "<page>")
    public void banlistPage(@CmdSender CommandSender sender, @CmdParam("page") int page) {
        showBanList(sender, Math.max(1, page));
    }
    
    private void showBanList(CommandSender sender, int page) {
        List<BanData> allBans = banService.getActiveBans();
        
        if (allBans.isEmpty()) {
            sender.sendMessage(i18n("§a没有活跃的封禁"));
            return;
        }
        
        int totalPages = (int) Math.ceil((double) allBans.size() / PAGE_SIZE);
        int currentPage = Math.min(page, totalPages);

        int start = (currentPage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allBans.size());
        
        sender.sendMessage(i18n("§6=== 封禁列表 ===") +
            " §7(" + currentPage + "/" + totalPages + ")");
        
        for (int i = start; i < end; i++) {
            BanData ban = allBans.get(i);
            StringBuilder info = new StringBuilder();
            
            info.append("§e").append(ban.getPlayerName());
            
            if (ban.isPermanent()) {
                info.append(" §c[永久]");
            } else {
                info.append(" §7[剩余: §f");
                info.append(BanService.formatDuration(ban.getRemainingTime()));
                info.append("§7]");
            }
            
            sender.sendMessage(info.toString());
            sender.sendMessage("  §7原因: §f" + ban.getReason());
            sender.sendMessage("  §7操作者: §f" + ban.getBannedByName() + 
                " §7于 " + DATE_FORMAT.format(new Date(ban.getBanTime())));
        }
        
        sender.sendMessage(i18n("§7共 ") + allBans.size() + 
            i18n(" 个活跃封禁"));
        
        if (totalPages > 1) {
            sender.sendMessage(i18n("§7使用 /banlist <页码> 查看更多"));
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(i18n("用法: /banlist [页码]"));
        sender.sendMessage(i18n("查看当前活跃的封禁列表"));
    }
}
