package com.ultikits.plugins.essentials.config;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for MotdConfig, TabBarConfig, LobbyConfig, SpawnConfig.
 * <p>
 * 测试其他配置类的默认值和设置器。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("Other Config Classes Tests")
class OtherConfigsTest {

    @Nested
    @DisplayName("MotdConfig")
    class MotdConfigTests {

        private MotdConfig config;

        @BeforeEach
        void setUp() {
            config = new MotdConfig();
        }

        @Test
        @DisplayName("Should have default line1")
        void shouldHaveDefaultLine1() {
            assertThat(config.getLine1()).isEqualTo("&6Welcome to our server!");
        }

        @Test
        @DisplayName("Should have default line2")
        void shouldHaveDefaultLine2() {
            assertThat(config.getLine2()).isEqualTo("&7Powered by UltiTools");
        }

        @Test
        @DisplayName("Should have default maxPlayers as -1")
        void shouldHaveDefaultMaxPlayers() {
            assertThat(config.getMaxPlayers()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Should update line1 via setter")
        void shouldUpdateLine1() {
            config.setLine1("&aCustom MOTD");
            assertThat(config.getLine1()).isEqualTo("&aCustom MOTD");
        }

        @Test
        @DisplayName("Should update line2 via setter")
        void shouldUpdateLine2() {
            config.setLine2("&bCustom line 2");
            assertThat(config.getLine2()).isEqualTo("&bCustom line 2");
        }

        @Test
        @DisplayName("Should update maxPlayers via setter")
        void shouldUpdateMaxPlayers() {
            config.setMaxPlayers(100);
            assertThat(config.getMaxPlayers()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("TabBarConfig")
    class TabBarConfigTests {

        private TabBarConfig config;

        @BeforeEach
        void setUp() {
            config = new TabBarConfig();
        }

        @Test
        @DisplayName("Should have default header")
        void shouldHaveDefaultHeader() {
            assertThat(config.getHeader()).isNotNull();
            assertThat(config.getHeader()).isNotEmpty();
        }

        @Test
        @DisplayName("Should have default footer")
        void shouldHaveDefaultFooter() {
            assertThat(config.getFooter()).isNotNull();
            assertThat(config.getFooter()).isNotEmpty();
        }

        @Test
        @DisplayName("Should update header via setter")
        void shouldUpdateHeader() {
            config.setHeader("&6Custom Header");
            assertThat(config.getHeader()).isEqualTo("&6Custom Header");
        }

        @Test
        @DisplayName("Should update footer via setter")
        void shouldUpdateFooter() {
            config.setFooter("&7Custom Footer");
            assertThat(config.getFooter()).isEqualTo("&7Custom Footer");
        }
    }

    @Nested
    @DisplayName("LobbyConfig")
    class LobbyConfigTests {

        private LobbyConfig config;

        @BeforeEach
        void setUp() {
            config = new LobbyConfig();
        }

        @Test
        @DisplayName("Should have default world")
        void shouldHaveDefaultWorld() {
            assertThat(config.getWorld()).isEqualTo("world");
        }

        @Test
        @DisplayName("Should have default coordinates")
        void shouldHaveDefaultCoordinates() {
            assertThat(config.getX()).isEqualTo(0.0);
            assertThat(config.getY()).isEqualTo(64.0);
            assertThat(config.getZ()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should have default rotation")
        void shouldHaveDefaultRotation() {
            assertThat(config.getYaw()).isEqualTo(0.0f);
            assertThat(config.getPitch()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Should update world via setter")
        void shouldUpdateWorld() {
            config.setWorld("lobby");
            assertThat(config.getWorld()).isEqualTo("lobby");
        }

        @Test
        @DisplayName("Should update coordinates via setters")
        void shouldUpdateCoordinates() {
            config.setX(100.5);
            config.setY(80.0);
            config.setZ(-50.5);
            assertThat(config.getX()).isEqualTo(100.5);
            assertThat(config.getY()).isEqualTo(80.0);
            assertThat(config.getZ()).isEqualTo(-50.5);
        }
    }

    @Nested
    @DisplayName("SpawnConfig")
    class SpawnConfigTests {

        private SpawnConfig config;

        @BeforeEach
        void setUp() {
            config = new SpawnConfig();
        }

        @Test
        @DisplayName("Should have default world")
        void shouldHaveDefaultWorld() {
            assertThat(config.getWorld()).isEqualTo("world");
        }

        @Test
        @DisplayName("Should have default coordinates")
        void shouldHaveDefaultCoordinates() {
            assertThat(config.getX()).isEqualTo(0.0);
            assertThat(config.getY()).isEqualTo(64.0);
            assertThat(config.getZ()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should have teleport on first join enabled by default")
        void shouldHaveTeleportOnFirstJoin() {
            assertThat(config.isTeleportOnFirstJoin()).isTrue();
        }

        @Test
        @DisplayName("Should have teleport on respawn enabled by default")
        void shouldHaveTeleportOnRespawn() {
            assertThat(config.isTeleportOnRespawn()).isTrue();
        }

        @Test
        @DisplayName("Should update teleport on first join via setter")
        void shouldUpdateTeleportOnFirstJoin() {
            config.setTeleportOnFirstJoin(false);
            assertThat(config.isTeleportOnFirstJoin()).isFalse();
        }

        @Test
        @DisplayName("Should update teleport on respawn via setter")
        void shouldUpdateTeleportOnRespawn() {
            config.setTeleportOnRespawn(false);
            assertThat(config.isTeleportOnRespawn()).isFalse();
        }

        @Test
        @DisplayName("Should update all coordinates")
        void shouldUpdateAllCoordinates() {
            config.setWorld("spawn_world");
            config.setX(50.0);
            config.setY(100.0);
            config.setZ(-50.0);
            config.setYaw(180.0f);
            config.setPitch(-10.0f);

            assertThat(config.getWorld()).isEqualTo("spawn_world");
            assertThat(config.getX()).isEqualTo(50.0);
            assertThat(config.getY()).isEqualTo(100.0);
            assertThat(config.getZ()).isEqualTo(-50.0);
            assertThat(config.getYaw()).isEqualTo(180.0f);
            assertThat(config.getPitch()).isEqualTo(-10.0f);
        }
    }
}
