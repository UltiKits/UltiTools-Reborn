package com.ultikits.plugins.backup.service;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupContent;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for BackupService.
 * <p>
 * Note: BackupService.createBackup() calls UltiTools.getInstance().getDataFolder()
 * which cannot be mocked (UltiTools is final). We test service methods that don't
 * require the full creation pipeline via UltiTools, and test the restore/delete/query
 * paths which can be fully exercised with injected dependencies.
 */
@DisplayName("BackupService Tests")
class BackupServiceTest {

    @TempDir
    Path tempDir;

    private BackupService service;
    private BackupConfig config;
    @SuppressWarnings("unchecked")
    private DataOperator<BackupMetadata> dataOperator = mock(DataOperator.class);

    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();

        config = UltiBackupTestHelper.createDefaultConfig();

        service = new BackupService();

        // Inject dependencies via reflection (normally done by @Autowired / @PostConstruct)
        UltiBackupTestHelper.setField(service, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(service, "config", config);
        UltiBackupTestHelper.setField(service, "dataOperator", dataOperator);
        UltiBackupTestHelper.setField(service, "backupsDirectory",
                new File(tempDir.toFile(), "backups"));
        new File(tempDir.toFile(), "backups").mkdirs();

        playerUuid = UUID.randomUUID();
        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    // ==================== getBackups ====================

    @Nested
    @DisplayName("getBackups")
    class GetBackups {

        @Test
        @DisplayName("Should query by player UUID")
        void queriesByUuid() {
            Query<BackupMetadata> query = mock(Query.class);
            when(dataOperator.query()).thenReturn(query);
            when(query.where("player_uuid")).thenReturn(query);
            when(query.eq(playerUuid.toString())).thenReturn(query);
            when(query.list()).thenReturn(new ArrayList<>());

            service.getBackups(playerUuid);

            verify(dataOperator).query();
            verify(query).where("player_uuid");
            verify(query).eq(playerUuid.toString());
            verify(query).list();
        }

        @Test
        @DisplayName("Should sort backups by time descending")
        void sortsByTimeDescending() {
            BackupMetadata older = BackupMetadata.builder().backupTime(1000L).build();
            BackupMetadata newer = BackupMetadata.builder().backupTime(2000L).build();
            BackupMetadata newest = BackupMetadata.builder().backupTime(3000L).build();

            Query<BackupMetadata> query = mock(Query.class);
            when(dataOperator.query()).thenReturn(query);
            when(query.where("player_uuid")).thenReturn(query);
            when(query.eq(playerUuid.toString())).thenReturn(query);
            when(query.list()).thenReturn(new ArrayList<>(Arrays.asList(older, newest, newer)));

            List<BackupMetadata> result = service.getBackups(playerUuid);

            assertThat(result).extracting(BackupMetadata::getBackupTime)
                    .containsExactly(3000L, 2000L, 1000L);
        }

        @Test
        @DisplayName("Should return empty list when no backups")
        void emptyList() {
            Query<BackupMetadata> query = mock(Query.class);
            when(dataOperator.query()).thenReturn(query);
            when(query.where("player_uuid")).thenReturn(query);
            when(query.eq(playerUuid.toString())).thenReturn(query);
            when(query.list()).thenReturn(new ArrayList<>());

            List<BackupMetadata> result = service.getBackups(playerUuid);

            assertThat(result).isEmpty();
        }
    }

    // ==================== restoreBackup ====================

    @Nested
    @DisplayName("restoreBackup")
    class RestoreBackup {

        @Test
        @DisplayName("Should return NOT_FOUND for null metadata")
        void nullMetadata() {
            BackupService.RestoreResult result = service.restoreBackup(player, null);
            assertThat(result).isEqualTo(BackupService.RestoreResult.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return CHECKSUM_FAILED for null file path")
        void nullFilePath() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .checksum("abc")
                    .build();

            BackupService.RestoreResult result = service.restoreBackup(player, metadata);
            assertThat(result).isEqualTo(BackupService.RestoreResult.CHECKSUM_FAILED);
        }
    }

    // ==================== forceRestore ====================

    @Nested
    @DisplayName("forceRestore")
    class ForceRestore {

        @Test
        @DisplayName("Should return NOT_FOUND for null metadata")
        void nullMetadata() {
            BackupService.RestoreResult result = service.forceRestore(player, null);
            assertThat(result).isEqualTo(BackupService.RestoreResult.NOT_FOUND);
        }

        @Test
        @DisplayName("Should return LOAD_FAILED when file path is null")
        void loadFailedNullPath() {
            BackupMetadata metadata = BackupMetadata.builder().build();

            BackupService.RestoreResult result = service.forceRestore(player, metadata);
            assertThat(result).isEqualTo(BackupService.RestoreResult.LOAD_FAILED);
        }
    }

    // ==================== verifyChecksum ====================

    @Nested
    @DisplayName("verifyChecksum")
    class VerifyChecksum {

        @Test
        @DisplayName("Should return false for null metadata")
        void nullMetadata() {
            assertThat(service.verifyChecksum(null)).isFalse();
        }

        @Test
        @DisplayName("Should return false for null file path")
        void nullFilePath() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            assertThat(service.verifyChecksum(metadata)).isFalse();
        }

    }

    // ==================== loadBackupContent ====================

    @Nested
    @DisplayName("loadBackupContent")
    class LoadBackupContent {

        @Test
        @DisplayName("Should return null for null metadata")
        void nullMetadata() {
            assertThat(service.loadBackupContent(null)).isNull();
        }

        @Test
        @DisplayName("Should return null for null file path")
        void nullFilePath() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            assertThat(service.loadBackupContent(metadata)).isNull();
        }

    }

