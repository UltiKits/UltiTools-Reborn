# UltiTools 抽象类架构重构指南

## 概述

本次重构对 UltiTools-Reborn 的抽象类进行了全面升级，应用了多种设计模式来提升代码的健壮性、可扩展性和可维护性。

## 重构动机

### 原有问题

1. **AbstractCommandExecutor (1122行)** - 严重违反单一职责原则
   - 命令解析、验证、执行、Tab补全全部混在一个类中
   - 难以测试和维护
   - 扩展新验证逻辑需要修改基类

2. **AbstractDataEntity** - 类型不安全
   - 使用 `Object` 类型存储 ID
   - 缺少生命周期钩子
   - 无法进行类型检查

3. **GUI 类 (PagingPage/OkCancelPage)** - 代码重复
   - 工具栏设置代码重复
   - 缺少一致的扩展点
   - 没有使用设计模式

4. **AbstractCommendExecutor** - 拼写错误的遗留类
   - 应该早已移除

## 新架构

### 命令系统 (`abstracts.command`)

#### 设计模式
- **责任链模式 (Chain of Responsibility)** - 命令验证管道
- **策略模式 (Strategy)** - 类型解析器
- **上下文对象模式 (Context Object)** - 命令执行上下文

#### 核心组件

```
command/
├── CommandContext.java          # 不可变的命令上下文
├── BaseCommandExecutor.java     # 新的基础命令执行器 (~400行)
├── parser/
│   ├── TypeParser.java         # 类型解析器接口
│   ├── TypeParseException.java # 解析异常
│   └── TypeParserRegistry.java # 解析器注册表
└── validation/
    ├── CommandValidator.java   # 验证器接口
    ├── ValidatorChain.java     # 验证链管理器
    └── validators/
        ├── SenderTypeValidator.java    # 发送者类型验证 (order: 100)
        ├── PermissionValidator.java    # 权限验证 (order: 200)
        ├── UsageLockValidator.java     # 并发锁验证 (order: 250)
        └── CooldownValidator.java      # 冷却时间验证 (order: 300)
```

#### 迁移示例

```java
// 旧代码 (已废弃)
@CmdExecutor(alias = {"mycmd"}, permission = "myplugin.cmd")
public class MyCommand extends AbstractCommandExecutor {
    @CmdMapping(format = "action <param>")
    public void doAction(@CmdSender Player player, @CmdParam("param") String param) {
        // ...
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        // ...
    }
}

// 新代码
@CmdExecutor(alias = {"mycmd"}, permission = "myplugin.cmd")
public class MyCommand extends BaseCommandExecutor {
    @CmdMapping(format = "action <param>")
    public void doAction(@CmdSender Player player, @CmdParam("param") String param) {
        // ...
    }
    
    @Override
    protected void handleHelp(CommandSender sender) {
        // ...
    }
    
    // 可选：添加自定义验证器
    @Override
    protected void setupValidatorChain(ValidatorChain.Builder builder) {
        super.setupValidatorChain(builder);
        builder.addValidator(new MyCustomValidator());
    }
}
```

#### 自定义类型解析器

```java
public class LocationParser implements TypeParser<Location> {
    @Override
    public Class<Location> getPrimaryType() {
        return Location.class;
    }
    
    @Override
    public List<Class<?>> getSupportedTypes() {
        return Arrays.asList(Location.class);
    }
    
    @Override
    public Location parse(String value) throws TypeParseException {
        try {
            String[] parts = value.split(",");
            World world = Bukkit.getWorld(parts[0]);
            return new Location(world, 
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]));
        } catch (Exception e) {
            throw new TypeParseException("Invalid location format: " + value, e);
        }
    }
}

// 注册
TypeParserRegistry.getInstance().register(new LocationParser());
```

#### 自定义验证器

```java
public class VIPValidator implements CommandValidator {
    @Override
    public ValidationResult validate(CommandContext context) {
        if (!context.isPlayer()) {
            return ValidationResult.success();
        }
        if (isVIP(context.getPlayer())) {
            return ValidationResult.success();
        }
        return ValidationResult.failure("需要 VIP 权限！");
    }
    
    @Override
    public int getOrder() {
        return 150; // 在发送者验证之后，权限验证之前
    }
}
```

---

### 数据实体 (`abstracts.data`)

#### 设计改进

