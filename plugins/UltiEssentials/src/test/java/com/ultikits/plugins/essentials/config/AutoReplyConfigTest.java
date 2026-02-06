package com.ultikits.plugins.essentials.config;

import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AutoReplyConfig.
 * <p>
 * 测试自动回复配置类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("AutoReplyConfig Tests")
class AutoReplyConfigTest {

    private AutoReplyConfig config;

    @BeforeEach
    void setUp() {
        config = new AutoReplyConfig();
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have default rules")
        void shouldHaveDefaultRules() {
            assertThat(config.getRules()).isNotNull();
            assertThat(config.getRules()).isNotEmpty();
            assertThat(config.getRules()).containsKey("服务器IP");
        }

        @Test
        @DisplayName("Should be case-insensitive by default")
        void shouldBeCaseInsensitiveByDefault() {
            assertThat(config.isCaseSensitive()).isFalse();
        }

        @Test
        @DisplayName("Should have default cooldown of 10 seconds")
        void shouldHaveDefaultCooldown() {
            assertThat(config.getCooldown()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update rules")
        void shouldUpdateRules() {
            Map<String, String> newRules = new HashMap<>();
            newRules.put("test", "test response");
            config.setRules(newRules);

            assertThat(config.getRules()).containsKey("test");
            assertThat(config.getRules()).hasSize(1);
        }

        @Test
        @DisplayName("Should update case sensitivity")
        void shouldUpdateCaseSensitivity() {
            config.setCaseSensitive(true);
            assertThat(config.isCaseSensitive()).isTrue();
        }

        @Test
        @DisplayName("Should update cooldown")
        void shouldUpdateCooldown() {
            config.setCooldown(30);
            assertThat(config.getCooldown()).isEqualTo(30);
        }
    }
}
