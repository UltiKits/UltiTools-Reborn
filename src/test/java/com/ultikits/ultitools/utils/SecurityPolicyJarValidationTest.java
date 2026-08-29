package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SecurityPolicy}'s static, instance-free JAR validation rule (WIRE-11).
 * <br>
 * {@link SecurityPolicy} 静态、无实例依赖的 JAR 校验规则测试（WIRE-11）。
 *
 * <p>This is deliberately a separate test class from {@code SecurityPolicyTest} — it targets the
 * new JAR-validation method in isolation, and captures {@code SecurityPolicy}'s own logger directly
 * rather than going through MockBukkit, because the method under test must be callable with no
 * server running at all.</p>
 */
@DisplayName("SecurityPolicy JAR 校验测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SecurityPolicyJarValidationTest {

    @TempDir
    File tempDir;

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;
    private Logger securityPolicyLogger;

    @BeforeEach
    void setUp() {
        capturedLogs.clear();
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
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
        securityPolicyLogger = Logger.getLogger(SecurityPolicy.class.getName());
        securityPolicyLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        securityPolicyLogger.removeHandler(captureHandler);
    }

    @Nested
    @DisplayName("非法输入")
    class InvalidInputTests {

        @Test
        @DisplayName("null 应该返回 false")
        void nullFileShouldReturnFalse() {
            assertThat(SecurityPolicy.isValidModuleJar(null)).isFalse();
        }

        @Test
        @DisplayName("不存在的路径应该返回 false")
        void nonExistentPathShouldReturnFalse() {
            File missing = new File(tempDir, "does-not-exist.jar");
            assertThat(SecurityPolicy.isValidModuleJar(missing)).isFalse();
        }

        @Test
        @DisplayName("目录应该返回 false")
        void directoryShouldReturnFalse() {
            File dir = new File(tempDir, "a-directory.jar");
            assertThat(dir.mkdirs()).isTrue();
            assertThat(SecurityPolicy.isValidModuleJar(dir)).isFalse();
        }

        @Test
        @DisplayName("文件名不以 .jar 结尾（大小写不敏感）应该返回 false")
        void nonJarSuffixShouldReturnFalse() throws IOException {
            File notes = new File(tempDir, "notes.txt");
            Files.write(notes.toPath(), "not a jar".getBytes());
            assertThat(SecurityPolicy.isValidModuleJar(notes)).isFalse();
        }

        @Test
        @DisplayName("不可读的归档（随机字节）应该返回 false，并记录命名该文件的 WARNING")
        void unreadableArchiveShouldReturnFalseAndWarn() throws IOException {
            File garbage = new File(tempDir, "garbage.jar");
            Files.write(garbage.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            assertThat(SecurityPolicy.isValidModuleJar(garbage)).isFalse();

            boolean warned = capturedLogs.stream().anyMatch(record ->
                    Level.WARNING.equals(record.getLevel())
                            && record.getMessage() != null
                            && record.getMessage().contains(garbage.getName()));
            assertThat(warned)
                    .as("expected a WARNING naming the unreadable jar, got: %s", capturedLogs)
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("合法归档")
    class ValidArchiveTests {

        @Test
        @DisplayName("少量条目的合法 jar 应该返回 true")
        void validJarWithFewEntriesShouldReturnTrue() throws IOException {
            File jar = createJarWithEntries("valid.jar", 3);
            assertThat(SecurityPolicy.isValidModuleJar(jar)).isTrue();
        }

        @Test
        @DisplayName("恰好 10000 个条目应该返回 true（边界含）")
        void exactlyMaxEntriesShouldReturnTrue() throws IOException {
            File jar = createJarWithEntries("exactly-max.jar", 10_000);
            assertThat(SecurityPolicy.isValidModuleJar(jar)).isTrue();
        }

        @Test
        @DisplayName("10001 个条目应该返回 false（超出条目数上限）")
        void overMaxEntriesShouldReturnFalse() throws IOException {
            File jar = createJarWithEntries("over-max.jar", 10_001);
            assertThat(SecurityPolicy.isValidModuleJar(jar)).isFalse();
        }
    }

    private File createJarWithEntries(String name, int entryCount) throws IOException {
        File jar = new File(tempDir, name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (int i = 0; i < entryCount; i++) {
                output.putNextEntry(new JarEntry("entry" + i + ".txt"));
                output.closeEntry();
            }
        }
        return jar;
    }
}
