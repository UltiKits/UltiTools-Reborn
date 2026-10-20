package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.IOException;
import java.net.URL;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a failing module JAR never enters the URL array {@code getModuleUrls()} hands to the
 * {@code URLClassLoader} (WIRE-11).
 * <br>
 * 测试未通过校验的模块 JAR 永远不会进入 {@code getModuleUrls()} 交给 {@code URLClassLoader}
 * 的 URL 数组（WIRE-11）。
 *
 * <p>Calls {@link UltiTools#collectModuleJarUrls(File)} directly against a {@code @TempDir} —
 * deliberately not MockBukkit, since the assertion is about a plain URL array, not a Bukkit API,
 * and MockBukkit does not simulate JVM classloading.</p>
 */
@DisplayName("模块 JAR URL 校验测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleJarUrlValidationTest {

    @TempDir
    File tempDir;

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Handler captureHandler;
    private Logger ultiToolsLogger;

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
        ultiToolsLogger = Logger.getLogger(UltiTools.class.getName());
        ultiToolsLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        ultiToolsLogger.removeHandler(captureHandler);
    }

    @Test
    @DisplayName("超出条目数上限的 JAR 被跳过，合法 JAR 的 URL 仍被收集")
    void oversizedJarIsExcludedWhileValidJarIsIncluded() throws Exception {
        File validJar = createJarWithEntries("valid.jar", 3);
        File oversizedJar = createJarWithEntries("oversized.jar", 10_001);

        List<URL> urls = UltiTools.collectModuleJarUrls(tempDir);

        assertThat(urls).contains(validJar.toURI().toURL());
        assertThat(urls).doesNotContain(oversizedJar.toURI().toURL());
    }

    @Test
    @DisplayName("非 .jar 文件被跳过，只收集 JAR 的 URL")
    void nonJarFileIsExcluded() throws Exception {
        File validJar = createJarWithEntries("valid.jar", 3);
        File notes = new File(tempDir, "notes.txt");
        Files.write(notes.toPath(), "not a jar".getBytes());

        List<URL> urls = UltiTools.collectModuleJarUrls(tempDir);

        assertThat(urls).containsExactly(validJar.toURI().toURL());
    }

    @Test
    @DisplayName("不可读归档（随机字节的 .jar）被跳过，只收集合法 JAR 的 URL")
    void unreadableJarIsExcluded() throws Exception {
        File validJar = createJarWithEntries("valid.jar", 3);
        File garbage = new File(tempDir, "garbage.jar");
        Files.write(garbage.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        List<URL> urls = UltiTools.collectModuleJarUrls(tempDir);

        assertThat(urls).containsExactly(validJar.toURI().toURL());
    }

    @Test
    @DisplayName("目录下全部是合法 JAR 时，每一个都被收集（证明否定断言并非空判）")
    void allValidJarsAreCollected() throws Exception {
        File jarOne = createJarWithEntries("one.jar", 1);
        File jarTwo = createJarWithEntries("two.jar", 2);
        File jarThree = createJarWithEntries("three.jar", 3);

        List<URL> urls = UltiTools.collectModuleJarUrls(tempDir);

        assertThat(urls).containsExactlyInAnyOrder(
                jarOne.toURI().toURL(), jarTwo.toURI().toURL(), jarThree.toURI().toURL());
    }

    @Test
    @DisplayName("目录不存在时返回空集合，不抛出异常")
    void nonExistentDirectoryReturnsEmptyCollection() {
        File missing = new File(tempDir, "does-not-exist");

        List<URL> urls = new ArrayList<>();
        assertThatCode(() -> urls.addAll(UltiTools.collectModuleJarUrls(missing))).doesNotThrowAnyException();
        assertThat(urls).isEmpty();
    }

    @Test
    @DisplayName("每个被跳过的文件都产生一条命名该文件的 WARNING")
    void skippedFileProducesNamingWarning() throws Exception {
        File oversizedJar = createJarWithEntries("oversized.jar", 10_001);

        UltiTools.collectModuleJarUrls(tempDir);

        boolean warned = capturedLogs.stream().anyMatch(record ->
                Level.WARNING.equals(record.getLevel())
                        && record.getMessage() != null
                        && record.getMessage().contains(oversizedJar.getName()));
        assertThat(warned)
                .as("expected a WARNING naming the skipped jar, got: %s", capturedLogs)
                .isTrue();
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
