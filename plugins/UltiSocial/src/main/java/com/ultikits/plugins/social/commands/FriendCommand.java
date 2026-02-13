package com.ultikits.plugins.social.commands;

import com.ultikits.plugins.social.entity.BlacklistData;
import com.ultikits.plugins.social.entity.FriendRequest;
import com.ultikits.plugins.social.entity.FriendshipData;
import com.ultikits.plugins.social.gui.BlockListGUI;
import com.ultikits.plugins.social.gui.FriendListGUI;
import com.ultikits.plugins.social.service.FriendService;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;
import com.ultikits.ultitools.services.TeleportService;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Friend command executor.
 * Supports friend management, blacklist, teleport and messaging.
 *
 * @author wisdomme
 * @version 1.1.0
 */
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"friend", "friends", "f"},
    permission = "ultisocial.use",
    description = "好友系统"
)
public class FriendCommand extends BaseCommandExecutor {
    
    private final FriendService friendService;
    private final TeleportService teleportService;
    
    public FriendCommand(FriendService friendService, TeleportService teleportService) {
        this.friendService = friendService;
        this.teleportService = teleportService;
    }
    
    // ==================== Friend Commands ====================
    
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
    
    // ==================== Teleport Commands ====================
    
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
        
        // Use TeleportService for teleportation
        if (teleportService != null) {
            teleportService.teleport(player, target.getLocation());
        } else {
            player.teleport(target.getLocation());
        }
        
