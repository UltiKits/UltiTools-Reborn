package com.ultikits.plugins.essentials.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EssentialsConfig.
 * <p>
 * 测试配置类的默认值和设置器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("EssentialsConfig Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EssentialsConfigTest {

    private EssentialsConfig config;

    @BeforeEach
    void setUp() {
        config = new EssentialsConfig();
    }

    @Nested
    @DisplayName("Teleport Feature Defaults")
    class TeleportFeatureDefaults {

        @Test
        @DisplayName("Should have correct back feature defaults")
        void shouldHaveBackDefaults() {
            assertThat(config.isBackEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct spawn feature defaults")
        void shouldHaveSpawnDefaults() {
            assertThat(config.isSpawnEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct lobby feature defaults")
        void shouldHaveLobbyDefaults() {
            assertThat(config.isLobbyEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct wild feature defaults")
        void shouldHaveWildDefaults() {
            assertThat(config.isWildEnabled()).isTrue();
            assertThat(config.getWildMaxRange()).isEqualTo(10000);
            assertThat(config.getWildMinRange()).isEqualTo(100);
            assertThat(config.getWildCooldown()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should have correct recall feature defaults")
        void shouldHaveRecallDefaults() {
            assertThat(config.isRecallEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Player Status Feature Defaults")
    class PlayerStatusDefaults {

        @Test
        @DisplayName("Should have correct fly feature defaults")
        void shouldHaveFlyDefaults() {
            assertThat(config.isFlyEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct heal feature defaults")
        void shouldHaveHealDefaults() {
            assertThat(config.isHealEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct speed feature defaults")
        void shouldHaveSpeedDefaults() {
            assertThat(config.isSpeedEnabled()).isTrue();
            assertThat(config.getSpeedMaxSpeed()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have correct gamemode feature defaults")
        void shouldHaveGamemodeDefaults() {
            assertThat(config.isGamemodeEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have correct hide feature defaults")
        void shouldHaveHideDefaults() {
            assertThat(config.isHideEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Home System Defaults")
    class HomeSystemDefaults {

        @Test
        @DisplayName("Should have correct home feature defaults")
        void shouldHaveHomeDefaults() {
            assertThat(config.isHomeEnabled()).isTrue();
            assertThat(config.getHomeDefaultMaxHomes()).isEqualTo(3);
            assertThat(config.getHomeTeleportWarmup()).isEqualTo(3);
            assertThat(config.isHomeCancelOnMove()).isTrue();
        }
    }

    @Nested
    @DisplayName("TPA System Defaults")
    class TpaSystemDefaults {

        @Test
        @DisplayName("Should have correct TPA defaults")
        void shouldHaveTpaDefaults() {
            assertThat(config.isTpaEnabled()).isTrue();
            assertThat(config.getTpaTimeout()).isEqualTo(30);
            assertThat(config.getTpaCooldown()).isEqualTo(10);
            assertThat(config.isTpaAllowCrossWorld()).isTrue();
        }
    }

    @Nested
    @DisplayName("Warp System Defaults")
    class WarpSystemDefaults {

        @Test
        @DisplayName("Should have correct warp defaults")
        void shouldHaveWarpDefaults() {
            assertThat(config.isWarpEnabled()).isTrue();
            assertThat(config.getWarpTeleportWarmup()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Ban System Defaults")
    class BanSystemDefaults {

        @Test
        @DisplayName("Should have correct ban defaults")
        void shouldHaveBanDefaults() {
            assertThat(config.isBanEnabled()).isTrue();
            assertThat(config.isBanBroadcast()).isTrue();
            assertThat(config.isUnbanBroadcast()).isTrue();
        }
    }

    @Nested
    @DisplayName("Scoreboard System Defaults")
    class ScoreboardSystemDefaults {

        @Test
        @DisplayName("Should have correct scoreboard defaults")
        void shouldHaveScoreboardDefaults() {
            assertThat(config.isScoreboardEnabled()).isTrue();
            assertThat(config.isScoreboardAutoEnable()).isTrue();
            assertThat(config.getScoreboardUpdateInterval()).isEqualTo(1);
            assertThat(config.getScoreboardTitle()).isNotEmpty();
            assertThat(config.getScoreboardLines()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("ChestLock System Defaults")
    class ChestLockSystemDefaults {

        @Test
        @DisplayName("Should have correct chest lock defaults")
        void shouldHaveChestLockDefaults() {
            assertThat(config.isChestLockEnabled()).isTrue();
            assertThat(config.isChestLockAdminBypass()).isTrue();
        }
    }

    @Nested
    @DisplayName("NamePrefix System Defaults")
    class NamePrefixSystemDefaults {

        @Test
        @DisplayName("Should have correct name prefix defaults")
        void shouldHaveNamePrefixDefaults() {
            assertThat(config.isNamePrefixEnabled()).isFalse();
            assertThat(config.getNamePrefixFormat()).isNotEmpty();
            assertThat(config.getNameSuffixFormat()).isEmpty();
            assertThat(config.getNamePrefixUpdateInterval()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update home enabled setting")
        void shouldUpdateHomeEnabled() {
            config.setHomeEnabled(false);
            assertThat(config.isHomeEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update home max homes setting")
        void shouldUpdateHomeMaxHomes() {
            config.setHomeDefaultMaxHomes(10);
            assertThat(config.getHomeDefaultMaxHomes()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should update TPA timeout setting")
        void shouldUpdateTpaTimeout() {
            config.setTpaTimeout(60);
            assertThat(config.getTpaTimeout()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should update scoreboard enabled setting")
        void shouldUpdateScoreboardEnabled() {
            config.setScoreboardEnabled(false);
            assertThat(config.isScoreboardEnabled()).isFalse();
        }
    }
}