- **泛型 ID** - 类型安全的 ID 处理
- **生命周期钩子** - 持久化事件回调
- **审计实体** - 自动记录创建/更新信息

#### 数据实体核心组件

```
data/
├── BaseDataEntity.java         # 泛型基础实体
└── AuditableDataEntity.java    # 带审计字段的实体
```

#### 数据实体迁移示例

```java
// 旧代码 (已废弃)
@Table("player_data")
public class PlayerData extends AbstractDataEntity {
    @Column("name")
    private String name;
    // id 是 Object 类型
}

// 新代码 - 使用 UUID 作为 ID
@Table("player_data")
public class PlayerData extends BaseDataEntity<UUID> {
    @Column("name")
    private String name;
    
    @Override
    public void onCreate() {
        // 首次保存前调用
        if (getId() == null) {
            setId(UUID.randomUUID());
        }
    }
    
    @Override
    public void validate() throws IllegalStateException {
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("Name cannot be empty");
        }
    }
}

// 或者使用带审计字段的实体
@Table("player_data")
public class PlayerData extends AuditableDataEntity<UUID> {
    @Column("name")
    private String name;
    // 自动包含: createdAt, updatedAt, createdBy, updatedBy
}
```

#### 生命周期钩子

| 方法 | 触发时机 |
|------|---------|
| `onCreate()` | 首次插入前 |
| `onUpdate()` | 更新前 |
| `onDelete()` | 删除前 |
| `onLoad()` | 从存储加载后 |
| `validate()` | 任何持久化操作前 |

---

### GUI 系统 (`abstracts.gui`)

#### GUI 设计模式

- **模板方法模式 (Template Method)** - 统一的页面结构
- **建造者模式 (Builder)** - 快速创建确认对话框

#### GUI 核心组件

```
gui/
├── BaseInventoryPage.java      # GUI 基类
├── BasePaginationPage.java     # 分页 GUI
└── BaseConfirmationPage.java   # 确认对话框
```

#### GUI 迁移示例

```java
// 旧代码 - 分页 (已废弃)
public class MyPage extends PagingPage {
    @Override
    public List<Icon> setAllItems() {
        return myItems;
    }
}

// 新代码 - 分页
public class MyPage extends BasePaginationPage {
    @Override
    protected List<Icon> provideItems() {
        return myItems;
    }
    
    @Override
    protected void onPageChange(int oldPage, int newPage) {
        // 页面切换时的回调
    }
}
```

```java
// 旧代码 - 确认对话框 (已废弃)
public class MyDialog extends OkCancelPage {
    public MyDialog(Player player) {
        super(player, "标题");
    }
    
    @Override
    public void onOk(InventoryClickEvent event) {
        doSomething();
    }
    
    @Override
    public void onCancel(InventoryClickEvent event) {
        player.closeInventory();
    }
}

// 新代码 - 使用 Builder
BaseConfirmationPage.builder(player)
    .title("确认操作")
    .message("确定要执行此操作吗？")
    .confirmText("确认")
    .cancelText("取消")
    .onConfirm(event -> doSomething())
    .onCancel(event -> {})
    .dangerous(true)  // 红色确认按钮
    .open();
```

#### 模板方法钩子

| 方法 | 用途 |
|------|------|
| `setupContent()` | 设置页面主要内容 |
| `setupBottomToolbar()` | 设置底部工具栏 |
| `onPageOpen()` | 页面打开时回调 |
| `onPageClose()` | 页面关闭时回调 |

---

## 废弃类清单

| 废弃类 | 替代类 | 版本 |
|--------|--------|------|
| `AbstractCommandExecutor` | `BaseCommandExecutor` | 6.2.0 |
| `AbstractDataEntity` | `BaseDataEntity<ID>` | 6.2.0 |
| `PagingPage` | `BasePaginationPage` | 6.2.0 |
| `OkCancelPage` | `BaseConfirmationPage` | 6.2.0 |
| `AbstractCommendExecutor` | 已删除 (拼写错误) | - |

## 架构对比

### 命令系统

