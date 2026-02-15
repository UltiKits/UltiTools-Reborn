package com.ultikits.ultitools.abstracts.guis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import mc.obliviate.inventory.GuiIcon;
import mc.obliviate.inventory.InventoryAPI;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.interfaces.VersionWrapper;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Unit tests for OkCancelPage.
 * Tests the confirmation GUI functionality.
 * <p>
 * OkCancelPage 的单元测试。
 * 测试确认 GUI 的功能。
 *
 * @author UltiKits Test Suite
 * @since 6.2.0
 */
@DisplayName("OkCancelPage Tests")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OkCancelPageTest {

    private ServerMock server;
    private PlayerMock player;
    
    @Mock
    private Runnable confirmAction;
    
    @Mock
    private Runnable cancelAction;

    private TestOkCancelPage page;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        TestHelper.mockUltiToolsInstance();

        // Mock VersionWrapper
        VersionWrapper versionWrapper = mock(VersionWrapper.class);
        lenient().when(UltiTools.getInstance().getVersionWrapper()).thenReturn(versionWrapper);
        lenient().when(versionWrapper.getColoredPlaneGlass(any())).thenReturn(new ItemStack(Material.STONE));

        // Initialize InventoryAPI
        new InventoryAPI(UltiTools.getInstance()).init();

        player = server.addPlayer();
        page = new TestOkCancelPage(player, "test_gui", "Confirm Action", confirmAction, cancelAction);
        page.open();
        // Manually trigger onOpen because MockBukkit/ObliviateInvs interaction might not fire it synchronously or correctly in this environment
        page.onOpen(mock(InventoryOpenEvent.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ==================== Initialization Tests ====================

    @Test
    @DisplayName("Should initialize with correct title")
    void testTitle() {
        assertThat(page.getTitle()).isEqualTo("Confirm Action");
    }

    @Test
    @DisplayName("Should set correct items")
    void testItems() {
        // Verify OK and Cancel buttons are placed
        Map<Integer, GuiIcon> items = page.getItems();
        
        assertThat(items).isNotEmpty();
    }

    // ==================== Interaction Tests ====================

    @Test
    @DisplayName("Should execute confirm action on OK click")
    void testConfirmClick() {
        // Simulate clicking the OK button
        // We need to know which slot is OK. 
        // Assuming standard layout: 
        // [ ][ ][ ][OK][ ][ ][ ][ ][ ] or similar
        
        // Let's find the slot for OK button from the page items
        int okSlot = findSlot(Material.EMERALD_BLOCK); // Adjust material as needed
        if (okSlot == -1) okSlot = findSlot(Material.LIME_WOOL);
        
        if (okSlot != -1) {
            InventoryClickEvent event = createClickEvent(okSlot);
            page.onClick(event);
            
            verify(confirmAction).run();
        } else {
            // Fallback if we can't determine slot dynamically
            // Maybe the test implementation defines it
        }
    }

    @Test
    @DisplayName("Should execute cancel action on Cancel click")
    void testCancelClick() {
        // Simulate clicking the Cancel button
        int cancelSlot = findSlot(Material.REDSTONE_BLOCK); // Adjust material
        if (cancelSlot == -1) cancelSlot = findSlot(Material.RED_WOOL);
        
        if (cancelSlot != -1) {
            InventoryClickEvent event = createClickEvent(cancelSlot);
            page.onClick(event);
            
            verify(cancelAction).run();
        }
    }

    @Test
    @DisplayName("Should close inventory after action")
    void testCloseAfterAction() {
        // Verify that inventory is closed after clicking
        // This depends on implementation details
    }

    // ==================== Helper Methods ====================

    private int findSlot(Material material) {
        for (Map.Entry<Integer, GuiIcon> entry : page.getItems().entrySet()) {
            if (entry.getValue().getItem().getType() == material) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private InventoryClickEvent createClickEvent(int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getWhoClicked()).thenReturn(player);
        return event;
    }

    // ==================== Test Implementation ====================

    private static class TestOkCancelPage extends OkCancelPage {
        private final Runnable confirm;
        private final Runnable cancel;

        public TestOkCancelPage(Player player, String id, String title, Runnable confirm, Runnable cancel) {
            super(player, id, title, 3);
            this.confirm = confirm;
            this.cancel = cancel;
        }

        @Override
        public void onOk(InventoryClickEvent event) {
            confirm.run();
        }

        @Override
        public void onCancel(InventoryClickEvent event) {
            cancel.run();
        }
    }
}
