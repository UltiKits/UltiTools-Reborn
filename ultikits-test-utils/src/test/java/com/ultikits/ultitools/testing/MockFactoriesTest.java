package com.ultikits.ultitools.testing;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MockFactories Tests")
class MockFactoriesTest {

    @Nested
    @DisplayName("createMockPlayer(String)")
    class CreateMockPlayerTests {

        @Test
        @DisplayName("creates player with correct name and derived UUID")
        void createsPlayerWithNameAndUuid() {
            Player player = MockFactories.createMockPlayer("TestPlayer");
            assertThat(player.getName()).isEqualTo("TestPlayer");
            assertThat(player.getUniqueId()).isEqualTo(UUID.nameUUIDFromBytes("TestPlayer".getBytes()));
        }

        @Test
        @DisplayName("player is online by default")
        void playerIsOnline() {
            Player player = MockFactories.createMockPlayer("TestPlayer");
            assertThat(player.isOnline()).isTrue();
        }

        @Test
        @DisplayName("player has permission by default")
        void playerHasPermission() {
            Player player = MockFactories.createMockPlayer("TestPlayer");
            assertThat(player.hasPermission("some.perm")).isTrue();
        }

        @Test
        @DisplayName("player has location in default world")
        void playerHasLocation() {
            Player player = MockFactories.createMockPlayer("TestPlayer");
            assertThat(player.getLocation()).isNotNull();
            assertThat(player.getWorld()).isNotNull();
            assertThat(player.getWorld().getName()).isEqualTo("world");
        }

        @Test
        @DisplayName("two players with different names have different UUIDs")
        void differentPlayersHaveDifferentUuids() {
            Player player1 = MockFactories.createMockPlayer("Alice");
            Player player2 = MockFactories.createMockPlayer("Bob");
            assertThat(player1.getUniqueId()).isNotEqualTo(player2.getUniqueId());
        }
    }

    @Nested
    @DisplayName("createMockPlayer(String, UUID)")
    class CreateMockPlayerWithUuidTests {

        @Test
        @DisplayName("uses the provided UUID")
        void usesProvidedUuid() {
            UUID customUuid = UUID.randomUUID();
            Player player = MockFactories.createMockPlayer("TestPlayer", customUuid);
            assertThat(player.getUniqueId()).isEqualTo(customUuid);
            assertThat(player.getName()).isEqualTo("TestPlayer");
        }
    }

    @Nested
    @DisplayName("createMockWorld")
    class CreateMockWorldTests {

        @Test
        @DisplayName("creates world with correct name")
        void createsWorldWithName() {
            World world = MockFactories.createMockWorld("nether");
            assertThat(world.getName()).isEqualTo("nether");
        }
    }

    @Nested
    @DisplayName("createMockServer")
    class CreateMockServerTests {

        @Test
        @DisplayName("creates server with default name")
        void createsServerWithName() {
            Server server = MockFactories.createMockServer();
            assertThat(server.getName()).isEqualTo("TestServer");
        }
    }
}
