package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for HomeData entity.
 * <p>
 * 纯单元测试家位置实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("HomeData Entity Tests (Mockito)")
class HomeDataMockitoTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            UUID uuid = UUID.randomUUID();
            UUID playerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            HomeData home = HomeData.builder()
                .uuid(uuid)
                .playerUuid(playerUuid.toString())
                .name("home")
                .world("world")
                .x(100.5)
                .y(64.0)
                .z(-200.5)
                .yaw(90.0f)
                .pitch(0.0f)
                .createdAt(now)
                .build();

            assertThat(home.getUuid()).isEqualTo(uuid);
            assertThat(home.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(home.getName()).isEqualTo("home");
            assertThat(home.getWorld()).isEqualTo("world");
            assertThat(home.getX()).isEqualTo(100.5);
            assertThat(home.getY()).isEqualTo(64.0);
            assertThat(home.getZ()).isEqualTo(-200.5);
            assertThat(home.getYaw()).isEqualTo(90.0f);
            assertThat(home.getPitch()).isEqualTo(0.0f);
            assertThat(home.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            HomeData home = new HomeData();
            assertThat(home.getName()).isNull();
            assertThat(home.getX()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("ID Methods Tests")
    class IdMethodTests {

        @Test
        @DisplayName("getId should return uuid")
        void getIdShouldReturnUuid() {
            UUID uuid = UUID.randomUUID();
            HomeData home = HomeData.builder().uuid(uuid).build();
            assertThat(home.getId()).isEqualTo(uuid);
        }

        @Test
        @DisplayName("setId should update uuid")
        void setIdShouldUpdateUuid() {
            HomeData home = new HomeData();
            UUID uuid = UUID.randomUUID();
            home.setId(uuid);
            assertThat(home.getUuid()).isEqualTo(uuid);
            assertThat(home.getId()).isEqualTo(uuid);
        }
    }

    @Nested
    @DisplayName("Location Fields Tests")
    class LocationFieldsTests {

        @Test
        @DisplayName("Should inherit location fields from LocationDataEntity")
        void shouldInheritLocationFields() {
            HomeData home = HomeData.builder()
                .world("nether")
                .x(50.0)
                .y(32.0)
                .z(100.0)
                .yaw(180.0f)
                .pitch(-45.0f)
                .build();

            assertThat(home.getWorld()).isEqualTo("nether");
            assertThat(home.getX()).isEqualTo(50.0);
            assertThat(home.getY()).isEqualTo(32.0);
            assertThat(home.getZ()).isEqualTo(100.0);
            assertThat(home.getYaw()).isEqualTo(180.0f);
            assertThat(home.getPitch()).isEqualTo(-45.0f);
        }

        @Test
        @DisplayName("Should update location fields via setters")
        void shouldUpdateLocationFieldsViaSetters() {
            HomeData home = new HomeData();
            home.setWorld("end");
            home.setX(0.0);
            home.setY(100.0);
            home.setZ(0.0);
            home.setYaw(0.0f);
            home.setPitch(0.0f);

            assertThat(home.getWorld()).isEqualTo("end");
            assertThat(home.getX()).isEqualTo(0.0);
            assertThat(home.getY()).isEqualTo(100.0);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update name via setter")
        void shouldUpdateName() {
            HomeData home = HomeData.builder().name("home1").build();
            home.setName("base");
            assertThat(home.getName()).isEqualTo("base");
        }

        @Test
        @DisplayName("Should update playerUuid via setter")
        void shouldUpdatePlayerUuid() {
            HomeData home = new HomeData();
            home.setPlayerUuid("new-uuid");
            assertThat(home.getPlayerUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update createdAt via setter")
        void shouldUpdateCreatedAt() {
            HomeData home = new HomeData();
            home.setCreatedAt(12345L);
            assertThat(home.getCreatedAt()).isEqualTo(12345L);
        }
    }
}
