package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.DataStoreManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Tests for JsonStore.
 */
@SuppressWarnings("PMD.UnusedLocalVariable") // Some variables exist for test setup clarity
class JsonStoreTest {

    @TempDir
    Path tempDir;

    private static MockedStatic<UltiTools> mockedUltiTools;
    private static MockedStatic<DataStoreManager> mockedDataStoreManager;
    private static UltiTools mockUltiToolsInstance;
    private static BukkitScheduler mockScheduler;
    private static BukkitTask mockTask;
    private static PluginManager mockPluginManager;

    @BeforeAll
    static void setUpClass() {
        // Setup Bukkit Server mock
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            mockScheduler = mock(BukkitScheduler.class);
            mockTask = mock(BukkitTask.class);
            
            when(mockServer.getLogger()).thenReturn(mockLogger);
            when(mockServer.getScheduler()).thenReturn(mockScheduler);
            when(mockScheduler.runTaskTimerAsynchronously(
                org.mockito.ArgumentMatchers.any(), 
                org.mockito.ArgumentMatchers.any(Runnable.class), 
                org.mockito.ArgumentMatchers.anyLong(), 
                org.mockito.ArgumentMatchers.anyLong()
            )).thenReturn(mockTask);
            
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(mockServer);
        }
        
        // Setup UltiTools static mock BEFORE JsonStore class is loaded
        mockUltiToolsInstance = mock(UltiTools.class);
        FileConfiguration mockConfig = mock(FileConfiguration.class);
        
        when(mockUltiToolsInstance.getConfig()).thenReturn(mockConfig);
        when(mockConfig.getInt(anyString())).thenReturn(10);
        when(mockUltiToolsInstance.isEnabled()).thenReturn(true);

        // 02-12 Task 2: every getOperator(UltiToolsPlugin, Class) call now runs checkOwnership()
        // first, which consults UltiTools.getInstance().getPluginManager().findOwningPlugin(...).
        // Each nested class's own @BeforeEach (re-)stubs findOwningPlugin's default Answer to
        // echo back its own freshly-created mockPlugin's name, so ownership passes by default for
        // the plugin under test; individual tests override this with a more specific,
        // entity-scoped stub when they need a genuine refusal.
        mockPluginManager = mock(PluginManager.class);
        when(mockUltiToolsInstance.getPluginManager()).thenReturn(mockPluginManager);

        mockedUltiTools = mockStatic(UltiTools.class);
        mockedUltiTools.when(UltiTools::getInstance).thenReturn(mockUltiToolsInstance);

