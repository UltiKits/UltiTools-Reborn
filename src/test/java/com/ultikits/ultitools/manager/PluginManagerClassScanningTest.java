package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.interfaces.IPlugin;

/**
 * PluginManager module main-class scanning tests.
 */
@DisplayName("PluginManager 模块主类扫描测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class PluginManagerClassScanningTest {

    @TempDir
    File tempDir;

    private PluginManager pluginManager;
    private final List<LogRecord> bukkitLogs = new ArrayList<>();
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance();
        pluginManager = new PluginManager();

        bukkitLogs.clear();
        captureHandler = new Handler() {
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
        Bukkit.getLogger().addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Bukkit.getLogger().removeHandler(captureHandler);
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("plan 17-30: 主类通过 plugin.yml 的 main: 声明解析，而不是遍历 jar 条目找第一个可赋值的子类")
    void shouldResolveMainClassFromPluginYmlMainEntry() throws Exception {
        // Decoys are still physically present in the jar (a real module jar also carries plenty
        // of classes with nothing to do with main-class discovery), but with plugin.yml driving
        // discovery none of them is ever loaded to find the main class -- only ConcretePlugin,
        // named explicitly by main:, matters.
        File pluginJar = createModuleJar(
                "mixed-plugin.jar",
                ConcretePlugin.class.getName(),
                IPlugin.class,
                PluginContract.class,
                AbstractPlugin.class,
                PlainPlugin.class,
                ConcretePlugin.class
        );

        assertThat(invokeLoadPluginMainClass(pluginJar)).isEqualTo(ConcretePlugin.class);
    }

    @Test
    @DisplayName("plan 17-30: 一个只服务于可选插件、加载即失败的类不应被main类发现过程接触或记录")
    void shouldNeverLoadOrLogAClassUnrelatedToTheDeclaredMainClass() throws Exception {
        // "com.ultikits.ultitools.manager.OptionalPluginExpansion" names no real class anywhere
        // on the test classpath (see writeClassEntry's javadoc) -- loading it would throw
        // ClassNotFoundException, standing in for the LinkageError a real class referencing an
        // absent optional plugin's type throws (e.g. a PlaceholderAPI expansion without
        // PlaceholderAPI installed): loadPluginMainClass catches both identically, and the point
        // of this test is that neither is ever attempted. Before plan 17-30 this class, sitting
        // ahead of ConcretePlugin in jar entry order, would have been loaded, failed, and logged.
        // Under plugin.yml-driven discovery it must never be touched at all.
        String decoyClassName = "com.ultikits.ultitools.manager.OptionalPluginExpansion";
        File pluginJar = createModuleJar(
                "optional-dependency-plugin.jar",
                ConcretePlugin.class.getName(),
                decoyClassName,
                ConcretePlugin.class
        );

        assertThat(invokeLoadPluginMainClass(pluginJar)).isEqualTo(ConcretePlugin.class);
        assertThat(bukkitLogs).as("no log record should ever mention the untouched decoy class")
                .noneMatch(record -> record.getMessage() != null && record.getMessage().contains(decoyClassName));
    }

    @Test
    @DisplayName("plan 17-30: plugin.yml 中没有 main: 时应明确拒绝加载，而不是静默跳过")
    void shouldRefuseWhenPluginYmlHasNoMainEntry() throws Exception {
        File pluginJar = new File(tempDir, "no-main-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(pluginJar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write("name: NoMainModule\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            writeClassEntry(output, ConcretePlugin.class);
        }

        assertThat(invokeLoadPluginMainClass(pluginJar)).isNull();
        assertThat(bukkitLogs).anyMatch(record ->
                Level.SEVERE.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains("main:"));
    }

    @Test
    @DisplayName("plan 17-30: jar 中完全没有 plugin.yml 时应明确拒绝加载，而不是静默跳过")
    void shouldRefuseWhenJarHasNoPluginYmlAtAll() throws Exception {
        File pluginJar = createJar("no-plugin-yml.jar", ConcretePlugin.class);

        assertThat(invokeLoadPluginMainClass(pluginJar)).isNull();
        assertThat(bukkitLogs).anyMatch(record ->
                Level.SEVERE.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName()));
    }

    @Test
    @DisplayName("plan 17-30: 声明的 main: 类加载失败时应明确拒绝加载，而不是静默跳过")
    void shouldRefuseWhenDeclaredMainClassFailsToLoad() throws Exception {
        String brokenMainClassName = "com.ultikits.ultitools.manager.BrokenMainClass";
        File pluginJar = createModuleJar("broken-main-plugin.jar", brokenMainClassName, brokenMainClassName);

        assertThat(invokeLoadPluginMainClass(pluginJar)).isNull();
        assertThat(bukkitLogs).anyMatch(record ->
                Level.SEVERE.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains(brokenMainClassName));
    }

    @Test
    @DisplayName("plan 17-30: 声明的 main: 不是具体的 UltiToolsPlugin 子类时应明确拒绝加载")
    void shouldRefuseWhenDeclaredMainClassIsNotAConcreteUltiToolsPlugin() throws Exception {
        File pluginJar = createModuleJar(
                "not-a-plugin.jar", PlainPlugin.class.getName(), PlainPlugin.class);

        assertThat(invokeLoadPluginMainClass(pluginJar)).isNull();
        assertThat(bukkitLogs).anyMatch(record ->
                Level.SEVERE.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains("UltiToolsPlugin"));
    }

    @Test
    @DisplayName("plan 17-30: 声明的 main: 不是合法类名格式时应明确拒绝加载而不是抛出异常")
    void shouldRefuseWhenDeclaredMainClassNameIsInvalidFormat() throws Exception {
        String invalidMainClassName = "1nvalid-class-name";
        File pluginJar = createModuleJar("invalid-main-name-plugin.jar", invalidMainClassName);

        assertThat(invokeLoadPluginMainClass(pluginJar)).isNull();
        assertThat(bukkitLogs).anyMatch(record ->
                Level.WARNING.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains(invalidMainClassName));
    }

    @Test
    @DisplayName("坏 JAR 不应该阻止后续有效 JAR 扫描")
    void badJarShouldNotPreventLaterValidJarScan() throws Exception {
        File badJar = new File(tempDir, "bad-plugin.jar");
        Files.write(badJar.toPath(), new byte[]{0x00, 0x01, 0x02});
        File validJar = createModuleJar("valid-plugin.jar", ConcretePlugin.class.getName(), ConcretePlugin.class);

        assertThat(invokeLoadPluginMainClass(badJar)).isNull();
        assertThat(invokeLoadPluginMainClass(validJar)).isEqualTo(ConcretePlugin.class);
    }

    @Test
    @DisplayName("扫描应只收集本 JAR 中携带 @Table 的类，忽略非 @Table 类")
    void shouldScanTableAnnotatedClassesAndOnlyThoseInThisJar() throws Exception {
        File pluginJar = createJar("entity-plugin.jar", EntityA.class, EntityB.class, PlainPlugin.class);

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).containsExactlyInAnyOrder(EntityA.class, EntityB.class);

        DataScope scope = DataScope.forExternal("entity-plugin", tempDir, scanned);
        assertThat(scope.owns(EntityA.class)).isTrue();
        assertThat(scope.owns(EntityB.class)).isTrue();
        assertThat(scope.owns(UnrelatedEntity.class)).isFalse();
    }

    @Test
    @DisplayName("零 @Table 类的 JAR 扫描出空集合，不影响插件加载")
    void shouldReturnEmptySetForJarWithNoTableClasses() throws Exception {
        File pluginJar = createJar("no-entity-plugin.jar", ConcretePlugin.class);

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("超过 1000 类上限只报告、不截断——扫描完整跑完并记录一条 WARNING")
    void shouldReportWithoutTruncatingWhenClassCountExceedsCap() throws Exception {
        File pluginJar = new File(tempDir, "oversized-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(pluginJar.toPath()))) {
            for (int i = 0; i < 1001; i++) {
                output.putNextEntry(new JarEntry(
                        "com/ultikits/ultitools/manager/scanfixture/SyntheticClass" + i + ".class"));
                output.closeEntry();
            }
        }

        Set<Class<?>> scanned = invokeScanEntitiesInJar(pluginJar);

        assertThat(scanned).isNotNull();
        boolean warned = bukkitLogs.stream().anyMatch(record ->
                Level.WARNING.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(pluginJar.getName())
                        && record.getMessage().contains("1000"));
        assertThat(warned).as("expected a WARNING naming the jar and the 1000-class cap, got: %s", bukkitLogs)
                .isTrue();
    }

    @Test
    @DisplayName("@UltiToolsModule#additionalEntities 声明的实体并入插件的 scope（D-19 合法用例：不在自身 jar 中、"
            + "不属于任何其他已知插件——不会被 02-14 的校验拒绝）")
    void additionalEntitiesAttributeShouldBeAddedToPluginScope() throws Exception {
        // scanPluginEntities takes the plugin's Class, not an instance -- everything it reads (the
        // jar's own CodeSource and the class-level @UltiToolsModule annotation) is a class-level
        // fact, so no UltiToolsPlugin construction (real plugin.yml/language/config I/O) is needed
        // here at all.
        //
        // 02-14: ModuleWithAdditionalEntities and UnrelatedEntity are both nested test classes with
        // no real jar (resolveOwnJarFile returns null for both), and UnrelatedEntity is not
        // recorded to any other plugin in entityOwnership -- neither of validateAdditionalEntity's
        // refusal conditions fires, so this stays accepted exactly as D-19 intends.
        Set<Class<?>> scanned = invokeScanPluginEntities("ModuleWithAdditionalEntities", ModuleWithAdditionalEntities.class);

        assertThat(scanned).contains(UnrelatedEntity.class);
    }

    @Test
    @DisplayName("没有声明任何实体的插件得到空集合而非 null，owns() 不抛异常")
    void pluginWithNoEntitiesGetsEmptySetNotNull() throws Exception {
        Set<Class<?>> scanned = invokeScanPluginEntities("ConcretePlugin", ConcretePlugin.class);

        assertThat(scanned).isNotNull().isEmpty();
        DataScope scope = DataScope.forExternal("no-entities-plugin", tempDir, scanned);
        assertThat(scope.owns(EntityA.class)).isFalse();
    }

    @Test
    @DisplayName("connect(plugin, additionalEntities) 声明的实体并入外部插件的 scope（D-19 合法用例，同上不会被拒绝）")
    void externalConnectAdditionalEntitiesShouldBeAddedToScope() throws Exception {
        org.bukkit.plugin.java.JavaPlugin mockPlugin = MockBukkit.createMockPlugin("ExternalScanFixture");
        com.ultikits.ultitools.api.ExternalPluginAdapter adapter =
                new com.ultikits.ultitools.api.ExternalPluginAdapter(mockPlugin);

        Set<Class<?>> scanned = invokeScanExternalEntities(adapter, new Class<?>[]{EntityA.class});

        assertThat(scanned).contains(EntityA.class);
    }

    @Test
    @DisplayName("02-14: @UltiToolsModule#additionalEntities 声明一个已确认属于另一个已注册模块的实体时应被拒绝"
            + "（02-SECURITY.md 内部路径）")
    void additionalEntitiesNamingAnotherModulesOwnedEntityShouldBeRefused() throws Exception {
        // UnrelatedEntity is already confirmed owned by "VictimModule" (mirrors PluginManager's own
        // registerEntityOwnership having run for Victim's scope first). ModuleWithAdditionalEntities
        // (standing in for "AttackerModule") declares that exact same entity in its own
        // additionalEntities -- the internal equivalent of the two-line public attack.
        registerOwnership("VictimModule", UnrelatedEntity.class);

        assertThrows(com.ultikits.ultitools.exceptions.PluginModuleException.class,
                () -> invokeScanPluginEntities("ModuleWithAdditionalEntities", ModuleWithAdditionalEntities.class));
    }

    @Test
    @DisplayName("02-14: 公开路径 UltiToolsAPI.connect(attacker, VictimEntity.class) 应该被拒绝"
            + "（02-SECURITY.md 两行攻击，经公开 API 表面）")
    void publicConnectWithAnotherPluginsRealEntityShouldBeRefused() throws Exception {
        // The exact two-line attack 02-SECURITY.md demonstrated: no reflection, no reference to the
        // victim's own scope -- just naming its real @Table class in additionalEntities through the
        // PUBLIC UltiToolsAPI.connect(plugin, additionalEntities...) overload D-19 introduced.
        registerOwnership("VictimModule", UnrelatedEntity.class);

        DependenceManagers dependenceManagers =
                org.mockito.Mockito.mock(DependenceManagers.class);
        org.mockito.Mockito.when(dependenceManagers.getContext())
                .thenReturn(new com.ultikits.ultitools.context.SimpleContainer());
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            org.mockito.Mockito.when(ultiTools.getDependenceManagers()).thenReturn(dependenceManagers);
            org.mockito.Mockito.when(ultiTools.getPluginManager()).thenReturn(pluginManager);
        });

        org.bukkit.plugin.java.JavaPlugin attacker = org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class);
        org.bukkit.plugin.PluginDescriptionFile desc = org.mockito.Mockito.mock(org.bukkit.plugin.PluginDescriptionFile.class);
        org.mockito.Mockito.when(attacker.getName()).thenReturn("AttackerPlugin");
        org.mockito.Mockito.when(attacker.getDescription()).thenReturn(desc);
        org.mockito.Mockito.when(desc.getVersion()).thenReturn("1.0.0");
        org.mockito.Mockito.when(desc.getAuthors()).thenReturn(Collections.emptyList());
        // Top-level main class name (no dot) -> ExternalPluginAdapter.getScanPackage() is empty,
        // so registerExternal skips component scanning -- keeps this test focused on the ownership
        // refusal, not on IoC container plumbing.
        org.mockito.Mockito.when(desc.getMain()).thenReturn("AttackerMain");
        org.mockito.Mockito.when(attacker.getDataFolder()).thenReturn(new File(tempDir, "attacker-data"));
        org.mockito.Mockito.when(attacker.getLogger()).thenReturn(java.util.logging.Logger.getLogger("AttackerPluginTest"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> com.ultikits.ultitools.api.UltiToolsAPI.connect(attacker, UnrelatedEntity.class));

        assertThat(thrown.getCause()).isInstanceOf(com.ultikits.ultitools.exceptions.PluginModuleException.class);
        assertThat(thrown.getCause().getMessage())
                .contains("VictimModule")
                .contains(UnrelatedEntity.class.getName());
        // Refusal happens before any scope is minted or operator cached -- connect()'s own catch
        // block removes the adapter it optimistically registered, so the attacker never ends up
        // "connected".
        assertThat(com.ultikits.ultitools.api.UltiToolsAPI.isConnected(attacker)).isFalse();
    }

    @Test
    @DisplayName("02-14: 附加实体的 jar 是另一个已发现模块自身的 jar 时应被结构性拒绝，即使尚未有任何所有权登记"
            + "（防先手窗口——entityOwnership 的 putIfAbsent 语义单独无法关闭这个窗口）")
    void additionalEntityStructurallyBelongingToAnotherDiscoveredModuleShouldBeRefusedEvenWithoutPriorOwnershipRecord()
            throws Exception {
        // Real, already-on-disk jars from the test's own Maven dependencies stand in for "the
        // declarer's own jar" and "another module's own jar" -- this exercises the actual
        // java.security.CodeSource-based resolveOwnJarFile() comparison, not a fabricated File, and
        // crucially registers NOTHING in entityOwnership for either side first: this proves the
        // structural check alone (independent of registration order) is what closes the
        // first-mover window, not the registry-conflict check exercised by the two tests above.
        File declarerJar = invokeResolveOwnJarFile(org.assertj.core.api.Assertions.class);
        File otherModuleJar = invokeResolveOwnJarFile(org.mockito.Mockito.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                declarerJar != null && otherModuleJar != null && !declarerJar.equals(otherModuleJar),
                "requires assertj-core and mockito-core to resolve to two distinct real jars on the test classpath");

        // Stands in for "Victim's own module main class, already discovered by init()'s upfront
        // scan" -- populated regardless of whether Victim has registered a DataScope yet.
        addToPluginClassList(org.mockito.Mockito.class);

        assertThat(pluginManager.findOwningPlugin(org.mockito.Answers.class)).isNull();

        assertThrows(com.ultikits.ultitools.exceptions.PluginModuleException.class,
                () -> invokeValidateAdditionalEntity(
                        "Declarer", org.assertj.core.api.Assertions.class, declarerJar, org.mockito.Answers.class));
    }

    @Test
    @DisplayName("02-14: 附加实体与声明方位于同一个物理 jar 时视为合法（共享库被 shade 进了声明方自身的 jar）")
    void additionalEntityFromDeclarersOwnJarShouldBeAccepted() throws Exception {
        File jar = invokeResolveOwnJarFile(org.mockito.Mockito.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null,
                "requires mockito-core to resolve to a real jar on the test classpath");

        assertDoesNotThrow(() -> invokeValidateAdditionalEntity(
                "Declarer", org.mockito.Mockito.class, jar, org.mockito.Answers.class));
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanEntitiesInJar(File pluginJar) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("scanEntitiesInJar", File.class);
        method.setAccessible(true);
        return (Set<Class<?>>) method.invoke(pluginManager, pluginJar);
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanPluginEntities(String pluginName, Class<? extends UltiToolsPlugin> pluginClass)
            throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("scanPluginEntities", String.class, Class.class);
        method.setAccessible(true);
        try {
            return (Set<Class<?>>) method.invoke(pluginManager, pluginName, pluginClass);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Class<?>> invokeScanExternalEntities(
            com.ultikits.ultitools.api.ExternalPluginAdapter adapter, Class<?>[] additionalEntities
    ) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "scanExternalEntities", com.ultikits.ultitools.api.ExternalPluginAdapter.class, Class[].class
        );
        method.setAccessible(true);
        try {
            return (Set<Class<?>>) method.invoke(pluginManager, adapter, additionalEntities);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private void invokeValidateAdditionalEntity(
            String declaringPluginName, Class<?> declaringClass, File declaringOwnJarFile, Class<?> entityClass
    ) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "validateAdditionalEntity", String.class, Class.class, File.class, Class.class);
        method.setAccessible(true);
        try {
            method.invoke(pluginManager, declaringPluginName, declaringClass, declaringOwnJarFile, entityClass);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private File invokeResolveOwnJarFile(Class<?> aClass) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod("resolveOwnJarFile", Class.class);
        method.setAccessible(true);
        return (File) method.invoke(null, aClass);
    }

    /**
     * Directly populates {@code PluginManager.entityOwnership} (bypassing DataScope minting
     * entirely) so a test can assert the registry-conflict branch of {@code
     * validateAdditionalEntity} without needing a full, real plugin registration for the "owner".
     */
    @SuppressWarnings("unchecked")
    private void registerOwnership(String owner, Class<?> entity) throws Exception {
        Field field = PluginManager.class.getDeclaredField("entityOwnership");
        field.setAccessible(true);
        ((java.util.Map<Class<?>, String>) field.get(pluginManager)).put(entity, owner);
    }

    /**
     * Directly populates {@code PluginManager.pluginClassList} (bypassing {@code init()}'s real
     * jar-discovery loop) so a test can assert the structural, first-mover-independent branch of
     * {@code validateAdditionalEntity} in isolation. Raw/unchecked on purpose: at runtime generics
     * are erased, so a class that does not literally extend {@code UltiToolsPlugin} can still
     * stand in for "another already-discovered module's own class" for jar-identity comparison
     * purposes -- only {@code resolveOwnJarFile}'s {@code CodeSource} resolution is exercised.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addToPluginClassList(Class<?> standIn) throws Exception {
        Field field = PluginManager.class.getDeclaredField("pluginClassList");
        field.setAccessible(true);
        ((List) field.get(pluginManager)).add(standIn);
    }

    private static Exception unwrap(java.lang.reflect.InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return e;
    }

    private Class<? extends UltiToolsPlugin> invokeLoadPluginMainClass(File pluginJar) throws Exception {
        Method method = PluginManager.class.getDeclaredMethod(
                "loadPluginMainClass", ClassLoader.class, File.class
        );
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Class<? extends UltiToolsPlugin> result = (Class<? extends UltiToolsPlugin>) method.invoke(
                pluginManager,
                Thread.currentThread().getContextClassLoader(),
                pluginJar
        );
        return result;
    }

    private File createJar(String name, Object... entries) throws IOException {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Object entry : entries) {
                writeClassEntry(output, entry);
            }
        }
        return jar;
    }

    /**
     * Builds a module jar carrying a {@code plugin.yml} whose {@code main:} entry names {@code
     * mainClassName}, plus whatever class entries the test wants physically present -- plan
     * 17-30's tests need this so that "main class discovered from plugin.yml" and "a decoy class
     * that would fail to load" can be told apart, which a jar with no {@code plugin.yml} at all
     * (the pre-existing {@link #createJar}) cannot express.
     */
    private File createModuleJar(String name, String mainClassName, Object... entries) throws IOException {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("main: " + mainClassName + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            for (Object entry : entries) {
                writeClassEntry(output, entry);
            }
        }
        return jar;
    }

    /**
     * Writes one jar entry for {@code entry}: a compiled {@code .class} resource when it is a
     * {@code Class}, or an empty placeholder {@code .class} entry when it is a bare {@code
     * String} class name that names no real class anywhere on the test classpath.
     * <p>
     * {@code loadPluginMainClass} resolves classes through {@code ClassLoaderUtils.loadClass},
     * which in this test process ultimately reaches the ambient JVM classloader (see {@code
     * UltiTools.getJavaPluginClassLoader()}'s test fallback) -- not the bytes physically written
     * into these fabricated jar files. So a bare-{@code String} entry's class body is never
     * actually read; what makes it fail is that no class by that name is defined anywhere the
     * classloader can see, giving a real {@link ClassNotFoundException}. That is enough to stand
     * in for "a class that fails to link" for these tests' purposes (a class referencing an
     * absent optional plugin's type throws {@link LinkageError} instead, but {@code
     * loadPluginMainClass} catches both identically) -- and, for the decoy-class test, the point
     * being proven is that this name is never even attempted, so which exception it *would* throw
     * if it were is moot.
     */
    private void writeClassEntry(JarOutputStream output, Object entry) throws IOException {
        String className = entry instanceof Class ? ((Class<?>) entry).getName() : (String) entry;
        String resourceName = className.replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resourceName));
        if (entry instanceof Class) {
            try (InputStream input = ((Class<?>) entry).getResourceAsStream("/" + resourceName)) {
                assertThat(input).as("compiled class resource for %s", className).isNotNull();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        }
        output.closeEntry();
    }

    interface PluginContract extends IPlugin {
    }

    abstract static class AbstractPlugin extends UltiToolsPlugin {
    }

    static class PlainPlugin implements IPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }

        @Override
        public void unregisterSelf() {
            // No-op for this scanner fixture.
        }

        @Override
        public void reloadSelf() {
            // No-op for this scanner fixture.
        }
    }

    static class ConcretePlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @Table("scan_entity_a")
    static class EntityA {
    }

    @Table("scan_entity_b")
    static class EntityB {
    }

    @Table("scan_entity_other")
    static class UnrelatedEntity {
    }

    @UltiToolsModule(additionalEntities = {UnrelatedEntity.class})
    static class ModuleWithAdditionalEntities extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }
}
