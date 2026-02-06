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
 * Unit tests for WarpData entity.
 * <p>
 * 测试地标点实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("WarpData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class WarpDataTest {

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
        @DisplayName("Should build warp data with all fields")
        void shouldBuildWithAllFields() {
            UUID creatorUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0.0)
                .y(64.0)
                .z(0.0)
                .yaw(0.0f)
                .pitch(0.0f)
                .permission("ultiessentials.warp.spawn")
                .createdBy(creatorUuid.toString())
                .createdAt(now)
                .build();

            assertThat(warp.getName()).isEqualTo("spawn");
            assertThat(warp.getWorld()).isEqualTo("world");
            assertThat(warp.getPermission()).isEqualTo("ultiessentials.warp.spawn");
            assertThat(warp.getCreatedBy()).isEqualTo(creatorUuid.toString());
            assertThat(warp.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build warp with null permission")
        void shouldBuildWarpWithNullPermission() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("public")
                .world("world")
                .x(0)
                .y(64)
                .z(0)
                .permission(null)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            assertThat(warp.getPermission()).isNull();
        }
    }

    @Nested
    @DisplayName("Location Conversion Tests")
    class LocationConversionTests {

        @Test
        @DisplayName("Should convert to location successfully")
        void shouldConvertToLocation() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("world")
                .x(0.0)
                .y(64.0)
                .z(0.0)
                .yaw(0.0f)
                .pitch(0.0f)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            Location loc = warp.toLocation();

            assertThat(loc).isNotNull();
            assertThat(loc.getWorld().getName()).isEqualTo("world");
            assertThat(loc.getX()).isEqualTo(0.0);
            assertThat(loc.getY()).isEqualTo(64.0);
            assertThat(loc.getZ()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should return null when world not exists")
        void shouldReturnNullWhenWorldNotExists() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("nonexistent")
                .x(0)
                .y(64)
                .z(0)
                .createdBy(UUID.randomUUID().toString())
                .createdAt(System.currentTimeMillis())
                .build();

            Location loc = warp.toLocation();

            assertThat(loc).isNull();
        }
    }
}
