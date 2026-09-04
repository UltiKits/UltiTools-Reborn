package com.ultikits.ultitools.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Issue #392 / D-01: {@code @ConditionalOnConfig} is evaluated once at component-scan time, so
 * {@code ul reload} re-reads the config file but never re-evaluates the condition. Per the locked
 * decision, the framework does not rebuild or re-register anything on reload -- it records the
 * scan-time decision and reports drift as a {@code Level.WARNING} when {@link
 * UltiToolsPlugin#reloadSelf()} is called, so an operator sees the flip instead of silence.
 * <p>
 * This class proves the end-to-end path first (scan -&gt; config flip -&gt; reload -&gt; warning),
 * following the {@code doCallRealMethod()} idiom used by {@code UltiToolsPluginLanguageFallbackTest}.
 *
 * @since 6.3.0
 */
@DisplayName("ConditionalRegistrationEvaluator drift report (#392, D-01)")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective field set mirrors the proven idiom
class ConditionalRegistrationEvaluatorDriftTest {

    @TempDir
    File tempDir;

    private ConfigManager mockConfigManager;
    private final List<LogRecord> captured = new ArrayList<>();
    private Handler captureHandler;

    abstract static class FixturePlugin extends UltiToolsPlugin {
    }

    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeatureA")
    static class FeatureAComponent {
    }

    @BeforeEach
    void setUp() {
        mockConfigManager = mock(ConfigManager.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getConfigManager()).thenReturn(mockConfigManager));

        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger(ConditionalRegistrationEvaluator.class.getName()).addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(ConditionalRegistrationEvaluator.class.getName()).removeHandler(captureHandler);
    }

    private void writeYaml(String relativePath, String content) throws IOException {
        File configFile = new File(tempDir, relativePath);
        configFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(content);
        }
    }

    private void writeLangFile(String code, String jsonContent) throws IOException {
        File langDir = new File(tempDir, "lang");
        langDir.mkdirs();
        Files.write(new File(langDir, code + ".json").toPath(), jsonContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("scan enabled -> reload disabled emits exactly one WARNING naming class/file/key/direction/restart")
    void reloadAfterFlipToDisabledReportsDriftOnce() throws Exception {
        // 1. Scan-time evaluation: enableFeatureA is true, component is registered.
        writeYaml("config/config.yml", "enableFeatureA: true\n");
        writeLangFile("en", "{\"greeting\":\"Hi\"}");

        UltiToolsPlugin plugin = mock(FixturePlugin.class);
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.getLanguageCode()).thenReturn("en");
        when(plugin.supported()).thenReturn(Collections.singletonList("en"));
        when(plugin.getPluginName()).thenReturn("TestModule");
        PluginLogger mockLogger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(mockLogger);

        // reloadSelf() reads the private field directly, not the getter -- mirror the proven
        // idiom from UltiToolsPluginLanguageFallbackTest.
        Field resourceFolderPathField = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        resourceFolderPathField.setAccessible(true);
        resourceFolderPathField.set(plugin, tempDir.getAbsolutePath());

        SimpleContainer container = new SimpleContainer();
        container.registerType(UltiToolsPlugin.class, plugin);

        boolean registeredAtScanTime = ConditionalRegistrationEvaluator.shouldRegister(FeatureAComponent.class, container);
        assertThat(registeredAtScanTime).isTrue();

        // 2. Flip the key on disk -- exactly what an operator does before `ul reload`.
        writeYaml("config/config.yml", "enableFeatureA: false\n");

        // 3. Reload -- must call the real reloadSelf() body, including the new drift report.
        doCallRealMethod().when(plugin).reloadSelf();
        plugin.reloadSelf();

        // 4. Exactly one WARNING, naming the class, the config file, the key, the direction, and
        // the restart requirement.
        List<LogRecord> warnings = new ArrayList<>();
        for (LogRecord record : captured) {
            if (record.getLevel() == Level.WARNING) {
                warnings.add(record);
            }
        }
        assertThat(warnings).hasSize(1);
        String message = warnings.get(0).getMessage();
        assertThat(message).contains(FeatureAComponent.class.getName());
        assertThat(message).contains("config/config.yml");
        assertThat(message).contains("enableFeatureA");
        assertThat(message).contains("disabled");
        assertThat(message).contains("restart");

        container.close();
    }
}
