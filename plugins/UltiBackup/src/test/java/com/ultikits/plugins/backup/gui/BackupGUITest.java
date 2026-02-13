package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupGUI Tests")
class BackupGUITest {

    private UltiToolsPlugin plugin;
    private BackupService backupService;
    private Player viewer;
    private UUID targetUuid;
    private MockedStatic<Bukkit> bukkitMock;
    private MockedStatic<XVersionUtils> xVersionMock;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        plugin = UltiBackupTestHelper.getMockPlugin();

        backupService = mock(BackupService.class);
        targetUuid = UUID.randomUUID();
        viewer = UltiBackupTestHelper.createMockPlayer("Viewer", UUID.randomUUID());

        // Mock ItemMeta for ItemStack operations
        ItemMeta itemMeta = mock(ItemMeta.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        lenient().when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

        // Mock Bukkit.createInventory and Bukkit.getItemFactory
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString()))
                .thenAnswer(inv -> {
                    Inventory mockInv = mock(Inventory.class);
                    when(mockInv.getHolder()).thenReturn(inv.getArgument(0));
                    return mockInv;
                });
        bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);

        // Mock XVersionUtils for cross-version glass panes
        xVersionMock = mockStatic(XVersionUtils.class);
        xVersionMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                .thenReturn(new ItemStack(Material.GLASS_PANE));
    }

    @AfterEach
    void tearDown() throws Exception {
        xVersionMock.close();
        bukkitMock.close();
        UltiBackupTestHelper.tearDown();
    }

    // ==================== Pagination ====================

    @Nested
    @DisplayName("Pagination")
    class Pagination {

        @Test
        @DisplayName("Should start on page 0")
        void startsAtPageZero() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(5));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getBackupAtSlot(0)).isNotNull();
        }

        @Test
        @DisplayName("Should not go to previous page when on first page")
        void noPreviousOnFirst() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(5));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");
            gui.previousPage();

            assertThat(gui.getBackupAtSlot(0)).isNotNull();
        }

        @Test
        @DisplayName("Should not go past last page when all fit on one page")
        void noPastLastPage() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(10));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");
            gui.nextPage();

            // Still on page 0
            assertThat(gui.getBackupAtSlot(0)).isNotNull();
        }

        @Test
        @DisplayName("Should navigate forward when more than one page")
        void navigateForward() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(50));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");
            gui.nextPage();

            BackupMetadata slotItem = gui.getBackupAtSlot(0);
            assertThat(slotItem).isNotNull();
            assertThat(slotItem.getBackupTime()).isEqualTo(1045L);
        }

        @Test
        @DisplayName("Should navigate back after forward")
        void navigateBack() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(50));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");
            gui.nextPage();
            gui.previousPage();

            BackupMetadata slotItem = gui.getBackupAtSlot(0);
            assertThat(slotItem.getBackupTime()).isEqualTo(1000L);
        }
    }

    // ==================== getBackupAtSlot ====================

    @Nested
    @DisplayName("getBackupAtSlot")
    class GetBackupAtSlot {

        @Test
        @DisplayName("Should return null for negative slot")
        void negativeSlot() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(5));
            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getBackupAtSlot(-1)).isNull();
        }

        @Test
        @DisplayName("Should return null for slot >= 45")
        void slotBeyondItemArea() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(5));
            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getBackupAtSlot(45)).isNull();
        }

        @Test
        @DisplayName("Should return null for slot beyond backup count")
        void slotBeyondCount() {
            when(backupService.getBackups(targetUuid)).thenReturn(createBackupList(3));
            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getBackupAtSlot(3)).isNull();
            assertThat(gui.getBackupAtSlot(10)).isNull();
        }

        @Test
        @DisplayName("Should return correct backup at valid slot")
        void validSlot() {
            List<BackupMetadata> backups = createBackupList(5);
            when(backupService.getBackups(targetUuid)).thenReturn(backups);
            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getBackupAtSlot(2)).isSameAs(backups.get(2));
        }
    }

    // ==================== Refresh ====================

    @Nested
    @DisplayName("Refresh")
    class Refresh {

        @Test
        @DisplayName("Should reload backups from service on refresh")
        void reloadsBackups() {
            when(backupService.getBackups(targetUuid))
                    .thenReturn(createBackupList(3))
                    .thenReturn(createBackupList(5));

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");
            assertThat(gui.getBackupAtSlot(4)).isNull();

            gui.refresh();
            assertThat(gui.getBackupAtSlot(4)).isNotNull();
        }
    }

    // ==================== Properties ====================

    @Nested
    @DisplayName("Properties")
    class Properties {

        @Test
        @DisplayName("Should expose viewer, target UUID, target name, service")
        void properties() {
            when(backupService.getBackups(targetUuid)).thenReturn(Collections.emptyList());

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "TargetName");

            assertThat(gui.getViewer()).isSameAs(viewer);
            assertThat(gui.getTargetUuid()).isEqualTo(targetUuid);
            assertThat(gui.getTargetName()).isEqualTo("TargetName");
            assertThat(gui.getBackupService()).isSameAs(backupService);
        }

        @Test
        @DisplayName("Should implement InventoryHolder")
        void inventoryHolder() {
            when(backupService.getBackups(targetUuid)).thenReturn(Collections.emptyList());

            BackupGUI gui = new BackupGUI(plugin, backupService, viewer, targetUuid, "Target");

            assertThat(gui.getInventory()).isNotNull();
        }
    }

    // --- Helpers ---

    private List<BackupMetadata> createBackupList(int count) {
        List<BackupMetadata> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BackupMetadata m = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("Target")
                    .backupTime(1000L + i)
                    .backupReason("MANUAL")
                    .worldName("world")
                    .locationX(0)
                    .locationY(64)
                    .locationZ(0)
                    .expLevel(10)
                    .build();
            m.setId("id-" + i);
            list.add(m);
        }
        return list;
    }
}
