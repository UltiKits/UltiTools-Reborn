package com.ultikits.plugins.kits.service;

import com.ultikits.plugins.kits.entity.KitClaimData;
import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.utils.EconomyUtils;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("KitServiceImpl")
class KitServiceImplTest {

    @TempDir
    File tempDir;

    private UltiToolsPlugin plugin;
    private PluginLogger mockLogger;
    @SuppressWarnings("unchecked")
    private DataOperator<KitClaimData> mockClaimOperator;
    @SuppressWarnings("unchecked")
    private Query<KitClaimData> mockQuery;
    private KitServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        plugin = mock(UltiToolsPlugin.class);
        mockLogger = mock(PluginLogger.class);
        mockClaimOperator = mock(DataOperator.class);
        mockQuery = mock(Query.class);

        when(plugin.getLogger()).thenReturn(mockLogger);
        when(plugin.getResourceFolderPath()).thenReturn(tempDir.getAbsolutePath());
        when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(plugin.getDataOperator(KitClaimData.class)).thenReturn(mockClaimOperator);
        when(mockClaimOperator.query()).thenReturn(mockQuery);
        when(mockQuery.where(anyString())).thenReturn(mockQuery);
        when(mockQuery.eq(any())).thenReturn(mockQuery);
        when(mockQuery.and(anyString())).thenReturn(mockQuery);
        when(mockQuery.list()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        EconomyUtils.reset();
    }

    /**
     * Create a KitServiceImpl after kit files have been set up in tempDir.
     * The constructor calls loadKits(), so files must exist before construction.
     */
    private KitServiceImpl createService() {
        return new KitServiceImpl(plugin);
    }

    /**
     * Create a minimal kit YAML file in the kits folder.
     */
    private File createKitFile(String name, String displayName, String icon, double price,
                               int levelRequired, String permission, boolean reBuyable, long cooldown) throws IOException {
        File kitsFolder = new File(tempDir, "kits");
        kitsFolder.mkdirs();
        File kitFile = new File(kitsFolder, name + ".yml");

        StringBuilder yaml = new StringBuilder();
        yaml.append("displayName: \"").append(displayName).append("\"\n");
        yaml.append("icon: ").append(icon).append("\n");
        yaml.append("price: ").append(price).append("\n");
        yaml.append("levelRequired: ").append(levelRequired).append("\n");
        yaml.append("permission: \"").append(permission).append("\"\n");
        yaml.append("reBuyable: ").append(reBuyable).append("\n");
        yaml.append("cooldown: ").append(cooldown).append("\n");
        yaml.append("items: \"someBase64Data\"\n");
        yaml.append("description:\n  - \"A test kit\"\n");
        yaml.append("playerCommands: []\n");
        yaml.append("consoleCommands: []\n");

        FileWriter writer = new FileWriter(kitFile);
        writer.write(yaml.toString());
        writer.close();
        return kitFile;
    }

    private File createSimpleKitFile(String name) throws IOException {
        return createKitFile(name, "&a" + name, "CHEST", 0, 0, "", false, 0);
    }

    private File createKitFileWithItems(String name, String items) throws IOException {
        File kitsFolder = new File(tempDir, "kits");
        kitsFolder.mkdirs();
        File kitFile = new File(kitsFolder, name + ".yml");

        StringBuilder yaml = new StringBuilder();
        yaml.append("displayName: \"&a").append(name).append("\"\n");
        yaml.append("icon: CHEST\n");
        yaml.append("price: 0\n");
        yaml.append("levelRequired: 0\n");
        yaml.append("permission: \"\"\n");
        yaml.append("reBuyable: false\n");
        yaml.append("cooldown: 0\n");
        yaml.append("items: \"").append(items).append("\"\n");
        yaml.append("description:\n  - \"A test kit\"\n");
        yaml.append("playerCommands: []\n");
        yaml.append("consoleCommands: []\n");

        FileWriter writer = new FileWriter(kitFile);
        writer.write(yaml.toString());
        writer.close();
        return kitFile;
    }

    /**
     * Inject a KitDefinition into the service's internal kits map via reflection.
     */
    private void injectKit(KitServiceImpl svc, KitDefinition kit) throws Exception {
        Field kitsField = KitServiceImpl.class.getDeclaredField("kits");
        kitsField.setAccessible(true); // NOPMD
        @SuppressWarnings("unchecked")
        Map<String, KitDefinition> kitsMap = (Map<String, KitDefinition>) kitsField.get(svc);
        kitsMap.put(kit.getName().toLowerCase(), kit);
    }

    /**
     * Set up EconomyUtils with a mock Economy via reflection.
     */
    private Economy setupMockEconomy() throws Exception {
        Economy mockEconomy = mock(Economy.class);
        Field economyField = EconomyUtils.class.getDeclaredField("economy");
        economyField.setAccessible(true); // NOPMD
        economyField.set(null, mockEconomy);

        Field setupAttemptedField = EconomyUtils.class.getDeclaredField("setupAttempted");
        setupAttemptedField.setAccessible(true); // NOPMD
        setupAttemptedField.set(null, true);

        return mockEconomy;
    }

