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
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

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
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.ExceptionInterceptor;
import com.ultikits.ultitools.api.ExternalPluginAdapter;
import com.ultikits.ultitools.api.UltiToolsAPI;
import com.ultikits.ultitools.events.EventBus;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.PluginDependencyResolver.CircularDependencyException;
import com.ultikits.ultitools.manager.PluginDependencyResolver.MissingDependencyException;
import com.ultikits.ultitools.utils.AnnotationUtils;
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
            pluginContext.getBeanFactory().registerSingleton(plugin.getClass().getSimpleName(), plugin);
            wireAop(pluginContext);
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
                pluginContext.refresh();
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
        if (!validateJarFile(pluginJar)) {
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
     * Validate jar file security.
     * <br>
     * 验证jar文件安全性。
     *
     * @param jarFile jar file to validate <br> 要验证的jar文件
     * @return true if valid, false otherwise <br> 如果有效则为true，否则为false
     */
    private boolean validateJarFile(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.isFile()) {
            return false;
        }
        
        // 检查文件扩展名
        if (!jarFile.getName().toLowerCase().endsWith(".jar")) {
            return false;
        }
        
        // 验证jar文件结构
        try (JarFile jar = new JarFile(jarFile)) {
            // UltiTools modules don't require plugin.yml — they're identified by @UltiToolsModule

            // 统计条目数量
            Enumeration<JarEntry> entries = jar.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                entries.nextElement();
                entryCount++;
            }
            
            // 使用 SecurityPolicy 验证文件结构
            return SecurityPolicy.isSafeFileStructure(jarFile.length(), entryCount);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, 
                "[UltiTools-API] Failed to validate jar file: " + jarFile.getName(), e);
            return false;
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
     * Only {@code @ExceptionCatch} is wired in this release. {@code @Transactional} is declared
     * unavailable rather than silently inert: the framework has no reachable
     * {@code TransactionManager} today, because {@code DataStore} does not expose its
     * {@code DataSource} and the default SQLite backend opens one connection pool per entity class
     * against a per-plugin {@code .db} file. Tracked in issues #195 and #196.
     * <p>
     * <b>Scope limit 1 — {@code registerSingleton} bypasses this entirely.</b> Only beans the
     * container constructs itself (through {@code registerBean} or component scanning) are ever
     * offered to the resolver, because {@link AopProxyResolver#resolve(Class)} only runs on the
     * constructor branch of bean creation. The plugin instance itself, an {@code @ContextEntry}
     * bean (built by hand with reflection), config entities, {@code @Configuration} classes, and
     * the beans their {@code @Bean} methods produce are all registered with
     * {@code registerSingleton} instead, so none of them ever reach it:
     * {@code @ExceptionCatch} on such a class stays a no-op, and {@code @Transactional} on it is
     * silently allowed to run rather than rejected. Unlike the first three, {@code @Configuration}
     * classes and {@code @Bean} methods are written by module authors rather than the framework,
     * and they are also constructed before {@code wireAop} runs in {@code initializePlugin}, an
     * independent second reason they can never be proxied. The "beans using it are rejected"
     * promise above only reaches beans built through a bean definition. Tracked in issue #308.
     * <p>
     * <b>Scope limit 2 — class-level coverage skips some methods silently.</b> A class-level
     * annotation covers the whole superclass chain, minus two sets. First, methods this proxy
     * cannot both override and reach through {@code super}: {@code private}, {@code static},
     * {@code final}, package-private ones declared in another package, and the erased half of
     * a generic override that a bridge method shadows. Second,
     * {@code equals(Object)} / {@code hashCode()} / {@code canEqual(Object)}, where swallowing
     * an exception would replace it with a silent wrong answer. Both skips are silent by
     * deliberate choice, so a class-level annotation that appears not to apply to one inherited
     * method leaves no diagnostic. A method-level annotation is never skipped: an unproxyable
     * one fails the module load by name instead. See issue #309.
     * <p>
     * <b>Scope limit 3 — two kinds of annotation are still never seen.</b> The scan walks
     * {@code getSuperclass()} only, so an annotation on an <b>interface default method</b> is
     * invisible: {@code @ExceptionCatch} silently does nothing there and {@code @Transactional}
     * is not even refused. Separately, a <b>class-level annotation declared on a superclass</b>
     * is invisible to a subclass bean, because neither annotation carries {@code @Inherited}
     * and the advisor tests the bean's own type. Both predate this wiring and neither is fixed
     * here.
     * <p>
     * 本版本只接线 @ExceptionCatch。@Transactional 声明为不可用而非静默失效，
     * 因为框架当前没有可达的 TransactionManager。见 issue #195 / #196。
     * <p>
     * 范围限制一：以 registerSingleton 方式注册的对象完全绕开这套机制——插件实例本身、
     * {@code @ContextEntry} bean（反射手工构造）、config 实体、{@code @Configuration} 类
     * 及其 {@code @Bean} 方法产出的 bean 都是这样注册的，它们上面的
     * {@code @ExceptionCatch} 依旧是空注解，{@code @Transactional} 也不会被拒绝，
     * 只会静默地不受事务保护地运行。与前三者不同，{@code @Configuration}/{@code @Bean}
     * 是模块作者自己写的代码，而且它们在 {@code initializePlugin} 中于 {@code wireAop}
     * 执行前就已构造完成，这是它们永远不会被代理的另一个独立原因。见 issue #308。
     * 范围限制二：类级注解覆盖整条父类链，但减去两类。其一是代理既覆写不了、也 super
     * 不到的方法：{@code private}、{@code static}、{@code final}、声明在别的包里的
     * package-private 方法，以及被桥接方法遮蔽的泛型覆写擦除另一半。其二是
     * {@code equals(Object)} / {@code hashCode()} / {@code canEqual(Object)}——吞掉它们的
     * 异常会把一个可见的异常换成一个静默的错误结果。两类跳过均为有意静默，因此类级注解
     * 对某个继承方法看似未生效时不会留下任何排查线索。方法级注解从不被跳过：不可代理时
     * 直接点名该方法并让模块加载失败。见 issue #309。
     * 范围限制三：仍有两类注解完全看不见。扫描只走 {@code getSuperclass()}，因此
     * <b>接口 default 方法</b>上的注解不可见——{@code @ExceptionCatch} 在那里静默失效，
     * {@code @Transactional} 连拒绝都不会触发。另外，<b>声明在父类上的类级注解</b>对子类
     * bean 不可见，因为两个注解都没有 {@code @Inherited}，而 advisor 判定的是 bean 自身的
     * 类型。两者都早于本次接线存在，本批均未修复。
     *
     * @param context the plugin container, before refresh
     */
    static void wireAop(SimpleContainer context) {
        AopProxyResolver resolver = new AopProxyResolver();

        // The interceptor resolves @ExceptionCatch(handler = "...") beans from THIS container.
        // Reading the global ContextHolder instead would let the last plugin to initialise
        // overwrite every earlier plugin's handler lookup. See issue #190.
        ExceptionInterceptor exceptionInterceptor =
                new ExceptionInterceptor(Collections.emptyList(), context);
        resolver.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class, exceptionInterceptor, 200));

        resolver.addUnavailableAnnotation(Transactional.class,
                "@Transactional needs a TransactionManager bound to a DataSource. The framework "
                        + "cannot provide one yet: DataStore does not expose its DataSource, and "
                        + "the default SQLite backend opens one connection pool per entity class, "
                        + "so a single .db file would have several unrelated transaction managers. "
                        + "Tracked in issues #195 and #196. Until then use "
                        + "DataOperator.transaction(Callable) explicitly.");

        resolver.validateAnnotationCoverage();

        context.setAopProxyResolver(resolver);
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
            pluginContext.getBeanFactory().registerSingleton(pluginClass.getSimpleName(), plugin);
            // Register plugin as UltiToolsPlugin type so services can inject it via constructor
            pluginContext.registerType(UltiToolsPlugin.class, plugin);

            // Trigger component scanning to discover @CmdExecutor, @EventListener, @Service beans
            String[] scanPackages = getPluginScanPackages(pluginClass);
            if (scanPackages.length > 0) {
                pluginContext.scanComponents(scanPackages);
            }

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

            wireAop(pluginContext);
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
        EnableAutoRegister annotation = AnnotationUtils.findAnnotation(plugin.getClass(), EnableAutoRegister.class);
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
        UltiToolsModule module = pluginClass.getAnnotation(UltiToolsModule.class);
        if (module != null && module.scanBasePackages().length > 0) {
            return module.scanBasePackages();
        }
        ComponentScan componentScan = pluginClass.getAnnotation(ComponentScan.class);
        if (componentScan != null) {
            if (componentScan.value().length > 0) return componentScan.value();
            if (componentScan.basePackages().length > 0) return componentScan.basePackages();
        }
        return new String[]{pluginClass.getPackage().getName()};
    }

    /**
     * Sort plugins by their dependencies using Kahn's algorithm (topological sort).
     * <br>
     * 使用 Kahn 算法（拓扑排序）按依赖关系对插件进行排序。
     *
     * @param plugins list of plugin classes to sort <br> 要排序的插件类列表
     * @return sorted list of plugin classes <br> 排序后的插件类列表
     */
    private List<Class<? extends UltiToolsPlugin>> sortPluginsByDependencies(
            List<Class<? extends UltiToolsPlugin>> plugins) {

        PluginDependencyResolver resolver = new PluginDependencyResolver(Bukkit.getLogger());

        try {
            List<Class<? extends UltiToolsPlugin>> sorted = resolver.resolve(plugins);
            Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Plugin load order resolved successfully.");
            return sorted;
        } catch (CircularDependencyException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] " + e.getMessage());
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Falling back to unsorted load order. Some plugins may fail to initialize!");
            return new ArrayList<>(plugins);
        } catch (MissingDependencyException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] " + e.getMessage());
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Falling back to unsorted load order. Some plugins may fail to initialize!");
            return new ArrayList<>(plugins);
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
        wireAop(context);
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
