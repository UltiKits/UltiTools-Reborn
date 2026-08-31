package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.aop.AnnotationLookupCache;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ExceptionInterceptor;
import com.ultikits.ultitools.aop.TransactionInterceptor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.api.UltiToolsAPI;
import com.ultikits.ultitools.commands.tabcomplete.MethodInvocationCompleter;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionContext;
import com.ultikits.ultitools.commands.tabcomplete.TabCompletionManager;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.exceptions.UltiToolsException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.ultikits.ultitools.manager.PluginDependencyResolver.CircularDependencyException;
import com.ultikits.ultitools.manager.PluginDependencyResolver.MissingDependencyException;
import com.ultikits.ultitools.utils.ClassLoaderUtils;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.utils.SecurityPolicy;

import lombok.Getter;

/**
 * UltiTools plugin manager.
 * <p>
 * UltiTools模块管理器
 */
public class PluginManager {
    /**
     * The name of the JVM system property that opts back into the pre-6.3.0 degraded load
     * order (D-10): every module in filesystem/classpath order, with no dependency resolution
     * at all. Modeled on Paper's own {@code -Dpaper.useLegacyPluginLoading=true} precedent -- a
     * one-shot, consumed-at-bootstrap decision, which is why it is a system property rather than
     * a reloadable {@code config.yml} key. The literal name is repeated (rather than referenced
     * only through this constant) at every call site below, so the property is legible directly
     * in the operator-facing message that names it, not only in code that reads it.
     * <br>
     * 退回 6.3.0 之前退化加载顺序（D-10）的 JVM 系统属性名：所有模块按文件系统/类路径顺序加载，
     * 完全不做依赖解析。参照 Paper 自身的 {@code -Dpaper.useLegacyPluginLoading=true} 先例——
     * 这是一个一次性的、在启动时就被消费掉的决定，因此用系统属性而非可热重载的
     * {@code config.yml} 键。下方每个调用点都重复写出字面量名称（而非只通过这个常量引用），
     * 使这个属性名在命名它的运维提示信息里本身就清晰可读，而不仅仅存在于读取它的代码中。
     */
    private static final String LEGACY_PLUGIN_LOADING_PROPERTY = "ultitools.useLegacyPluginLoading";

    @Getter
    private final List<UltiToolsPlugin> pluginList = new ArrayList<>();

    private final List<Class<? extends UltiToolsPlugin>> pluginClassList = new ArrayList<>();
    private ClassLoader classLoader;
    @Getter
    private TaskManager taskManager;
    @Getter
    private PlayerCacheManager playerCacheManager;

    /**
     * Entity class -&gt; owning plugin name, populated every time a {@link DataScope} is minted
     * (both internal modules and external plugins). Lets a refusal built by {@link
     * DataScope#refusalFor(Class)} say "belongs to module X" instead of only "not registered to
     * you" -- D-15 requires the two to be distinguishable. First registration wins; a genuine
     * collision has never been observed across this project's 21 {@code @Table} classes / 17
     * repositories (02-CONTEXT.md), so this is a defensive tie-break, not a load-bearing one.
     * <p>
     * 实体类 -&gt; 拥有它的插件名，每次铸造 {@link DataScope} 时都会填充（内部模块和外部插件均
     * 会）。使得 {@link DataScope#refusalFor(Class)} 构造的拒绝信息能说「属于模块 X」而不只是
     * 「未向你注册」——D-15 要求二者可区分。先注册者优先；本项目 21 个 {@code @Table} 类 /
     * 17 个仓库中从未观测到真实冲突（见 02-CONTEXT.md），所以这只是防御性的兜底，不是关键路径。
     */
    private final Map<Class<?>, String> entityOwnership = new ConcurrentHashMap<>();

    /**
     * External plugin data folder (canonical path) -&gt; the {@link DataScope} minted for it,
     * populated by {@link #registerExternal(ExternalPluginAdapter, Class[])}. Lets {@code
     * DataStore.getOperator(File, Class)} resolve its caller back to a scope (D-18), for the
     * default body a third-party {@code DataStore} that does not override that method inherits.
     * <p>
     * 外部插件数据文件夹（规范路径）-&gt; 为其铸造的 {@link DataScope}，由
     * {@link #registerExternal(ExternalPluginAdapter, Class[])} 填充。使
     * {@code DataStore.getOperator(File, Class)} 能把调用方解析回一个 scope（D-18）——针对未覆写
     * 该方法的第三方 {@code DataStore} 所继承的默认实现。
     */
    private final Map<String, DataScope> externalScopesByFolder = new ConcurrentHashMap<>();

    /**
     * Initialize plugin manager. Please do not call this method manually.
     * <br>
     * 初始化插件管理器。请不要手动调用此方法。
     *
     * @throws IOException IO exception <br> IO异常
     */
    public void init(ClassLoader classLoader) throws IOException {
        this.classLoader = classLoader;
        this.taskManager = new TaskManager(UltiTools.getInstance());
        this.playerCacheManager = new PlayerCacheManager();
        registerPlayerQuitListener();
        registerPluginDisableListener();
        String currentPath = System.getProperty("user.dir");
        String path = currentPath + File.separator + "plugins" + File.separator + "UltiTools" + File.separator + "plugins";
        File pluginFolder = new File(path);
        File[] plugins = pluginFolder.listFiles((file) -> file.getName().endsWith(".jar"));

        if (plugins == null) {
            return;
        }

        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Found " + plugins.length + " file(s):");

        for (File file : plugins) {
            Class<? extends UltiToolsPlugin> pluginClass = loadPluginMainClass(classLoader, file);
            if (pluginClass != null) {
                pluginClassList.add(pluginClass);
            }
        }
        int success = 0;
        if (pluginClassList.isEmpty()) {
            Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] No UltiTools plugin found.");
            return;
        }
        Bukkit.getLogger().log(Level.INFO, String.format("[UltiTools-API] %d UltiTools plugin(s) found.", pluginClassList.size()));
        
        // Sort plugins by dependencies using Kahn's algorithm
        List<Class<? extends UltiToolsPlugin>> sortedPlugins = sortPluginsByDependencies(pluginClassList);
        
        for (Class<? extends UltiToolsPlugin> pluginClass : sortedPlugins) {
            if (register(pluginClass)) {
                success++;
            }
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Plugin Loading completed.");
        Bukkit.getLogger().log(
                Level.INFO,
                String.format("[UltiTools-API] Succeeded loaded %d, Failed %d.", success, sortedPlugins.size() - success)
        );
    }

