package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Tests for the {@link DataStore} interface.
 */
@DisplayName("DataStore Interface Tests")
class DataStoreTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(DataStore.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should have getStoreType method")
        void shouldHaveGetStoreTypeMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getStoreType");
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getStoreType should return String")
        void getStoreTypeShouldReturnString() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getStoreType");
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("Should have getOperator method")
        void shouldHaveGetOperatorMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getOperator", UltiToolsPlugin.class, Class.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getOperator should return DataOperator")
        void getOperatorShouldReturnDataOperator() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getOperator", UltiToolsPlugin.class, Class.class);
            assertThat(method.getReturnType()).isEqualTo(DataOperator.class);
        }

        @Test
        @DisplayName("getOperator should have generic type parameter")
        void getOperatorShouldHaveGenericTypeParameter() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getOperator", UltiToolsPlugin.class, Class.class);
            TypeVariable<?>[] typeParams = method.getTypeParameters();
            assertThat(typeParams).hasSize(1);
            assertThat(typeParams[0].getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("Should have destroyAllOperators method")
        void shouldHaveDestroyAllOperatorsMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("destroyAllOperators");
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("destroyAllOperators should return void")
        void destroyAllOperatorsShouldReturnVoid() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("destroyAllOperators");
            assertThat(method.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("Should have exactly 9 methods (3 abstract + 6 default)")
        void shouldHaveExactlyNineMethods() {
            // 02-01: added default getDataSource(DataScope) (D-01/D-17), alongside the existing
            // default getOperator(File, Class). 02-07 Task 2: added default
            // getOperator(DataScope, Class) (D-14/D-17), the fail-closed, ownership-checked entry
            // point. 02-12 Task 1: added the two checkOwnership(...) default methods extracting
            // the ownership check into one reusable member, called by both this interface's own
            // getOperator(File, Class) default body and, as their first statement, by every
            // concrete store's own getOperator overrides (D-14/D-18). 02-13 Task 1: added default
            // getOperatorUnchecked(DataScope, Class) (CR-01/CR-03) -- the internal, unchecked
            // construction path getOperator(DataScope, Class) now delegates to instead of the
            // public, previously ownership-exempt getOperator(File, Class) overload.
            long count = java.util.Arrays.stream(DataStore.class.getDeclaredMethods())
                    .filter(m -> !m.isSynthetic())
                    .count();
            assertThat(count).isEqualTo(9);
        }

        @Test
        @DisplayName("Should have checkOwnership(UltiToolsPlugin, Class) as a default method (D-14/D-18, 02-12)")
        void shouldHaveCheckOwnershipPluginMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("checkOwnership", UltiToolsPlugin.class, Class.class);
            assertThat(method).isNotNull();
            assertThat(method.isDefault()).isTrue();
        }

        @Test
        @DisplayName("Should have checkOwnership(File, Class) as a default method (D-14/D-18, 02-12)")
        void shouldHaveCheckOwnershipFileMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("checkOwnership", java.io.File.class, Class.class);
            assertThat(method).isNotNull();
            assertThat(method.isDefault()).isTrue();
        }

        @Test
        @DisplayName("Should have getDataSource(DataScope) as a default method")
        void shouldHaveGetDataSourceMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getDataSource",
                    com.ultikits.ultitools.manager.DataScope.class);
            assertThat(method).isNotNull();
            assertThat(method.isDefault()).isTrue();
        }

        @Test
        @DisplayName("Should have getOperator(DataScope, Class) as a default method (D-17)")
        void shouldHaveGetOperatorDataScopeMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getOperator",
                    com.ultikits.ultitools.manager.DataScope.class, Class.class);
            assertThat(method).isNotNull();
            assertThat(method.isDefault()).isTrue();
        }

        @Test
        @DisplayName("Should have getOperatorUnchecked(DataScope, Class) as a default method (CR-01/CR-03, 02-13)")
        void shouldHaveGetOperatorUncheckedMethod() throws NoSuchMethodException {
            Method method = DataStore.class.getMethod("getOperatorUnchecked",
                    com.ultikits.ultitools.manager.DataScope.class, Class.class);
            assertThat(method).isNotNull();
            assertThat(method.isDefault()).isTrue();
        }
    }

    @Nested
    @DisplayName("Implementation Tests")
    class ImplementationTests {

        @Test
        @DisplayName("Simple implementation should work")
        void simpleImplementationShouldWork() {
            DataStore dataStore = new DataStore() {
                @Override
                public String getStoreType() {
                    return "test";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                    // Empty implementation - test DataStore interface, not operator destruction
                }
            };

            assertThat(dataStore.getStoreType()).isEqualTo("test");
            assertThat(dataStore.getOperator((UltiToolsPlugin) null, null)).isNull();
        }

        @Test
        @DisplayName("Implementation with different store types")
        void implementationWithDifferentStoreTypes() {
            DataStore jsonStore = new DataStore() {
                @Override
                public String getStoreType() {
                    return "json";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            DataStore sqliteStore = new DataStore() {
                @Override
                public String getStoreType() {
                    return "sqlite";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            DataStore mysqlStore = new DataStore() {
                @Override
                public String getStoreType() {
                    return "mysql";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            assertThat(jsonStore.getStoreType()).isEqualTo("json");
            assertThat(sqliteStore.getStoreType()).isEqualTo("sqlite");
            assertThat(mysqlStore.getStoreType()).isEqualTo("mysql");
        }

        @Test
        @DisplayName("destroyAllOperators should be callable")
        void destroyAllOperatorsShouldBeCallable() {
            AtomicBoolean destroyed = new AtomicBoolean(false);

            DataStore dataStore = new DataStore() {
                @Override
                public String getStoreType() {
                    return "test";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                    destroyed.set(true);
                }
            };

            assertThat(destroyed.get()).isFalse();
            dataStore.destroyAllOperators();
            assertThat(destroyed.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("Operator Management Tests")
    class OperatorManagementTests {

        @Test
        @DisplayName("Should track created operators")
        void shouldTrackCreatedOperators() {
            class TrackingDataStore implements DataStore {
                private final Map<Class<?>, DataOperator<?>> operators = new HashMap<>();
                private boolean destroyed = false;

                @Override
                public String getStoreType() {
                    return "tracking";
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    DataOperator<T> operator = mock(DataOperator.class);
                    operators.put(dataEntity, operator);
                    return operator;
                }

                @Override
                public void destroyAllOperators() {
                    operators.clear();
                    destroyed = true;
                }

                public int getOperatorCount() {
                    return operators.size();
                }

                public boolean isDestroyed() {
                    return destroyed;
                }
            }

            TrackingDataStore store = new TrackingDataStore();
            assertThat(store.getOperatorCount()).isZero();

            // Simulate getting operators for different entity types
            // In real usage, these would be actual entity classes
            store.getOperator((UltiToolsPlugin) null, TestEntity1.class);
            assertThat(store.getOperatorCount()).isEqualTo(1);

            store.getOperator((UltiToolsPlugin) null, TestEntity2.class);
            assertThat(store.getOperatorCount()).isEqualTo(2);

            assertThat(store.isDestroyed()).isFalse();
            store.destroyAllOperators();
            assertThat(store.isDestroyed()).isTrue();
            assertThat(store.getOperatorCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Store Type Identification Tests")
    class StoreTypeIdentificationTests {

        @Test
        @DisplayName("Store type should be consistent")
        void storeTypeShouldBeConsistent() {
            DataStore store = new DataStore() {
                @Override
                public String getStoreType() {
                    return "consistent";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            String type1 = store.getStoreType();
            String type2 = store.getStoreType();
            String type3 = store.getStoreType();

            assertThat(type1).isEqualTo(type2).isEqualTo(type3);
        }

        @Test
        @DisplayName("Store type should not be empty")
        void storeTypeShouldNotBeEmpty() {
            DataStore store = new DataStore() {
                @Override
                public String getStoreType() {
                    return "valid_type";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            assertThat(store.getStoreType())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    /**
     * Task 1 (02-12): the extracted {@code checkOwnership} default methods, exercised directly on
     * a bare stub {@code DataStore} that implements only the three abstract methods -- proving the
     * refusal is inherited from the interface itself, not from any one store's own code. Before
     * this task, {@code getOperator(UltiToolsPlugin, Class)} carried no check at all (D-14/D-18's
     * disclosed gap); {@code checkOwnership(UltiToolsPlugin, Class)} did not exist to call.
     */
    @Nested
    @DisplayName("checkOwnership default methods (D-14/D-18, 02-12 Task 1)")
    class CheckOwnershipTests {

        private DataStore stub() {
            return new DataStore() {
                @Override
                public String getStoreType() {
                    return "checkownership-stub";
                }

                @Override
                public <T extends BaseDataEntity<String>> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };
        }

        class UnownedEntity extends BaseDataEntity<String> {
        }

        @Test
        @DisplayName("checkOwnership(UltiToolsPlugin, Class) should refuse an entity the plugin does not own")
        void checkOwnershipPluginShouldRefuseUnownedEntity() {
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("RequestingPlugin");
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.findOwningPlugin(UnownedEntity.class)).thenReturn("OwningPlugin");
            UltiTools ultiTools = mock(UltiTools.class);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);

                assertThatThrownBy(() -> stub().checkOwnership(plugin, UnownedEntity.class))
                        .isInstanceOf(DataAccessException.class)
                        .extracting(e -> ((DataAccessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);
            }
        }

        @Test
        @DisplayName("checkOwnership(UltiToolsPlugin, Class) refusal message names both the entity and the offending module")
        void checkOwnershipPluginRefusalMessageNamesEntityAndModule() {
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("RequestingPlugin");
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.findOwningPlugin(UnownedEntity.class)).thenReturn("OwningPlugin");
            UltiTools ultiTools = mock(UltiTools.class);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);

                assertThatThrownBy(() -> stub().checkOwnership(plugin, UnownedEntity.class))
                        .hasMessageContaining(UnownedEntity.class.getName())
                        .hasMessageContaining("OwningPlugin");
            }
        }

        @Test
        @DisplayName("checkOwnership(UltiToolsPlugin, Class) should not refuse an entity the plugin owns")
        void checkOwnershipPluginShouldNotRefuseOwnedEntity() {
            UltiToolsPlugin plugin = mock(UltiToolsPlugin.class);
            when(plugin.getPluginName()).thenReturn("OwningPlugin");
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.findOwningPlugin(UnownedEntity.class)).thenReturn("OwningPlugin");
            UltiTools ultiTools = mock(UltiTools.class);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);

                assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> stub().checkOwnership(plugin, UnownedEntity.class))).isNull();
            }
        }

        @Test
        @DisplayName("checkOwnership(File, Class) should no longer exempt the framework's own core data folder (CR-01, 02-13)")
        void checkOwnershipFileShouldRefuseFrameworkCoreFolder() {
            // CR-01 (02-REVIEW.md): the old isFrameworkCoreFolder exemption was keyed purely on a
            // value any caller can obtain (UltiTools#getDataFolder() is public in the published
            // jar), not on any credential -- so any code sharing the JVM could reach it. 02-13
            // deletes the exemption. With no PluginManager registered, the reverse lookup finds no
            // scope for the core folder either, so this must refuse exactly like any other
            // unregistered folder.
            File coreFolder = new File(System.getProperty("java.io.tmpdir"), "ultitools-test-core-folder");
            UltiTools ultiTools = mock(UltiTools.class);
            when(ultiTools.getDataFolder()).thenReturn(coreFolder);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);

                assertThatThrownBy(() -> stub().checkOwnership(coreFolder, UnownedEntity.class))
                        .isInstanceOf(DataAccessException.class)
                        .extracting(e -> ((DataAccessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ENTITY_NOT_OWNED);
            }
        }

        @Test
        @DisplayName("checkOwnership(File, Class) should still refuse an unregistered external folder with the 02-07 message")
        void checkOwnershipFileShouldRefuseUnregisteredFolder() {
            File unregisteredFolder = new File(System.getProperty("java.io.tmpdir"), "ultitools-test-unregistered-folder");
            File coreFolder = new File(System.getProperty("java.io.tmpdir"), "ultitools-test-core-folder-other");
            PluginManager pluginManager = mock(PluginManager.class);
            when(pluginManager.findScopeForDataFolder(unregisteredFolder)).thenReturn(null);
            UltiTools ultiTools = mock(UltiTools.class);
            when(ultiTools.getDataFolder()).thenReturn(coreFolder);
            when(ultiTools.getPluginManager()).thenReturn(pluginManager);

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(ultiTools);

                assertThatThrownBy(() -> stub().checkOwnership(unregisteredFolder, UnownedEntity.class))
                        .isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("UltiToolsAPI.connect");
            }
        }
    }

    // Test entity classes for testing generic methods
    static class TestEntity1 extends BaseDataEntity<String> {
    }

    static class TestEntity2 extends BaseDataEntity<String> {
    }
}
