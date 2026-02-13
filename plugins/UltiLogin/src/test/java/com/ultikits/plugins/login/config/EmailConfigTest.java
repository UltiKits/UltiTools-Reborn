package com.ultikits.plugins.login.config;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EmailConfig Tests")
class EmailConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have default code length of 6")
        void codeLength() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getCodeLength()).isEqualTo(6);
        }

        @Test
        @DisplayName("Should have default code expiry of 300 seconds")
        void codeExpirySeconds() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getCodeExpirySeconds()).isEqualTo(300);
        }

        @Test
        @DisplayName("Should have default max attempts of 3")
        void maxAttempts() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getMaxAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should have default cooldown of 60 seconds")
        void cooldownSeconds() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getCooldownSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should have default domain blacklist with 5 entries")
        void domainBlacklist() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getDomainBlacklist())
                    .hasSize(5)
                    .contains("10minutemail.com", "tempmail.com", "guerrillamail.com",
                            "mailinator.com", "throwaway.email");
        }

        @Test
        @DisplayName("Should have default max accounts per email of 1")
        void maxAccountsPerEmail() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getMaxAccountsPerEmail()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should have rewards disabled by default")
        void rewardEnabled() {
            EmailConfig config = new EmailConfig();
            assertThat(config.isRewardEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have default reward commands")
        void rewardCommands() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getRewardCommands())
                    .hasSize(1)
                    .contains("givemoney %player% 500");
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update code length")
        void setCodeLength() {
            EmailConfig config = new EmailConfig();
            config.setCodeLength(8);
            assertThat(config.getCodeLength()).isEqualTo(8);
        }

        @Test
        @DisplayName("Should update code expiry seconds")
        void setCodeExpirySeconds() {
            EmailConfig config = new EmailConfig();
            config.setCodeExpirySeconds(600);
            assertThat(config.getCodeExpirySeconds()).isEqualTo(600);
        }

        @Test
        @DisplayName("Should update max attempts")
        void setMaxAttempts() {
            EmailConfig config = new EmailConfig();
            config.setMaxAttempts(5);
            assertThat(config.getMaxAttempts()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should update cooldown seconds")
        void setCooldownSeconds() {
            EmailConfig config = new EmailConfig();
            config.setCooldownSeconds(120);
            assertThat(config.getCooldownSeconds()).isEqualTo(120);
        }

        @Test
        @DisplayName("Should update domain blacklist")
        void setDomainBlacklist() {
            EmailConfig config = new EmailConfig();
            config.setDomainBlacklist(java.util.Arrays.asList("spam.com", "junk.net"));
            assertThat(config.getDomainBlacklist()).containsExactly("spam.com", "junk.net");
        }

        @Test
        @DisplayName("Should update max accounts per email")
        void setMaxAccountsPerEmail() {
            EmailConfig config = new EmailConfig();
            config.setMaxAccountsPerEmail(3);
            assertThat(config.getMaxAccountsPerEmail()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should update reward enabled")
        void setRewardEnabled() {
            EmailConfig config = new EmailConfig();
            config.setRewardEnabled(true);
            assertThat(config.isRewardEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update reward commands")
        void setRewardCommands() {
            EmailConfig config = new EmailConfig();
            config.setRewardCommands(java.util.Arrays.asList("eco give %player% 1000", "say %player% bound email"));
            assertThat(config.getRewardCommands()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Config File Path")
    class ConfigFilePath {

        @Test
        @DisplayName("Should have correct config file path")
        void configFilePath() {
            EmailConfig config = new EmailConfig();
            assertThat(config.getConfigFilePath()).isEqualTo("config/email.yml");
        }
    }
}
