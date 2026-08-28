package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FileUtils 测试类
 */
@DisplayName("FileUtils 测试")
class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("touch 方法测试")
    class TouchMethodTests {

        @Test
        @DisplayName("应该创建新文件")
        void shouldCreateNewFile() {
            File file = tempDir.resolve("newfile.txt").toFile();

            boolean result = FileUtils.touch(file);

            assertThat(result).isTrue();
            assertThat(file.exists()).isTrue();
        }

        @Test
        @DisplayName("应该返回 true 对于已存在的文件")
        void shouldReturnTrueForExistingFile() throws IOException {
            File file = tempDir.resolve("existing.txt").toFile();
            file.createNewFile();

            boolean result = FileUtils.touch(file);

            assertThat(result).isTrue();
            assertThat(file.exists()).isTrue();
        }

        @Test
        @DisplayName("应该创建父目录")
        void shouldCreateParentDirectories() {
            File file = tempDir.resolve("parent/child/file.txt").toFile();

            boolean result = FileUtils.touch(file);

            assertThat(result).isTrue();
            assertThat(file.exists()).isTrue();
            assertThat(file.getParentFile().exists()).isTrue();
        }

        @Test
        @DisplayName("应该返回 false 对于 null 参数")
        void shouldReturnFalseForNull() {
            boolean result = FileUtils.touch(null);
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("del 方法测试")
    class DelMethodTests {

        @Test
        @DisplayName("应该删除文件")
        void shouldDeleteFile() throws IOException {
            File file = tempDir.resolve("todelete.txt").toFile();
            file.createNewFile();

            boolean result = FileUtils.del(file);

            assertThat(result).isTrue();
            assertThat(file.exists()).isFalse();
        }

        @Test
        @DisplayName("应该返回 true 对于不存在的文件")
        void shouldReturnTrueForNonExistentFile() {
            File file = tempDir.resolve("nonexistent.txt").toFile();

            boolean result = FileUtils.del(file);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("应该返回 true 对于 null 参数")
        void shouldReturnTrueForNull() {
            boolean result = FileUtils.del(null);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("应该递归删除目录")
        void shouldDeleteDirectoryRecursively() throws IOException {
            File dir = tempDir.resolve("todeleteDir").toFile();
            dir.mkdir();
            new File(dir, "file1.txt").createNewFile();
            new File(dir, "file2.txt").createNewFile();
            File subDir = new File(dir, "subdir");
            subDir.mkdir();
            new File(subDir, "subfile.txt").createNewFile();

            boolean result = FileUtils.del(dir);

            assertThat(result).isTrue();
            assertThat(dir.exists()).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteDirectory 方法测试")
    class DeleteDirectoryMethodTests {

        @Test
        @DisplayName("应该删除空目录")
        void shouldDeleteEmptyDirectory() {
            File dir = tempDir.resolve("emptydir").toFile();
            dir.mkdir();

            boolean result = FileUtils.deleteDirectory(dir);

            assertThat(result).isTrue();
            assertThat(dir.exists()).isFalse();
        }

        @Test
        @DisplayName("应该删除非空目录")
        void shouldDeleteNonEmptyDirectory() throws IOException {
            File dir = tempDir.resolve("nonemptydir").toFile();
            dir.mkdir();
            new File(dir, "file.txt").createNewFile();

            boolean result = FileUtils.deleteDirectory(dir);

            assertThat(result).isTrue();
            assertThat(dir.exists()).isFalse();
        }

        @Test
        @DisplayName("应该返回 true 对于 null 参数")
        void shouldReturnTrueForNull() {
            boolean result = FileUtils.deleteDirectory(null);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("应该返回 true 对于不存在的目录")
        void shouldReturnTrueForNonExistentDirectory() {
            File dir = tempDir.resolve("nonexistentdir").toFile();

            boolean result = FileUtils.deleteDirectory(dir);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("mainName 方法测试")
    class MainNameMethodTests {

        @Test
        @DisplayName("应该获取文件主名")
        void shouldGetMainNameFromFile() {
            File file = new File("test.txt");

            String mainName = FileUtils.mainName(file);

            assertThat(mainName).isEqualTo("test");
        }

        @Test
        @DisplayName("应该获取文件名的主名")
        void shouldGetMainNameFromFileName() {
            assertThat(FileUtils.mainName("test.txt")).isEqualTo("test");
            assertThat(FileUtils.mainName("test.config.yml")).isEqualTo("test.config");
            assertThat(FileUtils.mainName("test")).isEqualTo("test");
        }

        @Test
        @DisplayName("应该返回 null 对于 null 文件")
        void shouldReturnNullForNullFile() {
            assertThat(FileUtils.mainName((File) null)).isNull();
        }

        @Test
        @DisplayName("应该处理空文件名")
        void shouldHandleEmptyFileName() {
            assertThat(FileUtils.mainName("")).isEqualTo("");
        }

        @Test
        @DisplayName("应该处理没有扩展名的文件名")
        void shouldHandleFileNameWithoutExtension() {
            assertThat(FileUtils.mainName("filename")).isEqualTo("filename");
        }

        @Test
        @DisplayName("应该处理 null 文件名")
        void shouldHandleNullFileName() {
            assertThat(FileUtils.mainName((String) null)).isNull();
        }
    }

    @Nested
    @DisplayName("extName 方法测试")
    class ExtNameMethodTests {

        @Test
        @DisplayName("应该获取文件扩展名")
        void shouldGetExtNameFromFile() {
            File file = new File("test.txt");

            String extName = FileUtils.extName(file);

            assertThat(extName).isEqualTo("txt");
        }

        @Test
        @DisplayName("应该获取文件名的扩展名")
        void shouldGetExtNameFromFileName() {
            assertThat(FileUtils.extName("test.txt")).isEqualTo("txt");
            assertThat(FileUtils.extName("test.config.yml")).isEqualTo("yml");
        }

        @Test
        @DisplayName("应该返回 null 对于 null 文件")
        void shouldReturnNullForNullFile() {
            assertThat(FileUtils.extName((File) null)).isNull();
        }

        @Test
        @DisplayName("应该返回空字符串对于没有扩展名的文件")
        void shouldReturnEmptyForFileWithoutExtension() {
            assertThat(FileUtils.extName("filename")).isEqualTo("");
        }

        @Test
        @DisplayName("应该返回空字符串对于空文件名")
        void shouldReturnEmptyForEmptyFileName() {
            assertThat(FileUtils.extName("")).isEqualTo("");
        }

        @Test
        @DisplayName("应该返回空字符串对于以点结尾的文件名")
        void shouldReturnEmptyForFileNameEndingWithDot() {
            assertThat(FileUtils.extName("filename.")).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("mkdir 方法测试")
    class MkdirMethodTests {

        @Test
        @DisplayName("应该创建目录")
        void shouldCreateDirectory() {
            File dir = tempDir.resolve("newdir").toFile();

            File result = FileUtils.mkdir(dir);

            assertThat(result).isEqualTo(dir);
            assertThat(dir.exists()).isTrue();
            assertThat(dir.isDirectory()).isTrue();
        }

        @Test
        @DisplayName("应该返回已存在的目录")
        void shouldReturnExistingDirectory() {
            File dir = tempDir.resolve("existingdir").toFile();
            dir.mkdir();

            File result = FileUtils.mkdir(dir);

            assertThat(result).isEqualTo(dir);
            assertThat(dir.exists()).isTrue();
        }

        @Test
        @DisplayName("应该创建嵌套目录")
        void shouldCreateNestedDirectories() {
            File dir = tempDir.resolve("level1/level2/level3").toFile();

            File result = FileUtils.mkdir(dir);

            assertThat(result).isEqualTo(dir);
            assertThat(dir.exists()).isTrue();
        }

        @Test
        @DisplayName("应该返回 null 对于 null 参数")
        void shouldReturnNullForNullInput() {
            File result = FileUtils.mkdir(null);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("mkParentDirs 方法测试")
    class MkParentDirsMethodTests {

        @Test
        @DisplayName("应该创建父目录")
        void shouldCreateParentDirectories() {
            File file = tempDir.resolve("parent1/parent2/file.txt").toFile();

            File result = FileUtils.mkParentDirs(file);

            assertThat(result).isEqualTo(file);
            assertThat(file.getParentFile().exists()).isTrue();
        }

        @Test
        @DisplayName("应该处理已存在的父目录")
        void shouldHandleExistingParentDirectory() {
            File file = tempDir.resolve("existing/file.txt").toFile();
            file.getParentFile().mkdir();

            File result = FileUtils.mkParentDirs(file);

            assertThat(result).isEqualTo(file);
            assertThat(file.getParentFile().exists()).isTrue();
        }

        @Test
        @DisplayName("应该返回 null 对于 null 参数")
        void shouldReturnNullForNullInput() {
            File result = FileUtils.mkParentDirs(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("应该处理没有父目录的文件")
        void shouldHandleFileWithNoParent() {
            File file = new File("rootfile.txt");

            File result = FileUtils.mkParentDirs(file);

            assertThat(result).isEqualTo(file);
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("私有构造函数应该抛出 UnsupportedOperationException")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void privateConstructorShouldThrowException() throws Exception {
            Constructor<FileUtils> constructor = FileUtils.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("应该处理特殊字符的文件名")
        void shouldHandleSpecialCharactersInFileName() {
            assertThat(FileUtils.mainName("file name.txt")).isEqualTo("file name");
            assertThat(FileUtils.extName("file name.txt")).isEqualTo("txt");
        }

        @Test
        @DisplayName("应该处理多个扩展名")
        void shouldHandleMultipleExtensions() {
            assertThat(FileUtils.mainName("archive.tar.gz")).isEqualTo("archive.tar");
            assertThat(FileUtils.extName("archive.tar.gz")).isEqualTo("gz");
        }

        @Test
        @DisplayName("应该处理隐藏文件")
        void shouldHandleHiddenFiles() {
            assertThat(FileUtils.mainName(".gitignore")).isEqualTo("");
            assertThat(FileUtils.extName(".gitignore")).isEqualTo("gitignore");
        }
    }
}
