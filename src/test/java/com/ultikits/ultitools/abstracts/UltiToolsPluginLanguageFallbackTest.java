package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
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

    private ProvenanceChildFirstClassLoader provenanceLoader;

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
        Class<?> fixtureClass = Class.forName(
                UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName(), true, provenanceLoader);
        Objenesis objenesis = new ObjenesisStd();
        Object plugin = objenesis.newInstance(fixtureClass);

        Field pluginNameField = UltiToolsPlugin.class.getDeclaredField("pluginName");
        pluginNameField.setAccessible(true);
        pluginNameField.set(plugin, "TestModule");

        Logger mockLogger = Mockito.mock(Logger.class);
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
        byte[] originalBytes = Files.readAllBytes(diskFile.toPath());
        String originalHash = ResourceHashSidecar.sha256(diskFile);
        ResourceHashSidecar.record(fixture.resourceFolder, "lang/en.json", originalHash);

        // Force Files.write(...) to fail with an IOException without touching the file's
        // existing bytes: remove write permission on the file itself (this test runs as a
        // non-root user, so this reliably raises AccessDeniedException on POSIX).
        assertThat(diskFile.setWritable(false)).isTrue();
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
            // Restore write permission so JUnit's @TempDir cleanup can delete the file afterward.
            diskFile.setWritable(true);
        }
    }
}
