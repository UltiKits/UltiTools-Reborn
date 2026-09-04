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
import com.ultikits.ultitools.websocket.PanelResponderRegistry;
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
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import com.ultikits.ultitools.utils.ReflectionUtil;
import com.ultikits.ultitools.utils.SecurityPolicy;

import lombok.Getter;

/**
 * UltiTools plugin manager.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Injects a plugin's private static instance field -- see 08-GATE05-TRIAGE.md
public class PluginManager {
    /**
     * The name of the JVM system property that opts back into the pre-6.3.0 degraded load
     * order (D-10): every module in filesystem/classpath order, with no dependency resolution
     * at all. Modeled on Paper's own {@code -Dpaper.useLegacyPluginLoading=true} precedent -- a
     * one-shot, consumed-at-bootstrap decision, which is why it is a system property rather than
     * a reloadable {@code config.yml} key. The literal name is repeated (rather than referenced
     * only through this constant) at every call site below, so the property is legible directly
     * in the operator-facing message that names it, not only in code that reads it.
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
     */
    private final Map<Class<?>, String> entityOwnership = new ConcurrentHashMap<>();

    /**
     * External plugin data folder (canonical path) -&gt; the {@link DataScope} minted for it,
     * populated by {@link #registerExternal(ExternalPluginAdapter, Class[])}. Lets {@code
     * DataStore.getOperator(File, Class)} resolve its caller back to a scope (D-18), for the
     * default body a third-party {@code DataStore} that does not override that method inherits.
     */
    private final Map<String, DataScope> externalScopesByFolder = new ConcurrentHashMap<>();

    /**
     * The framework-owned types whose {@link com.ultikits.ultitools.annotations.Scheduled} methods
     * this manager registers.
     * <p>
     * <b>This set is the wiring, not a description of it.</b> {@link
     * #registerFrameworkScheduledOwners()} registers exactly the instances whose types appear here
     * and fails fast if the two disagree, and {@code FrameworkScheduledWiringTest} fails the build
     * if a framework class carries {@code @Scheduled} without appearing here. Adding a type to
     * this set and wiring its task are therefore the same act -- which is the point.
     * <p>
     * The defect this prevents (#384): {@code TaskManager}'s two original entry points both
     * iterate a {@code SimpleContainer}'s beans, so an object the framework constructs directly
     * was reached by neither. {@code PlayerCacheManager.sweepExpiredEntries()} carried a
     * {@code @Scheduled(period = 5 minutes)} annotation, and its javadoc explained at length why a
     * clock-driven sweep was chosen over a hand-rolled {@code BukkitRunnable} -- and it never ran
     * once. Nothing reported an error, because nothing had looked.
     *
     * @since 6.3.0
     */
    static final Set<Class<?>> FRAMEWORK_SCHEDULED_OWNER_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Collections.<Class<?>>singletonList(PlayerCacheManager.class)));

    /**
     * Initialize plugin manager. Please do not call this method manually.
     *
     * @throws IOException IO exception
     */
    public void init(ClassLoader classLoader) throws IOException {
        this.classLoader = classLoader;
        this.taskManager = new TaskManager(UltiTools.getInstance());
        this.playerCacheManager = new PlayerCacheManager();
        registerFrameworkScheduledOwners();
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
     *
     * @param pluginClass Plugin class
     * @return Register result
     */
    public boolean register(Class<? extends UltiToolsPlugin> pluginClass) {
        UltiToolsPlugin plugin;
        try {
            plugin = initializePlugin(classLoader, pluginClass);
        } catch (Exception | Error e) {
            logPluginInitializationFailure(pluginClass.getName(), e);
            return false;
        }
        // null means the compatibility gate refused it; the refusal reason has already been
        // logged, so don't wrap it in another generic error here.
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
     * @param plugin UltiTools plugin instance
     * @return Register result
     */
    public boolean register(UltiToolsPlugin plugin) {
        // The gate runs first: this path's instance is caller-supplied and the container hasn't
        // been built yet, so if it's refused, not a single bean gets constructed. See issue #184.
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
     *
     * @param moduleName the module refusing to load, however the caller identifies it
     * @param thrown     the throwable caught at the registration boundary
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
     * @param plugin UltiTools plugin instance
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
        // Unregister this module's panel message responders (WIRE-16, D-26/D-27, Plan 06-08 Task
        // 3) — mirrors the EventBus.unregisterAll call immediately above; a responder left behind
        // by an unloaded module would go on answering panel requests with code whose classloader
        // is gone.
        PanelResponderRegistry panelResponderRegistry = UltiTools.getInstance().getPanelResponderRegistry();
        if (panelResponderRegistry != null) {
            panelResponderRegistry.unregisterAll(plugin.getPluginName());
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

        // Framework-owned tasks are keyed on no plugin, so the unregister loop above does not
        // reach them. A repeating task left running here survives /reload and the next onEnable
        // schedules a second copy against the same object.
        if (taskManager != null) {
            taskManager.cancelAllCore();
        }
    }

    /**
     * Register the {@code @Scheduled} methods of every framework-owned object.
     * <p>
     * Called from {@link #init(ClassLoader)} once {@code taskManager} and the owner instances
     * exist. Cancellation is the mirror of this, in {@link #close()}.
     *
     * @throws IllegalStateException if the instances offered do not match {@link
     *         #FRAMEWORK_SCHEDULED_OWNER_TYPES}. This is reachable only by editing one of the two
     *         without the other, which the guard test already fails the build for; it is a
     *         belt-and-braces check on an invariant, and cannot be triggered by user
     *         configuration. Failing loudly here is deliberate -- degrading quietly to "some of
     *         the tasks got registered" is the exact behaviour this whole change exists to remove.
     * @since 6.3.0
     */
    private void registerFrameworkScheduledOwners() {
        List<Object> owners = frameworkScheduledOwners();
        Set<Class<?>> offered = new LinkedHashSet<>();
        for (Object owner : owners) {
            offered.add(owner.getClass());
        }
        if (!offered.equals(FRAMEWORK_SCHEDULED_OWNER_TYPES)) {
            throw new IllegalStateException(
                    "Framework @Scheduled wiring is inconsistent: declared "
                            + FRAMEWORK_SCHEDULED_OWNER_TYPES + " but frameworkScheduledOwners() offered "
                            + offered + ". Update both together.");
        }
        for (Object owner : owners) {
            taskManager.registerScheduledMethodsCore(owner);
        }
    }

    /**
     * The live instances corresponding to {@link #FRAMEWORK_SCHEDULED_OWNER_TYPES}, in the same
     * order.
     *
     * @return the framework-owned objects to scan for {@code @Scheduled} methods
     * @since 6.3.0
     */
    private List<Object> frameworkScheduledOwners() {
        return Collections.<Object>singletonList(playerCacheManager);
    }

    /**
     * Register a Bukkit listener for PlayerQuitEvent to clean up @PlayerCache maps.
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
     *
     * @param classLoader Class loader
     * @param pluginJar   Plugin jar file
     * @return Plugin main class
     */
    private Class<? extends UltiToolsPlugin> loadPluginMainClass(ClassLoader classLoader, File pluginJar) { // NOPMD - classLoader used implicitly by Class.forName
        // Validate jar file security
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
                
                // Avoid scanning the same class twice
                if (scannedClasses.contains(className)) {
                    continue;
                }
                scannedClasses.add(className);
                
                // Cap the number of classes scanned, to guard against a denial-of-service attack
                if (scannedClasses.size() > 1000) {
                    Bukkit.getLogger().log(Level.WARNING, 
                        "[UltiTools-API] Too many classes in jar, scanning stopped: " + pluginJar.getName());
                    break;
                }
                
                try {
                    // GEN-07 (D-14): records what the removed classload filter layers would have
                    // refused for className, independent of whether loadClass below succeeds,
                    // throws ClassNotFoundException, or throws SecurityException -- classify() is
                    // a pure function of the name alone. Purely observational; never refuses.
                    ClassLoaderUtils.recordClassloadFilterAudit(pluginJar.getName(), className);
                    // Use security-validated class loading (checks dangerous classes/packages)
                    // but NOT loadPluginClass() which rejects non-UltiToolsPlugin classes
                    Class<?> aClass = ClassLoaderUtils.loadClass(className);
                    if (UltiToolsPlugin.class.isAssignableFrom(aClass)
                            && !aClass.isInterface()
                            && !Modifier.isAbstract(aClass.getModifiers())) {
                        return aClass.asSubclass(UltiToolsPlugin.class);
                    }
                } catch (ClassNotFoundException | LinkageError e) {
                    // Log but don't abort -- continue scanning the remaining classes
                    // D-19: also accumulated for the one-SEVERE-per-module summary this method's
                    // finally block emits below -- skip-and-continue itself is unchanged.
                    ModuleScanDiagnostics.recordSkippedClass(pluginJar.getName(), className, e);
                    Bukkit.getLogger().log(Level.FINE,
                        "[UltiTools-API] Could not load class: " + className + " - " + e.getMessage());
                } catch (SecurityException e) {
                    // Security violations must be logged — only triggers for actually dangerous classes
                    Bukkit.getLogger().log(Level.WARNING,
                        "[UltiTools-API] Security violation while loading class: " + className + " - " + e.getMessage());
                }
            }
        } catch (IOException | LinkageError | RuntimeException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Failed to read jar file: " + pluginJar.getName(), e);
        } finally {
            // D-19: fires whether the method returned early (main class found), fell through
            // (class-count cap reached / jar exhausted), or the jar itself could not be read --
            // exactly once per call, after the scan loop, naming pluginJar as the module.
            ModuleScanDiagnostics.emitSummary(pluginJar.getName());
            // GEN-07 (D-14): the audit summary lands at the same point, so the two diagnostics
            // read as one pattern rather than two.
            ClassLoaderUtils.emitClassloadFilterAuditSummary(pluginJar.getName());
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
     *
     * @param pluginJar the module's own jar file
     * @return every {@code @Table}-annotated class found, never null
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

                resolveEntityClass(className, pluginJar.getName()).ifPresent(entities::add);
            }
        } catch (IOException | LinkageError | RuntimeException e) {
            Bukkit.getLogger().log(Level.SEVERE,
                "[UltiTools-API] Failed to scan jar for entities: " + pluginJar.getName(), e);
        } finally {
            // D-19: fires after the entity scan loop finishes, whether it completed normally or
            // the jar itself could not be read -- exactly once per call, naming pluginJar as the
            // module. Independent of loadPluginMainClass's own emitSummary call above: the two
            // scan the same jar for different purposes and may run at different times, so each
            // owns its own accumulator lifecycle for the classes it individually recorded.
            ModuleScanDiagnostics.emitSummary(pluginJar.getName());
            // GEN-07 (D-14): the audit summary lands at the same point, so the two diagnostics
            // read as one pattern rather than two. Same independence rationale as
            // ModuleScanDiagnostics above -- this scan owns its own ClassloadFilterAudit
            // accumulator lifecycle, separate from loadPluginMainClass's.
            ClassLoaderUtils.emitClassloadFilterAuditSummary(pluginJar.getName());
        }
        return entities;
    }

    /**
     * Converts one {@code JarEntry} from {@link #scanEntitiesInJar} into a dotted class name,
     * or {@code null} when the entry is not a class file the entity scan cares about (a
     * {@code META-INF} entry, or anything not ending in {@code .class}).
     *
     * @param entry the jar entry under inspection
     * @return the dotted class name, or null to skip the entry
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
     *
     * @param className  the dotted class name to load
     * @param moduleName the D-19 diagnostic identifier for the enclosing {@link
     *                   #scanEntitiesInJar}'s jar, threaded through so this method's own catch
     *                   block can accumulate into the correct module
     * @return the loaded class if it is a {@code @Table} entity, otherwise empty
     */
    private static Optional<Class<?>> resolveEntityClass(String className, String moduleName) {
        try {
            // GEN-07 (D-14): records what the removed classload filter layers would have refused
            // for className, independent of whether loadClass below succeeds or throws. Purely
            // observational; never refuses.
            ClassLoaderUtils.recordClassloadFilterAudit(moduleName, className);
            Class<?> aClass = ClassLoaderUtils.loadClass(className);
            if (aClass.isAnnotationPresent(com.ultikits.ultitools.annotations.Table.class)) {
                return Optional.of(aClass);
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // D-19: also accumulated for scanEntitiesInJar's one-SEVERE-per-module summary --
            // skip-and-continue itself is unchanged.
            ModuleScanDiagnostics.recordSkippedClass(moduleName, className, e);
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
     *
     * @param aClass the class whose jar should be resolved
     * @return the jar file, or null if it cannot be resolved
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
     *                     check
     * @param pluginClass the plugin whose scope is being minted
     * @return the entity set for that plugin's scope
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
     * Every class named in {@code additionalEntities} is validated (02-14) before being folded in
     * -- see the private provenance-check method below {@code scanPluginEntities}. This is the
     * public, reachable side of D-19: {@code UltiToolsAPI.connect(plugin, additionalEntities...)}
     * lands here with zero prior trust established, so the validation matters most on this path.
     *
     * @param adapter            the external plugin adapter
     * @param additionalEntities entity classes declared via {@code connect(...)}
     * @return the entity set for that external plugin's scope
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
     * @param declaringPluginName the plugin declaring {@code entityClass}
     * @param declaringClass      the declaring plugin's own main/adapter class, used to look it up
     *                             in {@link #pluginClassList} and excluded from the
     *                             "belongs to another known plugin" check
     * @param declaringOwnJarFile the declaring plugin's own resolved jar, or {@code null} when
     *                             unresolvable (e.g. a directly-constructed test instance)
     * @param entityClass          the entity class named in {@code additionalEntities}
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
     *
     * @param entityJarFile  the entity's resolved own jar, never null
     * @param declaringClass the declaring plugin's own class, excluded from the comparison
     * @return true if a different, already-known plugin's own jar matches
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
     *
     * @param declaringPluginName the plugin whose declaration is refused
     * @param entityClass          the entity class that was declared
     * @param owner                the confirmed owning plugin's name, or {@code null} when none is
     *                             known
     * @return the refusal message
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
     *
     * @param scope the scope just minted
     */
    private void registerEntityOwnership(DataScope scope) {
        for (Class<?> entity : scope.getOwnedEntities()) {
            entityOwnership.putIfAbsent(entity, scope.getPluginName());
        }
    }

    /**
     * Looks up which loaded plugin owns {@code entityClass}, per {@link #entityOwnership}.
     *
     * @param entityClass the entity class to look up
     * @return the owning plugin's name, or {@code null} if no loaded scope owns it
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
     * Refuses (02-14 Task 2) rather than silently replacing when the canonical path already maps
     * to a DIFFERENT plugin's scope -- {@code externalScopesByFolder} used a plain {@code .put()}
     * before this, so wrapping another plugin (all public calls -- {@code new
     * ExternalPluginAdapter(Bukkit.getPluginManager().getPlugin("Victim"))}) and calling the
     * public {@code registerExternal} would displace the victim's legitimate scope here, poisoning
     * the D-18 reverse lookup {@code checkOwnership(File, Class)} depends on. Re-registration by
     * the SAME plugin (matched by name) is idempotent, not refused -- a plugin reconnecting after
     * {@link #unregisterExternal(ExternalPluginAdapter)} must not be permanently locked out.
     *
     * @param dataFolder the external plugin's own data folder
     * @param scope      the scope just minted for it
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
     *
     * @param dataFolder the folder to resolve
     * @return the registered scope, or {@code null} if the folder matches no registered adapter
     */
    public DataScope findScopeForDataFolder(File dataFolder) {
        return externalScopesByFolder.get(canonicalPath(dataFolder));
    }

    /**
     * Removes a data folder's registered external plugin scope (02-14 Task 2), so a subsequent
     * {@link #registerExternal(ExternalPluginAdapter, Class[])} for the same folder -- a legitimate
     * reconnect -- is not refused by {@link #registerExternalScope}'s displacement guard as if it
     * were a different plugin still holding the folder.
     *
     * @param dataFolder the folder whose registration should be cleared
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
     * <p>
     * These two gates originally ran after {@code initializePlugin}, i.e. after the container's
     * {@code refresh()} -- the whole bean graph plus {@code @PostConstruct} -- had already
     * completed. The consequence of that inversion landed exactly on the one scenario where it
     * mattered: a module compiled against a newer API, precisely the kind of module whose beans
     * reference nonexistent methods, would die inside {@code refresh()} first and get reported by
     * the generic catch block as a plain initialization failure -- so the friendly "UltiTools
     * version too old" message never got the chance to be said. See issue #184.
     * <p>
     * The gates only read metadata sourced from {@code plugin.yml} ({@code api-version} /
     * {@code main} / {@code version}), which is already in place once the instance is constructed
     * and needs no container.
     *
     * @param plugin freshly constructed plugin instance, context not yet attached
     * @return true if the module may proceed to context construction
     */
    private boolean passesCompatibilityGates(UltiToolsPlugin plugin) {
        return !hasNewerVersionLoaded(plugin) && isUltiToolsVersionCompatible(plugin);
    }

    /**
     * Determines whether a newer version of the same module is already loaded. **Determination
     * only -- never modifies an already-loaded module.**
     * <p>
     * Unloading the old version this load supersedes is a separate concern, see {@link
     * #unregisterSupersededVersions}. The two are split apart because the determination must
     * happen ahead of bean construction, while the unload cannot -- unloading early would turn
     * "the new version failed to initialize" into "the server is left with neither version".
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
     * Unloads the old version this load supersedes.
     * <p>
     * <b>May only be called after the new module's {@code registerSelf()} returns true</b> -- not
     * "after the container is built". The two are one step apart: the container being built only
     * means the bean graph could be constructed, the module is not yet active; and when {@code
     * registerSelf()} returns false or throws, the caller closes the new container. If the old
     * version had already been unloaded before that point, the module would be left with neither
     * version -- while it was originally running fine.
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
                // Unloading the old version can only happen here: the container being built does
                // not mean the module is alive -- registerSelf() returning true is the step where
                // the module declares itself successfully activated. Doing it earlier means that
                // when registerSelf() returns false or throws, the old version has already been
                // unloaded and the new version's context gets closed too -- leaving the module
                // with neither version, while it was originally running fine.
                //
                // Doing it here does not race the old version for commands: registerBukkit(), the
                // method that actually registers Bukkit commands, is only called after this
                // method returns -- at this point the new version hasn't registered a single
                // command yet.
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
     *
     * @param context the plugin container, before refresh
     * @param scope   the identity token minted for the caller, used to resolve that plugin's
     *                {@code DataSource} for the {@code @Transactional} advisor
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
        // Reading a global static holder instead would let the last plugin to initialise
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
     *
     * @param context  the plugin container, before refresh
     * @param scope    the identity token used to resolve the plugin's {@code DataSource}
     * @param resolver the resolver {@code wireAop} is assembling
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
     * @param scope      the identity token used to resolve the manager
     * @param dataSource the {@code DataSource} {@code dataStore.getDataSource(scope)} returned,
     *                    used only for the third-party fallback
     * @return the {@link JdbcTransactionManager} to bind the interceptor to
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
     *
     * @param context   the plugin container, before refresh
     * @param scope     the identity token used to resolve the manager
     * @param resolver  the resolver {@code wireAop} is assembling
     * @param jsonStore the JSON store to obtain the per-identity manager from
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
     *
     * @param classLoader Class loader
     * @param pluginClass Plugin class
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     */
    private UltiToolsPlugin initializePlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass) {
        UltiToolsPlugin plugin;
        try {
            // Use the default constructor
            Constructor<? extends UltiToolsPlugin> constructor = pluginClass.getDeclaredConstructor();
            plugin = constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize plugin: " + pluginClass.getName(), e);
        }
        return finishInitializingPlugin(classLoader, pluginClass, plugin);
    }

    /**
     * Run the construction-independent second half after {@code initializePlugin} completes
     * construction: the compatibility gate, then container assembly.
     *
     * @param classLoader Class loader to attach to the assembled container
     * @param pluginClass Plugin class
     * @param plugin      the already-constructed plugin instance
     * @return the initialized module, or {@code null} if a compatibility gate rejected it
     */
    private UltiToolsPlugin finishInitializingPlugin(ClassLoader classLoader, Class<? extends UltiToolsPlugin> pluginClass, UltiToolsPlugin plugin) {
        // The gate runs before the container is built. The metadata it reads is already in place
        // once the instance is constructed, while checking only after refresh() would mean an
        // incompatible bean graph runs first -- exactly where it would blow up. See
        // passesCompatibilityGates.
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
     *
     * @param pluginContext a freshly created, not-yet-refreshed container
     * @param plugin        the plugin instance
     * @param pluginClass   the plugin's class
     * @param loader        the classloader to attach to {@code pluginContext} -- deliberately a
     *        parameter rather than reading the {@code PluginManager.classLoader} field directly,
     *        so {@code initializePlugin} can pass ITS OWN {@code classLoader} parameter (which
     *        may differ from the field, e.g. under test) exactly as it did before this method
     *        was extracted
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

        // 07-21 D-19: the per-container diagnostic identifier for
        // SimpleContainer.preInstantiateSingletons' own SEVERE summary -- set before
        // scanComponents/refresh() run, so both register(Class) (via initializePlugin) and
        // register(UltiToolsPlugin) get it for free through this one shared method.
        pluginContext.setDisplayName(plugin.getPluginName());

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
     * {@code AbstractCommandExecutor} generation -- removed in 6.3.0 -- never had a {@link
     * com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} at all) is skipped.
     * <p>
     * This is a STRUCTURAL check only -- it asks whether the required validator TYPE is present
     * in the chain, never whether a given invocation would actually be blocked. No opt-out exists
     * (D-04): a declared cooldown or usage limit that cannot be enforced is always a module-author
     * bug, and Phase 3 D-08's module-granularity isolation -- this refusal alone fails the
     * offending module, every other module in the same load pass still completes -- is the
     * accepted escape hatch.
     *
     * @param pluginContext the just-assembled, just-{@code refresh()}ed container to scan for
     *                       {@link CommandExecutor} beans
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
     * @param executor the already-constructed executor instance
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
     * @param executorClass          the offending executor class
     * @param method                 the offending mapping method, or {@code null} for a
     *                                class-level declaration
     * @param annotationType         the declared annotation ({@code @CmdCD} or
     *                                {@code @UsageLimit})
     * @param requiredValidatorType  the validator type the chain is missing
     * @return the refusal message
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
     *
     * @param executorClass the executor class {@code method} belongs to, for the refusal message
     * @param method        the {@code @CmdMapping} method whose parameters are checked
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
     * @param executorClass the offending executor class
     * @param method        the offending mapping method
     * @param key           the unknown {@code @key}
     * @return the refusal message
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
     *                       MethodInvocationCompleter} performs at completion time
     * @param executorClass the executor class {@code method} belongs to, for the refusal message
     * @param method        the {@code @CmdMapping} method whose parameters are checked
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
     * @param executorClass  the offending executor class
     * @param mappingMethod  the offending {@code @CmdMapping} method
     * @param suggestMethod  the resolved suggest method with the uninvocable signature
     * @return the refusal message
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
     * @param paramTypes the parameter types to format
     * @return the formatted parameter list, including the enclosing parentheses
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
     *
     * @param plugin UltiTools module instance
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
     *
     * @param pluginClass the plugin class
     * @param plugin      the plugin instance
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
     *
     * @param pluginClass plugin class
     * @return scan packages
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
     *
     * @param plugins list of plugin classes to sort
     * @return sorted list of plugin classes
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
     *
     * @param adapter            the external plugin adapter
     * @param additionalEntities entity classes owned by this plugin that live outside its own JAR
     * @since 6.3.0
     */
    public void registerExternal(ExternalPluginAdapter adapter, Class<?>[] additionalEntities) {
        // 1. Create child IoC container
        SimpleContainer context = new SimpleContainer();
        context.setParent(UltiTools.getInstance().getDependenceManagers().getContext());
        context.registerShutdownHook();
        context.setClassLoader(adapter.getPluginClassLoader());

        // 07-fix: the per-container diagnostic identifier for
        // SimpleContainer.preInstantiateSingletons' own SEVERE summary (07-21 D-19).
        // assemblePluginContainer sets it at :1577 for both UltiToolsPlugin load paths, but
        // registerExternal is a third container-assembly path that never goes through that
        // method -- leaving displayName null, which ModuleScanDiagnostics' isBlank guard turns
        // into a silently dropped record AND a suppressed summary. Set before
        // scanComponents/refresh() so a bean-creation-time linkage failure here produces the
        // same operator-matchable signature compatibility/records/6.3.0.md documents for
        // module JARs, instead of only the per-bean WARNING createBean logs.
        context.setDisplayName(adapter.getPluginName());

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
     *
     * @param context  the container to register into
     * @param instance the object to register under its own concrete class
     * @param <T> the object's static type
     */
    @SuppressWarnings("unchecked")
    private static <T> void registerOwnType(SimpleContainer context, T instance) {
        Class<T> ownType = (Class<T>) instance.getClass();
        context.registerType(ownType, instance);
    }

    /**
     * Unregister an external Bukkit plugin adapter from the framework.
     * Tears down the IoC container, unregisters commands/listeners, and cleans up resources.
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

        // Unregister this external plugin's panel message responders (WIRE-16, D-26/D-27, Plan
        // 06-08 Task 3) — the second of the two existing unload call sites; EventBus.unregisterAll
        // itself is called from both, and a responder left behind here would go on answering panel
        // requests with code whose classloader is gone, exactly like the in-process path above.
        PanelResponderRegistry panelResponderRegistry = UltiTools.getInstance().getPanelResponderRegistry();
        if (panelResponderRegistry != null) {
            panelResponderRegistry.unregisterAll(pluginName);
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
