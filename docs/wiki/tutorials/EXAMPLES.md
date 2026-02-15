# 完整示例

本文档提供多个完整的 UltiTools 模块开发示例。

---

## 目录

- [经济系统示例](#经济系统示例)
- [传送系统示例](#传送系统示例)
- [公告系统示例](#公告系统示例)

---

## 经济系统示例

一个完整的服务器经济系统实现。

### 项目结构

```
economy-plugin/
├── src/main/java/com/example/economy/
│   ├── EconomyPlugin.java
│   ├── commands/
│   │   └── MoneyCommand.java
│   ├── services/
│   │   └── EconomyService.java
│   ├── entities/
│   │   └── Account.java
│   ├── configs/
│   │   └── EconomyConfig.java
│   └── listeners/
│       └── JoinListener.java
└── src/main/resources/
    ├── plugin.yml
    └── lang/zh.json
```

### Account.java

```java
package com.example.economy.entities;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("economy_accounts")
public class Account extends AbstractDataEntity {
    
    @Column("uuid")
    private String uuid;
    
    @Column("name")
    private String name;
    
    @Column(value = "balance", type = "DECIMAL(15,2)")
    private double balance;
    
    @Column(value = "total_earned", type = "DECIMAL(15,2)")
    private double totalEarned;
    
    @Column(value = "total_spent", type = "DECIMAL(15,2)")
    private double totalSpent;
    
    @Column(value = "created_at", type = "BIGINT")
    private long createdAt;
    
    @Column(value = "last_transaction", type = "BIGINT")
    private long lastTransaction;
}
```

### EconomyConfig.java

```java
package com.example.economy.configs;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity(path = "config/economy.yml")
public class EconomyConfig extends AbstractConfigEntity {
    
    @ConfigEntry(path = "currency.name", comment = "货币名称")
    private String currencyName = "金币";
    
    @ConfigEntry(path = "currency.symbol", comment = "货币符号")
    private String currencySymbol = "💰";
    
    @ConfigEntry(path = "starting-balance", comment = "新玩家初始余额")
    private double startingBalance = 100.0;
    
    @ConfigEntry(path = "max-balance", comment = "最大余额限制 (0 = 无限制)")
    private double maxBalance = 0;
    
    @ConfigEntry(path = "transfer.enabled", comment = "是否允许玩家转账")
    private boolean transferEnabled = true;
    
    @ConfigEntry(path = "transfer.min-amount", comment = "最小转账金额")
    private double minTransferAmount = 1.0;
    
    @ConfigEntry(path = "transfer.fee-rate", comment = "转账手续费率 (0.05 = 5%)")
    private double transferFeeRate = 0.0;
    
    @ConfigEntry(path = "daily-reward.enabled", comment = "是否启用每日奖励")
    private boolean dailyRewardEnabled = true;
    
    @ConfigEntry(path = "daily-reward.amount", comment = "每日奖励金额")
    private double dailyRewardAmount = 50.0;
    
    public EconomyConfig() {
        super("config/economy.yml");
    }
}
```

### EconomyService.java

```java
package com.example.economy.services;

import com.example.economy.EconomyPlugin;
import com.example.economy.configs.EconomyConfig;
import com.example.economy.entities.Account;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class EconomyService {
    
    private final DataOperator<Account> accountOperator;
    
    @Autowired
    private EconomyConfig config;
    
    public EconomyService() {
        this.accountOperator = EconomyPlugin.getInstance().getDataOperator(Account.class);
    }
    
    /**
     * 获取玩家账户
     */
    public Account getAccount(Player player) {
        return getAccountByUuid(player.getUniqueId().toString())
            .orElseGet(() -> createAccount(player));
    }
    
    /**
     * 根据 UUID 获取账户
     */
    public Optional<Account> getAccountByUuid(String uuid) {
        List<Account> accounts = accountOperator.getAll(
            WhereCondition.builder().column("uuid").value(uuid).build()
        );
        return accounts.isEmpty() ? Optional.empty() : Optional.of(accounts.get(0));
    }
    
    /**
     * 创建新账户
     */
    private Account createAccount(Player player) {
        Account account = Account.builder()
            .uuid(player.getUniqueId().toString())
            .name(player.getName())
            .balance(config.getStartingBalance())
            .totalEarned(config.getStartingBalance())
            .totalSpent(0)
            .createdAt(System.currentTimeMillis())
            .lastTransaction(System.currentTimeMillis())
            .build();
        
        accountOperator.insert(account);
        return account;
    }
    
    /**
     * 获取余额
     */
    public double getBalance(Player player) {
        return getAccount(player).getBalance();
    }
    
    /**
     * 存款
     */
    public boolean deposit(Player player, double amount) {
        if (amount <= 0) return false;
        
        Account account = getAccount(player);
        double newBalance = account.getBalance() + amount;
        
        // 检查最大余额限制
        if (config.getMaxBalance() > 0 && newBalance > config.getMaxBalance()) {
            return false;
        }
        
        account.setBalance(newBalance);
        account.setTotalEarned(account.getTotalEarned() + amount);
        account.setLastTransaction(System.currentTimeMillis());
        
        try {
            accountOperator.update(account);
            return true;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 取款
     */
    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) return false;
        
        Account account = getAccount(player);
        if (account.getBalance() < amount) {
            return false;
        }
        
        account.setBalance(account.getBalance() - amount);
        account.setTotalSpent(account.getTotalSpent() + amount);
        account.setLastTransaction(System.currentTimeMillis());
        
        try {
            accountOperator.update(account);
            return true;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 转账
     */
    public TransferResult transfer(Player from, Player to, double amount) {
        if (!config.isTransferEnabled()) {
            return TransferResult.DISABLED;
        }
        
        if (amount < config.getMinTransferAmount()) {
            return TransferResult.BELOW_MINIMUM;
        }
        
        double fee = amount * config.getTransferFeeRate();
        double totalDeduct = amount + fee;
        
        Account fromAccount = getAccount(from);
        if (fromAccount.getBalance() < totalDeduct) {
            return TransferResult.INSUFFICIENT_FUNDS;
        }
        
        Account toAccount = getAccount(to);
        double newToBalance = toAccount.getBalance() + amount;
        
        if (config.getMaxBalance() > 0 && newToBalance > config.getMaxBalance()) {
            return TransferResult.RECEIVER_MAX_BALANCE;
        }
        
        // 执行转账
        if (!withdraw(from, totalDeduct)) {
            return TransferResult.FAILED;
        }
        
        if (!deposit(to, amount)) {
            // 回滚
            deposit(from, totalDeduct);
            return TransferResult.FAILED;
        }
        
        return TransferResult.SUCCESS;
    }
    
    /**
     * 获取财富排行榜
     */
    public List<Account> getTopAccounts(int limit) {
        return accountOperator.getAll().stream()
            .sorted((a, b) -> Double.compare(b.getBalance(), a.getBalance()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * 格式化金额显示
     */
    public String formatBalance(double amount) {
        return config.getCurrencySymbol() + String.format("%.2f", amount) + " " + config.getCurrencyName();
    }
    
    public enum TransferResult {
        SUCCESS,
        DISABLED,
        BELOW_MINIMUM,
        INSUFFICIENT_FUNDS,
        RECEIVER_MAX_BALANCE,
        FAILED
    }
}
```

### MoneyCommand.java

```java
package com.example.economy.commands;

import com.example.economy.configs.EconomyConfig;
import com.example.economy.entities.Account;
import com.example.economy.services.EconomyService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"money", "eco", "balance", "bal"},
    permission = "economy.use",
    description = "经济系统命令"
)
public class MoneyCommand extends AbstractCommandExecutor {
    
    @Autowired
    private EconomyService economyService;
    
    @Autowired
    private EconomyConfig config;
    
    /**
     * /money - 查看自己余额
     */
    @CmdMapping(format = "")
    public void checkBalance(@CmdSender Player player) {
        double balance = economyService.getBalance(player);
        player.sendMessage(ChatColor.GREEN + "你的余额: " + 
            ChatColor.GOLD + economyService.formatBalance(balance));
    }
    
    /**
     * /money <player> - 查看他人余额
     */
    @CmdMapping(format = "<target>", permission = "economy.check.others")
    public void checkOtherBalance(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target
    ) {
        double balance = economyService.getBalance(target);
        sender.sendMessage(ChatColor.GREEN + target.getName() + " 的余额: " + 
            ChatColor.GOLD + economyService.formatBalance(balance));
    }
    
    /**
     * /money pay <player> <amount> - 转账
     */
    @CmdMapping(format = "pay <target> <amount>")
    @CmdCD(value = 5, unit = java.util.concurrent.TimeUnit.SECONDS)
    public void transfer(
        @CmdSender Player sender,
        @CmdParam("target") Player target,
        @CmdParam("amount") double amount
    ) {
        if (sender.equals(target)) {
            sender.sendMessage(ChatColor.RED + "不能给自己转账！");
            return;
        }
        
        EconomyService.TransferResult result = economyService.transfer(sender, target, amount);
        
        switch (result) {
            case SUCCESS:
                double fee = amount * config.getTransferFeeRate();
                sender.sendMessage(ChatColor.GREEN + "成功转账 " + 
                    economyService.formatBalance(amount) + " 给 " + target.getName());
                if (fee > 0) {
                    sender.sendMessage(ChatColor.GRAY + "手续费: " + 
                        economyService.formatBalance(fee));
                }
                target.sendMessage(ChatColor.GREEN + sender.getName() + " 向你转账 " + 
                    economyService.formatBalance(amount));
                break;
            case DISABLED:
                sender.sendMessage(ChatColor.RED + "转账功能已禁用！");
                break;
            case BELOW_MINIMUM:
                sender.sendMessage(ChatColor.RED + "最小转账金额: " + 
                    economyService.formatBalance(config.getMinTransferAmount()));
                break;
            case INSUFFICIENT_FUNDS:
                sender.sendMessage(ChatColor.RED + "余额不足！");
                break;
            case RECEIVER_MAX_BALANCE:
                sender.sendMessage(ChatColor.RED + "对方余额已达上限！");
                break;
            default:
                sender.sendMessage(ChatColor.RED + "转账失败，请稍后重试！");
        }
    }
    
    /**
     * /money top [数量] - 财富排行榜
     */
    @CmdMapping(format = "top")
    public void showTop(@CmdSender CommandSender sender) {
        showTopN(sender, 10);
    }
    
    @CmdMapping(format = "top <count>")
    public void showTopN(
        @CmdSender CommandSender sender,
        @CmdParam("count") int count
    ) {
        if (count < 1 || count > 100) {
            count = 10;
        }
        
        List<Account> topAccounts = economyService.getTopAccounts(count);
        
        sender.sendMessage(ChatColor.GOLD + "===== 财富排行榜 Top " + count + " =====");
        
        for (int i = 0; i < topAccounts.size(); i++) {
            Account account = topAccounts.get(i);
            String rank = getRankColor(i + 1) + "" + (i + 1);
            sender.sendMessage(rank + ". " + ChatColor.WHITE + account.getName() + 
                ChatColor.GRAY + " - " + ChatColor.GOLD + 
                economyService.formatBalance(account.getBalance()));
        }
    }
    
    /**
     * /money give <player> <amount> - 管理员给钱
     */
    @CmdMapping(format = "give <target> <amount>", permission = "economy.admin")
    public void give(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target,
        @CmdParam("amount") double amount
    ) {
        if (economyService.deposit(target, amount)) {
            sender.sendMessage(ChatColor.GREEN + "已给予 " + target.getName() + " " + 
                economyService.formatBalance(amount));
            target.sendMessage(ChatColor.GREEN + "你收到了 " + 
                economyService.formatBalance(amount));
        } else {
            sender.sendMessage(ChatColor.RED + "操作失败！");
        }
    }
    
    /**
     * /money take <player> <amount> - 管理员扣钱
     */
    @CmdMapping(format = "take <target> <amount>", permission = "economy.admin")
    public void take(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target,
        @CmdParam("amount") double amount
    ) {
        if (economyService.withdraw(target, amount)) {
            sender.sendMessage(ChatColor.GREEN + "已从 " + target.getName() + " 扣除 " + 
                economyService.formatBalance(amount));
            target.sendMessage(ChatColor.RED + "你被扣除了 " + 
                economyService.formatBalance(amount));
        } else {
            sender.sendMessage(ChatColor.RED + "操作失败（可能余额不足）！");
        }
    }
    
    private ChatColor getRankColor(int rank) {
        switch (rank) {
            case 1: return ChatColor.GOLD;
            case 2: return ChatColor.GRAY;
            case 3: return ChatColor.DARK_RED;
            default: return ChatColor.WHITE;
        }
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== 经济系统帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/money" + ChatColor.GRAY + " - 查看余额");
        sender.sendMessage(ChatColor.YELLOW + "/money <玩家>" + ChatColor.GRAY + " - 查看他人余额");
        sender.sendMessage(ChatColor.YELLOW + "/money pay <玩家> <金额>" + ChatColor.GRAY + " - 转账");
        sender.sendMessage(ChatColor.YELLOW + "/money top [数量]" + ChatColor.GRAY + " - 财富排行");
        
        if (sender.hasPermission("economy.admin")) {
            sender.sendMessage(ChatColor.RED + "--- 管理员命令 ---");
            sender.sendMessage(ChatColor.YELLOW + "/money give <玩家> <金额>" + ChatColor.GRAY + " - 给予金币");
            sender.sendMessage(ChatColor.YELLOW + "/money take <玩家> <金额>" + ChatColor.GRAY + " - 扣除金币");
        }
    }
}
```

---

## 传送系统示例

一个完整的家和传送点系统。

### Home.java (数据实体)

```java
package com.example.teleport.entities;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("player_homes")
public class Home extends AbstractDataEntity {
    
    @Column("owner_uuid")
    private String ownerUuid;
    
    @Column("name")
    private String name;
    
    @Column("world")
    private String world;
    
    @Column(value = "x", type = "DOUBLE")
    private double x;
    
    @Column(value = "y", type = "DOUBLE")
    private double y;
    
    @Column(value = "z", type = "DOUBLE")
    private double z;
    
    @Column(value = "yaw", type = "FLOAT")
    private float yaw;
    
    @Column(value = "pitch", type = "FLOAT")
    private float pitch;
    
    @Column(value = "created_at", type = "BIGINT")
    private long createdAt;
}
```

### HomeService.java

```java
package com.example.teleport.services;

import com.example.teleport.TeleportPlugin;
import com.example.teleport.entities.Home;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

@Service
public class HomeService {
    
    private final DataOperator<Home> homeOperator;
    
    public HomeService() {
        this.homeOperator = TeleportPlugin.getInstance().getDataOperator(Home.class);
    }
    
    /**
     * 获取玩家的所有家
     */
    public List<Home> getHomes(Player player) {
        return homeOperator.getAll(
            WhereCondition.builder()
                .column("owner_uuid")
                .value(player.getUniqueId().toString())
                .build()
        );
    }
    
    /**
     * 获取指定的家
     */
    public Optional<Home> getHome(Player player, String name) {
        List<Home> homes = homeOperator.getAll(
            WhereCondition.builder()
                .column("owner_uuid")
                .value(player.getUniqueId().toString())
                .build(),
            WhereCondition.builder()
                .column("name")
                .value(name)
                .build()
        );
        return homes.isEmpty() ? Optional.empty() : Optional.of(homes.get(0));
    }
    
    /**
     * 设置家
     */
    public boolean setHome(Player player, String name, int maxHomes) {
        List<Home> existingHomes = getHomes(player);
        
        // 检查是否已存在同名的家
        Optional<Home> existing = existingHomes.stream()
            .filter(h -> h.getName().equalsIgnoreCase(name))
            .findFirst();
        
        Location loc = player.getLocation();
        
        if (existing.isPresent()) {
            // 更新现有的家
            Home home = existing.get();
            home.setWorld(loc.getWorld().getName());
            home.setX(loc.getX());
            home.setY(loc.getY());
            home.setZ(loc.getZ());
            home.setYaw(loc.getYaw());
            home.setPitch(loc.getPitch());
            
            try {
                homeOperator.update(home);
                return true;
            } catch (IllegalAccessException e) {
                return false;
            }
        }
        
        // 检查家的数量限制
        if (existingHomes.size() >= maxHomes) {
            return false;
        }
        
        // 创建新的家
        Home home = Home.builder()
            .ownerUuid(player.getUniqueId().toString())
            .name(name)
            .world(loc.getWorld().getName())
            .x(loc.getX())
            .y(loc.getY())
            .z(loc.getZ())
            .yaw(loc.getYaw())
            .pitch(loc.getPitch())
            .createdAt(System.currentTimeMillis())
            .build();
        
        homeOperator.insert(home);
        return true;
    }
    
    /**
     * 删除家
     */
    public boolean deleteHome(Player player, String name) {
        Optional<Home> home = getHome(player, name);
        if (home.isPresent()) {
            homeOperator.delById(home.get().getId());
            return true;
        }
        return false;
    }
    
    /**
     * 传送到家
     */
    public boolean teleportToHome(Player player, String name) {
        Optional<Home> homeOpt = getHome(player, name);
        if (!homeOpt.isPresent()) {
            return false;
        }
        
        Home home = homeOpt.get();
        World world = Bukkit.getWorld(home.getWorld());
        if (world == null) {
            return false;
        }
        
        Location location = new Location(
            world,
            home.getX(),
            home.getY(),
            home.getZ(),
            home.getYaw(),
            home.getPitch()
        );
        
        player.teleport(location);
        return true;
    }
}
```

### HomeCommand.java

```java
package com.example.teleport.commands;

import com.example.teleport.entities.Home;
import com.example.teleport.services.HomeService;
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"home", "h"},
    permission = "teleport.home",
    description = "家传送命令"
)
public class HomeCommand extends AbstractCommandExecutor {
    
    @Autowired
    private HomeService homeService;
    
    private static final int DEFAULT_MAX_HOMES = 3;
    
    /**
     * /home - 传送到默认家
     */
    @CmdMapping(format = "")
    public void goHome(@CmdSender Player player) {
        teleportHome(player, "home");
    }
    
    /**
     * /home <name> - 传送到指定家
     */
    @CmdMapping(format = "<name>")
    @CmdCD(value = 3, unit = java.util.concurrent.TimeUnit.SECONDS)
    public void teleportHome(
        @CmdSender Player player,
        @CmdParam("name") @CmdSuggest({"home", "base", "farm"}) String name
    ) {
        if (homeService.teleportToHome(player, name)) {
            player.sendMessage(ChatColor.GREEN + "已传送到家: " + name);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
        } else {
            player.sendMessage(ChatColor.RED + "找不到名为 '" + name + "' 的家！");
        }
    }
    
    /**
     * /home set <name> - 设置家
     */
    @CmdMapping(format = "set <name>")
    public void setHome(
        @CmdSender Player player,
        @CmdParam("name") String name
    ) {
        int maxHomes = getMaxHomes(player);
        
        if (homeService.setHome(player, name, maxHomes)) {
            player.sendMessage(ChatColor.GREEN + "家 '" + name + "' 已设置！");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1);
        } else {
            player.sendMessage(ChatColor.RED + "无法设置家！你可能已达到上限 (" + maxHomes + " 个)");
        }
    }
    
    /**
     * /home del <name> - 删除家
     */
    @CmdMapping(format = "del <name>")
    public void deleteHome(
        @CmdSender Player player,
        @CmdParam("name") String name
    ) {
        if (homeService.deleteHome(player, name)) {
            player.sendMessage(ChatColor.GREEN + "家 '" + name + "' 已删除！");
        } else {
            player.sendMessage(ChatColor.RED + "找不到名为 '" + name + "' 的家！");
        }
    }
    
    /**
     * /home list - 列出所有家
     */
    @CmdMapping(format = "list")
    public void listHomes(@CmdSender Player player) {
        List<Home> homes = homeService.getHomes(player);
        
        if (homes.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "你还没有设置任何家！");
            player.sendMessage(ChatColor.GRAY + "使用 /home set <名称> 设置一个家");
            return;
        }
        
        int maxHomes = getMaxHomes(player);
        player.sendMessage(ChatColor.GOLD + "===== 你的家 (" + homes.size() + "/" + maxHomes + ") =====");
        
        for (Home home : homes) {
            String info = String.format(
                "%s%s %s- %s(%s, %.0f, %.0f, %.0f)",
                ChatColor.YELLOW,
                home.getName(),
                ChatColor.GRAY,
                ChatColor.WHITE,
                home.getWorld(),
                home.getX(),
                home.getY(),
                home.getZ()
            );
            player.sendMessage(info);
        }
    }
    
    private int getMaxHomes(Player player) {
        // 可以根据权限或VIP等级返回不同数量
        if (player.hasPermission("teleport.home.unlimited")) {
            return Integer.MAX_VALUE;
        }
        if (player.hasPermission("teleport.home.vip")) {
            return 10;
        }
        return DEFAULT_MAX_HOMES;
    }
    
    @Override
    protected List<String> suggest(Player player, org.bukkit.command.Command command, String[] args) {
        if (args.length == 1) {
            // 补全家的名称
            return homeService.getHomes(player).stream()
                .map(Home::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return super.suggest(player, command, args);
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== 家命令帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/home" + ChatColor.GRAY + " - 传送到默认家");
        sender.sendMessage(ChatColor.YELLOW + "/home <名称>" + ChatColor.GRAY + " - 传送到指定家");
        sender.sendMessage(ChatColor.YELLOW + "/home set <名称>" + ChatColor.GRAY + " - 设置家");
        sender.sendMessage(ChatColor.YELLOW + "/home del <名称>" + ChatColor.GRAY + " - 删除家");
        sender.sendMessage(ChatColor.YELLOW + "/home list" + ChatColor.GRAY + " - 列出所有家");
    }
}
```

---

## 公告系统示例

一个支持定时公告的系统。

### Announcement.java

```java
package com.example.announce.entities;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("announcements")
public class Announcement extends AbstractDataEntity {
    
    @Column("name")
    private String name;
    
    @Column(value = "message", type = "TEXT")
    private String message;
    
    @Column(value = "interval_seconds", type = "INT")
    private int intervalSeconds;
    
    @Column("enabled")
    private boolean enabled;
    
    @Column("permission")
    private String permission;
    
    @Column(value = "created_at", type = "BIGINT")
    private long createdAt;
}
```

### AnnouncementService.java

```java
package com.example.announce.services;

import com.example.announce.AnnouncePlugin;
import com.example.announce.entities.Announcement;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AnnouncementService {
    
    private final DataOperator<Announcement> announcementOperator;
    private final Map<String, BukkitTask> scheduledTasks = new HashMap<>();
    
    public AnnouncementService() {
        this.announcementOperator = AnnouncePlugin.getInstance().getDataOperator(Announcement.class);
    }
    
    @PostConstruct
    public void init() {
        // 启动时加载所有启用的公告
        loadEnabledAnnouncements();
    }
    
    @PreDestroy
    public void cleanup() {
        // 取消所有定时任务
        scheduledTasks.values().forEach(BukkitTask::cancel);
        scheduledTasks.clear();
    }
    
    /**
     * 加载所有启用的公告
     */
    public void loadEnabledAnnouncements() {
        List<Announcement> enabled = announcementOperator.getAll(
            WhereCondition.builder().column("enabled").value(true).build()
        );
        
        for (Announcement announcement : enabled) {
            scheduleAnnouncement(announcement);
        }
        
        Bukkit.getLogger().info("[Announce] 已加载 " + enabled.size() + " 条定时公告");
    }
    
    /**
     * 调度公告
     */
    private void scheduleAnnouncement(Announcement announcement) {
        if (scheduledTasks.containsKey(announcement.getName())) {
            scheduledTasks.get(announcement.getName()).cancel();
        }
        
        long intervalTicks = announcement.getIntervalSeconds() * 20L;
        
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
            UltiTools.getInstance(),
            () -> broadcastAnnouncement(announcement),
            intervalTicks,
            intervalTicks
        );
        
        scheduledTasks.put(announcement.getName(), task);
    }
    
    /**
     * 广播公告
     */
    public void broadcastAnnouncement(Announcement announcement) {
        String message = ChatColor.translateAlternateColorCodes('&', announcement.getMessage());
        String permission = announcement.getPermission();
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (permission == null || permission.isEmpty() || player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }
    
    /**
     * 创建公告
     */
    public void createAnnouncement(String name, String message, int intervalSeconds) {
        Announcement announcement = Announcement.builder()
            .name(name)
            .message(message)
            .intervalSeconds(intervalSeconds)
            .enabled(true)
            .createdAt(System.currentTimeMillis())
            .build();
        
        announcementOperator.insert(announcement);
        scheduleAnnouncement(announcement);
    }
    
    /**
     * 删除公告
     */
    public boolean deleteAnnouncement(String name) {
        Optional<Announcement> announcement = getAnnouncement(name);
        if (announcement.isPresent()) {
            // 取消定时任务
            if (scheduledTasks.containsKey(name)) {
                scheduledTasks.get(name).cancel();
                scheduledTasks.remove(name);
            }
            
            announcementOperator.delById(announcement.get().getId());
            return true;
        }
        return false;
    }
    
    /**
     * 启用/禁用公告
     */
    public boolean toggleAnnouncement(String name, boolean enabled) {
        Optional<Announcement> announcementOpt = getAnnouncement(name);
        if (announcementOpt.isPresent()) {
            Announcement announcement = announcementOpt.get();
            announcement.setEnabled(enabled);
            
            try {
                announcementOperator.update(announcement);
            } catch (IllegalAccessException e) {
                return false;
            }
            
            if (enabled) {
                scheduleAnnouncement(announcement);
            } else {
                if (scheduledTasks.containsKey(name)) {
                    scheduledTasks.get(name).cancel();
                    scheduledTasks.remove(name);
                }
            }
            
            return true;
        }
        return false;
    }
    
    /**
     * 获取所有公告
     */
    public List<Announcement> getAllAnnouncements() {
        return announcementOperator.getAll();
    }
    
    /**
     * 获取指定公告
     */
    public Optional<Announcement> getAnnouncement(String name) {
        List<Announcement> results = announcementOperator.getAll(
            WhereCondition.builder().column("name").value(name).build()
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
```

---

## 总结

这些示例展示了 UltiTools-API 的核心功能：

1. **IoC 容器**: `@Service`、`@Autowired` 自动装配
2. **命令系统**: `@CmdExecutor`、`@CmdMapping` 注解驱动
3. **数据存储**: `@Table`、`@Column` ORM 映射
4. **配置管理**: `@ConfigEntity`、`@ConfigEntry` 类型安全配置
5. **生命周期**: `@PostConstruct`、`@PreDestroy` 钩子

---

> **更多资源**: 查看 [API 参考](../api/INDEX.md) 获取完整 API 文档
