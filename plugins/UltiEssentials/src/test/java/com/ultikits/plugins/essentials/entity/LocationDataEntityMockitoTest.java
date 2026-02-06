package com.ultikits.plugins.essentials.entity;

import com.ultikits.plugins.essentials.entity.base.LocationDataEntity;
import com.ultikits.plugins.essentials.utils.EssentialsTestHelper;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests for LocationDataEntity.
 * Uses HomeData as the concrete implementation for testing.
 * <p>
 * 纯 Mockito 测试位置数据实体基类。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("LocationDataEntity Tests (Mockito)")
class LocationDataEntityMockitoTest {

    @BeforeEach
    void setUp() throws Exception {
        EssentialsTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        EssentialsTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with location fields via HomeData builder")
        void shouldCreateWithLocationFields() {
            HomeData home = HomeData.builder()
                .world("world")
                .x(100.5)
                .y(64.0)
                .z(-200.5)
                .yaw(90.0f)
                .pitch(-45.0f)
                .build();

            assertThat(home.getWorld()).isEqualTo("world");
            assertThat(home.getX()).isEqualTo(100.5);
            assertThat(home.getY()).isEqualTo(64.0);
            assertThat(home.getZ()).isEqualTo(-200.5);
            assertThat(home.getYaw()).isEqualTo(90.0f);
            assertThat(home.getPitch()).isEqualTo(-45.0f);
        }

        @Test
        @DisplayName("Should create with default values via no-args constructor")
        void shouldCreateWithDefaultValues() {
            HomeData home = new HomeData();
            assertThat(home.getWorld()).isNull();
            assertThat(home.getX()).isEqualTo(0.0);
            assertThat(home.getY()).isEqualTo(0.0);
            assertThat(home.getZ()).isEqualTo(0.0);
            assertThat(home.getYaw()).isEqualTo(0.0f);
            assertThat(home.getPitch()).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("toLocation Tests")
    class ToLocationTests {

        @Test
        @DisplayName("Should return location when world exists")
        void shouldReturnLocationWhenWorldExists() throws Exception {
            World mockWorld = EssentialsTestHelper.createMockWorld("world");
            when(EssentialsTestHelper.getMockServer().getWorld("world")).thenReturn(mockWorld);

            HomeData home = HomeData.builder()
                .world("world")
                .x(100.5)
                .y(64.0)
                .z(-200.5)
                .yaw(90.0f)
                .pitch(-45.0f)
                .build();

            Location loc = home.toLocation();

            assertThat(loc).isNotNull();
            assertThat(loc.getWorld()).isEqualTo(mockWorld);
            assertThat(loc.getX()).isEqualTo(100.5);
            assertThat(loc.getY()).isEqualTo(64.0);
            assertThat(loc.getZ()).isEqualTo(-200.5);
            assertThat(loc.getYaw()).isEqualTo(90.0f);
            assertThat(loc.getPitch()).isEqualTo(-45.0f);
        }

        @Test
        @DisplayName("Should return null when world does not exist")
        void shouldReturnNullWhenWorldNotFound() throws Exception {
            when(EssentialsTestHelper.getMockServer().getWorld("nonexistent")).thenReturn(null);

            HomeData home = HomeData.builder()
                .world("nonexistent")
                .x(0)
                .y(0)
                .z(0)
                .build();

            Location loc = home.toLocation();
            assertThat(loc).isNull();
        }
    }

    @Nested
    @DisplayName("fromLocation Tests")
    class FromLocationTests {

        @Test
        @DisplayName("Should update fields from Bukkit Location")
        void shouldUpdateFieldsFromLocation() throws Exception {
            World mockWorld = EssentialsTestHelper.createMockWorld("nether");
            Location location = new Location(mockWorld, 50.5, 32.0, 100.5, 180.0f, 10.0f);

            HomeData home = new HomeData();
            home.fromLocation(location);

            assertThat(home.getWorld()).isEqualTo("nether");
            assertThat(home.getX()).isEqualTo(50.5);
            assertThat(home.getY()).isEqualTo(32.0);
            assertThat(home.getZ()).isEqualTo(100.5);
            assertThat(home.getYaw()).isEqualTo(180.0f);
            assertThat(home.getPitch()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("Should handle null location gracefully")
        void shouldHandleNullLocation() throws Exception {
            HomeData home = HomeData.builder()
                .world("world")
                .x(100.0)
                .build();

            home.fromLocation(null);

            // Should not change existing values
            assertThat(home.getWorld()).isEqualTo("world");
            assertThat(home.getX()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Should handle location with null world gracefully")
        void shouldHandleNullWorldInLocation() throws Exception {
            Location location = new Location(null, 50.0, 32.0, 100.0);

            HomeData home = HomeData.builder()
                .world("world")
                .x(100.0)
                .build();

            home.fromLocation(location);

            // Should not change existing values
            assertThat(home.getWorld()).isEqualTo("world");
        }
    }

    @Nested
    @DisplayName("isWorldValid Tests")
    class IsWorldValidTests {

        @Test
        @DisplayName("Should return true when world exists")
        void shouldReturnTrueWhenWorldExists() throws Exception {
            World mockWorld = EssentialsTestHelper.createMockWorld("world");
            when(EssentialsTestHelper.getMockServer().getWorld("world")).thenReturn(mockWorld);

            HomeData home = HomeData.builder().world("world").build();

            assertThat(home.isWorldValid()).isTrue();
        }

        @Test
        @DisplayName("Should return false when world does not exist")
        void shouldReturnFalseWhenWorldNotFound() throws Exception {
            when(EssentialsTestHelper.getMockServer().getWorld("nonexistent")).thenReturn(null);

            HomeData home = HomeData.builder().world("nonexistent").build();

            assertThat(home.isWorldValid()).isFalse();
        }
    }
}