```
旧架构:
┌─────────────────────────────────────────┐
│     AbstractCommandExecutor (1122行)    │
│  - 解析、验证、执行、Tab补全混合        │
│  - 难以测试                             │
│  - 难以扩展                             │
└─────────────────────────────────────────┘

新架构:
┌───────────────────┐
│ CommandContext    │ ← 不可变上下文
└───────────────────┘
         ↓
┌───────────────────┐
│ ValidatorChain    │ ← 责任链验证
│ ├─ SenderType     │
│ ├─ Permission     │
│ ├─ UsageLock      │
│ └─ Cooldown       │
└───────────────────┘
         ↓
┌───────────────────┐
│TypeParserRegistry │ ← 策略模式解析
└───────────────────┘
         ↓
┌───────────────────┐
│BaseCommandExecutor│ ← 精简的执行器
│    (~400行)       │
└───────────────────┘
```

### 数据实体

```
旧架构:
┌─────────────────────────┐
│  AbstractDataEntity     │
│  - Object id (不安全)   │
│  - 无生命周期钩子       │
└─────────────────────────┘

新架构:
┌─────────────────────────┐
│ BaseDataEntity<ID>      │
│ - 泛型 ID (类型安全)    │
│ - 生命周期钩子          │
│ - 验证方法              │
└─────────────────────────┘
           ↓
┌─────────────────────────┐
│ AuditableDataEntity<ID> │
│ - 审计字段              │
│ - 自动时间戳            │
└─────────────────────────┘
```

## 测试建议

新架构更容易进行单元测试：

```java
// 测试类型解析器
@Test
void testIntegerParser() {
    TypeParser<?> parser = TypeParserRegistry.getInstance().getParser(Integer.class);
    assertEquals(123, parser.parse("123"));
}

// 测试验证器
@Test
void testPermissionValidator() {
    CommandContext context = CommandContext.builder()
        .sender(mockPlayer)
        .build();
    
    PermissionValidator validator = new PermissionValidator("test.permission", false);
    ValidationResult result = validator.validate(context);
    
    assertFalse(result.isSuccess());
}

// 测试验证链
@Test
void testValidatorChain() {
    ValidatorChain chain = ValidatorChain.builder()
        .addValidator(new SenderTypeValidator(CmdTarget.CmdTargetType.PLAYER))
        .addValidator(new PermissionValidator("test.perm", false))
        .build();
    
    // 使用 console sender
    CommandContext context = CommandContext.builder()
        .sender(Bukkit.getConsoleSender())
        .build();
    
    ValidationResult result = chain.validate(context);
    assertFalse(result.isSuccess());
    assertEquals("This command can only be executed by a player.", result.getMessage());
}
```

## 后续计划

1. ~~为所有新类添加完整的单元测试~~ ✅ 已完成
2. 更新现有插件模块使用新的抽象类
3. 在下一个大版本中移除废弃类
4. ~~添加更多内置类型解析器 (如 Location, World, ItemStack)~~ ✅ 已完成
5. ~~考虑添加异步命令执行支持~~ ✅ 已完成

### 6.2.0 版本新增内容

#### 新增类型解析器

- `WorldParser` - 解析世界名称
- `GameModeParser` - 解析游戏模式（支持名称和数字）
- `LocationParser` - 解析位置（支持多种格式）
- `EnchantmentParser` - 解析附魔类型

#### 新增异步命令支持

- `@AsyncCommand` 注解 - 标记命令为异步执行
  - `showProcessing()` - 显示处理中提示
  - `processingMessageKey()` - 自定义 i18n 消息键
  - `timeout()` - 超时设置（秒）

---

## 6.2.0 模块化重构 (P0/P1/P2)

### P0 阶段：核心组件增强

#### 统一异常体系

新增统一的异常处理体系，所有异常携带错误码：

```
exceptions/
├── UltiToolsException.java      # 抽象基类
├── ErrorCode.java               # 错误码枚举
├── ContainerException.java      # IoC 容器异常 (2000-2999)
├── DataAccessException.java     # 数据访问异常 (3000-3999)
├── CommandException.java        # 命令异常 (4000-4999)
├── ConfigurationException.java  # 配置异常 (5000-5999)
└── PluginModuleException.java   # 插件模块异常 (6000-6999)
```

使用示例：

```java
throw new DataAccessException(ErrorCode.ENTITY_NOT_FOUND, 
    "User not found with id: " + id);

// 工厂方法
throw ContainerException.circularDependency("ServiceA");
throw DataAccessException.entityNotFound(User.class, userId);
```

#### 事务管理器

新增 `TransactionManager` 接口和 `DataSourceTransactionManager` 实现：

