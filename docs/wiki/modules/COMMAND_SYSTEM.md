# 命令系统

本文档详细介绍 UltiTools-API 的注解驱动命令系统。

---

## 目录

- [概述](#概述)
- [快速入门](#快速入门)
- [注解详解](#注解详解)
- [参数解析](#参数解析)
- [Tab 补全](#tab-补全)
- [高级特性](#高级特性)
- [最佳实践](#最佳实践)

---

## 概述

UltiTools 命令系统采用 **类似 Spring MVC** 的注解驱动模式，让命令开发变得简单直观：

- **声明式定义**: 通过注解定义命令和参数
- **自动参数解析**: 自动将字符串参数转换为目标类型
- **智能 Tab 补全**: 基于参数类型自动生成补全建议
- **权限管理**: 注解级别的权限控制
- **冷却时间**: 内置命令冷却支持

---

## 快速入门

### 最简示例

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"hello"}, permission = "myplugin.hello")
public class HelloCommand extends AbstractCommandExecutor {
    
    @CmdMapping(format = "")
    public void sayHello(@CmdSender Player player) {
        player.sendMessage("Hello, " + player.getName() + "!");
    }
}
```

**效果**: 玩家输入 `/hello` 后收到问候消息。

### 带参数的命令

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"greet"}, permission = "myplugin.greet")
public class GreetCommand extends AbstractCommandExecutor {
    
    @CmdMapping(format = "<target>")
    public void greet(@CmdSender Player player, @CmdParam("target") Player target) {
        target.sendMessage(player.getName() + " says hello to you!");
        player.sendMessage("You greeted " + target.getName());
    }
}
```

**效果**: `/greet Steve` - 向 Steve 发送问候。

---

## 注解详解

### @CmdExecutor

标记命令执行器类：

```java
@CmdExecutor(
    alias = {"cmd", "c"},           // 命令别名（必需）
    permission = "plugin.cmd",       // 权限节点
    description = "命令描述",        // 描述
    requireOp = false,               // 是否需要 OP
    manualRegister = false           // 是否手动注册
)
public class MyCommand extends AbstractCommandExecutor {
    // ...
}
```

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `alias` | String[] | 必需 | 命令别名列表 |
| `permission` | String | "" | 权限节点 |
| `description` | String | "" | 命令描述 |
| `requireOp` | boolean | false | 是否需要 OP 权限 |
| `manualRegister` | boolean | false | 是否手动注册（不自动注册） |

### @CmdTarget

指定命令执行者类型：

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)  // 仅玩家
@CmdTarget(CmdTarget.CmdTargetType.CONSOLE) // 仅控制台
@CmdTarget(CmdTarget.CmdTargetType.BOTH)    // 两者都可
```

### @CmdMapping

映射命令格式到方法：

```java
@CmdMapping(
    format = "sub <param>",     // 命令格式
    permission = "plugin.sub",  // 额外权限
    requireOp = false           // 是否需要 OP
)
public void handleSubCommand(...) {
    // ...
}
```

**格式语法**:

| 语法 | 描述 | 示例 |
|------|------|------|
| `""` | 无参数 | `/cmd` |
| `sub` | 固定子命令 | `/cmd sub` |
| `<param>` | 必需参数 | `/cmd <name>` |
| `<param...>` | 可变参数（多个值） | `/cmd <words...>` |
| 组合 | 混合使用 | `send <target> <message...>` |

### @CmdParam

标记方法参数：

```java
@CmdMapping(format = "give <player> <amount>")
public void giveCoins(
    @CmdSender Player sender,
    @CmdParam("player") Player target,
    @CmdParam("amount") int amount
) {
    // ...
}
```

### @CmdSender

标记命令发送者参数：

```java
// 玩家发送者
public void playerOnly(@CmdSender Player player) { }

// 通用发送者（玩家或控制台）
public void anyone(@CmdSender CommandSender sender) { }
```

### @CmdSuggest

自定义 Tab 补全建议：

```java
@CmdMapping(format = "teleport <world>")
public void teleport(
    @CmdSender Player player,
    @CmdParam("world") @CmdSuggest({"world", "world_nether", "world_the_end"}) String world
) {
    // ...
}
```

---

## 参数解析

### 内置类型转换

框架自动支持以下类型转换：

| 目标类型 | 输入示例 | 说明 |
|----------|----------|------|
| `String` | `hello` | 原样传递 |
| `int` / `Integer` | `42` | 整数 |
| `double` / `Double` | `3.14` | 浮点数 |
| `boolean` / `Boolean` | `true`, `false` | 布尔值 |
| `Player` | `Steve` | 在线玩家 |
| `OfflinePlayer` | `Steve` | 离线玩家 |
| `Material` | `DIAMOND` | 物品材质 |
| `UUID` | `uuid字符串` | UUID |

### 可变参数

使用 `<param...>` 接收多个参数：

```java
@CmdMapping(format = "broadcast <message...>")
public void broadcast(
    @CmdSender CommandSender sender,
    @CmdParam("message...") String[] message
) {
    String fullMessage = String.join(" ", message);
    Bukkit.broadcastMessage(fullMessage);
}
```

**调用**: `/broadcast Hello everyone in the server!`

### 自定义解析器

注册自定义类型解析器：

```java
public class MyCommand extends AbstractCommandExecutor {
    
    public MyCommand() {
        super();
        // 注册自定义解析器
        registerParser(MyCustomType.class, this::parseMyType);
    }
    
    private MyCustomType parseMyType(String input) {
        return new MyCustomType(input);
    }
}
```

---

## Tab 补全

### 自动补全

框架根据参数类型自动提供补全：

| 参数类型 | 自动补全内容 |
|----------|--------------|
| `Player` | 在线玩家列表 |
| `Material` | 物品材质列表 |
| `boolean` | `true`, `false` |

### 自定义补全

**方式一：使用 @CmdSuggest**

```java
@CmdMapping(format = "mode <mode>")
public void setMode(
    @CmdSender Player player,
    @CmdParam("mode") @CmdSuggest({"easy", "normal", "hard"}) String mode
) {
    // ...
}
```

**方式二：重写 suggest 方法**

```java
@Override
protected List<String> suggest(Player player, Command command, String[] args) {
    if (args.length == 1) {
        return Arrays.asList("option1", "option2", "option3");
    }
    return Collections.emptyList();
}
```

---

## 高级特性

### 命令冷却

使用 `@CmdCD` 设置命令冷却时间：

```java
@CmdMapping(format = "heal")
@CmdCD(value = 60, unit = TimeUnit.SECONDS)  // 60 秒冷却
public void heal(@CmdSender Player player) {
    player.setHealth(20.0);
    player.sendMessage("你已被治愈！60秒后可再次使用。");
}
```

### 使用限制

使用 `@UsageLimit` 限制命令使用次数：

```java
@CmdMapping(format = "reward")
@UsageLimit(5)  // 每天限制 5 次
public void dailyReward(@CmdSender Player player) {
    // 发放奖励
}
```

### 异步执行

使用 `@RunAsync` 在异步线程执行命令：

```java
@CmdMapping(format = "query <player>")
@RunAsync
public void queryDatabase(
    @CmdSender Player player,
    @CmdParam("player") String targetName
) {
    // 这段代码在异步线程执行
    // 适合数据库查询等耗时操作
    PlayerData data = database.query(targetName);
    
    // 注意：操作 Bukkit API 需要回到主线程
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("查询结果: " + data);
    });
}
```

### 权限分级

为不同子命令设置不同权限：

```java
@CmdExecutor(alias = {"admin"}, permission = "myplugin.admin")
public class AdminCommand extends AbstractCommandExecutor {
    
    @CmdMapping(format = "ban <player>", permission = "myplugin.admin.ban")
    public void banPlayer(@CmdSender Player sender, @CmdParam("player") Player target) {
        // 需要 myplugin.admin 和 myplugin.admin.ban 权限
    }
    
    @CmdMapping(format = "kick <player>", permission = "myplugin.admin.kick")
    public void kickPlayer(@CmdSender Player sender, @CmdParam("player") Player target) {
        // 需要 myplugin.admin 和 myplugin.admin.kick 权限
    }
}
```

### 帮助信息

重写 `handleHelp` 方法自定义帮助信息：

```java
@Override
protected void handleHelp(CommandSender sender) {
    sender.sendMessage(ChatColor.GOLD + "===== 命令帮助 =====");
    sender.sendMessage(ChatColor.YELLOW + "/cmd help" + ChatColor.GRAY + " - 显示帮助");
    sender.sendMessage(ChatColor.YELLOW + "/cmd give <player> <amount>" + ChatColor.GRAY + " - 给予金币");
}
```

---

## 完整示例

### 经济系统命令

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(
    alias = {"money", "eco"}, 
    permission = "economy.use",
    description = "经济系统命令"
)
public class EconomyCommand extends AbstractCommandExecutor {
    
    @Autowired
    private EconomyService economyService;
    
    // /money - 查看余额
    @CmdMapping(format = "")
    public void checkBalance(@CmdSender Player player) {
        double balance = economyService.getBalance(player);
        player.sendMessage(ChatColor.GREEN + "你的余额: " + balance);
    }
    
    // /money pay <player> <amount> - 转账
    @CmdMapping(format = "pay <target> <amount>")
    @CmdCD(value = 5, unit = TimeUnit.SECONDS)
    public void pay(
        @CmdSender Player sender,
        @CmdParam("target") Player target,
        @CmdParam("amount") double amount
    ) {
        if (amount <= 0) {
            sender.sendMessage(ChatColor.RED + "金额必须大于0！");
            return;
        }
        
        if (economyService.transfer(sender, target, amount)) {
            sender.sendMessage(ChatColor.GREEN + "成功转账 " + amount + " 给 " + target.getName());
            target.sendMessage(ChatColor.GREEN + sender.getName() + " 转账给你 " + amount);
        } else {
            sender.sendMessage(ChatColor.RED + "余额不足！");
        }
    }
    
    // /money give <player> <amount> - 管理员给钱
    @CmdMapping(format = "give <target> <amount>", permission = "economy.admin")
    public void give(
        @CmdSender Player sender,
        @CmdParam("target") Player target,
        @CmdParam("amount") double amount
    ) {
        economyService.deposit(target, amount);
        sender.sendMessage(ChatColor.GREEN + "已给予 " + target.getName() + " " + amount);
    }
    
    // /money top - 排行榜
    @CmdMapping(format = "top")
    @RunAsync
    public void showTop(@CmdSender Player player) {
        List<PlayerBalance> top = economyService.getTopBalances(10);
        
        Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
            player.sendMessage(ChatColor.GOLD + "===== 财富排行榜 =====");
            for (int i = 0; i < top.size(); i++) {
                PlayerBalance pb = top.get(i);
                player.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + pb.getName() + ": " + pb.getBalance());
            }
        });
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== 经济命令帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/money" + ChatColor.GRAY + " - 查看余额");
        sender.sendMessage(ChatColor.YELLOW + "/money pay <玩家> <金额>" + ChatColor.GRAY + " - 转账");
        sender.sendMessage(ChatColor.YELLOW + "/money top" + ChatColor.GRAY + " - 财富排行榜");
        if (sender.hasPermission("economy.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/money give <玩家> <金额>" + ChatColor.GRAY + " - 管理员给钱");
        }
    }
}
```

---

## 最佳实践

### 推荐做法

1. **使用有意义的命令别名**
   ```java
   @CmdExecutor(alias = {"teleport", "tp"})  // 主名称 + 简写
   ```

2. **合理设置权限层级**
   ```
   myplugin.use         // 基础使用权限
   myplugin.admin       // 管理员权限
   myplugin.admin.ban   // 具体功能权限
   ```

3. **为耗时操作使用 @RunAsync**

4. **提供清晰的帮助信息**

5. **使用类型安全的参数**
   ```java
   // 好 - 自动验证玩家存在
   @CmdParam("player") Player target
   
   // 不推荐 - 需要手动验证
   @CmdParam("player") String playerName
   ```

### 避免做法

1. **避免在同步方法中进行数据库操作**
2. **避免过长的命令格式**
3. **避免忽略权限检查**
4. **避免在命令方法中抛出未处理的异常**

---

> **下一步**: 阅读 [数据存储](./DATA_STORAGE.md) 了解数据持久化
