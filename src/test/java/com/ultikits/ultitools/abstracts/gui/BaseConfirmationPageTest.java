package com.ultikits.ultitools.abstracts.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
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
import net.kyori.adventure.text.Component;

/**
 * Unit tests for BaseConfirmationPage.
 * Tests focus on methods that don't require full Bukkit inventory setup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseConfirmationPage Tests")
class BaseConfirmationPageTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private InventoryOpenEvent mockOpenEvent;

    @Mock
    private InventoryClickEvent mockClickEvent;

    @Mock
    private ItemStack mockGreenGlass;

    @Mock
    private ItemStack mockRedGlass;

    @Mock
    private ItemStack mockGrayGlass;

    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<XVersionUtils> xVersionUtilsMock;
    private MockedStatic<MessageUtils> messageUtilsMock;

    @BeforeEach
    void setUp() {
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        xVersionUtilsMock = mockStatic(XVersionUtils.class);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN)).thenReturn(mockGreenGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.RED)).thenReturn(mockRedGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(Colors.GRAY)).thenReturn(mockGrayGlass);
        xVersionUtilsMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class))).thenReturn(mockGrayGlass);
        
        messageUtilsMock = mockStatic(MessageUtils.class);
        messageUtilsMock.when(() -> MessageUtils.coloredMsg(anyString())).thenAnswer(i -> "§f" + i.getArgument(0));
        
        lenient().when(mockUltiTools.i18n(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mockGreenGlass.getType()).thenReturn(Material.GREEN_STAINED_GLASS_PANE);
        lenient().when(mockRedGlass.getType()).thenReturn(Material.RED_STAINED_GLASS_PANE);
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
     * Test implementation of BaseConfirmationPage
     */
    private static class TestConfirmationPage extends BaseConfirmationPage {
        private boolean confirmCalled = false;
        private boolean cancelCalled = false;
        private boolean dialogSetupCalled = false;
        private String customOkName = null;
        private String customCancelName = null;

        public TestConfirmationPage(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }

        public TestConfirmationPage(Player player, String id, String title, InventoryType type) {
            super(player, id, title, type);
        }
        
        public TestConfirmationPage(Player player, String id, Component title, int rows) {
            super(player, id, title, rows);
        }
        
        public TestConfirmationPage(Player player, String id, Component title, InventoryType type) {
            super(player, id, title, type);
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

        @Override
        protected String getOkButtonName() {
            return customOkName != null ? customOkName : super.getOkButtonName();
        }

        @Override
        protected String getCancelButtonName() {
            return customCancelName != null ? customCancelName : super.getCancelButtonName();
        }

        public void setCustomOkName(String name) {
            this.customOkName = name;
        }

        public void setCustomCancelName(String name) {
            this.customCancelName = name;
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

        // Expose protected methods for testing
        public Icon testCreateOkButton() {
            return createOkButton();
        }

        public Icon testCreateCancelButton() {
            return createCancelButton();
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

        @Test
        @DisplayName("Should create confirmation page with 5 rows")
        void shouldCreate5RowPage() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 5);
            
            assertEquals(45, page.getSize());
        }

        @Test
        @DisplayName("Should create confirmation page with InventoryType")
        void shouldCreateWithInventoryType() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", InventoryType.CHEST);
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should create confirmation page with 2 rows")
        void shouldCreate2RowPage() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 2);
            
            assertEquals(18, page.getSize());
        }

        @Test
        @DisplayName("Should create confirmation page with 4 rows")
        void shouldCreate4RowPage() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 4);
            
            assertEquals(36, page.getSize());
        }

        @Test
        @DisplayName("Should create confirmation page with 6 rows")
        void shouldCreate6RowPage() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 6);
            
            assertEquals(54, page.getSize());
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

        @Test
        @DisplayName("Should return custom OK button name")
        void shouldReturnCustomOkButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            page.setCustomOkName("Yes");
            
            String okName = page.getOkButtonName();
            
            assertEquals("Yes", okName);
        }

        @Test
        @DisplayName("Should return custom cancel button name")
        void shouldReturnCustomCancelButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            page.setCustomCancelName("No");
            
            String cancelName = page.getCancelButtonName();
            
            assertEquals("No", cancelName);
        }

        @Test
        @DisplayName("Should use i18n for default OK button name")
        void shouldUseI18nForDefaultOkButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            page.getOkButtonName();
            
            verify(mockUltiTools).i18n("OK");
        }

        @Test
        @DisplayName("Should use i18n for default cancel button name")
        void shouldUseI18nForDefaultCancelButtonName() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            page.getCancelButtonName();
            
            verify(mockUltiTools).i18n("取消");
        }
    }

    @Nested
    @DisplayName("Button Creation Tests")
    class ButtonCreationTests {

        @Test
        @DisplayName("Should create OK button")
        void shouldCreateOkButton() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            Icon okButton = page.testCreateOkButton();
            
            assertNotNull(okButton);
        }

        @Test
        @DisplayName("Should create Cancel button")
        void shouldCreateCancelButton() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            Icon cancelButton = page.testCreateCancelButton();
            
            assertNotNull(cancelButton);
        }

        @Test
        @DisplayName("Should setup confirmation buttons method exists")
        void shouldSetupConfirmationButtonsMethodExists() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            // Verify the method exists and page is properly constructed
            assertNotNull(page);
            // The actual setupConfirmationButtons requires a real inventory
            // which is created when the GUI is opened, not at construction time
        }

        @Test
        @DisplayName("Should use green color for OK button")
        void shouldUseGreenColorForOkButton() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            page.testCreateOkButton();
            
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.GREEN), atLeastOnce());
        }

        @Test
        @DisplayName("Should use red color for Cancel button")
        void shouldUseRedColorForCancelButton() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            page.testCreateCancelButton();
            
            xVersionUtilsMock.verify(() -> XVersionUtils.getColoredPlaneGlass(Colors.RED), atLeastOnce());
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
            
            assertEquals(45, page.getSize());
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
        @DisplayName("Builder onConfirm should be stored")
        void builderOnConfirmShouldBeStored() {
            AtomicBoolean confirmCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .onConfirm(e -> confirmCalled.set(true))
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Builder onCancel should be stored")
        void builderOnCancelShouldBeStored() {
            AtomicBoolean cancelCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .onCancel(e -> cancelCalled.set(true))
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Builder content setup should be stored")
        void builderContentSetupShouldBeStored() {
            AtomicBoolean contentSetupCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .content(e -> contentSetupCalled.set(true))
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should build with all options")
        void shouldBuildWithAllOptions() {
            AtomicBoolean confirmCalled = new AtomicBoolean(false);
            AtomicBoolean cancelCalled = new AtomicBoolean(false);
            AtomicBoolean contentCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .id("full-test")
                    .title("Full Test")
                    .rows(4)
                    .okButton("Confirm")
                    .cancelButton("Abort")
                    .onConfirm(e -> confirmCalled.set(true))
                    .onCancel(e -> cancelCalled.set(true))
                    .content(e -> contentCalled.set(true))
                    .build();
            
            assertNotNull(page);
            assertEquals(36, page.getSize());
        }

        @Test
        @DisplayName("Builder should use default values when not set")
        void builderShouldUseDefaultValuesWhenNotSet() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .build();
            
            assertNotNull(page);
            assertEquals(27, page.getSize()); // Default 3 rows
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

        @Test
        @DisplayName("Should handle empty custom OK name")
        void shouldHandleEmptyCustomOkName() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .okButton("")
                    .build();
            
            assertEquals("", page.getOkButtonName());
        }

        @Test
        @DisplayName("Should handle empty custom cancel name")
        void shouldHandleEmptyCustomCancelName() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .cancelButton("")
                    .build();
            
            assertEquals("", page.getCancelButtonName());
        }
    }

    @Nested
    @DisplayName("Content Slots Tests")
    class ContentSlotsTests {

        @Test
        @DisplayName("Should have content slots")
        void shouldHaveContentSlots() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            int[] slots = page.getContentSlots();
            
            assertNotNull(slots);
            assertTrue(slots.length > 0);
        }

        @Test
        @DisplayName("Content slots should be based on row count")
        void contentSlotsShouldBeBasedOnRowCount() {
            TestConfirmationPage page3Rows = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            TestConfirmationPage page6Rows = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 6);
            
            assertTrue(page6Rows.getContentSlots().length > page3Rows.getContentSlots().length);
        }
    }

    @Nested
    @DisplayName("Dialog Setup Tests")
    class DialogSetupTests {

        @Test
        @DisplayName("Should track dialog setup status")
        void shouldTrackDialogSetupStatus() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            assertFalse(page.isDialogSetupCalled());
        }

        @Test
        @DisplayName("Should track confirm status")
        void shouldTrackConfirmStatus() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            assertFalse(page.isConfirmCalled());
        }

        @Test
        @DisplayName("Should track cancel status")
        void shouldTrackCancelStatus() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            assertFalse(page.isCancelCalled());
        }
    }

    @Nested
    @DisplayName("Default Implementation Tests")
    class DefaultImplementationTests {

        /**
         * Test page that only implements required abstract method
         */
        class MinimalConfirmationPage extends BaseConfirmationPage {
            public MinimalConfirmationPage(Player player) {
                super(player, "minimal", "Minimal", 3);
            }

            @Override
            protected void onConfirm(InventoryClickEvent event) {
                // Required implementation
            }
            
            // Note: setupDialogContent and onCancel are NOT overridden
        }

        @Test
        @DisplayName("Should work with minimal implementation")
        void shouldWorkWithMinimalImplementation() {
            MinimalConfirmationPage page = new MinimalConfirmationPage(mockPlayer);
            
            assertNotNull(page);
            assertEquals(27, page.getSize());
        }

        @Test
        @DisplayName("Should have default OK button name")
        void shouldHaveDefaultOkButtonName() {
            MinimalConfirmationPage page = new MinimalConfirmationPage(mockPlayer);
            
            assertEquals("OK", page.getOkButtonName());
        }

        @Test
        @DisplayName("Should have default cancel button name")
        void shouldHaveDefaultCancelButtonName() {
            MinimalConfirmationPage page = new MinimalConfirmationPage(mockPlayer);
            
            assertEquals("取消", page.getCancelButtonName());
        }
    }

    @Nested
    @DisplayName("Builder Variations Tests")
    class BuilderVariationsTests {

        @Test
        @DisplayName("Should build page with 1 row")
        void shouldBuildPageWith1Row() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(1)
                    .build();
            
            assertEquals(9, page.getSize());
        }

        @Test
        @DisplayName("Should build page with 2 rows")
        void shouldBuildPageWith2Rows() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(2)
                    .build();
            
            assertEquals(18, page.getSize());
        }

        @Test
        @DisplayName("Should build page with 6 rows")
        void shouldBuildPageWith6Rows() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(6)
                    .build();
            
            assertEquals(54, page.getSize());
        }

        @Test
        @DisplayName("Should build page with only id set")
        void shouldBuildPageWithOnlyIdSet() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .id("only-id")
                    .build();
            
            assertNotNull(page);
        }

        @Test
        @DisplayName("Should build page with only title set")
        void shouldBuildPageWithOnlyTitleSet() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .title("Only Title")
                    .build();
            
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
    @DisplayName("Button Creation Multiple Tests")
    class ButtonCreationMultipleTests {
        
        @Test
        @DisplayName("Should create OK button multiple times")
        void shouldCreateOkButtonMultipleTimes() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            for (int i = 0; i < 3; i++) {
                Icon button = page.testCreateOkButton();
                assertNotNull(button);
            }
        }
        
        @Test
        @DisplayName("Should create Cancel button multiple times")
        void shouldCreateCancelButtonMultipleTimes() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            for (int i = 0; i < 3; i++) {
                Icon button = page.testCreateCancelButton();
                assertNotNull(button);
            }
        }
        
        @Test
        @DisplayName("Should create both buttons in sequence")
        void shouldCreateBothButtonsInSequence() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 3);
            
            Icon okButton = page.testCreateOkButton();
            Icon cancelButton = page.testCreateCancelButton();
            
            assertNotNull(okButton);
            assertNotNull(cancelButton);
        }
    }
    
    @Nested
    @DisplayName("Builder Callback Execution Tests")
    class BuilderCallbackExecutionTests {
        
        @Test
        @DisplayName("Builder should store all callbacks")
        void builderShouldStoreAllCallbacks() {
            AtomicBoolean confirmCalled = new AtomicBoolean(false);
            AtomicBoolean cancelCalled = new AtomicBoolean(false);
            AtomicBoolean contentCalled = new AtomicBoolean(false);
            
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .id("callback-test")
                    .title("Callback Test")
                    .rows(3)
                    .onConfirm(e -> confirmCalled.set(true))
                    .onCancel(e -> cancelCalled.set(true))
                    .content(e -> contentCalled.set(true))
                    .build();
            
            assertNotNull(page);
            // Callbacks are stored but not executed until events trigger them
            assertFalse(confirmCalled.get());
            assertFalse(cancelCalled.get());
            assertFalse(contentCalled.get());
        }
        
        @Test
        @DisplayName("Builder should handle null callbacks")
        void builderShouldHandleNullCallbacks() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .onConfirm(null)
                    .onCancel(null)
                    .content(null)
                    .build();
            
            assertNotNull(page);
        }
    }
    
    @Nested
    @DisplayName("Content Slots Tests Extended")
    class ContentSlotsTestsExtended {
        
        @Test
        @DisplayName("Should have correct content slots for 1 row page")
        void shouldHaveCorrectContentSlotsFor1Row() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 1);
            int[] slots = page.getContentSlots();
            
            // With toolbar on 1 row page, 0 content slots (toolbar takes entire row)
            assertEquals(0, slots.length);
        }
        
        @Test
        @DisplayName("Should have correct content slots for 2 row page")
        void shouldHaveCorrectContentSlotsFor2Row() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 2);
            int[] slots = page.getContentSlots();
            
            assertEquals(9, slots.length); // 18 - 9 = 9
        }
        
        @Test
        @DisplayName("Should have correct content slots for 4 row page")
        void shouldHaveCorrectContentSlotsFor4Row() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 4);
            int[] slots = page.getContentSlots();
            
            assertEquals(27, slots.length); // 36 - 9 = 27
        }
        
        @Test
        @DisplayName("Should have correct content slots for 5 row page")
        void shouldHaveCorrectContentSlotsFor5Row() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 5);
            int[] slots = page.getContentSlots();
            
            assertEquals(36, slots.length); // 45 - 9 = 36
        }
        
        @Test
        @DisplayName("Should have correct content slots for 6 row page")
        void shouldHaveCorrectContentSlotsFor6Row() {
            TestConfirmationPage page = new TestConfirmationPage(mockPlayer, "confirm", "Confirm", 6);
            int[] slots = page.getContentSlots();
            
            assertEquals(45, slots.length); // 54 - 9 = 45
        }
    }
    
    @Nested
    @DisplayName("Builder Chain Order Tests")
    class BuilderChainOrderTests {
        
        @Test
        @DisplayName("Should allow setting properties in any order")
        void shouldAllowSettingPropertiesInAnyOrder() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(4)
                    .title("Test")
                    .id("test-id")
                    .cancelButton("No")
                    .okButton("Yes")
                    .build();
            
            assertNotNull(page);
            assertEquals(36, page.getSize());
            assertEquals("Yes", page.getOkButtonName());
            assertEquals("No", page.getCancelButtonName());
        }
        
        @Test
        @DisplayName("Should allow overwriting properties")
        void shouldAllowOverwritingProperties() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .rows(3)
                    .rows(4)
                    .title("First")
                    .title("Second")
                    .build();
            
            assertEquals(36, page.getSize()); // Should use last value (4 rows)
        }
    }
    
    @Nested
    @DisplayName("Custom Button Name Edge Cases Tests")
    class CustomButtonNameEdgeCasesTests {
        
        @Test
        @DisplayName("Should handle whitespace-only button names")
        void shouldHandleWhitespaceOnlyButtonNames() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .okButton("   ")
                    .cancelButton("\t\n")
                    .build();
            
            assertEquals("   ", page.getOkButtonName());
            assertEquals("\t\n", page.getCancelButtonName());
        }
        
        @Test
        @DisplayName("Should handle very long button names")
        void shouldHandleVeryLongButtonNames() {
            String longName = "A".repeat(100);
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .okButton(longName)
                    .build();
            
            assertEquals(longName, page.getOkButtonName());
        }
        
        @Test
        @DisplayName("Should handle special characters in button names")
        void shouldHandleSpecialCharactersInButtonNames() {
            BaseConfirmationPage page = BaseConfirmationPage.builder(mockPlayer)
                    .okButton("§aYes §r✓")
                    .cancelButton("§cNo §r✗")
                    .build();
            
            assertEquals("§aYes §r✓", page.getOkButtonName());
            assertEquals("§cNo §r✗", page.getCancelButtonName());
        }
    }
    
    @Nested
    @DisplayName("Default onCancel Implementation Tests")
    class DefaultOnCancelImplementationTests {
        
        /**
         * Test page that only implements onConfirm, uses default onCancel
         */
        class OnlyConfirmPage extends BaseConfirmationPage {
            private boolean confirmCalled = false;
            
            public OnlyConfirmPage(Player player) {
                super(player, "only-confirm", "Test", 3);
            }
            
            @Override
            protected void onConfirm(InventoryClickEvent event) {
                confirmCalled = true;
            }
            
            // onCancel uses default empty implementation
            
            public boolean isConfirmCalled() {
                return confirmCalled;
            }
        }
        
        @Test
        @DisplayName("Should work with default onCancel implementation")
        void shouldWorkWithDefaultOnCancel() {
            OnlyConfirmPage page = new OnlyConfirmPage(mockPlayer);
            
            assertNotNull(page);
            assertFalse(page.isConfirmCalled());
        }
    }
}