    // ==================== deleteBackup ====================

    @Nested
    @DisplayName("deleteBackup")
    class DeleteBackup {

        @Test
        @DisplayName("Should return false for null metadata")
        void nullMetadata() {
            assertThat(service.deleteBackup((BackupMetadata) null)).isFalse();
        }

        @Test
        @DisplayName("Should call onDelete and delete from database")
        void deletesMetadataAndFile() throws Exception {
            File backupsDir = new File(tempDir.toFile(), "backups");
            backupsDir.mkdirs();
            File backupFile = new File(backupsDir, "to_delete.yml");
            backupFile.createNewFile();

            // Use a metadata with filePath=null to avoid UltiTools.getInstance() call
            // in onDelete(). The file won't be deleted but DB delete still happens.
            BackupMetadata metadata = BackupMetadata.builder().build();
            metadata.setId("delete-id");

            service.deleteBackup(metadata);

            verify(dataOperator).delById("delete-id");
        }

        @Test
        @DisplayName("Should delete by ID when metadata exists in DB")
        void deleteById() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            metadata.setId("lookup-id");

            when(dataOperator.getById("lookup-id")).thenReturn(metadata);

            boolean result = service.deleteBackup("lookup-id");

            assertThat(result).isTrue();
            verify(dataOperator).delById("lookup-id");
        }

