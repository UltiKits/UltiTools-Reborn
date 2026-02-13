package com.ultikits.plugins.backup.listener;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.gui.BackupPreviewGUI;
import com.ultikits.plugins.backup.service.BackupService;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupListener Tests")
class BackupListenerTest {

    private BackupListener listener;
    private BackupService backupService;
    private BackupConfig config;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();

        backupService = mock(BackupService.class);
        config = UltiBackupTestHelper.createDefaultConfig();
        when(backupService.getConfig()).thenReturn(config);

        listener = new BackupListener();
        UltiBackupTestHelper.setField(listener, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(listener, "backupService", backupService);

        playerUuid = UUID.randomUUID();
        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    // ==================== Death Auto-Backup ====================

    @Nested
    @DisplayName("Player Death Auto-Backup")
    class DeathAutoBackup {

        @Test
        @DisplayName("Should create backup on death when enabled")
        void createOnDeath() {
            when(config.isBackupOnDeath()).thenReturn(true);

            PlayerDeathEvent event = createDeathEvent(player);
            listener.onPlayerDeath(event);

            verify(backupService).createBackup(player, "DEATH");
        }

        @Test
        @DisplayName("Should not create backup when death backup disabled")
        void disabledConfig() {
            when(config.isBackupOnDeath()).thenReturn(false);

            PlayerDeathEvent event = createDeathEvent(player);
            listener.onPlayerDeath(event);

            verify(backupService, never()).createBackup(any(), anyString());
        }

        @Test
        @DisplayName("Should not create backup when player lacks permission")
        void noPermission() {
            when(config.isBackupOnDeath()).thenReturn(true);
            when(player.hasPermission("ultibackup.auto")).thenReturn(false);

            PlayerDeathEvent event = createDeathEvent(player);
            listener.onPlayerDeath(event);

            verify(backupService, never()).createBackup(any(), anyString());
        }
    }

    // ==================== Quit Auto-Backup ====================

    @Nested
    @DisplayName("Player Quit Auto-Backup")
    class QuitAutoBackup {

        @Test
        @DisplayName("Should create backup on quit when enabled")
        void createOnQuit() {
            when(config.isBackupOnQuit()).thenReturn(true);

            PlayerQuitEvent event = new PlayerQuitEvent(player, "left");
            listener.onPlayerQuit(event);

            verify(backupService).createBackup(player, "QUIT");
        }

        @Test
        @DisplayName("Should not create backup when quit backup disabled")
        void disabledConfig() {
            when(config.isBackupOnQuit()).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player, "left");
            listener.onPlayerQuit(event);

            verify(backupService, never()).createBackup(any(), anyString());
        }

        @Test
        @DisplayName("Should not create backup when player lacks permission")
        void noPermission() {
            when(config.isBackupOnQuit()).thenReturn(true);
            when(player.hasPermission("ultibackup.auto")).thenReturn(false);

            PlayerQuitEvent event = new PlayerQuitEvent(player, "left");
            listener.onPlayerQuit(event);

            verify(backupService, never()).createBackup(any(), anyString());
        }
    }

    // ==================== BackupGUI Click Handling ====================

    @Nested
    @DisplayName("BackupGUI Click Handling")
    class BackupGUIClicks {

        @Test
        @DisplayName("Should cancel all clicks in BackupGUI")
        void cancelsClicks() {
            BackupGUI gui = mock(BackupGUI.class);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 10);

            listener.onBackupGUIClick(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("Should call previousPage on slot 45")
        void previousPage() {
            BackupGUI gui = mock(BackupGUI.class);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 45);

            listener.onBackupGUIClick(event);

            verify(gui).previousPage();
        }

        @Test
        @DisplayName("Should call nextPage on slot 53")
        void nextPage() {
            BackupGUI gui = mock(BackupGUI.class);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 53);

            listener.onBackupGUIClick(event);

            verify(gui).nextPage();
        }

