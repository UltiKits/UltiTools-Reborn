# UltiTools-API 6.2.0 版本更新日志

> 发布日期：2025年1月  
> 此版本包含重大架构重构，引入多项新功能并改进测试覆盖率。

---

## 📋 目录

1. [版本概述](#版本概述)
2. [新增功能](#新增功能)
3. [IoC 容器增强](#ioc-容器增强)
4. [异常处理系统](#异常处理系统)
5. [事务管理](#事务管理)
6. [Tab 补全系统](#tab-补全系统)
7. [WebSocket 重连机制](#websocket-重连机制)
8. [插件依赖管理](#插件依赖管理)
9. [配置变更监听](#配置变更监听)
10. [命令系统重构](#命令系统重构)
11. [数据实体重构](#数据实体重构)
12. [GUI 系统重构](#gui-系统重构)
13. [类型解析器](#类型解析器)
14. [废弃的 API](#废弃的-api)
15. [测试覆盖率](#测试覆盖率)
16. [迁移指南](#迁移指南)
17. [已知问题](#已知问题)

---

## 版本概述

### 主要变更

| 类别 | 变更内容 |
|------|----------|
| 🏗️ 架构 | IoC 容器三级缓存 - 解决循环依赖 |
| 🏗️ 架构 | 统一异常体系 - ErrorCode 错误码 |
| 🏗️ 架构 | 事务管理器 - 声明式事务支持 |
| 🏗️ 架构 | 命令验证链 (Chain of Responsibility) |
| 🏗️ 架构 | 数据实体泛型 ID 支持 |
| 🏗️ 架构 | GUI 模板方法模式 |
| ✨ 新功能 | Tab 补全系统 - 策略模式 |
| ✨ 新功能 | WebSocket 指数退避重连 |
| ✨ 新功能 | 插件依赖拓扑排序 (Kahn 算法) |
| ✨ 新功能 | 配置变更监听器 |
| ✨ 新功能 | `@AsyncCommand` 异步命令注解 |
| ✨ 新功能 | 新增类型解析器 (World, Location, Enchantment, GameMode) |
| 🧪 测试 | 2696 单元/集成测试全覆盖 |
| 🧪 测试 | JaCoCo 代码覆盖率报告 |
| ⚠️ 废弃 | 旧版命令/数据/GUI 类标记废弃 |

### 设计模式应用

- **三级缓存** - 循环依赖解决
- **责任链模式** - 命令验证器链
- **策略模式** - 类型解析器、Tab 补全、重连策略
- **观察者模式** - 配置变更监听器
- **模板方法模式** - GUI 页面
- **构建器模式** - 确认对话框
- **上下文对象模式** - 命令执行上下文
- **拓扑排序** - 插件依赖加载顺序

---

## IoC 容器增强

### 三级缓存循环依赖解决

**位置**: `com.ultikits.ultitools.context.SimpleContainer`

SimpleContainer 现在实现了类似 Spring 的三级缓存机制，支持通过 setter 注入解决循环依赖：

| 缓存级别 | 名称 | 存储内容 |
|---------|------|---------|
| 一级缓存 | singletonObjects | 完全初始化的 Bean 实例 |
| 二级缓存 | earlySingletonObjects | 早期暴露的 Bean 实例（尚未完成属性注入） |
| 三级缓存 | singletonFactories | Bean 工厂（ObjectFactory） |

```java
// 循环依赖示例 - 现在可以正常工作
@Service
public class ServiceA {
    @Autowired
    private ServiceB serviceB;
}

@Service
public class ServiceB {
    @Autowired
    private ServiceA serviceA;  // 通过三级缓存解决
}
```

### 新增 API

```java
// 获取 singleton（支持早期引用）
Object bean = container.getSingleton("beanName", true);

// 直接添加 singleton
container.addSingleton("beanName", instance);

// 添加 singleton 工厂
container.addSingletonFactory("beanName", () -> createBean());
```

### ContextHolder 全局访问

**位置**: `com.ultikits.ultitools.context.ContextHolder`

静态持有器，用于在无法进行依赖注入的地方访问容器：

```java
// 设置全局上下文（启动时调用）
ContextHolder.setContext(container);

// 获取 Bean
MyService service = ContextHolder.getBean(MyService.class);
MyService service = ContextHolder.getBean("myService");

// 清除（关闭时调用）
ContextHolder.clear();
```

---

## 异常处理系统

### 统一异常体系

**位置**: `com.ultikits.ultitools.exceptions`

引入统一的异常体系，所有异常都包含错误码：

```
UltiToolsException (抽象基类)
├── ContainerException      # IoC 容器异常 (2000-2999)
├── DataAccessException     # 数据访问异常 (3000-3999)
├── CommandException        # 命令异常 (4000-4999)
├── ConfigurationException  # 配置异常 (5000-5999)
└── PluginModuleException   # 插件模块异常 (6000-6999)
```

### ErrorCode 错误码

```java
public enum ErrorCode {
    // 容器错误 (2000-2999)
    BEAN_CREATION_FAILED(2000, "Bean creation failed"),
    BEAN_NOT_FOUND(2001, "Bean not found"),
    CIRCULAR_DEPENDENCY(2002, "Circular dependency detected"),
    DUPLICATE_BEAN(2004, "Duplicate bean definition"),
    
    // 数据访问错误 (3000-3999)
    ENTITY_NOT_FOUND(3001, "Entity not found"),
    CONNECTION_FAILED(3005, "Database connection failed"),
    TRANSACTION_FAILED(3006, "Transaction failed"),
    
    // 命令错误 (4000-4999)
    COMMAND_PERMISSION_DENIED(4002, "Permission denied"),
    COMMAND_COOLDOWN_ACTIVE(4003, "Command on cooldown"),
    
    // 配置错误 (5000-5999)
    CONFIG_LOAD_FAILED(5001, "Configuration load failed"),
    CONFIG_VALIDATION_FAILED(5004, "Configuration validation failed"),
    
    // 插件错误 (6000-6999)
    PLUGIN_CIRCULAR_DEPENDENCY(6004, "Plugin circular dependency")
}
```

### 使用示例

```java
// 抛出带错误码的异常
throw new DataAccessException(ErrorCode.ENTITY_NOT_FOUND, 
    "User with ID " + id + " not found");

// 使用工厂方法
throw ContainerException.beanNotFound(MyService.class);
throw DataAccessException.entityNotFound(User.class, userId);
throw CommandException.permissionDenied("admin.command");

// 获取格式化消息
catch (UltiToolsException e) {
    logger.error(e.getFormattedMessage()); // [ULTI-3001] User not found...
}
```

---

## 事务管理

### TransactionManager 接口

**位置**: `com.ultikits.ultitools.interfaces.TransactionManager`

提供声明式事务支持：

```java
public interface TransactionManager {
    void begin();
    void commit();
    void rollback();
    boolean hasActiveTransaction();
    int getTransactionDepth();  // 支持嵌套事务
    void setIsolationLevel(int level);
    void setReadOnly(boolean readOnly);
}
```

### DataSourceTransactionManager 实现

**位置**: `com.ultikits.ultitools.manager.DataSourceTransactionManager`

基于 ThreadLocal 的线程安全事务管理：

```java
TransactionManager txManager = new DataSourceTransactionManager(dataSource);

// 手动事务管理
txManager.begin();
try {
    userRepository.save(user);
    orderRepository.save(order);
    txManager.commit();
} catch (Exception e) {
    txManager.rollback();
    throw e;
}
```

### 嵌套事务支持

```java
txManager.begin();  // depth = 1
    txManager.begin();  // depth = 2 (嵌套)
    txManager.commit(); // depth = 1
txManager.commit();     // depth = 0, 真正提交
```

---

## Tab 补全系统

### 策略模式补全器

**位置**: `com.ultikits.ultitools.commands.tabcomplete`

```
tabcomplete/
├── TabCompleter.java              # 补全器接口
├── TabCompletionContext.java      # 补全上下文
├── TabCompletionManager.java      # 中央管理器
├── OnlinePlayersCompleter.java    # 在线玩家补全
├── WorldsCompleter.java           # 世界名称补全
├── MaterialsCompleter.java        # 材料/方块/物品补全
├── StaticSuggestionsCompleter.java # 静态列表补全
└── MethodInvocationCompleter.java  # 方法调用补全
```

### 内置补全器

| 补全器 | 快捷键 | 说明 |
|--------|--------|------|
| OnlinePlayersCompleter | `@players` | 在线玩家名 |
| WorldsCompleter | `@worlds` | 服务器世界名 |
| MaterialsCompleter | `@materials` | 所有材料 |
| MaterialsCompleter.blocksOnly() | `@blocks` | 仅方块 |
| MaterialsCompleter.itemsOnly() | `@items` | 仅物品 |
| StaticSuggestionsCompleter.forBoolean() | `@boolean` | true/false |
| StaticSuggestionsCompleter.forToggle() | `@toggle` | on/off/enable/disable |

### 使用示例

```java
// 获取管理器
TabCompletionManager manager = TabCompletionManager.getInstance();

// 注册自定义补全器
manager.register("@gamemodes", new StaticSuggestionsCompleter(
    "survival", "creative", "adventure", "spectator"
));

// 在命令中使用
@CmdMapping(format = "teleport <@players> <@worlds>")
public void teleport(Player sender, String target, String world) { }
```

---

## WebSocket 重连机制

### 指数退避策略

**位置**: `com.ultikits.ultitools.websocket`

```
websocket/
├── ReconnectStrategy.java              # 重连策略接口
├── ExponentialBackoffStrategy.java     # 指数退避实现
├── WebSocketMessageHandler.java        # 消息处理器接口
├── MessageHandlerRegistry.java         # 处理器注册表
└── handlers/
    ├── PongHandler.java                # 心跳响应
    ├── ServerStatusHandler.java        # 服务器状态请求
    ├── CommandExecutionHandler.java    # 远程命令执行
    ├── LogStreamHandler.java           # 日志流控制
    └── FileOperationHandler.java       # 文件操作
```

### ExponentialBackoffStrategy

延迟时间指数增长：5s → 10s → 20s → 40s → ... → 最大 5 分钟

```java
// 默认策略（无限重试）
ReconnectStrategy strategy = new ExponentialBackoffStrategy();

// 限制最大尝试次数
ReconnectStrategy strategy = ExponentialBackoffStrategy.withMaxAttempts(10);

// 自定义配置
ReconnectStrategy strategy = ExponentialBackoffStrategy.builder()
    .initialDelay(3000)      // 初始延迟 3 秒
    .maxDelay(180000)        // 最大延迟 3 分钟
    .multiplier(1.5)         // 倍数 1.5
    .maxAttempts(20)         // 最多 20 次
    .build();

// 使用
long delay = strategy.getNextDelay();  // 获取下次延迟
strategy.reset();                       // 连接成功后重置
if (strategy.shouldContinue()) { ... } // 是否继续重试
```

### 消息处理器注册

```java
MessageHandlerRegistry registry = new MessageHandlerRegistry();

// 注册处理器
registry.register(new PongHandler());
registry.register(new ServerStatusHandler(monitorManager));

// 注册全局处理器（接收所有消息）
registry.registerGlobal(loggingHandler);

// 分发消息
registry.dispatch(jsonMessage);
```

---

## 插件依赖管理

### @PluginDependency 注解

**位置**: `com.ultikits.ultitools.annotations.PluginDependency`

声明插件间的依赖关系：

```java
@PluginDependency(
    depends = {"CorePlugin", "DatabasePlugin"},  // 硬依赖（必须存在）
    softDepends = {"OptionalPlugin"},            // 软依赖（可选）
    loadBefore = {"LatePlugin"}                  // 在指定插件前加载
)
@UltiToolsModule(...)
public class MyPlugin extends UltiToolsPlugin { }
```

### PluginDependencyResolver

**位置**: `com.ultikits.ultitools.manager.PluginDependencyResolver`

使用 **Kahn 算法** 进行拓扑排序，确定正确的插件加载顺序：

```java
PluginDependencyResolver resolver = new PluginDependencyResolver(logger);

try {
    List<Class<? extends UltiToolsPlugin>> sorted = resolver.resolve(pluginClasses);
    // sorted 按依赖顺序排列
} catch (CircularDependencyException e) {
    // 检测到循环依赖
    logger.severe(e.getMessage());
} catch (MissingDependencyException e) {
    // 缺少必要依赖
    logger.severe(e.getMessage());
}
```

### 依赖图示例

```
输入: [PluginC, PluginB, PluginA]
依赖: PluginC depends on PluginB
      PluginB depends on PluginA

输出: [PluginA, PluginB, PluginC]  // 正确的加载顺序
```

---

## 配置变更监听

### ConfigChangeListener 接口

**位置**: `com.ultikits.ultitools.interfaces.ConfigChangeListener`

监听配置重载事件：

```java
@FunctionalInterface
public interface ConfigChangeListener {
    void onConfigReload(AbstractConfigEntity config);
}
```

### 使用示例

```java
@Service
public class CacheService {
    @Autowired
    private MyConfig config;
    
    private Map<String, Object> cache = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 注册配置变更监听
        config.addChangeListener(cfg -> {
            logger.info("Config reloaded, refreshing cache...");
            refreshCache();
        });
    }
    
    private void refreshCache() {
        cache.clear();
        // 重新加载缓存...
    }
}
```

### AbstractConfigEntity 新方法

```java
// 添加监听器
config.addChangeListener(listener);

// 移除监听器
config.removeChangeListener(listener);

// 清除所有监听器
config.clearChangeListeners();

// 获取监听器数量
int count = config.getChangeListenerCount();

// 手动触发重载
config.reload();
```

### 异常隔离

单个监听器抛出异常不会影响其他监听器的执行：

```java
config.addChangeListener(cfg -> doSomething());        // 正常执行
config.addChangeListener(cfg -> { throw new Ex(); });  // 异常被捕获
config.addChangeListener(cfg -> doOther());            // 仍然执行
```

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
