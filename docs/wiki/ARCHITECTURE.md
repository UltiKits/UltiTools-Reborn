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

**作用域支持**:
- `SINGLETON`: 单例模式（默认）
- `PROTOTYPE`: 原型模式，每次获取创建新实例

### 3. UltiToolsPlugin (模块基类)

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

### 4. PluginManager (模块管理器)

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
3. 初始化 IoC 容器
4. 自动注册组件（命令、监听器、配置）
5. 调用 `registerSelf()`

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
