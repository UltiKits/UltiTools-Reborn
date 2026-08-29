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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
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

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @TempDir
    File tempDir;

    private ConfigManager mockConfigManager;

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
}
