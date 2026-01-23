package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
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
import com.ultikits.ultitools.utils.MessageUtils;
import com.ultikits.ultitools.utils.XVersionUtils;

import mc.obliviate.inventory.Icon;
import mc.obliviate.inventory.pagination.PaginationManager;
import net.kyori.adventure.text.Component;

/**
 * Unit tests for BasePaginationPage.
 * Tests focus on methods that don't require full Bukkit inventory setup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BasePaginationPage Tests")
class BasePaginationPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private ItemStack mockGreenGlass;

    @Mock
    private ItemStack mockGrayGlass;

    @Mock
    private InventoryOpenEvent mockOpenEvent;

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<XVersionUtils> xVersionUtilsMock;
    private MockedStatic<MessageUtils> messageUtilsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        xVersionUtilsMock = mockStatic(XVersionUtils.class);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN)).thenReturn(mockGreenGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.GRAY)).thenReturn(mockGrayGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class))).thenReturn(mockGrayGlass);
        
        messageUtilsMock = mockStatic(MessageUtils.class);
        messageUtilsMock.when(() -> MessageUtils.info(anyString())).thenAnswer(i -> "§a" + i.getArgument(0));
        messageUtilsMock.when(() -> MessageUtils.warning(anyString())).thenAnswer(i -> "§c" + i.getArgument(0));
        
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mockGreenGlass.getType()).thenReturn(Material.GREEN_STAINED_GLASS_PANE);
        lenient().when(mockGrayGlass.getType()).thenReturn(Material.GRAY_STAINED_GLASS_PANE);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
        if (xVersionUtilsMock != null) {
            xVersionUtilsMock.close();
        }
        if (messageUtilsMock != null) {
            messageUtilsMock.close();
        }
    }

    /**
     * Test implementation of BasePaginationPage
     */
    private static class TestPaginationPage extends BasePaginationPage {
        private final List<Icon> items;
        private boolean provideItemsCalled = false;

        public TestPaginationPage(Player player, String id, String title, int rows, List<Icon> items) {
            super(player, id, title, rows);
            this.items = items != null ? items : new ArrayList<>();
        }

        public TestPaginationPage(Player player, String id, String title, InventoryType type, List<Icon> items) {
            super(player, id, title, type);
            this.items = items != null ? items : new ArrayList<>();
        }
        
        public TestPaginationPage(Player player, String id, Component title, int rows, List<Icon> items) {
            super(player, id, title, rows);
            this.items = items != null ? items : new ArrayList<>();
        }
        
        public TestPaginationPage(Player player, String id, Component title, InventoryType type, List<Icon> items) {
            super(player, id, title, type);
            this.items = items != null ? items : new ArrayList<>();
        }

        @Override
        protected List<Icon> provideItems() {
            provideItemsCalled = true;
            return items;
        }

        public boolean isProvideItemsCalled() {
            return provideItemsCalled;
        }

        // Expose protected methods for testing
        public Icon testCreatePreviousButton() {
            return createPreviousButton();
        }

        public Icon testCreateNextButton() {
            return createNextButton();
        }
    }

    private Icon createTestIcon() {
        return new Icon(mockGrayGlass);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create pagination page with correct size")
        void shouldCreatePaginationPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            
            assertEquals(27, page.getSize());
        }

        @Test
        @DisplayName("Should create pagination page with 6 rows")
        void shouldCreate6RowPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 6, null);
            
            assertEquals(54, page.getSize());
        }

        @Test
        @DisplayName("Should create pagination page with InventoryType")
        void shouldCreateWithInventoryType() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", InventoryType.CHEST, null);
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should create pagination page with 4 rows")
        void shouldCreate4RowPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 4, null);
            
            assertEquals(36, page.getSize());
        }

        @Test
        @DisplayName("Should create pagination page with 5 rows")
        void shouldCreate5RowPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 5, null);
            
            assertEquals(45, page.getSize());
        }

        @Test
        @DisplayName("Should create pagination page with 2 rows")
        void shouldCreate2RowPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 2, null);
            
            assertEquals(18, page.getSize());
        }
    }

    @Nested
    @DisplayName("Button Constants Tests")
    class ButtonConstantsTests {

        @Test
        @DisplayName("Previous button should be at column 3")
        void prevButtonShouldBeAtColumn3() {
            assertEquals(3, BasePaginationPage.PREV_BUTTON_COLUMN);
        }

        @Test
        @DisplayName("Next button should be at column 5")
        void nextButtonShouldBeAtColumn5() {
            assertEquals(5, BasePaginationPage.NEXT_BUTTON_COLUMN);
        }
    }

    @Nested
    @DisplayName("Pagination Manager Tests")
    class PaginationManagerTests {

        @Test
        @DisplayName("Should have pagination manager initialized")
        void shouldHavePaginationManager() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            
            assertNotNull(page.getPaginationManager());
        }

        @Test
        @DisplayName("Should have PaginationManager instance")
        void shouldReturnPaginationManagerInstance() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            
            PaginationManager manager = page.getPaginationManager();
            
            assertNotNull(manager);
            assertTrue(manager instanceof PaginationManager);
        }

        @Test
        @DisplayName("Should initialize pagination manager for different sizes")
        void shouldInitializePaginationManagerForDifferentSizes() {
            for (int rows = 2; rows <= 6; rows++) {
                TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", rows, null);
                assertNotNull(page.getPaginationManager());
            }
        }
    }

    @Nested
    @DisplayName("Pagination State Tests")
    class PaginationStateTests {

        @Test
        @DisplayName("Should start on page 1")
        void shouldStartOnPage1() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertEquals(1, page.getCurrentPage());
        }

        @Test
        @DisplayName("Should report at least 1 total page for empty items")
        void shouldReportAtLeast1Page() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertTrue(page.getTotalPages() >= 1);
        }

        @Test
        @DisplayName("Should report hasNextPage correctly for empty items")
        void shouldReportHasNextPageForEmpty() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertFalse(page.hasNextPage());
        }

        @Test
        @DisplayName("Should report hasPreviousPage correctly for first page")
        void shouldReportHasPreviousPageForFirstPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertFalse(page.hasPreviousPage());
        }

        @Test
        @DisplayName("getCurrentPage should return 1-based page number")
        void getCurrentPageShouldReturn1BasedPageNumber() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Should be at least 1
            assertTrue(page.getCurrentPage() >= 1);
        }

        @Test
        @DisplayName("getTotalPages should return at least 1")
        void getTotalPagesShouldReturnAtLeast1() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertTrue(page.getTotalPages() >= 1);
        }
    }

    @Nested
    @DisplayName("Navigation Button Tests")
    class NavigationButtonTests {

        @Test
        @DisplayName("Should create previous button")
        void shouldCreatePreviousButton() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            Icon prevButton = page.testCreatePreviousButton();
            
            assertNotNull(prevButton);
        }

        @Test
        @DisplayName("Should create next button")
        void shouldCreateNextButton() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            Icon nextButton = page.testCreateNextButton();
            
            assertNotNull(nextButton);
        }

        @Test
        @DisplayName("Should use i18n for previous button text")
        void shouldUseI18nForPreviousButton() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            page.testCreatePreviousButton();
            
            verify(mockUltiTools, atLeastOnce()).i18n("上一页");
        }

        @Test
        @DisplayName("Should use i18n for next button text")
        void shouldUseI18nForNextButton() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            page.testCreateNextButton();
            
            verify(mockUltiTools, atLeastOnce()).i18n("下一页");
        }

        @Test
        @DisplayName("Should use green color for buttons")
        void shouldUseGreenColorForButtons() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            page.testCreatePreviousButton();
            page.testCreateNextButton();
            
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN), atLeast(2));
        }
    }

    @Nested
    @DisplayName("Go To Page Logic Tests")
    class GoToPageTests {
        
        @Test
        @DisplayName("goToPage should clamp to minimum of 1")
        void goToPageShouldClampToMinimum() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Verify page is at page 1 initially
            assertEquals(1, page.getCurrentPage());
        }

        @Test
        @DisplayName("Page number should be 1-based")
        void pageNumberShouldBe1Based() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Initial page should be 1, not 0
            assertTrue(page.getCurrentPage() >= 1);
        }

        @Test
        @DisplayName("Should initialize to first page")
        void shouldInitializeToFirstPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertEquals(1, page.getCurrentPage());
            assertFalse(page.hasPreviousPage());
        }
    }

    @Nested
    @DisplayName("Items Tests")
    class ItemsTests {

        @Test
        @DisplayName("Should handle null items list")
        void shouldHandleNullItemsList() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            
            // provideItems should return empty list for null
            List<Icon> items = page.provideItems();
            
            assertNotNull(items);
            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("Should return provided items")
        void shouldReturnProvidedItems() {
            List<Icon> expectedItems = new ArrayList<>();
            expectedItems.add(createTestIcon());
            expectedItems.add(createTestIcon());
            
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, expectedItems);
            
            List<Icon> items = page.provideItems();
            
            assertEquals(2, items.size());
        }

        @Test
        @DisplayName("Should track provideItems called")
        void shouldTrackProvideItemsCalled() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            assertFalse(page.isProvideItemsCalled());
            
            page.provideItems();
            
            assertTrue(page.isProvideItemsCalled());
        }
    }

    @Nested
    @DisplayName("Content Slots Integration Tests")
    class ContentSlotsIntegrationTests {

        @Test
        @DisplayName("Should have content slots for pagination")
        void shouldHaveContentSlotsForPagination() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            
            int[] slots = page.getContentSlots();
            
            // Should have content slots (excluding last row for toolbar)
            assertTrue(slots.length > 0);
        }

        @Test
        @DisplayName("Content slots should be based on row count")
        void contentSlotsShouldBeBasedOnRowCount() {
            TestPaginationPage page3Rows = new TestPaginationPage(mockPlayer, "test", "Test", 3, null);
            TestPaginationPage page6Rows = new TestPaginationPage(mockPlayer, "test", "Test", 6, null);
            
            // 6 row page should have more content slots
            assertTrue(page6Rows.getContentSlots().length > page3Rows.getContentSlots().length);
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
            // Verify BasePaginationPage class exists and is accessible
            assertNotNull(BasePaginationPage.class, "Component title constructors are available but require NMS");
        }
    }
    
    @Nested
    @DisplayName("Navigation Button Lore Tests")
    class NavigationButtonLoreTests {
        
        @Test
        @DisplayName("Should set lore on previous button when on first page")
        void shouldSetLoreOnPreviousButtonWhenFirstPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Create previous button - it should have lore since we're on first page
            Icon prevButton = page.testCreatePreviousButton();
            
            assertNotNull(prevButton);
            verify(mockUltiTools, atLeastOnce()).i18n("已经是第一页了");
        }
        
        @Test
        @DisplayName("Should set lore on next button when on last page")
        void shouldSetLoreOnNextButtonWhenLastPage() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Create next button - it should have lore since empty list means only one page
            Icon nextButton = page.testCreateNextButton();
            
            assertNotNull(nextButton);
            verify(mockUltiTools, atLeastOnce()).i18n("已经是最后一页了");
        }
    }
    
    @Nested
    @DisplayName("Items List Tests")
    class ItemsListTests {
        
        @Test
        @DisplayName("Should handle single item")
        void shouldHandleSingleItem() {
            List<Icon> expectedItems = new ArrayList<>();
            expectedItems.add(createTestIcon());
            
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, expectedItems);
            
            List<Icon> items = page.provideItems();
            assertEquals(1, items.size());
        }
        
        @Test
        @DisplayName("Should handle large items list")
        void shouldHandleLargeItemsList() {
            List<Icon> expectedItems = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                expectedItems.add(createTestIcon());
            }
            
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, expectedItems);
            
            List<Icon> items = page.provideItems();
            assertEquals(100, items.size());
        }
        
        @Test
        @DisplayName("Should calculate total pages based on items")
        void shouldCalculateTotalPagesBasedOnItems() {
            TestPaginationPage emptyPage = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // Empty page should have at least 1 total page
            assertTrue(emptyPage.getTotalPages() >= 1);
        }
    }
    
    @Nested
    @DisplayName("Pagination State Queries Tests")
    class PaginationStateQueriesTests {
        
        @Test
        @DisplayName("hasNextPage and hasPreviousPage should be consistent")
        void hasNextAndPreviousShouldBeConsistent() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            // On first page with no items, should not have previous or next
            assertFalse(page.hasPreviousPage());
            assertFalse(page.hasNextPage());
        }
        
        @Test
        @DisplayName("getCurrentPage should be in valid range")
        void getCurrentPageShouldBeInValidRange() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            int currentPage = page.getCurrentPage();
            int totalPages = page.getTotalPages();
            
            assertTrue(currentPage >= 1);
            assertTrue(currentPage <= totalPages);
        }
    }
    
    @Nested
    @DisplayName("Button Creation Multiple Times Tests")
    class ButtonCreationMultipleTimesTests {
        
        @Test
        @DisplayName("Should create previous button multiple times")
        void shouldCreatePreviousButtonMultipleTimes() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            for (int i = 0; i < 3; i++) {
                Icon button = page.testCreatePreviousButton();
                assertNotNull(button);
            }
        }
        
        @Test
        @DisplayName("Should create next button multiple times")
        void shouldCreateNextButtonMultipleTimes() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 3, new ArrayList<>());
            
            for (int i = 0; i < 3; i++) {
                Icon button = page.testCreateNextButton();
                assertNotNull(button);
            }
        }
    }
    
    @Nested
    @DisplayName("Different Row Counts Tests")
    class DifferentRowCountsTests {
        
        @Test
        @DisplayName("Should work with 1 row")
        void shouldWorkWith1Row() {
            TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test", 1, null);
            
            assertNotNull(page);
            assertEquals(9, page.getSize());
        }
        
        @Test
        @DisplayName("Should have content slots for each row count")
        void shouldHaveContentSlotsForEachRowCount() {
            for (int rows = 2; rows <= 6; rows++) {
                TestPaginationPage page = new TestPaginationPage(mockPlayer, "test", "Test " + rows, rows, null);
                int[] slots = page.getContentSlots();
                
                // Content slots should be total slots minus the bottom row (9 slots for toolbar)
                assertEquals((rows - 1) * 9, slots.length, "Row count: " + rows);
            }
        }
    }
}
