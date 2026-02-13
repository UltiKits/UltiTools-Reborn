package com.ultikits.plugins.backup.commands;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupCommand Tests")
class BackupCommandTest {

    private UltiToolsPlugin plugin;
    private BackupService backupService;
    private BackupCommand command;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        plugin = UltiBackupTestHelper.getMockPlugin();
        backupService = mock(BackupService.class);
        command = new BackupCommand();
        UltiBackupTestHelper.setField(command, "plugin", plugin);
        UltiBackupTestHelper.setField(command, "backupService", backupService);

        playerUuid = UUID.randomUUID();
        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    // ==================== listBackups ====================

    @Nested
    @DisplayName("listBackups")
    class ListBackups {

        @Test
        @DisplayName("Should show no_backups message when empty")
        void noBackups() {
            when(backupService.getBackups(playerUuid)).thenReturn(Collections.emptyList());

            command.listBackups(player);

            verify(player).sendMessage("backup.message.no_backups");
        }

        @Test
        @DisplayName("Should list up to 5 backups")
        void listsBackups() {
            List<BackupMetadata> backups = createBackupList(7);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);

            command.listBackups(player);

            verify(player).sendMessage("backup.message.list_header");
            // 5 list items shown (i18n returns key as-is, replace returns the key string)
            verify(player, atLeast(5)).sendMessage(argThat(
                    (String msg) -> msg.startsWith("backup.message.list_item")));
        }

