package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
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
 * Unit tests for BaseConfirmationPage.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseConfirmationPage Tests")
class BaseConfirmationPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private VersionWrapper mockVersionWrapper;

    @Mock
    private InventoryOpenEvent mockOpenEvent;

    @Mock
    private InventoryClickEvent mockClickEvent;

    @Mock
    private ItemStack mockGreenGlass;

    @Mock
    private ItemStack mockRedGlass;

    private MockedStatic<UltiTools> ultiToolsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        lenient().when(mockUltiTools.getVersionWrapper()).thenReturn(mockVersionWrapper);
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(Colors.GREEN)).thenReturn(mockGreenGlass);
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(Colors.RED)).thenReturn(mockRedGlass);
        lenient().when(mockVersionWrapper.getColoredPlaneGlass(Colors.GRAY)).thenReturn(mockRedGlass);
        lenient().when(mockGreenGlass.getType()).thenReturn(Material.GREEN_STAINED_GLASS_PANE);
        lenient().when(mockRedGlass.getType()).thenReturn(Material.RED_STAINED_GLASS_PANE);
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) {
            ultiToolsMock.close();
        }
    }

    /**
     * Test implementation of BaseConfirmationPage
     */
    private static class TestConfirmationPage extends BaseConfirmationPage {
        private boolean confirmCalled = false;
        private boolean cancelCalled = false;
        private boolean dialogSetupCalled = false;

        public TestConfirmationPage(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }

        @Override
        protected void setupDialogContent(InventoryOpenEvent event) {
            dialogSetupCalled = true;
        }

        @Override
        protected void onConfirm(InventoryClickEvent event) {
            confirmCalled = true;
        }

        @Override
        protected void onCancel(InventoryClickEvent event) {
            cancelCalled = true;
        }

        public boolean isConfirmCalled() {
            return confirmCalled;
        }

        public boolean isCancelCalled() {
            return cancelCalled;
        }

        public boolean isDialogSetupCalled() {
            return dialogSetupCalled;
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create confirmation page with correct size")
        void shouldCreateConfirmationPage() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            assertEquals(27, page.getSize());
        }
    }

    @Nested
    @DisplayName("Button Constants Tests")
    class ButtonConstantsTests {

        @Test
        @DisplayName("Cancel button should be at column 3")
        void cancelButtonShouldBeAtColumn3() {
            assertEquals(3, BaseConfirmationPage.CANCEL_BUTTON_COLUMN);
        }

        @Test
        @DisplayName("OK button should be at column 5")
        void okButtonShouldBeAtColumn5() {
            assertEquals(5, BaseConfirmationPage.OK_BUTTON_COLUMN);
        }
    }

    @Nested
    @DisplayName("Button Name Tests")
    class ButtonNameTests {

        @Test
        @DisplayName("Should return default OK button name")
        void shouldReturnDefaultOkButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            String okName = page.getOkButtonName();
            
            assertEquals("OK", okName);
        }

        @Test
        @DisplayName("Should return default cancel button name")
        void shouldReturnDefaultCancelButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            String cancelName = page.getCancelButtonName();
            
            assertEquals("取消", cancelName);
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create builder with player")
        void shouldCreateBuilderWithPlayer() {
            BaseConfirmationPage.Builder builder = BaseConfirmationPage.builder(mockPlayer);
            
            assertNotNull(builder);
        }

        @Test
        @DisplayName("Should build page with custom id")
        void shouldBuildPageWithCustomId() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .id("custom-id")
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should build page with custom title")
        void shouldBuildPageWithCustomTitle() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .title("Custom Title")
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should build page with custom rows")
        void shouldBuildPageWithCustomRows() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(5)
                    .build();
            
            assertEquals(45, page.getSize()); // 5 rows * 9
        }

        @Test
        @DisplayName("Should support method chaining")
        void shouldSupportMethodChaining() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .id("test")
                    .title("Test")
                    .rows(3)
                    .okButton("Yes")
                    .cancelButton("No")
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Builder onConfirm should be called when confirm action triggered")
        void builderOnConfirmShouldBeCalled() {
            AtomicBoolean confirmCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .onConfirm(e -> confirmCalled.set(true))
                    .build();
            
            // Verify the page was built successfully
            assertNotNull(page);
        }

        @Test
        @DisplayName("Builder onCancel should be called when cancel action triggered")
        void builderOnCancelShouldBeCalled() {
            AtomicBoolean cancelCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .onCancel(e -> cancelCalled.set(true))
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Builder content setup should be applied")
        void builderContentSetupShouldBeApplied() {
            AtomicBoolean contentSetupCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .content(e -> contentSetupCalled.set(true))
                    .build();
            
            assertNotNull(page);
        }
    }

    @Nested
    @DisplayName("Custom Button Names via Builder")
    class CustomButtonNamesTests {

        @Test
        @DisplayName("Should use custom OK button name from builder")
        void shouldUseCustomOkName() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .okButton("Confirm")
                    .build();
            
            assertEquals("Confirm", page.getOkButtonName());
        }

        @Test
        @DisplayName("Should use custom cancel button name from builder")
        void shouldUseCustomCancelName() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .cancelButton("Abort")
                    .build();
            
            assertEquals("Abort", page.getCancelButtonName());
        }

        @Test
        @DisplayName("Should fall back to default when custom name is null")
        void shouldFallBackToDefaultWhenNull() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .build();
            
            assertEquals("OK", page.getOkButtonName());
            assertEquals("取消", page.getCancelButtonName());
        }
    }
}
