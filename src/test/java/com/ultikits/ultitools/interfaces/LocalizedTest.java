package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.I18n;

/**
 * Localized 接口测试
 */
@DisplayName("Localized 接口测试")
class LocalizedTest {

    /**
     * 没有 @I18n 注解的 Localized 实现
     */
    static class NoAnnotationLocalized implements Localized {
        // 使用默认实现
    }

    /**
     * 有 @I18n 注解的 Localized 实现
     */
    @I18n({"en_US", "zh_CN", "ja_JP"})
    static class AnnotatedLocalized implements Localized {
        // 使用默认实现
    }

    /**
     * 只支持一种语言的 Localized 实现
     */
    @I18n({"en_US"})
    static class SingleLanguageLocalized implements Localized {
        // 使用默认实现
    }

    /**
     * 没有 @I18n 注解的 Localized 实现 - 供 SupportedEndToEndTests 控制其代码源目录的 lang/ 内容。
     * <p>
     * static 嵌套类不能声明在非 static 的 {@code @Nested} 内部类里面（Java 8 语言规则），
     * 所以这些端到端测试用的实现类必须放在顶层。
     */
    static class DirectoryBackedLocalized implements Localized {
    }

    /** @I18n 声明了 "fr"，但实际只打包了 en.json -- 派生结果必须以实际资源为准，而不是注解。 */
    @I18n({"fr"})
    static class AnnotatedButOnlyShipsEnglish implements Localized {
    }

    /**
     * 自定义 i18n 实现的 Localized
     */
    @I18n({"en_US", "zh_CN"})
    static class CustomI18nLocalized implements Localized {
        @Override
        public String i18n(String code, String str) {
            if ("zh_CN".equals(code)) {
                if ("Hello".equals(str)) {
                    return "你好";
                }
                if ("World".equals(str)) {
                    return "世界";
                }
            } else if ("en_US".equals(code)) {
                return str; // 英文原文
            }
            return str; // 默认返回原文
        }
    }

    @Nested
    @DisplayName("supported 测试")
    class SupportedTests {

        // NOTE (04-04 Task 1): supported() no longer reads @I18n at all (D-20) - it is derived
        // from the lang/*.json resources the implementor's own code source actually ships. None
        // of these three fixtures has a real lang/ directory backing their code source
        // (target/test-classes), so all three now correctly return an empty list regardless of
        // their @I18n annotation. Task 3 owns the full rewrite of this nested class (better
        // fixtures, names reflecting the new contract) - this is the minimal fix needed to keep
        // this test class green after Task 1's behavior change.

        @Test
        @DisplayName("没有 @I18n 注解应该返回空列表")
        void shouldReturnEmptyListWithoutAnnotation() {
            // Arrange
            Localized localized = new NoAnnotationLocalized();

            // Act
            List<String> supported = localized.supported();

            // Assert
            assertThat(supported).isEmpty();
        }

        @Test
        @DisplayName("有 @I18n 注解但未打包任何 lang/*.json 时应该返回空列表 (D-20: 注解不再驱动派生结果)")
        void shouldReturnEmptyListWhenAnnotatedButNoLangResourcesShipped() {
            // Arrange
            Localized localized = new AnnotatedLocalized();

            // Act
            List<String> supported = localized.supported();

            // Assert
            assertThat(supported).isEmpty();
        }

        @Test
        @DisplayName("单语言注解但未打包 lang/*.json 时应该返回空列表 (D-20: 注解不再驱动派生结果)")
        void shouldReturnEmptyListForSingleAnnotationWithoutShippedResources() {
            // Arrange
            Localized localized = new SingleLanguageLocalized();

            // Act
            List<String> supported = localized.supported();

            // Assert
            assertThat(supported).isEmpty();
        }
    }

    @Nested
    @DisplayName("supported() 端到端 - 真实实现类的目录代码源测试 (04-04 Task 1, D-20)")
    class SupportedEndToEndTests {

        private File langDir;

        @BeforeEach
        void setUp() throws URISyntaxException {
            URL codeSourceUrl = DirectoryBackedLocalized.class.getProtectionDomain().getCodeSource().getLocation();
            File testClassesRoot = new File(codeSourceUrl.toURI());
            langDir = new File(testClassesRoot, "lang");
            // Defensive: a previous run's cleanup failing should be loud, not silently overwritten.
            assertThat(langDir).doesNotExist();
        }

        @AfterEach
        void tearDown() throws IOException {
            if (langDir.exists()) {
                deleteRecursively(langDir);
            }
        }