        @Test
        @DisplayName("Should ignore clicks in non-BackupGUI inventories")
        void ignoresNonBackupGUI() {
            InventoryClickEvent event = createClickEventForHolder(null, player, 10);

            listener.onBackupGUIClick(event);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    // ==================== PreviewGUI Click Handling ====================

    @Nested
    @DisplayName("BackupPreviewGUI Click Handling")
    class PreviewGUIClicks {

        @Test
        @DisplayName("Should cancel all clicks in PreviewGUI")
        void cancelsClicks() {
            BackupPreviewGUI gui = mock(BackupPreviewGUI.class);
            when(gui.isTabSlot(10)).thenReturn(false);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 10);

            listener.onPreviewGUIClick(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("Should handle tab click in PreviewGUI")
        void tabClick() {
            BackupPreviewGUI gui = mock(BackupPreviewGUI.class);
            when(gui.isTabSlot(46)).thenReturn(true);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 46);

            listener.onPreviewGUIClick(event);

            verify(gui).handleTabClick(46);
        }

        @Test
        @DisplayName("Should not handle non-tab click in PreviewGUI")
        void nonTabClick() {
            BackupPreviewGUI gui = mock(BackupPreviewGUI.class);
            when(gui.isTabSlot(20)).thenReturn(false);
            InventoryClickEvent event = createClickEventForHolder(gui, player, 20);

            listener.onPreviewGUIClick(event);

            verify(gui, never()).handleTabClick(anyInt());
        }

        @Test
        @DisplayName("Should ignore clicks in non-PreviewGUI inventories")
        void ignoresNonPreviewGUI() {
            InventoryClickEvent event = createClickEventForHolder(null, player, 10);

            listener.onPreviewGUIClick(event);

            assertThat(event.isCancelled()).isFalse();
        }
    }

    // ==================== Slot 47 Create Button ====================

    @Nested
    @DisplayName("BackupGUI Create Button (slot 47)")
    class CreateButton {

        @Test
        @DisplayName("Should create backup when admin clicks slot 47 and target online")
        void adminCreateSuccess() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getTargetName()).thenReturn("Target");

            when(player.hasPermission("ultibackup.admin")).thenReturn(true);
            when(backupService.createBackup(targetPlayer, "MANUAL"))
                    .thenReturn(BackupMetadata.builder().build());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 47);
                listener.onBackupGUIClick(event);
            }

            verify(backupService).createBackup(targetPlayer, "MANUAL");
            verify(player).sendMessage("backup.message.created");
            verify(gui).refresh();
        }

        @Test
        @DisplayName("Should show failure when create returns null")
        void createFailed() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getTargetName()).thenReturn("Target");

            when(player.hasPermission("ultibackup.admin")).thenReturn(true);
            when(backupService.createBackup(targetPlayer, "MANUAL")).thenReturn(null);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 47);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage("backup.message.create_failed");
        }

        @Test
        @DisplayName("Should show player_offline when target not online")
        void targetOffline() {
            UUID targetUuid = UUID.randomUUID();
            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getTargetName()).thenReturn("OfflineGuy");

            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(null);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 47);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("player_offline")));
        }

        @Test
        @DisplayName("Should allow self-backup when own UUID matches")
        void selfBackup() {
            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(playerUuid);
            when(gui.getTargetName()).thenReturn("TestPlayer");

            // Not admin, but own UUID
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);
            when(backupService.createBackup(player, "MANUAL"))
                    .thenReturn(BackupMetadata.builder().build());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(player);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 47);
                listener.onBackupGUIClick(event);
            }

            verify(backupService).createBackup(player, "MANUAL");
        }
    }

    // ==================== Backup Item Click Handling ====================

    @Nested
    @DisplayName("Backup Item Clicks (slots 0-44)")
    class BackupItemClicks {

        @Test
        @DisplayName("Should ignore click when no backup at slot")
        void nullBackup() {
            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getBackupAtSlot(10)).thenReturn(null);

            InventoryClickEvent event = createClickEventForHolder(gui, player, 10);
            listener.onBackupGUIClick(event);

            verify(backupService, never()).restoreBackup(any(), any());
        }

        @Test
        @DisplayName("Should right-click delete when player has delete permission")
        void rightClickDelete() {
            BackupGUI gui = mock(BackupGUI.class);
            BackupMetadata backup = BackupMetadata.builder().build();
            backup.setId("del-id");
            when(gui.getBackupAtSlot(5)).thenReturn(backup);

            when(player.hasPermission("ultibackup.delete")).thenReturn(true);

            InventoryClickEvent event = createRightClickEventForHolder(gui, player, 5);
            listener.onBackupGUIClick(event);

            verify(backupService).deleteBackup(backup);
            verify(player).sendMessage("backup.message.deleted");
            verify(gui).refresh();
        }

        @Test
        @DisplayName("Should deny delete when no permission")
        void rightClickDeleteNoPermission() {
            BackupGUI gui = mock(BackupGUI.class);
            BackupMetadata backup = BackupMetadata.builder().build();
            backup.setId("del-id");
            when(gui.getBackupAtSlot(5)).thenReturn(backup);

            when(player.hasPermission("ultibackup.delete")).thenReturn(false);
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            InventoryClickEvent event = createRightClickEventForHolder(gui, player, 5);
            listener.onBackupGUIClick(event);

            verify(backupService, never()).deleteBackup(any(BackupMetadata.class));
            verify(player).sendMessage("backup.message.no_permission");
        }
    }

    // ==================== Left-click Restore via handleRestore ====================

    @Nested
    @DisplayName("Left-click Restore (handleRestore)")
    class LeftClickRestore {

        @Test
        @DisplayName("Should restore on left-click and show success")
        void restoreSuccess() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);
            BackupMetadata backup = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("Target")
                    .build();
            backup.setId("restore-id");

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getBackupAtSlot(3)).thenReturn(backup);

            when(backupService.restoreBackup(targetPlayer, backup))
                    .thenReturn(BackupService.RestoreResult.SUCCESS);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 3);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage("backup.message.restored");
            verify(player).closeInventory();
        }

        @Test
        @DisplayName("Should show player_offline when target is offline")
        void targetOffline() {
            UUID targetUuid = UUID.randomUUID();
            BackupMetadata backup = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("OfflineTarget")
                    .build();
            backup.setId("offline-id");

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getBackupAtSlot(3)).thenReturn(backup);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(null);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 3);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("player_offline")));
        }

        @Test
        @DisplayName("Should show not_found on NOT_FOUND result")
        void notFound() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);
            BackupMetadata backup = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("Target")
                    .build();

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getBackupAtSlot(3)).thenReturn(backup);

            when(backupService.restoreBackup(targetPlayer, backup))
                    .thenReturn(BackupService.RestoreResult.NOT_FOUND);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 3);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage("backup.message.not_found");
        }

        @Test
        @DisplayName("Should show load_failed on LOAD_FAILED result")
        void loadFailed() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);
            BackupMetadata backup = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("Target")
                    .build();

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getBackupAtSlot(3)).thenReturn(backup);

            when(backupService.restoreBackup(targetPlayer, backup))
                    .thenReturn(BackupService.RestoreResult.LOAD_FAILED);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 3);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage("backup.message.load_failed");
        }

        @Test
        @DisplayName("Should show restore_failed on RESTORE_FAILED result")
        void restoreFailed() {
            UUID targetUuid = UUID.randomUUID();
            Player targetPlayer = UltiBackupTestHelper.createMockPlayer("Target", targetUuid);
            BackupMetadata backup = BackupMetadata.builder()
                    .playerUuid(targetUuid.toString())
                    .playerName("Target")
                    .build();

            BackupGUI gui = mock(BackupGUI.class);
            when(gui.getTargetUuid()).thenReturn(targetUuid);
            when(gui.getBackupAtSlot(3)).thenReturn(backup);

            when(backupService.restoreBackup(targetPlayer, backup))
                    .thenReturn(BackupService.RestoreResult.RESTORE_FAILED);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(targetPlayer);

                InventoryClickEvent event = createClickEventForHolder(gui, player, 3);
                listener.onBackupGUIClick(event);
            }

            verify(player).sendMessage("backup.message.restore_failed");
        }

        // Note: CHECKSUM_FAILED path opens ForceRestoreConfirmPage which requires
        // ObliviateInv's InventoryAPI to be initialized. Cannot test without the framework.
    }

    // --- Helpers ---

    private PlayerDeathEvent createDeathEvent(Player player) {
        return new PlayerDeathEvent(player, new ArrayList<>(), 0, "died");
    }

    /**
     * Create an InventoryClickEvent backed by an inventory whose holder is the given object.
     * If holder is null, simulates a non-custom inventory.
     */
    private InventoryClickEvent createClickEventForHolder(Object holder, Player whoClicked, int rawSlot) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder instanceof org.bukkit.inventory.InventoryHolder
                ? (org.bukkit.inventory.InventoryHolder) holder : null);

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(whoClicked);

        return new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private InventoryClickEvent createRightClickEventForHolder(Object holder, Player whoClicked, int rawSlot) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder instanceof org.bukkit.inventory.InventoryHolder
                ? (org.bukkit.inventory.InventoryHolder) holder : null);

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(whoClicked);

        return new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.RIGHT, InventoryAction.PICKUP_HALF);
    }
}