    private Player createMockPlayer() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(player.getLevel()).thenReturn(10);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private Player createMockPlayerWithUuid(UUID uuid, String name, int level) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getLevel()).thenReturn(level);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private KitDefinition createTestKit(String name) {
        KitDefinition kit = new KitDefinition();
        kit.setName(name);
        kit.setDisplayName("&a" + name);
        kit.setIcon("CHEST");
        kit.setItems("someBase64Data");
        return kit;
    }

    private ItemStack mockItemStack(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }

    // =========================================================================
    // Constructor Tests
    // =========================================================================
    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("constructor initializes all fields from plugin")
        void constructorInitializesFields() {
            new File(tempDir, "kits").mkdirs();
            service = createService();

            verify(plugin).getLogger();
            verify(plugin).getDataOperator(KitClaimData.class);
            verify(plugin, atLeastOnce()).getResourceFolderPath();
        }

        @Test
        @DisplayName("constructor calls loadKits during initialization")
        void constructorCallsLoadKits() throws IOException {
            createSimpleKitFile("autoloaded");

            service = createService();

            assertThat(service.getKit("autoloaded")).isNotNull();
        }
    }

    // =========================================================================
    // Loading Tests
    // =========================================================================
    @Nested
    @DisplayName("Loading Tests")
    class LoadingTests {

        @Test
        @DisplayName("loads kits from YAML files in kits folder")
        void loadsFromYamlFiles() throws IOException {
            createKitFile("starter", "&aStarter Kit", "CHEST", 0, 0, "", false, 0);
            createKitFile("vip", "&bVIP Kit", "DIAMOND", 100, 5, "kit.vip", true, 3600);

            service = createService();

            assertThat(service.getAllKits()).hasSize(2);
            assertThat(service.getKit("starter")).isNotNull();
            assertThat(service.getKit("vip")).isNotNull();
        }

        @Test
        @DisplayName("creates kits folder if not exists")
        void createsFolderIfNotExists() {
            service = createService();

            File kitsFolder = new File(tempDir, "kits");
            assertThat(kitsFolder).exists().isDirectory();
        }

        @Test
        @DisplayName("logs warning when no kit files found")
        void logsWarningWhenEmpty() {
            new File(tempDir, "kits").mkdirs();

            service = createService();

            verify(mockLogger).warn(contains("没有找到礼包配置文件"));
            assertThat(service.getAllKits()).isEmpty();
        }

        @Test
        @DisplayName("loads multiple kits and reports count")
        void loadsMultipleKits() throws IOException {
            createSimpleKitFile("kit1");
            createSimpleKitFile("kit2");
            createSimpleKitFile("kit3");

            service = createService();

            assertThat(service.getAllKits()).hasSize(3);
            verify(mockLogger).info(contains("3"));
        }

        @Test
        @DisplayName("normalizes kit names to lowercase")
        void normalizesNamesToLowercase() throws IOException {
            createKitFile("MyKit", "&aMy Kit", "CHEST", 0, 0, "", false, 0);

            service = createService();

            assertThat(service.getKit("mykit")).isNotNull();
        }

        @Test
        @DisplayName("handles invalid icon material gracefully with CHEST fallback")
        void handlesInvalidIcon() throws IOException {
            createKitFile("badicon", "&aBad", "NOT_A_MATERIAL", 0, 0, "", false, 0);

            service = createService();

            KitDefinition kit = service.getKit("badicon");
            assertThat(kit).isNotNull();
            assertThat(kit.getIcon()).isEqualTo("CHEST");
            verify(mockLogger).warn(contains("invalid icon"));
        }

        @Test
        @DisplayName("parses all kit fields from YAML")
        void parsesAllFields() throws IOException {
            createKitFile("full", "&6Full Kit", "DIAMOND_SWORD", 50.5, 10,
                    "kit.full", true, 7200);

            service = createService();

            KitDefinition kit = service.getKit("full");
            assertThat(kit).isNotNull();
            assertThat(kit.getDisplayName()).isEqualTo("&6Full Kit");
            assertThat(kit.getIcon()).isEqualTo("DIAMOND_SWORD");
            assertThat(kit.getPrice()).isEqualTo(50.5);
            assertThat(kit.getLevelRequired()).isEqualTo(10);
            assertThat(kit.getPermission()).isEqualTo("kit.full");
            assertThat(kit.isReBuyable()).isTrue();
            assertThat(kit.getCooldown()).isEqualTo(7200);
        }

        @Test
        @DisplayName("ignores non-yml files in kits folder")
        void ignoresNonYmlFiles() throws IOException {
            createSimpleKitFile("valid");
            File kitsFolder = new File(tempDir, "kits");
            new File(kitsFolder, "readme.txt").createNewFile();
            new File(kitsFolder, "backup.bak").createNewFile();

            service = createService();

            assertThat(service.getAllKits()).hasSize(1);
        }

        @Test
        @DisplayName("reload clears and reloads kits")
        void reloadClearsAndReloads() throws IOException {
            createSimpleKitFile("original");
            service = createService();
            assertThat(service.getAllKits()).hasSize(1);

            createSimpleKitFile("added");
            service.reload();

            assertThat(service.getAllKits()).hasSize(2);
            assertThat(service.getKit("added")).isNotNull();
        }

        @Test
        @DisplayName("reload removes kits whose files were deleted")
        void reloadRemovesDeletedKits() throws IOException {
            File kitFile = createSimpleKitFile("temporary");
            service = createService();
            assertThat(service.getKit("temporary")).isNotNull();

            kitFile.delete();
            service.reload();

            assertThat(service.getKit("temporary")).isNull();
            assertThat(service.getAllKits()).isEmpty();
        }

        @Test
        @DisplayName("kit name is set from file name, not YAML content")
        void kitNameFromFileName() throws IOException {
            createSimpleKitFile("warrior");

            service = createService();

            KitDefinition kit = service.getKit("warrior");
            assertThat(kit).isNotNull();
            assertThat(kit.getName()).isEqualTo("warrior");
        }

        @Test
        @DisplayName("loads kit with empty items field")
        void loadsKitWithEmptyItems() throws IOException {
            createKitFileWithItems("emptyitems", "");

            service = createService();

            KitDefinition kit = service.getKit("emptyitems");
            assertThat(kit).isNotNull();
            assertThat(kit.hasItems()).isFalse();
        }

        @Test
        @DisplayName("loadKits clears existing kits before loading")
        void loadKitsClearsExisting() throws Exception {
            new File(tempDir, "kits").mkdirs();
            service = createService();
            injectKit(service, createTestKit("injected"));
            assertThat(service.getAllKits()).hasSize(1);

            service.loadKits();

            assertThat(service.getAllKits()).isEmpty();
        }

        @Test
        @DisplayName("parses kit description as string list")
        void parsesDescriptionList() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "desc.yml");

            StringBuilder yaml = new StringBuilder();
            yaml.append("displayName: \"&aDesc Kit\"\n");
            yaml.append("icon: CHEST\n");
            yaml.append("description:\n");
            yaml.append("  - \"&7Line one\"\n");
            yaml.append("  - \"&eLine two\"\n");
            yaml.append("  - \"&6Line three\"\n");
            yaml.append("items: \"\"\n");

            FileWriter writer = new FileWriter(kitFile);
            writer.write(yaml.toString());
            writer.close();

            service = createService();

            KitDefinition kit = service.getKit("desc");
            assertThat(kit).isNotNull();
            assertThat(kit.getDescription()).hasSize(3);
            assertThat(kit.getDescription()).containsExactly("&7Line one", "&eLine two", "&6Line three");
        }

        @Test
        @DisplayName("parses kit with player and console commands")
        void parsesCommandLists() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "cmdkit.yml");

            StringBuilder yaml = new StringBuilder();
            yaml.append("displayName: \"&aCmd Kit\"\n");
            yaml.append("icon: CHEST\n");
            yaml.append("items: \"data\"\n");
            yaml.append("playerCommands:\n");
            yaml.append("  - \"spawn\"\n");
            yaml.append("  - \"msg {player} hello\"\n");
            yaml.append("consoleCommands:\n");
            yaml.append("  - \"give {player} diamond 1\"\n");

            FileWriter writer = new FileWriter(kitFile);
            writer.write(yaml.toString());
            writer.close();

            service = createService();

            KitDefinition kit = service.getKit("cmdkit");
            assertThat(kit).isNotNull();
            assertThat(kit.getPlayerCommands()).containsExactly("spawn", "msg {player} hello");
            assertThat(kit.getConsoleCommands()).containsExactly("give {player} diamond 1");
        }

        @Test
        @DisplayName("handles kits folder being a file instead of directory")
        void kitsFolderIsFile() throws IOException {
            File kitsFile = new File(tempDir, "kits");
            kitsFile.createNewFile(); // create as FILE, not directory

            service = createService();

            verify(mockLogger).warn(contains("没有找到礼包配置文件"));
            assertThat(service.getAllKits()).isEmpty();
        }
    }

    // =========================================================================
    // Retrieval Tests
    // =========================================================================
    @Nested
    @DisplayName("Retrieval Tests")
    class RetrievalTests {

        @BeforeEach
        void setUp() throws Exception {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("getKit returns kit by name (case insensitive)")
        void getKitCaseInsensitive() throws Exception {
            KitDefinition kit = createTestKit("starter");
            injectKit(service, kit);

            assertThat(service.getKit("starter")).isSameAs(kit);
            assertThat(service.getKit("STARTER")).isSameAs(kit);
            assertThat(service.getKit("Starter")).isSameAs(kit);
        }

        @Test
        @DisplayName("getKit returns null for nonexistent kit")
        void getKitReturnsNull() {
            assertThat(service.getKit("nonexistent")).isNull();
        }

        @Test
        @DisplayName("getKit with empty string returns null")
        void getKitEmptyString() {
            assertThat(service.getKit("")).isNull();
        }

        @Test
        @DisplayName("getAllKits returns unmodifiable collection")
        void getAllKitsUnmodifiable() throws Exception {
            KitDefinition kit = createTestKit("test");
            injectKit(service, kit);

            Collection<KitDefinition> allKits = service.getAllKits();
            assertThat(allKits).hasSize(1);

            Assertions.assertThrows(UnsupportedOperationException.class, () ->
                    allKits.add(new KitDefinition()));
        }

        @Test
        @DisplayName("getAllKits returns empty when no kits loaded")
        void getAllKitsEmpty() {
            assertThat(service.getAllKits()).isEmpty();
        }

        @Test
        @DisplayName("getAvailableKits filters by permission")
        void getAvailableKitsFiltersByPermission() throws Exception {
            KitDefinition freeKit = createTestKit("free");
            freeKit.setPermission("");
            injectKit(service, freeKit);

            KitDefinition vipKit = createTestKit("vip");
            vipKit.setPermission("kit.vip");
            injectKit(service, vipKit);

            KitDefinition adminKit = createTestKit("admin");
            adminKit.setPermission("kit.admin");
            injectKit(service, adminKit);

            Player player = createMockPlayer();
            when(player.hasPermission("kit.vip")).thenReturn(true);
            when(player.hasPermission("kit.admin")).thenReturn(false);

            List<KitDefinition> available = service.getAvailableKits(player);

            assertThat(available).hasSize(2);
            assertThat(available).contains(freeKit, vipKit);
            assertThat(available).doesNotContain(adminKit);
        }

        @Test
        @DisplayName("getAvailableKits returns all kits when player has all permissions")
        void getAvailableKitsAllPermissions() throws Exception {
            KitDefinition kit1 = createTestKit("kit1");
            kit1.setPermission("kit.one");
            injectKit(service, kit1);

            KitDefinition kit2 = createTestKit("kit2");
            kit2.setPermission("kit.two");
            injectKit(service, kit2);

            Player player = createMockPlayer();
            when(player.hasPermission(anyString())).thenReturn(true);

            List<KitDefinition> available = service.getAvailableKits(player);
            assertThat(available).hasSize(2);
        }

        @Test
        @DisplayName("getAvailableKits returns empty when no kits loaded")
        void getAvailableKitsEmptyWhenNoKits() {
            Player player = createMockPlayer();

            List<KitDefinition> available = service.getAvailableKits(player);
            assertThat(available).isEmpty();
        }

        @Test
        @DisplayName("getAvailableKits includes kits with null permission")
        void getAvailableKitsIncludesNullPermKits() throws Exception {
            KitDefinition kit = createTestKit("noperm");
            kit.setPermission(null);
            injectKit(service, kit);

            Player player = createMockPlayer();

            List<KitDefinition> available = service.getAvailableKits(player);
            assertThat(available).hasSize(1);
            assertThat(available).contains(kit);
        }

        @Test
        @DisplayName("getKitNames returns list of all kit names")
        void getKitNamesReturnsList() throws Exception {
            injectKit(service, createTestKit("alpha"));
            injectKit(service, createTestKit("beta"));

            List<String> names = service.getKitNames();

            assertThat(names).containsExactlyInAnyOrder("alpha", "beta");
        }

        @Test
        @DisplayName("getKitNames returns empty list when no kits")
        void getKitNamesEmpty() {
            assertThat(service.getKitNames()).isEmpty();
        }

        @Test
        @DisplayName("getKitNames returns a mutable list independent of internal state")
        void getKitNamesReturnsMutableList() throws Exception {
            injectKit(service, createTestKit("test"));

            List<String> names = service.getKitNames();
            names.add("extra");
            assertThat(names).hasSize(2);

            // Original kits should not be affected
            assertThat(service.getKitNames()).hasSize(1);
        }

        @Test
        @DisplayName("kits map preserves insertion order")
        void kitsMapPreservesOrder() throws Exception {
            injectKit(service, createTestKit("alpha"));
            injectKit(service, createTestKit("beta"));
            injectKit(service, createTestKit("gamma"));

            List<String> names = service.getKitNames();
            assertThat(names).containsExactly("alpha", "beta", "gamma");
        }
    }

    // =========================================================================
    // Create Tests
    // =========================================================================
    @Nested
    @DisplayName("Create Tests")
    class CreateTests {

        private Player player;
        private PlayerInventory inventory;

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();

            player = createMockPlayer();
            inventory = player.getInventory();
        }

        @Test
        @DisplayName("createKit returns ALREADY_EXISTS for duplicate name")
        void createKitDuplicate() throws Exception {
            injectKit(service, createTestKit("existing"));

            ItemStack[] contents = new ItemStack[]{ mockItemStack(Material.STONE) };
            when(inventory.getStorageContents()).thenReturn(contents);

            KitService.CreateResult result = service.createKit(player, "EXISTING");
            assertThat(result).isEqualTo(KitService.CreateResult.ALREADY_EXISTS);
        }

        @Test
        @DisplayName("createKit returns INVALID_NAME for empty name")
        void createKitEmptyName() {
            KitService.CreateResult result = service.createKit(player, "   ");
            assertThat(result).isEqualTo(KitService.CreateResult.INVALID_NAME);
        }

        @Test
        @DisplayName("createKit returns INVALID_NAME for name longer than 32 characters")
        void createKitLongName() {
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 33; i++) {
                longName.append("a");
            }
            KitService.CreateResult result = service.createKit(player, longName.toString());
            assertThat(result).isEqualTo(KitService.CreateResult.INVALID_NAME);
        }

        @Test
        @DisplayName("createKit accepts name exactly 32 characters long")
        void createKitExactly32Chars() {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                name.append("x");
            }

            ItemStack[] contents = new ItemStack[]{ mockItemStack(Material.STONE) };
            when(inventory.getStorageContents()).thenReturn(contents);

            // Should NOT return INVALID_NAME -- proceeds past validation
            KitService.CreateResult result = service.createKit(player, name.toString());
            assertThat(result).isNotEqualTo(KitService.CreateResult.INVALID_NAME);
        }

        @Test
        @DisplayName("createKit returns EMPTY_INVENTORY when all slots are air or null")
        void createKitEmptyInventory() {
            ItemStack airItem = mockItemStack(Material.AIR);
            ItemStack[] contents = new ItemStack[]{ null, airItem, null };
            when(inventory.getStorageContents()).thenReturn(contents);

            KitService.CreateResult result = service.createKit(player, "empty");
            assertThat(result).isEqualTo(KitService.CreateResult.EMPTY_INVENTORY);
        }

        @Test
        @DisplayName("createKit returns EMPTY_INVENTORY for completely empty array")
        void createKitEmptyArray() {
            when(inventory.getStorageContents()).thenReturn(new ItemStack[0]);

            KitService.CreateResult result = service.createKit(player, "emptyarray");
            assertThat(result).isEqualTo(KitService.CreateResult.EMPTY_INVENTORY);
        }

        @Test
        @DisplayName("createKit normalizes name to lowercase for duplicate check")
        void createKitNormalizesName() throws Exception {
            injectKit(service, createTestKit("mykit"));

            ItemStack[] contents = new ItemStack[]{ mockItemStack(Material.STONE) };
            when(inventory.getStorageContents()).thenReturn(contents);

            KitService.CreateResult result = service.createKit(player, "MyKit");
            assertThat(result).isEqualTo(KitService.CreateResult.ALREADY_EXISTS);
        }

        @Test
        @DisplayName("createKit trims whitespace from name before validation")
        void createKitTrimsWhitespace() throws Exception {
            injectKit(service, createTestKit("padded"));

            ItemStack[] contents = new ItemStack[]{ mockItemStack(Material.STONE) };
            when(inventory.getStorageContents()).thenReturn(contents);

            KitService.CreateResult result = service.createKit(player, "  Padded  ");
            assertThat(result).isEqualTo(KitService.CreateResult.ALREADY_EXISTS);
        }
    }

    // =========================================================================
    // Delete Tests
    // =========================================================================
    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("deleteKit returns true and removes kit and file")
        void deleteKitSuccess() throws Exception {
            injectKit(service, createTestKit("todelete"));

            File kitFile = new File(tempDir, "kits/todelete.yml");
            kitFile.createNewFile();
            assertThat(kitFile).exists();

            boolean result = service.deleteKit("todelete");

            assertThat(result).isTrue();
            assertThat(service.getKit("todelete")).isNull();
            assertThat(kitFile).doesNotExist();
        }

        @Test
        @DisplayName("deleteKit returns false for nonexistent kit")
        void deleteKitNotFound() {
            boolean result = service.deleteKit("nosuchkit");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("deleteKit handles case-insensitive names")
        void deleteKitCaseInsensitive() throws Exception {
            injectKit(service, createTestKit("mykit"));

            boolean result = service.deleteKit("MYKIT");
            assertThat(result).isTrue();
            assertThat(service.getKit("mykit")).isNull();
        }

        @Test
        @DisplayName("deleteKit works even when file does not exist on disk")
        void deleteKitNoFile() throws Exception {
            injectKit(service, createTestKit("nofile"));

            boolean result = service.deleteKit("nofile");
            assertThat(result).isTrue();
            assertThat(service.getKit("nofile")).isNull();
        }

        @Test
        @DisplayName("deleteKit removes kit from getAllKits result")
        void deleteKitRemovesFromGetAll() throws Exception {
            injectKit(service, createTestKit("a"));
            injectKit(service, createTestKit("b"));
            assertThat(service.getAllKits()).hasSize(2);

            service.deleteKit("a");

            assertThat(service.getAllKits()).hasSize(1);
            assertThat(service.getKitNames()).containsExactly("b");
        }

        @Test
        @DisplayName("deleteKit trims and lowercases the name")
        void deleteKitTrimsName() throws Exception {
            injectKit(service, createTestKit("trimme"));

            boolean result = service.deleteKit("  TRIMME  ");
            assertThat(result).isTrue();
            assertThat(service.getKit("trimme")).isNull();
        }

        @Test
        @DisplayName("deleteKit with empty string returns false")
        void deleteKitEmptyString() {
            assertThat(service.deleteKit("")).isFalse();
        }
    }

    // =========================================================================
    // Claim Tests
    // =========================================================================
    @Nested
    @DisplayName("Claim Tests")
    class ClaimTests {

        private Player player;
        private PlayerInventory inventory;

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();

            player = createMockPlayer();
            inventory = player.getInventory();
            // Default: plenty of empty slots
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        }

        @Test
        @DisplayName("claimKit returns NOT_FOUND for nonexistent kit")
        void claimNotFound() {
            KitService.ClaimResult result = service.claimKit(player, "nonexistent");
            assertThat(result).isEqualTo(KitService.ClaimResult.NOT_FOUND);
        }

        @Test
        @DisplayName("claimKit returns NOT_FOUND for empty string")
        void claimNotFoundEmptyString() {
            KitService.ClaimResult result = service.claimKit(player, "");
            assertThat(result).isEqualTo(KitService.ClaimResult.NOT_FOUND);
        }

        @Test
        @DisplayName("claimKit returns NO_PERMISSION when player lacks permission")
        void claimNoPermission() throws Exception {
            KitDefinition kit = createTestKit("vip");
            kit.setPermission("kit.vip");
            injectKit(service, kit);

            when(player.hasPermission("kit.vip")).thenReturn(false);

            KitService.ClaimResult result = service.claimKit(player, "vip");
            assertThat(result).isEqualTo(KitService.ClaimResult.NO_PERMISSION);
        }

        @Test
        @DisplayName("claimKit passes permission check when player has permission")
        void claimWithPermission() throws Exception {
            KitDefinition kit = createTestKit("vipok");
            kit.setPermission("kit.vip");
            kit.setItems(""); // will hit EMPTY_KIT
            injectKit(service, kit);

            when(player.hasPermission("kit.vip")).thenReturn(true);

            KitService.ClaimResult result = service.claimKit(player, "vipok");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit returns INSUFFICIENT_LEVEL when player level too low")
        void claimInsufficientLevel() throws Exception {
            KitDefinition kit = createTestKit("highlevel");
            kit.setLevelRequired(20);
            injectKit(service, kit);

            when(player.getLevel()).thenReturn(5);

            KitService.ClaimResult result = service.claimKit(player, "highlevel");
            assertThat(result).isEqualTo(KitService.ClaimResult.INSUFFICIENT_LEVEL);
        }

        @Test
        @DisplayName("claimKit passes level check when player has exact level required")
        void claimExactLevel() throws Exception {
            KitDefinition kit = createTestKit("exactlevel");
            kit.setLevelRequired(10);
            kit.setItems("");
            injectKit(service, kit);

            when(player.getLevel()).thenReturn(10);

            KitService.ClaimResult result = service.claimKit(player, "exactlevel");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit skips level check when no level required")
        void claimNoLevelRequired() throws Exception {
            KitDefinition kit = createTestKit("nolevel");
            kit.setLevelRequired(0);
            kit.setItems("");
            injectKit(service, kit);

            when(player.getLevel()).thenReturn(0);

            KitService.ClaimResult result = service.claimKit(player, "nolevel");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit returns INSUFFICIENT_FUNDS when player can't afford")
        void claimInsufficientFunds() throws Exception {
            KitDefinition kit = createTestKit("expensive");
            kit.setPrice(500);
            injectKit(service, kit);

            Economy mockEconomy = setupMockEconomy();
            when(mockEconomy.has(eq(player), eq(500.0))).thenReturn(false);

            KitService.ClaimResult result = service.claimKit(player, "expensive");
            assertThat(result).isEqualTo(KitService.ClaimResult.INSUFFICIENT_FUNDS);
        }

        @Test
        @DisplayName("claimKit skips economy check for free kit")
        void claimFreeKitSkipsEconomy() throws Exception {
            KitDefinition kit = createTestKit("freebie");
            kit.setPrice(0);
            kit.setItems("");
            injectKit(service, kit);

            KitService.ClaimResult result = service.claimKit(player, "freebie");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit treats negative price as free")
        void claimNegativePriceIsFree() throws Exception {
            KitDefinition kit = createTestKit("negprice");
            kit.setPrice(-10);
            kit.setItems("");
            injectKit(service, kit);

            KitService.ClaimResult result = service.claimKit(player, "negprice");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit returns ALREADY_CLAIMED for one-time kit already claimed")
        void claimAlreadyClaimed() throws Exception {
            KitDefinition kit = createTestKit("onetime");
            kit.setReBuyable(false);
            injectKit(service, kit);

            KitClaimData existingClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("onetime")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(existingClaim));

            KitService.ClaimResult result = service.claimKit(player, "onetime");
            assertThat(result).isEqualTo(KitService.ClaimResult.ALREADY_CLAIMED);
        }

        @Test
        @DisplayName("claimKit returns ON_COOLDOWN when cooldown active")
        void claimOnCooldown() throws Exception {
            KitDefinition kit = createTestKit("cooldown");
            kit.setReBuyable(true);
            kit.setCooldown(3600);
            injectKit(service, kit);

            KitClaimData existingClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("cooldown")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(existingClaim));

            KitService.ClaimResult result = service.claimKit(player, "cooldown");
            assertThat(result).isEqualTo(KitService.ClaimResult.ON_COOLDOWN);
        }

        @Test
        @DisplayName("claimKit returns EMPTY_KIT when kit has no items")
        void claimEmptyKit() throws Exception {
            KitDefinition kit = createTestKit("empty");
            kit.setItems("");
            injectKit(service, kit);

            KitService.ClaimResult result = service.claimKit(player, "empty");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit returns EMPTY_KIT when items is null")
        void claimNullItems() throws Exception {
            KitDefinition kit = createTestKit("nullitems");
            kit.setItems(null);
            injectKit(service, kit);

            KitService.ClaimResult result = service.claimKit(player, "nullitems");
            assertThat(result).isEqualTo(KitService.ClaimResult.EMPTY_KIT);
        }

        @Test
        @DisplayName("claimKit returns INVENTORY_FULL when not enough slots")
        void claimInventoryFull() throws Exception {
            KitDefinition kit = createTestKit("big");
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem1 = mock(ItemStack.class);
            ItemStack mockItem2 = mock(ItemStack.class);
            doReturn(new ItemStack[]{mockItem1, mockItem2}).when(spyService).deserializeItems("someBase64Data");

            ItemStack occupied = mock(ItemStack.class);
            when(occupied.getType()).thenReturn(Material.STONE);
            ItemStack[] fullInv = new ItemStack[36];
            Arrays.fill(fullInv, occupied);
            fullInv[0] = null; // only 1 empty slot, need 2
            when(inventory.getStorageContents()).thenReturn(fullInv);

            KitService.ClaimResult result = spyService.claimKit(player, "big");
            assertThat(result).isEqualTo(KitService.ClaimResult.INVENTORY_FULL);
        }

        @Test
        @DisplayName("claimKit succeeds for free kit with no restrictions")
        void claimFreeKitSuccess() throws Exception {
            KitDefinition kit = createTestKit("free");
            kit.setPrice(0);
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "free");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);

            verify(inventory).addItem(mockItem);
        }

        @Test
        @DisplayName("claimKit deducts price for paid kit")
        void claimPaidKitDeductsPrice() throws Exception {
            KitDefinition kit = createTestKit("paid");
            kit.setPrice(100);
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            Economy mockEconomy = setupMockEconomy();
            when(mockEconomy.has(eq(player), eq(100.0))).thenReturn(true);
            EconomyResponse successResponse = new EconomyResponse(100, 900, EconomyResponse.ResponseType.SUCCESS, "");
            when(mockEconomy.withdrawPlayer(eq(player), eq(100.0))).thenReturn(successResponse);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "paid");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);

            verify(mockEconomy).withdrawPlayer(eq(player), eq(100.0));
        }

        @Test
        @DisplayName("claimKit does not deduct for free kit even when economy available")
        void claimFreeKitNoDeduction() throws Exception {
            KitDefinition kit = createTestKit("freenodeduct");
            kit.setPrice(0);
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            Economy mockEconomy = setupMockEconomy();

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "freenodeduct");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);

            verify(mockEconomy, never()).withdrawPlayer(any(Player.class), anyDouble());
        }

        @Test
        @DisplayName("claimKit updates claim record for new claim")
        void claimUpdatesNewRecord() throws Exception {
            KitDefinition kit = createTestKit("tracked");
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            spyService.claimKit(player, "tracked");

            verify(mockClaimOperator).insert(any(KitClaimData.class));
        }

        @Test
        @DisplayName("claimKit allows re-claim when cooldown expired")
        void claimWhenCooldownExpired() throws Exception {
            KitDefinition kit = createTestKit("reclaim");
            kit.setReBuyable(true);
            kit.setCooldown(10);
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitClaimData oldClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("reclaim")
                    .lastClaim(System.currentTimeMillis() - 20000) // 20s ago, cooldown 10s
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(oldClaim));

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "reclaim");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);
        }

        @Test
        @DisplayName("claimKit skips permission check when kit has no permission")
        void claimSkipsPermissionCheckWhenEmpty() throws Exception {
            KitDefinition kit = createTestKit("noperm");
            kit.setPermission("");
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "noperm");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);

            verify(player, never()).hasPermission(anyString());
        }

        @Test
        @DisplayName("claimKit allows rebuyable kit with zero cooldown to be re-claimed")
        void claimRebuyableZeroCooldown() throws Exception {
            KitDefinition kit = createTestKit("rebuyable");
            kit.setReBuyable(true);
            kit.setCooldown(0);
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitClaimData existingClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("rebuyable")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(5)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(existingClaim));

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "rebuyable");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);
        }

        @Test
        @DisplayName("claimKit gives multiple items to player")
        void claimMultipleItems() throws Exception {
            KitDefinition kit = createTestKit("multi");
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack item1 = mock(ItemStack.class);
            ItemStack item2 = mock(ItemStack.class);
            ItemStack item3 = mock(ItemStack.class);
            when(item1.clone()).thenReturn(item1);
            when(item2.clone()).thenReturn(item2);
            when(item3.clone()).thenReturn(item3);
            doReturn(new ItemStack[]{item1, item2, item3}).when(spyService).deserializeItems("someBase64Data");
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            KitService.ClaimResult result = spyService.claimKit(player, "multi");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);

            verify(inventory).addItem(item1);
            verify(inventory).addItem(item2);
            verify(inventory).addItem(item3);
        }

        @Test
        @DisplayName("claimKit counts AIR slots as empty for capacity check")
        void claimCountsAirAsEmpty() throws Exception {
            KitDefinition kit = createTestKit("aircheck");
            kit.setItems("someBase64Data");
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            ItemStack occupied = mock(ItemStack.class);
            when(occupied.getType()).thenReturn(Material.STONE);
            ItemStack airItem = mock(ItemStack.class);
            when(airItem.getType()).thenReturn(Material.AIR);

            ItemStack[] inv = new ItemStack[]{null, airItem, occupied};
            when(inventory.getStorageContents()).thenReturn(inv);

            KitService.ClaimResult result = spyService.claimKit(player, "aircheck");
            assertThat(result).isEqualTo(KitService.ClaimResult.SUCCESS);
        }
    }

    // =========================================================================
    // Cooldown Tests
    // =========================================================================
    @Nested
    @DisplayName("Cooldown Tests")
    class CooldownTests {

        private Player player;

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
            player = createMockPlayer();
        }

        @Test
        @DisplayName("getRemainingCooldown returns 0 when never claimed (rebuyable)")
        void neverClaimedRebuyable() {
            KitDefinition kit = createTestKit("fresh");
            kit.setReBuyable(true);
            kit.setCooldown(3600);

            when(mockQuery.list()).thenReturn(Collections.emptyList());

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("getRemainingCooldown returns -1 for one-time kit already claimed")
        void oneTimeAlreadyClaimed() {
            KitDefinition kit = createTestKit("once");
            kit.setReBuyable(false);

            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("once")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(-1);
        }

        @Test
        @DisplayName("getRemainingCooldown returns 0 for one-time kit never claimed")
        void oneTimeNeverClaimed() {
            KitDefinition kit = createTestKit("once2");
            kit.setReBuyable(false);

            when(mockQuery.list()).thenReturn(Collections.emptyList());

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("getRemainingCooldown returns positive value when cooldown active")
        void activeCooldown() {
            KitDefinition kit = createTestKit("cd");
            kit.setReBuyable(true);
            kit.setCooldown(3600);

            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("cd")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isGreaterThan(0);
            assertThat(remaining).isLessThanOrEqualTo(3600 * 1000L);
        }

        @Test
        @DisplayName("getRemainingCooldown returns 0 when cooldown expired")
        void expiredCooldown() {
            KitDefinition kit = createTestKit("expired");
            kit.setReBuyable(true);
            kit.setCooldown(10);

            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("expired")
                    .lastClaim(System.currentTimeMillis() - 20000)
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("getRemainingCooldown returns 0 for rebuyable kit with zero cooldown")
        void zeroCooldownRebuyable() {
            KitDefinition kit = createTestKit("zerocool");
            kit.setReBuyable(true);
            kit.setCooldown(0);

            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("zerocool")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(3)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(0);
        }

        @Test
        @DisplayName("getRemainingCooldown clamps negative remaining to zero")
        void remainingNeverNegative() {
            KitDefinition kit = createTestKit("veryold");
            kit.setReBuyable(true);
            kit.setCooldown(1);

            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid(player.getUniqueId().toString())
                    .kitName("veryold")
                    .lastClaim(System.currentTimeMillis() - 1000000)
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            long remaining = service.getRemainingCooldown(player, kit);
            assertThat(remaining).isEqualTo(0);
        }
    }

    // =========================================================================
    // Format Cooldown Tests
    // =========================================================================
    @Nested
    @DisplayName("Format Cooldown Tests")
    class FormatCooldownTests {

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("formats zero as claimable")
        void formatZero() {
            assertThat(service.formatCooldown(0)).isEqualTo("可领取");
        }

        @Test
        @DisplayName("formats negative values as claimable")
        void formatNegative() {
            assertThat(service.formatCooldown(-100)).isEqualTo("可领取");
            assertThat(service.formatCooldown(-1)).isEqualTo("可领取");
        }

        @Test
        @DisplayName("formats seconds only")
        void formatSecondsOnly() {
            String result = service.formatCooldown(45_000); // 45 seconds
            assertThat(result).contains("45").contains("秒");
            assertThat(result).doesNotContain("小时").doesNotContain("分钟");
        }

        @Test
        @DisplayName("formats minutes and seconds")
        void formatMinutesSeconds() {
            String result = service.formatCooldown(150_000); // 2 min 30 sec
            assertThat(result).contains("2").contains("分钟");
            assertThat(result).contains("30").contains("秒");
        }

        @Test
        @DisplayName("formats hours, minutes, and seconds")
        void formatHoursMinutesSeconds() {
            long millis = (2 * 3600 + 15 * 60 + 30) * 1000L; // 2h 15m 30s
            String result = service.formatCooldown(millis);
            assertThat(result).contains("2").contains("小时");
            assertThat(result).contains("15").contains("分钟");
            assertThat(result).contains("30").contains("秒");
        }

        @Test
        @DisplayName("formats hours only (exact)")
        void formatHoursOnly() {
            long millis = 2 * 3600 * 1000L; // exactly 2 hours
            String result = service.formatCooldown(millis);
            assertThat(result).contains("2").contains("小时");
            assertThat(result).doesNotContain("分钟");
        }

        @Test
        @DisplayName("formats 1 second")
        void formatOneSecond() {
            String result = service.formatCooldown(1000);
            assertThat(result).contains("1").contains("秒");
        }

        @Test
        @DisplayName("formats sub-second as 0 seconds")
        void formatSubSecond() {
            String result = service.formatCooldown(500); // 500ms = 0 seconds
            assertThat(result).contains("0").contains("秒");
        }

        @Test
        @DisplayName("formats hours and seconds with no minutes")
        void formatHoursAndSecondsNoMinutes() {
            long millis = (1 * 3600 + 0 * 60 + 45) * 1000L; // 1h 0m 45s
            String result = service.formatCooldown(millis);
            assertThat(result).contains("1").contains("小时");
            assertThat(result).contains("45").contains("秒");
            assertThat(result).doesNotContain("分钟");
        }

        @Test
        @DisplayName("formats minutes only with no seconds")
        void formatMinutesOnlyNoSeconds() {
            long millis = 5 * 60 * 1000L; // exactly 5 minutes
            String result = service.formatCooldown(millis);
            assertThat(result).contains("5").contains("分钟");
            // seconds = 0, sb.length() > 0 after minutes, so no seconds appended
            assertThat(result).doesNotContain("秒");
        }

        @Test
        @DisplayName("formats large values correctly")
        void formatLargeValue() {
            long millis = (24 * 3600 + 59 * 60 + 59) * 1000L; // 24h 59m 59s
            String result = service.formatCooldown(millis);
            assertThat(result).contains("24").contains("小时");
            assertThat(result).contains("59").contains("分钟");
            assertThat(result).contains("59").contains("秒");
        }
    }

    // =========================================================================
    // Serialization Tests
    // =========================================================================
    @Nested
    @DisplayName("Serialization Tests")
    class SerializationTests {

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("deserializeItems returns null for null data")
        void deserializeNullReturnsNull() {
            assertThat(service.deserializeItems(null)).isNull();
        }

        @Test
        @DisplayName("deserializeItems returns null for empty data")
        void deserializeEmptyReturnsNull() {
            assertThat(service.deserializeItems("")).isNull();
        }

        @Test
        @DisplayName("deserializeItems returns null for invalid base64 and logs error")
        void deserializeInvalidDataReturnsNull() {
            assertThat(service.deserializeItems("AAAA")).isNull();
            verify(mockLogger).error(contains("Failed to deserialize"));
        }

        @Test
        @DisplayName("deserializeItems returns null for whitespace-only data")
        void deserializeWhitespaceReturnsNull() {
            // Non-empty but will fail base64 decode or stream read
            assertThat(service.deserializeItems("   ")).isNull();
        }
    }

    // =========================================================================
    // Save Tests
    // =========================================================================
    @Nested
    @DisplayName("Save Tests")
    class SaveTests {

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("saveKitItems returns false for nonexistent kit")
        void saveNonexistentKit() {
            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.getType()).thenReturn(Material.STONE);

            boolean result = service.saveKitItems("nosuchkit", new ItemStack[]{mockItem});
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("saveKitToFile creates YAML file with correct structure")
        void saveKitToFileCreatesYaml() throws Exception {
            KitDefinition kit = new KitDefinition();
            kit.setName("saved");
            kit.setDisplayName("&aSaved Kit");
            kit.setIcon("DIAMOND");
            kit.setPrice(50.0);
            kit.setLevelRequired(5);
            kit.setPermission("kit.saved");
            kit.setReBuyable(true);
            kit.setCooldown(1800);
            kit.setItems("base64data");
            kit.setDescription(Arrays.asList("Line 1", "Line 2"));
            kit.setPlayerCommands(Arrays.asList("cmd1", "cmd2"));
            kit.setConsoleCommands(Arrays.asList("give {player} diamond 1"));

            boolean result = service.saveKitToFile("saved", kit);
            assertThat(result).isTrue();

            File kitFile = new File(tempDir, "kits/saved.yml");
            assertThat(kitFile).exists();
            assertThat(kitFile.length()).isGreaterThan(0);
        }

        @Test
        @DisplayName("saveKitToFile handles IO errors gracefully")
        void saveKitToFileHandlesErrors() {
            when(plugin.getResourceFolderPath()).thenReturn("/nonexistent/path/that/wont/work");

            KitDefinition kit = new KitDefinition();
            kit.setName("fail");

            boolean result = service.saveKitToFile("fail", kit);
            assertThat(result).isFalse();
            verify(mockLogger).error(contains("Failed to save kit file"));
        }

        @Test
        @DisplayName("saveKitToFile creates file that can be reloaded correctly")
        void saveAndReload() throws Exception {
            KitDefinition kit = new KitDefinition();
            kit.setName("roundtrip");
            kit.setDisplayName("&cRound Trip");
            kit.setIcon("CHEST");
            kit.setPrice(25.0);
            kit.setLevelRequired(3);
            kit.setPermission("kit.rt");
            kit.setReBuyable(true);
            kit.setCooldown(600);
            kit.setItems("testdata");
            kit.setDescription(Arrays.asList("Test description"));
            kit.setPlayerCommands(Collections.emptyList());
            kit.setConsoleCommands(Collections.emptyList());

            boolean saved = service.saveKitToFile("roundtrip", kit);
            assertThat(saved).isTrue();

            service.reload();

            KitDefinition loaded = service.getKit("roundtrip");
            assertThat(loaded).isNotNull();
            assertThat(loaded.getDisplayName()).isEqualTo("&cRound Trip");
            assertThat(loaded.getPrice()).isEqualTo(25.0);
            assertThat(loaded.getLevelRequired()).isEqualTo(3);
            assertThat(loaded.getPermission()).isEqualTo("kit.rt");
            assertThat(loaded.isReBuyable()).isTrue();
            assertThat(loaded.getCooldown()).isEqualTo(600);
            assertThat(loaded.getItems()).isEqualTo("testdata");
        }
    }

    // =========================================================================
    // Command Execution Tests
    // =========================================================================
    @Nested
    @DisplayName("Command Execution Tests")
    class CommandExecutionTests {

        private Player player;

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
            player = createMockPlayer();
        }

        @Test
        @DisplayName("claimKit executes player commands with {player} replacement")
        void executesPlayerCommands() throws Exception {
            KitDefinition kit = createTestKit("cmdkit");
            kit.setItems("someBase64Data");
            kit.setPlayerCommands(Arrays.asList("spawn", "msg {player} Welcome!"));
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            spyService.claimKit(player, "cmdkit");

            verify(player).performCommand("spawn");
            verify(player).performCommand("msg TestPlayer Welcome!");
        }

        @Test
        @DisplayName("claimKit executes console commands via Bukkit scheduler")
        void executesConsoleCommands() throws Exception {
            KitDefinition kit = createTestKit("consolekit");
            kit.setItems("someBase64Data");
            kit.setConsoleCommands(Arrays.asList("give {player} diamond 1"));
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin ultiToolsPlugin = mock(Plugin.class);
                BukkitScheduler scheduler = mock(BukkitScheduler.class);

                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(ultiToolsPlugin);
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

                spyService.claimKit(player, "consolekit");

                verify(scheduler).runTask(eq(ultiToolsPlugin), any(Runnable.class));
            }
        }

        @Test
        @DisplayName("claimKit skips player commands when list is empty")
        void skipsEmptyPlayerCommands() throws Exception {
            KitDefinition kit = createTestKit("nocmd");
            kit.setItems("someBase64Data");
            kit.setPlayerCommands(Collections.emptyList());
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            spyService.claimKit(player, "nocmd");

            verify(player, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("claimKit skips console commands when list is null")
        void skipsNullConsoleCommands() throws Exception {
            KitDefinition kit = createTestKit("nullcmd");
            kit.setItems("someBase64Data");
            kit.setConsoleCommands(null);
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            // No static Bukkit mock needed -- should skip entirely without calling Bukkit
            spyService.claimKit(player, "nullcmd");
        }

        @Test
        @DisplayName("claimKit skips player commands when list is null")
        void skipsNullPlayerCommands() throws Exception {
            KitDefinition kit = createTestKit("nullplayercmd");
            kit.setItems("someBase64Data");
            kit.setPlayerCommands(null);
            kit.setConsoleCommands(null);
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            spyService.claimKit(player, "nullplayercmd");

            verify(player, never()).performCommand(anyString());
        }

        @Test
        @DisplayName("console commands skip when UltiTools plugin not found")
        void consoleCommandsSkipWhenNoPlugin() throws Exception {
            KitDefinition kit = createTestKit("noplugin");
            kit.setItems("someBase64Data");
            kit.setConsoleCommands(Arrays.asList("say hello"));
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(null);

                spyService.claimKit(player, "noplugin");

                mockedBukkit.verify(Bukkit::getScheduler, never());
            }
        }

        @Test
        @DisplayName("console commands replace {player} with player name for each command")
        void consoleCommandsReplacePlayerMultiple() throws Exception {
            KitDefinition kit = createTestKit("conrep");
            kit.setItems("someBase64Data");
            kit.setConsoleCommands(Arrays.asList(
                    "give {player} diamond 1",
                    "broadcast {player} claimed a kit"
            ));
            injectKit(service, kit);

            KitServiceImpl spyService = spy(service);
            injectKit(spyService, kit);

            ItemStack mockItem = mock(ItemStack.class);
            when(mockItem.clone()).thenReturn(mockItem);
            doReturn(new ItemStack[]{mockItem}).when(spyService).deserializeItems("someBase64Data");

            PlayerInventory inventory = player.getInventory();
            when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);

            try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
                PluginManager pluginManager = mock(PluginManager.class);
                Plugin ultiToolsPlugin = mock(Plugin.class);
                BukkitScheduler scheduler = mock(BukkitScheduler.class);

                mockedBukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
                when(pluginManager.getPlugin("UltiTools")).thenReturn(ultiToolsPlugin);
                mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

                spyService.claimKit(player, "conrep");

                verify(scheduler, times(2)).runTask(eq(ultiToolsPlugin), any(Runnable.class));
            }
        }
    }

    // =========================================================================
    // Internal Method Tests
    // =========================================================================
    @Nested
    @DisplayName("Internal Method Tests")
    class InternalMethodTests {

        @BeforeEach
        void setUp() {
            new File(tempDir, "kits").mkdirs();
            service = createService();
        }

        @Test
        @DisplayName("getClaimData returns null when no claims exist")
        void getClaimDataReturnsNull() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            KitClaimData result = service.getClaimData(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getClaimData finds claim by kit name (case insensitive)")
        void getClaimDataFindsByName() {
            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("Starter")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            KitClaimData result = service.getClaimData(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");
            assertThat(result).isSameAs(claim);
        }

        @Test
        @DisplayName("getClaimData ignores claims for different kits")
        void getClaimDataIgnoresDifferentKits() {
            KitClaimData claim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("vip")
                    .lastClaim(System.currentTimeMillis())
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(claim));

            KitClaimData result = service.getClaimData(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("getClaimData finds correct claim among multiple")
        void getClaimDataFindsAmongMultiple() {
            KitClaimData starterClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("starter")
                    .lastClaim(1000L)
                    .claimCount(1)
                    .build();

            KitClaimData vipClaim = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("vip")
                    .lastClaim(2000L)
                    .claimCount(2)
                    .build();

            when(mockQuery.list()).thenReturn(Arrays.asList(starterClaim, vipClaim));

            KitClaimData result = service.getClaimData(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), "vip");
            assertThat(result).isSameAs(vipClaim);
        }

        @Test
        @DisplayName("getClaimData queries with player UUID string")
        void getClaimDataQueriesCorrectly() {
            UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000042");
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.getClaimData(playerUuid, "somekit");

            verify(mockQuery).where("player_uuid");
            verify(mockQuery).eq(playerUuid.toString());
        }

        @Test
        @DisplayName("updateClaimData inserts new record when no existing claim")
        void updateClaimDataInserts() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            service.updateClaimData(UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");

            ArgumentCaptor<KitClaimData> captor = ArgumentCaptor.forClass(KitClaimData.class);
            verify(mockClaimOperator).insert(captor.capture());

            KitClaimData inserted = captor.getValue();
            assertThat(inserted.getPlayerUuid()).isEqualTo("00000000-0000-0000-0000-000000000001");
            assertThat(inserted.getKitName()).isEqualTo("starter");
            assertThat(inserted.getClaimCount()).isEqualTo(1);
            assertThat(inserted.getUuid()).isNotNull();
        }

        @Test
        @DisplayName("updateClaimData updates existing record incrementing claim count")
        void updateClaimDataUpdates() throws Exception {
            KitClaimData existing = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("starter")
                    .lastClaim(1000L)
                    .claimCount(3)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(existing));

            service.updateClaimData(UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");

            verify(mockClaimOperator).update(existing);
            assertThat(existing.getClaimCount()).isEqualTo(4);
            assertThat(existing.getLastClaim()).isGreaterThan(1000L);
        }

        @Test
        @DisplayName("updateClaimData logs error on update failure")
        void updateClaimDataLogsError() throws Exception {
            KitClaimData existing = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .playerUuid("00000000-0000-0000-0000-000000000001")
                    .kitName("starter")
                    .lastClaim(1000L)
                    .claimCount(1)
                    .build();

            when(mockQuery.list()).thenReturn(Collections.singletonList(existing));
            doThrow(new IllegalAccessException("test error")).when(mockClaimOperator).update(existing);

            service.updateClaimData(UUID.fromString("00000000-0000-0000-0000-000000000001"), "starter");

            verify(mockLogger).error(contains("Failed to update kit claim data"));
        }

        @Test
        @DisplayName("updateClaimData sets lastClaim to approximately current time")
        void updateClaimDataSetsCurrentTime() {
            when(mockQuery.list()).thenReturn(Collections.emptyList());

            long before = System.currentTimeMillis();
            service.updateClaimData(UUID.fromString("00000000-0000-0000-0000-000000000001"), "test");
            long after = System.currentTimeMillis();

            ArgumentCaptor<KitClaimData> captor = ArgumentCaptor.forClass(KitClaimData.class);
            verify(mockClaimOperator).insert(captor.capture());

            long lastClaim = captor.getValue().getLastClaim();
            assertThat(lastClaim).isBetween(before, after);
        }

        @Test
        @DisplayName("parseKitFile returns kit with defaults for minimal YAML")
        void parseKitFileMinimalYaml() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "minimal.yml");

            FileWriter writer = new FileWriter(kitFile);
            writer.write("# empty kit\n");
            writer.close();

            KitDefinition result = service.parseKitFile(kitFile);
            assertThat(result).isNotNull();
            assertThat(result.getDisplayName()).isEqualTo("&7Kit");
            assertThat(result.getIcon()).isEqualTo("CHEST");
            assertThat(result.getPrice()).isEqualTo(0.0);
            assertThat(result.getLevelRequired()).isEqualTo(0);
            assertThat(result.isReBuyable()).isFalse();
            assertThat(result.getCooldown()).isEqualTo(0);
        }

        @Test
        @DisplayName("parseKitFile handles valid icon material")
        void parseKitFileValidIcon() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "goodicon.yml");

            FileWriter writer = new FileWriter(kitFile);
            writer.write("icon: DIAMOND_SWORD\n");
            writer.close();

            KitDefinition result = service.parseKitFile(kitFile);
            assertThat(result).isNotNull();
            assertThat(result.getIcon()).isEqualTo("DIAMOND_SWORD");
        }

        @Test
        @DisplayName("parseKitFile uppercases icon material string")
        void parseKitFileUppercasesIcon() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "lowericon.yml");

            FileWriter writer = new FileWriter(kitFile);
            writer.write("icon: diamond\n");
            writer.close();

            KitDefinition result = service.parseKitFile(kitFile);
            assertThat(result).isNotNull();
            assertThat(result.getIcon()).isEqualTo("DIAMOND");
        }

        @Test
        @DisplayName("parseKitFile with all fields populated")
        void parseKitFileAllFields() throws IOException {
            File kitsFolder = new File(tempDir, "kits");
            kitsFolder.mkdirs();
            File kitFile = new File(kitsFolder, "allfields.yml");

            StringBuilder yaml = new StringBuilder();
            yaml.append("displayName: \"&6Complete Kit\"\n");
            yaml.append("icon: DIAMOND_CHESTPLATE\n");
            yaml.append("price: 99.99\n");
            yaml.append("levelRequired: 15\n");
            yaml.append("permission: \"kit.complete\"\n");
            yaml.append("reBuyable: true\n");
            yaml.append("cooldown: 3600\n");
            yaml.append("items: \"somedata\"\n");
            yaml.append("description:\n  - \"Line 1\"\n  - \"Line 2\"\n");
            yaml.append("playerCommands:\n  - \"spawn\"\n");
            yaml.append("consoleCommands:\n  - \"give {player} diamond 1\"\n");

            FileWriter writer = new FileWriter(kitFile);
            writer.write(yaml.toString());
            writer.close();

            KitDefinition result = service.parseKitFile(kitFile);
            assertThat(result).isNotNull();
            assertThat(result.getDisplayName()).isEqualTo("&6Complete Kit");
            assertThat(result.getIcon()).isEqualTo("DIAMOND_CHESTPLATE");
            assertThat(result.getPrice()).isEqualTo(99.99);
            assertThat(result.getLevelRequired()).isEqualTo(15);
            assertThat(result.getPermission()).isEqualTo("kit.complete");
            assertThat(result.isReBuyable()).isTrue();
            assertThat(result.getCooldown()).isEqualTo(3600);
            assertThat(result.getItems()).isEqualTo("somedata");
            assertThat(result.getDescription()).containsExactly("Line 1", "Line 2");
            assertThat(result.getPlayerCommands()).containsExactly("spawn");
            assertThat(result.getConsoleCommands()).containsExactly("give {player} diamond 1");
        }
    }
}