```java
public interface TransactionManager {
    void begin();
    void commit();
    void rollback();
    boolean hasActiveTransaction();
    int getTransactionDepth();  // 支持嵌套事务
}
```

特性：
- 基于 ThreadLocal 的线程安全实现
- 支持嵌套事务（depth 计数）
- 支持事务隔离级别设置

#### ContextHolder

静态持有器，用于在无法依赖注入的场景访问 IoC 容器：

```java
ContextHolder.setContext(container);  // 启动时
MyService service = ContextHolder.getBean(MyService.class);
ContextHolder.clear();  // 关闭时
```

### P1 阶段：命令与 WebSocket

#### Tab 补全系统

新增策略模式的 Tab 补全系统：

```
commands/tabcomplete/
├── TabCompleter.java              # 补全策略接口
├── TabCompletionContext.java      # 补全上下文
├── TabCompletionManager.java      # 中央管理器
├── OnlinePlayersCompleter.java    # 在线玩家
├── WorldsCompleter.java           # 世界名称
├── MaterialsCompleter.java        # 材料/方块/物品
├── StaticSuggestionsCompleter.java # 静态列表
└── MethodInvocationCompleter.java  # 方法调用
```

内置快捷键：`@players`, `@worlds`, `@materials`, `@blocks`, `@items`, `@boolean`, `@toggle`

#### WebSocket 重连机制

新增指数退避重连策略和消息处理器注册表：

```
websocket/
├── ReconnectStrategy.java              # 重连策略接口
├── ExponentialBackoffStrategy.java     # 指数退避 (5s→5min)
├── WebSocketMessageHandler.java        # 消息处理器接口
├── MessageHandlerRegistry.java         # 处理器注册表
└── handlers/
    ├── PongHandler.java                # 心跳响应
    ├── ServerStatusHandler.java        # 服务器状态
    ├── CommandExecutionHandler.java    # 远程命令
    ├── LogStreamHandler.java           # 日志流
    └── FileOperationHandler.java       # 文件操作
```

#### 插件依赖排序

新增 `@PluginDependency` 注解和 `PluginDependencyResolver`（Kahn 算法拓扑排序）：

```java
@PluginDependency(
    depends = {"CorePlugin"},
    softDepends = {"OptionalPlugin"},
    loadBefore = {"LatePlugin"}
)
public class MyPlugin extends UltiToolsPlugin { }
```

#### 三级缓存

SimpleContainer 新增三级缓存，解决 setter 注入循环依赖：

| 缓存级别 | 名称 | 存储内容 |
|---------|------|---------|
| 一级缓存 | singletonObjects | 完全初始化的 Bean |
| 二级缓存 | earlySingletonObjects | 早期暴露的 Bean |
| 三级缓存 | singletonFactories | Bean 工厂 |

### P2 阶段：配置与测试

#### 配置变更监听

新增 `ConfigChangeListener` 接口和 `AbstractConfigEntity` 支持：

```java
config.addChangeListener(cfg -> {
    logger.info("Config reloaded!");
    refreshCache();
});
config.reload();  // 手动触发重载
```

特性：异常隔离，单个监听器异常不影响其他监听器。

#### JaCoCo 覆盖率

Maven 集成 JaCoCo 插件，生成代码覆盖率报告：

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
</plugin>
```

报告位置：`target/site/jacoco/index.html`

#### 测试覆盖

- 2696 个测试用例
- 配置监听器测试 (16 cases)
- 全栈集成测试 (11 cases)
- 插件依赖排序测试
- 三级缓存测试

---

## 单元测试覆盖

- `TypeParserRegistryTest` - 类型解析器注册表测试
- `GameModeParserTest` - 游戏模式解析器测试
- `ValidationChainTest` - 验证链测试
- `CommandContextTest` - 命令上下文测试
- `DataEntityTest` - 数据实体测试
- `ConfigChangeListenerTest` - 配置变更监听测试 (6.2.0)
- `SimpleContainerThreeLevelCacheTest` - 三级缓存测试 (6.2.0)
- `PluginDependencyResolverTest` - 插件依赖排序测试 (6.2.0)
- `FullStackIntegrationTest` - 全栈集成测试 (6.2.0)

## 版本兼容性

- 所有废弃类保持向后兼容
- 新类从 6.2.0 版本开始提供
- 建议在 7.0.0 之前完成迁移
- 废弃类将在 7.0.0 中移除
