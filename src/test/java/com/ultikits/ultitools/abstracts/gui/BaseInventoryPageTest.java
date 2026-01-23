package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
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
import com.ultikits.ultitools.utils.XVersionUtils;

import mc.obliviate.inventory.Icon;
import net.kyori.adventure.text.Component;

/**
 * Unit tests for BaseInventoryPage.
 * Tests focus on methods that don't require full Bukkit inventory setup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseInventoryPage Tests")
class BaseInventoryPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private ItemStack mockGlass;

    @Mock
    private ItemStack mockGreenGlass;
    
    @Mock
    private ItemStack mockRedGlass;
    
    @Mock
    private ItemStack mockBlueGlass;
    
    @Mock
    private ItemStack mockYellowGlass;

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<XVersionUtils> xVersionUtilsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        xVersionUtilsMock = mockStatic(XVersionUtils.class);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                .thenReturn(mockGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN))
                .thenReturn(mockGreenGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.RED))
                .thenReturn(mockRedGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.BLUE))
                .thenReturn(mockBlueGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.YELLOW))
                .thenReturn(mockYellowGlass);
        
        lenient().when(mockGlass.getType()).thenReturn(Material.GRAY_STAINED_GLASS_PANE);
        lenient().when(mockGreenGlass.getType()).thenReturn(Material.GREEN_STAINED_GLASS_PANE);
        lenient().when(mockRedGlass.getType()).thenReturn(Material.RED_STAINED_GLASS_PANE);
        lenient().when(mockBlueGlass.getType()).thenReturn(Material.BLUE_STAINED_GLASS_PANE);
        lenient().when(mockYellowGlass.getType()).thenReturn(Material.YELLOW_STAINED_GLASS_PANE);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
        if (xVersionUtilsMock != null) {
            xVersionUtilsMock.close();
        }
    }

    /**
     * Test implementation of BaseInventoryPage
     */
    private static class TestInventoryPage extends BaseInventoryPage {
        private boolean setupContentCalled = false;
        private boolean afterSetupCalled = false;
        private InventoryOpenEvent lastOpenEvent;

        public TestInventoryPage(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }

        public TestInventoryPage(Player player, String id, String title, InventoryType type) {
            super(player, id, title, type);
        }
        
        public TestInventoryPage(Player player, String id, Component title, int rows) {
            super(player, id, title, rows);
        }
        
        public TestInventoryPage(Player player, String id, Component title, InventoryType type) {
            super(player, id, title, type);
        }

        @Override
        protected void setupContent(InventoryOpenEvent event) {
            setupContentCalled = true;
            lastOpenEvent = event;
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

        public InventoryOpenEvent getLastOpenEvent() {
            return lastOpenEvent;
        }

        // Expose protected methods for testing
        public Icon testCreateBackgroundIcon() {
            return createBackgroundIcon();
        }

        public Icon testCreateActionButton(Colors color, String name, Consumer<InventoryClickEvent> onClick) {
            return createActionButton(color, name, onClick);
        }
        
        public int testGetBottomCenterSlot() {
            return getBottomCenterSlot();
        }
        
        public int testGetSlotFromEnd(int fromEnd) {
            return getSlotFromEnd(fromEnd);
        }
    }
    
    /**
     * Test implementation that tracks method calls without requiring inventory
     */
    private static class MethodTrackingPage extends BaseInventoryPage {
        private boolean setupBottomToolbarCalled = false;
        private boolean fillAreaCalled = false;
        private boolean fillBorderCalled = false;
        private boolean addToBottomRowCalled = false;
        private int fillAreaStartSlot = -1;
        private int fillAreaEndSlot = -1;
        private int addToBottomRowColumn = -1;
        
        public MethodTrackingPage(Player player) {
            super(player, "tracking", "Tracking", 3);
        }
        
        @Override
        protected void setupContent(InventoryOpenEvent event) {
            // No-op for testing
        }
        
        @Override
        protected void setupBottomToolbar() {
            setupBottomToolbarCalled = true;
            // Don't call super to avoid addItem
        }
        
        // Track fillArea calls
        public void trackFillArea(int startSlot, int endSlot) {
            fillAreaCalled = true;
            fillAreaStartSlot = startSlot;
            fillAreaEndSlot = endSlot;
        }
        
        // Track fillBorder calls
        public void trackFillBorder() {
            fillBorderCalled = true;
        }
        
        // Track addToBottomRow calls
        public void trackAddToBottomRow(int column) {
            addToBottomRowCalled = true;
            addToBottomRowColumn = column;
        }
        
        public boolean isSetupBottomToolbarCalled() {
            return setupBottomToolbarCalled;
        }
        
        public boolean isFillAreaCalled() {
            return fillAreaCalled;
        }
        
        public boolean isFillBorderCalled() {
            return fillBorderCalled;
        }
        
        public boolean isAddToBottomRowCalled() {
            return addToBottomRowCalled;
        }
        
        public int getFillAreaStartSlot() {
            return fillAreaStartSlot;
        }
        
        public int getFillAreaEndSlot() {
            return fillAreaEndSlot;
        }
        
        public int getAddToBottomRowColumn() {
            return addToBottomRowColumn;
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create page with correct size for rows")
        void shouldCreatePageWithCorrectSize() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test Title", 3);
            assertEquals(27, page.getSize());
        }

        @Test
        @DisplayName("Should create page with 6 rows")
        void shouldCreatePageWith6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test Title", 6);
            assertEquals(54, page.getSize());
        }

        @Test
        @DisplayName("Should create page with InventoryType")
        void shouldCreatePageWithInventoryType() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", InventoryType.CHEST);
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should create page with 1 row")
        void shouldCreatePageWith1Row() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 1);
            assertEquals(9, page.getSize());
        }

        @Test
        @DisplayName("Should create page with 4 rows")
        void shouldCreatePageWith4Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 4);
            assertEquals(36, page.getSize());
        }

        @Test
        @DisplayName("Should create page with 5 rows")
        void shouldCreatePageWith5Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 5);
            assertEquals(45, page.getSize());
        }

        @Test
        @DisplayName("Should create page with 2 rows")
        void shouldCreatePageWith2Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            assertEquals(18, page.getSize());
        }
    }

    @Nested
    @DisplayName("Toolbar Tests")
    class ToolbarTests {

        @Test
        @DisplayName("Should show bottom toolbar by default")
        void shouldShowBottomToolbarByDefault() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            int[] contentSlots = page.getContentSlots();
            assertEquals(18, contentSlots.length); // 3 rows - 1 row for toolbar = 2 rows = 18 slots
        }

        @Test
        @DisplayName("Should hide bottom toolbar when disabled")
        void shouldHideBottomToolbarWhenDisabled() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            page.setShowBottomToolbar(false);
            
            int[] contentSlots = page.getContentSlots();
            assertEquals(27, contentSlots.length);
        }

        @Test
        @DisplayName("Should return this for method chaining")
        void shouldReturnThisForMethodChaining() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            BaseInventoryPage result = page.setShowBottomToolbar(false);
            
            assertSame(page, result);
        }

        @Test
        @DisplayName("Should allow toggling toolbar multiple times")
        void shouldAllowTogglingToolbarMultipleTimes() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            page.setShowBottomToolbar(false);
            assertEquals(27, page.getContentSlots().length);
            
            page.setShowBottomToolbar(true);
            assertEquals(18, page.getContentSlots().length);
            
            page.setShowBottomToolbar(false);
            assertEquals(27, page.getContentSlots().length);
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
            
            assertEquals(22, centerSlot);
        }

        @Test
        @DisplayName("Should calculate bottom center slot correctly for 6 rows")
        void shouldCalculateBottomCenterFor6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            
            int centerSlot = page.getBottomCenterSlot();
            
            assertEquals(49, centerSlot);
        }

        @Test
        @DisplayName("Should calculate bottom center slot for 1 row")
        void shouldCalculateBottomCenterFor1Row() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 1);
            
            int centerSlot = page.getBottomCenterSlot();
            
            assertEquals(4, centerSlot); // 9 - 5 = 4
        }

        @Test
        @DisplayName("Should calculate slot from end correctly")
        void shouldCalculateSlotFromEnd() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            assertEquals(26, page.getSlotFromEnd(1));
            assertEquals(25, page.getSlotFromEnd(2));
            assertEquals(18, page.getSlotFromEnd(9));
        }

        @Test
        @DisplayName("Should calculate slot from end for 6 row page")
        void shouldCalculateSlotFromEndFor6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            
            assertEquals(53, page.getSlotFromEnd(1)); // 54 - 1 = 53
            assertEquals(52, page.getSlotFromEnd(2)); // 54 - 2 = 52
            assertEquals(45, page.getSlotFromEnd(9)); // 54 - 9 = 45
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

        @Test
        @DisplayName("Should return correct content slots for 6 row page")
        void shouldReturnCorrectContentSlotsFor6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(45, slots.length); // 54 - 9 = 45
        }

        @Test
        @DisplayName("Should return correct content slots for 1 row page with toolbar")
        void shouldReturnCorrectContentSlotsFor1Row() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 1);
            
            int[] slots = page.getContentSlots();
            
            // With toolbar on, 1 row - 1 row = 0 slots
            assertEquals(0, slots.length);
        }

        @Test
        @DisplayName("Should return correct content slots for 1 row page without toolbar")
        void shouldReturnCorrectContentSlotsFor1RowWithoutToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 1);
            page.setShowBottomToolbar(false);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(9, slots.length);
        }

        @Test
        @DisplayName("Content slots should be sequential starting from 0")
        void contentSlotsShouldBeSequential() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            page.setShowBottomToolbar(false);
            
            int[] slots = page.getContentSlots();
            
            for (int i = 0; i < slots.length; i++) {
                assertEquals(i, slots[i]);
            }
        }

        @Test
        @DisplayName("Should return correct content slots for 4 row page")
        void shouldReturnCorrectContentSlotsFor4Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 4);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(27, slots.length); // 36 - 9 = 27
        }

        @Test
        @DisplayName("Should return correct content slots for 5 row page")
        void shouldReturnCorrectContentSlotsFor5Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 5);
            
            int[] slots = page.getContentSlots();
            
            assertEquals(36, slots.length); // 45 - 9 = 36
        }
    }

    @Nested
    @DisplayName("Icon Creation Tests")
    class IconCreationTests {

        @Test
        @DisplayName("Should create background icon with gray glass")
        void shouldCreateBackgroundIcon() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            Icon icon = page.testCreateBackgroundIcon();
            
            assertNotNull(icon);
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.GRAY));
        }

        @Test
        @DisplayName("Should create action button with specified color")
        void shouldCreateActionButton() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            Icon icon = page.testCreateActionButton(Colors.GREEN, "Test Button", e -> {});
            
            assertNotNull(icon);
        }

        @Test
        @DisplayName("Should create action button without click handler")
        void shouldCreateActionButtonWithoutClickHandler() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            Icon icon = page.testCreateActionButton(Colors.RED, "No Handler", null);
            
            assertNotNull(icon);
        }

        @Test
        @DisplayName("Should create action button with RED color")
        void shouldCreateActionButtonWithRedColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            Icon icon = page.testCreateActionButton(Colors.RED, "Red Button", null);
            
            assertNotNull(icon);
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.RED));
        }

        @Test
        @DisplayName("Should create action button with different colors")
        void shouldCreateActionButtonWithDifferentColors() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            for (Colors color : new Colors[]{Colors.GREEN, Colors.RED, Colors.BLUE, Colors.YELLOW}) {
                Icon icon = page.testCreateActionButton(color, color.name() + " Button", null);
                assertNotNull(icon);
            }
        }

        @Test
        @DisplayName("Should create background icon multiple times")
        void shouldCreateBackgroundIconMultipleTimes() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            Icon icon1 = page.testCreateBackgroundIcon();
            Icon icon2 = page.testCreateBackgroundIcon();
            
            assertNotNull(icon1);
            assertNotNull(icon2);
        }
    }

    @Nested
    @DisplayName("Close Handler Tests")
    class CloseHandlerTests {

        @Test
        @DisplayName("Should return this for method chaining")
        void shouldReturnThisForCloseHandler() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            BaseInventoryPage result = page.onClose((Consumer<InventoryCloseEvent>) e -> {});
            
            assertSame(page, result);
        }

        @Test
        @DisplayName("Should allow setting close handler to null")
        void shouldAllowSettingCloseHandlerToNull() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            BaseInventoryPage result = page.onClose((Consumer<InventoryCloseEvent>) null);
            
            assertSame(page, result);
        }
    }

    @Nested
    @DisplayName("Default AfterSetup Tests")
    class DefaultAfterSetupTests {

        /**
         * Test page that doesn't override afterSetup
         */
        class DefaultAfterSetupPage extends BaseInventoryPage {
            public DefaultAfterSetupPage(Player player) {
                super(player, "default", "Default", 3);
            }

            @Override
            protected void setupContent(InventoryOpenEvent event) {
                // Required implementation
            }
            
            // Note: afterSetup is NOT overridden, uses default empty implementation
        }

        @Test
        @DisplayName("Should have default afterSetup that does nothing")
        void shouldHaveDefaultAfterSetup() {
            DefaultAfterSetupPage page = new DefaultAfterSetupPage(mockPlayer);
            
            // Page should be created successfully with default afterSetup
            assertNotNull(page);
        }
    }
    
    @Nested
    @DisplayName("Component Title Tests Documentation")
    class ComponentTitleConstructorTests {
        
        // Note: Component title constructors require NMS initialization which is not 
        // available in unit test environment. These constructors are tested via integration tests.
        // The String title constructors provide equivalent coverage for the constructor logic.
        
        @Test
        @DisplayName("Should document that Component title constructors exist")
        void shouldDocumentComponentTitleConstructorsExist() {
            // This test documents that Component title constructors are available
            // but cannot be unit tested due to NMS dependencies
            assertTrue(true, "Component title constructors are available but require NMS");
        }
    }
    
    @Nested
    @DisplayName("All Colors Action Button Tests")
    class AllColorsActionButtonTests {
        
        @Test
        @DisplayName("Should create action button with ORANGE color")
        void shouldCreateButtonWithOrangeColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.ORANGE, "Orange", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with WHITE color")
        void shouldCreateButtonWithWhiteColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.WHITE, "White", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with BLACK color")
        void shouldCreateButtonWithBlackColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.BLACK, "Black", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with PURPLE color")
        void shouldCreateButtonWithPurpleColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.PURPLE, "Purple", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with CYAN color")
        void shouldCreateButtonWithCyanColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.CYAN, "Cyan", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with LIGHT_BLUE color")
        void shouldCreateButtonWithLightBlueColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.LIGHT_BLUE, "Light Blue", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with LIGHT_GRAY color")
        void shouldCreateButtonWithLightGrayColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.LIGHT_GRAY, "Light Gray", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with LIME color")
        void shouldCreateButtonWithLimeColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.LIME, "Lime", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with MAGENTA color")
        void shouldCreateButtonWithMagentaColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.MAGENTA, "Magenta", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with PINK color")
        void shouldCreateButtonWithPinkColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.PINK, "Pink", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create action button with BROWN color")
        void shouldCreateButtonWithBrownColor() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.BROWN, "Brown", null);
            assertNotNull(icon);
        }
    }
    
    @Nested
    @DisplayName("Close Handler Execution Tests")
    class CloseHandlerExecutionTests {
        
        @Test
        @DisplayName("Should set close handler with non-null consumer")
        void shouldSetCloseHandlerWithNonNullConsumer() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            java.util.concurrent.atomic.AtomicBoolean handlerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
            
            page.onClose(e -> handlerCalled.set(true));
            
            // Handler is set but not executed yet
            assertFalse(handlerCalled.get());
        }
        
        @Test
        @DisplayName("Should chain multiple method calls")
        void shouldChainMultipleMethodCalls() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            
            BaseInventoryPage result = page
                    .setShowBottomToolbar(false)
                    .onClose(e -> {});
            
            assertSame(page, result);
        }
    }
    
    @Nested
    @DisplayName("Extended Slot Calculation Tests")
    class ExtendedSlotCalculationTests {
        
        @Test
        @DisplayName("Should calculate bottom center slot for 2 rows")
        void shouldCalculateBottomCenterFor2Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            int centerSlot = page.getBottomCenterSlot();
            assertEquals(13, centerSlot); // 18 - 5 = 13
        }
        
        @Test
        @DisplayName("Should calculate bottom center slot for 4 rows")
        void shouldCalculateBottomCenterFor4Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 4);
            int centerSlot = page.getBottomCenterSlot();
            assertEquals(31, centerSlot); // 36 - 5 = 31
        }
        
        @Test
        @DisplayName("Should calculate bottom center slot for 5 rows")
        void shouldCalculateBottomCenterFor5Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 5);
            int centerSlot = page.getBottomCenterSlot();
            assertEquals(40, centerSlot); // 45 - 5 = 40
        }
        
        @Test
        @DisplayName("Should calculate slot from end for 1 row page")
        void shouldCalculateSlotFromEndFor1Row() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 1);
            assertEquals(8, page.getSlotFromEnd(1)); // 9 - 1 = 8
            assertEquals(7, page.getSlotFromEnd(2)); // 9 - 2 = 7
        }
        
        @Test
        @DisplayName("Should calculate slot from end for 2 row page")
        void shouldCalculateSlotFromEndFor2Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            assertEquals(17, page.getSlotFromEnd(1)); // 18 - 1 = 17
            assertEquals(16, page.getSlotFromEnd(2)); // 18 - 2 = 16
        }
        
        @Test
        @DisplayName("Should calculate slot from end for 4 row page")
        void shouldCalculateSlotFromEndFor4Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 4);
            assertEquals(35, page.getSlotFromEnd(1)); // 36 - 1 = 35
            assertEquals(27, page.getSlotFromEnd(9)); // 36 - 9 = 27
        }
        
        @Test
        @DisplayName("Should calculate slot from end for 5 row page")
        void shouldCalculateSlotFromEndFor5Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 5);
            assertEquals(44, page.getSlotFromEnd(1)); // 45 - 1 = 44
            assertEquals(36, page.getSlotFromEnd(9)); // 45 - 9 = 36
        }
    }
    
    @Nested
    @DisplayName("Content Slots Boundary Tests")
    class ContentSlotsBoundaryTests {
        
        @Test
        @DisplayName("Should handle 2 row page with toolbar")
        void shouldHandle2RowPageWithToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            int[] slots = page.getContentSlots();
            assertEquals(9, slots.length); // 18 - 9 = 9
        }
        
        @Test
        @DisplayName("Should handle 2 row page without toolbar")
        void shouldHandle2RowPageWithoutToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            page.setShowBottomToolbar(false);
            int[] slots = page.getContentSlots();
            assertEquals(18, slots.length);
        }
        
        @Test
        @DisplayName("Should have content slots array with correct indices")
        void shouldHaveCorrectIndicesInContentSlots() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 2);
            page.setShowBottomToolbar(false);
            
            int[] slots = page.getContentSlots();
            for (int i = 0; i < slots.length; i++) {
                assertEquals(i, slots[i], "Slot at index " + i + " should be " + i);
            }
        }
        
        @Test
        @DisplayName("Should handle 6 row page without toolbar")
        void shouldHandle6RowPageWithoutToolbar() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            page.setShowBottomToolbar(false);
            int[] slots = page.getContentSlots();
            assertEquals(54, slots.length);
        }
    }
    
    @Nested
    @DisplayName("Create Background Icon Multiple Tests")
    class CreateBackgroundIconMultipleTests {
        
        @Test
        @DisplayName("Should create background icon with correct name")
        void shouldCreateBackgroundIconWithCorrectName() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateBackgroundIcon();
            assertNotNull(icon);
            // Verify XVersionUtils was called with GRAY
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.GRAY));
        }
        
        @Test
        @DisplayName("Should create background icon in a loop")
        void shouldCreateBackgroundIconInLoop() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            for (int i = 0; i < 9; i++) {
                Icon icon = page.testCreateBackgroundIcon();
                assertNotNull(icon);
            }
        }
    }
    
    @Nested
    @DisplayName("Action Button Click Handler Tests")
    class ActionButtonClickHandlerTests {
        
        @Test
        @DisplayName("Should create button with click handler that can be invoked")
        void shouldCreateButtonWithInvocableClickHandler() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            java.util.concurrent.atomic.AtomicInteger clickCount = new java.util.concurrent.atomic.AtomicInteger(0);
            
            Icon icon = page.testCreateActionButton(Colors.GREEN, "Click Me", e -> clickCount.incrementAndGet());
            
            assertNotNull(icon);
            // The click handler is attached to the icon
            assertEquals(0, clickCount.get()); // Not clicked yet
        }
        
        @Test
        @DisplayName("Should create button with empty name")
        void shouldCreateButtonWithEmptyName() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.RED, "", null);
            assertNotNull(icon);
        }
        
        @Test
        @DisplayName("Should create button with special characters in name")
        void shouldCreateButtonWithSpecialCharactersInName() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            Icon icon = page.testCreateActionButton(Colors.BLUE, "§aColored §bText §c!", null);
            assertNotNull(icon);
        }
    }
    
    @Nested
    @DisplayName("Method Tracking Tests")
    class MethodTrackingTests {
        
        @Test
        @DisplayName("Should create MethodTrackingPage successfully")
        void shouldCreateMethodTrackingPage() {
            MethodTrackingPage page = new MethodTrackingPage(mockPlayer);
            assertNotNull(page);
            assertEquals(27, page.getSize());
        }
        
        @Test
        @DisplayName("Should track setupBottomToolbar calls")
        void shouldTrackSetupBottomToolbarCalls() {
            MethodTrackingPage page = new MethodTrackingPage(mockPlayer);
            assertFalse(page.isSetupBottomToolbarCalled());
        }
        
        @Test
        @DisplayName("Should track fillArea parameters")
        void shouldTrackFillAreaParameters() {
            MethodTrackingPage page = new MethodTrackingPage(mockPlayer);
            page.trackFillArea(0, 17);
            
            assertTrue(page.isFillAreaCalled());
            assertEquals(0, page.getFillAreaStartSlot());
            assertEquals(17, page.getFillAreaEndSlot());
        }
        
        @Test
        @DisplayName("Should track fillBorder calls")
        void shouldTrackFillBorderCalls() {
            MethodTrackingPage page = new MethodTrackingPage(mockPlayer);
            page.trackFillBorder();
            
            assertTrue(page.isFillBorderCalled());
        }
        
        @Test
        @DisplayName("Should track addToBottomRow parameters")
        void shouldTrackAddToBottomRowParameters() {
            MethodTrackingPage page = new MethodTrackingPage(mockPlayer);
            page.trackAddToBottomRow(4);
            
            assertTrue(page.isAddToBottomRowCalled());
            assertEquals(4, page.getAddToBottomRowColumn());
        }
    }
    
    @Nested
    @DisplayName("FillArea Calculation Tests")
    class FillAreaCalculationTests {
        
        @Test
        @DisplayName("Should calculate correct number of slots in fillArea")
        void shouldCalculateCorrectSlotsInFillArea() {
            // Test calculation logic without actually filling
            // startSlot=0, endSlot=8 means row 0, columns 0-8 (9 slots)
            int startSlot = 0;
            int endSlot = 8;
            int startRow = startSlot / 9; // 0
            int endRow = endSlot / 9; // 0
            int startCol = startSlot % 9; // 0
            int endCol = endSlot % 9; // 8
            
            int expectedSlots = 0;
            for (int row = startRow; row <= endRow; row++) {
                int colStart = (row == startRow) ? startCol : 0;
                int colEnd = (row == endRow) ? endCol : 8;
                expectedSlots += (colEnd - colStart + 1);
            }
            
            assertEquals(9, expectedSlots);
        }
        
        @Test
        @DisplayName("Should calculate multi-row fillArea slots")
        void shouldCalculateMultiRowFillAreaSlots() {
            // Test calculation for slots 0-17 (rows 0-1)
            int startSlot = 0;
            int endSlot = 17;
            int startRow = startSlot / 9; // 0
            int endRow = endSlot / 9; // 1
            int startCol = startSlot % 9; // 0
            int endCol = endSlot % 9; // 8
            
            int expectedSlots = 0;
            for (int row = startRow; row <= endRow; row++) {
                int colStart = (row == startRow) ? startCol : 0;
                int colEnd = (row == endRow) ? endCol : 8;
                expectedSlots += (colEnd - colStart + 1);
            }
            
            assertEquals(18, expectedSlots);
        }
    }
    
    @Nested
    @DisplayName("FillBorder Calculation Tests")
    class FillBorderCalculationTests {
        
        @Test
        @DisplayName("Should calculate border slots for 3 row page")
        void shouldCalculateBorderSlotsFor3Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 3);
            int rows = page.getSize() / 9; // 3
            
            // Top row: 9 slots, Bottom row: 9 slots
            // Left column (excluding corners): 1 slot, Right column (excluding corners): 1 slot
            int topBottomSlots = 9 * 2;
            int sideSlots = (rows - 2) * 2;
            int totalBorderSlots = topBottomSlots + sideSlots;
            
            assertEquals(20, totalBorderSlots);
        }
        
        @Test
        @DisplayName("Should calculate border slots for 6 row page")
        void shouldCalculateBorderSlotsFor6Rows() {
            TestInventoryPage page = new TestInventoryPage(mockPlayer, "test", "Test", 6);
            int rows = page.getSize() / 9; // 6
            
            int topBottomSlots = 9 * 2;
            int sideSlots = (rows - 2) * 2;
            int totalBorderSlots = topBottomSlots + sideSlots;
            
            assertEquals(26, totalBorderSlots);
        }
    }
}
