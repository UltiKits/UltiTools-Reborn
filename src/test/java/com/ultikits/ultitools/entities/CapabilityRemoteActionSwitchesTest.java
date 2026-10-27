package com.ultikits.ultitools.entities;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRemoteActionSwitchesTest {
    @ParameterizedTest
    @CsvSource({"POWER_ACTIONS,power-actions", "PLAYER_ACTIONS,player-actions"})
    void remoteActionSwitchesShipDisabledWithMatchingBilingualComments(String name, String key) throws Exception {
        Capability capability = Capability.valueOf(name);
        assertThat(capability.getConfigKey()).isEqualTo(key);
        assertThat(capability.getConfigPath()).isEqualTo("ultipanel.capabilities." + key);
        assertThat(capability.getDefaultEnabled()).isFalse();
        assertThat(capability.ordinal()).isLessThan(Capability.NONE.ordinal());
        List<String> comments = capability.getCommentLines();
        assertThat(comments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(comments.get(0)).startsWith("Whether the panel may");
        assertThat(comments.get(comments.size() - 1)).contains("面板");
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            assertThat(input).isNotNull();
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            assertThat(config.isSet(capability.getConfigPath())).isTrue();
            assertThat(config.getBoolean(capability.getConfigPath(), true)).isFalse();
            assertThat(config.getComments(capability.getConfigPath())).containsExactlyElementsOf(comments);
            assertThat(config.getStringList("ultipanel.commands.blocklist")).contains("stop", "restart");
        }
    }
}
