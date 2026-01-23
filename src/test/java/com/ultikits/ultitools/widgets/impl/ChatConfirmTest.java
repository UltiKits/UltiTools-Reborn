package com.ultikits.ultitools.widgets.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ChatCallbackManager;
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.widgets.Confirm;

import net.kyori.adventure.text.Component;

/**
 * Unit tests for {@link ChatConfirm}.
 */
@DisplayName("ChatConfirm 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test requires reflection for mocking internal state
class ChatConfirmTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private Runnable mockOnConfirm;
    
    @Mock
    private Runnable mockOnCancel;
    
    @Mock
    private UltiTools mockUltiTools;

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("ChatConfirm 应该实现 Confirm 接口")
        void shouldImplementConfirmInterface() {
            assertThat(Confirm.class.isAssignableFrom(ChatConfirm.class)).isTrue();
        }

        @Test
        @DisplayName("ChatConfirm 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(ChatConfirm.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("字段测试")
    class FieldTests {

        @Test
        @DisplayName("应该有 confirmText 字段")
        void shouldHaveConfirmTextField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("confirmText");
            assertThat(field.getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("应该有 cancelText 字段")
        void shouldHaveCancelTextField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("cancelText");
            assertThat(field.getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("应该有 player 字段")
        void shouldHavePlayerField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("player");
            assertThat(field.getType()).isEqualTo(Player.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 title 字段")
        void shouldHaveTitleField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("title");
            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 description 字段")
        void shouldHaveDescriptionField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("description");
            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 onConfirm 字段")
        void shouldHaveOnConfirmField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("onConfirm");
            assertThat(field.getType()).isEqualTo(Runnable.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 onCancel 字段")
        void shouldHaveOnCancelField() throws NoSuchFieldException {
            Field field = ChatConfirm.class.getDeclaredField("onCancel");
            assertThat(field.getType()).isEqualTo(Runnable.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该有5参数构造函数")
        void shouldHave5ParamConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = ChatConfirm.class.getConstructor(
                Player.class, String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(constructor).isNotNull();
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有7参数构造函数")
        void shouldHave7ParamConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = ChatConfirm.class.getConstructor(
                Player.class, String.class, String.class,
                String.class, String.class,
                Runnable.class, Runnable.class);

            assertThat(constructor).isNotNull();
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("5参数构造函数应该正确初始化字段")
        void constructor5ParamsShouldInitializeFields() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Test Title",
                "Test Description", mockOnConfirm, mockOnCancel);

            Field playerField = ChatConfirm.class.getDeclaredField("player");
            playerField.setAccessible(true);
            assertThat(playerField.get(confirm)).isEqualTo(mockPlayer);

            Field titleField = ChatConfirm.class.getDeclaredField("title");
            titleField.setAccessible(true);
            assertThat(titleField.get(confirm)).isEqualTo("Test Title");

            Field descField = ChatConfirm.class.getDeclaredField("description");
            descField.setAccessible(true);
            assertThat(descField.get(confirm)).isEqualTo("Test Description");
        }

        @Test
        @DisplayName("7参数构造函数应该正确初始化自定义文本")
        void constructor7ParamsShouldInitializeCustomText() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                "Accept", "Decline", mockOnConfirm, mockOnCancel);

            Field confirmTextField = ChatConfirm.class.getDeclaredField("confirmText");
            confirmTextField.setAccessible(true);
            assertThat(confirmTextField.get(confirm)).isEqualTo("Accept");

            Field cancelTextField = ChatConfirm.class.getDeclaredField("cancelText");
            cancelTextField.setAccessible(true);
            assertThat(cancelTextField.get(confirm)).isEqualTo("Decline");
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("show 方法应该存在")
        void showMethodShouldExist() throws NoSuchMethodException {
            Method method = ChatConfirm.class.getDeclaredMethod("show");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("getConfirmText 方法应该存在")
        void getConfirmTextMethodShouldExist() throws NoSuchMethodException {
            Method method = ChatConfirm.class.getDeclaredMethod("getConfirmText");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getCancelText 方法应该存在")
        void getCancelTextMethodShouldExist() throws NoSuchMethodException {
            Method method = ChatConfirm.class.getDeclaredMethod("getCancelText");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("自定义文本测试")
    class CustomTextTests {

        @Test
        @DisplayName("使用自定义确认文本时应该返回自定义文本")
        void shouldReturnCustomConfirmText() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                "Yes Please", "No Thanks", mockOnConfirm, mockOnCancel);

            assertThat(confirm.getConfirmText()).isEqualTo("Yes Please");
        }

        @Test
        @DisplayName("使用自定义取消文本时应该返回自定义文本")
        void shouldReturnCustomCancelText() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                "Yes Please", "No Thanks", mockOnConfirm, mockOnCancel);

            assertThat(confirm.getCancelText()).isEqualTo("No Thanks");
        }

        @Test
        @DisplayName("未设置自定义文本时 confirmText 应该为 null")
        void confirmTextShouldBeNullWhenNotSet() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);

            Field confirmTextField = ChatConfirm.class.getDeclaredField("confirmText");
            confirmTextField.setAccessible(true);
            assertThat(confirmTextField.get(confirm)).isNull();
        }

        @Test
        @DisplayName("未设置自定义文本时 cancelText 应该为 null")
        void cancelTextShouldBeNullWhenNotSet() throws Exception {
            ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);

            Field cancelTextField = ChatConfirm.class.getDeclaredField("cancelText");
            cancelTextField.setAccessible(true);
            assertThat(cancelTextField.get(confirm)).isNull();
        }
    }
    
    // ========== Mock 测试：show 方法 ==========
    
    @Nested
    @DisplayName("show 方法 Mock 测试")
    class ShowMethodMockTests {
        
        @Test
        @DisplayName("show 方法应该发送三条消息")
        void showShouldSendThreeMessages() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<MessageUtils> messageUtilsMock = mockStatic(MessageUtils.class);
                 MockedStatic<ChatCallbackManager> callbackMock = mockStatic(ChatCallbackManager.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                lenient().when(mockUltiTools.i18n("OK")).thenReturn("OK");
                lenient().when(mockUltiTools.i18n("取消")).thenReturn("Cancel");
                
                callbackMock.when(() -> ChatCallbackManager.registerCallback(any()))
                    .thenReturn(UUID.randomUUID());
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Test Title", "Test Desc",
                    mockOnConfirm, mockOnCancel);
                
                confirm.show();

                // 验证发送了 3 条消息：标题、描述、按钮
                messageUtilsMock.verify(() -> MessageUtils.sendMessage(eq(mockPlayer), any(net.kyori.adventure.text.TextComponent.class)), times(3));
                assertThat(confirm).isNotNull();
            }
        }
        
        @Test
        @DisplayName("show 方法应该注册两个回调")
        void showShouldRegisterTwoCallbacks() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<MessageUtils> messageUtilsMock = mockStatic(MessageUtils.class);
                 MockedStatic<ChatCallbackManager> callbackMock = mockStatic(ChatCallbackManager.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                lenient().when(mockUltiTools.i18n("OK")).thenReturn("OK");
                lenient().when(mockUltiTools.i18n("取消")).thenReturn("Cancel");
                
                callbackMock.when(() -> ChatCallbackManager.registerCallback(any()))
                    .thenReturn(UUID.randomUUID());
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    mockOnConfirm, mockOnCancel);
                
                confirm.show();
                
                // 验证注册了两个回调（confirm 和 cancel）
                callbackMock.verify(() -> ChatCallbackManager.registerCallback(mockOnConfirm));
                callbackMock.verify(() -> ChatCallbackManager.registerCallback(mockOnCancel));
            }
        }
        
        @Test
        @DisplayName("使用自定义按钮文本的 show 方法")
        void showWithCustomButtonText() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<MessageUtils> messageUtilsMock = mockStatic(MessageUtils.class);
                 MockedStatic<ChatCallbackManager> callbackMock = mockStatic(ChatCallbackManager.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                // i18n 不应该被调用因为使用了自定义文本
                
                callbackMock.when(() -> ChatCallbackManager.registerCallback(any()))
                    .thenReturn(UUID.randomUUID());
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    "Yes", "No", mockOnConfirm, mockOnCancel);
                
                confirm.show();

                // 验证消息发送
                messageUtilsMock.verify(() -> MessageUtils.sendMessage(eq(mockPlayer), any(net.kyori.adventure.text.TextComponent.class)), times(3));
                assertNotNull(confirm);
            }
        }
    }
    
    // ========== getConfirmText/getCancelText Mock 测试 ==========
    
    @Nested
    @DisplayName("按钮文本获取 Mock 测试")
    class ButtonTextMockTests {
        
        @Test
        @DisplayName("getConfirmText 无自定义文本时调用 i18n")
        void getConfirmTextShouldCallI18nWhenNoCustomText() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.i18n("OK")).thenReturn("确定");
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    mockOnConfirm, mockOnCancel);
                
                String text = confirm.getConfirmText();
                
                assertThat(text).isEqualTo("确定");
                verify(mockUltiTools).i18n("OK");
            }
        }
        
        @Test
        @DisplayName("getCancelText 无自定义文本时调用 i18n")
        void getCancelTextShouldCallI18nWhenNoCustomText() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.i18n("取消")).thenReturn("取消操作");
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    mockOnConfirm, mockOnCancel);
                
                String text = confirm.getCancelText();
                
                assertThat(text).isEqualTo("取消操作");
                verify(mockUltiTools).i18n("取消");
            }
        }
        
        @Test
        @DisplayName("getConfirmText 有自定义文本时不调用 i18n")
        void getConfirmTextShouldNotCallI18nWhenCustomTextSet() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    "Accept", "Decline", mockOnConfirm, mockOnCancel);
                
                String text = confirm.getConfirmText();
                
                assertThat(text).isEqualTo("Accept");
                verify(mockUltiTools, never()).i18n(anyString());
            }
        }
        
        @Test
        @DisplayName("getCancelText 有自定义文本时不调用 i18n")
        void getCancelTextShouldNotCallI18nWhenCustomTextSet() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "Title", "Desc",
                    "Accept", "Decline", mockOnConfirm, mockOnCancel);
                
                String text = confirm.getCancelText();
                
                assertThat(text).isEqualTo("Decline");
                verify(mockUltiTools, never()).i18n(anyString());
            }
        }
    }
    
    // ========== Adventure 组件测试 ==========
    
    @Nested
    @DisplayName("Adventure 组件构建测试")
    class AdventureComponentTests {
        
        @Test
        @DisplayName("标题组件应该有粗体和金色")
        void titleComponentShouldHaveBoldAndGold() {
            // 验证 Adventure API 组件构建逻辑
            net.kyori.adventure.text.TextComponent titleText = Component
                    .text("Test Title")
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .color(net.kyori.adventure.text.format.TextColor.color(255, 222, 55));
            
            assertThat(titleText.content()).isEqualTo("Test Title");
            assertThat(titleText.decorations().get(net.kyori.adventure.text.format.TextDecoration.BOLD))
                .isEqualTo(net.kyori.adventure.text.format.TextDecoration.State.TRUE);
        }
        
        @Test
        @DisplayName("确认按钮应该是绿色")
        void confirmButtonShouldBeGreen() {
            net.kyori.adventure.text.format.TextColor green = 
                net.kyori.adventure.text.format.TextColor.color(0, 255, 0);
            
            assertThat(green.red()).isEqualTo(0);
            assertThat(green.green()).isEqualTo(255);
            assertThat(green.blue()).isEqualTo(0);
        }
        
        @Test
        @DisplayName("取消按钮应该是红色")
        void cancelButtonShouldBeRed() {
            net.kyori.adventure.text.format.TextColor red = 
                net.kyori.adventure.text.format.TextColor.color(255, 0, 0);
            
            assertThat(red.red()).isEqualTo(255);
            assertThat(red.green()).isEqualTo(0);
            assertThat(red.blue()).isEqualTo(0);
        }
        
        @Test
        @DisplayName("按钮应该有 ClickEvent")
        void buttonsShouldHaveClickEvent() {
            String callbackId = UUID.randomUUID().toString();
            net.kyori.adventure.text.event.ClickEvent clickEvent = 
                net.kyori.adventure.text.event.ClickEvent.runCommand("/ultitools_callback " + callbackId);
            
            assertThat(clickEvent.action())
                .isEqualTo(net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND);
            assertThat(clickEvent.value()).contains("/ultitools_callback");
            assertThat(clickEvent.value()).contains(callbackId);
        }
    }
    
    // ========== 集成场景测试 ==========
    
    @Nested
    @DisplayName("集成场景测试")
    class IntegrationScenarioTests {
        
        @Test
        @DisplayName("完整的确认对话框流程")
        void completeConfirmDialogFlow() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class);
                 MockedStatic<MessageUtils> messageUtilsMock = mockStatic(MessageUtils.class);
                 MockedStatic<ChatCallbackManager> callbackMock = mockStatic(ChatCallbackManager.class)) {
                
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.i18n("OK")).thenReturn("确定");
                when(mockUltiTools.i18n("取消")).thenReturn("取消");
                
                UUID confirmCallbackId = UUID.randomUUID();
                UUID cancelCallbackId = UUID.randomUUID();
                
                callbackMock.when(() -> ChatCallbackManager.registerCallback(mockOnConfirm))
                    .thenReturn(confirmCallbackId);
                callbackMock.when(() -> ChatCallbackManager.registerCallback(mockOnCancel))
                    .thenReturn(cancelCallbackId);
                
                ChatConfirm confirm = new ChatConfirm(mockPlayer, "删除确认", "确定要删除这个物品吗？",
                    mockOnConfirm, mockOnCancel);
                
                // 获取按钮文本
                assertThat(confirm.getConfirmText()).isEqualTo("确定");
                assertThat(confirm.getCancelText()).isEqualTo("取消");
                
                // 显示对话框
                confirm.show();
                
                // 验证交互
                messageUtilsMock.verify(() -> MessageUtils.sendMessage(eq(mockPlayer), any(net.kyori.adventure.text.TextComponent.class)), times(3));
            }
        }
    }
}
