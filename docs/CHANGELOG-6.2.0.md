# UltiTools-API 6.2.0 版本更新日志

> 发布日期：2025年1月  
> 此版本包含重大架构重构，引入多项新功能并改进测试覆盖率。

---

## 📋 目录

1. [版本概述](#版本概述)
2. [新增功能](#新增功能)
3. [命令系统重构](#命令系统重构)
4. [数据实体重构](#数据实体重构)
5. [GUI 系统重构](#gui-系统重构)
6. [类型解析器](#类型解析器)
7. [废弃的 API](#废弃的-api)
8. [测试覆盖率](#测试覆盖率)
9. [迁移指南](#迁移指南)
10. [已知问题](#已知问题)

---

## 版本概述

### 主要变更

| 类别 | 变更内容 |
|------|----------|
| 🏗️ 架构 | 命令验证链 (Chain of Responsibility) |
| 🏗️ 架构 | 数据实体泛型 ID 支持 |
| 🏗️ 架构 | GUI 模板方法模式 |
| ✨ 新功能 | `@AsyncCommand` 异步命令注解 |
| ✨ 新功能 | 新增类型解析器 (World, Location, Enchantment, GameMode) |
| 🧪 测试 | 2655+ 单元测试全覆盖 |
| ⚠️ 废弃 | 旧版命令/数据/GUI 类标记废弃 |

### 设计模式应用

- **责任链模式** - 命令验证器链
- **策略模式** - 类型解析器
- **模板方法模式** - GUI 页面
- **构建器模式** - 确认对话框
- **上下文对象模式** - 命令执行上下文

---

## 新增功能

### 1. `@AsyncCommand` 异步命令注解

标记命令方法为异步执行，适用于耗时操作（I/O、网络请求等）。

**位置**: `com.ultikits.ultitools.annotations.command.AsyncCommand`

```java
@CmdMapping(format = "backup")
@AsyncCommand
public void backupWorld(@CmdSender Player player) {
    // 异步执行 - 适合 I/O 操作
    performBackup();
    
    // 同步回主线程进行 Bukkit 操作
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("备份完成!");
    });
}
```

**属性**:

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `showProcessing` | boolean | `true` | 是否显示处理中消息 |
| `processingMessageKey` | String | `""` | 自定义 i18n 消息键 |
| `timeout` | int | `30` | 超时时间（秒），0 表示无超时 |

### 2. 命令验证链

新的验证器系统，支持自定义验证逻辑：

```java
// 自定义验证器
public class VIPValidator implements CommandValidator {
    @Override
    public ValidationResult validate(CommandContext context) {
        if (!context.isPlayer()) {
            return ValidationResult.success();
        }
        if (isVIP(context.getPlayer())) {
            return ValidationResult.success();
        }
        return ValidationResult.failure("需要 VIP 权限!");
    }
    
    @Override
    public int getOrder() {
        return 150; // 在发送者类型检查之后，权限检查之前
    }
}

// 添加到命令
myCommand.addValidator(new VIPValidator());
```

### 3. 泛型数据实体

支持类型安全的 ID 字段和生命周期钩子：

```java
public class PlayerData extends BaseDataEntity<UUID> {
    private String playerName;
    
    @Override
    public void onCreate() {
        // 首次保存前调用
    }
    
    @Override
    public void onUpdate() {
        // 更新前调用
    }
}
```

---

## 命令系统重构

### 新包结构

```
com.ultikits.ultitools.abstracts.command/
├── CommandContext.java           # 不可变命令上下文
├── BaseCommandExecutor.java      # 新基础命令执行器
├── parser/                       # 类型解析器
│   ├── TypeParser.java
│   ├── TypeParseException.java
│   ├── TypeParserRegistry.java
│   ├── WorldParser.java          # 新增
│   ├── LocationParser.java       # 新增
│   ├── EnchantmentParser.java    # 新增
│   └── GameModeParser.java       # 新增
└── validation/                   # 验证器
    ├── CommandValidator.java
    ├── ValidatorChain.java
    └── validators/
        ├── SenderTypeValidator.java   # order: 100
        ├── PermissionValidator.java   # order: 200
        ├── UsageLockValidator.java    # order: 250
        └── CooldownValidator.java     # order: 300
```

### 验证器执行顺序

```
请求 → SenderTypeValidator(100) → PermissionValidator(200) 
     → UsageLockValidator(250) → CooldownValidator(300) → 执行命令
```

### 内置验证器

| 验证器 | Order | 功能 |
|--------|-------|------|
| `SenderTypeValidator` | 100 | 验证发送者类型 (玩家/控制台) |
| `PermissionValidator` | 200 | 验证权限和 OP 状态 |
| `UsageLockValidator` | 250 | 防止并发执行 |
| `CooldownValidator` | 300 | 命令冷却时间管理 |

---

## 数据实体重构

### 新包结构

```
com.ultikits.ultitools.abstracts.data/
├── BaseDataEntity.java           # 泛型 ID + 生命周期钩子
├── AuditableDataEntity.java      # 审计字段 (创建/更新时间)
└── package-info.java
```

### BaseDataEntity 生命周期钩子

| 方法 | 调用时机 |
|------|----------|
| `onCreate()` | 首次插入前 |
| `onUpdate()` | 更新前 |
| `onDelete()` | 删除前 |
| `onLoad()` | 从存储加载后 |
| `validate()` | 持久化操作前 |

### AuditableDataEntity 审计字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `createdAt` | LocalDateTime | 创建时间 |
| `updatedAt` | LocalDateTime | 更新时间 |
| `createdBy` | UUID | 创建者 |
| `updatedBy` | UUID | 更新者 |

```java
// 使用示例
public class ServerLog extends AuditableDataEntity<Long> {
    @Column("action")
    private String action;
    
    @Column("details")
    private String details;
}

// 设置当前用户上下文
AuditableDataEntity.setCurrentUser(player.getUniqueId());
try {
    logEntity.onCreate(); // 自动设置 createdAt, createdBy
    dataOperator.insert(logEntity);
} finally {
    AuditableDataEntity.clearCurrentUser();
}
```

---

## GUI 系统重构

### 新包结构

```
com.ultikits.ultitools.abstracts.gui/
├── BaseInventoryPage.java        # 模板方法基类
├── BasePaginationPage.java       # 分页支持
├── BaseConfirmationPage.java     # 确认对话框 + Builder
└── package-info.java
```

### BaseInventoryPage 模板方法

```java
public abstract class BaseInventoryPage extends Gui {
    
    // 模板方法 - 子类不应重写
    @Override
    public final void onOpen(InventoryOpenEvent event) {
        if (showBottomToolbar) {
            setupBottomToolbar();
        }
        setupContent(event);  // 抽象方法
        afterSetup(event);    // 钩子方法
    }
    
    // 子类必须实现
    protected abstract void setupContent(InventoryOpenEvent event);
    
    // 可选钩子
    protected void afterSetup(InventoryOpenEvent event) { }
}
```

### BaseConfirmationPage Builder

```java
// Builder 模式创建确认对话框
BaseConfirmationPage.builder(player)
    .id("delete-confirm")
    .title("确认删除?")
    .rows(3)
    .okButton("删除")
    .cancelButton("取消")
    .content(event -> {
        // 设置对话框内容
    })
    .onConfirm(event -> {
        // 确认时执行
        deleteItem();
    })
    .onCancel(event -> {
        // 取消时执行
    })
    .open();
```

### BasePaginationPage 分页

```java
public class ItemListPage extends BasePaginationPage {
    
    public ItemListPage(Player player) {
        super(player, "items", "物品列表", 6);
    }
    
    @Override
    protected List<Icon> provideItems() {
        return itemService.getAll().stream()
            .map(this::createIcon)
            .collect(Collectors.toList());
    }
}
```

---

## 类型解析器

### 新增解析器

| 解析器 | 目标类型 | 输入格式示例 |
|--------|----------|--------------|
| `WorldParser` | `World` | `world`, `world_nether` |
| `LocationParser` | `Location` | `100,64,-200` 或 `world,100,64,-200,90,45` |
| `EnchantmentParser` | `Enchantment` | `SHARPNESS`, `sharpness`, `SHARP` (部分匹配) |
| `GameModeParser` | `GameMode` | `SURVIVAL`, `0`, `creative` |

### 使用示例

```java
@CmdMapping(format = "tp <location>")
public void teleport(@CmdSender Player player, @CmdParam("location") Location loc) {
    player.teleport(loc);
}

// 支持的格式:
// /cmd tp 100,64,-200           (使用默认世界)
// /cmd tp world,100,64,-200     (指定世界)
// /cmd tp world,100,64,-200,90,45 (带旋转)
```

### 自定义解析器注册

```java
TypeParserRegistry.getInstance().register(new MyCustomParser());
```

---

## 废弃的 API

### 已废弃类 (将在 7.0.0 移除)

| 废弃类 | 替代类 |
|--------|--------|
| `AbstractCommandExecutor` | `BaseCommandExecutor` |
| `AbstractCommendExecutor` | `BaseCommandExecutor` |
| `AbstractDataEntity` | `BaseDataEntity<ID>` |
| `PagingPage` | `BasePaginationPage` |
| `OkCancelPage` | `BaseConfirmationPage` |

### 废弃注解

```java
@Deprecated(since = "6.2.0", forRemoval = true)
```

---

## 测试覆盖率

### 测试统计

```
Tests run: 2655, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

### 新增测试类

| 测试类 | 覆盖内容 |
|--------|----------|
| `BaseCommandExecutorTest` | 命令执行器核心逻辑 |
| `CommandContextTest` | 命令上下文不可变性 |
| `ValidationChainTest` | 验证链执行顺序 |
| `LocationParserTest` | Location 解析各种格式 |
| `WorldParserTest` | World 解析 |
| `EnchantmentParserTest` | Enchantment 解析 |
| `GameModeParserTest` | GameMode 解析 |
| `TypeParserRegistryTest` | 解析器注册表 |
| `DataEntityTest` | 数据实体生命周期 |
| `BaseInventoryPageTest` | GUI 基类 |
| `BasePaginationPageTest` | 分页页面 |
| `BaseConfirmationPageTest` | 确认对话框 |

### Mock 策略

所有测试使用 Mockito 进行依赖模拟:

```java
@ExtendWith(MockitoExtension.class)
class MyTest {
    @Mock private Player mockPlayer;
    @Mock private UltiTools mockUltiTools;
    
    private MockedStatic<UltiTools> ultiToolsMock;
    
    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
    }
    
    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }
}
```

---

## 迁移指南

### 命令迁移

```java
// Before (6.1.x)
public class MyCommand extends AbstractCommandExecutor {
    @Override
    protected void handleHelp(CommandSender sender) { }
}

// After (6.2.0)
public class MyCommand extends BaseCommandExecutor {
    @Override
    protected void handleHelp(CommandSender sender) { }
    
    // 可选: 自定义验证链
    @Override
    protected void setupValidatorChain(ValidatorChain.Builder builder) {
        super.setupValidatorChain(builder);
        builder.add(new MyValidator());
    }
}
```

### 数据实体迁移

```java
// Before (6.1.x)
public class MyData extends AbstractDataEntity {
    // id 是 Object 类型
}

// After (6.2.0)
public class MyData extends BaseDataEntity<UUID> {
    // id 是类型安全的 UUID
    
    @Override
    public void onCreate() {
        // 生命周期钩子
    }
}
```

### GUI 迁移

```java
// Before (6.1.x)
public class MyPage extends PagingPage {
    @Override
    public List<Icon> setAllItems() { }
}

// After (6.2.0)
public class MyPage extends BasePaginationPage {
    @Override
    protected List<Icon> provideItems() { }
}

// Before (6.1.x)
public class MyDialog extends OkCancelPage {
    @Override
    public void onOk(InventoryClickEvent e) { }
}

// After (6.2.0)
BaseConfirmationPage.builder(player)
    .onConfirm(e -> { })
    .open();
```

---

## 已知问题

1. **Null Safety 警告**: `BaseInventoryPage` 构造函数中存在 null 安全警告，不影响运行
2. **异步命令**: `@AsyncCommand` 需要手动处理主线程同步
3. **向后兼容**: 废弃 API 仍然可用，但会在 7.0.0 移除

---

## 相关文档

- [ARCHITECTURE_REFACTORING_GUIDE.md](./ARCHITECTURE_REFACTORING_GUIDE.md) - 架构重构详细指南
- [API_MIGRATION_6.2.0.md](./API_MIGRATION_6.2.0.md) - API 迁移详细步骤
- [Javadoc](../target/apidocs/) - 完整 API 文档
