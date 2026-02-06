package com.ultikits.plugins.backup.config;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupConfig Tests")
class BackupConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have auto backup enabled by default")
        void autoBackupEnabled() {
            BackupConfig config = createRealConfig();
            assertThat(config.isAutoBackupEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have 30 minute default interval")
        void defaultInterval() {
            BackupConfig config = createRealConfig();
            assertThat(config.getAutoBackupInterval()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should have backup on death enabled by default")
        void backupOnDeath() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupOnDeath()).isTrue();
        }

        @Test
        @DisplayName("Should have backup on quit enabled by default")
        void backupOnQuit() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupOnQuit()).isTrue();
        }

        @Test
        @DisplayName("Should have max 10 backups per player by default")
        void maxBackups() {
            BackupConfig config = createRealConfig();
            assertThat(config.getMaxBackupsPerPlayer()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have backup armor enabled by default")
        void backupArmor() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupArmor()).isTrue();
        }

        @Test
        @DisplayName("Should have backup enderchest enabled by default")
        void backupEnderchest() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupEnderchest()).isTrue();
        }

        @Test
        @DisplayName("Should have backup exp enabled by default")
        void backupExp() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupExp()).isTrue();
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update auto backup enabled")
        void setAutoBackupEnabled() {
            BackupConfig config = createRealConfig();
            config.setAutoBackupEnabled(false);
            assertThat(config.isAutoBackupEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update auto backup interval")
        void setAutoBackupInterval() {
            BackupConfig config = createRealConfig();
            config.setAutoBackupInterval(60);
            assertThat(config.getAutoBackupInterval()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should update backup on death")
        void setBackupOnDeath() {
            BackupConfig config = createRealConfig();
            config.setBackupOnDeath(false);
            assertThat(config.isBackupOnDeath()).isFalse();
        }

        @Test
        @DisplayName("Should update backup on quit")
        void setBackupOnQuit() {
            BackupConfig config = createRealConfig();
            config.setBackupOnQuit(false);
            assertThat(config.isBackupOnQuit()).isFalse();
        }

        @Test
        @DisplayName("Should update max backups per player")
        void setMaxBackups() {
            BackupConfig config = createRealConfig();
            config.setMaxBackupsPerPlayer(20);
            assertThat(config.getMaxBackupsPerPlayer()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should update backup armor")
        void setBackupArmor() {
            BackupConfig config = createRealConfig();
            config.setBackupArmor(false);
            assertThat(config.isBackupArmor()).isFalse();
        }

        @Test
        @DisplayName("Should update backup enderchest")
        void setBackupEnderchest() {
            BackupConfig config = createRealConfig();
            config.setBackupEnderchest(false);
            assertThat(config.isBackupEnderchest()).isFalse();
        }

        @Test
        @DisplayName("Should update backup exp")
        void setBackupExp() {
            BackupConfig config = createRealConfig();
            config.setBackupExp(false);
            assertThat(config.isBackupExp()).isFalse();
        }
    }

    /**
     * Create a real BackupConfig using a mock path to avoid AbstractConfigEntity I/O.
     * We use Mockito spy to bypass the superclass constructor's file loading.
     */
    private BackupConfig createRealConfig() {
        // Use mock to avoid AbstractConfigEntity file I/O, then set fields
        BackupConfig config = mock(BackupConfig.class, withSettings().useConstructor("config/backup.yml").defaultAnswer(CALLS_REAL_METHODS));
        return config;
    }
}
