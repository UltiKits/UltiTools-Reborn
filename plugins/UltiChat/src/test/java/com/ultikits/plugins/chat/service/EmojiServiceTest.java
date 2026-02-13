package com.ultikits.plugins.chat.service;

import com.ultikits.plugins.chat.config.EmojiConfig;
import com.ultikits.plugins.chat.utils.ChatTestHelper;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmojiService")
class EmojiServiceTest {

    private EmojiService service;
    private EmojiConfig config;

    @BeforeEach
    void setUp() throws Exception {
        ChatTestHelper.setUp();
        config = new EmojiConfig();
        service = new EmojiService();
        ChatTestHelper.setField(service, "config", config);
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatTestHelper.tearDown();
    }

    @Nested
    @DisplayName("replaceEmojis")
    class ReplaceEmojisTests {

        @Test
        @DisplayName("should return null when message is null")
        void shouldReturnNullWhenMessageIsNull() {
            config.setEnabled(true);
            assertThat(service.replaceEmojis(null)).isNull();
        }

        @Test
        @DisplayName("should return message unchanged when disabled")
        void shouldReturnUnchangedWhenDisabled() {
            config.setEnabled(false);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis("hello :smile:")).isEqualTo("hello :smile:");
        }

        @Test
        @DisplayName("should return message unchanged when mappings are null")
        void shouldReturnUnchangedWhenMappingsNull() {
            config.setEnabled(true);
            config.setMappings(null);

            assertThat(service.replaceEmojis("hello :smile:")).isEqualTo("hello :smile:");
        }

        @Test
        @DisplayName("should return message unchanged when mappings are empty")
        void shouldReturnUnchangedWhenMappingsEmpty() {
            config.setEnabled(true);
            config.setMappings(Collections.<String, String>emptyMap());

            assertThat(service.replaceEmojis("hello :smile:")).isEqualTo("hello :smile:");
        }

        @Test
        @DisplayName("should replace a single shortcode")
        void shouldReplaceSingleShortcode() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis("hello :smile:")).isEqualTo("hello \u263A");
        }

        @Test
        @DisplayName("should replace multiple different shortcodes")
        void shouldReplaceMultipleDifferentShortcodes() {
            config.setEnabled(true);
            Map<String, String> mappings = new LinkedHashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            mappings.put(":heart:", "\u2764");
            mappings.put(":star:", "\u2605");
            config.setMappings(mappings);

            String result = service.replaceEmojis(":smile: hello :heart: world :star:");
            assertThat(result).isEqualTo("\u263A hello \u2764 world \u2605");
        }

        @Test
        @DisplayName("should replace multiple occurrences of the same shortcode")
        void shouldReplaceMultipleOccurrencesOfSameShortcode() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            String result = service.replaceEmojis(":smile: :smile: :smile:");
            assertThat(result).isEqualTo("\u263A \u263A \u263A");
        }

        @Test
        @DisplayName("should leave unknown shortcodes untouched")
        void shouldLeaveUnknownShortcodesUntouched() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            String result = service.replaceEmojis(":unknown: text :smile:");
            assertThat(result).isEqualTo(":unknown: text \u263A");
        }

        @Test
        @DisplayName("should return empty string unchanged")
        void shouldReturnEmptyStringUnchanged() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis("")).isEqualTo("");
        }

        @Test
        @DisplayName("should handle message with no shortcodes")
        void shouldHandleMessageWithNoShortcodes() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", "\u263A");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis("hello world")).isEqualTo("hello world");
        }

        @Test
        @DisplayName("should skip null keys in mappings")
        void shouldSkipNullKeysInMappings() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(null, "\u263A");
            mappings.put(":heart:", "\u2764");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis("hello :heart:")).isEqualTo("hello \u2764");
        }

        @Test
        @DisplayName("should skip null values in mappings")
        void shouldSkipNullValuesInMappings() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":smile:", null);
            mappings.put(":heart:", "\u2764");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis(":smile: :heart:")).isEqualTo(":smile: \u2764");
        }

        @Test
        @DisplayName("should handle shortcodes adjacent to each other")
        void shouldHandleAdjacentShortcodes() {
            config.setEnabled(true);
            Map<String, String> mappings = new LinkedHashMap<String, String>();
            mappings.put(":a:", "A");
            mappings.put(":b:", "B");
            config.setMappings(mappings);

            assertThat(service.replaceEmojis(":a::b:")).isEqualTo("AB");
        }

        @Test
        @DisplayName("should handle shortcodes in long messages")
        void shouldHandleShortcodesInLongMessages() {
            config.setEnabled(true);
            Map<String, String> mappings = new HashMap<String, String>();
            mappings.put(":ok:", "\u2705");
            config.setMappings(mappings);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("word ");
            }
            sb.append(":ok:");
            String result = service.replaceEmojis(sb.toString());
            assertThat(result).endsWith("\u2705");
            assertThat(result).doesNotContain(":ok:");
        }

        @Test
        @DisplayName("should return null for null message even when disabled")
        void shouldReturnNullForNullEvenWhenDisabled() {
            config.setEnabled(false);
            assertThat(service.replaceEmojis(null)).isNull();
        }
    }
}
