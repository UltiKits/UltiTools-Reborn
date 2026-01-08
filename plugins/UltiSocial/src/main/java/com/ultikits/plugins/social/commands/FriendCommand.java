package com.ultikits.plugins.social.commands;

import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.gui.FriendListGUI;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Friend command executor.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"friend", "friends", "f"},
    permission = "ultisocial.use",
    description = "好友系统"
)
public class FriendCommand extends AbstractCommendExecutor {
    
    private final FriendService friendService;
    
    public FriendCommand(FriendService friendService) {
        this.friendService = friendService;
    }
    
    @CmdMapping(format = "")
    public void openFriendList(@CmdSender Player player) {
        FriendListGUI gui = new FriendListGUI(friendService, player);
        player.openInventory(gui.getInventory());
    }
    
    @CmdMapping(format = "list")
    public void listFriends(@CmdSender Player player) {
        List<FriendshipData> friends = friendService.getFriends(player.getUniqueId());
        
        if (friends.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "你还没有好友，使用 /friend add <玩家> 添加好友");
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== 好友列表 (" + friends.size() + ") ===");
        for (FriendshipData friend : friends) {
            Player online = Bukkit.getPlayer(UUID.fromString(friend.getFriendUuid()));
            String status = online != null ? ChatColor.GREEN + "● 在线" : ChatColor.GRAY + "○ 离线";
            String star = friend.isFavorite() ? ChatColor.YELLOW + "★ " : "";
            player.sendMessage(star + status + " " + ChatColor.WHITE + friend.getFriendName());
        }
    }
    
    @CmdMapping(format = "add <player>")
    public void addFriend(@CmdSender Player sender, @CmdParam("player") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不在线！");
            return;
        }
        
        if (target.equals(sender)) {
            sender.sendMessage(ChatColor.RED + "不能添加自己为好友！");
            return;
        }
        
        friendService.sendRequest(sender, target);
    }
    
    @CmdMapping(format = "accept <player>")
    public void acceptRequest(@CmdSender Player player, @CmdParam("player") String senderName) {
        friendService.acceptRequest(player, senderName);
    }
    
    @CmdMapping(format = "deny <player>")
    public void denyRequest(@CmdSender Player player, @CmdParam("player") String senderName) {
        friendService.denyRequest(player, senderName);
    }
    
    @CmdMapping(format = "remove <player>")
    public void removeFriend(@CmdSender Player player, @CmdParam("player") String friendName) {
        friendService.removeFriend(player, friendName);
    }
    
    @CmdMapping(format = "tp <player>")
    public void teleportToFriend(@CmdSender Player player, @CmdParam("player") String friendName) {
        if (!friendService.getConfig().isTpToFriendEnabled()) {
            player.sendMessage(ChatColor.RED + "传送到好友功能已禁用！");
            return;
        }
        
        List<FriendshipData> friends = friendService.getFriends(player.getUniqueId());
        FriendshipData targetFriend = null;
        for (FriendshipData friend : friends) {
            if (friend.getFriendName().equalsIgnoreCase(friendName)) {
                targetFriend = friend;
                break;
            }
        }
        
        if (targetFriend == null) {
            player.sendMessage(ChatColor.RED + friendName + " 不是你的好友！");
            return;
        }
        
        Player target = Bukkit.getPlayer(UUID.fromString(targetFriend.getFriendUuid()));
        if (target == null) {
            player.sendMessage(ChatColor.RED + friendName + " 不在线！");
            return;
        }
        
        if (!friendService.canTeleport(player.getUniqueId())) {
            int remaining = friendService.getRemainingCooldown(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "传送冷却中！请等待 " + remaining + " 秒");
            return;
        }
        
        player.teleport(target.getLocation());
        friendService.setTpCooldown(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "已传送到 " + friendName + " 身边！");
    }
    
    @CmdMapping(format = "requests")
    public void viewRequests(@CmdSender Player player) {
        List<FriendRequest> requests = friendService.getPendingRequests(player.getUniqueId());
        
        if (requests.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "你没有待处理的好友请求");
            return;
        }
        
        player.sendMessage(ChatColor.GOLD + "=== 好友请求 ===");
        for (FriendRequest request : requests) {
            player.sendMessage(ChatColor.YELLOW + "- " + ChatColor.WHITE + request.getSenderName() + 
                ChatColor.GRAY + " (点击接受: /friend accept " + request.getSenderName() + ")");
        }
    }
    
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 好友系统帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/friend" + ChatColor.WHITE + " - 打开好友列表");
        player.sendMessage(ChatColor.YELLOW + "/friend list" + ChatColor.WHITE + " - 列出所有好友");
        player.sendMessage(ChatColor.YELLOW + "/friend add <玩家>" + ChatColor.WHITE + " - 发送好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend accept <玩家>" + ChatColor.WHITE + " - 接受好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend deny <玩家>" + ChatColor.WHITE + " - 拒绝好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend remove <玩家>" + ChatColor.WHITE + " - 删除好友");
        player.sendMessage(ChatColor.YELLOW + "/friend tp <玩家>" + ChatColor.WHITE + " - 传送到好友");
        player.sendMessage(ChatColor.YELLOW + "/friend requests" + ChatColor.WHITE + " - 查看待处理请求");
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
}