        @Test
        @DisplayName("目录代码源下 lang/en.json 与 lang/zh.json 被正确枚举")
        void directoryCodeSourceEnumeratesShippedLanguages() throws IOException {
            langDir.mkdirs();
            Files.write(new File(langDir, "en.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(langDir, "zh.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(new DirectoryBackedLocalized().supported()).containsExactlyInAnyOrder("en", "zh");
        }

        @Test
        @DisplayName("@I18n 声明了 fr，但实际只打包了 en.json -- 派生结果以实际资源为准，不是注解")
        void annotationDoesNotOverrideActualResources() throws IOException {
            langDir.mkdirs();
            Files.write(new File(langDir, "en.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(new AnnotatedButOnlyShipsEnglish().supported()).containsExactly("en");
        }

        @Test
        @DisplayName("没有 lang/ 目录时返回空列表，不抛出异常")
        void noLangDirectoryReturnsEmptyList() {
            assertThat(new DirectoryBackedLocalized().supported()).isEmpty();
        }

        private void deleteRecursively(File file) throws IOException {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
            Files.delete(file.toPath());
        }
    }

    @Nested
    @DisplayName("scanLangJar / scanLangDirectory 派生辅助方法测试 (04-04 Task 1, D-20)")
    class LangResourceDerivationHelperTests {

        @TempDir
        File tempDir;

        @Test
        @DisplayName("JAR 中 lang/ 直接子级的 .json 条目被枚举，非 .json 条目被忽略")
        void jarEntriesAreFilteredToJsonUnderLang() throws IOException {
            File jar = new File(tempDir, "module.jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
                writeEntry(out, "lang/en.json", "{}");
                writeEntry(out, "lang/zh.json", "{}");
                writeEntry(out, "lang/README.txt", "notes");
            }

            assertThat(Localized.scanLangJar(jar)).containsExactlyInAnyOrder("en", "zh");
        }

        @Test
        @DisplayName("JAR 中嵌套的 lang/extra/en.json 被忽略，只统计 lang/ 直接子级")
        void nestedJarEntriesAreIgnored() throws IOException {
            File jar = new File(tempDir, "nested.jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
                writeEntry(out, "lang/extra/en.json", "{}");
            }

            assertThat(Localized.scanLangJar(jar)).isEmpty();
        }

        @Test
        @DisplayName("无法作为 JAR 打开的文件（随机字节）返回空列表，不抛出异常")
        void unreadableJarReturnsEmptyList() throws IOException {
            File garbage = new File(tempDir, "garbage.jar");
            Files.write(garbage.toPath(), new byte[]{1, 2, 3, 4});

            assertThat(Localized.scanLangJar(garbage)).isEmpty();
        }

        @Test
        @DisplayName("目录中没有 .json 文件时返回空列表")
        void directoryWithNoJsonFilesReturnsEmptyList() throws IOException {
            File langDir = new File(tempDir, "lang");
            langDir.mkdirs();
            Files.write(new File(langDir, "README.txt").toPath(), "notes".getBytes(StandardCharsets.UTF_8));

            assertThat(Localized.scanLangDirectory(langDir)).isEmpty();
        }

        @Test
        @DisplayName("目录中嵌套的 lang/extra/en.json 被忽略")
        void nestedDirectoryEntriesAreIgnored() throws IOException {
            File langDir = new File(tempDir, "lang");
            File extra = new File(langDir, "extra");
            extra.mkdirs();
            Files.write(new File(extra, "en.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(Localized.scanLangDirectory(langDir)).isEmpty();
        }

        @Test
        @DisplayName("不存在的 lang 目录返回空列表")
        void missingLangDirectoryReturnsEmptyList() {
            File missing = new File(tempDir, "does-not-exist");
            assertThat(Localized.scanLangDirectory(missing)).isEmpty();
        }

        private void writeEntry(JarOutputStream out, String name, String content) throws IOException {
            out.putNextEntry(new JarEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    @Nested
    @DisplayName("i18n 测试")
    class I18nTests {

        @Test
        @DisplayName("默认实现应该返回原文")
        void defaultShouldReturnOriginalText() {
            // Arrange
            Localized localized = new NoAnnotationLocalized();

            // Act
            String result = localized.i18n("zh_CN", "Hello World");

            // Assert
            assertThat(result).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("即使有注解，默认 i18n 也应该返回原文")
        void annotatedDefaultShouldReturnOriginalText() {
            // Arrange
            Localized localized = new AnnotatedLocalized();

            // Act
            String result = localized.i18n("zh_CN", "Test Message");

            // Assert
            assertThat(result).isEqualTo("Test Message");
        }

        @Test
        @DisplayName("自定义实现应该返回翻译后的文本")
        void customShouldReturnTranslatedText() {
            // Arrange
            Localized localized = new CustomI18nLocalized();

            // Act
            String result = localized.i18n("zh_CN", "Hello");

            // Assert
            assertThat(result).isEqualTo("你好");
        }

        @Test
        @DisplayName("自定义实现 - 英文应该返回原文")
        void customEnglishShouldReturnOriginal() {
            // Arrange
            Localized localized = new CustomI18nLocalized();

            // Act
            String result = localized.i18n("en_US", "Hello");

            // Assert
            assertThat(result).isEqualTo("Hello");
        }

        @Test
        @DisplayName("自定义实现 - 不支持的语言应该返回原文")
        void customUnsupportedLanguageShouldReturnOriginal() {
            // Arrange
            Localized localized = new CustomI18nLocalized();

            // Act
            String result = localized.i18n("fr_FR", "Hello");

            // Assert
            assertThat(result).isEqualTo("Hello");
        }

        @Test
        @DisplayName("自定义实现 - 未翻译的文本应该返回原文")
        void customUntranslatedTextShouldReturnOriginal() {
            // Arrange
            Localized localized = new CustomI18nLocalized();

            // Act
            String result = localized.i18n("zh_CN", "Unknown Text");

            // Assert
            assertThat(result).isEqualTo("Unknown Text");
        }

        @Test
        @DisplayName("空字符串应该正常处理")
        void emptyStringShouldWork() {
            // Arrange
            Localized localized = new NoAnnotationLocalized();

            // Act
            String result = localized.i18n("en_US", "");

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空语言代码应该正常处理")
        void emptyLanguageCodeShouldWork() {
            // Arrange
            Localized localized = new NoAnnotationLocalized();

            // Act
            String result = localized.i18n("", "Test");

            // Assert
            assertThat(result).isEqualTo("Test");
        }

        @Test
        @DisplayName("null 语言代码应该正常处理")
        void nullLanguageCodeShouldWork() {
            // Arrange
            Localized localized = new NoAnnotationLocalized();

            // Act
            String result = localized.i18n(null, "Test");

            // Assert
            assertThat(result).isEqualTo("Test");
        }
    }
}
