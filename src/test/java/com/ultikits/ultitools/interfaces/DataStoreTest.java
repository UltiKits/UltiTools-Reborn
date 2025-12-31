package com.ultikits.ultitools.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

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
        @DisplayName("Should have exactly 3 methods")
        void shouldHaveExactlyThreeMethods() {
            Method[] methods = DataStore.class.getDeclaredMethods();
            assertThat(methods).hasSize(3);
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
                    return null;
                }

                @Override
                public void destroyAllOperators() {
                }
            };

            assertThat(dataStore.getStoreType()).isEqualTo("test");
            assertThat(dataStore.getOperator(null, null)).isNull();
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
            store.getOperator(null, TestEntity1.class);
            assertThat(store.getOperatorCount()).isEqualTo(1);

            store.getOperator(null, TestEntity2.class);
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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
                public <T extends AbstractDataEntity> DataOperator<T> getOperator(UltiToolsPlugin plugin, Class<T> dataEntity) {
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

    // Test entity classes for testing generic methods
    static class TestEntity1 extends AbstractDataEntity {
    }

    static class TestEntity2 extends AbstractDataEntity {
    }
}
