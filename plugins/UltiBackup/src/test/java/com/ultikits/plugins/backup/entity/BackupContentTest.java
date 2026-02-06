package com.ultikits.plugins.backup.entity;

import com.ultikits.plugins.backup.UltiBackupTestHelper;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupContent Tests")
class BackupContentTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    // ==================== Checksum Calculation ====================

    @Nested
    @DisplayName("Checksum Calculation")
    class ChecksumCalculation {

        @Test
        @DisplayName("Should produce consistent SHA-256 for same input")
        void consistentChecksum() {
            String input = "test content";
            String first = BackupContent.calculateChecksum(input);
            String second = BackupContent.calculateChecksum(input);

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("Should produce different checksums for different input")
        void differentInput() {
            String a = BackupContent.calculateChecksum("input A");
            String b = BackupContent.calculateChecksum("input B");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should produce 64-char hex string (SHA-256)")
        void hexLength() {
            String checksum = BackupContent.calculateChecksum("anything");

            assertThat(checksum).hasSize(64);
            assertThat(checksum).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("Should handle empty string")
        void emptyString() {
            String checksum = BackupContent.calculateChecksum("");
            assertThat(checksum).hasSize(64);
        }
    }

    // ==================== File Save and Load ====================

    @Nested
    @DisplayName("File Save and Load")
    class FileSaveLoad {

        @Test
        @DisplayName("Should save content to file and return checksum")
        void saveReturnsChecksum() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("inv-data")
                    .armorContents("armor-data")
                    .offhandItem("offhand-data")
                    .enderchestContents("ender-data")
                    .expLevel(30)
                    .expProgress(0.5f)
                    .build();

            File file = tempDir.resolve("test_backup.yml").toFile();
            String checksum = content.saveToFile(file);

            assertThat(file).exists();
            assertThat(checksum).isNotNull().hasSize(64);
        }

        @Test
        @DisplayName("Should create parent directories when saving")
        void createParentDirs() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("data")
                    .build();

            File file = tempDir.resolve("nested/deep/backup.yml").toFile();
            content.saveToFile(file);

            assertThat(file).exists();
        }

        @Test
        @DisplayName("Should write header with checksum and warning")
        void headerContainsChecksum() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("test")
                    .build();

            File file = tempDir.resolve("header_test.yml").toFile();
            String checksum = content.saveToFile(file);

            String fileContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertThat(fileContent).contains("DO NOT MODIFY THIS FILE");
            assertThat(fileContent).contains("Checksum: " + checksum);
        }

        @Test
        @DisplayName("Should round-trip all fields through save/load")
        void roundTrip() throws IOException {
            BackupContent original = BackupContent.builder()
                    .inventoryContents("inv-yaml")
                    .armorContents("armor-yaml")
                    .offhandItem("offhand-yaml")
                    .enderchestContents("ender-yaml")
                    .expLevel(42)
                    .expProgress(0.75f)
                    .build();

            File file = tempDir.resolve("roundtrip.yml").toFile();
            original.saveToFile(file);

            BackupContent loaded = BackupContent.loadFromFile(file);

            assertThat(loaded.getInventoryContents()).isEqualTo("inv-yaml");
            assertThat(loaded.getArmorContents()).isEqualTo("armor-yaml");
            assertThat(loaded.getOffhandItem()).isEqualTo("offhand-yaml");
            assertThat(loaded.getEnderchestContents()).isEqualTo("ender-yaml");
            assertThat(loaded.getExpLevel()).isEqualTo(42);
            assertThat(loaded.getExpProgress()).isEqualTo(0.75f);
        }

        @Test
        @DisplayName("Should load defaults for missing fields")
        void loadDefaults() throws IOException {
            File file = tempDir.resolve("minimal.yml").toFile();
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write("someOtherKey: value\n");
            }

            BackupContent loaded = BackupContent.loadFromFile(file);

            assertThat(loaded.getInventoryContents()).isEmpty();
            assertThat(loaded.getArmorContents()).isEmpty();
            assertThat(loaded.getExpLevel()).isZero();
            assertThat(loaded.getExpProgress()).isZero();
        }
    }

    // ==================== Checksum Verification ====================

    @Nested
    @DisplayName("Checksum Verification")
    class ChecksumVerification {

        @Test
        @DisplayName("Should verify valid checksum after save")
        void validChecksum() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("verify-data")
                    .expLevel(10)
                    .build();

            File file = tempDir.resolve("verify_valid.yml").toFile();
            String checksum = content.saveToFile(file);

            assertThat(BackupContent.verifyChecksum(file, checksum)).isTrue();
        }

        @Test
        @DisplayName("Should fail verification when content is tampered")
        void tamperedContent() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("original")
                    .build();

            File file = tempDir.resolve("verify_tampered.yml").toFile();
            String checksum = content.saveToFile(file);

            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                w.write("extra: tampered\n");
            }

            assertThat(BackupContent.verifyChecksum(file, checksum)).isFalse();
        }

        @Test
        @DisplayName("Should fail verification with wrong checksum")
        void wrongChecksum() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("data")
                    .build();

            File file = tempDir.resolve("verify_wrong.yml").toFile();
            content.saveToFile(file);

            assertThat(BackupContent.verifyChecksum(file, "0000000000000000000000000000000000000000000000000000000000000000"))
                    .isFalse();
        }

        @Test
        @DisplayName("Should fail verification for non-existent file")
        void nonExistentFile() throws IOException {
            File file = tempDir.resolve("nonexistent.yml").toFile();
            assertThat(BackupContent.verifyChecksum(file, "abc")).isFalse();
        }

        @Test
        @DisplayName("Should fail verification with null checksum")
        void nullChecksum() throws IOException {
            File file = tempDir.resolve("null_check.yml").toFile();
            Files.write(file.toPath(), "content".getBytes(StandardCharsets.UTF_8));

            assertThat(BackupContent.verifyChecksum(file, null)).isFalse();
        }

        @Test
        @DisplayName("Should skip header comment lines during verification")
        void skipsHeaderComments() throws IOException {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("skip-header-test")
                    .expLevel(5)
                    .build();

            File file = tempDir.resolve("verify_header.yml").toFile();
            String checksum = content.saveToFile(file);

            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertThat(raw).contains("# DO NOT MODIFY");

            assertThat(BackupContent.verifyChecksum(file, checksum)).isTrue();
        }
    }

    // ==================== Deserialization Edge Cases ====================

    @Nested
    @DisplayName("Deserialization Edge Cases")
    class DeserializationEdgeCases {

        @Test
        @DisplayName("Should return null items for null inventory data")
        void nullInventoryData() {
            BackupContent content = BackupContent.builder()
                    .inventoryContents(null)
                    .build();

            assertThat(content.getInventoryItems()).isNull();
        }

        @Test
        @DisplayName("Should return null items for empty inventory data")
        void emptyInventoryData() {
            BackupContent content = BackupContent.builder()
                    .inventoryContents("")
                    .build();

            assertThat(content.getInventoryItems()).isNull();
        }

        @Test
        @DisplayName("Should return null armor for null armor data")
        void nullArmorData() {
            BackupContent content = BackupContent.builder()
                    .armorContents(null)
                    .build();

            assertThat(content.getArmorItems()).isNull();
        }

        @Test
        @DisplayName("Should return null offhand for null offhand data")
        void nullOffhandData() {
            BackupContent content = BackupContent.builder()
                    .offhandItem(null)
                    .build();

            assertThat(content.getOffhandItemStack()).isNull();
        }

        @Test
        @DisplayName("Should return null offhand for empty offhand data")
        void emptyOffhandData() {
            BackupContent content = BackupContent.builder()
                    .offhandItem("")
                    .build();

            assertThat(content.getOffhandItemStack()).isNull();
        }

        @Test
        @DisplayName("Should return null enderchest for null data")
        void nullEnderchestData() {
            BackupContent content = BackupContent.builder()
                    .enderchestContents(null)
                    .build();

            assertThat(content.getEnderchestItems()).isNull();
        }
    }

    // ==================== fromPlayer Factory ====================

    @Nested
    @DisplayName("fromPlayer Factory")
    class FromPlayerFactory {

        @Test
        @DisplayName("Should capture inventory contents")
        void capturesInventory() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, true, true, true);

            // Empty items serialize to empty YAML string
            assertThat(content.getInventoryContents()).isNotNull();
        }

        @Test
        @DisplayName("Should capture armor when enabled")
        void capturesArmor() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, true, false, false);

            assertThat(content.getArmorContents()).isNotNull();
        }

        @Test
        @DisplayName("Should skip armor when disabled")
        void skipsArmor() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, false, false);

            assertThat(content.getArmorContents()).isNull();
            assertThat(content.getOffhandItem()).isNull();
        }

        @Test
        @DisplayName("Should capture enderchest when enabled")
        void capturesEnderchest() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, true, false);

            assertThat(content.getEnderchestContents()).isNotNull();
        }

        @Test
        @DisplayName("Should skip enderchest when disabled")
        void skipsEnderchest() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, false, false);

            assertThat(content.getEnderchestContents()).isNull();
        }

        @Test
        @DisplayName("Should capture exp when enabled")
        void capturesExp() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, false, true);

            assertThat(content.getExpLevel()).isEqualTo(30);
            assertThat(content.getExpProgress()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("Should skip exp when disabled")
        void skipsExp() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, false, false);

            assertThat(content.getExpLevel()).isZero();
            assertThat(content.getExpProgress()).isZero();
        }
    }

    // ==================== restoreToPlayer ====================

    @Nested
    @DisplayName("restoreToPlayer")
    class RestoreToPlayer {

        @Test
        @DisplayName("Should clear inventory before restore")
        void clearsInventory() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder().build();

            content.restoreToPlayer(player, false, false, false);

            verify(player.getInventory()).clear();
        }

        @Test
        @DisplayName("Should restore exp level and progress when enabled")
        void restoresExp() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .expLevel(42)
                    .expProgress(0.8f)
                    .build();

            content.restoreToPlayer(player, false, false, true);

            verify(player).setLevel(42);
            verify(player).setExp(0.8f);
        }

        @Test
        @DisplayName("Should not restore exp when disabled")
        void skipsExp() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .expLevel(42)
                    .expProgress(0.8f)
                    .build();

            content.restoreToPlayer(player, false, false, false);

            verify(player, never()).setLevel(anyInt());
            verify(player, never()).setExp(anyFloat());
        }

        @Test
        @DisplayName("Should skip armor restore when disabled")
        void skipsArmor() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .armorContents("some-data")
                    .build();

            content.restoreToPlayer(player, false, false, false);

            verify(player.getInventory(), never()).setArmorContents(any());
        }

        @Test
        @DisplayName("Should skip enderchest restore when disabled")
        void skipsEnderchest() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .enderchestContents("some-data")
                    .build();

            content.restoreToPlayer(player, false, false, false);

            verify(player.getEnderChest(), never()).setContents(any());
        }

        @Test
        @DisplayName("Should handle null inventory contents gracefully")
        void nullInventoryContents() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .inventoryContents(null)
                    .build();

            content.restoreToPlayer(player, true, true, true);

            // Should not throw
            verify(player.getInventory()).clear();
        }

        @Test
        @DisplayName("Should handle null armor contents gracefully")
        void nullArmorContents() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .armorContents(null)
                    .build();

            content.restoreToPlayer(player, true, false, false);

            verify(player.getInventory(), never()).setArmorContents(any());
        }

        @Test
        @DisplayName("Should handle null enderchest contents gracefully")
        void nullEnderchestContents() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .enderchestContents(null)
                    .build();

            content.restoreToPlayer(player, false, true, false);

            verify(player.getEnderChest(), never()).setContents(any());
        }
    }

    // ==================== restoreToPlayer armor/enderchest branches ====================

    @Nested
    @DisplayName("restoreToPlayer armor/enderchest with non-null data but flag off")
    class RestoreArmorEnderchestBranches {

        @Test
        @DisplayName("Should skip armor when flag is true but armorContents is null")
        void armorFlagTrueContentNull() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .armorContents(null)
                    .offhandItem(null)
                    .build();

            content.restoreToPlayer(player, true, false, false);
            // armorContents null => skip setArmorContents
            verify(player.getInventory(), never()).setArmorContents(any());
        }

        @Test
        @DisplayName("Should skip enderchest when flag is true but enderchestContents is null")
        void enderchestFlagTrueContentNull() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .enderchestContents(null)
                    .build();

            content.restoreToPlayer(player, false, true, false);
            verify(player.getEnderChest(), never()).setContents(any());
        }

        @Test
        @DisplayName("Should restore with all flags enabled but empty content")
        void allFlagsEmptyContent() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupContent content = BackupContent.builder()
                    .inventoryContents("")
                    .armorContents("")
                    .offhandItem("")
                    .enderchestContents("")
                    .expLevel(0)
                    .expProgress(0f)
                    .build();

            content.restoreToPlayer(player, true, true, true);

            // Empty strings should not cause NPEs
            verify(player).setLevel(0);
            verify(player).setExp(0f);
        }
    }

    // ==================== fromPlayer all flags off ====================

    @Nested
    @DisplayName("fromPlayer all disabled")
    class FromPlayerAllDisabled {

        @Test
        @DisplayName("Should only capture inventory when all optional flags disabled")
        void onlyInventory() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, false, false, false);

            assertThat(content.getInventoryContents()).isNotNull();
            assertThat(content.getArmorContents()).isNull();
            assertThat(content.getOffhandItem()).isNull();
            assertThat(content.getEnderchestContents()).isNull();
            assertThat(content.getExpLevel()).isZero();
        }

        @Test
        @DisplayName("Should capture all when all flags enabled")
        void allEnabled() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupContent content = BackupContent.fromPlayer(player, true, true, true);

            assertThat(content.getInventoryContents()).isNotNull();
            assertThat(content.getArmorContents()).isNotNull();
            assertThat(content.getEnderchestContents()).isNotNull();
            assertThat(content.getExpLevel()).isEqualTo(30);
            assertThat(content.getExpProgress()).isEqualTo(0.5f);
        }
    }

    // ==================== FILE_HEADER constant ====================

    @Nested
    @DisplayName("FILE_HEADER")
    class FileHeaderConstant {

        @Test
        @DisplayName("Should contain warning text")
        void containsWarning() {
            assertThat(BackupContent.FILE_HEADER).contains("DO NOT MODIFY");
            assertThat(BackupContent.FILE_HEADER).contains("请勿修改此文件");
        }

        @Test
        @DisplayName("Should contain checksum placeholder")
        void containsPlaceholder() {
            assertThat(BackupContent.FILE_HEADER).contains("Checksum: %s");
        }
    }

    // ==================== All-args constructor ====================

    @Nested
    @DisplayName("All-args Constructor")
    class AllArgsConstructor {

        @Test
        @DisplayName("Should set all fields")
        void allFields() {
            BackupContent content = new BackupContent("inv", "armor", "offhand", "ender", 50, 0.9f);

            assertThat(content.getInventoryContents()).isEqualTo("inv");
            assertThat(content.getArmorContents()).isEqualTo("armor");
            assertThat(content.getOffhandItem()).isEqualTo("offhand");
            assertThat(content.getEnderchestContents()).isEqualTo("ender");
            assertThat(content.getExpLevel()).isEqualTo(50);
            assertThat(content.getExpProgress()).isEqualTo(0.9f);
        }
    }

    // ==================== Setters (@Data) ====================

    @Nested
    @DisplayName("Setters")
    class SetterTests {

        @Test
        @DisplayName("Should set and get fields via setters")
        void settersWork() {
            BackupContent content = new BackupContent();
            content.setInventoryContents("new-inv");
            content.setArmorContents("new-armor");
            content.setOffhandItem("new-offhand");
            content.setEnderchestContents("new-ender");
            content.setExpLevel(77);
            content.setExpProgress(0.33f);

            assertThat(content.getInventoryContents()).isEqualTo("new-inv");
            assertThat(content.getArmorContents()).isEqualTo("new-armor");
            assertThat(content.getOffhandItem()).isEqualTo("new-offhand");
            assertThat(content.getEnderchestContents()).isEqualTo("new-ender");
            assertThat(content.getExpLevel()).isEqualTo(77);
            assertThat(content.getExpProgress()).isEqualTo(0.33f);
        }
    }

    // ==================== Builder ====================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create with all defaults via no-arg constructor")
        void noArgDefaults() {
            BackupContent content = new BackupContent();

            assertThat(content.getExpLevel()).isZero();
            assertThat(content.getExpProgress()).isZero();
            assertThat(content.getInventoryContents()).isNull();
        }

        @Test
        @DisplayName("Should create with builder values")
        void builderValues() {
            BackupContent content = BackupContent.builder()
                    .expLevel(99)
                    .expProgress(0.99f)
                    .inventoryContents("inv")
                    .build();

            assertThat(content.getExpLevel()).isEqualTo(99);
            assertThat(content.getExpProgress()).isEqualTo(0.99f);
            assertThat(content.getInventoryContents()).isEqualTo("inv");
        }
    }
}
