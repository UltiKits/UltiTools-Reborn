package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.aop.AnnotationLookupCache;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ExceptionInterceptor;
import com.ultikits.ultitools.aop.TransactionInterceptor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.api.UltiToolsAPI;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.JdbcTransactionManager;
import com.ultikits.ultitools.interfaces.TransactionManager;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonStore;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataStore;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataStore;
import com.ultikits.ultitools.manager.PluginDependencyResolver.CircularDependencyException;
import com.ultikits.ultitools.manager.PluginDependencyResolver.MissingDependencyException;
import com.ultikits.ultitools.utils.ClassLoaderUtils;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.SecurityPolicy;

import lombok.Getter;

/**
 * UltiTools plugin manager.
 * <p>
 * UltiTools模块管理器
 */
public class PluginManager {
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
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s: %s", pluginClass.getName(), e.getMessage()),
                    e
            );
            return false;
        }
        // null 表示兼容性门禁拒了它，拒绝理由已经打过日志，这里不要再包一层通用错误。
        if (plugin == null) {
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin, true);
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
     */
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
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s: %s", pluginClass.getName(), e.getMessage()),
                    e
            );
            return false;
        }
        // 同上：null 是门禁拒绝，不是初始化失败。
        if (plugin == null) {
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin, false);
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
            SimpleContainer pluginContext = new SimpleContainer();
            plugin.setContext(pluginContext);
            pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
            pluginContext.registerShutdownHook();
            pluginContext.setClassLoader(classLoader);

            // Register the plugin as UltiToolsPlugin type so services can inject it via
            // constructor. Must run BEFORE scanComponents, mirroring initializePlugin's own
            // T-03-27 fix: registerType writes the type registry directly and never goes through
            // registerSingleton, so it is unaffected by full assembly (D-14) and does not need to
            // move.
            pluginContext.registerType(UltiToolsPlugin.class, plugin);

            // Trigger component scanning to discover @CmdExecutor, @EventListener, @Service beans
            // BEFORE the plugin instance itself is registered as a singleton below (WR-03). This
            // overload had no scanComponents call at all until this fix -- unlike initializePlugin,
            // it was never given the T-03-27 reordering, even though it registers the same kind of
            // object through the same registerSingleton call two lines below.
            String[] scanPackages = getPluginScanPackages(plugin.getClass());
            if (scanPackages.length > 0) {
                pluginContext.scanComponents(scanPackages);
            }

            // Register the plugin instance itself by name AFTER scanComponents, not before:
            // registerSingleton now fully assembles its argument unconditionally (D-14), so
            // registering the plugin instance before any @Service bean exists would attempt to
            // autowire it against an empty bean graph -- an unresolvable
            // @Autowired(required = true) field on the plugin's own class would then throw,
            // turning a working module into one that cannot load. This is the exact ordering bug
            // T-03-27 already fixed in initializePlugin; this overload had it too (WR-03).
            pluginContext.getBeanFactory().registerSingleton(plugin.getClass().getSimpleName(), plugin);
            DataScope scope = DataScope.forPlugin(plugin, scanPluginEntities(plugin.getPluginName(), plugin.getClass()));
            registerEntityOwnership(scope);
            plugin.setDataScope(scope);
            wireAop(pluginContext, scope);
            pluginContext.refresh();
            if (plugin.getClass().isAnnotationPresent(ContextEntry.class)) {
                ContextEntry contextEntry = plugin.getClass().getAnnotation(ContextEntry.class);
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
                // No second refresh() here (D-14): registerSingleton above now fully assembles the
                // @ContextEntry bean itself unconditionally, so there is nothing left for a second
                // refresh() to do -- the first refresh() at the top of this method already
                // pre-instantiated every non-lazy singleton definition this container knows about.
                pluginContext.getAutowireCapableBeanFactory().autowireBean(plugin);
            }
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] Cannot initialize plugin for %s: %s", plugin.getPluginName(), e.getMessage()),
                    e
            );
            return false;
        }
        boolean result = attemptPluginRegistration(plugin);
        if (result) {
            registerBukkit(plugin, false);
        }
        return result;
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
        // Unregister @ModuleEventHandler handlers from EventBus
        EventBus eventBus = UltiTools.getInstance().getEventBus();
        if (eventBus != null) {
            eventBus.unregisterAll(plugin.getPluginName());
        }
        UltiTools.getInstance().getListenerManager().unregisterAll(plugin);
        plugin.unregisterSelf();
        plugin.getContext().close();
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
     * Initialize module.
     * <br>
     * 初始化模块。
     *
     * @param classLoader     Class loader <br> 类加载器
     * @param pluginClass     Plugin class <br> 插件类
     * @param constructorArgs Constructor arguments <br> 构造器参数
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     *         <br> 初始化好的模块；被兼容性门禁拒绝时返回 {@code null}
     */
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, Object... constructorArgs) {
        // 验证构造器参数安全性
        if (!validateConstructorArgs(constructorArgs)) {
            throw new SecurityException("Invalid constructor arguments provided");
        }

        UltiToolsPlugin plugin;
        try {
            if (constructorArgs.length == 0) {
                // 使用默认构造器
                Constructor<? extends UltiToolsPlugin> constructor = pluginClass.getDeclaredConstructor();
                plugin = constructor.newInstance();
            } else {
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
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }

        // 门禁跑在建容器之前。它读的元数据在实例构造完就已经就位，而 refresh() 之后才检查
        // 等于先让不兼容的 bean 图跑一遍——那正是它会炸的地方。见 passesCompatibilityGates。
        if (!passesCompatibilityGates(plugin)) {
            return null;
        }

        SimpleContainer pluginContext = new SimpleContainer();
        pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        pluginContext.registerShutdownHook();
        pluginContext.setClassLoader(classLoader);
        try {
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
            // autowire it against an empty bean graph -- after 03-03, an unresolvable
            // @Autowired(required = true) field on the plugin's own main class would then throw,
            // turning a working module into one that cannot load (T-03-27).
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
            pluginContext.refresh();
            plugin.setContext(pluginContext);
            return plugin;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }
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
     * <br>
     * 注册Bukkit命令或监听器。
     *
     * @param plugin UltiTools module instance <br> UltiTools模块实例
     * @param flag   True if register in default package  <br> 如果在默认包中注册则为true
     */
    private void registerBukkit(UltiToolsPlugin plugin, boolean flag) {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(plugin.getClass(), EnableAutoRegister.class);
        if (annotation == null) {
            return;
        }
        String[] packages = DependencyUtils.getPluginPackages(plugin);
        for (String packageName : packages) {
            if (annotation.cmdExecutor()) {
                if (flag) {
                    UltiTools.getInstance().getCommandManager().registerAll(plugin);
                } else {
                    UltiTools.getInstance().getCommandManager().registerAll(plugin, packageName);
                }
            }
            if (annotation.eventListener()) {
                if (flag) {
                    UltiTools.getInstance().getListenerManager().registerAll(plugin);
                } else {
                    UltiTools.getInstance().getListenerManager().registerAll(plugin, packageName);
                }
            }
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
