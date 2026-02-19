package com.ultikits.ultitools.widgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;

/**
 * Unit tests for {@link Toast}.
 */
@DisplayName("Toast 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
class ToastTest {
    
    @Mock
    private Player mockPlayer;
    
    @Mock
    private UltiTools mockUltiTools;
    
    @Mock
    private BukkitScheduler mockScheduler;
    
    @Mock
    private Advancement mockAdvancement;
    
    @Mock
    private AdvancementProgress mockProgress;
    
    @Mock
    private UnsafeValues mockUnsafe;

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("Toast 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(Toast.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 key 字段")
        void shouldHaveKeyField() throws NoSuchFieldException {
            Field field = Toast.class.getDeclaredField("key");

            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 icon 字段")
        void shouldHaveIconField() throws NoSuchFieldException {
            Field field = Toast.class.getDeclaredField("icon");

            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 message 字段")
        void shouldHaveMessageField() throws NoSuchFieldException {
            Field field = Toast.class.getDeclaredField("message");

            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 style 字段")
        void shouldHaveStyleField() throws NoSuchFieldException {
            Field field = Toast.class.getDeclaredField("style");

            assertThat(field.getType()).isEqualTo(Toast.Style.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该有私有构造函数")
        void shouldHavePrivateConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = Toast.class.getDeclaredConstructor(
                String.class, String.class, Toast.Style.class);

            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("静态方法测试")
    class StaticMethodTests {

        @Test
        @DisplayName("displayTo 方法应该存在且签名正确")
        void displayToMethodShouldExist() throws NoSuchMethodException {
            Method method = Toast.class.getDeclaredMethod("displayTo",
                Player.class, String.class, String.class, Toast.Style.class);

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("私有方法测试")
    class PrivateMethodTests {

        @Test
        @DisplayName("start 方法应该存在")
        void startMethodShouldExist() throws NoSuchMethodException {
            Method method = Toast.class.getDeclaredMethod("start",
                Player.class);

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("createAdvancement 方法应该存在")
        void createAdvancementMethodShouldExist() throws NoSuchMethodException {
            Method method = Toast.class.getDeclaredMethod("createAdvancement");

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("grantAdvancement 方法应该存在")
        void grantAdvancementMethodShouldExist() throws NoSuchMethodException {
            Method method = Toast.class.getDeclaredMethod("grantAdvancement",
                Player.class);

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("revokeAdvancement 方法应该存在")
        void revokeAdvancementMethodShouldExist() throws NoSuchMethodException {
            Method method = Toast.class.getDeclaredMethod("revokeAdvancement",
                Player.class);

            assertThat(Modifier.isPrivate(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("Style 枚举测试")
    class StyleEnumTests {

        @Test
        @DisplayName("Style 应该是枚举类型")
        void styleShouldBeEnum() {
            assertThat(Toast.Style.class.isEnum()).isTrue();
        }

        @Test
        @DisplayName("Style 应该有 GOAL 值")
        void styleShouldHaveGoal() {
            assertThat(Toast.Style.valueOf("GOAL")).isEqualTo(Toast.Style.GOAL);
        }

        @Test
        @DisplayName("Style 应该有 TASK 值")
        void styleShouldHaveTask() {
            assertThat(Toast.Style.valueOf("TASK")).isEqualTo(Toast.Style.TASK);
        }

        @Test
        @DisplayName("Style 应该有 CHALLENGE 值")
        void styleShouldHaveChallenge() {
            assertThat(Toast.Style.valueOf("CHALLENGE")).isEqualTo(Toast.Style.CHALLENGE);
        }

        @Test
        @DisplayName("Style 应该恰好有 3 个值")
        void styleShouldHaveExactly3Values() {
            assertThat(Toast.Style.values()).hasSize(3);
        }

        @Test
        @DisplayName("Style.toString() 应该返回小写用于 advancement frame")
        void styleToStringShouldBeLowerCase() {
            assertThat(Toast.Style.GOAL.toString().toLowerCase()).isEqualTo("goal");
            assertThat(Toast.Style.TASK.toString().toLowerCase()).isEqualTo("task");
            assertThat(Toast.Style.CHALLENGE.toString().toLowerCase()).isEqualTo("challenge");
        }
    }

    @Nested
    @DisplayName("消息格式测试")
    class MessageFormatTests {

        @Test
        @DisplayName("消息中的 | 应该替换为换行符")
        void pipeShouldBeReplacedWithNewline() {
            String message = "Line1|Line2|Line3";
            String replaced = message.replace("|", "\n");

            assertThat(replaced).isEqualTo("Line1\nLine2\nLine3");
        }

        @Test
        @DisplayName("没有 | 的消息应该保持不变")
        void messageWithoutPipeShouldRemainUnchanged() {
            String message = "Simple message";
            String replaced = message.replace("|", "\n");

            assertThat(replaced).isEqualTo("Simple message");
        }
    }

    @Nested
    @DisplayName("Advancement JSON 结构测试")
    class AdvancementJsonStructureTests {

        @Test
        @DisplayName("JSON 应该包含 criteria 字段")
        void jsonShouldContainCriteria() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"criteria\"");
        }

        @Test
        @DisplayName("JSON 应该包含 display 字段")
        void jsonShouldContainDisplay() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"display\"");
        }

        @Test
        @DisplayName("JSON 应该包含 icon 字段")
        void jsonShouldContainIcon() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"icon\"");
            assertThat(json).contains("minecraft:diamond");
        }

        @Test
        @DisplayName("JSON 应该包含 show_toast: true")
        void jsonShouldHaveShowToastTrue() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"show_toast\": true");
        }

        @Test
        @DisplayName("JSON 应该包含 announce_to_chat: false")
        void jsonShouldHaveAnnounceToChatFalse() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"announce_to_chat\": false");
        }

        @Test
        @DisplayName("JSON 应该包含 hidden: true")
        void jsonShouldHaveHiddenTrue() {
            String json = buildSampleJson("diamond", "Test", Toast.Style.TASK);
            assertThat(json).contains("\"hidden\": true");
        }

        private String buildSampleJson(String icon, String message, Toast.Style style) {
            return "{\n" +
                "    \"criteria\": {\n" +
                "        \"trigger\": {\n" +
                "            \"trigger\": \"minecraft:impossible\"\n" +
                "        }\n" +
                "    },\n" +
                "    \"display\": {\n" +
                "        \"icon\": {\n" +
                "            \"item\": \"minecraft:" + icon + "\"\n" +
                "        },\n" +
                "        \"title\": {\n" +
                "            \"text\": \"" + message.replace("|", "\n") + "\"\n" +
                "        },\n" +
                "        \"description\": {\n" +
                "            \"text\": \"\"\n" +
                "        },\n" +
                "        \"background\": \"minecraft:textures/gui/advancements/backgrounds/adventure.png\",\n" +
                "        \"frame\": \"" + style.toString().toLowerCase() + "\",\n" +
                "        \"announce_to_chat\": false,\n" +
                "        \"show_toast\": true,\n" +
                "        \"hidden\": true\n" +
                "    },\n" +
                "    \"requirements\": [\n" +
                "        [\n" +
                "            \"trigger\"\n" +
                "        ]\n" +
                "    ]\n" +
                "}";
        }
    }
    
    // ========== Mock 测试：displayTo 静态方法 ==========
    
    @Nested
    @DisplayName("displayTo Mock 测试")
    class DisplayToMockTests {
        
        @Test
        @DisplayName("displayTo 应该创建 advancement 并授予玩家")
        void displayToShouldCreateAndGrantAdvancement() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "diamond", "Test Toast", Toast.Style.TASK);
                
                // 验证 createAdvancement 被调用（通过 Bukkit.getUnsafe().loadAdvancement）
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), anyString());
                
                // 验证 grantAdvancement 被调用
                verify(mockProgress).awardCriteria("trigger");
                
                // 验证延迟任务被创建（用于 revokeAdvancement）
                verify(mockScheduler).runTaskLater(eq(mockUltiTools), any(Runnable.class), eq(10L));
            }
        }
        
        @Test
        @DisplayName("displayTo 使用 GOAL 样式")
        void displayToWithGoalStyle() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "emerald", "Goal Achieved!", Toast.Style.GOAL);
                
                // 验证 JSON 包含 "goal"
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("\"frame\": \"goal\""));
            }
        }
        
        @Test
        @DisplayName("displayTo 使用 CHALLENGE 样式")
        void displayToWithChallengeStyle() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "gold_ingot", "Challenge Complete!", Toast.Style.CHALLENGE);
                
                // 验证 JSON 包含 "challenge"
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("\"frame\": \"challenge\""));
            }
        }
        
        @Test
        @DisplayName("displayTo 消息中的 | 被替换为换行符")
        void displayToShouldReplacePipeWithNewline() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "diamond", "Line1|Line2", Toast.Style.TASK);
                
                // 验证 JSON 包含换行符（\n 而不是 \\n）
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("Line1\nLine2"));
            }
        }
        
        @Test
        @DisplayName("displayTo 应该使用正确的图标")
        void displayToShouldUseCorrectIcon() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "nether_star", "Special Item!", Toast.Style.TASK);
                
                // 验证 JSON 包含正确的图标
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("minecraft:nether_star"));
            }
        }
        
        @Test
        @DisplayName("延迟任务在 10 tick 后撤销 advancement")
        void delayedTaskShouldRevokeAfter10Ticks() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                
                // 捕获延迟任务
                doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    // 执行延迟任务（模拟 10 tick 后）
                    task.run();
                    return null;
                }).when(mockScheduler).runTaskLater(any(), any(Runnable.class), eq(10L));
                
                Toast.displayTo(mockPlayer, "diamond", "Test", Toast.Style.TASK);
                
                // 验证 revokeCriteria 被调用
                verify(mockProgress).revokeCriteria("trigger");
            }
        }
    }
    
    // ========== Mock 测试：Advancement 生命周期 ==========
    
    @Nested
    @DisplayName("Advancement 生命周期 Mock 测试")
    class AdvancementLifecycleMockTests {
        
        @Test
        @DisplayName("createAdvancement 使用 NamespacedKey 和 JSON")
        void createAdvancementUsesNamespacedKeyAndJson() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "diamond", "Test", Toast.Style.TASK);
                
                // 验证 loadAdvancement 被调用，且 JSON 包含正确结构
                verify(mockUnsafe).loadAdvancement(
                    any(NamespacedKey.class),
                    argThat(json -> json.contains("\"criteria\"") && 
                                   json.contains("\"display\"") &&
                                   json.contains("\"show_toast\": true"))
                );
            }
        }
        
        @Test
        @DisplayName("grantAdvancement 授予 trigger 标准")
        void grantAdvancementAwardsTriggerCriteria() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                Toast.displayTo(mockPlayer, "diamond", "Test", Toast.Style.TASK);
                
                // 验证 awardCriteria("trigger") 被调用
                verify(mockProgress).awardCriteria("trigger");
            }
        }
        
        @Test
        @DisplayName("revokeAdvancement 撤销 trigger 标准")
        void revokeAdvancementRevokesTriggerCriteria() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                
                // 立即执行延迟任务
                doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(1);
                    task.run();
                    return null;
                }).when(mockScheduler).runTaskLater(any(), any(Runnable.class), eq(10L));
                
                Toast.displayTo(mockPlayer, "diamond", "Test", Toast.Style.TASK);
                
                // 验证 revokeCriteria("trigger") 被调用
                verify(mockProgress).revokeCriteria("trigger");
            }
        }
    }
    
    // ========== Mock 测试：边界情况 ==========
    
    @Nested
    @DisplayName("边界情况 Mock 测试")
    class EdgeCaseMockTests {
        
        @Test
        @DisplayName("空消息应该正常处理")
        void emptyMessageShouldWork() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                // 空消息不应该抛出异常
                Toast.displayTo(mockPlayer, "diamond", "", Toast.Style.TASK);
                
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), anyString());
            }
        }
        
        @Test
        @DisplayName("Unicode 消息应该正常处理")
        void unicodeMessageShouldWork() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                // Unicode 消息
                Toast.displayTo(mockPlayer, "diamond", "测试消息 🎉", Toast.Style.TASK);
                
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("测试消息"));
            }
        }
        
        @Test
        @DisplayName("多行消息（多个 |）应该正常处理")
        void multiLineMessageShouldWork() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                    .thenReturn(mockAdvancement);
                
                when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                    .thenReturn(null);
                
                // 多行消息
                Toast.displayTo(mockPlayer, "diamond", "Line1|Line2|Line3|Line4", Toast.Style.TASK);
                
                verify(mockUnsafe).loadAdvancement(any(NamespacedKey.class), contains("Line1\nLine2\nLine3\nLine4"));
            }
        }
        
        @Test
        @DisplayName("所有 Style 类型都应该工作")
        void allStyleTypesShouldWork() {
            for (Toast.Style style : Toast.Style.values()) {
                try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                     MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                    
                    ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                    bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                    bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                    bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                        .thenReturn(mockAdvancement);
                    
                    when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                    lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                        .thenReturn(null);
                    
                    Toast.displayTo(mockPlayer, "diamond", "Test " + style.name(), style);
                    
                    verify(mockUnsafe).loadAdvancement(
                        any(NamespacedKey.class), 
                        contains("\"frame\": \"" + style.name().toLowerCase() + "\"")
                    );
                    
                    clearInvocations(mockUnsafe, mockProgress);
                }
            }
        }
    }
    
    // ========== Mock 测试：不同图标 ==========
    
    @Nested
    @DisplayName("图标 Mock 测试")
    class IconMockTests {
        
        @Test
        @DisplayName("常见物品图标应该正常工作")
        void commonItemIconsShouldWork() {
            String[] icons = {"diamond", "emerald", "gold_ingot", "iron_ingot", "coal"};
            
            for (String icon : icons) {
                try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                     MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                    
                    ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                    bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                    bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                    bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                        .thenReturn(mockAdvancement);
                    
                    when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                    lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                        .thenReturn(null);
                    
                    Toast.displayTo(mockPlayer, icon, "Test", Toast.Style.TASK);
                    
                    verify(mockUnsafe).loadAdvancement(
                        any(NamespacedKey.class),
                        contains("minecraft:" + icon)
                    );
                    
                    clearInvocations(mockUnsafe, mockProgress);
                }
            }
        }
        
        @Test
        @DisplayName("特殊物品图标应该正常工作")
        void specialItemIconsShouldWork() {
            String[] icons = {"nether_star", "dragon_egg", "elytra", "beacon"};
            
            for (String icon : icons) {
                try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                     MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                    
                    ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                    bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                    bukkitMock.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
                    bukkitMock.when(() -> Bukkit.getAdvancement(any(NamespacedKey.class)))
                        .thenReturn(mockAdvancement);
                    
                    when(mockPlayer.getAdvancementProgress(mockAdvancement)).thenReturn(mockProgress);
                    lenient().when(mockScheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                        .thenReturn(null);
                    
                    Toast.displayTo(mockPlayer, icon, "Special Item!", Toast.Style.CHALLENGE);
                    
                    verify(mockUnsafe).loadAdvancement(
                        any(NamespacedKey.class),
                        contains("minecraft:" + icon)
                    );
                    
                    clearInvocations(mockUnsafe, mockProgress);
                }
            }
        }
    }
}
