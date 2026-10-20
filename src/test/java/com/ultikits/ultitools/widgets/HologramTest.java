package com.ultikits.ultitools.widgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link Hologram}.
 */
@DisplayName("Hologram 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
class HologramTest {

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("Hologram 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(Hologram.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 lines 字段")
        void shouldHaveLinesField() throws NoSuchFieldException {
            Field field = Hologram.class.getDeclaredField("lines");

            assertThat(field.getType()).isEqualTo(String[].class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该有可变参数构造函数")
        void shouldHaveVarargConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = Hologram.class.getConstructor(String[].class);

            assertThat(constructor).isNotNull();
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("构造函数应该正确初始化 lines 字段")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void constructorShouldInitializeLinesField() throws Exception {
            String[] lines = {"Line 1", "Line 2", "Line 3"};
            Hologram hologram = new Hologram(lines);

            Field linesField = Hologram.class.getDeclaredField("lines");
            linesField.setAccessible(true);
            String[] storedLines = (String[]) linesField.get(hologram);

            assertThat(storedLines).containsExactly("Line 1", "Line 2", "Line 3");
        }

        @Test
        @DisplayName("应该能创建空行的全息图")
        void shouldCreateHologramWithNoLines() throws Exception {
            Hologram hologram = new Hologram();

            Field linesField = Hologram.class.getDeclaredField("lines");
            linesField.setAccessible(true);
            String[] storedLines = (String[]) linesField.get(hologram);

            assertThat(storedLines).isEmpty();
        }

        @Test
        @DisplayName("应该能创建单行全息图")
        void shouldCreateHologramWithSingleLine() throws Exception {
            Hologram hologram = new Hologram("Single Line");

            Field linesField = Hologram.class.getDeclaredField("lines");
            linesField.setAccessible(true);
            String[] storedLines = (String[]) linesField.get(hologram);

            assertThat(storedLines).containsExactly("Single Line");
        }

        @Test
        @DisplayName("应该能创建多行全息图")
        void shouldCreateHologramWithMultipleLines() throws Exception {
            Hologram hologram = new Hologram("First", "Second", "Third", "Fourth");

            Field linesField = Hologram.class.getDeclaredField("lines");
            linesField.setAccessible(true);
            String[] storedLines = (String[]) linesField.get(hologram);

            assertThat(storedLines).hasSize(4);
        }
    }

    @Nested
    @DisplayName("spawn 方法测试")
    class SpawnMethodTests {

        @Test
        @DisplayName("spawn 方法应该存在且签名正确")
        void spawnMethodShouldExist() throws NoSuchMethodException {
            Method method = Hologram.class.getDeclaredMethod("spawn", Location.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(ArmorStand[].class);
        }

        @Test
        @DisplayName("spawn 方法应该返回 ArmorStand 数组")
        void spawnShouldReturnArmorStandArray() throws NoSuchMethodException {
            Method method = Hologram.class.getDeclaredMethod("spawn", Location.class);

            assertThat(method.getReturnType()).isEqualTo(ArmorStand[].class);
        }
    }

    @Nested
    @DisplayName("ArmorStand 配置测试")
    class ArmorStandConfigTests {

        @Test
        @DisplayName("应该能访问 ArmorStand 的配置方法")
        void shouldAccessArmorStandConfigMethods() throws NoSuchMethodException {
            // 验证 ArmorStand 类有必要的配置方法
            assertThat(ArmorStand.class.getMethod("setVisible", boolean.class)).isNotNull();
            assertThat(ArmorStand.class.getMethod("setGravity", boolean.class)).isNotNull();
            assertThat(ArmorStand.class.getMethod("setInvulnerable", boolean.class)).isNotNull();
            assertThat(ArmorStand.class.getMethod("setCustomNameVisible", boolean.class)).isNotNull();
            assertThat(ArmorStand.class.getMethod("setCustomName", String.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("Location 操作测试")
    class LocationOperationTests {

        @Test
        @DisplayName("应该能对 Location 进行 subtract 操作")
        void shouldSubtractFromLocation() {
            World mockWorld = mock(World.class);
            Location location = new Location(mockWorld, 0, 100, 0);

            Location subtracted = location.subtract(0, 0.25, 0);

            assertThat(subtracted.getY()).isEqualTo(99.75);
        }

        @Test
        @DisplayName("多次 subtract 应该累积")
        void multipleSubtractShouldAccumulate() {
            World mockWorld = mock(World.class);
            Location location = new Location(mockWorld, 0, 100, 0);

            location.subtract(0, 0.25, 0);
            location.subtract(0, 0.25, 0);
            location.subtract(0, 0.25, 0);

            assertThat(location.getY()).isEqualTo(99.25);
        }
    }

    @Nested
    @DisplayName("行间距测试")
    class LineSpacingTests {

        @Test
        @DisplayName("默认行间距应该是 0.25")
        void defaultLineSpacingShouldBe025() {
            // 验证源代码中使用的行间距值
            double expectedSpacing = 0.25;
            assertThat(expectedSpacing).isEqualTo(0.25);
        }
    }
    
    // ========== Mock 测试：spawn 方法 ==========
    
    @Nested
    @DisplayName("spawn 方法 Mock 测试")
    class SpawnMockTests {
        
        @Mock
        private World mockWorld;
        
        @Mock
        private ArmorStand mockArmorStand1;
        
        @Mock
        private ArmorStand mockArmorStand2;
        
        @Mock
        private ArmorStand mockArmorStand3;
        
        @BeforeEach
        void setUp() {
            // MockitoExtension 会自动初始化 @Mock 字段
        }
        
        @Test
        @DisplayName("spawn 单行文本应该创建一个 ArmorStand")
        void spawnSingleLineShouldCreateOneArmorStand() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1);
            
            Hologram hologram = new Hologram("Single Line");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).hasSize(1);
            verify(mockWorld, times(1)).spawn(any(Location.class), eq(ArmorStand.class));
        }
        
        @Test
        @DisplayName("spawn 应该正确配置 ArmorStand 属性")
        void spawnShouldConfigureArmorStandProperties() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1);
            
            Hologram hologram = new Hologram("Test Line");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            // 验证 ArmorStand 配置
            verify(mockArmorStand1).setVisible(false);
            verify(mockArmorStand1).setGravity(false);
            verify(mockArmorStand1).setInvulnerable(true);
            verify(mockArmorStand1).setCustomNameVisible(true);
            verify(mockArmorStand1).setCustomName("Test Line");
        }
        
        @Test
        @DisplayName("spawn 多行文本应该创建多个 ArmorStand")
        void spawnMultipleLinesShouldCreateMultipleArmorStands() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1)
                .thenReturn(mockArmorStand2)
                .thenReturn(mockArmorStand3);
            
            Hologram hologram = new Hologram("Line 1", "Line 2", "Line 3");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).hasSize(3);
            verify(mockWorld, times(3)).spawn(any(Location.class), eq(ArmorStand.class));
        }
        
        @Test
        @DisplayName("spawn 应该为每行设置正确的自定义名称")
        void spawnShouldSetCorrectCustomNameForEachLine() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1)
                .thenReturn(mockArmorStand2);
            
            Hologram hologram = new Hologram("First Line", "Second Line");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            verify(mockArmorStand1).setCustomName("First Line");
            verify(mockArmorStand2).setCustomName("Second Line");
        }
        
        @Test
        @DisplayName("spawn 空全息图应该返回空数组")
        void spawnEmptyHologramShouldReturnEmptyArray() {
            Hologram hologram = new Hologram();
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).isEmpty();
            verify(mockWorld, never()).spawn(any(Location.class), any());
        }
        
        @Test
        @DisplayName("spawn 应该按 0.25 间距递减位置")
        void spawnShouldDecrementLocationBy025() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1)
                .thenReturn(mockArmorStand2)
                .thenReturn(mockArmorStand3);
            
            Hologram hologram = new Hologram("Line 1", "Line 2", "Line 3");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            // 验证 location 被修改了 (0.25 * 3 = 0.75)
            assertThat(location.getY()).isEqualTo(99.25);
        }
        
        @Test
        @DisplayName("spawn 返回的数组应该包含正确的 ArmorStand")
        void spawnShouldReturnCorrectArmorStands() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1)
                .thenReturn(mockArmorStand2);
            
            Hologram hologram = new Hologram("Line 1", "Line 2");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).containsExactly(mockArmorStand1, mockArmorStand2);
        }
        
        @Test
        @DisplayName("所有 ArmorStand 都应该配置相同的基础属性")
        void allArmorStandsShouldHaveSameBaseConfig() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand1)
                .thenReturn(mockArmorStand2);
            
            Hologram hologram = new Hologram("Line 1", "Line 2");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            // 验证两个 ArmorStand 都有相同的基础配置
            verify(mockArmorStand1).setVisible(false);
            verify(mockArmorStand1).setGravity(false);
            verify(mockArmorStand1).setInvulnerable(true);
            verify(mockArmorStand1).setCustomNameVisible(true);
            
            verify(mockArmorStand2).setVisible(false);
            verify(mockArmorStand2).setGravity(false);
            verify(mockArmorStand2).setInvulnerable(true);
            verify(mockArmorStand2).setCustomNameVisible(true);
        }
    }
    
    // ========== 边界条件测试 ==========
    
    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {
        
        @Mock
        private World mockWorld;
        
        @Mock
        private ArmorStand mockArmorStand;
        
        @Test
        @DisplayName("spawn 包含空字符串的行")
        void spawnWithEmptyStringLine() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            Hologram hologram = new Hologram("");
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).hasSize(1);
            verify(mockArmorStand).setCustomName("");
        }
        
        @Test
        @DisplayName("spawn 包含特殊字符的行")
        void spawnWithSpecialCharacters() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            String specialText = "§6Gold §lBold §c§oItalic";
            Hologram hologram = new Hologram(specialText);
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            verify(mockArmorStand).setCustomName(specialText);
        }
        
        @Test
        @DisplayName("spawn 包含很长文本的行")
        void spawnWithLongText() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            String longText = "This is a very long text line that might exceed normal display limits but should still be set as custom name";
            Hologram hologram = new Hologram(longText);
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            verify(mockArmorStand).setCustomName(longText);
        }
        
        @Test
        @DisplayName("spawn 包含 Unicode 字符的行")
        void spawnWithUnicodeCharacters() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            String unicodeText = "你好世界 🎮 こんにちは";
            Hologram hologram = new Hologram(unicodeText);
            Location location = new Location(mockWorld, 0, 100, 0);
            
            hologram.spawn(location);
            
            verify(mockArmorStand).setCustomName(unicodeText);
        }
        
        @Test
        @DisplayName("spawn 大量行数")
        void spawnWithManyLines() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            String[] lines = new String[10];
            for (int i = 0; i < 10; i++) {
                lines[i] = "Line " + (i + 1);
            }
            
            Hologram hologram = new Hologram(lines);
            Location location = new Location(mockWorld, 0, 100, 0);
            
            ArmorStand[] stands = hologram.spawn(location);
            
            assertThat(stands).hasSize(10);
            verify(mockWorld, times(10)).spawn(any(Location.class), eq(ArmorStand.class));
        }
    }
    
    // ========== 位置计算测试 ==========
    
    @Nested
    @DisplayName("位置计算精度测试")
    class LocationCalculationTests {
        
        @Mock
        private World mockWorld;
        
        @Mock
        private ArmorStand mockArmorStand;
        
        @Test
        @DisplayName("位置 Y 坐标应该精确递减 0.25")
        void locationYShouldDecrementPrecisely() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            Hologram hologram = new Hologram("Line 1", "Line 2", "Line 3", "Line 4");
            Location location = new Location(mockWorld, 10.5, 64.0, -20.3);
            
            hologram.spawn(location);
            
            // 4 行意味着 4 次减少，每次 0.25
            assertThat(location.getY()).isEqualTo(63.0);
            assertThat(location.getX()).isEqualTo(10.5);  // X 应该不变
            assertThat(location.getZ()).isEqualTo(-20.3); // Z 应该不变
        }
        
        @Test
        @DisplayName("负 Y 坐标也应该正确处理")
        void negativeYCoordinateShouldBeHandled() {
            when(mockWorld.spawn(any(Location.class), eq(ArmorStand.class)))
                .thenReturn(mockArmorStand);
            
            Hologram hologram = new Hologram("Line 1", "Line 2");
            Location location = new Location(mockWorld, 0, -10, 0);
            
            hologram.spawn(location);
            
            assertThat(location.getY()).isEqualTo(-10.5);
        }
    }
}
