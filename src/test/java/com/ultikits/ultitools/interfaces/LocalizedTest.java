package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        @DisplayName("有 @I18n 注解应该返回支持的语言列表")
        void shouldReturnSupportedLanguages() {
            // Arrange
            Localized localized = new AnnotatedLocalized();

            // Act
            List<String> supported = localized.supported();

            // Assert
            assertThat(supported).hasSize(3);
            assertThat(supported).containsExactly("en_US", "zh_CN", "ja_JP");
        }

        @Test
        @DisplayName("单语言支持")
        void shouldReturnSingleLanguage() {
            // Arrange
            Localized localized = new SingleLanguageLocalized();

            // Act
            List<String> supported = localized.supported();

            // Assert
            assertThat(supported).hasSize(1);
            assertThat(supported).containsExactly("en_US");
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
