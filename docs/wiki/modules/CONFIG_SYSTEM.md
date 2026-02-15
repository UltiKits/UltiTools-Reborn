# 配置管理

本文档详细介绍 UltiTools-API 的配置管理系统。

---

## 目录

- [概述](#概述)
- [配置实体定义](#配置实体定义)
- [注解详解](#注解详解)
- [配置使用](#配置使用)
- [自定义序列化](#自定义序列化)
- [最佳实践](#最佳实践)

---

## 概述

UltiTools 提供注解驱动的配置管理系统：

- **类型安全**: 配置字段有明确的 Java 类型
- **自动序列化**: 自动在 YAML 文件和 Java 对象之间转换
- **默认值支持**: 字段初始值作为默认配置值
- **注释支持**: 自动生成配置文件注释
- **热重载**: 支持运行时重新加载配置

---

## 配置实体定义

### 基本配置类

```java
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigEntity(path = "config/settings.yml")
public class PluginSettings extends AbstractConfigEntity {
    
    @ConfigEntry(path = "enabled", comment = "是否启用插件功能")
    private boolean enabled = true;
    
    @ConfigEntry(path = "max-players", comment = "最大玩家数限制")
    private int maxPlayers = 100;
    
    @ConfigEntry(path = "welcome-message", comment = "欢迎消息")
    private String welcomeMessage = "欢迎来到服务器！";
    
    @ConfigEntry(path = "vip.bonus-rate", comment = "VIP 奖励倍率")
    private double vipBonusRate = 1.5;
    
    public PluginSettings() {
        super("config/settings.yml");
    }
}
```

生成的 `settings.yml`:

```yaml
# 是否启用插件功能
enabled: true

# 最大玩家数限制
max-players: 100

# 欢迎消息
welcome-message: 欢迎来到服务器！

vip:
  # VIP 奖励倍率
  bonus-rate: 1.5
```

### 嵌套路径

使用点号分隔创建嵌套结构：

```java
@ConfigEntry(path = "database.host")
private String dbHost = "localhost";

@ConfigEntry(path = "database.port")
private int dbPort = 3306;

@ConfigEntry(path = "database.credentials.username")
private String dbUsername = "root";

@ConfigEntry(path = "database.credentials.password")
private String dbPassword = "";
```

生成的 YAML:

```yaml
database:
  host: localhost
  port: 3306
  credentials:
    username: root
    password: ''
```

---

## 注解详解

### @ConfigEntity

标记配置类并指定文件路径：

```java
@ConfigEntity(path = "config/my-config.yml")
public class MyConfig extends AbstractConfigEntity {
    // 构造函数必须调用 super(path)
    public MyConfig() {
        super("config/my-config.yml");
    }
}
```

| 属性 | 类型 | 描述 |
|------|------|------|
| `path` | String | 配置文件相对路径 |

### @ConfigEntry

标记配置字段：

```java
@ConfigEntry(
    path = "setting.name",           // YAML 路径
    comment = "配置项说明",           // 注释
    parser = DefaultParser.class     // 自定义解析器
)
private String settingName = "default";
```

| 属性 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `path` | String | 字段名 | YAML 中的路径 |
| `comment` | String | "" | 配置注释 |
| `parser` | Class | DefaultParser | 自定义序列化器 |

---

## 配置使用

### 注册配置

配置类会被自动注册（当使用 `@UltiToolsModule` 时）：

```java
@UltiToolsModule(scanBasePackages = {"com.example"})
public class MyPlugin extends UltiToolsPlugin {
    // 配置自动注册
}
```

手动注册：

```java
@Override
public boolean registerSelf() {
    PluginSettings settings = new PluginSettings();
    registerConfig(settings);
    return true;
}
```

### 获取配置

**方式一：通过容器获取**

```java
@Service
public class MyService {
    
    @Autowired
    private PluginSettings settings;
    
    public void doSomething() {
        if (settings.isEnabled()) {
            // ...
        }
    }
}
```

**方式二：通过插件获取**

```java
PluginSettings settings = plugin.getConfig(PluginSettings.class);
```

### 保存配置

修改配置后保存到文件：

```java
settings.setMaxPlayers(200);
settings.save();  // 保存到 YAML 文件
```

### 重新加载配置

从文件重新加载配置：

```java
// 重新初始化配置
settings.init(plugin);
```

---

## 支持的类型

### 基本类型

| Java 类型 | YAML 表示 |
|-----------|-----------|
| `boolean` | `true` / `false` |
| `int` | `123` |
| `long` | `123456789` |
| `double` | `3.14` |
| `float` | `3.14` |
| `String` | `"text"` |

### 集合类型

```java
@ConfigEntry(path = "allowed-worlds")
private List<String> allowedWorlds = Arrays.asList("world", "world_nether");

@ConfigEntry(path = "item-prices")
private Map<String, Double> itemPrices = new HashMap<>();
```

YAML:

```yaml
allowed-worlds:
  - world
  - world_nether

item-prices:
  diamond: 100.0
  gold_ingot: 10.0
```

### 枚举类型

```java
public enum GameMode {
    EASY, NORMAL, HARD
}

@ConfigEntry(path = "game-mode")
private GameMode gameMode = GameMode.NORMAL;
```

YAML:

```yaml
game-mode: NORMAL
```

---

## 自定义序列化

### Parser 接口

实现 `Parser` 接口处理复杂类型：

```java
import com.ultikits.ultitools.interfaces.Parser;

public class LocationParser implements Parser<Location, Map<String, Object>> {
    
    @Override
    public Location parse(Map<String, Object> config) {
        String world = (String) config.get("world");
        double x = ((Number) config.get("x")).doubleValue();
        double y = ((Number) config.get("y")).doubleValue();
        double z = ((Number) config.get("z")).doubleValue();
        float yaw = ((Number) config.getOrDefault("yaw", 0)).floatValue();
        float pitch = ((Number) config.getOrDefault("pitch", 0)).floatValue();
        
        World bukkitWorld = Bukkit.getWorld(world);
        return new Location(bukkitWorld, x, y, z, yaw, pitch);
    }
    
    @Override
    public Map<String, Object> serialize(Location location) {
        Map<String, Object> map = new HashMap<>();
        map.put("world", location.getWorld().getName());
        map.put("x", location.getX());
        map.put("y", location.getY());
        map.put("z", location.getZ());
        map.put("yaw", location.getYaw());
        map.put("pitch", location.getPitch());
        return map;
    }
}
```

### 使用自定义 Parser

```java
@ConfigEntry(path = "spawn-point", parser = LocationParser.class)
private Location spawnPoint;
```

YAML:

```yaml
spawn-point:
  world: world
  x: 0.0
  y: 64.0
  z: 0.0
  yaw: 0.0
  pitch: 0.0
```

---

## 完整示例

### 游戏配置

```java
@Getter
@Setter
@ConfigEntity(path = "config/game.yml")
public class GameConfig extends AbstractConfigEntity {
    
    // 基本设置
    @ConfigEntry(path = "general.enabled", comment = "是否启用游戏功能")
    private boolean enabled = true;
    
    @ConfigEntry(path = "general.debug-mode", comment = "调试模式")
    private boolean debugMode = false;
    
    // 游戏规则
    @ConfigEntry(path = "rules.max-players", comment = "最大玩家数")
    private int maxPlayers = 16;
    
    @ConfigEntry(path = "rules.game-duration", comment = "游戏时长（秒）")
    private int gameDuration = 600;
    
    @ConfigEntry(path = "rules.allowed-kits", comment = "允许的装备套件")
    private List<String> allowedKits = Arrays.asList("warrior", "archer", "mage");
    
    // 奖励设置
    @ConfigEntry(path = "rewards.winner-coins", comment = "胜者金币奖励")
    private int winnerCoins = 100;
    
    @ConfigEntry(path = "rewards.participation-coins", comment = "参与奖励")
    private int participationCoins = 10;
    
    @ConfigEntry(path = "rewards.kill-bonus", comment = "击杀奖励")
    private int killBonus = 5;
    
    // 消息
    @ConfigEntry(path = "messages.game-start", comment = "游戏开始消息")
    private String gameStartMessage = "&a游戏开始！";
    
    @ConfigEntry(path = "messages.game-end", comment = "游戏结束消息")  
    private String gameEndMessage = "&c游戏结束！胜者: %winner%";
    
    @ConfigEntry(path = "messages.player-join", comment = "玩家加入消息")
    private String playerJoinMessage = "&e%player% 加入了游戏";
    
    // 位置（使用自定义 Parser）
    @ConfigEntry(path = "locations.lobby", parser = LocationParser.class, comment = "大厅位置")
    private Location lobbyLocation;
    
    @ConfigEntry(path = "locations.arena-spawn", parser = LocationParser.class, comment = "竞技场出生点")
    private Location arenaSpawn;
    
    public GameConfig() {
        super("config/game.yml");
    }
    
    /**
     * 获取格式化的消息
     */
    public String getFormattedStartMessage() {
        return ChatColor.translateAlternateColorCodes('&', gameStartMessage);
    }
    
    public String getFormattedEndMessage(String winner) {
        return ChatColor.translateAlternateColorCodes('&', 
            gameEndMessage.replace("%winner%", winner));
    }
}
```

生成的 `game.yml`:

```yaml
general:
  # 是否启用游戏功能
  enabled: true
  # 调试模式
  debug-mode: false

rules:
  # 最大玩家数
  max-players: 16
  # 游戏时长（秒）
  game-duration: 600
  # 允许的装备套件
  allowed-kits:
    - warrior
    - archer
    - mage

rewards:
  # 胜者金币奖励
  winner-coins: 100
  # 参与奖励
  participation-coins: 10
  # 击杀奖励
  kill-bonus: 5

messages:
  # 游戏开始消息
  game-start: '&a游戏开始！'
  # 游戏结束消息
  game-end: '&c游戏结束！胜者: %winner%'
  # 玩家加入消息
  player-join: '&e%player% 加入了游戏'

locations:
  # 大厅位置
  lobby:
    world: world
    x: 0.0
    y: 64.0
    z: 0.0
  # 竞技场出生点
  arena-spawn:
    world: world
    x: 100.0
    y: 64.0
    z: 100.0
```

### 使用配置

```java
@Service
public class GameService {
    
    @Autowired
    private GameConfig config;
    
    public void startGame() {
        if (!config.isEnabled()) {
            return;
        }
        
        // 检查玩家数
        if (players.size() > config.getMaxPlayers()) {
            // 人数超限
        }
        
        // 广播消息
        Bukkit.broadcastMessage(config.getFormattedStartMessage());
        
        // 传送到竞技场
        Location spawn = config.getArenaSpawn();
        if (spawn != null) {
            for (Player player : players) {
                player.teleport(spawn);
            }
        }
    }
    
    public void endGame(Player winner) {
        // 发放奖励
        economy.deposit(winner, config.getWinnerCoins());
        
        // 广播结果
        Bukkit.broadcastMessage(config.getFormattedEndMessage(winner.getName()));
    }
}
```

---

## 最佳实践

### 推荐做法

1. **为所有配置项提供默认值**
   ```java
   private int timeout = 30; // 有默认值
   ```

2. **使用有意义的路径结构**
   ```java
   @ConfigEntry(path = "economy.starting-balance")  // 好
   @ConfigEntry(path = "sb")  // 不好
   ```

3. **添加注释说明配置用途**
   ```java
   @ConfigEntry(path = "rate", comment = "经验倍率，1.0 为正常，2.0 为双倍")
   ```

4. **验证配置值**
   ```java
   @PostConstruct
   public void validate() {
       if (maxPlayers < 1) {
           maxPlayers = 1;
           logger.warn("max-players 不能小于 1，已重置为 1");
       }
   }
   ```

5. **分离不同类型的配置**
   ```
   config/
   ├── settings.yml      # 通用设置
   ├── messages.yml      # 消息文本
   └── rewards.yml       # 奖励配置
   ```

### 避免做法

1. **避免在配置中存储运行时数据**
   - 配置用于静态设置，不是数据存储

2. **避免过深的嵌套结构**
   ```yaml
   # 不推荐 - 过深嵌套
   a:
     b:
       c:
         d:
           value: 1
   ```

3. **避免硬编码配置路径**
   ```java
   // 不好
   config.get("some.path");
   
   // 好 - 使用配置实体
   myConfig.getSomePath();
   ```

---

> **下一步**: 阅读 [GUI 系统](./GUI_SYSTEM.md) 了解背包界面开发
