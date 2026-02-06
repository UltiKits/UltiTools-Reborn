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
 * Unit tests for LocationDataEntity via HomeData/WarpData.
 * <p>
 * 位置数据实体单元测试。
 */
@DisplayName("LocationDataEntity 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("Requires Bukkit runtime - MockBukkit Registry/PotionEffectType initialization issue")
class LocationDataEntityTest {

    private World world;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        world = server.addSimpleWorld("testworld");
    }
    
    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }
    
    @Nested
    @DisplayName("HomeData toLocation 测试")
    class HomeDataToLocationTests {
        
        @Test
        @DisplayName("应该正确转换为 Location")
        void shouldConvertToLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .playerUuid("player-uuid")
                .name("home1")
                .world("testworld")
                .x(100.5)
                .y(64.0)
                .z(-200.3)
                .yaw(90.0f)
                .pitch(0.0f)
                .createdAt(System.currentTimeMillis())
                .build();
            
            Location loc = home.toLocation();
            
            assertThat(loc).isNotNull();
            assertThat(loc.getWorld().getName()).isEqualTo("testworld");
            assertThat(loc.getX()).isEqualTo(100.5);
            assertThat(loc.getY()).isEqualTo(64.0);
            assertThat(loc.getZ()).isEqualTo(-200.3);
            assertThat(loc.getYaw()).isEqualTo(90.0f);
            assertThat(loc.getPitch()).isEqualTo(0.0f);
        }
        
        @Test
        @DisplayName("世界不存在时应该返回 null")
        void shouldReturnNullWhenWorldNotExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("nonexistent_world")
                .x(0)
                .y(64)
                .z(0)
                .build();
            
            Location loc = home.toLocation();
            
            assertThat(loc).isNull();
        }
    }
    
    @Nested
    @DisplayName("WarpData toLocation 测试")
    class WarpDataToLocationTests {
        
        @Test
        @DisplayName("应该正确转换为 Location")
        void shouldConvertToLocation() {
            WarpData warp = WarpData.builder()
                .uuid(UUID.randomUUID())
                .name("spawn")
                .world("testworld")
                .x(0.0)
                .y(64.0)
                .z(0.0)
                .yaw(0.0f)
                .pitch(0.0f)
                .createdAt(System.currentTimeMillis())
                .build();
            
            Location loc = warp.toLocation();
            
            assertThat(loc).isNotNull();
            assertThat(loc.getWorld().getName()).isEqualTo("testworld");
        }
    }
    
    @Nested
    @DisplayName("fromLocation 测试")
    class FromLocationTests {
        
        @Test
        @DisplayName("应该正确从 Location 更新字段")
        void shouldUpdateFieldsFromLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("old_world")
                .x(0)
                .y(0)
                .z(0)
                .build();
            
            Location newLoc = new Location(world, 50.5, 100.0, -30.2, 45.0f, -10.0f);
            home.fromLocation(newLoc);
            
            assertThat(home.getWorld()).isEqualTo("testworld");
            assertThat(home.getX()).isEqualTo(50.5);
            assertThat(home.getY()).isEqualTo(100.0);
            assertThat(home.getZ()).isEqualTo(-30.2);
            assertThat(home.getYaw()).isEqualTo(45.0f);
            assertThat(home.getPitch()).isEqualTo(-10.0f);
        }
        
        @Test
        @DisplayName("null Location 不应该修改字段")
        void shouldNotModifyFieldsWithNullLocation() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("original_world")
                .x(100)
                .y(64)
                .z(200)
                .build();
            
            home.fromLocation(null);
            
            assertThat(home.getWorld()).isEqualTo("original_world");
            assertThat(home.getX()).isEqualTo(100);
        }
    }
    
    @Nested
    @DisplayName("isWorldValid 测试")
    class IsWorldValidTests {
        
        @Test
        @DisplayName("世界存在时应该返回 true")
        void shouldReturnTrueWhenWorldExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("testworld")
                .build();
            
            assertThat(home.isWorldValid()).isTrue();
        }
        
        @Test
        @DisplayName("世界不存在时应该返回 false")
        void shouldReturnFalseWhenWorldNotExists() {
            HomeData home = HomeData.builder()
                .uuid(UUID.randomUUID())
                .world("nonexistent")
                .build();
            
            assertThat(home.isWorldValid()).isFalse();
        }
    }
}
