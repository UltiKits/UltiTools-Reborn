package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ChestLockData entity.
 * <p>
 * 测试箱子锁实体类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("ChestLockData Entity Tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class ChestLockDataTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build chest lock data with all fields")
        void shouldBuildWithAllFields() {
            UUID ownerUuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            ChestLockData lock = ChestLockData.builder()
                .uuid(UUID.randomUUID())
                .world("world")
                .x(100)
                .y(64)
                .z(-200)
                .ownerUuid(ownerUuid.toString())
                .ownerName("TestPlayer")
                .createdAt(now)
                .build();

            assertThat(lock.getWorld()).isEqualTo("world");
            assertThat(lock.getX()).isEqualTo(100);
            assertThat(lock.getY()).isEqualTo(64);
            assertThat(lock.getZ()).isEqualTo(-200);
            assertThat(lock.getOwnerUuid()).isEqualTo(ownerUuid.toString());
            assertThat(lock.getOwnerName()).isEqualTo("TestPlayer");
            assertThat(lock.getCreatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("Location Key Tests")
    class LocationKeyTests {

        @Test
        @DisplayName("Should generate correct location key")
        void shouldGenerateCorrectLocationKey() {
            ChestLockData lock = ChestLockData.builder()
                .world("world")
                .x(100)
                .y(64)
                .z(-200)
                .build();

            String key = lock.getLocationKey();

            assertThat(key).isEqualTo("world:100:64:-200");
        }

        @Test
        @DisplayName("Should generate correct location key for negative coordinates")
        void shouldGenerateKeyForNegativeCoordinates() {
            ChestLockData lock = ChestLockData.builder()
                .world("world_nether")
                .x(-100)
                .y(50)
                .z(-200)
                .build();

            String key = lock.getLocationKey();

            assertThat(key).isEqualTo("world_nether:-100:50:-200");
        }

        @Test
        @DisplayName("Should generate same key for same location")
        void shouldGenerateSameKeyForSameLocation() {
            ChestLockData lock1 = ChestLockData.builder()
                .world("world")
                .x(100)
                .y(64)
                .z(200)
                .build();

            ChestLockData lock2 = ChestLockData.builder()
                .world("world")
                .x(100)
                .y(64)
                .z(200)
                .build();

            assertThat(lock1.getLocationKey()).isEqualTo(lock2.getLocationKey());
        }
    }

    @Nested
    @DisplayName("Static Location Key Tests")
    class StaticLocationKeyTests {

        @Test
        @DisplayName("Should create location key from parameters")
        void shouldCreateLocationKeyFromParameters() {
            String key = ChestLockData.createLocationKey("world", 100, 64, -200);

            assertThat(key).isEqualTo("world:100:64:-200");
        }

        @Test
        @DisplayName("Static method should match instance method")
        void staticMethodShouldMatchInstanceMethod() {
            ChestLockData lock = ChestLockData.builder()
                .world("world")
                .x(100)
                .y(64)
                .z(-200)
                .build();

            String instanceKey = lock.getLocationKey();
            String staticKey = ChestLockData.createLocationKey("world", 100, 64, -200);

            assertThat(staticKey).isEqualTo(instanceKey);
        }
    }

    @Nested
    @DisplayName("Coordinate Tests")
    class CoordinateTests {

        @Test
        @DisplayName("Should handle zero coordinates")
        void shouldHandleZeroCoordinates() {
            ChestLockData lock = ChestLockData.builder()
                .world("world")
                .x(0)
                .y(0)
                .z(0)
                .build();

            assertThat(lock.getX()).isEqualTo(0);
            assertThat(lock.getY()).isEqualTo(0);
            assertThat(lock.getZ()).isEqualTo(0);
            assertThat(lock.getLocationKey()).isEqualTo("world:0:0:0");
        }

        @Test
        @DisplayName("Should handle large coordinates")
        void shouldHandleLargeCoordinates() {
            ChestLockData lock = ChestLockData.builder()
                .world("world")
                .x(30000000)
                .y(255)
                .z(-30000000)
                .build();

            assertThat(lock.getX()).isEqualTo(30000000);
            assertThat(lock.getY()).isEqualTo(255);
            assertThat(lock.getZ()).isEqualTo(-30000000);
        }
    }
}
