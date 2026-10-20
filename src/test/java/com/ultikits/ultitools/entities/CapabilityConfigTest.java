package com.ultikits.ultitools.entities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.RemoteActionLog;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * The eight config-backed {@link Capability} constants' defaults and independence, plus the
 * comment-preserving {@code config.yml} migration and {@link RemoteActionLog}'s rotation-config
 * load — both reached via reflection since {@code UltiTools.migrateCapabilitiesConfig(File, Logger)}
 * and {@code RemoteActionLog.loadConfiguration()} are private, and this test's package differs from
 * both classes' own package.
 * <p>
 * The migration is driven against temporary {@code config.yml} files built with {@link TempDir}
 * rather than a live server — no MockBukkit needed since {@link YamlConfiguration} is a standalone
 * parser.
 */
@DisplayName("Capability 配置：默认值、独立解析与迁移")
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflection to reach private test seams
class CapabilityConfigTest {

    @TempDir
    File tempDir;

    @AfterEach
    void tearDown() throws Exception {
        Field instanceField = UltiTools.class.getDeclaredField("ultiTools");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static boolean invokeMigration(File configFile, Logger logger) throws Exception {
        Method method = UltiTools.class.getDeclaredMethod("migrateCapabilitiesConfig", File.class, Logger.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, configFile, logger);
    }

    private static File writeConfig(File dir, String content) throws Exception {
        File file = new File(dir, "config.yml");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static YamlConfiguration loadYaml(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return config;
    }

    @Nested
    @DisplayName("Capability 的默认值与独立解析")
    class CapabilityDefaultsAndIndependence {

        @Test
        @DisplayName("除 NONE 外恰好八个绑定配置的常量")
        void exactlyEightConfigBackedConstants() {
            long configBacked = java.util.Arrays.stream(Capability.values())
                    .filter(c -> c != Capability.NONE)
                    .count();
            assertThat(configBacked).isEqualTo(8);
        }

        @Test
        @DisplayName("D-08 拆分默认值：读类为真，写类为假")
        void splitDefaults() {
            assertThat(Capability.MONITORING.getDefaultEnabled()).isTrue();
            assertThat(Capability.LOGS.getDefaultEnabled()).isTrue();
            assertThat(Capability.PLAYER_EVENTS.getDefaultEnabled()).isTrue();
            assertThat(Capability.FILE_READ.getDefaultEnabled()).isTrue();
            assertThat(Capability.FILE_WRITE.getDefaultEnabled()).isFalse();
            assertThat(Capability.FILE_DELETE.getDefaultEnabled()).isFalse();
            assertThat(Capability.COMMANDS.getDefaultEnabled()).isFalse();
            assertThat(Capability.SERVER_PROPERTIES.getDefaultEnabled()).isFalse();
        }

        @Test
        @DisplayName("每个能力独立解析自己的键——关掉一个不影响其余七个")
        void eachCapabilityResolvesIndependently() {
            YamlConfiguration config = new YamlConfiguration();
            config.set("ultipanel.capabilities.commands", false);
            config.set("ultipanel.capabilities.monitoring", true);
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    lenient().when(ultiTools.getConfig()).thenReturn(config));

            assertThat(Capability.COMMANDS.isEnabled()).isFalse();
            assertThat(Capability.MONITORING.isEnabled())
                    .as("commands 被显式设为 false 不应影响 monitoring 自己的解析")
                    .isTrue();
            // 未在 config 中出现的其余六个各自解析到自己的出厂默认值，互不干扰。
            assertThat(Capability.LOGS.isEnabled()).isTrue();
            assertThat(Capability.FILE_WRITE.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("键缺失时解析到声明的默认值，而不是一律为 false")
        void absentKeyResolvesToDeclaredDefaultNotFalse() {
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    lenient().when(ultiTools.getConfig()).thenReturn(new YamlConfiguration()));

            assertThat(Capability.FILE_READ.isEnabled())
                    .as("file-read 的出厂默认是 true，缺键不能被读成 false")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("config.yml 迁移")
    class Migration {

        @Test
        @DisplayName("完全没有 capabilities 块：新增全部八个键，各带注释，其余内容不变")
        void addsAllEightKeysWhenBlockAbsent() throws Exception {
            File configFile = writeConfig(tempDir, "language: \"en\"\n");
            Logger logger = mock(Logger.class);

            boolean changed = invokeMigration(configFile, logger);

            assertThat(changed).isTrue();
            YamlConfiguration result = loadYaml(configFile);
            assertThat(result.getString("language")).isEqualTo("en");
            assertThat(result.getBoolean("ultipanel.capabilities.monitoring")).isTrue();
            assertThat(result.getBoolean("ultipanel.capabilities.commands")).isFalse();
            for (Capability capability : Capability.values()) {
                if (capability.getConfigKey() == null) {
                    continue;
                }
                assertThat(result.getComments(capability.getConfigPath()))
                        .as("每个新增键都要带上它的解释注释")
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("操作员已设置 commands: true：迁移后原样保留，其余七个被补上")
        void preservesOperatorSetValueAndAddsTheRest() throws Exception {
            String original = "ultipanel:\n  capabilities:\n    commands: true\n";
            File configFile = writeConfig(tempDir, original);
            byte[] beforeBytes = Files.readAllBytes(configFile.toPath());
            Logger logger = mock(Logger.class);

            boolean changed = invokeMigration(configFile, logger);

            assertThat(changed).isTrue();
            YamlConfiguration result = loadYaml(configFile);
            assertThat(result.getBoolean("ultipanel.capabilities.commands"))
                    .as("操作员已经设置的值必须原样保留")
                    .isTrue();
            assertThat(result.getBoolean("ultipanel.capabilities.monitoring")).isTrue();
            assertThat(result.getBoolean("ultipanel.capabilities.file-write")).isFalse();

            // 字节级证据：迁移前的原始区域在迁移后的文件里仍然逐字出现。
            String beforeText = new String(beforeBytes, StandardCharsets.UTF_8);
            String afterText = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            assertThat(afterText).contains(beforeText.trim());
        }

        @Test
        @DisplayName("本次迁移涉及的全部键都已存在：文件完全不被写入")
        void doesNotWriteWhenEveryMigratedKeyAlreadyExists() throws Exception {
            StringBuilder content = new StringBuilder(
                    "ultipanel:\n  capabilities:\n");
            for (Capability capability : Capability.values()) {
                if (capability.getConfigKey() == null) {
                    continue;
                }
                content.append("    ").append(capability.getConfigKey()).append(": ")
                        .append(capability.getDefaultEnabled()).append('\n');
            }
            content.append("  commands:\n    blocklist: []\n")
                    .append("  files:\n    editable-roots: []\n")
                    .append("  logging:\n    action-log:\n")
                    .append("      max-size-bytes: 1048576\n")
                    .append("      max-files: 5\n");
            File configFile = writeConfig(tempDir, content.toString());
            long beforeModified = configFile.lastModified();
            byte[] beforeBytes = Files.readAllBytes(configFile.toPath());
            Logger logger = mock(Logger.class);

            boolean changed = invokeMigration(configFile, logger);

            assertThat(changed).isFalse();
            byte[] afterBytes = Files.readAllBytes(configFile.toPath());
            assertThat(afterBytes).isEqualTo(beforeBytes);
            assertThat(configFile.lastModified()).isEqualTo(beforeModified);
        }

        @Test
        @DisplayName("配置文件不可读/畸形：记一条 SEVERE，不写入，不抛出异常")
        void malformedConfigLogsSevereAndDoesNotWrite() throws Exception {
            File configFile = writeConfig(tempDir, "ultipanel: [this is not a map: broken\n");
            Logger logger = mock(Logger.class);

            boolean changed = invokeMigration(configFile, logger);

            assertThat(changed).isFalse();
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(logger, times(1)).log(org.mockito.ArgumentMatchers.eq(Level.SEVERE),
                    messageCaptor.capture(), org.mockito.ArgumentMatchers.any(Throwable.class));
            assertThat(messageCaptor.getValue()).contains("config.yml");
        }
    }

    @Nested
    @DisplayName("RemoteActionLog 的轮转配置")
    class ActionLogRotationConfig {

        private static final int DEFAULT_MAX_SIZE_BYTES = 1_048_576;
        private static final int DEFAULT_MAX_FILES = 5;

        private void invokeLoadConfiguration(RemoteActionLog log) throws Exception {
            Method method = RemoteActionLog.class.getDeclaredMethod("loadConfiguration");
            method.setAccessible(true);
            method.invoke(log);
        }

        private int readIntField(RemoteActionLog log, String name) throws Exception {
            Field field = RemoteActionLog.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(log);
        }

        @Test
        @DisplayName("从 config.yml 读取轮转大小与文件数")
        void readsRotationKnobsFromConfig() throws Exception {
            YamlConfiguration config = new YamlConfiguration();
            config.set("ultipanel.logging.action-log.max-size-bytes", 2048);
            config.set("ultipanel.logging.action-log.max-files", 3);
            TestHelper.mockUltiToolsInstance(ultiTools ->
                    lenient().when(ultiTools.getConfig()).thenReturn(config));

            RemoteActionLog log = new RemoteActionLog();
            invokeLoadConfiguration(log);

            assertThat(readIntField(log, "maxSizeBytes")).isEqualTo(2048);
            assertThat(readIntField(log, "maxFiles")).isEqualTo(3);
        }

        @Test
        @DisplayName("没有可用配置时回退到出厂默认值")
        void fallsBackToDefaultsWhenConfigUnavailable() throws Exception {
            RemoteActionLog log = new RemoteActionLog();
            invokeLoadConfiguration(log);

            assertThat(readIntField(log, "maxSizeBytes")).isEqualTo(DEFAULT_MAX_SIZE_BYTES);
            assertThat(readIntField(log, "maxFiles")).isEqualTo(DEFAULT_MAX_FILES);
        }
    }
}
