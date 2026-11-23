package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.testfixtures.dependprecheck.PrecheckModule;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.ClassLoaderUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Admission is checked against JAR metadata before resolving any module class. */
class PluginManagerModuleApiLevelTest {
    private static final String VERSION = "7.0.0-SNAPSHOT";
    private static final String KEY = "Module '%s' declares api-version %d, below the minimum %d required by UltiTools-API %s; refusing to load. Install a module rebuilt for the current UltiTools-API; changing api-version alone does not make an old module compatible.";
    private static final String CHINESE = "模块 '%s' 声明的 api-version 为 %d，低于 UltiTools-API %4$s 要求的最低级别 %3$d；拒绝加载。请安装针对当前 UltiTools-API 重新构建的模块；仅修改 api-version 无法使旧模块兼容。";
    private static final String INVALID = "Module '%s' has no valid api-version (read: %s); UltiTools-API %s requires an integer api-version of %d or higher -- refusing to load. Install a module rebuilt for the current UltiTools-API.";
    private static final String INVALID_CHINESE = "模块 '%s' 未声明有效的 api-version（读到的值：%s）；UltiTools-API %s 要求模块声明 %d 或更高的整数；拒绝加载。请安装针对当前 UltiTools-API 重新构建的模块。";

    @TempDir Path temp;

    @Test
    void api630_isRefusedBeforeClassLoadingWithEnglishHint() throws Exception {
        verifyRefusal("en", KEY, 630);
    }

    @Test
    void api630_isRefusedBeforeClassLoadingWithChineseHint() throws Exception {
        verifyRefusal("zh", CHINESE, 630);
    }

    @Test
    void api699_isBelowTheSharedFloor() throws Exception {
        verifyRefusal("en", KEY, 699);
    }

    private void verifyRefusal(String language, String translation, int level) throws Exception {
        File jar = module("Legacy.jar", Integer.toString(level));
        List<LogRecord> logs = new ArrayList<>();
        UltiTools framework = framework(language);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            tools.when(UltiTools::getInstance).thenReturn(framework);
            assertThat(load(new PluginManager(), jar)).isNull();
            classes.verifyNoInteractions();
        }
        assertThat(logs).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
            assertThat(record.getThrown()).isNull();
            assertThat(record.getMessage()).isEqualTo("[UltiTools-API] " + String.format(translation, "Legacy.jar", level, 700, VERSION));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"700", "'700'"})
    void integer700_passes(String declaration) throws Exception {
        File current = module("Current700.jar", declaration);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(new ArrayList<>()));
            classes.when(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName())).thenReturn(PrecheckModule.class);
            assertThat(load(new PluginManager(), current)).isSameAs(PrecheckModule.class);
        }
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of("en", "zh").flatMap(language -> Stream.of(
                Arguments.of(language, null, language.equals("en") ? "<missing>" : "<缺失>"),
                Arguments.of(language, "", language.equals("en") ? "<missing>" : "<缺失>"),
                Arguments.of(language, "''", "''"),
                Arguments.of(language, "'26.3'", "'26.3'"),
                Arguments.of(language, "1.21", "'1.21'"),
                Arguments.of(language, "abc", "'abc'")));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void missingAndNonIntegerDeclarationsAreRefusedBeforeClassResolution(String language, String declaration, String read) throws Exception {
        File invalid = module("Invalid.jar", declaration);
        List<LogRecord> logs = new ArrayList<>();
        UltiTools framework = framework(language);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            tools.when(UltiTools::getInstance).thenReturn(framework);
            assertThat(load(new PluginManager(), invalid)).isNull();
            classes.verifyNoInteractions();
        }
        assertThat(logs).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
            assertThat(record.getThrown()).isNull();
            assertThat(record.getMessage()).isEqualTo("[UltiTools-API] " + String.format(
                    language.equals("en") ? INVALID : INVALID_CHINESE, "Invalid.jar", read, VERSION, 700));
        });
    }

    @Test
    void api701_keepsTheExistingNewerFrameworkRefusalAfterClassResolution() throws Exception {
        File newer = module("Newer701.jar", "701");
        List<LogRecord> logs = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(logs));
            tools.when(UltiTools::getPluginVersion).thenReturn(700);
            classes.when(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName())).thenReturn(PrecheckModule.class);
            PluginManager manager = new PluginManager();
            assertThat(load(manager, newer)).isSameAs(PrecheckModule.class);
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getMinUltiToolsVersion()).thenReturn(701);
            when(plugin.getPluginName()).thenReturn("Newer701");
            Method compatible = PluginManager.class.getDeclaredMethod("isUltiToolsVersionCompatible", UltiToolsPlugin.class);
            compatible.setAccessible(true); // NOPMD - test-only reflective call to a private check
            assertThat(compatible.invoke(manager, plugin)).isEqualTo(false);
        }
        assertThat(logs).singleElement().satisfies(record -> assertThat(record.getMessage())
                .isEqualTo("[UltiTools-API] Newer701 load failed! UltiTools version is outdated!"));
    }

    @Test
    void aRefusedOldJarDoesNotPreventTheNextCurrentJarFromLoading() throws Exception {
        File legacy = module("Legacy630.jar", "630");
        File current = module("Current700.jar", "700");
        UltiTools framework = framework("en");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(new ArrayList<>()));
            tools.when(UltiTools::getInstance).thenReturn(framework);
            classes.when(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName())).thenReturn(PrecheckModule.class);
            PluginManager manager = new PluginManager();
            assertThat(load(manager, legacy)).isNull();
            assertThat(load(manager, current)).isSameAs(PrecheckModule.class);
            classes.verify(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName()));
        }
    }

    @Test
    void installationAndLoadingUseTheSame700FloorAndParser() {
        assertThat(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL).isEqualTo(700);
        for (String raw : new String[]{null, "", "26.3", "1.21", "abc", "630", "699", "700", "701"}) {
            Integer level = ModuleApiLevels.parse(raw);
            assertThat(ModuleApiLevels.isLoadable(level))
                    .isEqualTo(level != null && ModuleApiLevels.isInstallable(level, ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, Integer.MAX_VALUE));
        }
        assertThat(ModuleApiLevels.isInstallable(701, ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, 700)).isFalse();
    }

    private UltiTools framework(String language) throws Exception {
        JsonObject catalogue;
        try (InputStreamReader input = new InputStreamReader(getClass().getResourceAsStream("/lang/" + language + ".json"), StandardCharsets.UTF_8)) {
            catalogue = JsonParser.parseReader(input).getAsJsonObject();
        }
        UltiTools framework = mock(UltiTools.class);
        when(framework.i18n(anyString())).thenAnswer(call -> catalogue.get(call.getArgument(0, String.class)).getAsString());
        when(framework.getDescription()).thenReturn(new PluginDescriptionFile("UltiTools", VERSION, "unused.Main"));
        return framework;
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {
                // Records are kept in memory; there is nothing to flush.
            }
            @Override public void close() {
                // Records are kept in memory; there is nothing to close.
            }
        });
        return logger;
    }

    private File module(String name, String level) throws Exception {
        Path jar = temp.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(("name: Probe\nversion: 1.0.0\nmain: " + PrecheckModule.class.getName() + "\n"
                    + (level == null ? "" : "api-version: " + level + "\n")).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar.toFile();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static Object load(PluginManager manager, File jar) throws Exception {
        Method load = PluginManager.class.getDeclaredMethod("loadPluginMainClass", ClassLoader.class, File.class);
        load.setAccessible(true);
        return load.invoke(manager, PluginManagerModuleApiLevelTest.class.getClassLoader(), jar);
    }
}
