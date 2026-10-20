package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PluginYmlReader#readFromJarFile(File)} tests -- plan 17-30.
 * <p>
 * {@link PluginYmlReader#read(Class)} (exercised by {@code PluginDependencyResolverTest}) needs an
 * already-loaded class's own code source; {@code readFromJarFile} is the entry point
 * {@code PluginManager}'s main-class discovery uses instead, before any class from the module jar
 * has been loaded. These tests exercise it directly, independent of {@code PluginManager}.
 */
@DisplayName("PluginYmlReader.readFromJarFile 测试")
class PluginYmlReaderTest {

    @TempDir
    File tempDir;

    @Test
    @DisplayName("null jar 文件返回 EMPTY")
    void nullJarFileReturnsEmpty() {
        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(null);

        assertThat(info.getName()).isNull();
        assertThat(info.getMain()).isNull();
        assertThat(info.getLoadAfter()).isEmpty();
    }

    @Test
    @DisplayName("不存在的文件返回 EMPTY")
    void nonExistentFileReturnsEmpty() {
        File missing = new File(tempDir, "does-not-exist.jar");

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(missing);

        assertThat(info.getMain()).isNull();
    }

    @Test
    @DisplayName("目录（非文件）返回 EMPTY")
    void directoryReturnsEmpty() {
        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(tempDir);

        assertThat(info.getMain()).isNull();
    }

    @Test
    @DisplayName("jar 中没有 plugin.yml 条目时返回 EMPTY")
    void jarWithNoPluginYmlEntryReturnsEmpty() throws Exception {
        File jar = new File(tempDir, "no-yml.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("com/example/SomeClass.class"));
            output.closeEntry();
        }

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(jar);

        assertThat(info.getName()).isNull();
        assertThat(info.getMain()).isNull();
        assertThat(info.getLoadAfter()).isEmpty();
    }

    @Test
    @DisplayName("jar 中有效的 plugin.yml 应解析出 name、main 与 loadAfter")
    void validPluginYmlIsParsed() throws Exception {
        File jar = writePluginYmlJar(
                "name: TestModule\n"
                        + "main: com.example.TestModule\n"
                        + "loadAfter:\n"
                        + "  - OtherModule\n");

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(jar);

        assertThat(info.getName()).isEqualTo("TestModule");
        assertThat(info.getMain()).isEqualTo("com.example.TestModule");
        assertThat(info.getLoadAfter()).containsExactly("OtherModule");
    }

    @Test
    @DisplayName("plugin.yml 中没有 main: 时 getMain() 为 null")
    void pluginYmlWithoutMainReturnsNullMain() throws Exception {
        File jar = writePluginYmlJar("name: TestModule\n");

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(jar);

        assertThat(info.getName()).isEqualTo("TestModule");
        assertThat(info.getMain()).isNull();
    }

    @Test
    @DisplayName("格式错误的 plugin.yml 返回 EMPTY 而不抛出异常")
    void malformedPluginYmlReturnsEmpty() throws Exception {
        File jar = writePluginYmlJar("name: [this is not closed\n");

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(jar);

        assertThat(info.getName()).isNull();
        assertThat(info.getMain()).isNull();
        assertThat(info.getLoadAfter()).isEmpty();
    }

    @Test
    @DisplayName("非 jar 格式的坏文件返回 EMPTY 而不抛出异常")
    void corruptArchiveReturnsEmpty() throws Exception {
        File jar = new File(tempDir, "corrupt.jar");
        Files.write(jar.toPath(), new byte[]{0x00, 0x01, 0x02, 0x03});

        PluginYmlReader.PluginYmlInfo info = PluginYmlReader.readFromJarFile(jar);

        assertThat(info.getMain()).isNull();
    }

    private File writePluginYmlJar(String pluginYmlContent) throws Exception {
        File jar = new File(tempDir, "plugin-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write(pluginYmlContent.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }
}
