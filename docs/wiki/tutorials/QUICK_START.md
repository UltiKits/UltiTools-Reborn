# 快速入门教程

本教程将引导你创建第一个 UltiTools 模块插件。

---

## 目录

- [环境准备](#环境准备)
- [创建项目](#创建项目)
- [编写主类](#编写主类)
- [创建命令](#创建命令)
- [添加数据存储](#添加数据存储)
- [添加配置](#添加配置)
- [添加事件监听](#添加事件监听)
- [构建和测试](#构建和测试)

---

## 环境准备

### 前置要求

- **JDK 8+** (推荐 JDK 11 或 17)
- **Maven 3.6+**
- **IDE**: IntelliJ IDEA 或 Eclipse
- **Minecraft 服务器**: Spigot/Paper 1.8-1.21

### 安装 UltiTools

1. 下载 [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn/releases) JAR
2. 放入服务器 `plugins/` 目录
3. 启动服务器，生成配置文件

---

## 创建项目

### 1. 创建 Maven 项目

**pom.xml**:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-ultitools-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>MyUltiToolsPlugin</name>
    <description>我的第一个 UltiTools 模块</description>

    <properties>
        <java.version>1.8</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <!-- Spigot 仓库 -->
        <repository>
            <id>spigot-repo</id>
            <url>https://hub.spigotmc.org/nexus/content/repositories/snapshots/</url>
        </repository>
    </repositories>

    <dependencies>
        <!-- Spigot API -->
        <dependency>
            <groupId>org.spigotmc</groupId>
            <artifactId>spigot-api</artifactId>
            <version>1.20.4-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>

        <!-- UltiTools API -->
        <dependency>
            <groupId>com.ultikits</groupId>
            <artifactId>UltiTools-API</artifactId>
            <version>6.2.0</version>
            <scope>provided</scope>
        </dependency>

        <!-- Lombok (可选，简化代码) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.30</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                </configuration>
            </plugin>
        </plugins>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
    </build>
</project>
```

### 2. 创建目录结构

```
my-ultitools-plugin/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── example/
        │           └── myplugin/
        │               ├── MyPlugin.java
        │               ├── commands/
        │               ├── services/
        │               ├── entities/
        │               ├── configs/
        │               └── listeners/
        └── resources/
            ├── plugin.yml
            └── lang/
                ├── zh.json
                └── en.json
```

---

## 编写主类

### plugin.yml

```yaml
name: MyUltiToolsPlugin
version: ${project.version}
main: com.example.myplugin.MyPlugin
authors:
  - YourName
api-version: 600
loadAfter: []
```

### MyPlugin.java

```java
package com.example.myplugin;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.UltiToolsModule;

@UltiToolsModule(
    scanBasePackages = {"com.example.myplugin"}
)
public class MyPlugin extends UltiToolsPlugin {
    
    private static MyPlugin instance;
    
    @Override
    public boolean registerSelf() {
        instance = this;
        
        getLogger().info("MyUltiToolsPlugin 启动成功！");
        getLogger().info("版本: " + getVersion());
        
        return true;
    }
    
    @Override
    public void unregisterSelf() {
        getLogger().info("MyUltiToolsPlugin 已卸载");
    }
    
    @Override
    public void reloadSelf() {
        getLogger().info("MyUltiToolsPlugin 配置已重载");
    }
    
    public static MyPlugin getInstance() {
        return instance;
    }
}
```

---

## 创建命令

### GreetCommand.java

```java
package com.example.myplugin.commands;

import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;
import com.ultikits.ultitools.annotations.command.*;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(
    alias = {"greet", "g"},
    permission = "myplugin.greet",
    description = "问候命令"
)
public class GreetCommand extends AbstractCommandExecutor {
    
    /**
     * /greet - 显示帮助
     */
    @CmdMapping(format = "")
    public void showHelp(@CmdSender CommandSender sender) {
        handleHelp(sender);
    }
    
    /**
     * /greet hello - 简单问候
     */
    @CmdMapping(format = "hello")
    public void sayHello(@CmdSender CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "Hello! 你好！");
    }
    
    /**
     * /greet <player> - 问候指定玩家
     */
    @CmdMapping(format = "<target>")
    public void greetPlayer(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target
    ) {
        String senderName = sender instanceof Player 
            ? ((Player) sender).getName() 
            : "控制台";
        
        target.sendMessage(ChatColor.GOLD + senderName + " 向你问好！");
        sender.sendMessage(ChatColor.GREEN + "你向 " + target.getName() + " 问好了！");
    }
    
    /**
     * /greet message <player> <message...> - 发送自定义消息
     */
    @CmdMapping(format = "message <target> <message...>")
    public void sendMessage(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target,
        @CmdParam("message...") String[] message
    ) {
        String fullMessage = String.join(" ", message);
        String senderName = sender instanceof Player 
            ? ((Player) sender).getName() 
            : "控制台";
        
        target.sendMessage(ChatColor.YELLOW + "[来自 " + senderName + "] " + fullMessage);
        sender.sendMessage(ChatColor.GREEN + "消息已发送给 " + target.getName());
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== 问候命令帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/greet hello" + ChatColor.GRAY + " - 简单问候");
        sender.sendMessage(ChatColor.YELLOW + "/greet <玩家>" + ChatColor.GRAY + " - 问候玩家");
        sender.sendMessage(ChatColor.YELLOW + "/greet message <玩家> <消息>" + ChatColor.GRAY + " - 发送消息");
    }
}
```

---

## 添加数据存储

### PlayerStats.java (数据实体)

```java
package com.example.myplugin.entities;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("player_stats")
public class PlayerStats extends AbstractDataEntity {
    
    @Column("uuid")
    private String uuid;
    
    @Column("name")
    private String name;
    
    @Column(value = "greet_count", type = "INT")
    private int greetCount;
    
    @Column(value = "last_greet", type = "BIGINT")
    private long lastGreet;
}
```

### StatsService.java (服务类)

```java
package com.example.myplugin.services;

import com.example.myplugin.MyPlugin;
import com.example.myplugin.entities.PlayerStats;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.entity.Player;

import java.util.List;

@Service
public class StatsService {
    
    private DataOperator<PlayerStats> dataOperator;
    
    public StatsService() {
        this.dataOperator = MyPlugin.getInstance().getDataOperator(PlayerStats.class);
    }
    
    /**
     * 获取玩家统计数据
     */
    public PlayerStats getStats(Player player) {
        List<PlayerStats> results = dataOperator.getAll(
            WhereCondition.builder()
                .column("uuid")
                .value(player.getUniqueId().toString())
                .build()
        );
        
        if (!results.isEmpty()) {
            return results.get(0);
        }
        
        // 创建新记录
        PlayerStats stats = PlayerStats.builder()
            .uuid(player.getUniqueId().toString())
            .name(player.getName())
            .greetCount(0)
            .lastGreet(0)
            .build();
        
        dataOperator.insert(stats);
        return stats;
    }
    
    /**
     * 增加问候次数
     */
    public void incrementGreetCount(Player player) {
        PlayerStats stats = getStats(player);
        stats.setGreetCount(stats.getGreetCount() + 1);
        stats.setLastGreet(System.currentTimeMillis());
        
        try {
            dataOperator.update(stats);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * 获取问候排行榜
     */
    public List<PlayerStats> getTopGreeters(int limit) {
        List<PlayerStats> all = dataOperator.getAll();
        return all.stream()
            .sorted((a, b) -> Integer.compare(b.getGreetCount(), a.getGreetCount()))
            .limit(limit)
            .collect(java.util.stream.Collectors.toList());
    }
}
```

---

## 添加配置

### PluginConfig.java

```java
package com.example.myplugin.configs;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity(path = "config/settings.yml")
public class PluginConfig extends AbstractConfigEntity {
    
    @ConfigEntry(path = "enabled", comment = "是否启用插件")
    private boolean enabled = true;
    
    @ConfigEntry(path = "greet.cooldown", comment = "问候冷却时间（秒）")
    private int greetCooldown = 30;
    
    @ConfigEntry(path = "greet.max-daily", comment = "每日最大问候次数")
    private int maxDailyGreets = 100;
    
    @ConfigEntry(path = "messages.prefix", comment = "消息前缀")
    private String messagePrefix = "&6[问候] &r";
    
    @ConfigEntry(path = "messages.greet-success", comment = "问候成功消息")
    private String greetSuccessMessage = "&a你成功向 %player% 问好！";
    
    @ConfigEntry(path = "messages.greet-received", comment = "收到问候消息")
    private String greetReceivedMessage = "&e%sender% 向你问好！";
    
    public PluginConfig() {
        super("config/settings.yml");
    }
}
```

### 使用配置

更新命令类以使用配置和服务：

```java
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
@CmdExecutor(alias = {"greet", "g"}, permission = "myplugin.greet")
public class GreetCommand extends AbstractCommandExecutor {
    
    @Autowired
    private StatsService statsService;
    
    @Autowired
    private PluginConfig config;
    
    @CmdMapping(format = "<target>")
    public void greetPlayer(
        @CmdSender CommandSender sender,
        @CmdParam("target") Player target
    ) {
        if (!config.isEnabled()) {
            sender.sendMessage(ChatColor.RED + "插件已禁用！");
            return;
        }
        
        String senderName = sender instanceof Player 
            ? ((Player) sender).getName() 
            : "控制台";
        
        // 发送消息
        String prefix = ChatColor.translateAlternateColorCodes('&', config.getMessagePrefix());
        
        String successMsg = config.getGreetSuccessMessage()
            .replace("%player%", target.getName());
        sender.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&', successMsg));
        
        String receivedMsg = config.getGreetReceivedMessage()
            .replace("%sender%", senderName);
        target.sendMessage(prefix + ChatColor.translateAlternateColorCodes('&', receivedMsg));
        
        // 更新统计
        if (sender instanceof Player) {
            statsService.incrementGreetCount((Player) sender);
        }
    }
}
```

---

## 添加事件监听

### PlayerListener.java

```java
package com.example.myplugin.listeners;

import com.example.myplugin.configs.PluginConfig;
import com.example.myplugin.services.StatsService;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

@EventListener
public class PlayerListener implements Listener {
    
    @Autowired
    private StatsService statsService;
    
    @Autowired
    private PluginConfig config;
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.isEnabled()) {
            return;
        }
        
        // 获取玩家统计
        var stats = statsService.getStats(event.getPlayer());
        
        // 显示统计信息
        event.getPlayer().sendMessage(
            ChatColor.GREEN + "欢迎回来！你已发送 " + 
            ChatColor.GOLD + stats.getGreetCount() + 
            ChatColor.GREEN + " 次问候。"
        );
    }
}
```

---

## 构建和测试

### 1. 构建项目

```bash
mvn clean package
```

### 2. 部署插件

将生成的 JAR 文件复制到服务器：

```bash
cp target/my-ultitools-plugin-1.0.0.jar /path/to/server/plugins/UltiTools/plugins/
```

### 3. 启动服务器测试

启动服务器并测试命令：

```
/greet hello
/greet Steve
/greet message Steve 你好，今天天气真好！
```

### 4. 检查生成的文件

配置文件位置:
```
plugins/UltiTools/pluginConfig/MyUltiToolsPlugin/config/settings.yml
```

数据文件位置（JSON 模式）:
```
plugins/UltiTools/pluginConfig/MyUltiToolsPlugin/data/player_stats/
```

---

## 下一步

恭喜你完成了第一个 UltiTools 模块！接下来你可以：

1. **学习更多模块**: 阅读 [完整示例](./EXAMPLES.md)
2. **深入了解系统**: 阅读各模块详细文档
3. **加入社区**: [Discord](https://discord.gg/6TVRRF47) | [QQ群](https://qm.qq.com/q/i5GeDH6a7C)

---

## 常见问题

### Q: 命令没有自动注册？

检查：

1. 命令类是否继承 `AbstractCommandExecutor`
2. 是否添加了 `@CmdExecutor` 注解
3. `@UltiToolsModule` 的 `scanBasePackages` 是否包含命令类所在包

### Q: 依赖注入失败？

检查：

1. 服务类是否添加了 `@Service` 注解
2. 字段是否添加了 `@Autowired` 注解
3. 类是否在扫描包路径下

### Q: 数据没有保存？

检查：

1. 实体类是否继承 `AbstractDataEntity`
2. 是否添加了 `@Table` 和 `@Column` 注解
3. `config.yml` 中的数据源配置

---

> **下一步**: 阅读 [完整示例](./EXAMPLES.md) 了解更复杂的开发场景
