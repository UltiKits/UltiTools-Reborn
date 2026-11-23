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
import com.ultikits.ultitools.utils.ClassLoaderUtils;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

/** Admission is checked against JAR metadata before resolving any module class. */
class PluginManagerModuleApiLevelTest {
    private static final String KEY = "Module '%s' declares api-version %d, below the minimum %d required by UltiTools-API 7.0; refusing to load. Install a module rebuilt for UltiTools-API 7.0 or later; changing api-version alone does not make a 6.x module compatible.";
    private static final String CHINESE = "模块 '%s' 声明的 api-version 为 %d，低于 UltiTools-API 7.0 要求的最低级别 %d；拒绝加载。请安装针对 UltiTools-API 7.0 或更高版本重新构建的模块；仅修改 api-version 无法使 6.x 模块兼容。";

    @TempDir
    Path temp;

    @Test
    void api630_isRefusedBeforeClassLoadingWithEnglishHint() throws Exception {
        verifyRefusal("en", KEY);
    }

    @Test
    void api630_isRefusedBeforeClassLoadingWithChineseHint() throws Exception {
        verifyRefusal("zh", CHINESE);
    }

    private void verifyRefusal(String language, String translation) throws Exception {
        File jar = module("Legacy630.jar", "630");
        List<LogRecord> logs = new ArrayList<>();
        Logger logger = logger(logs);
        UltiTools framework = mock(UltiTools.class);
        when(framework.i18n(anyString())).thenAnswer(invocation -> catalogue(language).get(invocation.getArgument(0, String.class)).getAsString());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<UltiTools> tools = mockStatic(UltiTools.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            tools.when(UltiTools::getInstance).thenReturn(framework);
            classes.when(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName())).thenReturn(PrecheckModule.class);
            assertThat(load(new PluginManager(), jar)).isNull();
            classes.verifyNoInteractions();
        }
        assertThat(logs).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
            assertThat(record.getThrown()).isNull();
            assertThat(record.getMessage()).isEqualTo("[UltiTools-API] " + String.format(translation, "Legacy630.jar", 630, 700));
        });
    }

    @Test
    void api700_passesAndMissingApiVersionRetainsTheExistingAdmissionBehavior() throws Exception {
        File current = module("Current700.jar", "700");
        File undeclared = module("Undeclared.jar", null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedStatic<ClassLoaderUtils> classes = mockStatic(ClassLoaderUtils.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger(new ArrayList<>()));
            classes.when(() -> ClassLoaderUtils.loadClass(PrecheckModule.class.getName())).thenReturn(PrecheckModule.class);
            PluginManager manager = new PluginManager();
            assertThat(load(manager, current)).isSameAs(PrecheckModule.class);
            assertThat(load(manager, undeclared)).isSameAs(PrecheckModule.class);
        }
    }

    @Test
    void aRefusedOldJarDoesNotPreventTheNextCurrentJarFromLoading() throws Exception {
        File legacy = module("Legacy630.jar", "630");
        File current = module("Current700.jar", "700");
        UltiTools framework = mock(UltiTools.class);
        when(framework.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
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
    void installationAndLoadingUseThe700Floor() {
        assertThat(ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL).isEqualTo(700);
        assertThat(ModuleApiLevels.isInstallable(630, ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, 700)).isFalse();
        assertThat(ModuleApiLevels.isInstallable(700, ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, 700)).isTrue();
        assertThat(ModuleApiLevels.isInstallable(701, ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL, 700)).isFalse();
        assertThat(ModuleApiLevels.parse(null)).isNull();
    }

    private JsonObject catalogue(String language) throws Exception {
        try (InputStreamReader input = new InputStreamReader(getClass().getResourceAsStream("/lang/" + language + ".json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(input).getAsJsonObject();
        }
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
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

    private static Object load(PluginManager manager, File jar) throws Exception {
        Method load = PluginManager.class.getDeclaredMethod("loadPluginMainClass", ClassLoader.class, File.class);
        load.setAccessible(true);
        return load.invoke(manager, PluginManagerModuleApiLevelTest.class.getClassLoader(), jar);
    }
}
