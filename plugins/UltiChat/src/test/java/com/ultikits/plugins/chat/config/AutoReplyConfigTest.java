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

@DisplayName("AutoReplyConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
        @DisplayName("Should be enabled by default")
        void shouldBeEnabledByDefault() {
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have default cooldown of 10 seconds")
        void shouldHaveDefaultCooldown() {
            assertThat(config.getCooldown()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have default rules")
        void shouldHaveDefaultRules() {
            assertThat(config.getRules()).isNotNull();
            assertThat(config.getRules()).isNotEmpty();
            assertThat(config.getRules()).hasSize(2);
        }

        @Test
        @DisplayName("Should have server-ip rule")
        void shouldHaveServerIpRule() {
            assertThat(config.getRules()).containsKey("server-ip");
            Map<String, Object> rule = config.getRules().get("server-ip");
            assertThat(rule.get("keyword")).isEqualTo("server IP");
            assertThat(rule.get("response")).isEqualTo("Server address: play.example.com");
            assertThat(rule.get("mode")).isEqualTo("contains");
            assertThat(rule.get("case-sensitive")).isEqualTo(false);
        }

        @Test
        @DisplayName("Should have rules-info rule")
        void shouldHaveRulesInfoRule() {
            assertThat(config.getRules()).containsKey("rules-info");
            Map<String, Object> rule = config.getRules().get("rules-info");
            assertThat(rule.get("keyword")).isEqualTo("rules");
            assertThat(rule.get("response")).isEqualTo("Please check /rules for server rules.");
            assertThat(rule.get("mode")).isEqualTo("contains");
            assertThat(rule.get("case-sensitive")).isEqualTo(false);
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
        @DisplayName("Should update cooldown")
        void shouldUpdateCooldown() {
            config.setCooldown(30);
            assertThat(config.getCooldown()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should update rules")
        void shouldUpdateRules() {
            Map<String, Map<String, Object>> newRules = new HashMap<>();
            HashMap<String, Object> rule = new HashMap<>();
            rule.put("keyword", "help");
            rule.put("response", "Use /help");
            rule.put("mode", "exact");
            rule.put("case-sensitive", true);
            newRules.put("help-rule", rule);

            config.setRules(newRules);

            assertThat(config.getRules()).hasSize(1);
            assertThat(config.getRules()).containsKey("help-rule");
            assertThat(config.getRules().get("help-rule").get("keyword")).isEqualTo("help");
        }

        @Test
        @DisplayName("Should allow empty rules map")
        void shouldAllowEmptyRules() {
            config.setRules(new HashMap<String, Map<String, Object>>());
            assertThat(config.getRules()).isEmpty();
        }
    }
}
