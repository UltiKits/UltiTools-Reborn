package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.interfaces.VersionWrapper;

/**
 * Unit tests for BaseInventoryPage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseInventoryPage Tests")
class BaseInventoryPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private VersionWrapper mockVersionWrapper;

    @Mock
    private ItemStack mockGlass;

    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        lenient().when(mockUltiTools.getVersionWrapper()).thenReturn(mockVersionWrapper);
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(any(Colors.class))).thenReturn(mockGlass);
        lenient().when(mockGlass.getType()).thenReturn(Material.GRAY_STAINED_GLASS_PANE);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    /**
     * Test implementation of BaseInventoryPage
     */
    private static class TestInventoryPage extends BaseInventoryPage {
        private boolean setupContentCalled = false;
        private boolean afterSetupCalled = false;

        public TestInventoryPage(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }

        @Override
        protected void setupContent(InventoryOpenEvent event) {
            setupContentCalled = true;
        }

        @Override
        protected void afterSetup(InventoryOpenEvent event) {
            afterSetupCalled = true;
        }

        public boolean isSetupContentCalled() {
            return setupContentCalled;
        }

        public boolean isAfterSetupCalled() {
            return afterSetupCalled;
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create page with correct size")
        void shouldCreatePageWithCorrectSize() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test Title", 3);
            
            assertEquals(27, page.getSize()); // 3 rows * 9 slots
        }

        @Test
        @DisplayName("Should create page with 6 rows")
        void shouldCreatePageWith6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test Title", 6);
            
            assertEquals(54, page.getSize()); // 6 rows * 9 slots
        }
    }

    @Nested
    @DisplayName("Toolbar Tests")
    class ToolbarTests {

        @Test
        @DisplayName("Should show bottom toolbar by default")
        void shouldShowBottomToolbarByDefault() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            // Verify toolbar is shown by default (checked via content slots)
            int[] contentSlots = page.getContentSlots();
            assertEquals(18, contentSlots.length); // 3 rows - 1 row for toolbar = 2 rows = 18 slots
        }

        @Test
        @DisplayName("Should hide bottom toolbar when disabled")
        void shouldHideBottomToolbarWhenDisabled() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            page.setShowBottomToolbar(false);
            
            int[] contentSlots = page.getContentSlots();
            assertEquals(27, contentSlots.length); // All 3 rows = 27 slots
        }

        @Test
        @DisplayName("Should return this for method chaining")
        void shouldReturnThisForMethodChaining() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            BaseInventoryPage result = page.setShowBottomToolbar(false);
            
            assertSame(page, result);
        }
    }

    @Nested
    @DisplayName("Slot Calculation Tests")
    class SlotCalculationTests {

        @Test
        @DisplayName("Should calculate bottom center slot correctly for 3 rows")
        void shouldCalculateBottomCenterFor3Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            int centerSlot = page.getBottomCenterSlot();
            
            assertEquals(22, centerSlot); // 27 - 5 = 22
        }

        @Test
        @DisplayName("Should calculate bottom center slot correctly for 6 rows")
        void shouldCalculateBottomCenterFor6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            
            int centerSlot = page.getBottomCenterSlot();
            
            assertEquals(49, centerSlot); // 54 - 5 = 49
        }

        @Test
        @DisplayName("Should calculate slot from end correctly")
        void shouldCalculateSlotFromEnd() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            assertEquals(26, page.getSlotFromEnd(1)); // Last slot
            assertEquals(25, page.getSlotFromEnd(2)); // Second to last
            assertEquals(18, page.getSlotFromEnd(9)); // First slot of last row
        }
    }

    @Nested
    @DisplayName("Content Slots Tests")
    class ContentSlotsTests {

        @Test
        @DisplayName("Should return correct content slots with toolbar")
        void shouldReturnCorrectContentSlotsWithToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(18, slots.length);
            assertEquals(0, slots[0]);
            assertEquals(17, slots[17]);
        }

        @Test
        @DisplayName("Should return correct content slots without toolbar")
        void shouldReturnCorrectContentSlotsWithoutToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            page.setShowBottomToolbar(false);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(27, slots.length);
            assertEquals(0, slots[0]);
            assertEquals(26, slots[26]);
        }
    }
}