    /**
     * Register plugin.
     * <br>
     * 注册插件。
     *
     * @param pluginClass Plugin class <br> 插件类
     * @return Register result <br> 注册结果
     */
    public boolean register(Class<? extends UltiToolsPlugin> pluginClass) {
        UltiToolsPlugin plugin;
        try {
            plugin = initializePlugin(classLoader, pluginClass);
        } catch (Exception | Error e) {
            logPluginInitializationFailure(pluginClass.getName(), e);
            return false;
        }
        // null 表示兼容性门禁拒了它，拒绝理由已经打过日志，这里不要再包一层通用错误。
        if (plugin == null) {
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin);
        }
        return result;
    }

    /**
     * Register plugin.
     * <br>
     * 注册插件。
     *
     * @param pluginClass         UltiTools plugin class <br> UltiTools模块类
     * @param pluginName          Plugin name <br> 插件名称
     * @param version             Plugin version <br> 插件版本
     * @param authors             Plugin authors <br> 插件作者
     * @param loadAfter           Load after plugins <br> 加载在此插件之后的插件
     * @param minUltiToolsVersion Min UltiTools version <br> 最低UltiTools版本
     * @param mainClass           Main class <br> 主类
     * @return Register result <br> 注册结果
     * @deprecated This overload's reflective, with-args construction has failed on every
     *             release since 6.2.0 (Phase 1 D-15, measured): {@link
     *             SecurityPolicy#isSafeParameterType} rejects the runtime types
     *             {@code authors} and {@code loadAfter} actually are ({@code
     *             Arrays.asList(...)}'s {@code java.util.Arrays$ArrayList}, or any {@code
     *             Collections.*} wrapper) before the module's constructor ever runs. This
     *             overload existed to bypass {@code plugin.yml}-based metadata for connector
     *             callers; use {@link #register(UltiToolsPlugin)} instead, which takes an
     *             already-constructed plugin instance and has no reflective construction path
     *             of its own. See issue #332.
     *             <p>
     *             这个重载的带参数反射构造自 6.2.0 起从未成功过（Phase 1 D-15，已实测）：
     *             {@link SecurityPolicy#isSafeParameterType} 会在模块构造函数运行之前，就
     *             拒绝 {@code authors} 与 {@code loadAfter} 实际的运行期类型（{@code
     *             Arrays.asList(...)} 的 {@code java.util.Arrays$ArrayList}，或任何 {@code
     *             Collections.*} 包装类型）。这个重载原本是为了绕开 {@code plugin.yml} 元数据，
     *             供连接器调用方使用；请改用 {@link #register(UltiToolsPlugin)} —— 它接收一个
     *             已经构造好的插件实例，自身不涉及任何反射构造路径。见 issue #332。
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    public boolean register(
            Class<? extends UltiToolsPlugin> pluginClass,
            String pluginName,
            String version,
            List<String> authors,
            List<String> loadAfter,
            int minUltiToolsVersion,
            String mainClass
    ) {
        UltiToolsPlugin plugin;
        try {
            plugin = initializePlugin(
                    classLoader, pluginClass, pluginName, version, authors, loadAfter, minUltiToolsVersion, mainClass
            );
        } catch (Exception | Error e) {
            logPluginInitializationFailure(pluginClass.getName(), e);
            return false;
        }
        // 同上：null 是门禁拒绝，不是初始化失败。
        if (plugin == null) {
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin);
        }
        return result;
    }

    /**
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Register result <br> 注册结果
     */
    public boolean register(UltiToolsPlugin plugin) {
        // 门禁先跑：这条路径的实例是调用方给的，容器还没建，被拒时一个 bean 都不会被构造。
        // 见 issue #184。
        if (!passesCompatibilityGates(plugin)) {
            return false;
        }
        try {
            // WIRE-05/WIRE-06: this path now assembles through the exact same method
            // initializePlugin does -- see its javadoc for the full instruction sequence. The
            // only remaining difference between the two entry points is where the plugin
            // instance comes from (handed in here vs. reflectively constructed there); the
            // registerBukkit flag fork plan 04-08 removed made registerBukkit itself identical
            // on both paths too.
            SimpleContainer pluginContext = new SimpleContainer();
            assemblePluginContainer(pluginContext, plugin, plugin.getClass(), classLoader);
        } catch (Exception | Error e) {
            logPluginInitializationFailure(plugin.getPluginName(), e);
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin);
        }
        return result;
    }

    /**
     * Logs one refusal WARNING for a module that failed to initialize, surfacing the innermost
     * {@link UltiToolsException}'s message (module/file/field/value/constraint, per e.g.
     * {@code ConfigurationException.validationFailed(...)}) instead of an outer wrapper's
     * generic text (04-REVIEW.md WR-02). All three refusal-log sites in this class route
     * through here so the message shape has exactly one place it is built.
     * <p>
     * Package-private, not private: {@code PluginManagerTest} lives in this same package and
     * drives this method directly with a real multi-level chain, without reflection or
     * {@code setAccessible(true)}. This is a test-seam choice, not an API widening - it is not
     * {@code public}, so it never enters the published surface.
     * <p>
     * 为初始化失败的模块记录一条拒绝 WARNING，展示最内层 {@link UltiToolsException} 的消息
     * （模块/文件/字段/值/约束，例如来自 {@code ConfigurationException.validationFailed(...)}），
     * 而不是外层包装异常的通用文本（04-REVIEW.md WR-02）。本类中全部三处拒绝日志调用点都经过
     * 这里，消息的组装逻辑只有一处。
     * <p>
     * 包级私有而非 private：{@code PluginManagerTest} 与本类同包，可以直接调用本方法驱动一条
     * 真实的多层异常链，无需反射或 {@code setAccessible(true)}。这是测试接缝的选择，不是 API
     * 扩张——它不是 {@code public}，不会进入已发布的对外接口。
     *
     * @param moduleName the module refusing to load, however the caller identifies it
     *                   <br> 拒绝加载的模块，调用方按自己的方式命名它
     * @param thrown     the throwable caught at the registration boundary <br> 在注册边界捕获到的异常
     */
    static void logPluginInitializationFailure(String moduleName, Throwable thrown) {
        Bukkit.getLogger().log(
                Level.WARNING,
                String.format("[UltiTools-API] Cannot initialize plugin for %s: %s", moduleName, rootCauseMessage(thrown)),
                thrown
        );
    }

    /**
     * Walks {@code thrown}'s cause chain for the deepest {@link UltiToolsException}, returning
     * its message - or {@code thrown.getMessage()} if the chain holds none. Bounded via
     * identity-based cycle detection ({@link IdentityHashMap}) so a self-referential or cyclic
     * cause chain terminates instead of spinning.
     * <p>
     * 沿 {@code thrown} 的异常链向下找最深层的 {@link UltiToolsException}，返回它的消息——
     * 若链上没有则返回 {@code thrown.getMessage()}。通过基于身份的环检测（{@link
     * IdentityHashMap}）设置边界，自引用或循环的异常链会终止而不是卡死。
     *
     * @param thrown the throwable whose cause chain is walked
     * @return the deepest {@link UltiToolsException}'s message, or {@code thrown}'s own message
     */
    static String rootCauseMessage(Throwable thrown) {
        Throwable deepest = null;
        Throwable current = thrown;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof UltiToolsException) {
                deepest = current;
            }
            current = current.getCause();
        }
        return deepest != null ? deepest.getMessage() : thrown.getMessage();
    }

    /**
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     */
    public void unregister(UltiToolsPlugin plugin) {
        // Cancel all @Scheduled tasks before unregistering
        if (taskManager != null) {
            taskManager.cancelAll(plugin);
        }
        // Unregister @PlayerCache beans before context closes
        if (playerCacheManager != null && plugin.getContext() != null) {
            for (Object bean : plugin.getContext().getSingletonValues()) {
                playerCacheManager.unregisterBean(bean);
            }
        }
        // Bulk-unregister this module's tab-completion completers so the singleton does not
        // pin the module's ClassLoader after unload (T-05-24 / D-08). A module that registered
        // nothing is a no-op (unregisterByOwner(null) and unregisterByOwner("unknown-name") both
        // return 0 and throw nothing).
        TabCompletionManager.getInstance().unregisterByOwner(plugin.getPluginName());
        // Unregister @ModuleEventHandler handlers from EventBus
        EventBus eventBus = UltiTools.getInstance().getEventBus();
        if (eventBus != null) {
            eventBus.unregisterAll(plugin.getPluginName());
        }
        UltiTools.getInstance().getListenerManager().unregisterAll(plugin);
        plugin.unregisterSelf();
        // unregister() is reachable with an instance the caller constructed directly, which never
        // went through PluginManager.register(...) and so never received a container (SILENT-19,
        // #338). Guard the close the same way the @PlayerCache block above already does.
        if (plugin.getContext() != null) {
            plugin.getContext().close();
        }
    }

    /**
     * Unregister all plugins.
     * <br>
     * 注销所有插件。
     */
    public void close() {
        // Disconnect all external plugins first
        UltiToolsAPI.disconnectAll();

        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Unregistering all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            unregister(plugin);
        }
        pluginList.clear();
        pluginClassList.clear();
    }

    /**
     * Register a Bukkit listener for PlayerQuitEvent to clean up @PlayerCache maps.
     * <br>
     * 注册 Bukkit 监听器，在玩家退出时清理 @PlayerCache 标注的 Map。
     */
    private void registerPlayerQuitListener() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                if (playerCacheManager != null) {
                    playerCacheManager.onPlayerQuit(event.getPlayer().getUniqueId());
                }
            }
        }, Bukkit.getPluginManager().getPlugin("UltiTools"));
    }

    /**
     * Register a Bukkit listener for PluginDisableEvent to auto-disconnect external plugins.
     * <br>
     * 注册 Bukkit 监听器，在外部插件禁用时自动断开连接。
     *
     * @since 6.2.2
     */
    private void registerPluginDisableListener() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                if (event.getPlugin() instanceof JavaPlugin) {
                    UltiToolsAPI.onPluginDisable((JavaPlugin) event.getPlugin());
                }
            }
        }, Bukkit.getPluginManager().getPlugin("UltiTools"));
    }

    /**
     * Reload all plugins. This operation only reload plugin configuration.
     * <br>
     * 重载所有插件。此操作仅会重新加载插件配置。
     */
    public void reload() {
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Reloading all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            plugin.reloadSelf();
        }
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] All plugins reloaded.");
        Bukkit.getLogger().log(
                Level.WARNING,
                "[UltiTools-API] This operation is only used for reloading plugin configuration. If (un)installing, please restart the server!"
        );
    }

    /**
     * Load module main class.
     * <br>
     * 加载模块主类。
     *
     * @param classLoader Class loader <br> 类加载器
     * @param pluginJar   Plugin jar file <br> 模块jar文件
     * @return Plugin main class <br> 模块主类
     */
    private Class<? extends UltiToolsPlugin> loadPluginMainClass(ClassLoader classLoader, File pluginJar) { // NOPMD - classLoader used implicitly by Class.forName
        // 验证jar文件安全性
        if (!SecurityPolicy.isValidModuleJar(pluginJar)) {
            Bukkit.getLogger().log(Level.SEVERE, 
                "[UltiTools-API] Security validation failed for jar: " + pluginJar.getName());
            return null;
        }
        
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entryEnumeration = jarFile.entries();
            Set<String> scannedClasses = new HashSet<>();
            
            while (entryEnumeration.hasMoreElements()) {
                JarEntry entry = entryEnumeration.nextElement();
                if (!entry.getName().contains(".class") || entry.getName().contains("META-INF")) {
                    continue;
                }
                
                String className = entry
                        .getName()
                        .replace('/', '.')
                        .replace(".class", "");
                
                // 防止重复扫描同一个类
                if (scannedClasses.contains(className)) {
                    continue;
                }
                scannedClasses.add(className);
                
                // 限制扫描的类数量，防止拒绝服务攻击
                if (scannedClasses.size() > 1000) {
                    Bukkit.getLogger().log(Level.WARNING, 
                        "[UltiTools-API] Too many classes in jar, scanning stopped: " + pluginJar.getName());
                    break;
                }
                
                try {
                    // Use security-validated class loading (checks dangerous classes/packages)
                    // but NOT loadPluginClass() which rejects non-UltiToolsPlugin classes
                    Class<?> aClass = ClassLoaderUtils.loadClass(className);
                    if (UltiToolsPlugin.class.isAssignableFrom(aClass)
                            && !aClass.isInterface()
                            && !Modifier.isAbstract(aClass.getModifiers())) {
                        return aClass.asSubclass(UltiToolsPlugin.class);
                    }
                } catch (ClassNotFoundException | LinkageError e) {
                    // 记录但不中断，继续扫描其他类
                    Bukkit.getLogger().log(Level.FINE,
                        "[UltiTools-API] Could not load class: " + className + " - " + e.getMessage());
                } catch (SecurityException e) {
                    // 安全异常需要记录 — only triggers for actually dangerous classes
                    Bukkit.getLogger().log(Level.WARNING,
                        "[UltiTools-API] Security violation while loading class: " + className + " - " + e.getMessage());
                }
            }
        } catch (IOException | LinkageError | RuntimeException e) {
            Bukkit.getLogger().log(Level.SEVERE, 
                "[UltiTools-API] Failed to read jar file: " + pluginJar.getName(), e);
        }
        return null;
    }

    /**
     * Scans a plugin's own JAR for classes carrying {@code @Table}, independently of {@link
     * #loadPluginMainClass}'s scan (which returns as soon as it finds the module's main class and
     * discards its own {@code scannedClasses} set). This pass visits every entry so a truncated
     * result never makes a legitimate entity look unowned -- under D-15's fail-closed rule that
     * would refuse a module that did nothing wrong. {@link #loadPluginMainClass}'s 1,000-class cap
     * is reported here, not enforced: crossing it produces one WARNING naming the jar and the
     * count, and the scan continues to the end of the jar regardless.
     * <p>
     * 独立于 {@link #loadPluginMainClass} 的扫描（后者一找到模块主类就 return，并丢弃自己的
     * {@code scannedClasses} 集合），扫描 {@code pluginJar} 中所有携带 {@code @Table} 注解的类。
     * 本次扫描会遍历每一个条目——截断的结果会让一个合法实体看起来「无主」，而 D-15 的
     * fail-closed 规则会因此拒绝一个完全无辜的模块。给 {@link #loadPluginMainClass} 设置上限的
     * 1000 这个数字，在这里只报告、不强制——超过时只产生一条 WARNING（点名 jar 和数量），
     * 扫描仍会继续到 jar 结尾。
     *
     * @param pluginJar the module's own jar file <br> 模块自身的 jar 文件
     * @return every {@code @Table}-annotated class found, never null <br> 找到的每一个携带
     *         {@code @Table} 的类，不会为 null
     */
    private Set<Class<?>> scanEntitiesInJar(File pluginJar) {
        Set<Class<?>> entities = new HashSet<>();
        if (pluginJar == null || !pluginJar.isFile()) {
            return entities;
        }
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entryEnumeration = jarFile.entries();
            Set<String> scannedClasses = new HashSet<>();
            boolean warnedOverCap = false;

            while (entryEnumeration.hasMoreElements()) {
                JarEntry entry = entryEnumeration.nextElement();
                String className = classNameForEntityScan(entry);
                if (className == null || !scannedClasses.add(className)) {
                    continue;
                }

                if (!warnedOverCap && scannedClasses.size() > 1000) {
                    warnedOverCap = true;
                    Bukkit.getLogger().log(Level.WARNING,
                        "[UltiTools-API] Entity scan of " + pluginJar.getName() + " has passed 1000 "
                            + "classes -- the scan continues to completion (the cap is reported here, "
                            + "not enforced, so no entity is silently dropped).");
                }

                resolveEntityClass(className).ifPresent(entities::add);
            }
        } catch (IOException | LinkageError | RuntimeException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Failed to scan jar for entities: " + pluginJar.getName(), e);
        }
        return entities;
    }

    /**
     * Converts one {@code JarEntry} from {@link #scanEntitiesInJar} into a dotted class name,
     * or {@code null} when the entry is not a class file the entity scan cares about (a
     * {@code META-INF} entry, or anything not ending in {@code .class}).
     * <p>
     * 把 {@link #scanEntitiesInJar} 中的一个 {@code JarEntry} 转换为点分隔的类名；如果这个条目不是
     * 实体扫描关心的 class 文件（{@code META-INF} 条目，或任何不以 {@code .class} 结尾的条目），
     * 返回 {@code null}。
     *
     * @param entry the jar entry under inspection <br> 正在检查的 jar 条目
     * @return the dotted class name, or null to skip the entry <br> 点分隔的类名，或 null 表示跳过该条目
     */
    private static String classNameForEntityScan(JarEntry entry) {
        if (!entry.getName().endsWith(".class") || entry.getName().contains("META-INF")) {
            return null;
        }
        return entry
                .getName()
                .replace('/', '.')
                .replace(".class", "");
    }

    /**
     * Loads {@code className} and returns it wrapped in an {@code Optional} when it carries
     * {@code @Table}, for {@link #scanEntitiesInJar}'s per-entry step. Never throws: a class that
     * cannot be loaded, or is loaded but is not an entity, both resolve to {@code Optional.empty()}.
     * <p>
     * 加载 {@code className}，若其携带 {@code @Table} 注解则以 {@code Optional} 包裹返回，供
     * {@link #scanEntitiesInJar} 逐条目使用。永不抛出异常：无法加载的类，或加载成功但不是实体的类，
     * 都会解析为 {@code Optional.empty()}。
     *
     * @param className the dotted class name to load <br> 待加载的点分隔类名
     * @return the loaded class if it is a {@code @Table} entity, otherwise empty <br>
     *         若加载的类是 {@code @Table} 实体则返回该类，否则为空
     */
    private static Optional<Class<?>> resolveEntityClass(String className) {
        try {
            Class<?> aClass = ClassLoaderUtils.loadClass(className);
            if (aClass.isAnnotationPresent(com.ultikits.ultitools.annotations.Table.class)) {
                return Optional.of(aClass);
            }
        } catch (ClassNotFoundException | LinkageError e) {
            Bukkit.getLogger().log(Level.FINE,
                "[UltiTools-API] Could not load class during entity scan: " + className + " - " + e.getMessage());
        } catch (SecurityException e) {
            Bukkit.getLogger().log(Level.WARNING,
                "[UltiTools-API] Security violation during entity scan: " + className + " - " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Best-effort resolution of the jar file {@code aClass} was loaded from, via its {@link
     * java.security.CodeSource}. Never throws; returns {@code null} when the code source is
     * unavailable or does not point at a real jar (e.g. exploded test classes) -- the same
     * "best effort, class-name fallback" posture {@link UltiToolsPlugin#resolveJarFileNameForError()}
     * already uses for the same lookup.
     * <p>
     * 通过 {@link java.security.CodeSource} 尽力解析 {@code aClass} 的加载来源 jar 文件。永不抛出
     * 异常；代码源不可用，或并未指向真实 jar 文件时（例如测试中未打包的 class），返回
     * {@code null}，与 {@link UltiToolsPlugin#resolveJarFileNameForError()} 对同一查找的既有
     * "尽力而为、回退类名" 做法一致。
     *
     * @param aClass the class whose jar should be resolved <br> 待解析所属 jar 的类
     * @return the jar file, or null if it cannot be resolved <br> jar 文件，无法解析时为 null
     */
    private static File resolveOwnJarFile(Class<?> aClass) {
        try {
            java.security.CodeSource src = aClass.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) {
                return null;
            }
            File file = new File(src.getLocation().toURI());
            return file.isFile() && file.getName().endsWith(".jar") ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Builds the entity set a {@link DataScope} for {@code plugin} should carry (D-19): every
     * {@code @Table} class found by scanning the module's own jar, plus anything declared via
     * {@code @UltiToolsModule#additionalEntities()} for entities that legitimately live elsewhere.
     * Never throws -- a plugin whose jar cannot be resolved (e.g. a directly-constructed test
     * instance with no real jar) simply gets an entity set built from its declared
     * {@code additionalEntities()} alone, which is empty by default.
     * <p>
     * 为 {@code plugin} 的 {@link DataScope} 构建实体集合（D-19）：扫描模块自身 jar 得到的每一个
     * {@code @Table} 类，加上模块通过 {@code @UltiToolsModule#additionalEntities()} 声明的、
     * 合法存放在别处的实体。永不抛出异常——jar 无法解析时（例如直接构造、没有真实 jar 的测试
     * 实例），实体集合仅由其声明的 {@code additionalEntities()} 构成，默认为空。
     * <p>
     * Takes the plugin's class rather than an instance -- everything this reads (the JAR's own
     * {@code CodeSource} and the class-level {@code @UltiToolsModule} annotation) is a class-level
     * fact, and {@code initializePlugin} already has {@code pluginClass} in hand before the
     * instance exists.
     * <p>
     * Every class named in {@code additionalEntities()} is validated (02-14) before being folded
     * in -- see the private provenance-check method below this one. A plugin can declare an entity
     * that legitimately lives outside its own JAR (D-19's purpose), but not one that structurally
     * belongs to a different, already-known module.
     *
     * @param pluginName  the plugin's own name, for the refusal message and the ownership-conflict
     *                     check <br> 插件自身名称，用于拒绝信息和所有权冲突检查
     * @param pluginClass the plugin whose scope is being minted <br> 正在铸造 scope 的插件
     * @return the entity set for that plugin's scope <br> 该插件 scope 的实体集合
     * @throws PluginModuleException fail-closed, when {@code additionalEntities()} names an entity
     *                                that does not live on this plugin's own classpath (02-14)
     */
    private Set<Class<?>> scanPluginEntities(String pluginName, Class<? extends UltiToolsPlugin> pluginClass) {
        Set<Class<?>> entities = new HashSet<>();
        File jarFile = resolveOwnJarFile(pluginClass);
        if (jarFile != null) {
            entities.addAll(scanEntitiesInJar(jarFile));
        }
        UltiToolsModule module = pluginClass.getAnnotation(UltiToolsModule.class);
        if (module != null) {
            for (Class<?> entity : module.additionalEntities()) {
                validateAdditionalEntity(pluginName, pluginClass, jarFile, entity);
                entities.add(entity);
            }
        }
        return entities;
    }

    /**
     * Builds the entity set a {@link DataScope} for an external plugin should carry (D-19): every
     * {@code @Table} class found by scanning the plugin's own jar, plus whatever it declared via
     * {@code UltiToolsAPI.connect(plugin, additionalEntities)}.
     * <p>
     * 为外部插件的 {@link DataScope} 构建实体集合（D-19）：扫描插件自身 jar 得到的每一个
     * {@code @Table} 类，加上通过 {@code UltiToolsAPI.connect(plugin, additionalEntities)} 声明的
     * 实体。
     * <p>
     * Every class named in {@code additionalEntities} is validated (02-14) before being folded in
     * -- see the private provenance-check method below {@code scanPluginEntities}. This is the
     * public, reachable side of D-19: {@code UltiToolsAPI.connect(plugin, additionalEntities...)}
     * lands here with zero prior trust established, so the validation matters most on this path.
     *
     * @param adapter            the external plugin adapter <br> 外部插件适配器
     * @param additionalEntities entity classes declared via {@code connect(...)} <br> 通过
     *                           {@code connect(...)} 声明的实体类
     * @return the entity set for that external plugin's scope <br> 该外部插件 scope 的实体集合
     * @throws PluginModuleException fail-closed, when {@code additionalEntities} names an entity
     *                                that does not live on this plugin's own classpath (02-14)
     */
    private Set<Class<?>> scanExternalEntities(ExternalPluginAdapter adapter, Class<?>[] additionalEntities) {
        Set<Class<?>> entities = new HashSet<>();
        Class<?> declaringClass = adapter.getJavaPlugin().getClass();
        File jarFile = resolveOwnJarFile(declaringClass);
        if (jarFile != null) {
            entities.addAll(scanEntitiesInJar(jarFile));
        }
        if (additionalEntities != null) {
            for (Class<?> entity : additionalEntities) {
                validateAdditionalEntity(adapter.getPluginName(), declaringClass, jarFile, entity);
                entities.add(entity);
            }
        }
        return entities;
    }

    /**
     * Validates that {@code entityClass}, declared via {@code additionalEntities} (D-19) by
     * {@code declaringPluginName}, does not structurally belong to a DIFFERENT, already-known
     * plugin before it is folded into that plugin's {@link DataScope} (02-14 finding: this
     * attribute previously accepted any {@code Class<?>} the caller named, with zero validation --
     * letting any plugin claim ownership of any other module's real {@code @Table} entity through
     * the public {@code UltiToolsAPI.connect(plugin, additionalEntities...)} surface, or the
     * internal {@code @UltiToolsModule#additionalEntities()} equivalent).
     * <p>
     * The discriminator is "does this class belong to a DIFFERENT plugin", not "is this class
     * mine" -- deliberately, since D-19 exists precisely so a module can declare an entity that
     * lives outside its own JAR:
     * <ul>
     *   <li>{@code entityClass} living in the SAME physical jar as {@code declaringOwnJarFile} (a
     *       shared library shaded into the declaring plugin's own artifact, or a harmless
     *       redundant re-declaration of an entity the automatic scan already found) -- legitimate,
     *       accept.</li>
     *   <li>{@code entityClass} already recorded in {@link #entityOwnership} under a DIFFERENT
     *       plugin name -- refuse, naming the confirmed owner (matches
     *       {@code DataScope.refusalFor}'s message shape).</li>
     *   <li>{@code entityClass}'s own jar is itself the own jar of a DIFFERENT, already-discovered
     *       {@link UltiToolsPlugin} module ({@link #pluginClassList}, populated by {@code init()}'s
     *       upfront jar scan BEFORE any individual plugin registers) or of a currently-loaded
     *       Bukkit plugin ({@code Bukkit.getPluginManager().getPlugins()}, populated by Bukkit's
     *       own plugin loading, independent of whether that plugin has called
     *       {@code UltiToolsAPI.connect(...)} yet) -- structurally confirmed as belonging to a
     *       different plugin, refuse. This is what closes the first-registration-wins window
     *       {@link #entityOwnership}'s {@code putIfAbsent} semantics alone cannot: it does not
     *       depend on which plugin happened to register first, only on which jar actually defines
     *       the class.</li>
     *   <li>Anything else -- {@code entityClass} lives outside the declarer's own jar but is not
     *       structurally tied to any other known plugin, and no registry conflict exists -- a
     *       genuine shared-library / multi-module common artifact, D-19's stated purpose. Accept.</li>
     * </ul>
     * Deliberately does not compare classloaders: every internal {@link UltiToolsPlugin} module is
     * loaded through ONE shared {@code URLClassLoader} covering the whole {@code plugins/} folder
     * (see {@code init(ClassLoader)}), so two different internal modules' classes always report
     * the same classloader -- using that as a signal would make this check a no-op for the
     * internal path, silently failing to close the exact vulnerability it exists to close. Jar
     * identity (via {@code java.security.CodeSource}) remains meaningful even when many jars share
     * one classloader, which is why it is the signal used here instead.
     * <p>
     * A residual gap, disclosed rather than silently accepted: an attacker who can obtain a
     * byte-identical copy of another plugin's entity class (e.g. by depending on its published jar
     * at build time) and shade that copy into their own jar would pass this check -- from the
     * JVM's perspective it genuinely is a different, attacker-owned {@code Class} object,
     * structurally indistinguishable from a legitimate shared-library entity. This method
     * validates class PROVENANCE (which jar/module actually defined this exact class), not table
     * name collision; a plugin that simply writes its own class with a matching {@code @Table}
     * name never reaches this method at all (no {@code additionalEntities} declaration involved),
     * and is a pre-existing, out-of-scope risk inherent to a single shared MySQL DataSource across
     * all plugins -- not the vulnerability 02-SECURITY.md described or this plan closes.
     *
     * @param declaringPluginName the plugin declaring {@code entityClass} <br> 声明
     *                             {@code entityClass} 的插件
     * @param declaringClass      the declaring plugin's own main/adapter class, used to look it up
     *                             in {@link #pluginClassList} and excluded from the
     *                             "belongs to another known plugin" check <br> 声明方插件自身的主类
     *                             /适配器类，用于在 {@link #pluginClassList} 中查找自身并将其排除在
     *                             「属于另一个已知插件」检查之外
     * @param declaringOwnJarFile the declaring plugin's own resolved jar, or {@code null} when
     *                             unresolvable (e.g. a directly-constructed test instance) <br>
     *                             声明方插件自身解析出的 jar；无法解析时（例如直接构造、无真实
     *                             jar 的测试实例）为 {@code null}
     * @param entityClass          the entity class named in {@code additionalEntities} <br>
     *                             {@code additionalEntities} 中声明的实体类
     * @throws PluginModuleException fail-closed (D-15), naming the declarer, the entity, and the
     *                                actual owner where one is known
     */
    private void validateAdditionalEntity(String declaringPluginName, Class<?> declaringClass,
            File declaringOwnJarFile, Class<?> entityClass) {
        File entityJarFile = resolveOwnJarFile(entityClass);

        if (entityJarFile != null && declaringOwnJarFile != null
                && canonicalPath(entityJarFile).equals(canonicalPath(declaringOwnJarFile))) {
            return; // same physical jar as the declarer's own artifact -- legitimate (D-19)
        }

        String owner = findOwningPlugin(entityClass);
        if (owner != null && !owner.equals(declaringPluginName)) {
            throw new PluginModuleException(ErrorCode.ENTITY_NOT_OWNED,
                    additionalEntityRefusalMessage(declaringPluginName, entityClass, owner));
        }

        if (entityJarFile != null && belongsToAnotherKnownPlugin(entityJarFile, declaringClass)) {
            throw new PluginModuleException(ErrorCode.ENTITY_NOT_OWNED,
                    additionalEntityRefusalMessage(declaringPluginName, entityClass, null));
        }
    }

    /**
     * Whether {@code entityJarFile} is itself the own jar of some plugin OTHER than
     * {@code declaringClass} -- checked against every internal module {@code init()} has already
     * discovered ({@link #pluginClassList}, regardless of load order) and every Bukkit plugin
     * currently loaded ({@code Bukkit.getPluginManager().getPlugins()}, regardless of whether it
     * has called {@code UltiToolsAPI.connect(...)} yet). See the caller directly above this method
     * for the full reasoning.
     * <p>
     * {@code entityJarFile} 是否正是除 {@code declaringClass} 之外某个插件自身的 jar——依次比对
     * {@code init()} 已经发现的每一个内部模块（{@link #pluginClassList}，与加载顺序无关）和每一个
     * 当前已加载的 Bukkit 插件（{@code Bukkit.getPluginManager().getPlugins()}，无论其是否已调用
     * {@code UltiToolsAPI.connect(...)}）。完整推理见上一个方法（本方法唯一的调用方）。
     *
     * @param entityJarFile  the entity's resolved own jar, never null <br> 实体自身解析出的 jar，
     *                       不会为 null
     * @param declaringClass the declaring plugin's own class, excluded from the comparison <br>
     *                       声明方插件自身的类，比对时会被排除
     * @return true if a different, already-known plugin's own jar matches <br>
     *         若匹配到另一个已知插件自身的 jar 则为 true
     */
    private boolean belongsToAnotherKnownPlugin(File entityJarFile, Class<?> declaringClass) {
        String entityPath = canonicalPath(entityJarFile);
        for (Class<? extends UltiToolsPlugin> known : pluginClassList) {
            if (known == declaringClass) {
                continue;
            }
            File knownJar = resolveOwnJarFile(known);
            if (knownJar != null && entityPath.equals(canonicalPath(knownJar))) {
                return true;
            }
        }
        for (org.bukkit.plugin.Plugin loaded : Bukkit.getPluginManager().getPlugins()) {
            if (loaded.getClass() == declaringClass) {
                continue;
            }
            File loadedJar = resolveOwnJarFile(loaded.getClass());
            if (loadedJar != null && entityPath.equals(canonicalPath(loadedJar))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the refusal message for an {@code additionalEntities} declaration that does not live
     * on the declarer's own classpath -- the same "confirmed owner" vs. "no confirmed owner"
     * distinction {@code DataScope.refusalFor(Class)} makes (D-15), applied at declaration time
     * instead of data-access time.
     * <p>
     * 为不在声明方自身 classpath 上的 {@code additionalEntities} 声明构建拒绝信息——与
     * {@code DataScope.refusalFor(Class)}（D-15）相同的「已确认归属方」与「归属方未知」区分，
     * 只是发生在声明时而非数据访问时。
     *
     * @param declaringPluginName the plugin whose declaration is refused <br> 被拒绝声明的插件
     * @param entityClass          the entity class that was declared <br> 被声明的实体类
     * @param owner                the confirmed owning plugin's name, or {@code null} when none is
     *                             known <br> 已确认的拥有者插件名；未知时为 {@code null}
     * @return the refusal message <br> 拒绝信息
     */
    private static String additionalEntityRefusalMessage(String declaringPluginName, Class<?> entityClass,
            String owner) {
        if (owner != null) {
            return "Plugin '" + declaringPluginName + "' declared additionalEntities entity "
                    + entityClass.getName() + ", which is confirmed to belong to module '" + owner
                    + "' -- refusing (the entity does not live on '" + declaringPluginName
                    + "'s own classpath; use " + owner + "'s exposed service or the EventBus instead).";
        }
        return "Plugin '" + declaringPluginName + "' declared additionalEntities entity "
                + entityClass.getName() + ", which is confirmed to belong to a different, "
                + "already-known plugin -- refusing (the entity does not live on '" + declaringPluginName
                + "'s own classpath).";
    }

    /**
     * Records {@code scope}'s owned entities in {@link #entityOwnership}, so a later ownership
     * refusal (D-15) can name the actual owning module instead of only saying "not registered to
     * you". Called every time a {@link DataScope} is minted, for both internal modules and
     * external plugins.
     * <p>
     * 把 {@code scope} 拥有的实体记录进 {@link #entityOwnership}，使后续的所有权拒绝（D-15）
     * 能够点名真正的拥有者模块，而不只是说「未向你注册」。每次铸造 {@link DataScope} 时都会
     * 调用，内部模块和外部插件均适用。
     *
     * @param scope the scope just minted <br> 刚铸造的 scope
     */
    private void registerEntityOwnership(DataScope scope) {
        for (Class<?> entity : scope.getOwnedEntities()) {
            entityOwnership.putIfAbsent(entity, scope.getPluginName());
        }
    }

    /**
     * Looks up which loaded plugin owns {@code entityClass}, per {@link #entityOwnership}.
     * <p>
     * 按 {@link #entityOwnership} 查找哪个已加载插件拥有 {@code entityClass}。
     *
     * @param entityClass the entity class to look up <br> 待查找的实体类
     * @return the owning plugin's name, or {@code null} if no loaded scope owns it <br>
     *         拥有者插件的名称；若没有任何已加载 scope 拥有该实体则为 {@code null}
     */
    public String findOwningPlugin(Class<?> entityClass) {
        return entityOwnership.get(entityClass);
    }

    /**
     * Records an external plugin's {@link DataScope} under its data folder's canonical path, so
     * {@link #findScopeForDataFolder} can resolve the caller of {@code DataStore.getOperator(File,
     * Class)} back to a scope (D-18) -- the same canonical-path keying 02-02's Task 2 used for the
     * SQLite pool map, so two spellings of the same folder resolve to one entry.
     * <p>
     * 把外部插件的 {@link DataScope} 按其数据文件夹的规范路径记录下来，使
     * {@link #findScopeForDataFolder} 能把 {@code DataStore.getOperator(File, Class)} 的调用方
     * 解析回一个 scope（D-18）——沿用 02-02 Task 2 为 SQLite 连接池 Map 采用的规范路径键法，
     * 让同一个文件夹的两种写法解析到同一条记录。
     * <p>
     * Refuses (02-14 Task 2) rather than silently replacing when the canonical path already maps
     * to a DIFFERENT plugin's scope -- {@code externalScopesByFolder} used a plain {@code .put()}
     * before this, so wrapping another plugin (all public calls -- {@code new
     * ExternalPluginAdapter(Bukkit.getPluginManager().getPlugin("Victim"))}) and calling the
     * public {@code registerExternal} would displace the victim's legitimate scope here, poisoning
     * the D-18 reverse lookup {@code checkOwnership(File, Class)} depends on. Re-registration by
     * the SAME plugin (matched by name) is idempotent, not refused -- a plugin reconnecting after
     * {@link #unregisterExternal(ExternalPluginAdapter)} must not be permanently locked out.
     * <p>
     * 当规范路径已经映射到另一个不同插件的 scope 时拒绝（02-14 Task 2），而不是静默替换——此前
     * {@code externalScopesByFolder} 用的是普通 {@code .put()}，于是包装另一个插件（全部为公开
     * 调用——{@code new ExternalPluginAdapter(Bukkit.getPluginManager().getPlugin("Victim"))}）
     * 并调用公开的 {@code registerExternal}，就能在这里覆盖受害插件的合法 scope，污染
     * {@code checkOwnership(File, Class)} 依赖的 D-18 反向查找。同一个插件（按名称匹配）重复
     * 注册是幂等的、不会被拒绝——插件在 {@link #unregisterExternal(ExternalPluginAdapter)} 后
     * 重连不应被永久锁死。
     *
     * @param dataFolder the external plugin's own data folder <br> 外部插件自己的数据文件夹
     * @param scope      the scope just minted for it <br> 刚为它铸造的 scope
     * @throws PluginModuleException fail-closed, when {@code dataFolder} already maps to a
     *                                DIFFERENT plugin's registered scope (02-14)
     */
    private void registerExternalScope(File dataFolder, DataScope scope) {
        String path = canonicalPath(dataFolder);
        // putIfAbsent, not get()-then-put(): atomic check-and-insert, so two concurrent
        // registrations for the same folder can't both observe "nothing registered yet" and both
        // proceed to write -- the same race a plain get()/put() pair would leave open.
        DataScope existing = externalScopesByFolder.putIfAbsent(path, scope);
        if (existing != null && !existing.getPluginName().equals(scope.getPluginName())) {
            throw new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                    "Refusing to register external plugin '" + scope.getPluginName() + "' for data folder '"
                            + path + "': already registered to '" + existing.getPluginName() + "' -- an "
                            + "external scope registration cannot displace an existing one.");
        }
        // existing == null: this call's putIfAbsent just inserted scope, nothing left to do.
        // existing != null && same plugin name: idempotent re-registration -- the map keeps the
        // ALREADY-registered scope (putIfAbsent never overwrites on a hit); same plugin, so this
        // is a no-op rather than an attack.
    }

    /**
     * Resolves a data folder back to the registered external plugin scope that owns it (D-18).
     * <p>
     * 把数据文件夹解析回拥有它的、已注册的外部插件 scope（D-18）。
     *
     * @param dataFolder the folder to resolve <br> 待解析的文件夹
     * @return the registered scope, or {@code null} if the folder matches no registered adapter
     *         <br> 已注册的 scope；若该文件夹不匹配任何已注册的适配器则为 {@code null}
     */
    public DataScope findScopeForDataFolder(File dataFolder) {
        return externalScopesByFolder.get(canonicalPath(dataFolder));
    }

    /**
     * Removes a data folder's registered external plugin scope (02-14 Task 2), so a subsequent
     * {@link #registerExternal(ExternalPluginAdapter, Class[])} for the same folder -- a legitimate
     * reconnect -- is not refused by {@link #registerExternalScope}'s displacement guard as if it
     * were a different plugin still holding the folder.
     * <p>
     * 移除某个数据文件夹已注册的外部插件 scope（02-14 Task 2），使随后针对同一文件夹的
     * {@link #registerExternal(ExternalPluginAdapter, Class[])}（合法的重新连接）不会被
     * {@link #registerExternalScope} 的覆盖防护误判成「该文件夹仍被另一个插件占用」而拒绝。
     *
     * @param dataFolder the folder whose registration should be cleared <br> 待清除注册的文件夹
     */
    private void unregisterExternalScope(File dataFolder) {
        externalScopesByFolder.remove(canonicalPath(dataFolder));
    }

    private static String canonicalPath(File folder) {
        try {
            return folder.getCanonicalPath();
        } catch (IOException e) {
            return folder.getAbsolutePath();
        }
    }

    /**
     * Compatibility gates that must run before the module's bean graph is built.
     * <br>
     * 必须跑在模块 bean 图构建之前的兼容性门禁。
     * <p>
     * 这两道门禁原先跑在 {@code initializePlugin} 之后，也就是容器 {@code refresh()}、整张
     * bean 图连同 {@code @PostConstruct} 都已经跑完之后。倒置的后果正好落在它们唯一有意义的
     * 场景上：针对更新 API 编译的模块，恰恰是那种 bean 会引用不存在方法的模块，它会先死在
     * {@code refresh()} 里，被通用 catch 报成一条普通的初始化失败，而「UltiTools 版本过旧」
     * 那句友好提示永远说不出口。见 issue #184。
     * <p>
     * 门禁只读 {@code plugin.yml} 里来的元数据（{@code api-version} / {@code main} /
     * {@code version}），这些在实例构造完就已经就位，不需要容器。
     *
     * @param plugin freshly constructed plugin instance, context not yet attached <br> 刚构造出来、还没挂上容器的插件实例
     * @return true if the module may proceed to context construction <br> 允许继续建容器则为 true
     */
    private boolean passesCompatibilityGates(UltiToolsPlugin plugin) {
        return !hasNewerVersionLoaded(plugin) && isUltiToolsVersionCompatible(plugin);
    }

    /**
     * 判定是否已经加载了同一模块的更新版本。**只判定，不改动已加载的模块。**
     * <p>
     * 卸掉被本次加载取代的旧版本是另一件事，见 {@link #unregisterSupersededVersions}。
     * 两者拆开是因为判定要提前到 bean 构造之前，而卸载不能——提前卸载会让「新版本初始化失败」
     * 变成「服务器上两个版本都没有」。
     */
    private boolean hasNewerVersionLoaded(UltiToolsPlugin plugin) {
        for (UltiToolsPlugin existing : pluginList) {
            if (!existing.getMainClass().equals(plugin.getMainClass())) {
                continue;
            }
            if (existing.isNewerVersionThan(plugin)) {
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！There is already a new version！", plugin.getPluginName()));
                return true;
            }
        }
        return false;
    }

    /**
     * 卸掉被本次加载取代的旧版本。
     * <p>
     * <b>只能在新模块的 {@code registerSelf()} 返回 true 之后调用</b>——不是「容器建好之后」。
     * 两者差着一步：容器建好只说明 bean 图能构造，模块尚未激活；而 {@code registerSelf()}
     * 返回 false 或抛异常时，调用方会关掉新容器。若旧版本已经在此之前被卸，这个模块就
     * 两头落空，而它原本正在正常运行。
     */
    private void unregisterSupersededVersions(UltiToolsPlugin plugin) {
        for (UltiToolsPlugin existing : pluginList) {
            if (!existing.getMainClass().equals(plugin.getMainClass())) {
                continue;
            }
            if (plugin.isNewerVersionThan(existing)) {
                existing.unregisterSelf();
            }
        }
    }

    private boolean isUltiToolsVersionCompatible(UltiToolsPlugin plugin) {
        if (plugin.getMinUltiToolsVersion() > UltiTools.getPluginVersion()) {
            Bukkit.getLogger().log(Level.WARNING,
                    String.format("[UltiTools-API] %s load failed！UltiTools version is outdated！", plugin.getPluginName()));
            return false;
        }
        return true;
    }

    private boolean attemptPluginRegistration(UltiToolsPlugin plugin) {
        try {
            boolean registerSelf = plugin.registerSelf();
            if (registerSelf) {
                // 卸旧只能放在这里：容器建好了不等于模块活了，registerSelf() 才是模块
                // 宣告自己激活成功的那一步。放在它之前的话，registerSelf 返回 false 或
                // 抛异常时，旧版本已经被卸、新版本的 context 又被 close——这个模块两头
                // 落空，而它原本正在正常运行。
                //
                // 放这里不会与旧版本抢命令：真正注册 Bukkit 命令的 registerBukkit() 在
                // 本方法返回之后才被调用，此刻新版本一条命令都还没注册。
                unregisterSupersededVersions(plugin);
                onPluginRegistered(plugin);
            } else {
                plugin.getContext().close();
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！Version: %s。", plugin.getPluginName(), plugin.getVersion()));
            }
            return registerSelf;
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(Level.WARNING, e, String::new);
            Bukkit.getLogger().log(Level.WARNING, String.format("[UltiTools-API] %s load failed！", plugin.getPluginName()));
            return false;
        }
    }

    private void onPluginRegistered(UltiToolsPlugin plugin) {
        pluginList.add(plugin);
        if (taskManager != null && plugin.getContext() != null) {
            for (Object bean : plugin.getContext().getSingletonValues()) {
                taskManager.registerScheduledMethods(plugin, bean);
            }
        }
        if (playerCacheManager != null && plugin.getContext() != null) {
            for (Object bean : plugin.getContext().getSingletonValues()) {
                playerCacheManager.registerBean(bean);
            }
        }
        // Register @ModuleEventHandler methods with EventBus
        EventBus eventBus = UltiTools.getInstance().getEventBus();
        if (eventBus != null && plugin.getContext() != null) {
            for (Object bean : plugin.getContext().getSingletonValues()) {
                registerModuleEventHandlers(eventBus, plugin, bean);
            }
        }
        Bukkit.getLogger().log(Level.INFO,
                String.format("[UltiTools-API] %s loaded！Version: %s。", plugin.getPluginName(), plugin.getVersion()));
    }

    /**
     * Scan a bean for @ModuleEventHandler methods and register them with the EventBus.
     */
    private void registerModuleEventHandlers(EventBus eventBus, UltiToolsPlugin plugin, Object bean) {
        for (Method method : bean.getClass().getMethods()) {
            ModuleEventHandler annotation = method.getAnnotation(ModuleEventHandler.class);
            if (annotation == null) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !ModuleEvent.class.isAssignableFrom(params[0])) {
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] Invalid @ModuleEventHandler: %s#%s — must have exactly 1 ModuleEvent parameter",
                                bean.getClass().getName(), method.getName()));
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ModuleEvent> eventType = (Class<? extends ModuleEvent>) params[0];
            eventBus.register(eventType, annotation.priority(), annotation.ignoreCancelled(),
                    plugin.getPluginName(), method, bean);
        }
    }

    /**
     * Attaches AOP to a plugin container.
     * <p>
     * Must be called before {@link SimpleContainer#refresh()}: the resolver participates in bean
     * instantiation, so beans created earlier are never proxied.
     * <p>
     * {@code @ExceptionCatch} and {@code @Transactional} are both wired in this release, on all
     * three backends. {@code DataStore} now exposes the plugin's {@code DataSource} via
     * {@code getDataSource(DataScope)}, keyed by database file rather than by entity class, and
     * this method builds one {@code TransactionManager} per plugin container from it and
     * registers a {@code TransactionInterceptor} advisor for that manager, for the two JDBC
     * backends (SQLite, MySQL). Issues #195 and #196 are both addressed for the JDBC backends.
     * The JSON backend has its own {@code TransactionManager} instead (02-05, D-03): a per-plugin
     * snapshot manager that rolls back every JSON operator a {@code @Transactional} method
     * touched, obtained from {@code JsonStore} when {@code getDataSource(DataScope)} throws. Only
     * a {@code DataStore} that can supply neither a {@code DataSource} nor its own
     * {@code TransactionManager} still declares {@code @Transactional} unavailable.
     * <p>
     * <b>Scope limit 1 — {@code registerSingleton} objects are never proxied, but no longer
     * silently unprotected.</b> Only beans the container constructs itself (through
     * {@code registerBean} or component scanning) are ever offered to the resolver, because
     * {@link AopProxyResolver#resolve(Class)} only runs on the constructor branch of bean creation.
     * The plugin instance itself, an {@code @ContextEntry} bean (built by hand with reflection),
     * config entities, {@code @Configuration} classes, and the beans their {@code @Bean} methods
     * produce are all registered with {@code registerSingleton} instead, so none of them can ever be
     * proxied: a ByteBuddy proxy is the bean itself in this framework's design, and it cannot be
     * retrofitted onto an object the caller already constructed. Unlike the first three,
     * {@code @Configuration} classes and {@code @Bean} methods are written by module authors rather
     * than the framework, and they are also constructed before {@code wireAop} runs in
     * {@code initializePlugin}, an independent second reason they can never be proxied.
     * <p>
     * As of 6.3.0, {@code registerSingleton} itself refuses to register an instance whose class
     * carries {@code @Transactional} or {@code @ExceptionCatch} — method-level or class-level — for
     * exactly that reason: an annotation that can never take effect must fail the load rather than
     * silently do nothing (D-15). An instance that is already a generated proxy is exempt, since it
     * already honours its own annotations. {@code registerSingleton} also now fully assembles
     * (autowires, invokes {@code @PostConstruct}, runs the {@code BeanPostProcessor} chain)
     * whatever it does accept, unconditionally and regardless of {@link SimpleContainer#refresh()}
     * state (D-14) — it is only proxying, not assembly, that remains structurally impossible here.
     * See issue #308.
     * <p>
     * <b>Class-level scope.</b> A class-level annotation is a default for the methods the
     * annotated class declares, and for its subclasses. It does not apply to ancestors, so a bean
     * does not extend its own annotation over everything it inherits - in particular not over the
     * framework base classes it extends, where a swallowed exception would become a null that
     * resurfaces as an unrelated failure far from its cause. Inherited methods must be locally
     * redeclared to pick up a subclass's annotation. This is the rule Spring documents for a
     * class-level {@code @Transactional}. A method-level annotation is unaffected: it applies
     * wherever the method is declared.
     * <p>
     * <b>Scope limit 2 - inside that scope, some methods are skipped silently.</b> Two sets.
     * First, methods this proxy cannot both override and reach through {@code super}:
     * {@code private}, {@code static}, {@code final}, package-private ones declared in another
     * package, and the erased half of a generic override that a bridge method shadows. Second,
     * {@code equals(Object)} / {@code hashCode()} / {@code canEqual(Object)}, where swallowing an
     * exception would replace it with a silent wrong answer - Lombok emits all three onto the
     * annotated class itself, so class-level scope does not keep them out. Both skips are silent
     * by deliberate choice and leave no diagnostic. A method-level annotation is skipped only
     * when the proxy cannot reach the method at all, and never quietly: it is named in a
     * startup warning, the way Spring ignores an annotation on a method it cannot advise. The
     * one thing that still fails the load is a final class, which cannot be subclassed at all.
     * <p>
     * <b>Scope limit 3 - one kind of annotation is never seen.</b> An annotation on an
     * <b>interface default method</b>. The scan walks {@code getSuperclass()} only, so
     * {@code @ExceptionCatch} silently does nothing there and {@code @Transactional} is not
     * even refused. Unchanged from 6.2.x, and not fixed here.
     * <p>
     * 本版本同时接线 @ExceptionCatch 与 @Transactional（覆盖全部三种后端）。
     * DataStore 现在通过 getDataSource(DataScope) 暴露插件的 DataSource——按数据库文件而非
     * 实体类分池，本方法据此为每个插件容器构建一个 TransactionManager，并为其注册
     * TransactionInterceptor 通知器，覆盖 SQLite、MySQL 两个 JDBC 后端。issue #195、#196
     * 在 JDBC 后端上均已解决。JSON 后端改为拥有自己的 TransactionManager（02-05，D-03）：
     * 一个按插件快照的管理器，回滚 @Transactional 方法触碰过的每一个 JSON 操作器；
     * 当 getDataSource(DataScope) 抛出异常时从 JsonStore 获取。只有既不能提供 DataSource
     * 也不能提供自身 TransactionManager 的 DataStore，才仍会把 @Transactional 声明为不可用。
     * <p>
     * 范围限制一：以 registerSingleton 方式注册的对象永远不会被代理，但不再是静默不受保护。
     * 插件实例本身、{@code @ContextEntry} bean（反射手工构造）、config 实体、
     * {@code @Configuration} 类及其 {@code @Bean} 方法产出的 bean 都是这样注册的，
     * 它们永远无法被代理——本框架中 ByteBuddy 代理就是 bean 本身，无法在调用方已经构造好的
     * 对象上重新套一层。与前三者不同，{@code @Configuration}/{@code @Bean} 是模块作者自己写的
     * 代码，而且它们在 {@code initializePlugin} 中于 {@code wireAop} 执行前就已构造完成，
     * 这是它们永远不会被代理的另一个独立原因。
     * 自 6.3.0 起，registerSingleton 本身会拒绝注册携带 {@code @Transactional} 或
     * {@code @ExceptionCatch}（方法级或类级）的对象——原因很直接：一个永远不会生效的注解必须
     * 让加载失败，而不是静默地什么也不做（D-15）。已经是生成代理的实例可以豁免，因为它已经
     * 遵循自己的注解。对于它确实接受的对象，registerSingleton 现在也会无条件完成完整装配
     * （自动装配、调用 @PostConstruct、执行 BeanPostProcessor 链），与
     * {@link SimpleContainer#refresh()} 的状态无关（D-14）——这里仍然结构性不可能的只是代理，
     * 不再是装配。见 issue #308。
     * 类级作用域：类级注解是「被注解类自己声明的方法」及其子类的默认值，不作用于祖先类。
     * 因此 bean 不会把自己的注解扩张到它继承来的一切——尤其不会扩张到它继承的框架基类上，
     * 在那里吞掉异常会变成一个 null，并在远离原因的地方以不相干的故障重新浮现。
     * 继承来的方法需在子类中重新声明才会被子类的注解覆盖。这与 Spring 对类级
     * {@code @Transactional} 的既定规则一致。方法级注解不受此限：方法声明在哪里就在哪里生效。
     * 范围限制二：在该作用域内仍有两类被跳过。其一是代理既覆写不了、也 super 不到的方法：
     * {@code private}、{@code static}、{@code final}、声明在别的包里的 package-private 方法，
     * 以及被桥接方法遮蔽的泛型覆写擦除另一半。其二是 {@code equals(Object)} /
     * {@code hashCode()} / {@code canEqual(Object)}——吞掉它们的异常会把一个可见的异常换成
     * 一个静默的错误结果；Lombok 把这三个生成在被注解类自己身上，类级作用域挡不住它们。
     * 两类跳过均为有意静默，不留任何排查线索。方法级注解只在代理根本够不着该方法时才被跳过，
     * 且绝不悄悄跳过：会以启动期警告点名，这与 Spring 对「织不进去的方法上的注解」的处理一致。
     * 唯一仍会让加载失败的是 final 类——它根本无法被继承。见 issue #309。
     * 范围限制三：仍有一类注解完全看不见——<b>接口 default 方法</b>上的注解。扫描只走
     * {@code getSuperclass()}，因此 {@code @ExceptionCatch} 在那里静默失效，
     * {@code @Transactional} 连拒绝都不会触发。与 6.2.x 行为相同，本批未修复。
     *
     * @param context the plugin container, before refresh
     * @param scope   the identity token minted for the caller, used to resolve that plugin's
     *                {@code DataSource} for the {@code @Transactional} advisor <br>
     *                为调用方铸造的身份令牌，用于解析该插件的 {@code DataSource}，供
     *                {@code @Transactional} 通知器使用
     */
    static void wireAop(SimpleContainer context, DataScope scope) {
        AopProxyResolver resolver = new AopProxyResolver();

        // One AnnotationLookupCache instance per annotation type, shared between the advisor and
        // the interceptor instead of each building its own (D-38). The two still ask different
        // questions of it - the advisor's match collapses own-and-inherited into a single presence
        // check, while the interceptor resolves own-method, then class-level, then
        // inherited-method - only the memoized class-level and inherited-method answers are shared.
        AnnotationLookupCache<ExceptionCatch> exceptionCatchCache =
                new AnnotationLookupCache<>(ExceptionCatch.class);

        // The interceptor resolves @ExceptionCatch(handler = "...") beans from THIS container.
        // Reading the global ContextHolder instead would let the last plugin to initialise
        // overwrite every earlier plugin's handler lookup. See issue #190.
        ExceptionInterceptor exceptionInterceptor =
                new ExceptionInterceptor(Collections.emptyList(), context, exceptionCatchCache);
        resolver.addAdvisor(AopAdvisor.forAnnotation(
                ExceptionCatch.class, exceptionInterceptor, 200, exceptionCatchCache));

        wireTransactional(context, scope, resolver);

        resolver.validateAnnotationCoverage();

        context.setAopProxyResolver(resolver);
    }

    /**
     * Builds and registers the {@code @Transactional} advisor for one plugin container. For a
     * backend whose {@code DataStore} exposes a JDBC {@code DataSource} (SQLite, MySQL), the
     * advisor is bound to a {@link DataSourceTransactionManager} -- resolved via {@link
     * #resolveJdbcTransactionManager}, which for these two shipped backends is the SAME instance
     * the store itself wires onto the {@code DataOperator}s it hands out (02-09, T-02-TAM-11), not
     * an independent second one. For the JSON backend, it is bound to a per-plugin snapshot
     * {@code JsonTransactionManager} instead (02-05, D-03), obtained from the store itself. A
     * {@code DataStore} that can supply neither still declares the annotation unavailable, naming
     * the configured backend.
     * <p>
     * 为一个插件容器构建并注册 {@code @Transactional} 通知器。对于其 {@code DataStore} 暴露
     * JDBC {@code DataSource} 的后端（SQLite、MySQL），通知器绑定到
     * {@link DataSourceTransactionManager}——通过 {@link #resolveJdbcTransactionManager} 解析，
     * 对这两个自带后端而言，与该存储自身绑定到其分发的 {@code DataOperator} 上的实例完全相同
     * （02-09，T-02-TAM-11），而非独立构造的第二个；对于 JSON 后端，则改为绑定到按插件快照的
     * {@code JsonTransactionManager}（02-05，D-03），从该存储自身获取。既不能提供以上任何一种的
     * {@code DataStore}，仍会声明该注解不可用，并指明所配置的后端。
     *
     * @param context  the plugin container, before refresh <br> 插件容器，尚未 refresh
     * @param scope    the identity token used to resolve the plugin's {@code DataSource}
     *                 <br> 用于解析插件 {@code DataSource} 的身份令牌
     * @param resolver the resolver {@code wireAop} is assembling <br> {@code wireAop} 正在组装的解析器
     */
    private static void wireTransactional(SimpleContainer context, DataScope scope, AopProxyResolver resolver) {
        DataStore dataStore = UltiTools.getInstance().getDataStore();
        DataSource dataSource;
        try {
            dataSource = dataStore.getDataSource(scope);
        } catch (UnsupportedOperationException e) {
            // Branch on the store's own capability, not on a string comparison of
            // datasource.type (D-04's split already removed backend-name knowledge from this
            // bootstrap; an instanceof check here is the smallest re-introduction of that
            // knowledge that still avoids it living inside JsonStore's own construction logic --
            // see 02-05's SUMMARY for why an interface method on DataStore was out of this plan's
            // scope). A store that is neither JDBC-capable nor JsonStore keeps the pre-6.3.0
            // refusal, naming itself so a third-party DataStore gets an honest answer instead of
            // an NPE.
            if (dataStore instanceof JsonStore) {
                wireJsonTransactional(context, scope, resolver, (JsonStore) dataStore);
                return;
            }
            resolver.addUnavailableAnnotation(Transactional.class,
                    "@Transactional needs a TransactionManager. The configured datasource.type ("
                            + dataStore.getStoreType() + ") does not expose a DataSource or a "
                            + "snapshot-based TransactionManager of its own. Until then use "
                            + "DataOperator.transaction(Callable) explicitly.");
            return;
        }

        // Same reasoning as the shared exceptionCatchCache above (D-38): one AnnotationLookupCache
        // instance per plugin container, constructor-injected into the interceptor, never static.
        AnnotationLookupCache<Transactional> transactionalCache =
                new AnnotationLookupCache<>(Transactional.class);

        // One DataSourceTransactionManager per plugin container (D-01/D-02, FOUND-04), so two
        // plugin containers can never share transaction state. Typed as JdbcTransactionManager
        // here (02-04, D-04) even though the interceptor's own constructor still takes the base
        // TransactionManager type - the local's type is what documents that this advisor is bound
        // to a JDBC-backed manager, not a future JSON one. Registered into the container under
        // the base TransactionManager type so DataOperator/service beans that need to join the
        // active transaction (e.g. via setTransactionManager) can resolve it regardless of
        // backend.
        //
        // resolveJdbcTransactionManager (02-09, T-02-TAM-11) resolves the SAME instance
        // SQLiteDataStore/MysqlDataStore wire onto the operators they hand out for this scope's
        // own database file/identity, instead of constructing an independent second manager here
        // - a @Transactional method calling insertAll must open exactly one transaction, not two.
        JdbcTransactionManager transactionManager = resolveJdbcTransactionManager(dataStore, scope, dataSource);
        context.registerType(TransactionManager.class, transactionManager);

        TransactionInterceptor transactionInterceptor = new TransactionInterceptor(transactionManager);
        // Order 100: below ExceptionCatch's 200 (lower value = higher priority, applied outermost),
        // matching the documented interceptor order Transaction -> Exception -> target, and the
        // "Transaction advisors typically use order 100" convention AopAdvisor#getOrder already
        // documents.
        resolver.addAdvisor(AopAdvisor.forAnnotation(
                Transactional.class, transactionInterceptor, 100, transactionalCache));
    }

    /**
     * Resolves the {@link JdbcTransactionManager} {@link #wireTransactional} should bind the AOP
     * interceptor to, for a JDBC-capable {@code dataStore} (02-09, T-02-TAM-11).
     * <p>
     * For the two backends this framework ships, {@code dataStore} itself owns a per-identity
     * registry of these managers -- {@code SQLiteDataStore.transactionManagerFor(DataScope)} /
     * {@code MysqlDataStore.transactionManagerFor(DataScope)} -- and every {@code getOperator}
     * call on that same store resolves a manager through the identical registry before handing an
     * operator to a module author. Calling that method here, instead of constructing a fresh
     * {@code new DataSourceTransactionManager(dataSource)}, is what makes the two paths converge
     * on one shared instance: a {@code @Transactional} method that calls {@code
     * DataOperator.insertAll} joins the same transaction the interceptor already opened, rather
     * than opening an independent second one on its own connection (a defect worse than no
     * transaction at all, because it looks correct).
     * <p>
     * A third-party {@code DataStore} that supplies a JDBC {@code DataSource} but is neither of
     * the two concrete types this framework ships has no such registry to share -- this method
     * falls back to a manager scoped only to the AOP interceptor, exactly {@code
     * wireTransactional}'s pre-6.3.0 behavior. A {@code DataOperator} that store hands out
     * separately (if any) would not join this manager's transactions; there is no seam on the
     * {@code DataStore} interface (out of this plan's file scope) to close that gap generically.
     *
     * @param dataStore  the store {@link #wireTransactional} resolved a {@code DataSource} from
     *                    <br> {@link #wireTransactional} 解析出 {@code DataSource} 的存储
     * @param scope      the identity token used to resolve the manager <br> 用于解析管理器的身份令牌
     * @param dataSource the {@code DataSource} {@code dataStore.getDataSource(scope)} returned,
     *                    used only for the third-party fallback <br> {@code
     *                    dataStore.getDataSource(scope)} 返回的 {@code DataSource}，仅供第三方
     *                    回退分支使用
     * @return the {@link JdbcTransactionManager} to bind the interceptor to <br> 应绑定到通知器的
     *         {@link JdbcTransactionManager}
     */
    private static JdbcTransactionManager resolveJdbcTransactionManager(
            DataStore dataStore, DataScope scope, DataSource dataSource) {
        if (dataStore instanceof SQLiteDataStore) {
            return ((SQLiteDataStore) dataStore).transactionManagerFor(scope);
        }
        if (dataStore instanceof MysqlDataStore) {
            return ((MysqlDataStore) dataStore).transactionManagerFor(scope);
        }
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Builds and registers the {@code @Transactional} advisor for a plugin container backed by
     * the JSON store, once {@link #wireTransactional} has determined {@code dataStore} cannot
     * supply a JDBC {@code DataSource} but is a {@link JsonStore} (02-05, D-03).
     * <p>
     * 为由 JSON 存储支撑的插件容器构建并注册 {@code @Transactional} 通知器——在
     * {@link #wireTransactional} 判定 {@code dataStore} 无法提供 JDBC {@code DataSource}
     * 但确实是 {@link JsonStore} 之后调用（02-05，D-03）。
     *
     * @param context   the plugin container, before refresh <br> 插件容器，尚未 refresh
     * @param scope     the identity token used to resolve the manager <br> 用于解析管理器的身份令牌
     * @param resolver  the resolver {@code wireAop} is assembling <br> {@code wireAop} 正在组装的解析器
     * @param jsonStore the JSON store to obtain the per-identity manager from <br>
     *                  用于获取按身份划分的管理器的 JSON 存储
     */
    private static void wireJsonTransactional(SimpleContainer context, DataScope scope,
            AopProxyResolver resolver, JsonStore jsonStore) {
        // Same reasoning as the shared exceptionCatchCache above (D-38): one AnnotationLookupCache
        // instance per plugin container, constructor-injected into the interceptor, never static.
        AnnotationLookupCache<Transactional> transactionalCache =
                new AnnotationLookupCache<>(Transactional.class);

        // jsonStore.transactionManagerFor(scope) shares one JsonTransactionManager instance with
        // every SimpleJsonDataOperator that store hands out for this identity (JsonStore binds it
        // at getOperator() time), so a single @Transactional method's writes across several of
        // this plugin's entities are governed by the same transaction (D-03).
        TransactionManager transactionManager = jsonStore.transactionManagerFor(scope);
        context.registerType(TransactionManager.class, transactionManager);

        TransactionInterceptor transactionInterceptor = new TransactionInterceptor(transactionManager);
        // Same order as the JDBC path above -- the interceptor does not know or care which
        // backend built the manager it was given.
        resolver.addAdvisor(AopAdvisor.forAnnotation(
                Transactional.class, transactionInterceptor, 100, transactionalCache));
    }

    /**
     * Initialize module using its default (zero-argument) constructor. This is the live,
     * undeprecated construction path -- {@link #register(Class)} calls it directly.
     * <br>
     * 使用模块的默认（无参）构造器初始化模块。这是当前有效、未废弃的构造路径 -- {@link
     * #register(Class)} 直接调用它。
     *
     * @param classLoader Class loader <br> 类加载器
     * @param pluginClass Plugin class <br> 插件类
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     *         <br> 初始化好的模块；被兼容性门禁拒绝时返回 {@code null}
     */
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass) {
        UltiToolsPlugin plugin;
        try {
            // 使用默认构造器
            Constructor<? extends UltiToolsPlugin> constructor = pluginClass.getDeclaredConstructor();
            plugin = constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }
        return finishInitializingPlugin(classLoader, pluginClass, plugin);
    }

    /**
     * Initialize module using caller-supplied constructor arguments, resolved reflectively.
     * A zero-length {@code constructorArgs} carries nothing to validate reflectively, so it is
     * routed straight through {@link #initializePlugin(ClassLoader, Class)} -- the live,
     * undeprecated path -- instead of running this method's with-args checks against an empty
     * array (SILENT-17).
     * <br>
     * 使用调用方提供的构造器参数，通过反射初始化模块。若 {@code constructorArgs} 长度为零，
     * 没有任何东西需要反射校验，因此直接转发给 {@link #initializePlugin(ClassLoader, Class)}
     * ——当前有效、未废弃的路径——而不是对空数组运行这个方法的带参数校验逻辑（SILENT-17）。
     *
     * @param classLoader     Class loader <br> 类加载器
     * @param pluginClass     Plugin class <br> 插件类
     * @param constructorArgs Constructor arguments <br> 构造器参数
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     *         <br> 初始化好的模块；被兼容性门禁拒绝时返回 {@code null}
     * @deprecated This reflective, with-args construction path has failed on every release
     *             since 6.2.0 (Phase 1 D-15, measured): {@link SecurityPolicy#isSafeParameterType}
     *             matches collection arguments by exact runtime-class-name prefix
     *             ({@code java.util.List}, {@code java.util.ArrayList}, ...), and neither
     *             {@code Arrays.asList(...)}'s runtime type ({@code java.util.Arrays$ArrayList})
     *             nor any {@code Collections.*} wrapper type matches any of those prefixes --
     *             the list-typed arguments a caller actually supplies are rejected before
     *             construction ever runs. This path is scheduled for removal (issue #332)
     *             rather than repair; only the seven-argument {@link #register(Class, String,
     *             String, List, List, int, String)} calls it with a non-empty argument array.
     *             <p>
     *             这条带参数的反射构造路径自 6.2.0 起从未成功过（Phase 1 D-15，已实测）：
     *             {@link SecurityPolicy#isSafeParameterType} 按运行期类名的精确前缀（
     *             {@code java.util.List}、{@code java.util.ArrayList} 等）匹配集合参数，而
     *             {@code Arrays.asList(...)} 的运行期类型（{@code java.util.Arrays$ArrayList}）
     *             和任何 {@code Collections.*} 包装类型都不匹配这些前缀——调用方实际能传入的
     *             List 类型参数因此在构造之前就会被拒绝。这条路径计划移除（issue #332）而非
     *             修复；只有传入非空参数数组的七参 {@link #register(Class, String, String,
     *             List, List, int, String)} 会调用它。
     */
    @Deprecated(since = "6.3.0", forRemoval = true)
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, Object... constructorArgs) {
        if (constructorArgs.length == 0) {
            return initializePlugin(classLoader, pluginClass);
        }

        // 验证构造器参数安全性
        if (!validateConstructorArgs(constructorArgs)) {
            throw new SecurityException("Invalid constructor arguments provided");
        }

        UltiToolsPlugin plugin;
        try {
            // 验证构造器参数类型安全性
            Class<?>[] paramTypes = new Class<?>[constructorArgs.length];
            for (int i = 0; i < constructorArgs.length; i++) {
                if (constructorArgs[i] == null) {
                    throw new SecurityException("Null constructor argument not allowed at index: " + i);
                }
                paramTypes[i] = constructorArgs[i].getClass();

                // 验证参数类型是否安全
                if (!isSafeParameterType(paramTypes[i])) {
                    throw new SecurityException("Unsafe parameter type: " + paramTypes[i].getName());
                }
            }

            Constructor<? extends UltiToolsPlugin> constructor = pluginClass.getDeclaredConstructor(paramTypes);
            plugin = constructor.newInstance(constructorArgs);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }

        return finishInitializingPlugin(classLoader, pluginClass, plugin);
    }

    /**
     * Run the construction-independent second half shared by both {@code initializePlugin}
     * overloads: the compatibility gate, then container assembly. Neither overload forks after
     * construction -- both funnel through this one method.
     * <br>
     * 运行两个 {@code initializePlugin} 重载共用的、与构造过程无关的后半段：先跑兼容性门禁，
     * 再组装容器。两个重载在构造完成之后都不再分叉——都汇入这一个方法。
     *
     * @param classLoader Class loader to attach to the assembled container <br> 挂到组装好的
     *        容器上的类加载器
     * @param pluginClass Plugin class <br> 插件类
     * @param plugin      the already-constructed plugin instance <br> 已经构造好的插件实例
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     *         <br> 初始化好的模块；被兼容性门禁拒绝时返回 {@code null}
     */
    private UltiToolsPlugin finishInitializingPlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, UltiToolsPlugin plugin) {
        // 门禁跑在建容器之前。它读的元数据在实例构造完就已经就位，而 refresh() 之后才检查
        // 等于先让不兼容的 bean 图跑一遍——那正是它会炸的地方。见 passesCompatibilityGates。
        if (!passesCompatibilityGates(plugin)) {
            return null;
        }

        SimpleContainer pluginContext = new SimpleContainer();
        try {
            // WIRE-05: both entry points build their container through this one shared
            // assembly method now -- see its own javadoc for the full instruction sequence and
            // why setContext() runs first. Pass THIS method's own `classLoader` PARAMETER, not
            // the PluginManager field of the same name -- initializePlugin's parameter shadows
            // the field precisely so a caller (register(Class, ...), or a test invoking this
            // method reflectively with its own loader) can hand in a different loader than
            // whatever the field currently holds; losing that distinction during the WIRE-05
            // extraction silently dropped every reflectively-injected test loader back to the
            // field's default null.
            assemblePluginContainer(pluginContext, plugin, pluginClass, classLoader);
            return plugin;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }
    }

    /**
     * Assemble a plugin's container: type registration, component scan, the plugin's own
     * singleton registration, config-entity beans, the static instance field, {@link DataScope}
     * minting plus entity ownership, AOP wiring, {@code refresh()}, and (after {@code refresh()})
     * {@code @ContextEntry} bean registration with {@code autowireBean}. Both {@link
     * #register(UltiToolsPlugin)} and {@link #initializePlugin} route through this one method now,
     * so a capability added to either path is never silently missing from the other (WIRE-05,
     * WIRE-06, #203/#326).
     * <br>
     * 组装一个插件的容器：类型注册、组件扫描、插件自身的单例注册、配置实体 bean、静态 instance
     * 字段、{@link DataScope} 铸造与实体所有权、AOP 装配、{@code refresh()}，以及（在
     * {@code refresh()} 之后）{@code @ContextEntry} bean 注册与 {@code autowireBean}。
     * {@link #register(UltiToolsPlugin)} 与 {@link #initializePlugin} 现在都经过这唯一一个方法，
     * 使添加到某一条路径上的能力不会再悄悄漏掉另一条路径（WIRE-05、WIRE-06，#203/#326）。
     *
     * @param pluginContext a freshly created, not-yet-refreshed container <br> 新建、尚未
     *        {@code refresh()} 的容器
     * @param plugin        the plugin instance <br> 插件实例
     * @param pluginClass   the plugin's class <br> 插件类
     * @param loader        the classloader to attach to {@code pluginContext} -- deliberately a
     *        parameter rather than reading the {@code PluginManager.classLoader} field directly,
     *        so {@code initializePlugin} can pass ITS OWN {@code classLoader} parameter (which
     *        may differ from the field, e.g. under test) exactly as it did before this method
     *        was extracted <br> 挂到 {@code pluginContext} 上的类加载器——刻意做成参数而不是直接
     *        读 {@code PluginManager.classLoader} 字段，这样 {@code initializePlugin} 才能传入它
     *        自己的 {@code classLoader} 参数（可能与字段不同，例如在测试中）——与这个方法被抽出来
     *        之前完全一致
     */
    private void assemblePluginContainer(SimpleContainer pluginContext, UltiToolsPlugin plugin,
            Class<? extends UltiToolsPlugin> pluginClass, ClassLoader loader) {
        // setContext runs BEFORE refresh() -- and before every other step below -- matching
        // register(UltiToolsPlugin)'s pre-existing behaviour (WIRE-05 Task 1 decision). A
        // @PostConstruct method invoked below (registerSingleton assembles unconditionally,
        // D-14) that calls plugin.getContext() must see a valid container, not null.
        // initializePlugin previously set this AFTER refresh(), so every @PostConstruct method
        // on that path silently observed a null context.
        plugin.setContext(pluginContext);

        pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        pluginContext.registerShutdownHook();
        pluginContext.setClassLoader(loader);

        // Register plugin as UltiToolsPlugin type so services can inject it via constructor.
        // This registerType call must run BEFORE scanComponents: ConditionalRegistrationEvaluator
        // (03-04) resolves the plugin via getBean(UltiToolsPlugin.class) during the scan.
        // registerType writes the type registry directly and never goes through
        // registerSingleton, so it is unaffected by full assembly (D-14) and does not need to
        // move.
        pluginContext.registerType(UltiToolsPlugin.class, plugin);

        // Trigger component scanning to discover @CmdExecutor, @EventListener, @Service beans
        String[] scanPackages = getPluginScanPackages(pluginClass);
        if (scanPackages.length > 0) {
            pluginContext.scanComponents(scanPackages);
        }

        // Register the plugin instance itself by name AFTER scanComponents, not before:
        // registerSingleton now fully assembles its argument unconditionally (D-14), so
        // registering the plugin instance before any @Service bean exists would attempt to
        // autowire it against an empty bean graph -- an unresolvable
        // @Autowired(required = true) field on the plugin's own class would then throw, turning a
        // working module into one that cannot load. This is the exact ordering bug T-03-27 fixed
        // in initializePlugin and WR-03 fixed in register(UltiToolsPlugin) -- now a single call
        // site fixes both.
        pluginContext.getBeanFactory().registerSingleton(pluginClass.getSimpleName(), plugin);

        // Register config entities as beans for @Autowired injection
        java.util.Map<String, com.ultikits.ultitools.abstracts.AbstractConfigEntity> configMap =
            UltiTools.getInstance().getConfigManager().getAllConfigEntities(plugin);
        if (configMap != null) {
            for (com.ultikits.ultitools.abstracts.AbstractConfigEntity config : configMap.values()) {
                String beanName = config.getClass().getSimpleName();
                beanName = Character.toLowerCase(beanName.charAt(0)) + beanName.substring(1);
                pluginContext.registerSingleton(beanName, config);
            }
        }

        // Set the static instance field before refresh() so @PostConstruct methods
        // can call Xxx.getInstance().getDataOperator() etc.
        setPluginStaticInstance(pluginClass, plugin);

        DataScope scope = DataScope.forPlugin(plugin, scanPluginEntities(plugin.getPluginName(), plugin.getClass()));
        registerEntityOwnership(scope);
        plugin.setDataScope(scope);
        wireAop(pluginContext, scope);
        // T-05-24 / D-08: attribute any TabCompletionManager.register(...) call made during
        // this plugin's own @PostConstruct (which runs synchronously inside refresh()) to this
        // plugin, so unregister(UltiToolsPlugin) can sweep it on unload without pinning the
        // module's ClassLoader. ClassLoader-derived ownership does not work here: every internal
        // module shares ONE URLClassLoader (see init(ClassLoader) and validateAdditionalEntity's
        // identical D-19 finding above) -- an explicit scope is the only mechanism that still
        // separates two modules' completers.
        TabCompletionManager.getInstance().beginRegistrationScope(plugin.getPluginName());
        try {
            pluginContext.refresh();
        } finally {
            TabCompletionManager.getInstance().endRegistrationScope();
        }

        // @ContextEntry handling (WIRE-06): read after refresh() -- registerSingleton above
        // already fully assembles its argument unconditionally (D-14), so there is nothing left
        // for a second refresh() to do; the refresh() just above already pre-instantiated every
        // non-lazy singleton definition this container knows about.
        if (pluginClass.isAnnotationPresent(ContextEntry.class)) {
            ContextEntry contextEntry = pluginClass.getAnnotation(ContextEntry.class);
            Class<?> clazz = contextEntry.value();
            // Register the context entry class as a bean
            try {
                Object contextBean = clazz.getDeclaredConstructor().newInstance();
                pluginContext.getBeanFactory().registerSingleton(clazz.getSimpleName(), contextBean);
            } catch (Exception e) {
                Bukkit.getLogger().log(
                        Level.WARNING,
                        String.format("[UltiTools-API] Cannot create context entry for %s", clazz.getName())
                );
            }
            pluginContext.getAutowireCapableBeanFactory().autowireBean(plugin);
        }

        // SILENT-11 half 2 (D-01, D-04): every CommandExecutor bean this container just fully
        // assembled -- autowired, @PostConstruct'd, refresh()'d -- is checked here, before
        // registerBukkit() ever hands one to Bukkit's CommandMap. See
        // validateCommandExecutorContracts's own javadoc for the fail-closed contract; both
        // callers of assemblePluginContainer already wrap it in a try/catch that routes any
        // PluginModuleException through logPluginInitializationFailure, so this refusal is
        // module-granular for free -- no new try/catch is added here.
        validateCommandExecutorContracts(pluginContext);
    }

    /**
     * Refuses to load a module whose {@link BaseCommandExecutor} bean declares {@code @CmdCD} or
     * {@code @UsageLimit} -- on a {@code @CmdMapping} method or on the executor class itself --
     * while its OWN validator chain (the exact chain {@code onCommand} dispatches through, not a
     * freshly built default one) holds no instance of the corresponding validator type
     * (SILENT-11 / D-01 half 2, D-04). Every {@link CommandExecutor} bean in {@code pluginContext}
     * is checked; a bean that is not a {@link BaseCommandExecutor} (the legacy
     * {@code AbstractCommandExecutor} generation has no {@link
     * com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} at all) is skipped.
     * <p>
     * This is a STRUCTURAL check only -- it asks whether the required validator TYPE is present
     * in the chain, never whether a given invocation would actually be blocked. No opt-out exists
     * (D-04): a declared cooldown or usage limit that cannot be enforced is always a module-author
     * bug, and Phase 3 D-08's module-granularity isolation -- this refusal alone fails the
     * offending module, every other module in the same load pass still completes -- is the
     * accepted escape hatch.
     * <p>
     * 拒绝加载：某个 {@link BaseCommandExecutor} bean 在 {@code @CmdMapping} 方法上或执行器类本身
     * 声明了 {@code @CmdCD} 或 {@code @UsageLimit}，而它自己的验证器链（{@code onCommand} 实际派发
     * 所经过的那一条，而不是重新构建的默认链）中不包含对应验证器类型的实例
     * （SILENT-11 / D-01 第二部分, D-04）。{@code pluginContext} 中每一个 {@link CommandExecutor}
     * bean 都会被检查；不是 {@link BaseCommandExecutor} 的 bean（旧一代
     * {@code AbstractCommandExecutor} 根本没有 {@link
     * com.ultikits.ultitools.abstracts.command.validation.ValidatorChain}）会被跳过。
     * <p>
     * 这只是一个结构性检查——只问链中是否存在所需验证器的类型，从不问某次具体调用是否真的会被
     * 拦下。此拒绝没有开关（D-04）：一个无法生效的冷却或使用限制声明永远是模块作者的错误，
     * Phase 3 D-08 的模块粒度隔离——只有问题模块本身加载失败，同一次加载中的其余模块仍会完成——
     * 是被接受的退路。
     *
     * @param pluginContext the just-assembled, just-{@code refresh()}ed container to scan for
     *                       {@link CommandExecutor} beans <br> 刚组装、刚 refresh() 的容器，
     *                       扫描其中的 {@link CommandExecutor} bean
     * @throws PluginModuleException fail-closed (D-01, D-04), naming the offending class and,
     *                                when known, the offending mapping method
     */
    static void validateCommandExecutorContracts(SimpleContainer pluginContext) {
        for (String beanName : pluginContext.getBeanNamesForType(CommandExecutor.class)) {
            CommandExecutor commandExecutor = pluginContext.getBean(beanName, CommandExecutor.class);
            if (commandExecutor instanceof BaseCommandExecutor) {
                validateCommandExecutorContract((BaseCommandExecutor) commandExecutor);
            }
        }
    }

    /**
     * The per-executor half of {@link #validateCommandExecutorContracts(SimpleContainer)} --
     * package-private, not private, so {@code PluginManagerCommandContractTest} (same package)
     * can drive it directly with a hand-built fixture, without reflection or
     * {@code setAccessible(true)}. Mirrors the test-seam rationale already used for
     * {@link #logPluginInitializationFailure(String, Throwable)}: this is not {@code public}, so
     * it never enters the published surface.
     * <p>
     * {@code @UsageLimit(LimitType.NONE)} is exempt -- it declares no limit to enforce, so
     * requiring {@code UsageLockValidator} for it would refuse a legitimate no-op declaration.
     *
     * @param executor the already-constructed executor instance <br> 已经构造好的执行器实例
     * @throws PluginModuleException fail-closed (D-01, D-04), naming the offending class and,
     *                                when known, the offending mapping method
     */
    static void validateCommandExecutorContract(BaseCommandExecutor executor) {
        List<CommandValidator> validators = executor.getValidatorChain().getValidators();
        boolean hasCooldownValidator = false;
        boolean hasUsageLockValidator = false;
        for (CommandValidator validator : validators) {
            hasCooldownValidator |= validator instanceof CooldownValidator;
            hasUsageLockValidator |= validator instanceof UsageLockValidator;
        }

        Class<?> executorClass = executor.getClass();

        if (executorClass.isAnnotationPresent(CmdCD.class) && !hasCooldownValidator) {
            throw new PluginModuleException(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE,
                    unenforceableCommandAnnotationMessage(executorClass, null, CmdCD.class, CooldownValidator.class));
        }
        UsageLimit classLevelUsageLimit = executorClass.getAnnotation(UsageLimit.class);
        if (classLevelUsageLimit != null && classLevelUsageLimit.value() != UsageLimit.LimitType.NONE
                && !hasUsageLockValidator) {
            throw new PluginModuleException(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE,
                    unenforceableCommandAnnotationMessage(executorClass, null, UsageLimit.class, UsageLockValidator.class));
        }

        for (Method method : ReflectionUtil.getAllMethods(executorClass)) {
            if (!method.isAnnotationPresent(CmdMapping.class)) {
                continue;
            }
            if (method.isAnnotationPresent(CmdCD.class) && !hasCooldownValidator) {
                throw new PluginModuleException(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE,
                        unenforceableCommandAnnotationMessage(executorClass, method, CmdCD.class, CooldownValidator.class));
            }
            UsageLimit methodUsageLimit = method.getAnnotation(UsageLimit.class);
            if (methodUsageLimit != null && methodUsageLimit.value() != UsageLimit.LimitType.NONE
                    && !hasUsageLockValidator) {
                throw new PluginModuleException(ErrorCode.COMMAND_ANNOTATION_UNENFORCEABLE,
                        unenforceableCommandAnnotationMessage(executorClass, method, UsageLimit.class, UsageLockValidator.class));
            }
            // D-07 / 05-06 Task 2: same walk, same method -- checked alongside the two rules
            // above rather than in a second pass over ReflectionUtil.getAllMethods, so the two
            // rules' ordering stays in one place.
            validateSuggestKeysForMethod(executorClass, method);
            // T-05-fix Part 2: same walk, same method -- a method-name suggest() value that
            // resolves to a signature MethodInvocationCompleter cannot invoke is refused here too.
            validateSuggestMethodSignatureForMethod(executor, executorClass, method);
        }
    }

    /**
     * Builds the refusal message for {@link #validateCommandExecutorContract(BaseCommandExecutor)},
     * naming the offending class and, when {@code method} is non-null, the offending mapping
     * method -- mirroring {@link #additionalEntityRefusalMessage(String, Class, String)}'s
     * message-builder shape.
     *
     * @param executorClass          the offending executor class <br> 问题执行器类
     * @param method                 the offending mapping method, or {@code null} for a
     *                                class-level declaration <br> 问题映射方法；类级声明时为
     *                                {@code null}
     * @param annotationType         the declared annotation ({@code @CmdCD} or
     *                                {@code @UsageLimit}) <br> 声明的注解
     * @param requiredValidatorType  the validator type the chain is missing <br> 链中缺失的验证器类型
     * @return the refusal message <br> 拒绝信息
     */
    private static String unenforceableCommandAnnotationMessage(Class<?> executorClass, Method method,
            Class<? extends Annotation> annotationType, Class<? extends CommandValidator> requiredValidatorType) {
        String location = method != null ? "on method '" + method.getName() + "'" : "at the class level";
        return "Command executor '" + executorClass.getName() + "' declares @" + annotationType.getSimpleName()
                + " " + location + ", but its validator chain contains no "
                + requiredValidatorType.getSimpleName() + " -- the annotation would be silently unenforced. "
                + "Add " + requiredValidatorType.getSimpleName()
                + " to createDefaultValidatorChain() or the ValidatorChain passed to the constructor.";
    }

    /**
     * Validate constructor arguments for security.
    /**
     * D-07's load-time half of {@code @CmdParam.suggest} (05-06 Task 2) -- for {@code method}'s
     * {@code @CmdParam} parameters, a {@code suggest()} value beginning with {@code @} must name a
     * key already registered in {@link TabCompletionManager} at the moment this check runs, i.e.
     * AFTER the module's own {@code pluginContext.refresh()} (and therefore after any completer the
     * module registers during its own {@code @PostConstruct}). Called from inside {@link
     * #validateCommandExecutorContract(BaseCommandExecutor)}'s existing {@code @CmdMapping} method
     * walk -- not a second pass -- so the two checks' ordering stays in one place (D-01, D-04, D-07).
     * <p>
     * A {@code suggest()} value that does NOT start with {@code @} is out of scope here entirely --
     * including one naming a method that does not exist -- because that case keeps the published
     * i18n hint-text fallback ({@code CmdParam.java}'s javadoc), which D-07 leaves deliberately
     * unchanged. No opt-out (D-04): an unknown {@code @key} is always a module-author bug, and
     * module-granularity isolation -- this refusal alone fails the offending module, every other
     * module in the same load pass still completes -- is the accepted escape hatch.
     * <p>
     * D-07 拒绝加载：
     * 对 {@code method} 的每个 {@code @CmdParam} 参数，如果 {@code suggest()} 以 {@code @} 开头，
     * 就要求该键此刻已在 {@link TabCompletionManager} 中注册——即在模块自身的
     * {@code pluginContext.refresh()} 之后（因此也在模块自己 {@code @PostConstruct} 期间注册的任何
     * 补全器之后）。
     *
     * @param executorClass the executor class {@code method} belongs to, for the refusal message
     *                      <br> {@code method} 所属的执行器类，用于构造拒绝信息
     * @param method        the {@code @CmdMapping} method whose parameters are checked
     *                      <br> 要检查其参数的 {@code @CmdMapping} 方法
     * @throws PluginModuleException fail-closed (D-07), naming the class, the method and the key
     */
    private static void validateSuggestKeysForMethod(Class<?> executorClass, Method method) {
        for (Parameter parameter : method.getParameters()) {
            CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
            if (cmdParam == null) {
                continue;
            }
            String suggest = cmdParam.suggest();
            if (suggest.isEmpty() || suggest.charAt(0) != '@') {
                continue;
            }
            if (TabCompletionManager.getInstance().getCompleter(suggest) == null) {
                throw new PluginModuleException(ErrorCode.COMMAND_SUGGEST_KEY_UNKNOWN,
                        unknownSuggestKeyMessage(executorClass, method, suggest));
            }
        }
    }

    /**
     * Builds the refusal message for {@link #validateSuggestKeysForMethod(Class, Method)}, naming
     * the offending class, method and key -- mirroring {@link
     * #unenforceableCommandAnnotationMessage(Class, Method, Class, Class)}'s message-builder shape.
     *
     * @param executorClass the offending executor class <br> 问题执行器类
     * @param method        the offending mapping method <br> 问题映射方法
     * @param key           the unknown {@code @key} <br> 未知的 {@code @key}
     * @return the refusal message <br> 拒绝信息
     */
    private static String unknownSuggestKeyMessage(Class<?> executorClass, Method method, String key) {
        return "Command executor '" + executorClass.getName() + "' declares @CmdParam(suggest = \""
                + key + "\") on method '" + method.getName() + "', but no completer is registered "
                + "under that key. Register a completer for '" + key + "' via "
                + "TabCompletionManager.getInstance().register(...) before this module loads, or fix "
                + "the typo -- an unknown @key does not fall back to the i18n hint text (D-07).";
    }

    /**
     * T-05-fix Part 2's load-time half of method-name {@code @CmdParam.suggest()} resolution: a
     * {@code suggest()} value that does NOT start with {@code @} names a method looked up EXACTLY
     * as {@link MethodInvocationCompleter#complete(TabCompletionContext)} looks it up at
     * completion time -- {@link MethodInvocationCompleter#getSuggestMethodsByName(Object, String)}
     * checks the executor's own class first, then any {@code @CmdSuggest}-referenced classes.
     * <p>
     * A real-machine UAT run on Paper 1.21.11 caught {@code MethodInvocationCompleter
     * .invokeSuggestMethod} falling into a final {@code else} branch that invoked ANY
     * unrecognized signature with ZERO arguments -- silently wrong, not a failure, so it surfaced
     * only as {@code IllegalArgumentException} the first time a player pressed Tab. 16 of 24 real
     * downstream {@code @CmdParam(suggest=)} call sites (every UltiWorlds suggest method, shaped
     * {@code (Player, String)}) were broken this way. This check closes the gap at its source:
     * if the resolved suggest method's signature is not one of the five shapes {@link
     * MethodInvocationCompleter#isInvocableSuggestSignature(Class[])} accepts, the module is
     * refused at load -- naming the class, the mapping method and the offending signature --
     * instead of loading cleanly and failing only on first invocation.
     * <p>
     * A {@code suggest()} value that resolves to NO method at all is out of scope here, exactly
     * as it is for {@link #validateSuggestKeysForMethod(Class, Method)} -- that keeps the
     * published i18n hint-text fallback, deliberately unchanged (D-07). {@link
     * MethodInvocationCompleter#complete(TabCompletionContext)} only ever invokes the FIRST
     * method {@code getSuggestMethodsByName} returns when a name resolves to more than one
     * overload, so only that first method's signature is validated here -- validating every
     * overload would refuse a module for an overload that is never actually called.
     *
     * @param executor      the already-constructed executor instance, needed for the SAME class
     *                       hierarchy / {@code @CmdSuggest} lookup {@link
     *                       MethodInvocationCompleter} performs at completion time <br> 已构造的
     *                       执行器实例
     * @param executorClass the executor class {@code method} belongs to, for the refusal message
     *                      <br> {@code method} 所属的执行器类，用于构造拒绝信息
     * @param method        the {@code @CmdMapping} method whose parameters are checked
     *                      <br> 要检查其参数的 {@code @CmdMapping} 方法
     * @throws PluginModuleException fail-closed, naming the class, the method and the offending
     *                                signature
     */
    private static void validateSuggestMethodSignatureForMethod(BaseCommandExecutor executor,
            Class<?> executorClass, Method method) {
        for (Parameter parameter : method.getParameters()) {
            CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
            if (cmdParam == null) {
                continue;
            }
            String suggest = cmdParam.suggest();
            if (suggest.isEmpty() || suggest.charAt(0) == '@') {
                continue;
            }
            Method[] suggestMethods = MethodInvocationCompleter.getSuggestMethodsByName(executor, suggest);
            if (suggestMethods == null || suggestMethods.length == 0) {
                continue; // unknown method name -- D-07 keeps the i18n hint fallback, out of scope here
            }
            Method suggestMethod = suggestMethods[0];
            if (!MethodInvocationCompleter.isInvocableSuggestSignature(suggestMethod.getParameterTypes())) {
                throw new PluginModuleException(ErrorCode.COMMAND_SUGGEST_METHOD_UNINVOCABLE,
                        uninvocableSuggestMethodMessage(executorClass, method, suggestMethod));
            }
        }
    }

    /**
     * Builds the refusal message for {@link #validateSuggestMethodSignatureForMethod(
     * BaseCommandExecutor, Class, Method)}, naming the offending class, the mapping method and
     * the offending suggest method's declaring class, name and parameter signature -- mirroring
     * {@link #unknownSuggestKeyMessage(Class, Method, String)}'s message-builder shape.
     *
     * @param executorClass  the offending executor class <br> 问题执行器类
     * @param mappingMethod  the offending {@code @CmdMapping} method <br> 问题映射方法
     * @param suggestMethod  the resolved suggest method with the uninvocable signature
     *                       <br> 签名无法调用的建议方法
     * @return the refusal message <br> 拒绝信息
     */
    private static String uninvocableSuggestMethodMessage(Class<?> executorClass, Method mappingMethod,
            Method suggestMethod) {
        return "Command executor '" + executorClass.getName() + "' declares @CmdParam(suggest = \""
                + suggestMethod.getName() + "\") on method '" + mappingMethod.getName() + "', but '"
                + suggestMethod.getDeclaringClass().getName() + "#" + suggestMethod.getName()
                + formatParameterTypes(suggestMethod.getParameterTypes()) + "' has a signature the "
                + "tab-completion invoker cannot call. Supported signatures: (), (Player), (String), "
                + "(Player, String), (Player, Command, String[]).";
    }

    /**
     * Formats {@code paramTypes} as a Java-source-like parameter list, e.g. {@code "(int)"} or
     * {@code "(Player, String)"}, for {@link #uninvocableSuggestMethodMessage(Class, Method,
     * Method)}.
     *
     * @param paramTypes the parameter types to format <br> 要格式化的参数类型
     * @return the formatted parameter list, including the enclosing parentheses <br> 格式化后的
     *         参数列表（含括号）
     */
    private static String formatParameterTypes(Class<?>[] paramTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(paramTypes[i].getSimpleName());
        }
        return builder.append(")").toString();
    }

    /**
     * Validate constructor arguments for security.
     * <br>
     * 验证构造器参数的安全性。
     *
     * @param args constructor arguments <br> 构造器参数
     * @return true if safe, false otherwise <br> 如果安全则为true，否则为false
     */
    private boolean validateConstructorArgs(Object... args) {
        if (args == null) {
            return true; // null args array is acceptable
        }
        
        // 限制参数数量
        if (args.length > 10) {
            Bukkit.getLogger().log(Level.WARNING, 
                "[UltiTools-API] Too many constructor arguments: " + args.length);
            return false;
        }
        
        for (Object arg : args) {
            if (arg == null) {
                continue; // null individual args will be checked later
            }
            
            // 检查是否是危险类型
            Class<?> argClass = arg.getClass();
            if (!isSafeParameterType(argClass)) {
                Bukkit.getLogger().log(Level.WARNING, 
                    "[UltiTools-API] Unsafe constructor argument type: " + argClass.getName());
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Check if parameter type is safe for constructor injection.
     * <br>
     * 检查参数类型是否对构造器注入安全。
     *
     * @param clazz parameter class <br> 参数类
     * @return true if safe, false otherwise <br> 如果安全则为true，否则为false
     */
    private boolean isSafeParameterType(Class<?> clazz) {
        return SecurityPolicy.isSafeParameterType(clazz);
    }

    /**
     * Register bukkit commands or listeners.
     * <p>
     * Both registration entry points ({@link #register(Class)} and {@link
     * #register(UltiToolsPlugin)}) now resolve commands and listeners as beans from the module's
     * own container -- there is no longer a package-scanning branch here. {@code manualRegister()}
     * and {@code @ConditionalOnConfig} are therefore honoured identically no matter which entry
     * point loaded the module (WIRE-05 differences #6-#9, plan 04-08), and a command class
     * extending the current {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor}
     * can no longer reach {@link CommandManager}'s legacy, casting {@code registerAll(UltiToolsPlugin,
     * String)} overload on either path (issue #272). The package-scanning overloads on {@link
     * CommandManager} and {@link ListenerManager} are retained -- both {@code @Deprecated(forRemoval
     * = true)} -- for downstream callers only.
     * <br>
     * 注册Bukkit命令或监听器。
     * <p>
     * 两个注册入口点（{@link #register(Class)} 与 {@link #register(UltiToolsPlugin)}）现在都从
     * 模块自身的容器中按 bean 解析命令与监听器——这里不再有包扫描分支。因此无论模块通过哪个入口点
     * 加载，{@code manualRegister()} 与 {@code @ConditionalOnConfig} 都会被同等遵循
     * （WIRE-05 差异 #6-#9，计划 04-08），命令类只要继承当前的
     * {@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor}，
     * 在任一路径上都不会再触达 {@link CommandManager} 那个做强转的旧版
     * {@code registerAll(UltiToolsPlugin, String)} 重载（issue #272）。
     * {@link CommandManager} 与 {@link ListenerManager} 上的包扫描重载被保留——两者都已标注
     * {@code @Deprecated}（{@code forRemoval} 为 true）——仅供下游调用方使用。
     *
     * @param plugin UltiTools module instance <br> UltiTools模块实例
     */
    private void registerBukkit(UltiToolsPlugin plugin) {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(plugin.getClass(), EnableAutoRegister.class);
        if (annotation == null) {
            return;
        }
        if (annotation.cmdExecutor()) {
            UltiTools.getInstance().getCommandManager().registerAll(plugin);
        }
        if (annotation.eventListener()) {
            UltiTools.getInstance().getListenerManager().registerAll(plugin);
        }
    }
    
    /**
     * Set the static instance field on a plugin class before @PostConstruct runs.
     * Most modules follow the pattern: private static XxxPlugin instance;
     * This enables @PostConstruct methods to call Xxx.getInstance().getDataOperator() etc.
     * <br>
     * 在 @PostConstruct 运行之前设置插件类的静态 instance 字段。
     *
     * @param pluginClass the plugin class <br> 插件类
     * @param plugin      the plugin instance <br> 插件实例
     */
    private void setPluginStaticInstance(Class<? extends UltiToolsPlugin> pluginClass, UltiToolsPlugin plugin) {
        try {
            java.lang.reflect.Field instanceField = pluginClass.getDeclaredField("instance");
            if (Modifier.isStatic(instanceField.getModifiers())) {
                instanceField.setAccessible(true); // NOPMD - required for plugin instance injection
                instanceField.set(null, plugin);
            }
        } catch (NoSuchFieldException ignored) {
            // Not all plugins have a static instance field — this is fine
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.FINE,
                "[UltiTools-API] Could not pre-set static instance for " + pluginClass.getName());
        }
    }

    /**
     * Get scan packages for a plugin class.
     * Reads from @UltiToolsModule or @ComponentScan annotations, defaults to plugin class package.
     * <br>
     * 获取插件类的扫描包。
     *
     * @param pluginClass plugin class <br> 插件类
     * @return scan packages <br> 扫描包
     */
    private String[] getPluginScanPackages(Class<? extends UltiToolsPlugin> pluginClass) {
        // Read through the merged resolver, not a bare pluginClass.getAnnotation(...) --
        // @UltiToolsModule is meta-annotated @ComponentScan, and its scanBasePackages()/
        // scanBasePackageClasses() attributes both declare @AliasFor onto ComponentScan's
        // basePackages()/basePackageClasses(), so the merged instance already carries the
        // module-level values (D-01). Measured: this replaces the old direct
        // module.scanBasePackages() read entirely -- the merged resolution already covers it,
        // so keeping a second hand-read alongside it would just be the per-site special-casing
        // D-01 rejected, reintroduced one attribute later.
        ComponentScan merged = MergedAnnotationResolver.find(pluginClass, ComponentScan.class);
        // Additive, not first-match: every source that declares a non-empty value contributes
        // packages, in declaration order, with duplicates collapsed to their first occurrence
        // (GEN-06). The old first-match-wins shape silently dropped every source after the
        // first non-empty one.
        LinkedHashSet<String> scanPackages = new LinkedHashSet<>();
        if (merged != null) {
            Collections.addAll(scanPackages, merged.value());
            Collections.addAll(scanPackages, merged.basePackages());
            for (Class<?> markerClass : merged.basePackageClasses()) {
                // Class.getPackage() is null for an array type/primitive/void -- skip rather
                // than fold a null entry into the scan set (T-03-31).
                Package markerPackage = markerClass.getPackage();
                if (markerPackage != null) {
                    scanPackages.add(markerPackage.getName());
                }
            }
        }
        if (scanPackages.isEmpty()) {
            // Default to the package of the plugin class itself, exactly as before.
            scanPackages.add(pluginClass.getPackage().getName());
        }
        return scanPackages.toArray(new String[0]);
    }


    /**
     * Sort plugins by their dependencies using Kahn's algorithm (topological sort).
     * <p>
     * A dependency cycle or a missing hard dependency no longer degrades every module to
     * filesystem order (SILENT-08/D-10): only the affected module(s) - the cycle/missing
     * declaration plus everything transitively depending on it - are absent from the returned
     * list; every unrelated module still loads. The pre-6.3.0 all-unsorted behaviour survives
     * only as an explicit, cost-stating opt-in via {@code ultitools.useLegacyPluginLoading}.
     * <br>
     * 使用 Kahn 算法（拓扑排序）按依赖关系对插件进行排序。
     * <p>
     * 一个依赖环或一个缺失的硬依赖不再让所有模块退化为文件系统顺序（SILENT-08/D-10）：
     * 只有受影响的模块——环成员/缺失声明本身及所有传递依赖它们的模块——会从返回列表中
     * 缺席；其余每个模块仍然加载。6.3.0 之前"全部退化"的行为只能通过
     * {@code ultitools.useLegacyPluginLoading} 这个显式的、会说明代价的开关继续存在。
     *
     * @param plugins list of plugin classes to sort <br> 要排序的插件类列表
     * @return sorted list of plugin classes <br> 排序后的插件类列表
     */
    private List<Class<? extends UltiToolsPlugin>> sortPluginsByDependencies(
            List<Class<? extends UltiToolsPlugin>> plugins) {

        if (Boolean.getBoolean(LEGACY_PLUGIN_LOADING_PROPERTY)) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Legacy unsorted plugin load order is ACTIVE because "
                    + "-Dultitools.useLegacyPluginLoading=true is set on the command line. "
                    + "Dependency resolution is skipped entirely - modules load in filesystem "
                    + "order and may fail to initialize if they rely on load order.");
            return new ArrayList<>(plugins);
        }

        PluginDependencyResolver resolver = new PluginDependencyResolver(Bukkit.getLogger());

        try {
            List<Class<? extends UltiToolsPlugin>> sorted = resolver.resolve(plugins);
            Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Plugin load order resolved successfully.");
            return sorted;
        } catch (CircularDependencyException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Circular dependency detected among plugins.");
            for (List<String> cyclePath : e.getCyclePaths()) {
                Bukkit.getLogger().log(Level.SEVERE,
                    "[UltiTools-API]   Loop: " + String.join(" -> ", cyclePath));
                Bukkit.getLogger().log(Level.SEVERE,
                    "[UltiTools-API]   Please have the author of '" + cyclePath.get(0)
                        + "' fix the circular dependency.");
            }
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] The cycle members and their dependents are excluded from this "
                    + "load; every other module still loads. Set "
                    + "-Dultitools.useLegacyPluginLoading=true to restore the old unsorted load "
                    + "order instead (not recommended: modules may fail to initialize in an "
                    + "unpredictable order).");
            return new ArrayList<>(e.getSortedPrefix());
        } catch (MissingDependencyException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] A required plugin dependency is missing.");
            Bukkit.getLogger().log(Level.SEVERE, "[UltiTools-API] " + e.getMessage());
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] The declaring module and its dependents are excluded from this "
                    + "load; every other module still loads. Set "
                    + "-Dultitools.useLegacyPluginLoading=true to restore the old unsorted load "
                    + "order instead (not recommended: modules may fail to initialize in an "
                    + "unpredictable order).");
            return new ArrayList<>(e.getSortedPrefix());
        }
    }

    /**
     * Register an external Bukkit plugin adapter with the framework.
     * Creates an IoC container, scans for annotated components, and registers commands/listeners/tasks.
     * <p>
     * 注册外部 Bukkit 插件适配器到框架中。
     * 创建 IoC 容器，扫描注解组件，并注册命令、监听器和定时任务。
     *
     * @param adapter the external plugin adapter
     * @since 6.2.2
     */
    public void registerExternal(ExternalPluginAdapter adapter) {
        registerExternal(adapter, new Class<?>[0]);
    }

    /**
     * Register an external Bukkit plugin adapter with the framework, declaring entity classes
     * that legitimately live outside the plugin's own JAR (D-19), in addition to the entities
     * found by scanning the plugin's own JAR for {@code @Table} classes.
     * <p>
     * 注册外部 Bukkit 插件适配器到框架中，并声明合法存放在插件自身 JAR 之外的实体类（D-19），
     * 作为对扫描插件自身 JAR 得到的 {@code @Table} 实体集合的补充。
     *
     * @param adapter            the external plugin adapter <br> 外部插件适配器
     * @param additionalEntities entity classes owned by this plugin that live outside its own JAR
     *                           <br> 该插件拥有、但存放在自身 JAR 之外的实体类
     * @since 6.3.0
     */
    public void registerExternal(ExternalPluginAdapter adapter, Class<?>[] additionalEntities) {
        // 1. Create child IoC container
        SimpleContainer context = new SimpleContainer();
        context.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        context.registerShutdownHook();
        context.setClassLoader(adapter.getPluginClassLoader());

        // Register the connector's own JavaPlugin so services can inject it via constructor.
        // Must run BEFORE scanComponents, mirroring initializePlugin's own T-03-27 fix
        // (:1524): registerType writes the type registry directly and never goes through
        // registerSingleton, so it is unaffected by full assembly (D-14) and does not need to
        // move.
        //
        // The parent container holds the CORE UltiTools instance under "ultiTools"
        // (DependenceManagers:34), and UltiTools extends JavaPlugin -- without this
        // registration a constructor parameter of type JavaPlugin would miss this (empty)
        // child, walk up, and isInstance-match that core instance instead (SILENT-16, #331).
        // registerType keys by exact Class, so a constructor parameter declared as the
        // connector's own concrete class needs a second registration under that class too.
        JavaPlugin javaPlugin = adapter.getJavaPlugin();
        context.registerType(JavaPlugin.class, javaPlugin);
        registerOwnType(context, javaPlugin);

        // 2. Scan components in the external plugin's package
        if (!adapter.getScanPackage().isEmpty()) {
            context.scanComponents(new String[]{adapter.getScanPackage()});
        }

        // 3. Refresh container to instantiate beans
        DataScope scope = DataScope.forExternal(adapter.getPluginName(), adapter.getDataFolder(),
                scanExternalEntities(adapter, additionalEntities));
        // registerExternalScope runs FIRST (02-14 Task 2): if it refuses (another plugin already
        // holds this data folder), nothing below has run yet -- no entity ownership recorded, no
        // DataScope attached to the adapter. A refused registerExternal() leaves no partial state.
        registerExternalScope(adapter.getDataFolder(), scope);
        registerEntityOwnership(scope);
        adapter.setDataScope(scope);
        wireAop(context, scope);
        context.refresh();
        adapter.setContext(context);

        // WR-01 (05-REVIEW.md): the External Plugin API's own registration path never reached
        // validateCommandExecutorContracts -- register(UltiToolsPlugin)/initializePlugin already
        // enforce it (assemblePluginContainer's own last line), but registerExternal is a
        // separate, parallel container-assembly path that built its own SimpleContainer and
        // skipped straight to task/listener/command registration. Placed here -- immediately
        // after refresh(), before ANY Bukkit-facing side effect (task scheduling, @PlayerCache
        // registration, EventBus wiring, command/listener registration) -- mirroring the internal
        // path's placement as the last step of container assembly, so a refusal leaves no partial
        // registration on either path (fail-closed, module-granularity isolation, D-01/D-04).
        validateCommandExecutorContracts(context);

        String pluginName = adapter.getPluginName();

        // 4. Register @Scheduled tasks
        if (taskManager != null) {
            for (Object bean : context.getSingletonValues()) {
                taskManager.registerScheduledMethodsExternal(pluginName, bean);
            }
        }

        // 5. Register @PlayerCache beans
        if (playerCacheManager != null) {
            for (Object bean : context.getSingletonValues()) {
                playerCacheManager.registerBean(bean);
            }
        }

        // 6. Register @ModuleEventHandler with EventBus
        EventBus eventBus = UltiTools.getInstance().getEventBus();
        if (eventBus != null) {
            for (Object bean : context.getSingletonValues()) {
                registerModuleEventHandlersExternal(eventBus, pluginName, bean);
            }
        }

        // 7. Register commands and listeners
        UltiTools.getInstance().getCommandManager().registerAllExternal(adapter);
        UltiTools.getInstance().getListenerManager().registerAllExternal(adapter);

        Bukkit.getLogger().log(Level.INFO,
                "[UltiTools-API] External plugin registered: " + pluginName + " v" + adapter.getVersion());
    }

    /**
     * Register an object into {@code context} keyed by its own concrete runtime class, in
     * addition to whatever declared-type registration the caller has already done. Needed
     * because {@code SimpleContainer.registerType(Class, T)} keys by exact {@code Class}: a
     * constructor parameter declared as the connector's own concrete plugin class (rather than
     * the general {@code JavaPlugin} type) would otherwise never resolve (SILENT-16, #331).
     * <p>
     * The unchecked cast is safe: {@code instance.getClass()} is always assignable to itself, so
     * capturing it as {@code Class<T>} for the same {@code instance} of static type {@code T}
     * never mismatches at runtime.
     * <br>
     * 把对象以它自己的具体运行时类为键注册进 {@code context}，作为调用方已经完成的声明类型注册
     * 之外的补充。之所以需要这一步，是因为 {@code SimpleContainer.registerType(Class, T)}
     * 按精确的 {@code Class} 作为键：如果构造函数参数声明的是连接器自己的具体插件类
     * （而不是通用的 {@code JavaPlugin} 类型），否则永远无法解析（SILENT-16，#331）。
     * <p>
     * 这里的非受检转换是安全的：{@code instance.getClass()} 总能赋值给它自身，所以对静态类型为
     * {@code T} 的同一个 {@code instance}，把它捕获为 {@code Class<T>} 在运行时永远不会不匹配。
     *
     * @param context  the container to register into <br> 要注册进去的容器
     * @param instance the object to register under its own concrete class <br>
     *                 要按自身具体类注册的对象
     * @param <T> the object's static type <br> 该对象的静态类型
     */
    @SuppressWarnings("unchecked")
    private static <T> void registerOwnType(SimpleContainer context, T instance) {
        Class<T> ownType = (Class<T>) instance.getClass();
        context.registerType(ownType, instance);
    }

    /**
     * Unregister an external Bukkit plugin adapter from the framework.
     * Tears down the IoC container, unregisters commands/listeners, and cleans up resources.
     * <p>
     * 从框架中注销外部 Bukkit 插件适配器。
     * 拆除 IoC 容器，注销命令和监听器，并清理资源。
     *
     * @param adapter the external plugin adapter
     * @since 6.2.2
     */
    public void unregisterExternal(ExternalPluginAdapter adapter) {
        String pluginName = adapter.getPluginName();

        // Cancel @Scheduled tasks
        if (taskManager != null) {
            taskManager.cancelAllExternal(pluginName);
        }

        // Unregister @PlayerCache beans
        if (playerCacheManager != null && adapter.getContext() != null) {
            for (Object bean : adapter.getContext().getSingletonValues()) {
                playerCacheManager.unregisterBean(bean);
            }
        }

        // Unregister @ModuleEventHandler from EventBus
        EventBus eventBus = UltiTools.getInstance().getEventBus();
        if (eventBus != null) {
            eventBus.unregisterAll(pluginName);
        }

        // Unregister commands and listeners
        UltiTools.getInstance().getCommandManager().unregisterAllExternal(pluginName);
        UltiTools.getInstance().getListenerManager().unregisterAllExternal(pluginName);

        // Close IoC container
        if (adapter.getContext() != null) {
            adapter.getContext().close();
            adapter.setContext(null);
        }

        // Clear the folder->scope registration (02-14 Task 2) so a fresh registerExternal(...) for
        // the same folder -- a legitimate reconnect -- is not refused by registerExternalScope's
        // displacement guard as if the folder were still held by a different plugin. This was
        // previously left behind entirely (a second, disclosed finding beyond the displacement
        // guard itself): unregisterExternal never removed the entry, so findScopeForDataFolder
        // kept resolving a disconnected plugin's stale scope indefinitely.
        unregisterExternalScope(adapter.getDataFolder());

        Bukkit.getLogger().log(Level.INFO,
                "[UltiTools-API] External plugin unregistered: " + pluginName);
    }

    /**
     * Scan a bean for @ModuleEventHandler methods and register them with EventBus for an external plugin.
     */
    private void registerModuleEventHandlersExternal(EventBus eventBus, String pluginName, Object bean) {
        for (Method method : bean.getClass().getMethods()) {
            ModuleEventHandler annotation = method.getAnnotation(ModuleEventHandler.class);
            if (annotation == null) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !ModuleEvent.class.isAssignableFrom(params[0])) {
                Bukkit.getLogger().log(Level.WARNING,
                        String.format("[UltiTools-API] Invalid @ModuleEventHandler: %s#%s — must have exactly 1 ModuleEvent parameter",
                                bean.getClass().getName(), method.getName()));
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ModuleEvent> eventType = (Class<? extends ModuleEvent>) params[0];
            eventBus.register(eventType, annotation.priority(), annotation.ignoreCancelled(),
                    pluginName, method, bean);
        }
    }
}
