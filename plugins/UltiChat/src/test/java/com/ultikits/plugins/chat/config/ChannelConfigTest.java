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

@DisplayName("ChannelConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChannelConfigTest {

    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        config = new ChannelConfig();
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
        @DisplayName("Should have global as default channel")
        void shouldHaveGlobalAsDefault() {
            assertThat(config.getDefaultChannel()).isEqualTo("global");
        }

        @Test
        @DisplayName("Should have three default channels")
        void shouldHaveThreeDefaultChannels() {
            assertThat(config.getChannels()).isNotNull();
            assertThat(config.getChannels()).hasSize(3);
        }

        @Test
        @DisplayName("Should have global channel definition")
        void shouldHaveGlobalChannel() {
            assertThat(config.getChannels()).containsKey("global");
            Map<String, Object> global = config.getChannels().get("global");
            assertThat(global.get("display-name")).isEqualTo("&f[Global]");
            assertThat(global.get("format")).isEqualTo("{display}&f: {message}");
            assertThat(global.get("permission")).isEqualTo("");
            assertThat(global.get("range")).isEqualTo(-1);
            assertThat(global.get("cross-world")).isEqualTo(true);
        }

        @Test
        @DisplayName("Should have local channel definition")
        void shouldHaveLocalChannel() {
            assertThat(config.getChannels()).containsKey("local");
            Map<String, Object> local = config.getChannels().get("local");
            assertThat(local.get("display-name")).isEqualTo("&a[Local]");
            assertThat(local.get("format")).isEqualTo("{display}&7: {message}");
            assertThat(local.get("permission")).isEqualTo("");
            assertThat(local.get("range")).isEqualTo(100);
            assertThat(local.get("cross-world")).isEqualTo(false);
        }

        @Test
        @DisplayName("Should have staff channel definition")
        void shouldHaveStaffChannel() {
            assertThat(config.getChannels()).containsKey("staff");
            Map<String, Object> staff = config.getChannels().get("staff");
            assertThat(staff.get("display-name")).isEqualTo("&c[Staff]");
            assertThat(staff.get("format")).isEqualTo("&c[Staff] &f{player}&7: {message}");
            assertThat(staff.get("permission")).isEqualTo("ultichat.channel.staff");
            assertThat(staff.get("range")).isEqualTo(-1);
            assertThat(staff.get("cross-world")).isEqualTo(true);
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
        @DisplayName("Should update default channel")
        void shouldUpdateDefaultChannel() {
            config.setDefaultChannel("local");
            assertThat(config.getDefaultChannel()).isEqualTo("local");
        }

        @Test
        @DisplayName("Should update channels map")
        void shouldUpdateChannels() {
            Map<String, Map<String, Object>> newChannels = new HashMap<>();
            HashMap<String, Object> custom = new HashMap<>();
            custom.put("display-name", "&d[VIP]");
            custom.put("format", "&d[VIP] {player}: {message}");
            custom.put("permission", "ultichat.channel.vip");
            custom.put("range", -1);
            custom.put("cross-world", true);
            newChannels.put("vip", custom);

            config.setChannels(newChannels);

            assertThat(config.getChannels()).hasSize(1);
            assertThat(config.getChannels()).containsKey("vip");
            assertThat(config.getChannels().get("vip").get("permission"))
                    .isEqualTo("ultichat.channel.vip");
        }

        @Test
        @DisplayName("Should allow empty channels map")
        void shouldAllowEmptyChannels() {
            config.setChannels(new HashMap<String, Map<String, Object>>());
            assertThat(config.getChannels()).isEmpty();
        }
    }
}
