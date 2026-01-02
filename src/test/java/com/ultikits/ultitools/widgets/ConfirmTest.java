package com.ultikits.ultitools.widgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.widgets.impl.ChatConfirm;
import com.ultikits.ultitools.widgets.impl.InventoryConfirm;

/**
 * Unit tests for {@link Confirm} interface.
 */
@DisplayName("Confirm 接口测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfirmTest {

    @Nested
    @DisplayName("接口结构测试")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Confirm 应该是接口")
        void shouldBeInterface() {
            assertThat(Confirm.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("应该有 show 方法")
        void shouldHaveShowMethod() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("show");

            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("应该有 getConfirmText 方法")
        void shouldHaveGetConfirmTextMethod() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("getConfirmText");

            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("应该有 getCancelText 方法")
        void shouldHaveGetCancelTextMethod() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("getCancelText");

            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("静态工厂方法测试")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("gui 工厂方法(5参数)应该存在")
        void guiFactoryMethodWith5ParamsShouldExist() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("gui",
                Player.class, String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(Confirm.class);
        }

        @Test
        @DisplayName("gui 工厂方法(7参数)应该存在")
        void guiFactoryMethodWith7ParamsShouldExist() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("gui",
                Player.class, String.class, String.class,
                String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(Confirm.class);
        }

        @Test
        @DisplayName("chat 工厂方法(5参数)应该存在")
        void chatFactoryMethodWith5ParamsShouldExist() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("chat",
                Player.class, String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(Confirm.class);
        }

        @Test
        @DisplayName("chat 工厂方法(7参数)应该存在")
        void chatFactoryMethodWith7ParamsShouldExist() throws NoSuchMethodException {
            Method method = Confirm.class.getDeclaredMethod("chat",
                Player.class, String.class, String.class,
                String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(Confirm.class);
        }
    }

    @Nested
    @DisplayName("工厂方法返回类型测试")
    class FactoryMethodReturnTypeTests {

        private Player mockPlayer;
        private Runnable mockOnConfirm;
        private Runnable mockOnCancel;

        @BeforeEach
        void setUp() {
            mockPlayer = mock(Player.class);
            mockOnConfirm = mock(Runnable.class);
            mockOnCancel = mock(Runnable.class);
        }

        @Test
        @DisplayName("gui 工厂方法应该返回 InventoryConfirm 实例")
        void guiFactoryShouldReturnInventoryConfirm() {
            Confirm confirm = Confirm.gui(mockPlayer, "Title", "Description",
                mockOnConfirm, mockOnCancel);

            assertThat(confirm).isInstanceOf(InventoryConfirm.class);
        }

        @Test
        @DisplayName("gui 工厂方法(带自定义文本)应该返回 InventoryConfirm 实例")
        void guiFactoryWithCustomTextShouldReturnInventoryConfirm() {
            Confirm confirm = Confirm.gui(mockPlayer, "Title", "Description",
                "Yes", "No", mockOnConfirm, mockOnCancel);

            assertThat(confirm).isInstanceOf(InventoryConfirm.class);
        }

        @Test
        @DisplayName("chat 工厂方法应该返回 ChatConfirm 实例")
        void chatFactoryShouldReturnChatConfirm() {
            Confirm confirm = Confirm.chat(mockPlayer, "Title", "Description",
                mockOnConfirm, mockOnCancel);

            assertThat(confirm).isInstanceOf(ChatConfirm.class);
        }

        @Test
        @DisplayName("chat 工厂方法(带自定义文本)应该返回 ChatConfirm 实例")
        void chatFactoryWithCustomTextShouldReturnChatConfirm() {
            Confirm confirm = Confirm.chat(mockPlayer, "Title", "Description",
                "Confirm", "Cancel", mockOnConfirm, mockOnCancel);

            assertThat(confirm).isInstanceOf(ChatConfirm.class);
        }
    }

    @Nested
    @DisplayName("实现类测试")
    class ImplementationClassTests {

        @Test
        @DisplayName("ChatConfirm 应该实现 Confirm 接口")
        void chatConfirmShouldImplementConfirm() {
            assertThat(Confirm.class.isAssignableFrom(ChatConfirm.class)).isTrue();
        }

        @Test
        @DisplayName("InventoryConfirm 应该实现 Confirm 接口")
        void inventoryConfirmShouldImplementConfirm() {
            assertThat(Confirm.class.isAssignableFrom(InventoryConfirm.class)).isTrue();
        }
    }
}
