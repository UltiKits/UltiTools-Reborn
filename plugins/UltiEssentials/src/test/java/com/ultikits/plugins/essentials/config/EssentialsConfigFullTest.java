package com.ultikits.plugins.essentials.config;

import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full coverage tests for EssentialsConfig fields not covered by other test files.
 * Covers teleport, player-state, management, home, TPA, warp, ban, kit,
 * scoreboard, nameprefix, and wild features.
 * <p>
 * 完整覆盖 EssentialsConfig 其余字段测试。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("EssentialsConfig Full Coverage Tests")
class EssentialsConfigFullTest {

    private EssentialsConfig config;

    @BeforeEach
    void setUp() {
        config = new EssentialsConfig();
    }

    @Nested
    @DisplayName("Teleport Feature Defaults")
    class TeleportDefaults {

        @Test
        @DisplayName("Should have back enabled by default")
        void shouldHaveBackEnabled() {
            assertThat(config.isBackEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have spawn enabled by default")
        void shouldHaveSpawnEnabled() {
            assertThat(config.isSpawnEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have lobby enabled by default")
        void shouldHaveLobbyEnabled() {
            assertThat(config.isLobbyEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have wild enabled by default")
        void shouldHaveWildEnabled() {
            assertThat(config.isWildEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct wild defaults")
        void shouldHaveWildDefaults() {
            assertThat(config.getWildMaxRange()).isEqualTo(10000);
            assertThat(config.getWildMinRange()).isEqualTo(100);
            assertThat(config.getWildCooldown()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should have recall enabled by default")
        void shouldHaveRecallEnabled() {
            assertThat(config.isRecallEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update teleport settings")
        void shouldUpdateTeleportSettings() {
            config.setBackEnabled(false);
            config.setSpawnEnabled(false);
            config.setLobbyEnabled(false);
            config.setWildEnabled(false);
            config.setWildMaxRange(5000);
            config.setWildMinRange(50);
            config.setWildCooldown(120);
            config.setRecallEnabled(false);

            assertThat(config.isBackEnabled()).isFalse();
            assertThat(config.isSpawnEnabled()).isFalse();
            assertThat(config.isLobbyEnabled()).isFalse();
            assertThat(config.isWildEnabled()).isFalse();
            assertThat(config.getWildMaxRange()).isEqualTo(5000);
            assertThat(config.getWildMinRange()).isEqualTo(50);
            assertThat(config.getWildCooldown()).isEqualTo(120);
            assertThat(config.isRecallEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Player State Features")
    class PlayerStateDefaults {

        @Test
        @DisplayName("Should have fly enabled by default")
        void shouldHaveFlyEnabled() {
            assertThat(config.isFlyEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have heal enabled by default")
        void shouldHaveHealEnabled() {
            assertThat(config.isHealEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have speed settings")
        void shouldHaveSpeedSettings() {
            assertThat(config.isSpeedEnabled()).isTrue();
            assertThat(config.getSpeedMaxSpeed()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have gamemode enabled by default")
        void shouldHaveGamemodeEnabled() {
            assertThat(config.isGamemodeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have hide enabled by default")
        void shouldHaveHideEnabled() {
            assertThat(config.isHideEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update player state settings")
        void shouldUpdatePlayerStateSettings() {
            config.setFlyEnabled(false);
            config.setHealEnabled(false);
            config.setSpeedEnabled(false);
            config.setSpeedMaxSpeed(5);
            config.setGamemodeEnabled(false);
            config.setHideEnabled(false);

            assertThat(config.isFlyEnabled()).isFalse();
            assertThat(config.isHealEnabled()).isFalse();
            assertThat(config.isSpeedEnabled()).isFalse();
            assertThat(config.getSpeedMaxSpeed()).isEqualTo(5);
            assertThat(config.isGamemodeEnabled()).isFalse();
            assertThat(config.isHideEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Management Features")
    class ManagementDefaults {

        @Test
        @DisplayName("Should have invsee enabled by default")
        void shouldHaveInvseeEnabled() {
            assertThat(config.isInvseeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have whitelist enabled by default")
        void shouldHaveWhitelistEnabled() {
            assertThat(config.isWhitelistEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update management settings")
        void shouldUpdateManagementSettings() {
            config.setInvseeEnabled(false);
            config.setWhitelistEnabled(false);

            assertThat(config.isInvseeEnabled()).isFalse();
            assertThat(config.isWhitelistEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Home System Defaults")
    class HomeDefaults {

        @Test
        @DisplayName("Should have home enabled by default")
        void shouldHaveHomeEnabled() {
            assertThat(config.isHomeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct home defaults")
        void shouldHaveCorrectHomeDefaults() {
            assertThat(config.getHomeDefaultMaxHomes()).isEqualTo(3);
            assertThat(config.getHomeTeleportWarmup()).isEqualTo(3);
            assertThat(config.isHomeCancelOnMove()).isTrue();
        }

        @Test
        @DisplayName("Should update home settings")
        void shouldUpdateHomeSettings() {
            config.setHomeEnabled(false);
            config.setHomeDefaultMaxHomes(5);
            config.setHomeTeleportWarmup(5);
            config.setHomeCancelOnMove(false);

            assertThat(config.isHomeEnabled()).isFalse();
            assertThat(config.getHomeDefaultMaxHomes()).isEqualTo(5);
            assertThat(config.getHomeTeleportWarmup()).isEqualTo(5);
            assertThat(config.isHomeCancelOnMove()).isFalse();
        }
    }

    @Nested
    @DisplayName("TPA System Defaults")
    class TpaDefaults {

        @Test
        @DisplayName("Should have TPA enabled by default")
        void shouldHaveTpaEnabled() {
            assertThat(config.isTpaEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct TPA defaults")
        void shouldHaveTpaDefaults() {
            assertThat(config.getTpaTimeout()).isEqualTo(30);
            assertThat(config.getTpaCooldown()).isEqualTo(10);
            assertThat(config.isTpaAllowCrossWorld()).isTrue();
        }

        @Test
        @DisplayName("Should update TPA settings")
        void shouldUpdateTpaSettings() {
            config.setTpaEnabled(false);
            config.setTpaTimeout(60);
            config.setTpaCooldown(20);
            config.setTpaAllowCrossWorld(false);

            assertThat(config.isTpaEnabled()).isFalse();
            assertThat(config.getTpaTimeout()).isEqualTo(60);
            assertThat(config.getTpaCooldown()).isEqualTo(20);
            assertThat(config.isTpaAllowCrossWorld()).isFalse();
        }
    }

    @Nested
    @DisplayName("Warp System Defaults")
    class WarpDefaults {

        @Test
        @DisplayName("Should have warp enabled by default")
        void shouldHaveWarpEnabled() {
            assertThat(config.isWarpEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have warp warmup default")
        void shouldHaveWarpWarmup() {
            assertThat(config.getWarpTeleportWarmup()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should update warp settings")
        void shouldUpdateWarpSettings() {
            config.setWarpEnabled(false);
            config.setWarpTeleportWarmup(5);

            assertThat(config.isWarpEnabled()).isFalse();
            assertThat(config.getWarpTeleportWarmup()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Ban System Defaults")
    class BanDefaults {

        @Test
        @DisplayName("Should have ban enabled by default")
        void shouldHaveBanEnabled() {
            assertThat(config.isBanEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have ban broadcast defaults")
        void shouldHaveBanBroadcastDefaults() {
            assertThat(config.isBanBroadcast()).isTrue();
            assertThat(config.isUnbanBroadcast()).isTrue();
        }

        @Test
        @DisplayName("Should update ban settings")
        void shouldUpdateBanSettings() {
            config.setBanEnabled(false);
            config.setBanBroadcast(false);
            config.setUnbanBroadcast(false);

            assertThat(config.isBanEnabled()).isFalse();
            assertThat(config.isBanBroadcast()).isFalse();
            assertThat(config.isUnbanBroadcast()).isFalse();
        }
    }

    @Nested
    @DisplayName("Scoreboard Defaults")
    class ScoreboardDefaults {

        @Test
        @DisplayName("Should have scoreboard enabled by default")
        void shouldHaveScoreboardEnabled() {
            assertThat(config.isScoreboardEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have scoreboard auto-enable by default")
        void shouldHaveAutoEnable() {
            assertThat(config.isScoreboardAutoEnable()).isTrue();
        }

        @Test
        @DisplayName("Should have correct scoreboard defaults")
        void shouldHaveScoreboardDefaults() {
            assertThat(config.getScoreboardUpdateInterval()).isEqualTo(1);
            assertThat(config.getScoreboardTitle()).contains("服务器信息");
            assertThat(config.getScoreboardLines()).isNotEmpty();
            assertThat(config.getScoreboardLines()).hasSizeGreaterThan(5);
        }

        @Test
        @DisplayName("Should update scoreboard settings")
        void shouldUpdateScoreboardSettings() {
            config.setScoreboardEnabled(false);
            config.setScoreboardAutoEnable(false);
            config.setScoreboardUpdateInterval(5);
            config.setScoreboardTitle("Custom");
            List<String> newLines = Arrays.asList("Line1", "Line2");
            config.setScoreboardLines(newLines);

            assertThat(config.isScoreboardEnabled()).isFalse();
            assertThat(config.isScoreboardAutoEnable()).isFalse();
            assertThat(config.getScoreboardUpdateInterval()).isEqualTo(5);
            assertThat(config.getScoreboardTitle()).isEqualTo("Custom");
            assertThat(config.getScoreboardLines()).containsExactly("Line1", "Line2");
        }
    }

    @Nested
    @DisplayName("NamePrefix Defaults")
    class NamePrefixDefaults {

        @Test
        @DisplayName("Should have name prefix disabled by default")
        void shouldHaveNamePrefixDisabled() {
            assertThat(config.isNamePrefixEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should have correct name prefix defaults")
        void shouldHaveNamePrefixDefaults() {
            assertThat(config.getNamePrefixFormat()).isNotNull();
            assertThat(config.getNameSuffixFormat()).isNotNull();
            assertThat(config.getNamePrefixUpdateInterval()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should update name prefix settings")
        void shouldUpdateNamePrefixSettings() {
            config.setNamePrefixEnabled(true);
            config.setNamePrefixFormat("&a[VIP] ");
            config.setNameSuffixFormat(" &7*");
            config.setNamePrefixUpdateInterval(10);

            assertThat(config.isNamePrefixEnabled()).isTrue();
            assertThat(config.getNamePrefixFormat()).isEqualTo("&a[VIP] ");
            assertThat(config.getNameSuffixFormat()).isEqualTo(" &7*");
            assertThat(config.getNamePrefixUpdateInterval()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Listener Feature Defaults")
    class ListenerDefaults {

        @Test
        @DisplayName("Should have MOTD enabled by default")
        void shouldHaveMotdEnabled() {
            assertThat(config.isMotdEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have tab bar enabled by default")
        void shouldHaveTabBarEnabled() {
            assertThat(config.isTabBarEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should update listener settings")
        void shouldUpdateListenerSettings() {
            config.setMotdEnabled(false);
            config.setTabBarEnabled(false);

            assertThat(config.isMotdEnabled()).isFalse();
            assertThat(config.isTabBarEnabled()).isFalse();
        }
    }
}
