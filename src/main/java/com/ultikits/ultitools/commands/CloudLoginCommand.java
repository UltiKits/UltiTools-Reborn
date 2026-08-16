package com.ultikits.ultitools.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.utils.ApiRateLimiter;
import com.ultikits.ultitools.utils.CloudAuthManager;
import com.ultikits.ultitools.utils.PluginInitiationUtils;

/**
 * Commands for UltiCloud authentication via magic link.
 * <br>
 * 通过魔法链接进行UltiCloud身份验证的命令。
 *
 * Usage:
 *   /ulticloud login  — Generate a magic link to authenticate
 *   /ulticloud logout — Clear saved credentials
 *   /ulticloud status — Show current auth status
 */
@CmdExecutor(description = "UltiCloud Authentication Commands", alias = "ulticloud", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE)
public class CloudLoginCommand extends AbstractCommandExecutor {

    @CmdMapping(format = "login")
    @RunAsync
    public void login(@CmdSender CommandSender sender) {
        if (CloudAuthManager.hasValidToken()) {
            sender.sendMessage(ChatColor.YELLOW + "Already logged in to UltiCloud. Use /ulticloud logout first to re-login.");
            return;
        }

        if (!ApiRateLimiter.isLoginAllowed()) {
            long remaining = ApiRateLimiter.getRemainingCooldown("login", 60_000);
            sender.sendMessage(ChatColor.RED + "Please wait " + remaining + " seconds before trying again.");
            return;
        }

        sender.sendMessage(ChatColor.AQUA + "Requesting login link from UltiCloud...");

        String url = CloudAuthManager.requestMagicLink(error -> {
            sender.sendMessage(ChatColor.RED + "Failed to get login link: " + error);
        });

        if (url != null) {
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GREEN + " Open this URL in your browser to login:");
            sender.sendMessage(ChatColor.AQUA + " " + url);
            sender.sendMessage(ChatColor.GREEN + "========================================");
            sender.sendMessage(ChatColor.GRAY + "The link will expire in 5 minutes.");
            sender.sendMessage(ChatColor.GRAY + "Waiting for authentication...");
        }
    }

    @CmdMapping(format = "logout")
    public void logout(@CmdSender CommandSender sender) {
        // 这里刻意**不**用 hasValidToken() 做门禁。
        //
        // 它要求 token 未过期，而「access token 过期了但一切还在跑」恰恰是最需要 logout
        // 生效的场景：主动刷新反复失败之后，socket、监控任务、root logger 上的日志 handler、
        // 玩家事件监听器与刷新调度可能全都还活着，而 logout 是操作员唯一的停止手段。
        // 旧实现在这一刻回一句「Not currently logged in」就退出，把人堵死在只能重启。
        //
        // 凭证的有效性不能作为生命周期拆解的门禁。改为看「有没有东西可清」：
        // 拆线无条件执行（disableCloud 的每一步对「本来就没起来」都幂等），
        // 清凭证只在确实存着凭证时做。
        try {
            // 先拆状态机，再清凭证。
            //
            // 顺序有讲究：清凭证只是让重连拿不到有效 token，并不会让它停下来——重连链会继续
            // 用已作废的凭证敲面板，401 循环照跑，实测只有重新 login 或重启服务器才停得下来。
            // 「Cloud features are now disabled」这句话此前是不成立的。见 issue #223。
            PluginInitiationUtils.disableCloud();

            // 凭证必须在拆线**之后**读。拆线之前读的话拿到的是一份可能过时的快照：
            // 一次在途的 magic-link 轮询完全可以在这中间提交成功，于是「拆线前没有凭证」
            // 的判断把我们送进不清凭证的分支，而 data.json 里已经躺着一份可用的 token，
            // 重启即自动重连。disableCloud() 第一件事就是作废在途操作，因此拆线返回之后
            // 读到的就是最终状态。
            TokenEntity existing = CloudAuthManager.getCurrentToken();

            if (existing == null) {
                sender.sendMessage(ChatColor.YELLOW + "Not currently logged in to UltiCloud.");
                sender.sendMessage(ChatColor.GRAY + "Cloud features have been stopped regardless.");
                return;
            }

            CloudAuthManager.clearToken();
            sender.sendMessage(ChatColor.GREEN + "Successfully logged out of UltiCloud. Cloud features are now disabled.");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to re-authenticate.");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to logout: " + e.getMessage());
        }
    }

    @CmdMapping(format = "status")
    public void status(@CmdSender CommandSender sender) {
        if (CloudAuthManager.hasValidToken()) {
            TokenEntity token = CloudAuthManager.getCurrentToken();
            String username = token.getUser_name() != null ? token.getUser_name() : "Unknown";
            sender.sendMessage(ChatColor.GREEN + "UltiCloud: Connected as " + username);
            if (token.getExpirationDate() != null) {
                sender.sendMessage(ChatColor.GRAY + "Token expires: " + token.getExpirationDate().toString());
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "UltiCloud: Not connected");
            sender.sendMessage(ChatColor.GRAY + "Use /ulticloud login to authenticate.");
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "=== UltiCloud Commands ===");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud login" + ChatColor.GRAY + " - Authenticate with UltiCloud");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud logout" + ChatColor.GRAY + " - Clear saved credentials");
        sender.sendMessage(ChatColor.WHITE + "/ulticloud status" + ChatColor.GRAY + " - Show connection status");
    }
}
