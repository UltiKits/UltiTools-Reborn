package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.annotations.command.CmdCD;

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
     * 类型上标注了额外语言声明，但实际只打包了 en.json -- 派生结果必须以实际资源为准，而不是这个声明。
     * <p>
     * static 嵌套类不能声明在非 static 的 {@code @Nested} 内部类里面（Java 8 语言规则），
     * 所以这些端到端测试用的实现类必须放在顶层。
     */
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
    @DisplayName("supported 测试 -- 派生自 lang/*.json 资源 (D-20)")
    class SupportedTests {

        // Rewritten for D-20 (04-04 Task 3): supported() is derived from the lang/*.json
        // resources an implementor's own code source actually ships, not from any type-level
        // annotation. These tests exercise the real default method end-to-end against a real
        // directory code source (target/test-classes, the shape Surefire runs test classes
        // from) rather than the old contract where an annotation alone drove the result.

        private File langDir;

        @BeforeEach
        void setUp() throws URISyntaxException {
            URL codeSourceUrl = NoAnnotationLocalized.class.getProtectionDomain().getCodeSource().getLocation();
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
        @DisplayName("没有 lang/ 目录时返回空列表，不抛出异常")
        void noLangDirectoryReturnsEmptyList() {
            assertThat(new NoAnnotationLocalized().supported()).isEmpty();
        }

        @Test
        @DisplayName("目录代码源下 lang/en.json 与 lang/zh.json 被正确枚举")
        void directoryCodeSourceEnumeratesShippedLanguages() throws IOException {
            langDir.mkdirs();
            Files.write(new File(langDir, "en.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(langDir, "zh.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(new NoAnnotationLocalized().supported()).containsExactlyInAnyOrder("en", "zh");
        }

        @Test
        @DisplayName("类型上标注了额外语言声明，但实际只打包了 en.json -- 派生结果以实际资源为准")
        void typeLevelDeclarationDoesNotOverrideActualResources() throws IOException {
            langDir.mkdirs();
            Files.write(new File(langDir, "en.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));

            assertThat(new AnnotatedButOnlyShipsEnglish().supported()).containsExactly("en");
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

    /**
     * SILENT-15: {@code CooldownValidator} formats a parameterized source literal (see its
     * {@code validate()}), but neither {@code lang/en.json} nor {@code lang/zh.json} carried an
     * entry for it -- an English-locale player saw raw Chinese, and Chinese only looked correct
     * by {@link com.ultikits.ultitools.entities.Language#getLocalizedText}'s identity fallback (source text returned unchanged
     * when no dictionary entry exists), not because the key existed.
     * <p>
     * The expected key is DERIVED from {@link CooldownValidator}'s own message-building call to
     * {@code UltiTools.getInstance().i18n(...)} at test time -- captured via a mocked {@code
     * i18n(String)} -- rather than retyped here, so a future edit to the source literal breaks
     * this test instead of silently reintroducing the identity-fallback gap the fallback masks.
     * <br>
     * SILENT-15：{@code CooldownValidator} 格式化的是一个带参数的源字面量（见其 {@code validate()}），
     * 但 {@code lang/en.json} 与 {@code lang/zh.json} 都没有对应条目——英文玩家看到的是原始中文，
     * 中文玩家只是因为 {@link com.ultikits.ultitools.entities.Language#getLocalizedText} 的恒等回退（找不到词典条目时原样返回源文本）
     * 而“看起来正确”，并非因为该 key 真的存在。
     */
    @Nested
    @DisplayName("cooldown 消息 i18n key 完整性测试 (SILENT-15)")
    class CooldownMessageKeyCompletenessTests {

        private static final String LEGACY_COOLDOWN_KEY = "操作频繁，请稍后再试";

        private final Type langMapType = new TypeToken<Map<String, String>>() {
        }.getType();

        private Player mockPlayer;
        private Command mockCommand;
        private UltiTools mockUltiTools;
        private MockedStatic<UltiTools> mockedUltiTools;
        private String expectedKey;

        /** Fixture method carrying {@code @CmdCD} so {@code CooldownValidator} treats it as
         * cooldown-gated -- never invoked, only read for its annotation. */
        @CmdCD(5)
        private void methodWithCooldown() {
        }

        @BeforeEach
        void setUp() throws Exception {
            mockPlayer = mock(Player.class);
            mockCommand = mock(Command.class);
            mockUltiTools = mock(UltiTools.class);
            mockedUltiTools = mockStatic(UltiTools.class);
            mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiTools);

            AtomicReference<String> captured = new AtomicReference<>();
            when(mockUltiTools.i18n(anyString())).thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                return invocation.getArgument(0);
            });
            when(mockPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
            when(mockCommand.getName()).thenReturn("test");

            CooldownValidator validator = new CooldownValidator();
            Method method = getClass().getDeclaredMethod("methodWithCooldown");
            CommandContext context = CommandContext.builder()
                    .sender(mockPlayer)
                    .command(mockCommand)
                    .alias("test")
                    .rawArgs(new String[]{})
                    .matchedMethod(method)
                    .build();

            // Put the player on cooldown, then trigger the rejection path -- this is what
            // reaches CooldownValidator's i18n(...) call and captures its exact argument.
            validator.applyCooldown(context);
            validator.validate(context);

            expectedKey = captured.get();
            assertThat(expectedKey)
                    .as("CooldownValidator.validate() must call i18n(...) while the player is on "
                            + "cooldown -- if this is null, the derivation itself is broken, not "
                            + "the language files")
                    .isNotNull();
        }

        @AfterEach
        void tearDown() {
            if (mockedUltiTools != null) {
                mockedUltiTools.close();
            }
        }

        private Map<String, String> loadLangResource(String resourcePath) throws IOException {
            try (Reader reader = new InputStreamReader(
                    getClass().getClassLoader().getResourceAsStream(resourcePath), StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, langMapType);
            }
        }

        @Test
        @DisplayName("Test 1: lang/en.json contains the parameterized cooldown key (presence, "
                + "not message production)")
        void enJsonContainsParameterizedCooldownKey() throws IOException {
            Map<String, String> en = loadLangResource("lang/en.json");

            assertThat(en)
                    .as("en.json must carry the exact key CooldownValidator formats -- absence "
                            + "is invisible at runtime because i18n falls back to the source text")
                    .containsKey(expectedKey);
        }

        @Test
        @DisplayName("Test 2: lang/zh.json contains the same parameterized cooldown key")
        void zhJsonContainsParameterizedCooldownKey() throws IOException {
            Map<String, String> zh = loadLangResource("lang/zh.json");

            assertThat(zh)
                    .as("zh.json is missing this key too -- Chinese players only saw a correct "
                            + "message by identity-fallback coincidence, not because the key existed")
                    .containsKey(expectedKey);
        }

        @Test
        @DisplayName("Test 3: the English value carries a %d placeholder and formats a "
                + "well-formed English sentence")
        void enValueHasPlaceholderAndFormatsToEnglish() throws IOException {
            Map<String, String> en = loadLangResource("lang/en.json");
            String value = en.get(expectedKey);

            assertThat(value).as("en.json entry for the cooldown key").isNotNull();
            assertThat(value).as("the English translation must carry the %%d placeholder")
                    .contains("%d");

            String formatted = String.format(value, 5L);
            assertThat(formatted)
                    .as("formatting the remaining-seconds argument must fully substitute the "
                            + "placeholder and read as English, not leak raw Chinese source text")
                    .doesNotContain("%d")
                    .doesNotMatch(".*[一-鿿].*");
        }

        @Test
        @DisplayName("Test 4: the Chinese value follows the file's identity-mapping convention")
        void zhValueIsIdentityMapped() throws IOException {
            Map<String, String> zh = loadLangResource("lang/zh.json");

            assertThat(zh.get(expectedKey))
                    .as("zh.json maps every Chinese-source key to itself, matching every other "
                            + "Chinese-source entry in the file")
                    .isEqualTo(expectedKey);
        }

        @Test
        @DisplayName("Test 5: both files carry the same production-derived key, byte-identical "
                + "to the source literal (never retyped)")
        void bothLanguageFilesUseTheSameProductionDerivedKey() throws IOException {
            Map<String, String> en = loadLangResource("lang/en.json");
            Map<String, String> zh = loadLangResource("lang/zh.json");

            assertThat(en).containsKey(expectedKey);
            assertThat(zh).containsKey(expectedKey);
            assertThat(expectedKey)
                    .as("the derived key must carry the %%d placeholder CooldownValidator "
                            + "actually formats -- confirms the derivation reached the real call")
                    .contains("%d");
        }

        @Test
        @DisplayName("Test 6: the older, non-parameterized cooldown key is still present in both "
                + "files -- nothing in this plan requires its removal")
        void olderNonParameterizedKeyStillPresentInBothFiles() throws IOException {
            Map<String, String> en = loadLangResource("lang/en.json");
            Map<String, String> zh = loadLangResource("lang/zh.json");

            assertThat(en).containsKey(LEGACY_COOLDOWN_KEY);
            assertThat(zh).containsKey(LEGACY_COOLDOWN_KEY);
        }
    }
}