        friendService.setTpCooldown(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "已传送到 " + friendName + " 身边！");
    }
    
    // ==================== Message Commands ====================
    
    @CmdMapping(format = "msg <player> <message...>")
    public void sendMessage(@CmdSender Player sender, @CmdParam("player") String friendName, 
                           @CmdParam("message") String[] messageParts) {
        // Check if target is friend
        List<FriendshipData> friends = friendService.getFriends(sender.getUniqueId());
        FriendshipData targetFriend = null;
        for (FriendshipData friend : friends) {
            if (friend.getFriendName().equalsIgnoreCase(friendName)) {
                targetFriend = friend;
                break;
            }
        }
        
        if (targetFriend == null) {
            sender.sendMessage(ChatColor.RED + friendName + " 不是你的好友！只能向好友发送私聊消息");
            return;
        }
        
        Player target = Bukkit.getPlayer(UUID.fromString(targetFriend.getFriendUuid()));
        if (target == null) {
            sender.sendMessage(ChatColor.RED + friendName + " 不在线！");
            return;
        }
        
        String message = String.join(" ", messageParts);
        
        // Send to target
        target.sendMessage(ChatColor.LIGHT_PURPLE + "[私聊] " + ChatColor.WHITE + sender.getName() + 
            ChatColor.GRAY + " → " + ChatColor.WHITE + "你: " + ChatColor.RESET + message);
        
        // Confirm to sender
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "[私聊] " + ChatColor.WHITE + "你" + 
            ChatColor.GRAY + " → " + ChatColor.WHITE + target.getName() + ": " + ChatColor.RESET + message);
    }
    
    // ==================== Blacklist Commands ====================
    
    @CmdMapping(format = "block <player>")
    public void blockPlayer(@CmdSender Player player, @CmdParam("player") String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            // Try offline player
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            if (!offline.hasPlayedBefore()) {
                player.sendMessage(ChatColor.RED + "玩家 " + targetName + " 不存在！");
                return;
            }
            
            if (friendService.addToBlacklist(player.getUniqueId(), offline.getUniqueId(), targetName, null)) {
                player.sendMessage(ChatColor.RED + "已将 " + targetName + " 加入黑名单");
            } else {
                player.sendMessage(ChatColor.RED + targetName + " 已在黑名单中！");
            }
            return;
        }
        
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "不能拉黑自己！");
            return;
        }
        
        if (friendService.addToBlacklist(player, target, null)) {
            player.sendMessage(ChatColor.RED + "已将 " + targetName + " 加入黑名单");
            // Notify if they were friends
            if (friendService.areFriends(player.getUniqueId(), target.getUniqueId())) {
                player.sendMessage(ChatColor.GRAY + "（已自动解除好友关系）");
            }
        } else {
            player.sendMessage(ChatColor.RED + targetName + " 已在黑名单中！");
        }
    }
    
    @CmdMapping(format = "unblock <player>")
    public void unblockPlayer(@CmdSender Player player, @CmdParam("player") String targetName) {
        if (friendService.removeFromBlacklist(player, targetName)) {
            player.sendMessage(ChatColor.GREEN + "已将 " + targetName + " 从黑名单中移除");
        } else {
            player.sendMessage(ChatColor.RED + targetName + " 不在你的黑名单中！");
        }
    }
    
    @CmdMapping(format = "blocklist")
    public void openBlockList(@CmdSender Player player) {
        BlockListGUI gui = new BlockListGUI(friendService, player);
        player.openInventory(gui.getInventory());
    }
    
    // ==================== Help Command ====================
    
    @CmdMapping(format = "help")
    public void help(@CmdSender Player player) {
        player.sendMessage(ChatColor.GOLD + "=== 好友系统帮助 ===");
        player.sendMessage(ChatColor.YELLOW + "/friend" + ChatColor.WHITE + " - 打开好友列表");
        player.sendMessage(ChatColor.YELLOW + "/friend list" + ChatColor.WHITE + " - 列出所有好友");
        player.sendMessage(ChatColor.YELLOW + "/friend add <玩家>" + ChatColor.WHITE + " - 发送好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend accept <玩家>" + ChatColor.WHITE + " - 接受好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend deny <玩家>" + ChatColor.WHITE + " - 拒绝好友请求");
        player.sendMessage(ChatColor.YELLOW + "/friend remove <玩家>" + ChatColor.WHITE + " - 删除好友");
        player.sendMessage(ChatColor.YELLOW + "/friend tp <好友>" + ChatColor.WHITE + " - 传送到好友");
        player.sendMessage(ChatColor.YELLOW + "/friend msg <好友> <消息>" + ChatColor.WHITE + " - 私聊好友");
        player.sendMessage(ChatColor.YELLOW + "/friend requests" + ChatColor.WHITE + " - 查看待处理请求");
        player.sendMessage(ChatColor.GOLD + "=== 黑名单功能 ===");
        player.sendMessage(ChatColor.YELLOW + "/friend block <玩家>" + ChatColor.WHITE + " - 拉黑玩家");
        player.sendMessage(ChatColor.YELLOW + "/friend unblock <玩家>" + ChatColor.WHITE + " - 解除拉黑");
        player.sendMessage(ChatColor.YELLOW + "/friend blocklist" + ChatColor.WHITE + " - 查看黑名单");
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        if (sender instanceof Player) {
            help((Player) sender);
        }
    }
    
    // ==================== Tab Complete ====================
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Let parent handle basic completion first
        List<String> parentSuggestions = super.onTabComplete(sender, command, alias, args);
        if (parentSuggestions != null && !parentSuggestions.isEmpty()) {
            return parentSuggestions;
        }
        
        if (!(sender instanceof Player)) {
            return new ArrayList<>();
        }
        
        Player player = (Player) sender;
        List<String> suggestions = new ArrayList<>();
        
        if (args.length == 1) {
            // First argument - subcommands
            suggestions.add("list");
            suggestions.add("add");
            suggestions.add("accept");
            suggestions.add("deny");
            suggestions.add("remove");
            suggestions.add("tp");
            suggestions.add("msg");
            suggestions.add("requests");
            suggestions.add("block");
            suggestions.add("unblock");
            suggestions.add("blocklist");
            suggestions.add("help");
            
            return filterStartsWith(suggestions, args[0]);
        }
        
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            
            switch (subCmd) {
                case "add":
                case "block":
                    // Online players (excluding self and already friends/blocked)
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.equals(player)) {
                            suggestions.add(online.getName());
                        }
                    }
                    break;
                    
                case "accept":
                case "deny":
                    // Pending request senders
                    for (FriendRequest req : friendService.getPendingRequests(player.getUniqueId())) {
                        suggestions.add(req.getSenderName());
                    }
                    break;
                    
                case "remove":
                case "tp":
                case "msg":
                    // Friends list
                    for (FriendshipData friend : friendService.getFriends(player.getUniqueId())) {
                        suggestions.add(friend.getFriendName());
                    }
                    break;
                    
                case "unblock":
                    // Blocked users
                    for (BlacklistData blocked : friendService.getBlacklist(player.getUniqueId())) {
                        suggestions.add(blocked.getBlockedName());
                    }
                    break;
                default:
                    // No suggestions for unknown subcommands
                    break;
            }

            return filterStartsWith(suggestions, args[1]);
        }
        
        return suggestions;
    }
    
    /**
     * Filter suggestions that start with given prefix.
     */
    private List<String> filterStartsWith(List<String> suggestions, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return suggestions;
        }
        String lowerPrefix = prefix.toLowerCase();
        return suggestions.stream()
            .filter(s -> s.toLowerCase().startsWith(lowerPrefix))
            .collect(Collectors.toList());
    }
}
