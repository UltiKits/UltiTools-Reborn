package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.ContextEntry;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.interfaces.IPlugin;
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

    /**
     * Initialize plugin manager. Please do not call this method manually.
     * <br>
     * 初始化插件管理器。请不要手动调用此方法。
     *
     * @throws IOException IO exception <br> IO异常
     */
    public void init(ClassLoader classLoader) throws IOException {
        this.classLoader = classLoader;
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
        boolean result = invokeRegisterSelf(plugin);
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
        boolean result = invokeRegisterSelf(plugin);
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
        try {
            SimpleContainer pluginContext = new SimpleContainer();
            plugin.setContext(pluginContext);
            pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
            pluginContext.registerShutdownHook();
            pluginContext.setClassLoader(classLoader);
            pluginContext.getBeanFactory().registerSingleton(plugin.getClass().getSimpleName(), plugin);
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
                    String.format("[UltiTools-API] Cannot initialize plugin for %s", plugin.getPluginName())
            );
            return false;
        }
        boolean result = invokeRegisterSelf(plugin);
        if (result) {
            registerBukkit(plugin, false);
        }
        return result;
    }

    /**
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     */
    public void unregister(UltiToolsPlugin plugin) {
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
        Bukkit.getLogger().log(Level.INFO, "[UltiTools-API] Unregistering all plugins...");
        for (UltiToolsPlugin plugin : pluginList) {
            unregister(plugin);
        }
        pluginList.clear();
        pluginClassList.clear();
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
    private Class<? extends UltiToolsPlugin> loadPluginMainClass(ClassLoader classLoader, File pluginJar) {
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
                    if (IPlugin.class.isAssignableFrom(aClass)) {
                        return aClass.asSubclass(UltiToolsPlugin.class);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // 记录但不中断，继续扫描其他类
                    Bukkit.getLogger().log(Level.FINE,
                        "[UltiTools-API] Could not load class: " + className + " - " + e.getMessage());
                } catch (SecurityException e) {
                    // 安全异常需要记录 — only triggers for actually dangerous classes
                    Bukkit.getLogger().log(Level.WARNING,
                        "[UltiTools-API] Security violation while loading class: " + className + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
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
            if (!SecurityPolicy.isSafeFileStructure(jarFile.length(), entryCount)) {
                return false;
            }
            
            return true;
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, 
                "[UltiTools-API] Failed to validate jar file: " + jarFile.getName(), e);
            return false;
        }
    }


    /**
     * Invoke registerSelf method.
     * <br>
     * 调用registerSelf方法。
     *
     * @param plugin UltiTools plugin instance <br> UltiTools模块实例
     * @return Register result <br> 注册结果
     */
    private boolean invokeRegisterSelf(UltiToolsPlugin plugin) {
        for (UltiToolsPlugin plugin1 : pluginList) {
            if (!plugin1.getMainClass().equals(plugin.getMainClass())) {
                continue;
            }
            if (plugin1.isNewerVersionThan(plugin)) {
                Bukkit.getLogger().log(
                        Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！There is already a new version！", plugin.getPluginName())
                );
                plugin.getContext().close();
                return false;
            } else if (plugin.isNewerVersionThan(plugin1)) {
                plugin1.unregisterSelf();
            }
        }
        if (plugin.getMinUltiToolsVersion() > UltiTools.getPluginVersion()) {
            Bukkit.getLogger().log(
                    Level.WARNING,
                    String.format("[UltiTools-API] %s load failed！UltiTools version is outdated！", plugin.getPluginName())
            );
            plugin.getContext().close();
            return false;
        }
        try {
            boolean registerSelf = plugin.registerSelf();
            if (registerSelf) {
                pluginList.add(plugin);
                Bukkit.getLogger().log(
                        Level.INFO,
                        String.format("[UltiTools-API] %s loaded！Version: %s。", plugin.getPluginName(), plugin.getVersion())
                );
            } else {
                plugin.getContext().close();
                Bukkit.getLogger().log(
                        Level.WARNING,
                        String.format("[UltiTools-API] %s load failed！Version: %s。", plugin.getPluginName(), plugin.getVersion())
                );
            }
            return registerSelf;
        } catch (Exception | Error e) {
            Bukkit.getLogger().log(Level.WARNING, e, String::new);
            Bukkit.getLogger().log(Level.WARNING, String.format("[UltiTools-API] %s load failed！", plugin.getPluginName()));
            return false;
        }
    }

    /**
     * Initialize module.
     * <br>
     * 初始化模块。
     *
     * @param classLoader     Class loader <br> 类加载器
     * @param pluginClass     Plugin class <br> 插件类
     * @param constructorArgs Constructor arguments <br> 构造器参数
     * @return UltiTools plugin instance <br> UltiTools模块实例
     */
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, Object... constructorArgs) {
        // 验证构造器参数安全性
        if (!validateConstructorArgs(constructorArgs)) {
            throw new SecurityException("Invalid constructor arguments provided");
        }
        
        SimpleContainer pluginContext = new SimpleContainer();
        pluginContext.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        pluginContext.registerShutdownHook();
        pluginContext.setClassLoader(classLoader);
        try {
            UltiToolsPlugin plugin;
            
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
            
            pluginContext.getBeanFactory().registerSingleton(pluginClass.getSimpleName(), plugin);

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

            pluginContext.refresh();
            plugin.setContext(pluginContext);
            return plugin;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize plugin: " + pluginClass.getName(), e);
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
            if (java.lang.reflect.Modifier.isStatic(instanceField.getModifiers())) {
                instanceField.setAccessible(true);
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
}
