# 架构设计

本文档详细描述 UltiTools-API 的系统架构、核心组件及其交互关系。

---

## 目录

- [整体架构](#整体架构)
- [核心组件](#核心组件)
- [生命周期](#生命周期)
- [模块关系图](#模块关系图)
- [分层设计](#分层设计)

---

## 整体架构

UltiTools-API 采用 **分层架构** + **IoC 容器** 设计，核心理念是通过注解驱动实现自动化组件注册和依赖注入。

```
┌─────────────────────────────────────────────────────────────────┐
│                     UltiTools Plugin Modules                     │
│                    (用户开发的功能模块)                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ @CmdExecutor │  │   @Service   │  │ @EventListener│          │
│  │   Commands   │  │   Services   │  │   Listeners   │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         │                 │                 │                    │
│         └────────────────┼────────────────┘                    │
│                          │                                       │
│                          ▼                                       │
│              ┌───────────────────────┐                          │
│              │    SimpleContainer    │                          │
│              │    (IoC 容器)          │                          │
│              └───────────┬───────────┘                          │
│                          │                                       │
├──────────────────────────┼──────────────────────────────────────┤
│                          ▼                                       │
│              ┌───────────────────────┐                          │
│              │    UltiToolsPlugin    │                          │
│              │    (模块基类)          │                          │
│              └───────────┬───────────┘                          │
│                          │                                       │
├──────────────────────────┼──────────────────────────────────────┤
│                     Core Layer                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │ Plugin   │  │ Command  │  │ Listener │  │ Config   │        │
│  │ Manager  │  │ Manager  │  │ Manager  │  │ Manager  │        │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘        │
│       │             │             │             │                │
│       └─────────────┴──────┬──────┴─────────────┘                │
│                            │                                     │
│                            ▼                                     │
│              ┌───────────────────────┐                          │
│              │      UltiTools        │                          │
│              │     (主插件类)         │                          │
│              └───────────┬───────────┘                          │
│                          │                                       │
├──────────────────────────┼──────────────────────────────────────┤
│                          ▼                                       │
│                    Bukkit/Spigot API                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心组件

### 1. UltiTools (主入口)

**位置**: `com.ultikits.ultitools.UltiTools`

作为 Bukkit 插件的主入口类，负责：

| 职责 | 描述 |
|------|------|
| 初始化管理器 | 创建并初始化所有核心 Manager |
| 加载依赖 | 动态加载 lib 目录下的依赖 JAR |
| 数据源配置 | 初始化 MySQL/SQLite/JSON 数据存储 |
| 语言加载 | 加载国际化语言文件 |
| WebSocket 连接 | 与 UltiPanel 建立 WebSocket 连接 |

```java
public final class UltiTools extends JavaPlugin implements Localized {
    @Getter private PluginManager pluginManager;
    @Getter private CommandManager commandManager;
    @Getter private ListenerManager listenerManager;
    @Getter private ConfigManager configManager;
    @Getter private DataStore dataStore;
    @Getter private ServerMonitorManager serverMonitorManager;
    // ...
}
```

### 2. SimpleContainer (IoC 容器)

**位置**: `com.ultikits.ultitools.context.SimpleContainer`

轻量级依赖注入容器，核心功能：

| 功能 | 方法 |
|------|------|
| 单例注册 | `registerSingleton(name, instance)` |
| 供应商注册 | `registerSupplier(name, supplier)` |
| 类型注册 | `registerType(class, instance)` |
| Bean 获取 | `getBean(name)` / `getBean(class)` |
| 依赖注入 | 自动处理 `@Autowired` 字段 |
| 生命周期 | `@PostConstruct` / `@PreDestroy` |
| 三级缓存 | 解决循环依赖问题 |

**作用域支持**:
- `SINGLETON`: 单例模式（默认）
- `PROTOTYPE`: 原型模式，每次获取创建新实例

#### 三级缓存机制 (6.2.0 新增)

SimpleContainer 实现了类似 Spring 的三级缓存，支持 setter 注入循环依赖解决：

| 缓存级别 | 名称 | 存储内容 |
|---------|------|---------|
| 一级缓存 | singletonObjects | 完全初始化的 Bean 实例 |
| 二级缓存 | earlySingletonObjects | 早期暴露的 Bean 实例 |
| 三级缓存 | singletonFactories | Bean 工厂 |

```java
// 循环依赖现在可以正常工作
@Service
public class ServiceA {
    @Autowired
    private ServiceB serviceB;  // 通过三级缓存获取早期引用
}

@Service
public class ServiceB {
    @Autowired
    private ServiceA serviceA;
}
```

### 3. ContextHolder (6.2.0 新增)

**位置**: `com.ultikits.ultitools.context.ContextHolder`

静态持有器，用于在无法依赖注入的地方访问容器：

```java
// 获取 Bean
MyService service = ContextHolder.getBean(MyService.class);
String service = ContextHolder.getBean("myService");
```

### 4. UltiToolsPlugin (模块基类)

**位置**: `com.ultikits.ultitools.abstracts.UltiToolsPlugin`

所有 UltiTools 模块的基类，提供：

```java
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    // 元数据
    @Getter private final String version;
    @Getter private final String pluginName;
    @Getter private final List<String> authors;
    
    // IoC 容器
    @Getter @Setter private SimpleContainer context;
    
    // 抽象方法
    public abstract boolean registerSelf();
    public abstract void unregisterSelf();
    
    // 工具方法
    public <T extends AbstractDataEntity> DataOperator<T> getDataOperator(Class<T> clazz);
    public String i18n(String key);
}
```

### 5. PluginManager (模块管理器)

**位置**: `com.ultikits.ultitools.manager.PluginManager`

负责发现、加载和管理 UltiTools 模块：

| 方法 | 描述 |
|------|------|
| `init(classLoader)` | 扫描并加载所有模块 JAR |
| `register(pluginClass)` | 注册单个模块 |
| `unregister(plugin)` | 卸载模块 |
| `getPluginList()` | 获取已加载模块列表 |

**加载流程**:
1. 扫描 `plugins/UltiTools/plugins/` 目录
2. 加载 JAR 中的主类（继承 `UltiToolsPlugin`）
3. **依赖排序** - 使用 PluginDependencyResolver 进行拓扑排序 (6.2.0 新增)
4. 初始化 IoC 容器
5. 自动注册组件（命令、监听器、配置）
6. 调用 `registerSelf()`

### 6. PluginDependencyResolver (6.2.0 新增)

**位置**: `com.ultikits.ultitools.manager.PluginDependencyResolver`

使用 **Kahn 算法** 进行插件依赖的拓扑排序，确保正确的加载顺序：

```java
@PluginDependency(
    depends = {"CorePlugin"},         // 硬依赖（必须存在）
    softDepends = {"OptionalPlugin"}, // 软依赖（可选）
    loadBefore = {"LatePlugin"}       // 先于某插件加载
)
public class MyPlugin extends UltiToolsPlugin { }
```

| 异常类型 | 说明 |
|---------|------|
| `CircularDependencyException` | 检测到循环依赖 |
| `MissingDependencyException` | 缺少必要的硬依赖 |

### 7. TransactionManager (6.2.0 新增)

**位置**: `com.ultikits.ultitools.interfaces.TransactionManager`

提供声明式事务支持，`DataSourceTransactionManager` 实现基于 ThreadLocal：

```java
TransactionManager txManager = new DataSourceTransactionManager(dataSource);
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

支持嵌套事务 (depth 计数) 和事务隔离级别设置。

### 8. ConfigChangeListener (6.2.0 新增)

**位置**: `com.ultikits.ultitools.interfaces.ConfigChangeListener`

配置变更监听器，支持配置热重载时的回调通知：

```java
config.addChangeListener(cfg -> {
    logger.info("Config reloaded!");
    refreshCache();
});
```

支持多监听器，单个监听器异常不影响其他监听器执行。

### 9. AOP 系统 (6.2.0 新增)

**位置**: `com.ultikits.ultitools.aop/`

面向切面编程支持，提供声明式事务和异常处理：

| 组件 | 描述 |
|------|------|
| `MethodInterceptor` | 方法拦截器接口 |
| `AopAdvisor` | 通知器，决定哪些方法被拦截 |
| `CglibProxyFactory` | CGLIB 代理创建工厂 |
| `AopProxyBeanPostProcessor` | 与 IoC 容器集成的后处理器 |
| `TransactionInterceptor` | 事务管理拦截器 |
| `ExceptionInterceptor` | 异常处理拦截器 |

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder(Order order) {
        // 自动事务管理
    }

    @ExceptionCatch(defaultValue = "empty")
    public List<Order> getOrders() {
        // 异常时返回空列表
    }
}
```

> **详细文档**: 阅读 [AOP 系统](./modules/AOP_SYSTEM.md) 了解完整的 AOP 和事务管理功能

### 10. SecurityPolicy (安全策略)

**位置**: `com.ultikits.ultitools.utils.SecurityPolicy`

核心安全策略类，提供多层安全验证：

| 功能 | 描述 |
|------|------|
| 危险类黑名单 | 19 个危险类（ProcessBuilder、Runtime 等） |
| 危险包前缀 | 9 个危险包（java.lang.reflect 等） |
| 可疑关键字 | 16 个关键字检测 |
| 信任包白名单 | 6 个信任包前缀 |
| 文件结构验证 | 防止 Zip 炸弹攻击 |

```java
// 验证类名安全性
if (SecurityPolicy.isSafeClassName(className)) {
    // 允许加载
}

// 验证参数类型安全性
if (SecurityPolicy.isSafeParameterType(parameterClass)) {
    // 允许注入
}
```

> **详细文档**: 阅读 [安全系统](./modules/SECURITY.md) 了解完整的安全机制

---

## 生命周期

### 主插件生命周期

```
onLoad()
    │
    ├─→ 保存默认配置
    ├─→ 下载必需依赖
    │
onEnable()
    │
    ├─→ 加载依赖 JAR
    ├─→ 初始化依赖管理器
    ├─→ 加载语言文件
    ├─→ 初始化 ConfigManager
    ├─→ 初始化 DataStore (MySQL/SQLite)
    ├─→ 初始化 PluginManager
    │     └─→ 加载所有模块
    ├─→ 初始化 WebSocket 管理器
    ├─→ 启动 Metrics
    │
onDisable()
    │
    ├─→ 卸载所有模块
    ├─→ 断开 WebSocket
    ├─→ 关闭数据存储
```

### 模块生命周期

```
模块 JAR 发现
    │
    ▼
主类实例化 (反射)
    │
    ▼
IoC 容器初始化
    │
    ├─→ 组件扫描 (@ComponentScan)
    ├─→ Bean 创建与依赖注入
    ├─→ @PostConstruct 调用
    │
    ▼
自动注册
    │
    ├─→ 命令注册 (@CmdExecutor)
    ├─→ 监听器注册 (@EventListener)
    ├─→ 配置加载 (@ConfigEntity)
    │
    ▼
registerSelf() 调用
    │
    ▼
模块运行中
    │
    ▼
unregisterSelf() 调用
    │
    ▼
@PreDestroy 调用
    │
    ▼
资源清理
```

---

## 模块关系图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              注解层                                      │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐           │
│  │@CmdExecutor│ │  @Service  │ │@EventListener│ │@ConfigEntity│           │
│  │ @CmdMapping│ │ @Component │ │             │ │@ConfigEntry │           │
│  │ @CmdParam  │ │ @Autowired │ │             │ │             │           │
│  │            │ │@Transactional│             │ │             │           │
│  │            │ │@ExceptionCatch│            │ │             │           │
│  └─────┬──────┘ └─────┬──────┘ └─────┬───────┘ └─────┬───────┘           │
│        │              │              │               │                    │
│        ▼              ▼              ▼               ▼                    │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                     ComponentScanner                             │    │
│  └─────────────────────────────┬───────────────────────────────────┘    │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           安全层 (6.2.0 新增)                             │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                      SecurityPolicy                              │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │    │
│  │  │ 危险类黑名单  │  │ 危险包前缀    │  │ 信任包白名单  │          │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘          │    │
│  │                      ClassLoaderUtils                            │    │
│  └─────────────────────────────┬───────────────────────────────────┘    │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           IoC 容器层                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                      SimpleContainer                             │    │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │    │
│  │  │  singletons  │  │   suppliers  │  │ typeMappings │          │    │
│  │  └──────────────┘  └──────────────┘  └──────────────┘          │    │
│  └─────────────────────────────┬───────────────────────────────────┘    │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           AOP 层 (6.2.0 新增)                            │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                   AopProxyBeanPostProcessor                      │    │
│  │  ┌──────────────────┐  ┌──────────────────┐                     │    │
│  │  │TransactionInterceptor│ │ExceptionInterceptor│                     │    │
│  │  │  (@Transactional) │  │  (@ExceptionCatch) │                     │    │
│  │  └──────────────────┘  └──────────────────┘                     │    │
│  │                    CglibProxyFactory                             │    │
│  └─────────────────────────────┬───────────────────────────────────┘    │
└────────────────────────────────┼────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            管理层                                        │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐        │
│  │CommandMgr  │  │ListenerMgr │  │ ConfigMgr  │  │ DataStore  │        │
│  │            │  │            │  │            │  │   Mgr      │        │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘        │
│        │              │              │               │                   │
│        └──────────────┴──────────────┴───────────────┘                   │
│                                │                                         │
│                                ▼                                         │
│                        PluginManager                                     │
└─────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           数据层                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                        DataStore                                  │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │ MySQLStore  │  │ SQLiteStore │  │  JsonStore  │              │   │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘              │   │
│  │         │                │                │                       │   │
│  │         └────────────────┴────────────────┘                       │   │
│  │                          │                                        │   │
│  │                          ▼                                        │   │
│  │                   DataOperator<T>                                 │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 分层设计

### 层次划分

| 层次 | 包路径 | 职责 |
|------|--------|------|
| **表示层** | `abstracts/gui/` | 背包 GUI 界面 |
| **命令层** | `abstracts/command/` | 命令处理与参数解析 |
| **业务层** | `services/` | 业务逻辑服务 |
| **数据访问层** | `interfaces/impl/data/` | 数据持久化操作 |
| **基础设施层** | `manager/`, `context/` | 核心框架支撑 |

### 依赖方向

```
    表示层 (GUI)
        │
        ▼
    命令层 (Commands)
        │
        ▼
    业务层 (Services)
        │
        ▼
    数据访问层 (DataOperator)
        │
        ▼
    基础设施层 (Container, Managers)
```

**原则**: 上层依赖下层，下层不依赖上层。

---

## WebSocket 集成架构

UltiTools 通过 WebSocket 与 UltiPanel 管理面板集成：

```
┌─────────────────────────────────────────────────────────────────┐
│                    Minecraft Server                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    UltiTools                              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │   │
│  │  │ServerMonitor │  │CommandExec   │  │ LogStream    │   │   │
│  │  │   Manager    │  │   Manager    │  │   Manager    │   │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │   │
│  │         │                 │                 │            │   │
│  │         └─────────────────┼─────────────────┘            │   │
│  │                           │                               │   │
│  │                           ▼                               │   │
│  │            ┌──────────────────────────┐                  │   │
│  │            │ UltiPanelWebSocketClient │                  │   │
│  │            └────────────┬─────────────┘                  │   │
│  └─────────────────────────┼───────────────────────────────┘   │
└────────────────────────────┼────────────────────────────────────┘
                             │ WebSocket
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     UltiPanel API                                │
│                  (Cloudflare Workers)                            │
│                             │                                    │
│                             ▼                                    │
│                    UltiPanel Frontend                            │
│                       (Vue 3)                                    │
└─────────────────────────────────────────────────────────────────┘
```

### 消息类型

| 类型 | 方向 | 描述 |
|------|------|------|
| `server_status` | Server → Panel | 服务器状态（TPS、内存、玩家数） |
| `execute_command` | Panel → Server | 远程执行命令 |
| `log_stream` | Server → Panel | 实时日志流 |
| `file_operation` | 双向 | 文件读写操作 |

---

> **下一步**: 阅读 [IoC 容器](./modules/IOC_CONTAINER.md) 了解依赖注入详情
