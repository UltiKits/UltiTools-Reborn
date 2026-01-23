package com.ultikits.ultitools.interfaces.impl.data.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.EqualsAndHashCode;

/**
 * SQLiteDataStore 完整测试类
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SQLiteDataStore 测试")
class SQLiteDataStoreTest {

    @TempDir
    File tempDir;

    @Mock
    private UltiTools mockUltiTools;

    @Mock
    private UltiToolsPlugin mockPlugin;

    private MockedStatic<UltiTools> ultiToolsStaticMock;

    @EqualsAndHashCode(callSuper = true)
    @Table("test_data")
    public static class TestDataEntity extends AbstractDataEntity {
        @Column("name")
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @EqualsAndHashCode(callSuper = true)
    @Table("another_data")
    public static class AnotherDataEntity extends AbstractDataEntity {
        @Column("value")
        private int value;

        public AnotherDataEntity() {}

        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    public static class NoTableAnnotationEntity extends AbstractDataEntity {}

    @BeforeEach
    void setUp() {
        ultiToolsStaticMock = mockStatic(UltiTools.class);
        ultiToolsStaticMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        when(mockUltiTools.getDataFolder()).thenReturn(tempDir);
        
        // 清空静态缓存
        try {
            Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
            map.clear();
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        if (ultiToolsStaticMock != null) {
            ultiToolsStaticMock.close();
        }
    }

    private DataSource createH2DataSource() {
        HikariConfig config = new HikariConfig();
        // Use H2 in MySQL compatibility mode for backtick support
        config.setJdbcUrl("jdbc:h2:mem:sqlitestoretest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        config.setPassword("");
        return new HikariDataSource(config);
    }

    @Nested
    @DisplayName("getOperator 测试")
    class GetOperatorTests {

        @Test
        @DisplayName("应该成功获取带 @Table 注解的实体的 DataOperator")
        void shouldGetOperatorForAnnotatedEntity() {
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);
                
                // Act
                DataOperator<TestDataEntity> operator = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator).isNotNull();
                assertThat(operator).isSameAs(h2Operator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("应该抛出 RuntimeException 当实体没有 @Table 注解")
        void shouldThrowExceptionForEntityWithoutTableAnnotation() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act & Assert
            assertThatThrownBy(() -> store.getOperator(mockPlugin, NoTableAnnotationEntity.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No Table annotation is presented!");
        }

        @Test
        @DisplayName("应该返回缓存的 DataOperator 实例")
        void shouldReturnCachedOperator() {
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator);

                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<TestDataEntity> operator2 = store.getOperator(mockPlugin, TestDataEntity.class);

                // Assert
                assertThat(operator1).isSameAs(operator2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("应该为不同的实体类返回不同的 DataOperator")
        void shouldReturnDifferentOperatorsForDifferentEntities() {
            // Arrange
            DataSource h2DataSource = createH2DataSource();
            SQLiteDataStore store = new SQLiteDataStore();
            
            try {
                SQLiteDataOperator<TestDataEntity> h2Operator1 = new SQLiteDataOperator<>(h2DataSource, TestDataEntity.class);
                SQLiteDataOperator<AnotherDataEntity> h2Operator2 = new SQLiteDataOperator<>(h2DataSource, AnotherDataEntity.class);
                Field field = SQLiteDataStore.class.getDeclaredField("dataOperatorMap");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Class<?>, DataOperator<?>> map = (Map<Class<?>, DataOperator<?>>) field.get(null);
                map.put(TestDataEntity.class, h2Operator1);
                map.put(AnotherDataEntity.class, h2Operator2);

                // Act
                DataOperator<TestDataEntity> operator1 = store.getOperator(mockPlugin, TestDataEntity.class);
                DataOperator<AnotherDataEntity> operator2 = store.getOperator(mockPlugin, AnotherDataEntity.class);

                // Assert
                assertThat(operator1).isNotSameAs(operator2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("getStoreType 测试")
    class GetStoreTypeTests {

        @Test
        @DisplayName("应该返回 sqlite")
        void shouldReturnSqlite() {
            // Arrange
            SQLiteDataStore store = new SQLiteDataStore();

            // Act
            String storeType = store.getStoreType();

            // Assert
            assertThat(storeType).isEqualTo("sqlite");
        }
    }
}
