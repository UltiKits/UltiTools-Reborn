# UltiTools-API 6.2.0 迁移指南

> 本文档详细说明如何从 6.1.x 迁移到 6.2.0

---

## 目录

1. [概述](#概述)
2. [命令系统迁移](#命令系统迁移)
3. [数据实体迁移](#数据实体迁移)
4. [GUI 系统迁移](#gui-系统迁移)
5. [类型解析器使用](#类型解析器使用)
6. [常见问题](#常见问题)

---

## 概述

### 废弃时间线

| 版本 | 状态 |
|------|------|
| 6.2.0 | 旧 API 标记为 `@Deprecated` |
| 6.3.0 | 旧 API 产生编译警告 |
| 7.0.0 | 旧 API 移除 |

### 快速检查清单

- [ ] 将 `AbstractCommandExecutor` 替换为 `BaseCommandExecutor`
- [ ] 将 `AbstractDataEntity` 替换为 `BaseDataEntity<ID>`
- [ ] 将 `PagingPage` 替换为 `BasePaginationPage`
- [ ] 将 `OkCancelPage` 替换为 `BaseConfirmationPage`
- [ ] 检查自定义类型解析器是否需要更新

---

## 命令系统迁移

### 基本迁移

**最小改动** - 只需更改父类：

```java
// Before
import com.ultikits.ultitools.abstracts.AbstractCommandExecutor;

public class MyCommand extends AbstractCommandExecutor {
    // 代码无需改动
}

// After
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;

public class MyCommand extends BaseCommandExecutor {
    // 代码无需改动
}
```

### 利用新验证链

**进阶用法** - 添加自定义验证逻辑：

```java
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.ValidatorChain;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;

public class MyCommand extends BaseCommandExecutor {
    
    @Override
    protected ValidatorChain buildValidatorChain() {
        return ValidatorChain.builder()
            .withDefaults(this)  // 包含默认验证器
            .add(new LevelRequirementValidator(10))  // 自定义验证器
            .build();
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("使用帮助...");
    }
}

// 自定义验证器
public class LevelRequirementValidator implements CommandValidator {
    private final int requiredLevel;
    
    public LevelRequirementValidator(int level) {
        this.requiredLevel = level;
    }
    
    @Override
    public ValidationResult validate(CommandContext context) {
        if (!context.isPlayer()) {
            return ValidationResult.success();
        }
        
        Player player = context.getPlayer();
        if (player.getLevel() >= requiredLevel) {
            return ValidationResult.success();
        }
        
        return ValidationResult.failure(
            String.format("需要 %d 级才能使用此命令!", requiredLevel),
            "command.error.level_required"
        );
    }
    
    @Override
    public int getOrder() {
        return 150;  // 在 SenderType(100) 之后, Permission(200) 之前
    }
}
```

### 使用 @AsyncCommand

**异步命令** - 对于耗时操作：

```java
import com.ultikits.ultitools.annotations.command.AsyncCommand;

public class BackupCommand extends BaseCommandExecutor {
    
    @CmdMapping(format = "backup <worldName>")
    @AsyncCommand(timeout = 60, showProcessing = true)
    public void backup(@CmdSender Player player, @CmdParam("worldName") String worldName) {
        // 这个方法在异步线程执行
        File backupFile = createBackup(worldName);
        
        // 需要回到主线程才能操作 Bukkit API
        Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
            player.sendMessage("备份完成: " + backupFile.getName());
        });
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage("/backup <世界名> - 备份指定世界");
    }
}
```

### 命令上下文

**使用 CommandContext** - 获取命令执行信息：

```java
// 在验证器中
@Override
public ValidationResult validate(CommandContext context) {
    // 获取发送者
    CommandSender sender = context.getSender();
    
    // 检查是否是玩家
    if (context.isPlayer()) {
        Player player = context.getPlayer();
    }
    
    // 获取原始参数
    String[] args = context.getRawArgs();
    
    // 获取匹配的方法
    Method method = context.getMatchedMethod();
    
    // 获取解析后的参数
    Map<String, String[]> params = context.getParsedParams();
}
```

---

## 数据实体迁移

### 基本迁移

```java
// Before
import com.ultikits.ultitools.abstracts.AbstractDataEntity;

@Table("player_data")
public class PlayerData extends AbstractDataEntity {
    @Column("player_id")
    private String playerId;  // 字符串存储 UUID
    
    @Column("balance")
    private double balance;
}

// After
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;

@Table("player_data")
public class PlayerData extends BaseDataEntity<UUID> {
    // ID 字段继承自父类，类型安全
    
    @Column("balance")
    private double balance;
    
    // 可选：使用生命周期钩子
    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化默认值
        if (this.balance == 0) {
            this.balance = 100.0;
        }
    }
    
    @Override
    public boolean validate() {
        return balance >= 0;
    }
}
```

### 使用审计实体

```java
import com.ultikits.ultitools.abstracts.data.AuditableDataEntity;

@Table("server_logs")
public class ServerLog extends AuditableDataEntity<Long> {
    
    @Column("action")
    private String action;
    
    @Column("details")
    private String details;
    
    // 自动包含: createdAt, updatedAt, createdBy, updatedBy
}

// 使用示例
public void logAction(Player player, String action, String details) {
    ServerLog log = new ServerLog();
    log.setAction(action);
    log.setDetails(details);
    
    // 设置当前用户上下文
    AuditableDataEntity.setCurrentUser(player.getUniqueId());
    try {
        log.onCreate();  // 自动填充审计字段
        dataOperator.insert(log);
    } finally {
        AuditableDataEntity.clearCurrentUser();
    }
}
```

### ID 类型选择

| ID 类型 | 适用场景 |
|---------|----------|
| `UUID` | 玩家数据、分布式系统 |
| `Long` | 自增主键、日志记录 |
| `Integer` | 简单计数、枚举映射 |
| `String` | 自定义标识符 |

```java
// UUID ID
public class PlayerData extends BaseDataEntity<UUID> { }

// Long ID (自增)
public class LogEntry extends BaseDataEntity<Long> { }

// String ID (自定义)
public class ConfigEntry extends BaseDataEntity<String> { }
```

---

## GUI 系统迁移

### 分页页面迁移

```java
// Before
import com.ultikits.ultitools.abstracts.guis.PagingPage;

public class ItemListPage extends PagingPage {
    
    public ItemListPage(Player player) {
        super(player, "items", "物品列表", 6);
    }
    
    @Override
    public List<Icon> setAllItems() {
        return items.stream()
            .map(this::createIcon)
            .collect(Collectors.toList());
    }
}

// After
import com.ultikits.ultitools.abstracts.gui.BasePaginationPage;

public class ItemListPage extends BasePaginationPage {
    
    public ItemListPage(Player player) {
        super(player, "items", "物品列表", 6);
    }
    
    @Override
    protected List<Icon> provideItems() {
        return items.stream()
            .map(this::createIcon)
            .collect(Collectors.toList());
    }
    
    // 可选：自定义导航按钮
    @Override
    protected Icon createPreviousButton() {
        Icon icon = super.createPreviousButton();
        icon.setLore("点击查看上一页");
        return icon;
    }
}
```

### 确认对话框迁移

```java
// Before
import com.ultikits.ultitools.abstracts.guis.OkCancelPage;

public class DeleteConfirmPage extends OkCancelPage {
    private final Item itemToDelete;
    
    public DeleteConfirmPage(Player player, Item item) {
        super(player, "delete", "确认删除", 3);
        this.itemToDelete = item;
    }
    
    @Override
    public void onOk(InventoryClickEvent event) {
        itemService.delete(itemToDelete);
        player.sendMessage("删除成功!");
    }
    
    @Override
    public void onCancel(InventoryClickEvent event) {
        player.sendMessage("取消删除");
    }
}

// After - 方式1：继承
import com.ultikits.ultitools.abstracts.gui.BaseConfirmationPage;

public class DeleteConfirmPage extends BaseConfirmationPage {
    private final Item itemToDelete;
    
    public DeleteConfirmPage(Player player, Item item) {
        super(player, "delete", "确认删除", 3);
        this.itemToDelete = item;
    }
    
    @Override
    protected void onConfirm(InventoryClickEvent event) {
        itemService.delete(itemToDelete);
        player.sendMessage("删除成功!");
    }
    
    @Override
    protected void onCancel(InventoryClickEvent event) {
        player.sendMessage("取消删除");
    }
    
    // 可选：自定义按钮名称
    @Override
    protected String getOkButtonName() {
        return "确认删除";
    }
}

// After - 方式2：Builder (推荐用于简单场景)
BaseConfirmationPage.builder(player)
    .id("delete")
    .title("确认删除?")
    .rows(3)
    .okButton("确认删除")
    .cancelButton("取消")
    .content(event -> {
        // 可选：添加显示内容
    })
    .onConfirm(event -> {
        itemService.delete(itemToDelete);
        player.sendMessage("删除成功!");
    })
    .onCancel(event -> {
        player.sendMessage("取消删除");
    })
    .open();
```

### 基础页面迁移

```java
// 如果只需要基础 GUI 功能
import com.ultikits.ultitools.abstracts.gui.BaseInventoryPage;

public class MyCustomPage extends BaseInventoryPage {
    
    public MyCustomPage(Player player) {
        super(player, "custom", "自定义页面", 4);
    }
    
    @Override
    protected void setupContent(InventoryOpenEvent event) {
        // 设置内容
        addItem(10, new Icon(Material.DIAMOND));
        addItem(16, new Icon(Material.EMERALD));
    }
    
    // 可选：禁用底部工具栏
    public MyCustomPage withoutToolbar() {
        setShowBottomToolbar(false);
        return this;
    }
}
```

---

## 类型解析器使用

### 内置解析器

| 类型 | 解析器 | 示例输入 |
|------|--------|----------|
| `World` | `WorldParser` | `world`, `world_nether` |
| `Location` | `LocationParser` | `100,64,-200`, `world,100,64,-200,90,45` |
| `Enchantment` | `EnchantmentParser` | `SHARPNESS`, `sharp` |
| `GameMode` | `GameMode` | `SURVIVAL`, `0`, `creative` |

### 命令中使用

```java
@CmdMapping(format = "teleport <location>")
public void teleport(@CmdSender Player player, @CmdParam("location") Location loc) {
    player.teleport(loc);
}

@CmdMapping(format = "gamemode <mode>")
public void setGameMode(@CmdSender Player player, @CmdParam("mode") GameMode mode) {
    player.setGameMode(mode);
}

@CmdMapping(format = "enchant <enchantment> <level>")
public void enchant(
    @CmdSender Player player,
    @CmdParam("enchantment") Enchantment enchant,
    @CmdParam("level") int level
) {
    ItemStack item = player.getInventory().getItemInMainHand();
    item.addEnchantment(enchant, level);
}
```

### 自定义解析器

```java
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;

public class ColorParser implements TypeParser<Color> {
    
    @Override
    public Class<Color> getPrimaryType() {
        return Color.class;
    }
    
    @Override
    public Color parse(String value) throws TypeParseException {
        if (value == null || value.isEmpty()) {
            throw new TypeParseException("颜色值不能为空");
        }
        
        // 支持 RGB 格式: 255,128,0
        if (value.contains(",")) {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                throw new TypeParseException("RGB 格式错误: " + value);
            }
            try {
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                return Color.fromRGB(r, g, b);
            } catch (NumberFormatException e) {
                throw new TypeParseException("无效的 RGB 值: " + value);
            }
        }
        
        // 支持十六进制: #FF8000
        if (value.startsWith("#")) {
            try {
                int rgb = Integer.parseInt(value.substring(1), 16);
                return Color.fromRGB(rgb);
            } catch (NumberFormatException e) {
                throw new TypeParseException("无效的十六进制颜色: " + value);
            }
        }
        
        throw new TypeParseException("无法解析颜色: " + value);
    }
}

// 注册
TypeParserRegistry.getInstance().register(new ColorParser());

// 使用
@CmdMapping(format = "setcolor <color>")
public void setColor(@CmdSender Player player, @CmdParam("color") Color color) {
    // 使用 color
}
```

---

## 常见问题

### Q: 迁移后编译报错怎么办？

**A:** 检查以下常见问题：

1. **导入包错误** - 确保导入新包路径
2. **方法签名变更** - `setAllItems()` → `provideItems()`
3. **构造函数参数** - 检查父类构造函数

### Q: 旧代码还能用吗？

**A:** 可以，但会有 `@Deprecated` 警告。建议在 7.0.0 之前完成迁移。

### Q: 如何禁用某个验证器？

**A:**

```java
@Override
protected ValidatorChain buildValidatorChain() {
    return ValidatorChain.builder()
        .add(new SenderTypeValidator())  // 只添加需要的
        .add(new PermissionValidator())
        // 不添加 CooldownValidator
        .build();
}
```

### Q: 如何在异步命令中使用 Bukkit API？

**A:** 使用调度器回到主线程：

```java
@AsyncCommand
public void asyncMethod(Player player) {
    // 异步工作
    String result = doHeavyWork();
    
    // 回到主线程
    Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
        player.sendMessage(result);
    });
}
```

### Q: 新旧代码能混用吗？

**A:** 可以，但不推荐。新旧系统独立工作，但维护两套代码会增加复杂度。

---

## 相关文档

- [CHANGELOG-6.2.0.md](./CHANGELOG-6.2.0.md) - 完整更新日志
- [ARCHITECTURE_REFACTORING_GUIDE.md](./ARCHITECTURE_REFACTORING_GUIDE.md) - 架构设计详解
