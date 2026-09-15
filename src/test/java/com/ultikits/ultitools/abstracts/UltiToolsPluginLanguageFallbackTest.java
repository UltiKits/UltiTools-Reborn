package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * D-20/D-21/WIRE-10: {@link UltiToolsPlugin} consults {@link com.ultikits.ultitools.interfaces.Localized#supported()}
 * before choosing which language file to load, so an unsupported configured code falls back to a
 * language that actually exists instead of silently loading an empty {@code {}} dictionary.
 * <p>
 * Follows the same mock-and-reflect idiom as {@code UltiToolsPluginConfigDiffTest}: Mockito's
 * inline mock maker bypasses the constructor (Objenesis) and lets {@code getLanguageCode()}
 * (public {@code final}) and {@code supported()} (public, overridable) be stubbed directly, while
 * the private {@code resolveLanguageCode()} / {@code createLanguageFromPath(String)} methods under
 * test are invoked via reflection so their real bodies run.
 */
@DisplayName("UltiToolsPlugin 语言解析回退测试 (D-20/D-21/WIRE-10)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of private resolution methods
class UltiToolsPluginLanguageFallbackTest {

    // Declared before the nested fixture classes below: PMD's
    // FieldDeclarationsShouldBeAtStartOfClass requires fields to precede any inner class.
    @TempDir
    File tempDir;

    private ConfigManager mockConfigManager;

    // Backs the D-05/D-06/D-07 provenance fixtures further below; declared here (not next to
    // ProvenanceChildFirstClassLoader) for the same FieldDeclarationsShouldBeAtStartOfClass reason.
    private ProvenanceChildFirstClassLoader provenanceLoader;

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @BeforeEach
    void setUp() {
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(mockConfigManager));
    }

    private String invokeResolveLanguageCode(UltiToolsPlugin plugin) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("resolveLanguageCode");
        method.setAccessible(true);
        try {
            return (String) method.invoke(plugin);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Language invokeCreateLanguageFromPath(UltiToolsPlugin plugin, String folderPath) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("createLanguageFromPath", String.class);
        method.setAccessible(true);
        try {
            return (Language) method.invoke(plugin, folderPath);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void writeLangFile(String code, String jsonContent) throws IOException {
        File langDir = new File(tempDir, "lang");
        langDir.mkdirs();
        Files.write(new File(langDir, code + ".json").toPath(), jsonContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("配置的代码在 supported() 中 -- 直接使用，不告警")
    void configuredCodeSupportedIsUsedWithoutWarning() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("en");
        when(plugin.supported()).thenReturn(Arrays.asList("en", "zh"));
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("en");
        verify(mockLogger, never()).warn(anyString());
    }

    @Test
    @DisplayName("配置了不支持的代码，但 supported() 里有 en -- 回退到 en 并告警，命名模块/请求代码/可用代码")
    void unsupportedCodeFallsBackToEnglishWithNamingWarning() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("fr");
        when(plugin.supported()).thenReturn(Arrays.asList("en", "zh"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("en");
        verify(mockLogger).warn(argThat((String msg) -> msg.contains("TestModule")
                && msg.contains("fr") && msg.contains("en") && msg.contains("zh")));
    }

    @Test
    @DisplayName("配置了不支持的代码，且 supported() 不含 en -- 回退到第一个条目并告警")
    void unsupportedCodeWithoutEnglishFallsBackToFirstEntry() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("fr");
        when(plugin.supported()).thenReturn(Collections.singletonList("zh"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("zh");
        verify(mockLogger, times(1)).warn(anyString());
    }

    @Test
    @DisplayName("配置代码为 null -- 与不支持的代码走相同的回退和告警路径")
    void nullConfiguredCodeFallsBackSameAsUnsupported() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn(null);
        when(plugin.supported()).thenReturn(Arrays.asList("en", "zh"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("en");
        verify(mockLogger, times(1)).warn(anyString());
    }

    @Test
    @DisplayName("supported() 为空列表 -- 视为无信息，不告警，不改变原有代码")
    void emptySupportedProducesNoWarningAndNoChange() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("fr");
        when(plugin.supported()).thenReturn(Collections.emptyList());
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("fr");
        verify(mockLogger, never()).warn(anyString());
    }

    @Test
    @DisplayName("D-21: 不支持的代码回退后，加载的 Language 是真实字典，已知 key 返回翻译值而非原文")
    void unsupportedCodeFallbackProducesRealDictionaryNotEmptyOne() throws Throwable {
        writeLangFile("zh", "{\"greeting\":\"\\u4f60\\u597d\"}");

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("fr");
        when(plugin.supported()).thenReturn(Collections.singletonList("zh"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        Language language = invokeCreateLanguageFromPath(plugin, tempDir.getAbsolutePath());

        assertThat(language.getLocalizedText("greeting")).isEqualTo("你好");
        verify(mockLogger).warn(argThat((String msg) -> msg.contains("TestModule") && msg.contains("fr")));
    }

    /**
     * 16-05 (CodeQL {@code java/zipslip} alert #11): a hostile {@code config.yml language:} value
     * is never restricted by {@link com.ultikits.ultitools.interfaces.Localized#languageCodeOf(String)}'s
     * allowlist at all -- {@code resolveLanguageCode()} returns the configured code UNCHANGED
     * whenever {@code supported()} is empty ("no information"), per its own documented contract.
     * The file-boundary guard added to {@code loadLanguageFromDisk} must therefore be the one
     * catching this case: the resolved code must never be turned into a {@link File} outside the
     * module's own {@code lang/} directory, and resolution must degrade to the same empty-
     * dictionary fallback {@code createLanguageFromPath} already uses when no language file is
     * loadable at all -- never throw, and never read or write anything outside {@code lang/}.
     */
    @Test
    @DisplayName("16-05: 越权的配置语言代码不会逃出 lang/ 目录，安全回退到空字典 (CodeQL java/zipslip #11)")
    void hostileConfiguredLanguageCodeNeverEscapesLangDirectory() throws Throwable {
        String marker = "zipslip-marker-" + System.nanoTime();
        String hostileCode = "../" + marker;

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn(hostileCode);
        when(plugin.supported()).thenReturn(Collections.emptyList());
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        Language language = invokeCreateLanguageFromPath(plugin, tempDir.getAbsolutePath());

        // Never resolves to a real dictionary -- every i18n(...) lookup renders its own raw key.
        assertThat(language.getLocalizedText("greeting")).isEqualTo("greeting");

        // The escaping path (tempDir's PARENT, one level above the lang/ directory) must never
        // have been touched, for any of the three language-file extensions.
        File parentDir = tempDir.getParentFile();
        assertThat(new File(parentDir, marker + ".json")).doesNotExist();
        assertThat(new File(parentDir, marker + ".yml")).doesNotExist();
        assertThat(new File(parentDir, marker + ".yaml")).doesNotExist();

        // The guard must have refused the escaping path and said so, naming the module.
        verify(mockLogger, atLeastOnce()).warn(argThat((String msg) ->
                msg.contains("TestModule") && msg.contains("escape")));
    }

    @Test
    @DisplayName("重写 supported() 的子类 -- 覆盖结果驱动告警与回退")
    void overriddenSupportedDrivesResolution() throws Throwable {
        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        // Simulates a subclass override: supported() is a public, overridable default method, and
        // stubbing it on the mock is virtual-dispatch-equivalent to a real override.
        when(plugin.supported()).thenReturn(Collections.singletonList("ja"));
        when(plugin.getLanguageCode()).thenReturn("en");
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        String resolved = invokeResolveLanguageCode(plugin);

        assertThat(resolved).isEqualTo("ja");
        verify(mockLogger, times(1)).warn(anyString());
    }

    @Test
    @DisplayName("reloadSelf() 重新触发同一套解析逻辑")
    void reloadSelfReRunsResolution() throws Exception {
        writeLangFile("en", "{\"greeting\":\"Hi\"}");

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getLanguageCode()).thenReturn("fr");
        when(plugin.supported()).thenReturn(Arrays.asList("en", "zh"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, tempDir.getAbsolutePath());

        doCallRealMethod().when(plugin).reloadSelf();
        assertThatCode(plugin::reloadSelf).doesNotThrowAnyException();

        verify(mockConfigManager).reloadConfigs(plugin);
        verify(mockLogger).warn(argThat((String msg) -> msg.contains("TestModule") && msg.contains("fr")));

        Field languageField = UltiToolsPlugin.class.getDeclaredField("language");
        languageField.setAccessible(true);
        Language language = (Language) languageField.get(plugin);
        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi");
    }

    // ------------------------------------------------------------------------------------------
    // D-05/D-06/D-07 (#441): recorded-provenance decision on load, plus the per-key
    // placeholder-arity warning. See resolveLanguageWithProvenance/applyPlaceholderArityOverride
    // in UltiToolsPlugin.java.
    //
    // Uses an exploded-directory fixture (not the mock-based FixturePlugin above) because these
    // tests need a real, readable "jar" side to hash and compare against -- same idiom as
    // UltiToolsPluginLanguagePerKeyFallbackTest, whose ModuleFixturePlugin fixture (declared in
    // UltiToolsPluginLanguageScopeTest, same package) is reused directly below.
    // ------------------------------------------------------------------------------------------

    /**
     * Same asymmetry as {@code UltiToolsPluginLanguageScopeTest}'s {@code ChildFirstClassLoader}:
     * child-first for the module's own class, parent-first (default) for resources. Duplicated
     * here rather than shared, matching the existing convention in this test package (see {@code
     * UltiToolsPluginLanguagePerKeyFallbackTest}, which duplicates the same class for the same
     * reason: it is {@code private static} in its original home).
     */
    private static final class ProvenanceChildFirstClassLoader extends URLClassLoader {
        ProvenanceChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notShippedByThisJar) {
                        found = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    void closeProvenanceLoaderIfOpen() throws IOException {
        if (provenanceLoader != null) {
            provenanceLoader.close();
            provenanceLoader = null;
        }
    }

    private static byte[] compiledModuleFixtureClassBytes() throws IOException {
        String resourceName = UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream in = UltiToolsPluginLanguageFallbackTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IOException("Compiled fixture class not found on the test classpath: " + resourceName);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    /**
     * Bundles everything a provenance-decision test needs: the plugin instance (its {@code
     * CodeSource} is {@code jarRoot}, standing in for the module's own jar), the separate {@code
     * resourceFolder} standing in for the on-disk extraction target, and the mocked {@link Logger}
     * backing {@code getLogger()} so INFO/WARN calls can be verified.
     */
    private static final class ProvenanceFixture {
        final Object plugin;
        final File resourceFolder;
        final Logger mockLogger;

        ProvenanceFixture(Object plugin, File resourceFolder, Logger mockLogger) {
            this.plugin = plugin;
            this.resourceFolder = resourceFolder;
            this.mockLogger = mockLogger;
        }
    }

    /**
     * Builds one exploded "jar" directory containing {@code lang/<code><extension>} = {@code
     * jarJson}, one separate on-disk resource folder containing the same path = {@code diskJson}
     * (or no file at all when {@code diskJson} is {@code null}), and wires {@code
     * UltiTools.getInstance()} so {@code getLogger()} returns a mock this test can verify.
     */
    private ProvenanceFixture buildProvenanceFixture(String code, String extension, String jarJson,
                                                       String diskJson) throws Exception {
        File explodedRoot = new File(tempDir, "jar-root-" + System.nanoTime());
        File classFile = new File(explodedRoot,
                UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParentFile().toPath());
        Files.write(classFile.toPath(), compiledModuleFixtureClassBytes());

        File jarLangFile = new File(explodedRoot, "lang" + File.separator + code + extension);
        Files.createDirectories(jarLangFile.getParentFile().toPath());
        Files.write(jarLangFile.toPath(), jarJson.getBytes(StandardCharsets.UTF_8));

        File resourceFolder = new File(tempDir, "disk-root-" + System.nanoTime());
        if (diskJson != null) {
            File diskLangFile = new File(resourceFolder, "lang" + File.separator + code + extension);
            Files.createDirectories(diskLangFile.getParentFile().toPath());
            Files.write(diskLangFile.toPath(), diskJson.getBytes(StandardCharsets.UTF_8));
        } else {
            Files.createDirectories(resourceFolder.toPath());
        }

        provenanceLoader = new ProvenanceChildFirstClassLoader(new URL[]{explodedRoot.toURI().toURL()},
                UltiToolsPluginLanguageFallbackTest.class.getClassLoader());
        // The class name is a compile-time constant (ModuleFixturePlugin.class.getName()), never
        // attacker-controllable; loading through provenanceLoader is required so the fixture's
        // own CodeSource is the exploded "jar" directory this test just built.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> fixtureClass = Class.forName(
                UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName(), true, provenanceLoader);
        Objenesis objenesis = new ObjenesisStd();
        Object plugin = objenesis.newInstance(fixtureClass);

        Field pluginNameField = UltiToolsPlugin.class.getDeclaredField("pluginName");
        pluginNameField.setAccessible(true);
        pluginNameField.set(plugin, "TestModule");

        Logger mockLogger = mock(Logger.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", code);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            Mockito.lenient().when(ultiTools.getConfig()).thenReturn(config);
            Mockito.lenient().when(ultiTools.getLogger()).thenReturn(mockLogger);
        });

        return new ProvenanceFixture(plugin, resourceFolder, mockLogger);
    }

    private Language resolveProvenanceLanguage(ProvenanceFixture fixture) throws Throwable {
        return invokeCreateLanguageFromPath((UltiToolsPlugin) fixture.plugin, fixture.resourceFolder.getAbsolutePath());
    }

    @Test
    @DisplayName("D-05 branch 1: recorded hash == disk hash -> overwritten from the jar, one INFO line")
    void recordedHashEqualsDiskHashOverwritesFromJarWithOneInfoLine() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json",
                ResourceHashSidecar.sha256(diskFile));

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v2");
        assertThat(Files.readAllBytes(diskFile.toPath()))
                .isEqualTo("{\"greeting\":\"Hi v2\"}".getBytes(StandardCharsets.UTF_8));
        verify(fixture.mockLogger, times(1)).info(argThat((String msg) ->
                msg.contains("lang/en.json") && msg.contains("TestModule")));
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("D-05 branch 2: recorded hash != disk hash -> disk left alone, no overwrite INFO line")
    void recordedHashDiffersFromDiskHashLeavesFileAloneWithNoOverwriteLog() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi customised\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        byte[] beforeBytes = Files.readAllBytes(diskFile.toPath());
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi customised");
        assertThat(Files.readAllBytes(diskFile.toPath())).isEqualTo(beforeBytes);
        verify(fixture.mockLogger, never()).info(anyString());
    }

    @Test
    @DisplayName("D-05 branch 2 + arity mismatch: exactly one WARN naming the key; that key uses the "
            + "jar value, every other key keeps the disk value (real %s/%d convention, CR-01)")
    void arityMismatchedKeyWarnsOnceAndUsesJarValueWhileOtherKeysKeepDiskValue() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Hi %s, you have %s items\",\"other\":\"stable\"}",
                "{\"known\":\"Hi %s\",\"other\":\"stable-customised\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("known")).isEqualTo("Hi %s, you have %s items");
        assertThat(language.getLocalizedText("other")).isEqualTo("stable-customised");
        verify(fixture.mockLogger, times(1)).warning(argThat((String msg) ->
                msg.contains("known") && msg.contains("lang/en.json") && msg.contains("TestModule")));
    }

    @Test
    @DisplayName("D-05 branch 2, same-arity reword: no warning, disk value kept (real %s convention, CR-01)")
    void sameArityRewordProducesNoWarningAndKeepsDiskValue() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Hello %s!\"}", "{\"known\":\"Hi there %s!\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("known")).isEqualTo("Hi there %s!");
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("arity counts DISTINCT argument positions, not occurrences: an explicit index "
            + "repeated twice does not fabricate a mismatch against a value using it once (CR-01/WR-03)")
    void repeatedExplicitIndexCountsAsOneDistinctPositionNotTwoOccurrences() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Value %1$s repeated as %1$s again\"}",
                "{\"known\":\"Just %1$s once\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        // Both values consume exactly ONE distinct argument position ({1}); a naive occurrence
        // count would see 2 occurrences in the jar value vs 1 in the disk value and incorrectly
        // warn. The disk value must be kept untouched, with no warning.
        assertThat(language.getLocalizedText("known")).isEqualTo("Just %1$s once");
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("D-06 branch 3: no record, disk == jar -> baseline recorded afterwards, no rewrite, no log")
    void noRecordWithDiskEqualToJarRecordsBaselineWithoutRewriteOrLog() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi\"}", "{\"greeting\":\"Hi\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        byte[] beforeBytes = Files.readAllBytes(diskFile.toPath());

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi");
        assertThat(Files.readAllBytes(diskFile.toPath())).isEqualTo(beforeBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder, "lang/en.json"))
                .contains(ResourceHashSidecar.sha256(diskFile));
        verify(fixture.mockLogger, never()).info(anyString());
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("D-06 branch 4: no record, disk != jar -> never recorded, never rewritten, per-key "
            + "placeholder check still applies (real %s/%d convention, CR-01)")
    void noRecordWithDiskDifferingFromJarNeverRecordsNeverRewritesAppliesPlaceholderCheck() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Hi %s, %d items\"}", "{\"known\":\"Hi %s\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        byte[] beforeBytes = Files.readAllBytes(diskFile.toPath());

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("known")).isEqualTo("Hi %s, %d items");
        assertThat(Files.readAllBytes(diskFile.toPath())).isEqualTo(beforeBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder, "lang/en.json")).isEmpty();
        verify(fixture.mockLogger, times(1)).warning(argThat((String msg) -> msg.contains("known")));
    }

    @Test
    @DisplayName("empty disk file: treated as a file with no keys, resolves every key through the jar "
            + "fallback, never as an error")
    void emptyDiskFileResolvesEveryKeyThroughJarFallbackWithoutError() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json", "{\"greeting\":\"Hi\"}", "");

        Language resolved = resolveProvenanceLanguage(fixture);

        assertThat(resolved.getLocalizedText("greeting")).isEqualTo("Hi");
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("order invariant: a same-arity shared key resolves to the same (disk) value whether "
            + "the decision took the known-customisation branch or the unknown-provenance branch")
    void sameArityKeyResolvesIdenticallyRegardlessOfWhichLeaveAloneBranchFired() throws Throwable {
        ProvenanceFixture knownCustomisation = buildProvenanceFixture("en", ".json",
                "{\"common\":\"jar-value\",\"onlyInJar\":\"x\"}",
                "{\"common\":\"disk-value\",\"onlyInJar\":\"x\"}");
        ResourceHashSidecar.record(knownCustomisation.resourceFolder, "lang/en.json",
                "stale-baseline-hash-not-matching");
        Language viaKnownCustomisation = resolveProvenanceLanguage(knownCustomisation);

        ProvenanceFixture unknownProvenance = buildProvenanceFixture("en", ".json",
                "{\"common\":\"jar-value\",\"onlyInJar\":\"x\"}",
                "{\"common\":\"disk-value\"}");
        Language viaUnknownProvenance = resolveProvenanceLanguage(unknownProvenance);

        assertThat(viaKnownCustomisation.getLocalizedText("common"))
                .isEqualTo(viaUnknownProvenance.getLocalizedText("common"))
                .isEqualTo("disk-value");
    }

    @Test
    @DisplayName("D-05 branch 1, write failure: a failed overwrite neither records the jar hash nor "
            + "logs the success line (WR-01)")
    void overwriteWriteFailureDoesNotRecordJarHashOrLogSuccess() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        File langDir = diskFile.getParentFile();
        byte[] originalBytes = Files.readAllBytes(diskFile.toPath());
        String originalHash = ResourceHashSidecar.sha256(diskFile);
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", originalHash);

        // Force the write to fail with an IOException without touching the file's existing
        // bytes: remove write permission on the CONTAINING DIRECTORY (this test runs as a
        // non-root user). The write-then-atomic-move implementation needs directory write
        // permission to create its temp staging file in the first place -- unlike a direct
        // Files.write(file, bytes), it does NOT need write permission on the target file itself,
        // since a rename only consults the directory entry, never the target's own mode bits.
        assertThat(langDir.setWritable(false)).isTrue();
        try {
            assertThatCode(() -> resolveProvenanceLanguage(fixture)).doesNotThrowAnyException();

            // The sidecar must still record the ORIGINAL hash -- never the jar's hash -- since
            // the write never actually landed those bytes on disk.
            assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder, "lang/en.json"))
                    .contains(originalHash);
            // The disk bytes must be untouched (the write failed before any content changed).
            assertThat(Files.readAllBytes(diskFile.toPath())).isEqualTo(originalBytes);
            // No success-shaped log line may be emitted for a write that did not succeed.
            verify(fixture.mockLogger, never()).info(anyString());
        } finally {
            // Restore write permission so JUnit's @TempDir cleanup can delete the directory
            // (and any stray temp file inside it) afterward.
            langDir.setWritable(true);
        }
    }

    @Test
    @DisplayName("disk hash read failure (e.g. an unreadable path or accidentally a directory) does "
            + "not abort module startup -- degrades to a best-effort read instead (Codex round 1, P1)")
    void diskHashReadFailureDoesNotAbortResolution() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi from jar\"}", "{\"greeting\":\"Hi from disk\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        // Force ResourceHashSidecar.sha256(file) to throw UncheckedIOException by replacing the
        // on-disk file with a directory of the same path -- Files.readAllBytes on a directory
        // throws "Is a directory", which sha256(File) rethrows unchecked.
        assertThat(diskFile.delete()).isTrue();
        assertThat(diskFile.mkdirs()).isTrue();

        Language language = null;
        try {
            language = resolveProvenanceLanguage(fixture);
        } catch (Throwable t) {
            org.junit.jupiter.api.Assertions.fail(
                    "resolution must not throw when the on-disk file cannot be hashed", t);
        }

        assertThat(language).isNotNull();
        // The disk dictionary could not be read at all, so it resolves nothing for "greeting";
        // this test's own createLanguageFromPath call chain then falls back to the jar-bundled
        // value via Language.withFallback -- never the stale/inaccessible disk content.
        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi from jar");
    }

    @Test
    @DisplayName("branch 1, unchanged bundled content: no rewrite and no 'has been updated' log line "
            + "when the jar's bytes already equal the disk bytes (Codex round 1, P2)")
    void overwriteSkippedWhenBundledContentUnchanged() throws Throwable {
        // Every subsequent boot after a normal extraction lands exactly here: recorded hash ==
        // disk hash (branch 1's own condition) AND the bundled jar content has not changed since
        // -- the common case on every restart, not an edge case. Rewriting identical bytes and
        // logging "has been updated" here is misleading every single time it happens, and (per
        // the review finding) an avoidable write on installations that harden module resources
        // read-only after provisioning.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi\"}", "{\"greeting\":\"Hi\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi");
        verify(fixture.mockLogger, never()).info(anyString());
    }

    @Test
    @DisplayName("branch 1 overwrite writes via a same-directory temp file and leaves none behind "
            + "after a successful replace (Codex round 1, P2 -- atomic-replace mechanism)")
    void overwriteWritesAtomicallyAndLeavesNoTempFileBehind() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        File langDir = diskFile.getParentFile();
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v2");
        assertThat(Files.readAllBytes(diskFile.toPath()))
                .isEqualTo("{\"greeting\":\"Hi v2\"}".getBytes(StandardCharsets.UTF_8));
        // Only the final language file should remain in the lang/ directory -- no leftover
        // temp/staging file from the write-then-atomic-move sequence.
        assertThat(langDir.listFiles()).extracting(File::getName).containsExactly("en.json");
    }

    @Test
    @DisplayName("branch 1 overwrite: a provenance-record write failure does not claim success, even "
            + "though the language file itself was refreshed (Codex round 2, P2)")
    void overwriteSucceedsButSidecarRecordFailsDoesNotLogFalseSuccess() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));
        // 16-05 Codex round 3, P2: ResourceHashSidecar.record() now writes via a temp file in the
        // sidecar's own parent directory (fixture.resourceFolder itself) followed by an atomic
        // move, mirroring writeBytes()'s own fix below -- so making the sidecar FILE read-only no
        // longer reproduces a write failure: a rename only consults the DIRECTORY entry's
        // permissions, never the target file's own, so the move would still succeed even with the
        // old file bits unwritable. Removing write from the resourceFolder ROOT instead blocks the
        // temp-file creation itself, while lang/ (a SEPARATE directory, still writable) is
        // untouched -- so the language file's own write is unaffected, reproducing exactly the same
        // "language file refreshed, sidecar record fails" scenario this test targets.
        assertThat(fixture.resourceFolder.setWritable(false)).isTrue();
        try {
            Language language = resolveProvenanceLanguage(fixture);

            // The language file itself WAS refreshed (its own write is unaffected)...
            assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v2");
            assertThat(Files.readAllBytes(diskFile.toPath()))
                    .isEqualTo("{\"greeting\":\"Hi v2\"}".getBytes(StandardCharsets.UTF_8));
            // ...but since the sidecar could not be updated, no success-shaped INFO line may be
            // logged -- it would misrepresent provenance tracking as healthy when it is not.
            verify(fixture.mockLogger, never()).info(anyString());
        } finally {
            fixture.resourceFolder.setWritable(true);
        }
    }

    @Test
    @DisplayName("arity is the highest REQUIRED argument position, not the count of distinct "
            + "positions used -- a gap from an explicit index still changes the required arg count "
            + "(Codex round 2, P2)")
    void arityAccountsForExplicitIndexGapsNotJustDistinctPositionCount() throws Throwable {
        // "%2$s" alone requires TWO arguments to String.format (positions 1 and 2 must both be
        // present in the args array, even though only position 2 is ever rendered) -- its
        // required arg count is 2. "%s" alone requires exactly ONE argument. A naive "count of
        // distinct positions used" (a one-element set in both cases) would wrongly call these
        // equal and skip the warning a real regression like this should trigger.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Uses %2$s only\"}", "{\"known\":\"Uses %s only\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("known")).isEqualTo("Uses %2$s only");
        verify(fixture.mockLogger, times(1)).warning(argThat((String msg) -> msg.contains("known")));
    }

    @Test
    @DisplayName("an oversized explicit format-argument index does not abort resolution -- forces "
            + "the bundled value for that key instead of propagating (Codex round 3, P2)")
    void oversizedExplicitFormatIndexDoesNotAbortResolutionAndForcesBundledValue() throws Throwable {
        // Integer.parseInt("999999999999999999") overflows int and throws NumberFormatException.
        // Before this fix, placeholderArity let that exception propagate uncaught out of
        // applyPlaceholderArityOverride, resolveLanguageWithProvenance, and ultimately this whole
        // module's language resolution -- aborting startup over a single malformed, possibly
        // never-formatted disk-side translation value.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"safe bundled value\",\"other\":\"stable\"}",
                "{\"known\":\"Uses %999999999999999999$s\",\"other\":\"stable-customised\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        // The malformed key falls back to the bundled (jar) value rather than aborting...
        assertThat(language.getLocalizedText("known")).isEqualTo("safe bundled value");
        // ...while every other key is completely unaffected.
        assertThat(language.getLocalizedText("other")).isEqualTo("stable-customised");
        verify(fixture.mockLogger, times(1)).warning(argThat((String msg) -> msg.contains("known")));
    }

    @Test
    @DisplayName("an ordinary '%' in customised text (e.g. '90% done') is never mistaken for a "
            + "Formatter placeholder -- the operator's customisation is preserved, not silently "
            + "overwritten (Codex round 6, P2)")
    void ordinaryPercentSignInTextIsNeverMistakenForAPlaceholder() throws Throwable {
        // Under the old, more permissive PLACEHOLDER_PATTERN, a space is accepted as a Formatter
        // flag, so "90% done" matches "% d" as a bogus %d conversion (arity 1) even though there
        // is no real placeholder here at all. The jar wording below carries no '%' character, so
        // its arity computes as 0 -- a false-positive mismatch that would silently overwrite the
        // operator's customised value with the plain-text bundled wording.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"known\":\"Progress: 90 percent done\"}",
                "{\"known\":\"Progress: 90% done\"}");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", "stale-baseline-hash-not-matching");

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("known")).isEqualTo("Progress: 90% done");
        verify(fixture.mockLogger, never()).warning(anyString());
    }

    @Test
    @DisplayName("an untouched file the operator made read-only is treated as pinned -- not "
            + "refreshed, not rewritten, one WARNING (discussion_r4012703529, Codex round 8, P2)")
    void readOnlyUntouchedFileIsTreatedAsOperatorPinnedAndNotRefreshed() throws Throwable {
        // Branch 1 (recorded hash == disk hash: "never touched since extraction") would normally
        // overwrite from the jar here, since the bundled content changed (v1 -> v2). But the
        // OPERATOR has made the file read-only -- a deliberate signal writeBytes must respect.
        // Before this fix, writeBytes' atomic move only consulted the DIRECTORY's write
        // permission (a rename replaces a directory entry, never touching the target file's own
        // permission bits), so it silently succeeded anyway: the read-only protection was
        // discarded, v2 landed, and the file came out owner-writable again.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));
        byte[] originalBytes = Files.readAllBytes(diskFile.toPath());

        assertThat(diskFile.setWritable(false)).isTrue();
        try {
            Language language = resolveProvenanceLanguage(fixture);

            // Not refreshed: the file keeps its original (v1) content...
            assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v1");
            assertThat(Files.readAllBytes(diskFile.toPath())).isEqualTo(originalBytes);
            // ...no success-shaped INFO log, since nothing was actually refreshed...
            verify(fixture.mockLogger, never()).info(anyString());
            // ...and exactly one WARNING naming the module, explaining why.
            verify(fixture.mockLogger, times(1)).warning(argThat((String msg) -> msg.contains("TestModule")));
        } finally {
            diskFile.setWritable(true);
        }
    }

    @Test
    @DisplayName("a successful refresh preserves the original file's POSIX permissions instead of "
            + "replacing them with createTempFile's process-owned defaults "
            + "(discussion_r4012703529, Codex round 8, P2)")
    void refreshPreservesOriginalPosixPermissions() throws Throwable {
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        PosixFileAttributeView view = Files.getFileAttributeView(diskFile.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping this permission-"
                        + "preservation test (the read-only-pinning test above still covers the "
                        + "portable, non-POSIX-specific half of this finding).");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));

        // Deliberately distinctive: File.createTempFile's default (commonly rw------- / 0600)
        // must NOT survive the refresh -- this permission set adds group-read, which a naive
        // "just create a new temp file" implementation would silently drop.
        Set<PosixFilePermission> distinctivePermissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(diskFile.toPath(), distinctivePermissions);

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v2");
        assertThat(Files.getPosixFilePermissions(diskFile.toPath())).isEqualTo(distinctivePermissions);
    }

    @Test
    @DisplayName("a successful refresh preserves the original file's owner and group, not just "
            + "its permission bits (discussion_r4013501574, Codex round 9, P2)")
    void refreshPreservesOriginalOwnerAndGroup() throws Throwable {
        // Cannot be a RED-then-GREEN pair: both the source file and the replacement temp file are
        // created by this SAME test process, so the owner/group already trivially match before
        // this fix existed too -- File.createTempFile only ever produces a foreign owner when the
        // source file was provisioned by a genuinely different user (Codex's own example: a
        // root-provisioned, group-writable catalogue), which cannot be constructed without root
        // in this sandbox. This is a real regression guard exercising the new self-chown code
        // path, not a demonstration that the old code was broken; the sibling test below
        // separately establishes the OS-level assumption the abort-on-mismatch branch relies on.
        ProvenanceFixture fixture = buildProvenanceFixture("en", ".json",
                "{\"greeting\":\"Hi v2\"}", "{\"greeting\":\"Hi v1\"}");
        File diskFile = new File(fixture.resourceFolder, "lang" + File.separator + "en.json");
        PosixFileAttributeView view = Files.getFileAttributeView(diskFile.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping.");
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", ResourceHashSidecar.sha256(diskFile));

        PosixFileAttributes before = view.readAttributes();

        Language language = resolveProvenanceLanguage(fixture);

        assertThat(language.getLocalizedText("greeting")).isEqualTo("Hi v2");
        PosixFileAttributes after = Files.readAttributes(diskFile.toPath(), PosixFileAttributes.class);
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.group()).isEqualTo(before.group());
    }

    @Test
    @DisplayName("sanity check: an unprivileged process cannot chown a file's group to one it "
            + "does not belong to -- the OS-level assumption "
            + "copyPosixAttributesIfSupported's abort-on-ownership-mismatch branch relies on "
            + "(discussion_r4013501574, Codex round 9, P2)")
    void unprivilegedProcessCannotChgrpToAGroupItDoesNotBelongTo() throws Throwable {
        File probe = new File(tempDir, "chgrp-probe.txt");
        Files.write(probe.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        PosixFileAttributeView view = Files.getFileAttributeView(probe.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping.");

        GroupPrincipal rootGroup;
        try {
            rootGroup = probe.toPath().getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName("root");
        } catch (IOException e) {
            Assumptions.abort("Could not resolve the 'root' group on this system; skipping. " + e);
            return;
        }
        Assumptions.assumeFalse(rootGroup.equals(view.readAttributes().group()),
                "This test process's own primary group is already 'root'; cannot demonstrate a "
                        + "denial -- skipping (running as root/similarly privileged).");

        assertThatThrownBy(() -> view.setGroup(rootGroup))
                .isInstanceOfAny(IOException.class, UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("isOperatorPinnedReadOnly's POSIX-write-bit fallback classifies a mode-0444 file "
            + "as pinned independent of Files.isWritable, and a normally-writable file as not "
            + "pinned (Codex round 10, P2, discussion on UltiToolsPlugin.java:724)")
    void posixWriteBitFallbackClassifiesModeBitsIndependentOfProcessPrivilege() throws Throwable {
        // Cannot reproduce the actual privileged-JVM scenario (Files.isWritable returning true
        // for a 0444 file under root/CAP_DAC_OVERRIDE) without root, which this sandbox does not
        // have -- in an unprivileged test process Files.isWritable ALREADY agrees with the POSIX
        // bits for both cases below, so this is a direct unit test of the new fallback signal's
        // own classification logic, not a demonstration that it changes the observable outcome
        // here. It documents and locks in the intended behaviour of the added code path.
        File readOnlyFile = new File(tempDir, "readonly.json");
        Files.write(readOnlyFile.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        PosixFileAttributeView view = Files.getFileAttributeView(readOnlyFile.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping.");
        // Owner-only bits (no group/other) throughout -- deliberately not "r--r--r--"/"rw-r--r--":
        // Codacy's overly-permissive-file-permission rule flags any OTHERS_READ bit regardless of
        // context, and owner-only bits are all isOperatorPinnedReadOnly's own logic needs anyway.
        Files.setPosixFilePermissions(readOnlyFile.toPath(), PosixFilePermissions.fromString("r--------"));

        File writableFile = new File(tempDir, "writable.json");
        Files.write(writableFile.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(writableFile.toPath(), PosixFilePermissions.fromString("rw-------"));

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        Method method = UltiToolsPlugin.class.getDeclaredMethod("isOperatorPinnedReadOnly", File.class);
        method.setAccessible(true);

        assertThat((Boolean) method.invoke(plugin, readOnlyFile)).isTrue();
        assertThat((Boolean) method.invoke(plugin, writableFile)).isFalse();
    }
}
