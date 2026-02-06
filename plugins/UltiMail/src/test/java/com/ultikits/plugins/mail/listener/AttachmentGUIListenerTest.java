package com.ultikits.plugins.mail.listener;

import com.ultikits.plugins.mail.gui.AttachmentSelectorPage;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AttachmentGUIListener.
 * <p>
 * Uses pure Mockito (no MockBukkit) for maximum compatibility.
 */
@DisplayName("AttachmentGUIListener 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AttachmentGUIListenerTest {

    private AttachmentGUIListener listener;

    @Mock
    private AttachmentSelectorPage mockPage;

    @Mock
    private InventoryClickEvent mockClickEvent;

    @Mock
    private InventoryDragEvent mockDragEvent;

    @Mock
    private InventoryCloseEvent mockCloseEvent;

    @Mock
    private InventoryView mockView;

    @Mock
    private Inventory mockInventory;

    @Mock
    private Inventory otherInventory;

    @BeforeEach
    void setUp() throws Exception {
        TestHelper.mockUltiMailInstance();
        listener = new AttachmentGUIListener();
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
    }

    // ==================== Content Area Click Tests ====================

    @Nested
    @DisplayName("内容区域点击测试")
    class ContentAreaClickTests {

        @Test
        @DisplayName("内容区域的点击应该允许自由操作")
        void shouldAllowClickInContentArea() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.getRawSlot()).thenReturn(0);

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent, never()).setCancelled(true);
        }

        @Test
        @DisplayName("内容区域中间槽位应该允许点击")
        void shouldAllowClickInMiddleContentSlot() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.getRawSlot()).thenReturn(22);

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent, never()).setCancelled(true);
        }

        @Test
        @DisplayName("内容区域最后一个槽位应该允许点击")
        void shouldAllowClickInLastContentSlot() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.getRawSlot()).thenReturn(44);

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent, never()).setCancelled(true);
        }
    }

    // ==================== Toolbar Area Click Tests ====================

    @Nested
    @DisplayName("工具栏区域点击测试")
    class ToolbarAreaClickTests {

        @Test
        @DisplayName("工具栏区域的点击应该被处理")
        void shouldHandleToolbarClick() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.getRawSlot()).thenReturn(45);

            listener.onInventoryClick(mockClickEvent);
            // Toolbar clicks are handled by base class, not cancelled here
        }
    }

    // ==================== Shift-Click Tests ====================

    @Nested
    @DisplayName("Shift-Click 测试")
    class ShiftClickTests {

        @Test
        @DisplayName("从玩家背包 Shift-Click 到工具栏区域应该被阻止")
        void shouldBlockShiftClickToToolbar() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.isShiftClick()).thenReturn(true);
            when(mockClickEvent.getRawSlot()).thenReturn(54); // Player inventory
            when(mockClickEvent.getView()).thenReturn(mockView);
            when(mockView.getTopInventory()).thenReturn(mockInventory);
            when(mockInventory.firstEmpty()).thenReturn(45); // Target is toolbar

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent).setCancelled(true);
        }

        @Test
        @DisplayName("从玩家背包 Shift-Click 到内容区域应该允许")
        void shouldAllowShiftClickToContentArea() {
            when(mockClickEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockClickEvent.isShiftClick()).thenReturn(true);
            when(mockClickEvent.getRawSlot()).thenReturn(54); // Player inventory
            when(mockClickEvent.getView()).thenReturn(mockView);
            when(mockView.getTopInventory()).thenReturn(mockInventory);
            when(mockInventory.firstEmpty()).thenReturn(10); // Target is content

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent, never()).setCancelled(true);
        }
    }

    // ==================== Non-AttachmentGUI Tests ====================

    @Nested
    @DisplayName("非附件选择器 GUI 测试")
    class NonAttachmentGUITests {

        @Test
        @DisplayName("非附件选择器的点击应该被忽略")
        void shouldIgnoreClickInOtherGUI() {
            when(mockClickEvent.getInventory()).thenReturn(otherInventory);
            when(otherInventory.getHolder()).thenReturn(null);

            listener.onInventoryClick(mockClickEvent);

            verify(mockClickEvent, never()).setCancelled(anyBoolean());
        }

        @Test
        @DisplayName("非附件选择器的拖拽应该被忽略")
        void shouldIgnoreDragInOtherGUI() {
            when(mockDragEvent.getInventory()).thenReturn(otherInventory);
            when(otherInventory.getHolder()).thenReturn(null);

            listener.onInventoryDrag(mockDragEvent);

            verify(mockDragEvent, never()).setCancelled(anyBoolean());
        }

        @Test
        @DisplayName("非附件选择器的关闭应该被忽略")
        void shouldIgnoreCloseInOtherGUI() {
            when(mockCloseEvent.getInventory()).thenReturn(otherInventory);
            when(otherInventory.getHolder()).thenReturn(null);

            listener.onInventoryClose(mockCloseEvent);

            verify(mockPage, never()).returnAllItems();
        }
    }

    // ==================== Drag Event Tests ====================

    @Nested
    @DisplayName("拖拽事件测试")
    class DragEventTests {

        @Test
        @DisplayName("拖拽到内容区域应该允许")
        void shouldAllowDragInContentArea() {
            when(mockDragEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            Set<Integer> slots = new HashSet<>();
            slots.add(0);
            slots.add(10);
            when(mockDragEvent.getRawSlots()).thenReturn(slots);

            listener.onInventoryDrag(mockDragEvent);

            verify(mockDragEvent, never()).setCancelled(true);
        }

        @Test
        @DisplayName("拖拽到工具栏区域应该被阻止")
        void shouldBlockDragInToolbarArea() {
            when(mockDragEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            Set<Integer> slots = new HashSet<>();
            slots.add(45); // Toolbar slot
            when(mockDragEvent.getRawSlots()).thenReturn(slots);
            when(mockDragEvent.getView()).thenReturn(mockView);
            when(mockView.getTopInventory()).thenReturn(mockInventory);
            when(mockInventory.getSize()).thenReturn(54);

            listener.onInventoryDrag(mockDragEvent);

            verify(mockDragEvent).setCancelled(true);
        }

        @Test
        @DisplayName("拖拽跨越内容和工具栏应该被阻止")
        void shouldBlockDragAcrossContentAndToolbar() {
            when(mockDragEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            Set<Integer> slots = new HashSet<>();
            slots.add(40);
            slots.add(45);
            when(mockDragEvent.getRawSlots()).thenReturn(slots);
            when(mockDragEvent.getView()).thenReturn(mockView);
            when(mockView.getTopInventory()).thenReturn(mockInventory);
            when(mockInventory.getSize()).thenReturn(54);

            listener.onInventoryDrag(mockDragEvent);

            verify(mockDragEvent).setCancelled(true);
        }

        @Test
        @DisplayName("拖拽到玩家背包应该允许")
        void shouldAllowDragInPlayerInventory() {
            when(mockDragEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            Set<Integer> slots = new HashSet<>();
            slots.add(54);
            slots.add(55);
            when(mockDragEvent.getRawSlots()).thenReturn(slots);
            when(mockDragEvent.getView()).thenReturn(mockView);
            when(mockView.getTopInventory()).thenReturn(mockInventory);

            listener.onInventoryDrag(mockDragEvent);

            verify(mockDragEvent, never()).setCancelled(true);
        }
    }

    // ==================== GUI Close Tests ====================

    @Nested
    @DisplayName("GUI 关闭测试")
    class GUICloseTests {

        @Test
        @DisplayName("未确认时关闭应该归还物品")
        void shouldReturnItemsWhenNotConfirmed() {
            when(mockCloseEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockPage.isConfirmed()).thenReturn(false);

            listener.onInventoryClose(mockCloseEvent);

            verify(mockPage).returnAllItems();
        }

        @Test
        @DisplayName("已确认时关闭不应归还物品")
        void shouldNotReturnItemsWhenConfirmed() {
            when(mockCloseEvent.getInventory()).thenReturn(mockInventory);
            when(mockInventory.getHolder()).thenReturn(mockPage);
            when(mockPage.isConfirmed()).thenReturn(true);

            listener.onInventoryClose(mockCloseEvent);

            verify(mockPage, never()).returnAllItems();
        }
    }

    // ==================== Annotation Tests ====================

    @Nested
    @DisplayName("注解配置测试")
    class AnnotationTests {

        @Test
        @DisplayName("类应该有 @EventListener 注解")
        void shouldHaveEventListenerAnnotation() {
            assertThat(AttachmentGUIListener.class.isAnnotationPresent(
                com.ultikits.ultitools.annotations.EventListener.class
            )).isTrue();
        }

        @Test
        @DisplayName("应该实现 Bukkit Listener 接口")
        void shouldImplementListener() {
            assertThat(org.bukkit.event.Listener.class)
                .isAssignableFrom(AttachmentGUIListener.class);
        }

        @Test
        @DisplayName("onInventoryClick 应该有 @EventHandler 注解")
        void shouldHaveEventHandlerOnClick() throws Exception {
            assertThat(AttachmentGUIListener.class
                .getMethod("onInventoryClick", InventoryClickEvent.class)
                .isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }

        @Test
        @DisplayName("onInventoryDrag 应该有 @EventHandler 注解")
        void shouldHaveEventHandlerOnDrag() throws Exception {
            assertThat(AttachmentGUIListener.class
                .getMethod("onInventoryDrag", InventoryDragEvent.class)
                .isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }

        @Test
        @DisplayName("onInventoryClose 应该有 @EventHandler 注解")
        void shouldHaveEventHandlerOnClose() throws Exception {
            assertThat(AttachmentGUIListener.class
                .getMethod("onInventoryClose", InventoryCloseEvent.class)
                .isAnnotationPresent(org.bukkit.event.EventHandler.class)).isTrue();
        }

        @Test
        @DisplayName("onInventoryClick 应该使用 HIGH 优先级")
        void shouldUseHighPriorityForClick() throws Exception {
            org.bukkit.event.EventHandler annotation = AttachmentGUIListener.class
                .getMethod("onInventoryClick", InventoryClickEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);

            assertThat(annotation.priority()).isEqualTo(EventPriority.HIGH);
        }

        @Test
        @DisplayName("onInventoryClose 应该使用 MONITOR 优先级")
        void shouldUseMonitorPriorityForClose() throws Exception {
            org.bukkit.event.EventHandler annotation = AttachmentGUIListener.class
                .getMethod("onInventoryClose", InventoryCloseEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);

            assertThat(annotation.priority()).isEqualTo(EventPriority.MONITOR);
        }
    }

    // ==================== Content Size Tests ====================

    @Nested
    @DisplayName("内容区域大小测试")
    class ContentSizeTests {

        @Test
        @DisplayName("内容区域应该是 45 个槽位")
        void shouldHaveCorrectContentSize() {
            assertThat(AttachmentSelectorPage.getContentSize()).isEqualTo(45);
        }

        @Test
        @DisplayName("内容区域应该占用前 5 行")
        void shouldOccupyFirst5Rows() {
            assertThat(AttachmentSelectorPage.getContentSize()).isEqualTo(5 * 9);
        }
    }
}
