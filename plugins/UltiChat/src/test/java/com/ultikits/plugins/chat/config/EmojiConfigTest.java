package com.ultikits.plugins.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmojiConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EmojiConfigTest {

    private EmojiConfig config;

    @BeforeEach
    void setUp() {
        config = new EmojiConfig();
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should be enabled by default")
        void shouldBeEnabledByDefault() {
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default emoji mappings")
        void shouldHaveDefaultMappings() {
            assertThat(config.getMappings()).isNotNull();
            assertThat(config.getMappings()).isNotEmpty();
            assertThat(config.getMappings()).hasSize(4);
        }

        @Test
        @DisplayName("Should have heart emoji mapping")
        void shouldHaveHeartEmoji() {
            assertThat(config.getMappings()).containsKey(":heart:");
            assertThat(config.getMappings().get(":heart:")).isEqualTo("\u2764");
        }

        @Test
        @DisplayName("Should have star emoji mapping")
        void shouldHaveStarEmoji() {
            assertThat(config.getMappings()).containsKey(":star:");
            assertThat(config.getMappings().get(":star:")).isEqualTo("\u2605");
        }

        @Test
        @DisplayName("Should have smile emoji mapping")
        void shouldHaveSmileEmoji() {
            assertThat(config.getMappings()).containsKey(":smile:");
            assertThat(config.getMappings().get(":smile:")).isEqualTo("\u263A");
        }

        @Test
        @DisplayName("Should have sword emoji mapping")
        void shouldHaveSwordEmoji() {
            assertThat(config.getMappings()).containsKey(":sword:");
            assertThat(config.getMappings().get(":sword:")).isEqualTo("\u2694");
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update enabled")
        void shouldUpdateEnabled() {
            config.setEnabled(false);
            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update mappings")
        void shouldUpdateMappings() {
            Map<String, String> newMappings = new HashMap<>();
            newMappings.put(":fire:", "\uD83D\uDD25");
            newMappings.put(":thumbsup:", "\uD83D\uDC4D");

            config.setMappings(newMappings);

            assertThat(config.getMappings()).hasSize(2);
            assertThat(config.getMappings()).containsKey(":fire:");
            assertThat(config.getMappings()).containsKey(":thumbsup:");
        }

        @Test
        @DisplayName("Should allow empty mappings")
        void shouldAllowEmptyMappings() {
            config.setMappings(new HashMap<String, String>());
            assertThat(config.getMappings()).isEmpty();
        }

        @Test
        @DisplayName("Should replace all existing mappings")
        void shouldReplaceExistingMappings() {
            assertThat(config.getMappings()).hasSize(4);

            Map<String, String> newMappings = new HashMap<>();
            newMappings.put(":custom:", "X");
            config.setMappings(newMappings);

            assertThat(config.getMappings()).hasSize(1);
            assertThat(config.getMappings()).doesNotContainKey(":heart:");
            assertThat(config.getMappings()).containsKey(":custom:");
        }
    }
}
