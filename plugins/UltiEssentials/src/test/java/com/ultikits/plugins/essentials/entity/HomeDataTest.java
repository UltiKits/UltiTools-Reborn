package com.ultikits.plugins.essentials.entity;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import com.ultikits.plugins.essentials.utils.MockBukkitHelper;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for HomeData entity.
 * <p>
 * 测试家位置实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("HomeData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class HomeDataTest {

    private World world;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build home data with all fields")
        void shouldBuildWithAllFields() {
            UUID playerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(playerUuid.toString())
                .name("home1")
                .world("world")
                .x(100.5)
                .y(64.0)
                .z(-200.3)
                .yaw(90.0f)
                .pitch(0.0f)
                .createdAt(now)
                .build();

            assertThat(home.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(home.getName()).isEqualTo("home1");
            assertThat(home.getWorld()).isEqualTo("world");
            assertThat(home.getX()).isEqualTo(100.5);
            assertThat(home.getY()).isEqualTo(64.0);
            assertThat(home.getZ()).isEqualTo(-200.3);
            assertThat(home.getYaw()).isEqualTo(90.0f);
            assertThat(home.getPitch()).isEqualTo(0.0f);
            assertThat(home.getCreatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("Location Conversion Tests")
    class LocationConversionTests {

        @Test
        @DisplayName("Should convert to location successfully")
        void shouldConvertToLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .name("home1")
                .world("world")
                .x(100.5)
                .y(64.0)
                .z(-200.3)
                .yaw(90.0f)
                .pitch(0.0f)
                .createdAt(System.currentTimeMillis())
                .build();

            Location loc = home.toLocation();

            assertThat(loc).isNotNull();
            assertThat(loc.getWorld().getName()).isEqualTo("world");
            assertThat(loc.getX()).isEqualTo(100.5);
            assertThat(loc.getY()).isEqualTo(64.0);
            assertThat(loc.getZ()).isEqualTo(-200.3);
            assertThat(loc.getYaw()).isEqualTo(90.0f);
            assertThat(loc.getPitch()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("Should return null when world not exists")
        void shouldReturnNullWhenWorldNotExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .name("home1")
                .world("nonexistent_world")
                .x(0)
                .y(64)
                .z(0)
                .createdAt(System.currentTimeMillis())
                .build();

            Location loc = home.toLocation();

            assertThat(loc).isNull();
        }
    }

    @Nested
    @DisplayName("World Validity Tests")
    class WorldValidityTests {

        @Test
        @DisplayName("Should return true when world exists")
        void shouldReturnTrueWhenWorldExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("world")
                .createdAt(System.currentTimeMillis())
                .build();

            assertThat(home.isWorldValid()).isTrue();
        }

        @Test
        @DisplayName("Should return false when world not exists")
        void shouldReturnFalseWhenWorldNotExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("nonexistent")
                .createdAt(System.currentTimeMillis())
                .build();

            assertThat(home.isWorldValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("From Location Tests")
    class FromLocationTests {

        @Test
        @DisplayName("Should update from location successfully")
        void shouldUpdateFromLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .name("home1")
                .world("old_world")
                .x(0)
                .y(0)
                .z(0)
                .createdAt(System.currentTimeMillis())
                .build();

            Location newLoc = new Location(world, 50.5, 100.0, -30.2, 45.0f, -10.0f);
            home.fromLocation(newLoc);

            assertThat(home.getWorld()).isEqualTo("world");
            assertThat(home.getX()).isEqualTo(50.5);
            assertThat(home.getY()).isEqualTo(100.0);
            assertThat(home.getZ()).isEqualTo(-30.2);
            assertThat(home.getYaw()).isEqualTo(45.0f);
            assertThat(home.getPitch()).isEqualTo(-10.0f);
        }

        @Test
        @DisplayName("Should not modify fields with null location")
        void shouldNotModifyFieldsWithNullLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid(UUID.randomUUID().toString())
                .name("home1")
                .world("original_world")
                .x(100)
                .y(64)
                .z(200)
                .createdAt(System.currentTimeMillis())
                .build();

            home.fromLocation(null);

            assertThat(home.getWorld()).isEqualTo("original_world");
            assertThat(home.getX()).isEqualTo(100);
            assertThat(home.getY()).isEqualTo(64);
            assertThat(home.getZ()).isEqualTo(200);
        }
    }
}