        @Test
        @DisplayName("Should not show 'more' message when <= 5 backups")
        void noMoreMessage() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(3));

            command.listBackups(player);

            verify(player, never()).sendMessage(argThat(
                    (String msg) -> msg.contains("list_more")));
        }
    }

    // ==================== createBackup ====================

    @Nested
    @DisplayName("createBackup")
    class CreateBackup {

        @Test
        @DisplayName("Should deny when no permission")
        void noPermission() {
            when(player.hasPermission("ultibackup.create")).thenReturn(false);

            command.createBackup(player);

            verify(player).sendMessage("backup.message.no_permission");
            verify(backupService, never()).createBackup(any(), anyString());
        }

        @Test
        @DisplayName("Should create backup when permitted")
        void withPermission() {
            when(player.hasPermission("ultibackup.create")).thenReturn(true);
            when(backupService.createBackup(player, "MANUAL"))
                    .thenReturn(BackupMetadata.builder().build());

            command.createBackup(player);

            verify(backupService).createBackup(player, "MANUAL");
            verify(player).sendMessage("backup.message.created");
        }

        @Test
        @DisplayName("Should show failure message when service returns null")
        void createFails() {
            when(player.hasPermission("ultibackup.create")).thenReturn(true);
            when(backupService.createBackup(player, "MANUAL")).thenReturn(null);

            command.createBackup(player);

            verify(player).sendMessage("backup.message.create_failed");
        }
    }

    // ==================== restoreBackup ====================

    @Nested
    @DisplayName("restoreBackup")
    class RestoreBackup {

        @Test
        @DisplayName("Should reject invalid number (0)")
        void invalidNumberZero() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(3));

            command.restoreBackup(player, 0);

            verify(player).sendMessage("backup.message.invalid_number");
        }

        @Test
        @DisplayName("Should reject number exceeding list size")
        void numberTooLarge() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(3));

            command.restoreBackup(player, 4);

            verify(player).sendMessage("backup.message.invalid_number");
        }

        @Test
        @DisplayName("Should reject when backup list is empty")
        void emptyList() {
            when(backupService.getBackups(playerUuid)).thenReturn(Collections.emptyList());

            command.restoreBackup(player, 1);

            verify(player).sendMessage("backup.message.invalid_number");
        }

        @Test
        @DisplayName("Should restore valid backup by index")
        void validRestore() {
            List<BackupMetadata> backups = createBackupList(3);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);
            when(backupService.restoreBackup(eq(player), any()))
                    .thenReturn(BackupService.RestoreResult.SUCCESS);

            command.restoreBackup(player, 2);

            verify(backupService).restoreBackup(player, backups.get(1));
            verify(player).sendMessage("backup.message.restored");
        }
    }

    // ==================== saveAllPlayers ====================

    @Nested
    @DisplayName("saveAllPlayers")
    class SaveAllPlayers {

        @Test
        @DisplayName("Should deny when no admin permission")
        void noPermission() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            command.saveAllPlayers(player);

            verify(player).sendMessage("backup.message.no_permission");
        }

        @Test
        @DisplayName("Should call saveAllOnlinePlayers when permitted")
        void withPermission() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);
            when(backupService.saveAllOnlinePlayers()).thenReturn(5);

            command.saveAllPlayers(player);

            verify(backupService).saveAllOnlinePlayers();
        }
    }

    // ==================== help ====================

    @Nested
    @DisplayName("help")
    class Help {

        @Test
        @DisplayName("Should show base help for non-admin")
        void nonAdmin() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            command.help(player);

            verify(player).sendMessage("backup.help.header");
            verify(player).sendMessage("backup.help.open");
            verify(player, never()).sendMessage("backup.help.saveall");
        }

        @Test
        @DisplayName("Should show admin help for admin")
        void admin() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            command.help(player);

            verify(player).sendMessage("backup.help.header");
            verify(player).sendMessage("backup.help.saveall");
            verify(player).sendMessage("backup.help.admin");
        }
    }

    // ==================== Tab Completion ====================

    @Nested
    @DisplayName("Tab Completion")
    class TabCompletion {

        @Test
        @DisplayName("Should suggest all subcommands")
        void subcommands() {
            List<String> suggestions = command.suggestSubcommands();

            assertThat(suggestions).containsExactly(
                    "list", "create", "restore", "help", "admin", "saveall");
        }
    }

    // ==================== restoreBackup handleRestore results ====================

    @Nested
    @DisplayName("handleRestore result paths")
    class HandleRestoreResults {

        @Test
        @DisplayName("Should show checksum_failed message")
        void checksumFailed() {
            List<BackupMetadata> backups = createBackupList(3);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);
            when(backupService.restoreBackup(eq(player), any()))
                    .thenReturn(BackupService.RestoreResult.CHECKSUM_FAILED);

            command.restoreBackup(player, 1);

            verify(player).sendMessage("backup.message.checksum_failed");
            verify(player).sendMessage("backup.message.checksum_hint");
        }

        @Test
        @DisplayName("Should show not_found message")
        void notFound() {
            List<BackupMetadata> backups = createBackupList(3);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);
            when(backupService.restoreBackup(eq(player), any()))
                    .thenReturn(BackupService.RestoreResult.NOT_FOUND);

            command.restoreBackup(player, 1);

            verify(player).sendMessage("backup.message.not_found");
        }

        @Test
        @DisplayName("Should show load_failed message")
        void loadFailed() {
            List<BackupMetadata> backups = createBackupList(3);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);
            when(backupService.restoreBackup(eq(player), any()))
                    .thenReturn(BackupService.RestoreResult.LOAD_FAILED);

            command.restoreBackup(player, 1);

            verify(player).sendMessage("backup.message.load_failed");
        }

        @Test
        @DisplayName("Should show restore_failed message")
        void restoreFailed() {
            List<BackupMetadata> backups = createBackupList(3);
            when(backupService.getBackups(playerUuid)).thenReturn(backups);
            when(backupService.restoreBackup(eq(player), any()))
                    .thenReturn(BackupService.RestoreResult.RESTORE_FAILED);

            command.restoreBackup(player, 1);

            verify(player).sendMessage("backup.message.restore_failed");
        }
    }

    // ==================== forceRestoreBackup ====================

    @Nested
    @DisplayName("forceRestoreBackup")
    class ForceRestoreBackup {

        @Test
        @DisplayName("Should reject invalid number")
        void invalidNumber() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(3));

            command.forceRestoreBackup(player, 0);

            verify(player).sendMessage("backup.message.invalid_number");
        }

        @Test
        @DisplayName("Should reject number too large")
        void numberTooLarge() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(3));

            command.forceRestoreBackup(player, 5);

            verify(player).sendMessage("backup.message.invalid_number");
        }
    }

    // ==================== adminBackups ====================

    @Nested
    @DisplayName("adminBackups")
    class AdminBackups {

        @Test
        @DisplayName("Should deny when no admin permission")
        void noPermission() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                command.adminBackups(player, "OtherPlayer");
            }

            verify(player).sendMessage("backup.message.no_permission");
        }

        @Test
        @DisplayName("Should show player_not_found when target never played")
        void playerNotFound() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                OfflinePlayer offlineTarget = mock(OfflinePlayer.class);
                when(offlineTarget.hasPlayedBefore()).thenReturn(false);
                when(offlineTarget.isOnline()).thenReturn(false);
                bukkitMock.when(() -> Bukkit.getOfflinePlayer("Unknown"))
                        .thenReturn(offlineTarget);

                command.adminBackups(player, "Unknown");
            }

            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("player_not_found")));
        }
    }

    // ==================== adminCreateBackup ====================

    @Nested
    @DisplayName("adminCreateBackup")
    class AdminCreateBackup {

        @Test
        @DisplayName("Should deny when no admin permission")
        void noPermission() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                command.adminCreateBackup(player, "Target");
            }

            verify(player).sendMessage("backup.message.no_permission");
        }

        @Test
        @DisplayName("Should show player_offline when target not online")
        void targetOffline() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("OfflineGuy"))
                        .thenReturn(null);

                command.adminCreateBackup(player, "OfflineGuy");
            }

            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("player_offline")));
        }

        @Test
        @DisplayName("Should create backup for online target")
        void createForTarget() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            Player target = UltiBackupTestHelper.createMockPlayer("Target", UUID.randomUUID());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("Target"))
                        .thenReturn(target);
                when(backupService.createBackup(target, "ADMIN"))
                        .thenReturn(BackupMetadata.builder().build());

                command.adminCreateBackup(player, "Target");
            }

            verify(backupService).createBackup(target, "ADMIN");
            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("admin_created")));
        }

        @Test
        @DisplayName("Should show failure when admin create returns null")
        void createFailsForAdmin() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            Player target = UltiBackupTestHelper.createMockPlayer("Target", UUID.randomUUID());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(() -> Bukkit.getPlayerExact("Target"))
                        .thenReturn(target);
                when(backupService.createBackup(target, "ADMIN"))
                        .thenReturn(null);

                command.adminCreateBackup(player, "Target");
            }

            verify(player).sendMessage("backup.message.create_failed");
        }
    }

    // ==================== help full coverage ====================

    @Nested
    @DisplayName("help full paths")
    class HelpFull {

        @Test
        @DisplayName("Should show all base help lines")
        void allBaseLines() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(false);

            command.help(player);

            verify(player).sendMessage("backup.help.list");
            verify(player).sendMessage("backup.help.create");
            verify(player).sendMessage("backup.help.restore");
            verify(player).sendMessage("backup.help.restore_force");
        }

        @Test
        @DisplayName("Should show admin_create line for admin")
        void adminCreateLine() {
            when(player.hasPermission("ultibackup.admin")).thenReturn(true);

            command.help(player);

            verify(player).sendMessage("backup.help.admin_create");
        }
    }

    // ==================== listBackups with >5 ====================

    @Nested
    @DisplayName("listBackups more message")
    class ListBackupsMore {

        @Test
        @DisplayName("Should show 'more' message when >5 backups")
        void showsMoreMessage() {
            when(backupService.getBackups(playerUuid)).thenReturn(createBackupList(8));

            command.listBackups(player);

            verify(player).sendMessage(argThat(
                    (String msg) -> msg.contains("list_more")));
        }
    }

    // --- Helper ---

    private List<BackupMetadata> createBackupList(int count) {
        List<BackupMetadata> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BackupMetadata m = BackupMetadata.builder()
                    .playerUuid(playerUuid.toString())
                    .playerName("TestPlayer")
                    .backupTime(1000L + i)
                    .backupReason("MANUAL")
                    .worldName("world")
                    .build();
            m.setId("id-" + i);
            list.add(m);
        }
        return list;
    }
}
