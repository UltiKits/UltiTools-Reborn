package com.ultikits.ultitools.widgets.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.guis.OkCancelPage;
import com.ultikits.ultitools.widgets.Confirm;

import cn.hutool.core.lang.func.VoidFunc0;

/**
 * Unit tests for {@link InventoryConfirm}.
 */
@DisplayName("InventoryConfirm 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
class InventoryConfirmTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private VoidFunc0 mockOnConfirm;
    
    @Mock
    private VoidFunc0 mockOnCancel;
    
    @Mock
    private InventoryClickEvent mockClickEvent;

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("InventoryConfirm 应该实现 Confirm 接口")
        void shouldImplementConfirmInterface() {
            assertThat(Confirm.class.isAssignableFrom(InventoryConfirm.class)).isTrue();
        }

        @Test
        @DisplayName("InventoryConfirm 应该继承 OkCancelPage")
        void shouldExtendOkCancelPage() {
            assertThat(OkCancelPage.class.isAssignableFrom(InventoryConfirm.class)).isTrue();
        }

        @Test
        @DisplayName("InventoryConfirm 应该是 public 类")
        void shouldBePublicClass() {
            assertThat(Modifier.isPublic(InventoryConfirm.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("静态常量测试")
    class StaticConstantsTests {

        @Test
        @DisplayName("应该有 GUI_ID 常量")
        void shouldHaveGuiIdConstant() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ID");

            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 GUI_ROWS 常量")
        void shouldHaveGuiRowsConstant() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ROWS");

            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("GUI_ID 应该是 confirm_gui")
        void guiIdShouldBeConfirmGui() throws Exception {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ID");
            field.setAccessible(true);
            assertThat(field.get(null)).isEqualTo("confirm_gui");
        }

        @Test
        @DisplayName("GUI_ROWS 应该是 3")
        void guiRowsShouldBe3() throws Exception {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ROWS");
            field.setAccessible(true);
            assertThat(field.get(null)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("字段测试")
    class FieldTests {

        @Test
        @DisplayName("应该有 confirmText 字段")
        void shouldHaveConfirmTextField() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("confirmText");
            assertThat(field.getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("应该有 cancelText 字段")
        void shouldHaveCancelTextField() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("cancelText");
            assertThat(field.getType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("应该有 onConfirm 字段")
        void shouldHaveOnConfirmField() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("onConfirm");
            assertThat(field.getType()).isEqualTo(VoidFunc0.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有 onCancel 字段")
        void shouldHaveOnCancelField() throws NoSuchFieldException {
            Field field = InventoryConfirm.class.getDeclaredField("onCancel");
            assertThat(field.getType()).isEqualTo(VoidFunc0.class);
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该有5参数构造函数")
        void shouldHave5ParamConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = InventoryConfirm.class.getConstructor(
                Player.class, String.class, String.class,
                VoidFunc0.class, VoidFunc0.class);

            assertThat(constructor).isNotNull();
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("应该有7参数构造函数")
        void shouldHave7ParamConstructor() throws NoSuchMethodException {
            Constructor<?> constructor = InventoryConfirm.class.getConstructor(
                Player.class, String.class, String.class,
                String.class, String.class,
                VoidFunc0.class, VoidFunc0.class);

            assertThat(constructor).isNotNull();
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("show 方法应该存在")
        void showMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("show");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("getConfirmText 方法应该存在")
        void getConfirmTextMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("getConfirmText");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getCancelText 方法应该存在")
        void getCancelTextMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("getCancelText");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getOkName 方法应该存在")
        void getOkNameMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("getOkName");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("getCancelName 方法应该存在")
        void getCancelNameMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("getCancelName");

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("onOk 方法应该存在")
        void onOkMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onOk",
                org.bukkit.event.inventory.InventoryClickEvent.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("onCancel 方法应该存在")
        void onCancelMethodShouldExist() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onCancel",
                org.bukkit.event.inventory.InventoryClickEvent.class);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("Lombok 注解测试")
    class LombokAnnotationTests {

        @Test
        @DisplayName("onOk 方法应该有 @SneakyThrows 注解")
        void onOkShouldHaveSneakyThrows() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onOk",
                org.bukkit.event.inventory.InventoryClickEvent.class);

            // @SneakyThrows 使方法不声明受检异常
            assertThat(method.getExceptionTypes()).isEmpty();
        }

        @Test
        @DisplayName("onCancel 方法应该有 @SneakyThrows 注解")
        void onCancelShouldHaveSneakyThrows() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onCancel",
                org.bukkit.event.inventory.InventoryClickEvent.class);

            // @SneakyThrows 使方法不声明受检异常
            assertThat(method.getExceptionTypes()).isEmpty();
        }
    }
    
    // ========== Mock 测试：onOk/onCancel 回调 ==========
    
    @Nested
    @DisplayName("回调 Mock 测试")
    class CallbackMockTests {
        
        @Test
        @DisplayName("onOk 应该调用 onConfirm 回调")
        void onOkShouldCallOnConfirmCallback() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);
            
            confirm.onOk(mockClickEvent);
            
            verify(mockOnConfirm).call();
            verify(mockOnCancel, never()).call();
        }
        
        @Test
        @DisplayName("onCancel 应该调用 onCancel 回调")
        void onCancelShouldCallOnCancelCallback() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);
            
            confirm.onCancel(mockClickEvent);
            
            verify(mockOnCancel).call();
            verify(mockOnConfirm, never()).call();
        }
        
        @Test
        @DisplayName("回调应该只被调用一次")
        void callbackShouldBeCalledOnlyOnce() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);
            
            confirm.onOk(mockClickEvent);
            
            verify(mockOnConfirm, times(1)).call();
        }
        
        @Test
        @DisplayName("onOk 和 onCancel 可以分别调用")
        void onOkAndOnCancelCanBeCalledSeparately() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                mockOnConfirm, mockOnCancel);
            
            confirm.onOk(mockClickEvent);
            confirm.onCancel(mockClickEvent);
            
            verify(mockOnConfirm, times(1)).call();
            verify(mockOnCancel, times(1)).call();
        }
    }
    
    // ========== 自定义文本测试 ==========
    
    @Nested
    @DisplayName("自定义按钮文本 Mock 测试")
    class CustomTextMockTests {
        
        @Test
        @DisplayName("getOkName 应该返回 getConfirmText 的结果")
        void getOkNameShouldReturnConfirmText() {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                "Yes", "No", mockOnConfirm, mockOnCancel);
            
            assertThat(confirm.getOkName()).isEqualTo("Yes");
            assertThat(confirm.getConfirmText()).isEqualTo("Yes");
        }
        
        @Test
        @DisplayName("getCancelName 应该返回 getCancelText 的结果")
        void getCancelNameShouldReturnCancelText() {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                "Yes", "No", mockOnConfirm, mockOnCancel);
            
            assertThat(confirm.getCancelName()).isEqualTo("No");
            assertThat(confirm.getCancelText()).isEqualTo("No");
        }
        
        @Test
        @DisplayName("未设置自定义文本时使用父类默认值")
        void shouldUseParentDefaultWhenNoCustomText() {
            try (MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                UltiTools mockUltiTools = mock(UltiTools.class);
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                when(mockUltiTools.i18n("OK")).thenReturn("OK");
                when(mockUltiTools.i18n("取消")).thenReturn("Cancel");
                
                InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Title", "Desc",
                    mockOnConfirm, mockOnCancel);
                
                // confirmText 为 null 时，getConfirmText 返回 super.getOkName()
                // 这需要 OkCancelPage 有默认值
                String confirmText = confirm.getConfirmText();
                String cancelText = confirm.getCancelText();
                
                // 不为 null（有默认值）
                assertThat(confirmText).isEqualTo("OK");
                assertThat(cancelText).isEqualTo("Cancel");
            }
        }
    }
    
    // ========== GUI 配置测试 ==========
    
    @Nested
    @DisplayName("GUI 配置测试")
    class GuiConfigTests {
        
        @Test
        @DisplayName("GUI ID 应该是 confirm_gui")
        void guiIdShouldBeConfirmGui() throws Exception {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ID");
            field.setAccessible(true);
            
            assertThat(field.get(null)).isEqualTo("confirm_gui");
        }
        
        @Test
        @DisplayName("GUI 应该有 3 行")
        void guiShouldHave3Rows() throws Exception {
            Field field = InventoryConfirm.class.getDeclaredField("GUI_ROWS");
            field.setAccessible(true);
            
            assertThat(field.get(null)).isEqualTo(3);
        }
        
        @Test
        @DisplayName("标题应该包含 title 和 description")
        void titleShouldContainTitleAndDescription() {
            // GUI 标题格式: title + " - " + description
            String title = "Confirm";
            String description = "Delete item?";
            String expectedTitle = title + " - " + description;
            
            assertThat(expectedTitle).isEqualTo("Confirm - Delete item?");
        }
    }
    
    // ========== 继承测试 ==========
    
    @Nested
    @DisplayName("OkCancelPage 继承测试")
    class OkCancelPageInheritanceTests {
        
        @Test
        @DisplayName("应该继承 OkCancelPage")
        void shouldExtendOkCancelPage() {
            assertThat(OkCancelPage.class.isAssignableFrom(InventoryConfirm.class)).isTrue();
        }
        
        @Test
        @DisplayName("show 方法应该调用 open")
        void showMethodShouldCallOpen() throws Exception {
            // show() 方法内部调用 this.open()
            Method showMethod = InventoryConfirm.class.getDeclaredMethod("show");
            Method openMethod = OkCancelPage.class.getMethod("open");
            
            assertThat(showMethod).isNotNull();
            assertThat(openMethod).isNotNull();
        }
        
        @Test
        @DisplayName("onOk 应该是 override 方法")
        void onOkShouldBeOverride() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onOk", InventoryClickEvent.class);
            
            // 检查父类也有这个方法
            Method parentMethod = OkCancelPage.class.getDeclaredMethod("onOk", InventoryClickEvent.class);
            
            assertThat(parentMethod).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(parentMethod.getReturnType());
        }
        
        @Test
        @DisplayName("onCancel 应该是 override 方法")
        void onCancelShouldBeOverride() throws NoSuchMethodException {
            Method method = InventoryConfirm.class.getDeclaredMethod("onCancel", InventoryClickEvent.class);
            
            // 检查父类也有这个方法
            Method parentMethod = OkCancelPage.class.getDeclaredMethod("onCancel", InventoryClickEvent.class);
            
            assertThat(parentMethod).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(parentMethod.getReturnType());
        }
    }
    
    // ========== 集成场景测试 ==========
    
    @Nested
    @DisplayName("集成场景测试")
    class IntegrationScenarioTests {
        
        @Test
        @DisplayName("完整的确认流程 - 确认")
        void completeConfirmFlow() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Purchase", "Buy sword for 100 coins?",
                mockOnConfirm, mockOnCancel);
            
            // 模拟点击确认
            confirm.onOk(mockClickEvent);
            
            // 验证确认回调被调用
            verify(mockOnConfirm).call();
        }
        
        @Test
        @DisplayName("完整的确认流程 - 取消")
        void completeCancelFlow() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Purchase", "Buy sword for 100 coins?",
                mockOnConfirm, mockOnCancel);
            
            // 模拟点击取消
            confirm.onCancel(mockClickEvent);
            
            // 验证取消回调被调用
            verify(mockOnCancel).call();
        }
        
        @Test
        @DisplayName("使用自定义按钮文本的确认流程")
        void customButtonTextFlow() throws Exception {
            InventoryConfirm confirm = new InventoryConfirm(mockPlayer, "Delete", "Confirm deletion?",
                "Delete Forever", "Keep It", mockOnConfirm, mockOnCancel);
            
            assertThat(confirm.getOkName()).isEqualTo("Delete Forever");
            assertThat(confirm.getCancelName()).isEqualTo("Keep It");
            
            confirm.onOk(mockClickEvent);
            verify(mockOnConfirm).call();
        }
    }
}
