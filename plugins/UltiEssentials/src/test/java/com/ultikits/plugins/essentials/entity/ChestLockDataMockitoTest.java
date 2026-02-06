package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito-based unit tests for ChestLockData entity.
 * <p>
 * 纯单元测试箱子锁实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChestLockData Entity Tests (Mockito)")
class ChestLockDataMockitoTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            UUID uuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            ChestLockData data = ChestLockData.builder()
                .uuid(uuid)
                .world("world")
                .x(100)
                .y(64)
                .z(-200)
                .ownerUuid(ownerUuid.toString())
                .ownerName("TestPlayer")
                .createdAt(now)
                .build();

            assertThat(data.getUuid()).isEqualTo(uuid);
            assertThat(data.getWorld()).isEqualTo("world");
            assertThat(data.getX()).isEqualTo(100);
            assertThat(data.getY()).isEqualTo(64);
            assertThat(data.getZ()).isEqualTo(-200);
            assertThat(data.getOwnerUuid()).isEqualTo(ownerUuid.toString());
            assertThat(data.getOwnerName()).isEqualTo("TestPlayer");
            assertThat(data.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build with no-args constructor")
        void shouldBuildWithNoArgsConstructor() {
            ChestLockData data = new ChestLockData();
            assertThat(data.getWorld()).isNull();
            assertThat(data.getX()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Location Key Tests")
    class LocationKeyTests {

        @Test
        @DisplayName("Should generate correct location key")
        void shouldGenerateCorrectLocationKey() {
            ChestLockData data = ChestLockData.builder()
                .world("world")
                .x(100)
                .y(64)
                .z(-200)
                .build();

            assertThat(data.getLocationKey()).isEqualTo("world:100:64:-200");
        }

        @Test
        @DisplayName("Should generate correct static location key")
        void shouldGenerateCorrectStaticLocationKey() {
            String key = ChestLockData.createLocationKey("nether", 50, 32, 100);
            assertThat(key).isEqualTo("nether:50:32:100");
        }

        @Test
        @DisplayName("Should handle negative coordinates in location key")
        void shouldHandleNegativeCoordinates() {
            ChestLockData data = ChestLockData.builder()
                .world("world")
                .x(-100)
                .y(64)
                .z(-200)
                .build();

            assertThat(data.getLocationKey()).isEqualTo("world:-100:64:-200");
        }

        @Test
        @DisplayName("Static and instance location keys should match")
        void staticAndInstanceKeysShouldMatch() {
            ChestLockData data = ChestLockData.builder()
                .world("end")
                .x(0)
                .y(100)
                .z(0)
                .build();

            assertThat(data.getLocationKey())
                .isEqualTo(ChestLockData.createLocationKey("end", 0, 100, 0));
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should update fields via setters")
        void shouldUpdateFieldsViaSetters() {
            ChestLockData data = new ChestLockData();

            UUID uuid = UUID.randomUUID();
            data.setUuid(uuid);
            data.setWorld("nether");
            data.setX(50);
            data.setY(32);
            data.setZ(100);
            data.setOwnerUuid("test-uuid");
            data.setOwnerName("Player");
            data.setCreatedAt(12345L);

            assertThat(data.getUuid()).isEqualTo(uuid);
            assertThat(data.getWorld()).isEqualTo("nether");
            assertThat(data.getX()).isEqualTo(50);
            assertThat(data.getY()).isEqualTo(32);
            assertThat(data.getZ()).isEqualTo(100);
            assertThat(data.getOwnerUuid()).isEqualTo("test-uuid");
            assertThat(data.getOwnerName()).isEqualTo("Player");
            assertThat(data.getCreatedAt()).isEqualTo(12345L);
        }
    }
}