        @Test
        @DisplayName("Should return false when ID not found in DB")
        void deleteByIdNotFound() {
            when(dataOperator.getById("missing-id")).thenReturn(null);

            boolean result = service.deleteBackup("missing-id");

            assertThat(result).isFalse();
        }
    }

    // ==================== getBackup by ID ====================

    @Nested
    @DisplayName("getBackup by ID")
    class GetBackupById {

        @Test
        @DisplayName("Should delegate to dataOperator.getById")
        void delegatesGetById() {
            BackupMetadata expected = BackupMetadata.builder().build();
            when(dataOperator.getById("some-id")).thenReturn(expected);

            BackupMetadata result = service.getBackup("some-id");

            assertThat(result).isSameAs(expected);
            verify(dataOperator).getById("some-id");
        }
    }

    // ==================== Config and Directory Getters ====================

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("Should return config")
        void returnsConfig() {
            assertThat(service.getConfig()).isSameAs(config);
        }

        @Test
        @DisplayName("Should return backups directory")
        void returnsBackupsDir() {
            assertThat(service.getBackupsDirectory()).isNotNull();
            assertThat(service.getBackupsDirectory().getName()).isEqualTo("backups");
        }
    }

    // ==================== saveAllOnlinePlayers ====================

    @Nested
    @DisplayName("saveAllOnlinePlayers")
    class SaveAllOnlinePlayers {

        @Test
        @DisplayName("Should return 0 when no online players")
        void noPlayers() {
            BackupService spyService = spy(service);
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());

                int count = spyService.saveAllOnlinePlayers();

                assertThat(count).isZero();
            }
        }

        @Test
        @DisplayName("Should skip players without permission")
        void skipsNoPermission() {
            Player noPermPlayer = UltiBackupTestHelper.createMockPlayer("NoPerms", UUID.randomUUID());
            when(noPermPlayer.hasPermission("ultibackup.auto")).thenReturn(false);

            BackupService spyService = spy(service);
            doReturn(null).when(spyService).createBackup(any(), anyString());

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getOnlinePlayers)
                        .thenReturn(Collections.singletonList(noPermPlayer));

                int count = spyService.saveAllOnlinePlayers();

                assertThat(count).isZero();
                verify(spyService, never()).createBackup(any(), anyString());
            }
        }

        @Test
        @DisplayName("Should count successful backups")
        void countsSuccess() {
            Player p1 = UltiBackupTestHelper.createMockPlayer("P1", UUID.randomUUID());
            Player p2 = UltiBackupTestHelper.createMockPlayer("P2", UUID.randomUUID());

            BackupService spyService = spy(service);
            doReturn(BackupMetadata.builder().build()).when(spyService).createBackup(p1, "ADMIN");
            doReturn(null).when(spyService).createBackup(p2, "ADMIN");

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                bukkitMock.when(Bukkit::getOnlinePlayers)
                        .thenReturn(Arrays.asList(p1, p2));

                int count = spyService.saveAllOnlinePlayers();

                assertThat(count).isEqualTo(1);
            }
        }
    }

    // ==================== forceRestore with content ====================

    @Nested
    @DisplayName("forceRestore with content")
    class ForceRestoreWithContent {

        @Test
        @DisplayName("Should return SUCCESS when content restores successfully")
        void successfulRestore() {
            BackupService spyService = spy(service);

            BackupMetadata metadata = BackupMetadata.builder().build();
            BackupContent content = BackupContent.builder()
                    .inventoryContents(null)
                    .expLevel(10)
                    .build();

            doReturn(content).when(spyService).loadBackupContent(metadata);

            BackupService.RestoreResult result = spyService.forceRestore(player, metadata);

            assertThat(result).isEqualTo(BackupService.RestoreResult.SUCCESS);
        }

        @Test
        @DisplayName("Should return RESTORE_FAILED when restoreToPlayer throws")
        void restoreThrows() {
            BackupService spyService = spy(service);

            BackupMetadata metadata = BackupMetadata.builder().build();
            BackupContent content = mock(BackupContent.class);
            doThrow(new RuntimeException("fail")).when(content)
                    .restoreToPlayer(any(), anyBoolean(), anyBoolean(), anyBoolean());

            doReturn(content).when(spyService).loadBackupContent(metadata);

            BackupService.RestoreResult result = spyService.forceRestore(player, metadata);

            assertThat(result).isEqualTo(BackupService.RestoreResult.RESTORE_FAILED);
        }
    }

    // ==================== restoreBackup integration ====================

    @Nested
    @DisplayName("restoreBackup integration")
    class RestoreBackupIntegration {

        @Test
        @DisplayName("Should call forceRestore when checksum passes")
        void callsForceRestoreOnSuccess() {
            BackupService spyService = spy(service);
            BackupMetadata metadata = BackupMetadata.builder().build();

            doReturn(true).when(spyService).verifyChecksum(metadata);
            doReturn(BackupService.RestoreResult.SUCCESS).when(spyService).forceRestore(player, metadata);

            BackupService.RestoreResult result = spyService.restoreBackup(player, metadata);

            assertThat(result).isEqualTo(BackupService.RestoreResult.SUCCESS);
            verify(spyService).forceRestore(player, metadata);
        }

        @Test
        @DisplayName("Should return CHECKSUM_FAILED when checksum fails")
        void checksumFails() {
            BackupService spyService = spy(service);
            BackupMetadata metadata = BackupMetadata.builder().build();

            doReturn(false).when(spyService).verifyChecksum(metadata);

            BackupService.RestoreResult result = spyService.restoreBackup(player, metadata);

            assertThat(result).isEqualTo(BackupService.RestoreResult.CHECKSUM_FAILED);
        }
    }

    // ==================== verifyChecksum with real file ====================

    @Nested
    @DisplayName("verifyChecksum with real file")
    class VerifyChecksumReal {

        @Test
        @DisplayName("Should return true for valid backup file")
        void validFile() throws Exception {
            // Create a real backup file
            BackupContent content = BackupContent.builder()
                    .inventoryContents("test-data")
                    .expLevel(5)
                    .build();
            File backupFile = tempDir.resolve("verify_test.yml").toFile();
            String checksum = content.saveToFile(backupFile);

            // Create metadata with a spy that returns our temp file
            BackupMetadata metadata = spy(BackupMetadata.builder()
                    .filePath("verify_test.yml")
                    .checksum(checksum)
                    .build());
            doReturn(backupFile).when(metadata).getBackupFile();

            assertThat(service.verifyChecksum(metadata)).isTrue();
        }

        @Test
        @DisplayName("Should return false for tampered file")
        void tamperedFile() throws Exception {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("original-data")
                    .build();
            File backupFile = tempDir.resolve("tampered_test.yml").toFile();
            String checksum = content.saveToFile(backupFile);

            // Tamper with file
            java.nio.file.Files.write(backupFile.toPath(),
                    "\nextra: tampered".getBytes(), java.nio.file.StandardOpenOption.APPEND);

            BackupMetadata metadata = spy(BackupMetadata.builder()
                    .filePath("tampered_test.yml")
                    .checksum(checksum)
                    .build());
            doReturn(backupFile).when(metadata).getBackupFile();

            assertThat(service.verifyChecksum(metadata)).isFalse();
        }

        @Test
        @DisplayName("Should return false when file does not exist")
        void fileNotExist() {
            File nonExistent = tempDir.resolve("nonexistent.yml").toFile();

            BackupMetadata metadata = spy(BackupMetadata.builder()
                    .filePath("nonexistent.yml")
                    .checksum("abc")
                    .build());
            doReturn(nonExistent).when(metadata).getBackupFile();

            assertThat(service.verifyChecksum(metadata)).isFalse();
        }
    }

    // ==================== loadBackupContent with real file ====================

    @Nested
    @DisplayName("loadBackupContent with real file")
    class LoadBackupContentReal {

        @Test
        @DisplayName("Should load content from valid file")
        void loadsContent() throws Exception {
            BackupContent original = BackupContent.builder()
                    .inventoryContents("load-inv")
                    .armorContents("load-armor")
                    .expLevel(20)
                    .build();
            File backupFile = tempDir.resolve("load_test.yml").toFile();
            original.saveToFile(backupFile);

            BackupMetadata metadata = spy(BackupMetadata.builder()
                    .filePath("load_test.yml")
                    .build());
            doReturn(backupFile).when(metadata).getBackupFile();

            BackupContent loaded = service.loadBackupContent(metadata);

            assertThat(loaded).isNotNull();
            assertThat(loaded.getInventoryContents()).isEqualTo("load-inv");
            assertThat(loaded.getArmorContents()).isEqualTo("load-armor");
            assertThat(loaded.getExpLevel()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should return null when file does not exist")
        void fileNotExist() {
            File nonExistent = tempDir.resolve("missing.yml").toFile();

            BackupMetadata metadata = spy(BackupMetadata.builder()
                    .filePath("missing.yml")
                    .build());
            doReturn(nonExistent).when(metadata).getBackupFile();

            assertThat(service.loadBackupContent(metadata)).isNull();
        }
    }

    // ==================== deleteBackup returns true ====================

    @Nested
    @DisplayName("deleteBackup returns true")
    class DeleteBackupReturnsTrue {

        @Test
        @DisplayName("Should return true for valid metadata")
        void returnsTrue() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            metadata.setId("valid-id");

            boolean result = service.deleteBackup(metadata);

            assertThat(result).isTrue();
        }
    }

    // ==================== RestoreResult enum ====================

    @Nested
    @DisplayName("RestoreResult enum")
    class RestoreResultEnum {

        @Test
        @DisplayName("Should have all expected values")
        void allValues() {
            assertThat(BackupService.RestoreResult.values())
                    .containsExactly(
                            BackupService.RestoreResult.SUCCESS,
                            BackupService.RestoreResult.NOT_FOUND,
                            BackupService.RestoreResult.CHECKSUM_FAILED,
                            BackupService.RestoreResult.LOAD_FAILED,
                            BackupService.RestoreResult.RESTORE_FAILED
                    );
        }

        @Test
        @DisplayName("Should support valueOf")
        void valueOf() {
            assertThat(BackupService.RestoreResult.valueOf("SUCCESS"))
                    .isEqualTo(BackupService.RestoreResult.SUCCESS);
        }
    }
}
