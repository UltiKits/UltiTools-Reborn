package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
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

import mc.obliviate.inventory.Icon;

/**
 * Unit tests for BasePaginationPage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BasePaginationPage Tests")
class BasePaginationPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private VersionWrapper mockVersionWrapper;

    @Mock
    private ItemStack mockGreenGlass;

    @Mock
    private ItemStack mockGrayGlass;

    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        lenient().when(mockUltiTools.getVersionWrapper()).thenReturn(mockVersionWrapper);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(Colors.GREEN)).thenReturn(mockGreenGlass);
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(Colors.GRAY)).thenReturn(mockGrayGlass);
        lenient().when(mockGreenGlass.getType()).thenReturn(Material.GREEN_STAINED_GLASS_PANE);
        lenient().when(mockGrayGlass.getType()).thenReturn(Material.GRAY_STAINED_GLASS_PANE);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    /**
     * Test implementation of BasePaginationPage
     */
    private static class TestPaginationPage extends BasePaginationPage {
        private final List<Icon> items;

        public TestPaginationPage(Player player, String id, String title, int rows, List<Icon> items) {
            super(player, id, title, rows);
            this.items = items != null ? items : new ArrayList<>();
        }

        @Override
        protected List<Icon> provideItems() {
            return items;
        }
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
    }
}
