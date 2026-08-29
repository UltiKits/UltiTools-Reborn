package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.testfixtures.configdualpath.DualPathConfig;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.abstracts.ConfigFileStubs;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * UAT-02 backstop: the same {@code configFilePath} arriving through BOTH registration routes --
 * {@code ConfigManager.registerAll}'s package scan and a module's {@code getAllConfigs()}
 * override -- occupies exactly one slot in {@code pluginConfigMap}.
 * <p>
 * The inner map is keyed by {@code configFilePath} and written with {@code Map.put}, so a second
 * registration of the same path replaces rather than accumulates. {@code
 * UltiToolsPluginConfigDiffTest} covers the diff's throw/no-throw branches against a MOCKED
 * {@code ConfigManager}, which cannot observe the map at all; this class uses a REAL one and
 * counts the entries.
 * <br>
 * UAT-02 兜底验证：同一个 {@code configFilePath} 经由两条注册路径到达时，在
 * {@code pluginConfigMap} 中只占一个槽位。内层 Map 以 {@code configFilePath} 为 key 并用
 * {@code put} 写入，因此第二次注册是替换而非累加。
 */
@DisplayName("ConfigManager dual-path registration counts a path once (UAT-02)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigManagerDualPathRegistrationTest {

    private static final String FIXTURE_PACKAGE = "com.ultikits.testfixtures.configdualpath";
    private static final String CONFIG_PATH = "config/dualpath.yml";

    @TempDir
    Path tempDir;

    private ConfigManager configManager;
    private UltiToolsPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        TestHelper.mockUltiToolsInstance();
        configManager = new ConfigManager();

        Path resourceFolder = tempDir.resolve("DualPathModule");
        Files.createDirectories(resourceFolder.resolve("config"));
        Files.write(resourceFolder.resolve(CONFIG_PATH),
                "threshold: 42".getBytes(StandardCharsets.UTF_8));

        plugin = mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DualPathModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(resourceFolder.toString());
        // getConfigFolder()/getConfigFile(String) are package-protected in
        // com.ultikits.ultitools.abstracts - this bridge exists for exactly this cross-package case.
        ConfigFileStubs.stubConfigFolder(plugin, resourceFolder.toFile());
    }

    @Test
    @DisplayName("package scan then getAllConfigs() override: one entry, not two")
    void scanThenOverride_countsPathOnce() throws Exception {
        configManager.registerAll(plugin, FIXTURE_PACKAGE, getClass().getClassLoader());

        Map<String, AbstractConfigEntity> afterScan = configManager.getAllConfigEntities(plugin);
        assertThat(afterScan)
                .as("the package scan must have found the fixture's @ConfigEntity class -- if this "
                        + "is empty the rest of the test proves nothing")
                .containsOnlyKeys(CONFIG_PATH);

        // What a module's getAllConfigs() override hands to ConfigManager.register: a SECOND,
        // independently constructed instance naming the SAME configFilePath.
        configManager.register(plugin, new DualPathConfig(CONFIG_PATH));

        Map<String, AbstractConfigEntity> afterBoth = configManager.getAllConfigEntities(plugin);
        assertThat(afterBoth)
                .as("both routes named '%s'; pluginConfigMap is keyed by configFilePath, so the "
                        + "second registration must replace the first, never accumulate", CONFIG_PATH)
                .hasSize(1)
                .containsOnlyKeys(CONFIG_PATH);
    }

    @Test
    @DisplayName("override first then package scan: still one entry")
    void overrideThenScan_countsPathOnce() throws Exception {
        configManager.register(plugin, new DualPathConfig(CONFIG_PATH));
        configManager.registerAll(plugin, FIXTURE_PACKAGE, getClass().getClassLoader());

        assertThat(configManager.getAllConfigEntities(plugin))
                .as("registration order must not change the count")
                .hasSize(1)
                .containsOnlyKeys(CONFIG_PATH);
    }

    @Test
    @DisplayName("the surviving entry is a live, initialized entity - not a half-registered stub")
    void survivingEntryIsUsable() throws Exception {
        configManager.registerAll(plugin, FIXTURE_PACKAGE, getClass().getClassLoader());
        configManager.register(plugin, new DualPathConfig(CONFIG_PATH));

        AbstractConfigEntity surviving = configManager.getAllConfigEntities(plugin).get(CONFIG_PATH);
        assertThat(surviving)
                .as("deduplication must leave a usable entity behind, not drop both")
                .isInstanceOf(DualPathConfig.class);
        assertThat(((DualPathConfig) surviving).getThreshold())
                .as("the surviving entity read the operator's on-disk value")
                .isEqualTo(42);
    }
}
