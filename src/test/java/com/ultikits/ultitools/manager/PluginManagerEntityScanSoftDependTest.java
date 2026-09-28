package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginManagerMock;
import org.mockito.Mockito;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * UltiEconomy#20 / UltiTools-Reborn#550: {@code PluginManager.scanEntitiesInJar}'s entity scan
 * must not log a SEVERE "skipped class...built against an older API" line for a class that fails
 * to load only because it references a type belonging to a plugin the module's own {@code
 * plugin.yml} declares under {@code softdepend:}, when that plugin is absent or disabled -- while
 * every other load failure (an undeclared dependency, or the module's own broken reference) must
 * still produce that SEVERE line exactly as before.
 * <br>
 * UltiEconomy#20 / UltiTools-Reborn#550：{@code PluginManager.scanEntitiesInJar} 的实体扫描不应该为
 * “仅仅因为引用了模块自身 {@code plugin.yml} 中 {@code softdepend:} 声明的插件、而该插件当前缺失或未启用”
 * 而记一条 SEVERE；但除此之外的任何加载失败（未声明的依赖、模块自身的坏引用）仍然必须保留原有的 SEVERE。
 */
@DisplayName("PluginManager 实体扫描 softdepend 缺失日志级别测试（UltiEconomy#20 / #550）")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerEntityScanSoftDependTest {

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private final List<LogRecord> diagnosticsLogs = new ArrayList<>();
    private Handler bukkitCaptureHandler;
    private Handler diagnosticsCaptureHandler;
    private Logger diagnosticsLogger;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        // FINE-level per-class detail is logged directly on Bukkit's own logger by
        // PluginManager#resolveEntityClass -- the ambient level must be widened or FINE records
        // never reach any handler at all (java.util.logging filters at the logger, before
        // handlers are even consulted).
        Bukkit.getLogger().setLevel(Level.ALL);
        bukkitLogs.clear();
        bukkitCaptureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                bukkitLogs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        bukkitCaptureHandler.setLevel(Level.ALL);
        Bukkit.getLogger().addHandler(bukkitCaptureHandler);

        // ModuleScanDiagnostics' SEVERE summary is emitted on its OWN dedicated logger, which
        // deliberately disables parent handlers (see that class's own javadoc) -- it never reaches
        // Bukkit.getLogger() at all, so it must be captured separately.
        diagnosticsLogger = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        diagnosticsLogger.setLevel(Level.ALL);
        diagnosticsLogs.clear();
        diagnosticsCaptureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                diagnosticsLogs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        diagnosticsCaptureHandler.setLevel(Level.ALL);
        diagnosticsLogger.addHandler(diagnosticsCaptureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(bukkitCaptureHandler);
        diagnosticsLogger.removeHandler(diagnosticsCaptureHandler);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("场景 1：模块声明的 softdepend 插件缺失——仅因此失败的类记 FINE，不进入 SEVERE 摘要，模块其余实体正常发现")
    void classFailingOnlyBecauseADeclaredSoftDependPluginIsAbsentIsFineNotSevere() throws Exception {
        File pluginJar = createModuleJarWithSoftDepend(
                "softdepend-absent.jar",
                Collections.singletonList("FakePlaceholderAPI"),
                WorkingEntity.class, EntityScanExpansionStandIn.class);

        URLClassLoader loader = new SelectivelyMissingClassLoader(
                Thread.currentThread().getContextClassLoader(),
                EntityScanExpansionStandIn.class.getName(),
                AbsentPluginApiType.class.getName());
        injectUltiToolsClassLoader(loader);
        try {
            Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

            assertThat(scanned)
                    .as("the module's own working entity must still be discovered -- the module loads")
                    .containsExactly(WorkingEntity.class);

            assertThat(diagnosticsLogs)
                    .as("must NOT appear in ModuleScanDiagnostics' per-module SEVERE summary at all")
                    .noneMatch(r -> r.getMessage() != null && r.getMessage().contains(pluginJar.getName()));

            assertThat(bukkitLogs)
                    .as("the per-class detail must still be observable at FINE, naming the class and "
                            + "the reason")
                    .anyMatch(r -> Level.FINE.equals(r.getLevel())
                            && r.getMessage() != null
                            && r.getMessage().contains(EntityScanExpansionStandIn.class.getName())
                            && r.getMessage().toLowerCase(Locale.ROOT).contains("soft-dependency"));
        } finally {
            injectUltiToolsClassLoader(null);
            loader.close();
        }
    }

    @Test
    @DisplayName("场景 2：完全相同的缺失类型，但模块没有在 softdepend 中声明任何插件——仍然记 SEVERE")
    void classFailingWithNoDeclaredSoftDependAtAllStaysSevereForTheIdenticalMissingType() throws Exception {
        File pluginJar = createModuleJarWithSoftDepend(
                "softdepend-none-declared.jar",
                Collections.emptyList(),
                WorkingEntity.class, EntityScanExpansionStandIn.class);

        URLClassLoader loader = new SelectivelyMissingClassLoader(
                Thread.currentThread().getContextClassLoader(),
                EntityScanExpansionStandIn.class.getName(),
                AbsentPluginApiType.class.getName());
        injectUltiToolsClassLoader(loader);
        try {
            Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

            assertThat(scanned).containsExactly(WorkingEntity.class);
            assertThat(diagnosticsLogs)
                    .as("a module that never declared this plugin as optional cannot silently opt out "
                            + "of the diagnostic")
                    .anyMatch(r -> Level.SEVERE.equals(r.getLevel())
                            && r.getMessage() != null
                            && r.getMessage().contains(pluginJar.getName())
                            && r.getMessage().contains(EntityScanExpansionStandIn.class.getName()));
        } finally {
            injectUltiToolsClassLoader(null);
            loader.close();
        }
    }

    @Test
    @DisplayName("场景 3：模块自身的坏引用（与任何可选插件无关）——仍然记 SEVERE")
    void modulesOwnBrokenReferenceUnrelatedToAnyOptionalPluginStaysSevere() throws Exception {
        File pluginJar = createModuleJarWithSoftDepend(
                "own-broken-reference.jar",
                Collections.emptyList(),
                WorkingEntity.class, ModuleOwnBrokenReferenceStandIn.class);

        URLClassLoader loader = new SelectivelyMissingClassLoader(
                Thread.currentThread().getContextClassLoader(),
                ModuleOwnBrokenReferenceStandIn.class.getName(),
                UnrelatedRemovedApiType.class.getName());
        injectUltiToolsClassLoader(loader);
        try {
            Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

            assertThat(scanned).containsExactly(WorkingEntity.class);
            assertThat(diagnosticsLogs).anyMatch(r -> Level.SEVERE.equals(r.getLevel())
                    && r.getMessage() != null
                    && r.getMessage().contains(pluginJar.getName())
                    && r.getMessage().contains(ModuleOwnBrokenReferenceStandIn.class.getName()));
        } finally {
            injectUltiToolsClassLoader(null);
            loader.close();
        }
    }

    @Test
    @DisplayName("场景 4：一个 @Table 实体因为无关原因加载失败——仍然记 SEVERE，且不出现在扫描结果中")
    void tableAnnotatedEntityFailingForAnUnrelatedReasonStaysSevereAndIsExcludedFromTheScan() throws Exception {
        File pluginJar = createModuleJarWithSoftDepend(
                "table-entity-broken.jar",
                Collections.emptyList(),
                WorkingEntity.class, TableEntityWithRemovedDependency.class);

        URLClassLoader loader = new SelectivelyMissingClassLoader(
                Thread.currentThread().getContextClassLoader(),
                TableEntityWithRemovedDependency.class.getName(),
                UnrelatedRemovedApiType.class.getName());
        injectUltiToolsClassLoader(loader);
        try {
            Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

            assertThat(scanned)
                    .as("the broken entity must never be silently revived into the scan result")
                    .containsExactly(WorkingEntity.class);
            assertThat(diagnosticsLogs).anyMatch(r -> Level.SEVERE.equals(r.getLevel())
                    && r.getMessage() != null
                    && r.getMessage().contains(pluginJar.getName())
                    && r.getMessage().contains(TableEntityWithRemovedDependency.class.getName()));
        } finally {
            injectUltiToolsClassLoader(null);
            loader.close();
        }
    }

    @Test
    @DisplayName("防误判：即使模块声明的 softdepend 插件缺失，如果缺失的类型实际能从另一个已启用插件自身的 jar "
            + "中解析到，也绝不能降级为 FINE")
    void missingClassActuallyAvailableFromAnotherEnabledPluginIsNeverDowngraded() throws Exception {
        // A Mockito interface-mock of org.bukkit.plugin.Plugin gets injected into the SAME
        // ProtectionDomain/CodeSource as the Plugin interface itself -- verified empirically:
        // Mockito.mock(Plugin.class).getClass().getProtectionDomain().getCodeSource() resolves to
        // the real paper-api jar on disk. That jar therefore genuinely carries an entry for
        // "org/bukkit/plugin/Plugin.class" -- standing in for "the missing type in fact belongs to
        // something installed and enabled right now", the isClassAvailableFromAnyEnabledPlugin
        // guard this decision's own javadoc describes. registerLoadedPlugin puts it where
        // Bukkit.getPluginManager().getPlugins() (and therefore this framework's own scan) can see it.
        Plugin enabledPlugin = Mockito.mock(Plugin.class);
        PluginDescriptionFile description = Mockito.mock(PluginDescriptionFile.class);
        Mockito.when(description.getCommands()).thenReturn(Collections.emptyMap());
        Mockito.when(enabledPlugin.getDescription()).thenReturn(description);
        Mockito.when(enabledPlugin.isEnabled()).thenReturn(true);
        Mockito.when(enabledPlugin.getName()).thenReturn("SomeOtherInstalledPlugin");
        ((PluginManagerMock) Bukkit.getPluginManager()).registerLoadedPlugin(enabledPlugin);

        NoClassDefFoundError failure = new NoClassDefFoundError("org/bukkit/plugin/Plugin");

        boolean downgraded = invokeIsAbsentSoftDependClasspathGap(
                failure, Collections.singletonList("FakePlaceholderAPI"));

        assertThat(downgraded)
                .as("a class resolvable from an enabled plugin's own jar must never be treated as "
                        + "caused by an absent optional dependency")
                .isFalse();
    }

    @Test
    @DisplayName("extractMissingClassName：JVM 生成的 NoClassDefFoundError 内部名（斜杠分隔）被正确转换为点分隔类名")
    void extractMissingClassNameConvertsInternalSlashNameToDottedName() throws Exception {
        String result = invokeExtractMissingClassName(
                new NoClassDefFoundError("me/clip/placeholderapi/expansion/PlaceholderExpansion"));

        assertThat(result).isEqualTo("me.clip.placeholderapi.expansion.PlaceholderExpansion");
    }

    @Test
    @DisplayName("extractMissingClassName：消息为空或 null 时返回 null，而不是猜测")
    void extractMissingClassNameReturnsNullWhenMessageIsUnusable() throws Exception {
        assertThat(invokeExtractMissingClassName(new NoClassDefFoundError((String) null))).isNull();
        assertThat(invokeExtractMissingClassName(new NoClassDefFoundError(""))).isNull();
    }

    // ---- reflection helpers -------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanEntitiesInJar(File pluginJar) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("scanEntitiesInJar", File.class);
        method.setAccessible(true);
        return (Set<Class<?>>) method.invoke(pluginManager, pluginJar);
    }

    private boolean invokeIsAbsentSoftDependClasspathGap(Throwable failure, List<String> softDepend)
            throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "isAbsentSoftDependClasspathGap", Throwable.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, failure, softDepend);
    }

    private String invokeExtractMissingClassName(Throwable failure) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("extractMissingClassName", Throwable.class);
        method.setAccessible(true);
        return (String) method.invoke(null, failure);
    }

    /**
     * Sets (or, with {@code null}, clears) {@code UltiTools}'s private {@code ultiToolsClassLoader}
     * field directly on the mock {@code @BeforeEach} published -- see
     * {@code PluginManagerClassScanningTest}'s copy of this same helper for why a real field write
     * is required rather than a stubbable method.
     */
    private void injectUltiToolsClassLoader(URLClassLoader loader) throws Exception {
        Field field = UltiTools.class.getDeclaredField("ultiToolsClassLoader");
        field.setAccessible(true);
        field.set(UltiTools.getInstance(), loader);
    }

    // ---- jar construction ----------------------------------------------------------------------

    /**
     * Builds a module jar carrying a minimal {@code plugin.yml} (a placeholder {@code main:}, never
     * loaded by {@code scanEntitiesInJar}) with the given {@code softdepend:} list, plus one empty
     * placeholder entry per class -- {@code scanEntitiesInJar} only needs the entry's *name* to
     * discover a class exists; the bytes actually loaded come from wherever {@code
     * ClassLoaderUtils.loadClass} resolves the name (the injected {@link SelectivelyMissingClassLoader}
     * below), never from this jar's own physical content, matching the established caveat in {@code
     * PluginManagerClassScanningTest#writeClassEntry}'s javadoc.
     */
    private File createModuleJarWithSoftDepend(String jarName, List<String> softDepend, Class<?>... entries)
            throws IOException {
        File jar = new File(tempDir, jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            StringBuilder yml = new StringBuilder();
            yml.append("name: SoftDependScanFixture\n");
            yml.append("main: unused.Main\n");
            if (softDepend != null && !softDepend.isEmpty()) {
                yml.append("softdepend: [").append(String.join(", ", softDepend)).append("]\n");
            }
            output.write(yml.toString().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (Class<?> entry : entries) {
                output.putNextEntry(new JarEntry(entry.getName().replace('.', '/') + ".class"));
                output.closeEntry();
            }
        }
        return jar;
    }

    // ---- fixtures --------------------------------------------------------------------------------

    @Table("scan_softdepend_working")
    static class WorkingEntity {
    }

    /** Stands in for {@code me.clip.placeholderapi.expansion.PlaceholderExpansion}. */
    static class AbsentPluginApiType {
    }

    /** Stands in for {@code EconomyPlaceholderExpansion extends PlaceholderExpansion}. */
    static class EntityScanExpansionStandIn extends AbsentPluginApiType {
    }

    /** Stands in for a framework-internal type a module's own broken reference points at. */
    static class UnrelatedRemovedApiType {
    }

    static class ModuleOwnBrokenReferenceStandIn extends UnrelatedRemovedApiType {
    }

    @Table("scan_softdepend_broken_entity")
    static class TableEntityWithRemovedDependency extends UnrelatedRemovedApiType {
    }

    /**
     * A {@link URLClassLoader} that defines exactly one named class itself (child-first, via its
     * own real compiled bytecode read off the ambient classpath) so that class's own defining
     * loader is genuinely {@code this} loader -- required so the JVM resolves that class's
     * superclass symbol through {@code this} loader too, per the standard defining-loader
     * resolution rule -- and treats one other named class as unconditionally absent, regardless of
     * whether its {@code .class} file physically exists anywhere reachable. Everything else
     * delegates normally to {@code parent}.
     * <p>
     * Verified empirically (not merely assumed) that this combination reproduces a genuine
     * {@link NoClassDefFoundError} at mere {@code loadClass} time, matching the real-server shape
     * a {@code NoClassDefFoundError} for an absent optional plugin's API type takes: a plain
     * parent-delegating loader is not enough, because the parent already has both classes on its
     * own classpath and would define {@code childFirstClassName} itself, silently bypassing this
     * loader's refusal of {@code missingClassName} entirely.
     */
    private static final class SelectivelyMissingClassLoader extends URLClassLoader {
        private final String childFirstClassName;
        private final String missingClassName;

        SelectivelyMissingClassLoader(ClassLoader parent, String childFirstClassName, String missingClassName) {
            super(new URL[0], parent);
            this.childFirstClassName = childFirstClassName;
            this.missingClassName = missingClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (missingClassName.equals(name)) {
                    throw new ClassNotFoundException("simulated absence: " + name);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && childFirstClassName.equals(name)) {
                    loaded = findClass(name);
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!childFirstClassName.equals(name)) {
                return super.findClass(name);
            }
            String resource = name.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] buffer = new byte[4096];
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                byte[] classBytes = bytes.toByteArray();
                return defineClass(name, classBytes, 0, classBytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
