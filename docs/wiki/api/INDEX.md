# API 参考索引

本文档提供 UltiTools-API 的核心接口和类的快速参考。

---

## 目录

- [核心类](#核心类)
- [抽象基类](#抽象基类)
- [注解](#注解)
- [接口](#接口)
- [实体类](#实体类)
- [管理器](#管理器)

---

## 核心类

### UltiTools

**包**: `com.ultikits.ultitools`

主插件入口类，提供全局访问点。

```java
// 获取实例
UltiTools instance = UltiTools.getInstance();

// 主要方法
PluginManager getPluginManager()        // 获取模块管理器
CommandManager getCommandManager()       // 获取命令管理器
ListenerManager getListenerManager()     // 获取监听器管理器
ConfigManager getConfigManager()         // 获取配置管理器
DataStore getDataStore()                 // 获取数据存储
Language getLanguage()                   // 获取语言对象
String i18n(String key)                  // 国际化翻译

// 静态方法
static int getPluginVersion()            // 获取插件版本号
static YamlConfiguration getEnv()        // 获取环境配置
```

### SimpleContainer

**包**: `com.ultikits.ultitools.context`

IoC 依赖注入容器。

```java
// Bean 注册
void registerSingleton(String name, Object instance)
void registerSupplier(String name, Supplier<Object> supplier)
<T> void registerType(Class<T> type, T instance)

// Bean 获取
Object getBean(String name)
<T> T getBean(Class<T> type)
<T> T getBean(String name, Class<T> type)

// Bean 管理
boolean containsBean(String name)
String[] getBeanNamesForType(Class<?> type)
void destroyBean(String name)

// 生命周期
void start()                             // 启动容器
void stop()                              // 停止容器
```

---

## 抽象基类

### UltiToolsPlugin

**包**: `com.ultikits.ultitools.abstracts`

所有 UltiTools 模块的基类。

```java
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    
    // 元数据（自动从 plugin.yml 读取）
    String getVersion()
    String getPluginName()
    List<String> getAuthors()
    
    // 生命周期方法（子类实现）
    abstract boolean registerSelf()      // 注册时调用
    abstract void unregisterSelf()       // 卸载时调用
    void reloadSelf()                    // 重载时调用
    
    // IoC 容器
    SimpleContainer getContext()
    void setContext(SimpleContainer context)
    
    // 数据操作
    <T extends AbstractDataEntity> DataOperator<T> getDataOperator(Class<T> clazz)
    
    // 配置管理
    <T extends AbstractConfigEntity> T getConfig(Class<T> configClass)
    void registerConfig(AbstractConfigEntity config)
    File getConfigFile(String path)
    String getConfigFolder()
    
    // 国际化
    String i18n(String key)
    String getLanguageCode()
    
    // 资源
    InputStream getResource(String path)
    void saveResource(String path, boolean replace)
    
    // 日志
    PluginLogger getLogger()
}
```

### AbstractCommandExecutor

**包**: `com.ultikits.ultitools.abstracts`

命令执行器基类。

```java
public abstract class AbstractCommandExecutor implements TabExecutor {
    
    // 帮助信息（子类重写）
    protected void handleHelp(CommandSender sender)
    
    // Tab 补全（子类可重写）
    protected List<String> suggest(Player player, Command command, String[] args)
    
    // 解析器注册
    <T> void registerParser(Class<T> type, Function<String, T> parser)
    Map<List<Class<?>>, Function<String, ?>> getParsers()
}
```

### AbstractDataEntity

**包**: `com.ultikits.ultitools.abstracts`

数据实体基类。

```java
@Data
public abstract class AbstractDataEntity implements Serializable {
    
    @Column("id")
    private Object id;                   // 主键 ID
}
```

### AbstractConfigEntity

**包**: `com.ultikits.ultitools.abstracts`

配置实体基类。

```java
@Getter
public abstract class AbstractConfigEntity {
    
    private final String configFilePath;
    
    // 构造函数
    protected AbstractConfigEntity(String configFilePath)
    
    // 初始化
    void init(UltiToolsPlugin plugin)
    
    // 保存
    void save() throws IOException
    
    // 获取配置对象
    YamlConfiguration getConfig()
}
```

### GUI 基类

**包**: `com.ultikits.ultitools.abstracts.gui`

```java
// 基础页面
public abstract class BaseInventoryPage extends Gui {
    protected abstract void setupContent(InventoryOpenEvent event);
    protected void afterSetup(InventoryOpenEvent event);
    void setShowBottomToolbar(boolean show);
}

// 分页页面
public abstract class BasePaginationPage<T> extends BaseInventoryPage {
    protected abstract List<T> getDataList();
    protected abstract Icon createItemIcon(T item, int index);
    protected int[] getContentSlots();
}

// 确认对话框
public abstract class BaseConfirmationPage extends BaseInventoryPage {
    protected abstract Icon getConfirmIcon();
    protected abstract Icon getCancelIcon();
    protected abstract Icon getInfoIcon();
}
```

---

## 注解

### IoC 相关

| 注解 | 目标 | 描述 |
|------|------|------|
| `@Service` | 类 | 标记服务类 |
| `@Component` | 类 | 标记组件类 |
| `@Autowired` | 字段/构造函数/方法 | 自动注入依赖 |
| `@PostConstruct` | 方法 | Bean 创建后调用 |
| `@PreDestroy` | 方法 | Bean 销毁前调用 |
| `@ComponentScan` | 类 | 指定扫描包路径 |

### 命令相关

| 注解 | 目标 | 描述 |
|------|------|------|
| `@CmdExecutor` | 类 | 标记命令执行器 |
| `@CmdTarget` | 类 | 指定命令目标类型 |
| `@CmdMapping` | 方法 | 映射命令格式 |
| `@CmdParam` | 参数 | 标记命令参数 |
| `@CmdSender` | 参数 | 标记命令发送者 |
| `@CmdSuggest` | 参数 | 指定补全建议 |
| `@CmdCD` | 方法 | 设置冷却时间 |
| `@RunAsync` | 方法 | 异步执行命令 |
| `@UsageLimit` | 方法 | 限制使用次数 |

### 数据相关

| 注解 | 目标 | 描述 |
|------|------|------|
| `@Table` | 类 | 指定数据表名 |
| `@Column` | 字段 | 映射数据列 |

### 配置相关

| 注解 | 目标 | 描述 |
|------|------|------|
| `@ConfigEntity` | 类 | 标记配置实体 |
| `@ConfigEntry` | 字段 | 映射配置项 |

### 其他

| 注解 | 目标 | 描述 |
|------|------|------|
| `@UltiToolsModule` | 类 | 标记模块主类 |
| `@EventListener` | 类 | 标记事件监听器 |
| `@EnableAutoRegister` | 类 | 启用自动注册 |
| `@I18n` | 类 | 启用国际化 |

---

## 接口

### DataOperator\<T\>

**包**: `com.ultikits.ultitools.interfaces`

数据操作接口。

```java
public interface DataOperator<T extends AbstractDataEntity> {
    
    // 存在性检查
    boolean exist(T object);
    boolean exist(WhereCondition... conditions);
    
    // 查询
    T getById(Object id);
    List<T> getAll();
    List<T> getAll(WhereCondition... conditions);
    List<T> getLike(String column, String value, LikeType likeType);
    List<T> page(int page, int size, WhereCondition... conditions);
    
    // 插入
    void insert(T obj);
    
    // 更新
    void update(String column, Object value, Object id);
    void update(T obj) throws IllegalAccessException;
    
    // 删除
    void del(WhereCondition... conditions);
    void delById(Object id);
}
```

### DataStore

**包**: `com.ultikits.ultitools.interfaces`

数据存储接口。

```java
public interface DataStore {
    
    String getStoreType();               // 获取存储类型
    
    <T extends AbstractDataEntity> DataOperator<T> getOperator(
        UltiToolsPlugin plugin, 
        Class<T> dataEntity
    );
    
    void destroyAllOperators();          // 销毁所有操作器
}
```

### IPlugin

**包**: `com.ultikits.ultitools.interfaces`

插件接口。

```java
public interface IPlugin {
    boolean registerSelf();
    void unregisterSelf();
    void reloadSelf();
}
```

### Localized

**包**: `com.ultikits.ultitools.interfaces`

国际化接口。

```java
public interface Localized {
    String i18n(String key);
}
```

---

## 实体类

### WhereCondition

**包**: `com.ultikits.ultitools.entities`

查询条件构建器。

```java
WhereCondition condition = WhereCondition.builder()
    .column("column_name")
    .value(value)
    .comparison(Comparison.EQUAL)        // 可选，默认 EQUAL
    .build();
```

### Comparison (枚举)

**包**: `com.ultikits.ultitools.entities`

```java
public enum Comparison {
    EQUAL,                               // =
    NOT_EQUAL,                           // !=
    GREATER_THAN,                        // >
    LESS_THAN,                           // <
    GREATER_THAN_OR_EQUAL,               // >=
    LESS_THAN_OR_EQUAL                   // <=
}
```

### Language

**包**: `com.ultikits.ultitools.entities`

语言对象。

```java
public class Language {
    Language(String json)
    Language(File file)
    String get(String key)
}
```

---

## 管理器

### PluginManager

**包**: `com.ultikits.ultitools.manager`

模块管理器。

```java
public class PluginManager {
    void init(ClassLoader classLoader)    // 初始化
    boolean register(Class<? extends UltiToolsPlugin> pluginClass)
    void unregister(UltiToolsPlugin plugin)
    List<UltiToolsPlugin> getPluginList()
}
```

### CommandManager

**包**: `com.ultikits.ultitools.manager`

命令管理器。

```java
public class CommandManager {
    void register(AbstractCommandExecutor executor, UltiToolsPlugin plugin)
    void unregister(String... aliases)
}
```

### ListenerManager

**包**: `com.ultikits.ultitools.manager`

监听器管理器。

```java
public class ListenerManager {
    void register(Listener listener, UltiToolsPlugin plugin)
    void unregister(Listener listener)
}
```

### ConfigManager

**包**: `com.ultikits.ultitools.manager`

配置管理器。

```java
public class ConfigManager {
    <T extends AbstractConfigEntity> void register(T config, UltiToolsPlugin plugin)
    <T extends AbstractConfigEntity> T getConfig(Class<T> configClass)
}
```

### ServerMonitorManager

**包**: `com.ultikits.ultitools.manager`

服务器监控管理器（WebSocket）。

```java
public class ServerMonitorManager {
    void setWebSocketClient(UltiPanelWebSocketClient client)
    void startMonitoring()
    void stopMonitoring()
    void sendServerStatus()
    void sendPluginList()
}
```

### LogStreamManager

**包**: `com.ultikits.ultitools.manager`

日志流管理器。

```java
public class LogStreamManager {
    static LogStreamManager getInstance()
    void initialize(UltiPanelWebSocketClient client)
    void startLogStream(String clientId, String level)
    void stopLogStream(String clientId)
    void setBatchEnabled(boolean enabled)
    void setBatchSize(int size)
}
```

---

## 工具类

### MessageUtils

**包**: `com.ultikits.ultitools.utils`

消息工具。

```java
public class MessageUtils {
    static void sendMessage(Player player, String message)
    static void broadcast(String message)
    static String colorize(String text)
}
```

### XVersionUtils

**包**: `com.ultikits.ultitools.utils`

版本兼容工具（基于 XSeries）。

```java
public class XVersionUtils {
    static ItemStack getColoredGlass(Colors color)
    static Material getMaterial(String name)
}
```

---

## 常量

### CmdTarget.CmdTargetType

```java
public enum CmdTargetType {
    PLAYER,                              // 仅玩家
    CONSOLE,                             // 仅控制台
    BOTH                                 // 两者都可
}
```

### Colors

**包**: `com.ultikits.ultitools.entities`

```java
public enum Colors {
    WHITE, ORANGE, MAGENTA, LIGHT_BLUE,
    YELLOW, LIME, PINK, GRAY,
    LIGHT_GRAY, CYAN, PURPLE, BLUE,
    BROWN, GREEN, RED, BLACK
}
```

---

> **更多信息**: 查看源码 JavaDoc 或访问 [官方文档](https://dev.ultikits.com/)
