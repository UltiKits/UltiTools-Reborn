package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for WarpData entity.
 * <p>
 * 纯单元测试地标实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("WarpData Entity Tests (Mockito)")
class WarpDataMockitoTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            UUID uuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            WarpData warp = WarpData.builder()
                .uuid(uuid)
                .name("spawn")
                .world("world")
                .x(0.0)
                .y(64.0)
                .z(0.0)
                .yaw(0.0f)
                .pitch(0.0f)
                .permission("warp.spawn")
                .createdBy("admin-uuid")
                .createdAt(now)
                .build();

            assertThat(warp.getUuid()).isEqualTo(uuid);
            assertThat(warp.getName()).isEqualTo("spawn");
            assertThat(warp.getWorld()).isEqualTo("world");
            assertThat(warp.getX()).isEqualTo(0.0);
            assertThat(warp.getY()).isEqualTo(64.0);
            assertThat(warp.getZ()).isEqualTo(0.0);
            assertThat(warp.getPermission()).isEqualTo("warp.spawn");
            assertThat(warp.getCreatedBy()).isEqualTo("admin-uuid");
            assertThat(warp.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            WarpData warp = new WarpData();
            assertThat(warp.getName()).isNull();
            assertThat(warp.getPermission()).isNull();
        }

        @Test
        @DisplayName("Should build without optional permission")
        void shouldBuildWithoutPermission() {
            WarpData warp = WarpData.builder()
                .name("public-warp")
                .permission(null)
                .build();

            assertThat(warp.getPermission()).isNull();
        }
    }

    @Nested
    @DisplayName("ID Methods Tests")
    class IdMethodTests {

        @Test
        @DisplayName("getId should return uuid")
        void getIdShouldReturnUuid() {
            UUID uuid = UUID.randomUUID();
            WarpData warp = WarpData.builder().uuid(uuid).build();
            assertThat(warp.getId()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("setId should update uuid")
        void setIdShouldUpdateUuid() {
            WarpData warp = new WarpData();
            UUID uuid = UUID.randomUUID();
            warp.setId(uuid);
            assertThat(warp.getUuid()).isEqualTo(uuid);
            assertThat(warp.getId()).isEqualTo(uuid);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update all fields via setters")
        void shouldUpdateAllFieldsViaSetters() {
            WarpData warp = new WarpData();

            warp.setName("market");
            warp.setPermission("warp.market");
            warp.setCreatedBy("admin-uuid");
            warp.setCreatedAt(12345L);
            warp.setWorld("world_nether");
            warp.setX(100.0);
            warp.setY(32.0);
            warp.setZ(-100.0);

            assertThat(warp.getName()).isEqualTo("market");
            assertThat(warp.getPermission()).isEqualTo("warp.market");
            assertThat(warp.getCreatedBy()).isEqualTo("admin-uuid");
            assertThat(warp.getCreatedAt()).isEqualTo(12345L);
            assertThat(warp.getWorld()).isEqualTo("world_nether");
            assertThat(warp.getX()).isEqualTo(100.0);
            assertThat(warp.getY()).isEqualTo(32.0);
            assertThat(warp.getZ()).isEqualTo(-100.0);
        }
    }
}