        mockedDataStoreManager = mockStatic(DataStoreManager.class);
    }
    
    @org.junit.jupiter.api.AfterAll
    static void tearDownClass() {
        if (mockedUltiTools != null) {
            mockedUltiTools.close();
        }
        if (mockedDataStoreManager != null) {
            mockedDataStoreManager.close();
        }
    }

    // ==================== Constructor Tests ====================
    
    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should create JsonStore with store location")
        void testConstructor() {
            JsonStore store = new JsonStore(tempDir.toString());
            assertThat(store).isNotNull();
            assertThat(store.getStoreType()).isEqualTo("json");
        }
        
        @Test
        @DisplayName("Should register with DataStoreManager")
        void testRegistersWithManager() {
            JsonStore store = new JsonStore(tempDir.toString());
            mockedDataStoreManager.verify(() -> DataStoreManager.register(store));
            assertThat(store).isNotNull();
        }
    }

    // ==================== getOperator Tests ====================
    
    @Nested
    @DisplayName("getOperator Tests")
    class GetOperatorTests {
        
        private JsonStore store;
        private UltiToolsPlugin mockPlugin;
        
        @BeforeEach
        void setUp() {
            store = new JsonStore(tempDir.toString());
            mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getPluginName()).thenReturn("TestPlugin");
            when(mockPluginManager.findOwningPlugin(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> mockPlugin.getPluginName());
        }

        @Test
        @DisplayName("Should return DataOperator for annotated entity")
        void testGetOperator() {
            DataOperator<AnnotatedTestData> operator = store.getOperator(mockPlugin, AnnotatedTestData.class);
            
            assertThat(operator).isNotNull();
            assertThat(operator).isInstanceOf(SimpleJsonDataOperator.class);
        }
        
        @Test
        @DisplayName("Should throw exception for entity without @Table annotation")
        void testGetOperatorNoAnnotation() {
            assertThatThrownBy(() -> store.getOperator(mockPlugin, NonAnnotatedTestData.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No @Table annotation");
        }
        
        @Test
        @DisplayName("Should cache and return same operator for same entity class")
        void testOperatorCaching() {
            DataOperator<AnnotatedTestData> operator1 = store.getOperator(mockPlugin, AnnotatedTestData.class);
            DataOperator<AnnotatedTestData> operator2 = store.getOperator(mockPlugin, AnnotatedTestData.class);
            
            assertThat(operator1).isSameAs(operator2);
        }
        
        @Test
        @DisplayName("Should return different operators for different entity classes")
        void testDifferentOperatorsForDifferentClasses() {
            DataOperator<AnnotatedTestData> operator1 = store.getOperator(mockPlugin, AnnotatedTestData.class);
            DataOperator<AnotherAnnotatedTestData> operator2 = store.getOperator(mockPlugin, AnotherAnnotatedTestData.class);
            
            assertThat(operator1).isNotSameAs(operator2);
        }
        
        @Test
        @DisplayName("Should create operator that can insert and retrieve data")
        void testOperatorFunctionality() {
            DataOperator<AnnotatedTestData> operator = store.getOperator(mockPlugin, AnnotatedTestData.class);
            
            // Insert data to verify operator works
            AnnotatedTestData data = new AnnotatedTestData();
            data.setId("func-test-" + System.nanoTime()); // Unique ID
            data.setName("test");
            operator.insert(data);
            
            // Verify we can retrieve it
            AnnotatedTestData retrieved = operator.getById(data.getId());
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getName()).isEqualTo("test");
        }
    }

    // ==================== destroyAllOperators Tests ====================
    
    @Nested
    @DisplayName("destroyAllOperators Tests")
    class DestroyAllOperatorsTests {
        
        private JsonStore store;
        private UltiToolsPlugin mockPlugin;
        
        @BeforeEach
        void setUp() {
            store = new JsonStore(tempDir.toString());
            mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getPluginName()).thenReturn("TestPlugin");
            when(mockPluginManager.findOwningPlugin(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> mockPlugin.getPluginName());
        }

        @Test
        @DisplayName("Should flush all operators without error")
        void testDestroyAllOperators() {
            // Create operators and add data
            DataOperator<AnnotatedTestData> operator = store.getOperator(mockPlugin, AnnotatedTestData.class);
            AnnotatedTestData data = new AnnotatedTestData();
            data.setId("destroy-test-" + System.nanoTime()); // Unique ID
            data.setName("test");
            operator.insert(data);
            
            // destroyAllOperators should not throw
            store.destroyAllOperators();
            
            // Data should still be in memory cache
            assertThat(operator.getById(data.getId())).isNotNull();
        }
        
        @Test
        @DisplayName("Should handle empty operators map")
        void testDestroyEmptyOperators() {
            // No operators created, should not throw
            assertDoesNotThrow(() -> store.destroyAllOperators());
        }
    }

    // ==================== getStoreType Tests ====================
    
    @Nested
    @DisplayName("getStoreType Tests")
    class GetStoreTypeTests {
        
        @Test
        @DisplayName("Should return 'json' as store type")
        void testGetStoreType() {
            JsonStore store = new JsonStore(tempDir.toString());
            assertThat(store.getStoreType()).isEqualTo("json");
        }
    }

    // ==================== Integration Tests ====================
    
    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {
        
        private JsonStore store;
        private UltiToolsPlugin mockPlugin;
        
        @BeforeEach
        void setUp() {
            store = new JsonStore(tempDir.toString());
            mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getPluginName()).thenReturn("IntegrationTestPlugin");
            when(mockPluginManager.findOwningPlugin(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> mockPlugin.getPluginName());
        }
        
        @Test
        @DisplayName("Full CRUD cycle through JsonStore")
        void testFullCrudCycle() throws IllegalAccessException {
            DataOperator<AnnotatedTestData> operator = store.getOperator(mockPlugin, AnnotatedTestData.class);
            
            // Create
            AnnotatedTestData data = new AnnotatedTestData();
            data.setId("crud-test");
            data.setName("created");
            operator.insert(data);
            
            // Read
            AnnotatedTestData retrieved = operator.getById("crud-test");
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getName()).isEqualTo("created");
            
            // Update
            retrieved.setName("updated");
            operator.update(retrieved);
            AnnotatedTestData afterUpdate = operator.getById("crud-test");
            assertThat(afterUpdate.getName()).isEqualTo("updated");
            
            // Delete
            operator.delById("crud-test");
            assertThat(operator.getById("crud-test")).isNull();
        }
        
        @Test
        @DisplayName("Two plugins, two entities: each succeeds on its own, isolated storage (SILENT-04)")
        void testMultiplePluginsWithOwnEntities() {
            // Was: testMultiplePlugins, which had plugin1 and plugin2 both request the SAME
            // @Table entity class and asserted each got its own isolated operator. Under 02-12's
            // ownership model an entity has exactly one owner (PluginManager.findOwningPlugin,
            // first-registration-wins), so that scenario is no longer legitimate -- it is exactly
            // what D-14 exists to refuse (see testSecondPluginRequestingFirstPluginsEntityIsRefused
            // below). This test keeps SILENT-04's real remaining claim: dataOperatorMap is keyed by
            // (plugin identity, entity class), so two DIFFERENT, each-legitimately-owned entities
            // resolve to two distinct operators writing to two distinct directories.
            UltiToolsPlugin plugin1 = mock(UltiToolsPlugin.class);
            when(plugin1.getPluginName()).thenReturn("Plugin1");
            when(mockPluginManager.findOwningPlugin(AnnotatedTestData.class)).thenReturn("Plugin1");

            UltiToolsPlugin plugin2 = mock(UltiToolsPlugin.class);
            when(plugin2.getPluginName()).thenReturn("Plugin2");
            when(mockPluginManager.findOwningPlugin(AnotherAnnotatedTestData.class)).thenReturn("Plugin2");

            DataOperator<AnnotatedTestData> op1 = store.getOperator(plugin1, AnnotatedTestData.class);
            DataOperator<AnotherAnnotatedTestData> op2 = store.getOperator(plugin2, AnotherAnnotatedTestData.class);

            assertThat(op1).isNotSameAs(op2);

            // Insert data through plugin1's operator
            AnnotatedTestData data1 = new AnnotatedTestData();
            data1.setId("plugin1-data");
            data1.setName("from plugin 1");
            op1.insert(data1);

            // Visible through plugin1's own operator, and plugin2's operator (a different entity
            // type entirely) has no way to even ask for it.
            assertThat(op1.getById("plugin1-data")).isNotNull();
        }

        @Test
        @DisplayName("A second plugin requesting the first plugin's entity is refused (D-14/D-18, 02-12)")
        void testSecondPluginRequestingFirstPluginsEntityIsRefused() {
            UltiToolsPlugin plugin1 = mock(UltiToolsPlugin.class);
            when(plugin1.getPluginName()).thenReturn("Plugin1");
            when(mockPluginManager.findOwningPlugin(AnnotatedTestData.class)).thenReturn("Plugin1");

            UltiToolsPlugin plugin2 = mock(UltiToolsPlugin.class);
            when(plugin2.getPluginName()).thenReturn("Plugin2");

            DataOperator<AnnotatedTestData> op1 = store.getOperator(plugin1, AnnotatedTestData.class);
            assertThat(op1).isNotNull();

            assertThatThrownBy(() -> store.getOperator(plugin2, AnnotatedTestData.class))
                    .isInstanceOf(DataAccessException.class)
                    .extracting(e -> ((DataAccessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);
        }
    }

    // ==================== Ownership Tests (02-12 Task 2, D-14/D-18) ====================

    /**
     * {@code checkOwnership} is now called as the first statement of both legacy
     * {@code getOperator} overrides -- closing the residual bypass 02-07 disclosed. JsonStore has
     * no connection pool to prove empty on refusal (unlike SQLite/MySQL), so these tests assert
     * against the operator cache instead, via the store's own package-private
     * {@code getDataOperatorCount()} test seam.
     */
    @Nested
    @DisplayName("Ownership Tests")
    class OwnershipTests {

        private JsonStore store;

        @BeforeEach
        void setUp() {
            store = new JsonStore(tempDir.toString());
            when(mockUltiToolsInstance.getDataFolder()).thenReturn(tempDir.toFile());
        }

        @Test
        @DisplayName("getOperator(File, Class) should refuse another plugin's unregistered folder, with no cache side effect")
        void shouldRefuseUnregisteredFolderViaFileOverloadWithNoSideEffects() throws java.io.IOException {
            java.io.File someOtherPluginsFolder = java.nio.file.Files.createTempDirectory("json-other-plugin").toFile();
            when(mockPluginManager.findScopeForDataFolder(someOtherPluginsFolder)).thenReturn(null);

            assertThatThrownBy(() -> store.getOperator(someOtherPluginsFolder, AnnotatedTestData.class))
                    .isInstanceOf(DataAccessException.class)
                    .extracting(e -> ((DataAccessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);

            // getDataOperatorCount() is package-private on JsonStore; this test class is in the
            // same package (com.ultikits.ultitools.interfaces.impl.data.json), so it is called
            // directly -- no reflection needed.
            assertThat(store.getDataOperatorCount())
                    .as("a refused call must not have built an operator")
                    .isZero();
        }

        @Test
        @DisplayName("The framework's own core data folder should no longer be exempt on getOperator(File, Class) -- CR-01, 02-13")
        void shouldRefuseFrameworkCoreFolderViaFileOverload() {
            // mockUltiToolsInstance.getDataFolder() is stubbed to tempDir.toFile() above -- passing
            // tempDir.toFile() itself used to be exactly the framework-core-folder sentinel
            // checkOwnership(File, Class) exempted (02-07/02-12). CR-01 (02-REVIEW.md): the
            // exemption was keyed purely on a value any caller can obtain
            // (UltiTools#getDataFolder() is public in the published jar), not on any credential.
            // 02-13 deletes the exemption; no PluginManager stub for this folder is registered
            // here, so the reverse lookup finds no scope and the call must refuse exactly like any
            // other unregistered folder -- not silently succeed.
            //
            // Fixed outside this plan's own files_modified list (Rule 3 deviation, matching the
            // precedent 02-07/02-12-SUMMARY.md both recorded for the identical situation): this
            // test asserted behavior CR-01's fix in DataStore.java directly breaks.
            assertThatThrownBy(() -> store.getOperator(tempDir.toFile(), AnnotatedTestData.class))
                    .isInstanceOf(DataAccessException.class)
                    .extracting(e -> ((DataAccessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);

            assertThat(store.getDataOperatorCount())
                    .as("a refused call must not have built an operator")
                    .isZero();
        }
    }

    // ==================== Journal location and recovery ====================

    @Nested
    @DisplayName("Journal location and recovery before the first operator")
    class JournalLocationTests {

        private UltiToolsPlugin mockPlugin;

        @BeforeEach
        void setUp() {
            mockPlugin = mock(UltiToolsPlugin.class);
            when(mockPlugin.getPluginName()).thenReturn("JournalPlugin");
            when(mockPluginManager.findOwningPlugin(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> mockPlugin.getPluginName());
        }

        @Test
        @DisplayName("An internal plugin journals in <store>/<plugin>/.ultitools-journal, never inside a table directory")
        void internalIdentityJournalsUnderThePluginDirectory() throws Exception {
            Path storeRoot = tempDir.resolve("internal-store");
            JsonStore store = new JsonStore(storeRoot.toString());
            DataOperator<AnnotatedTestData> operator = store.getOperator(mockPlugin, AnnotatedTestData.class);

            operator.transaction(() -> operator.insert(named("j1", "journaled")));

            Path journal = storeRoot.resolve("JournalPlugin").resolve(JsonJournal.DIRECTORY_NAME);
            assertThat(commitRecords(journal)).as("one committed record in the plugin's journal").hasSize(1);
            Path table = storeRoot.resolve("JournalPlugin").resolve("test_table");
            if (Files.isDirectory(table)) {
                try (java.util.stream.Stream<Path> files = Files.list(table)) {
                    assertThat(files.map(p -> p.getFileName().toString()))
                            .as("no journal file in the table directory").allMatch(name -> name.endsWith(".json"));
                }
            }

            store.flushAllCaches();
            assertThat(commitRecords(journal)).as("the flush's checkpoint truncated the journal").isEmpty();
            assertThat(table.resolve("j1.json")).exists();
        }

        @Test
        @DisplayName("An external plugin journals in <data folder>/data/.ultitools-journal")
        void externalIdentityJournalsUnderTheDataFolder() throws Exception {
            JsonStore store = new JsonStore(tempDir.resolve("external-store").toString());
            java.io.File dataFolder = Files.createDirectories(tempDir.resolve("external-plugin")).toFile();
            DataOperator<AnnotatedTestData> operator =
                    store.getOperator(newDataScope("ExternalPlugin", dataFolder, AnnotatedTestData.class),
                            AnnotatedTestData.class);

            operator.transaction(() -> operator.insert(named("e1", "journaled")));

            Path journal = dataFolder.toPath().resolve("data").resolve(JsonJournal.DIRECTORY_NAME);
            assertThat(commitRecords(journal)).as("one committed record in the data folder's journal").hasSize(1);
        }

        @Test
        @DisplayName("A committed record is applied before the identity's first operator is returned")
        void recoveryRunsBeforeTheFirstOperator() throws Exception {
            Path storeRoot = tempDir.resolve("recovering-store");
            Path root = storeRoot.resolve("JournalPlugin");
            JsonJournal journal = new JsonJournal(root, root.resolve(JsonJournal.DIRECTORY_NAME));
            journal.writeRecord(1L, JsonJournal.Kind.COMMIT, java.util.Collections.singletonList(
                    JsonJournal.Entry.put("test_table", "r1", "{\"id\":\"r1\",\"name\":\"recovered\"}")));

            DataOperator<AnnotatedTestData> operator =
                    new JsonStore(storeRoot.toString()).getOperator(mockPlugin, AnnotatedTestData.class);

            assertThat(operator.getById("r1")).isNotNull();
            assertThat(operator.getById("r1").getName()).isEqualTo("recovered");
            assertThat(commitRecords(root.resolve(JsonJournal.DIRECTORY_NAME))).isEmpty();
        }

        private AnnotatedTestData named(String id, String name) {
            AnnotatedTestData data = new AnnotatedTestData();
            data.setId(id);
            data.setName(name);
            return data;
        }

        private java.util.List<String> commitRecords(Path journal) throws java.io.IOException {
            java.util.List<String> names = new java.util.ArrayList<>();
            if (!Files.isDirectory(journal)) {
                return names;
            }
            try (java.util.stream.Stream<Path> files = Files.list(journal)) {
                files.map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(JsonJournal.COMMITTED_SUFFIX))
                        .forEach(names::add);
            }
            return names;
        }

        /** {@link com.ultikits.ultitools.manager.DataScope}'s constructor is package-private in another package. */
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        private com.ultikits.ultitools.manager.DataScope newDataScope(String pluginName, java.io.File dataFolder,
                Class<?>... owned) throws ReflectiveOperationException {
            java.lang.reflect.Constructor<com.ultikits.ultitools.manager.DataScope> ctor =
                    com.ultikits.ultitools.manager.DataScope.class.getDeclaredConstructor(
                            String.class, java.io.File.class, java.util.Set.class);
            ctor.setAccessible(true);
            return ctor.newInstance(pluginName, dataFolder, new java.util.HashSet<>(java.util.Arrays.asList(owned)));
        }
    }

    // ==================== Test Data Entities ====================
    
    @Table("test_table")
    public static class AnnotatedTestData extends BaseDataEntity<String> {
        private String name;

        public AnnotatedTestData() {}

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
    
    @Table("another_table")
    public static class AnotherAnnotatedTestData extends BaseDataEntity<String> {
        private String value;

        public AnotherAnnotatedTestData() {}

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
    
    // Entity without @Table annotation for testing error case
    public static class NonAnnotatedTestData extends BaseDataEntity<String> {
        private String data;

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }
}
