package com.ultikits.ultitools.interfaces.impl.data.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MysqlConfig 完整测试类
 * 覆盖所有构造函数分支和 getter 方法
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MysqlConfig 测试")
class MysqlConfigTest {

    @Mock
    private FileConfiguration mockConfig;

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("应该正确从配置中读取所有 MySQL 配置项")
        void shouldReadAllConfigValuesFromFileConfiguration() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("localhost");
            when(mockConfig.getInt("mysql.port")).thenReturn(3306);
            when(mockConfig.getString("mysql.username")).thenReturn("root");
            when(mockConfig.getString("mysql.password")).thenReturn("password123");
            when(mockConfig.getString("mysql.database")).thenReturn("ultitools_db");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.isEnable()).isTrue();
            assertThat(mysqlConfig.getHost()).isEqualTo("localhost");
            assertThat(mysqlConfig.getPort()).isEqualTo(3306);
            assertThat(mysqlConfig.getUsername()).isEqualTo("root");
            assertThat(mysqlConfig.getPassword()).isEqualTo("password123");
            assertThat(mysqlConfig.getDatabase()).isEqualTo("ultitools_db");
            assertThat(mysqlConfig.getConnectionTimeout()).isEqualTo(30000);
            assertThat(mysqlConfig.getKeepaliveTime()).isEqualTo(60000);
            assertThat(mysqlConfig.getMaxLifetime()).isEqualTo(1800000);
            assertThat(mysqlConfig.getConnectionTestQuery()).isEqualTo("SELECT 1");
            assertThat(mysqlConfig.getMaximumPoolSize()).isEqualTo(10);
            assertThat(mysqlConfig.isCachePrepStmts()).isTrue();
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isEqualTo(250);
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isEqualTo(2048);
        }

        @Test
        @DisplayName("应该正确处理禁用状态的配置")
        void shouldHandleDisabledMysqlConfig() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(false);
            when(mockConfig.getString("mysql.host")).thenReturn("127.0.0.1");
            when(mockConfig.getInt("mysql.port")).thenReturn(3307);
            when(mockConfig.getString("mysql.username")).thenReturn("admin");
            when(mockConfig.getString("mysql.password")).thenReturn("secret");
            when(mockConfig.getString("mysql.database")).thenReturn("test_db");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(5000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(30000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(600000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1 FROM DUAL");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(5);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(false);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(100);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(1024);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.isEnable()).isFalse();
            assertThat(mysqlConfig.getHost()).isEqualTo("127.0.0.1");
            assertThat(mysqlConfig.getPort()).isEqualTo(3307);
            assertThat(mysqlConfig.getUsername()).isEqualTo("admin");
            assertThat(mysqlConfig.getPassword()).isEqualTo("secret");
            assertThat(mysqlConfig.getDatabase()).isEqualTo("test_db");
            assertThat(mysqlConfig.getConnectionTimeout()).isEqualTo(5000);
            assertThat(mysqlConfig.getKeepaliveTime()).isEqualTo(30000);
            assertThat(mysqlConfig.getMaxLifetime()).isEqualTo(600000);
            assertThat(mysqlConfig.getConnectionTestQuery()).isEqualTo("SELECT 1 FROM DUAL");
            assertThat(mysqlConfig.getMaximumPoolSize()).isEqualTo(5);
            assertThat(mysqlConfig.isCachePrepStmts()).isFalse();
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isEqualTo(100);
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isEqualTo(1024);
        }

        @Test
        @DisplayName("应该正确处理空字符串配置值")
        void shouldHandleEmptyStringValues() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("");
            when(mockConfig.getInt("mysql.port")).thenReturn(0);
            when(mockConfig.getString("mysql.username")).thenReturn("");
            when(mockConfig.getString("mysql.password")).thenReturn("");
            when(mockConfig.getString("mysql.database")).thenReturn("");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(0);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(0);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(0);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(0);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(false);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(0);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(0);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.getHost()).isEmpty();
            assertThat(mysqlConfig.getPort()).isZero();
            assertThat(mysqlConfig.getUsername()).isEmpty();
            assertThat(mysqlConfig.getPassword()).isEmpty();
            assertThat(mysqlConfig.getDatabase()).isEmpty();
            assertThat(mysqlConfig.getConnectionTimeout()).isZero();
            assertThat(mysqlConfig.getKeepaliveTime()).isZero();
            assertThat(mysqlConfig.getMaxLifetime()).isZero();
            assertThat(mysqlConfig.getConnectionTestQuery()).isEmpty();
            assertThat(mysqlConfig.getMaximumPoolSize()).isZero();
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isZero();
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isZero();
        }

        @Test
        @DisplayName("应该正确处理 null 字符串配置值")
        void shouldHandleNullStringValues() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(false);
            when(mockConfig.getString("mysql.host")).thenReturn(null);
            when(mockConfig.getInt("mysql.port")).thenReturn(3306);
            when(mockConfig.getString("mysql.username")).thenReturn(null);
            when(mockConfig.getString("mysql.password")).thenReturn(null);
            when(mockConfig.getString("mysql.database")).thenReturn(null);
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn(null);
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.getHost()).isNull();
            assertThat(mysqlConfig.getUsername()).isNull();
            assertThat(mysqlConfig.getPassword()).isNull();
            assertThat(mysqlConfig.getDatabase()).isNull();
            assertThat(mysqlConfig.getConnectionTestQuery()).isNull();
        }

        @Test
        @DisplayName("应该正确处理特殊字符的主机名和密码")
        void shouldHandleSpecialCharactersInHostAndPassword() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("db.example.com");
            when(mockConfig.getInt("mysql.port")).thenReturn(3306);
            when(mockConfig.getString("mysql.username")).thenReturn("user@domain");
            when(mockConfig.getString("mysql.password")).thenReturn("p@ss!w0rd#123$%");
            when(mockConfig.getString("mysql.database")).thenReturn("ultitools-prod_2024");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(10000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(45000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(900000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("/* ping */ SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(20);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(500);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(4096);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.getHost()).isEqualTo("db.example.com");
            assertThat(mysqlConfig.getUsername()).isEqualTo("user@domain");
            assertThat(mysqlConfig.getPassword()).isEqualTo("p@ss!w0rd#123$%");
            assertThat(mysqlConfig.getDatabase()).isEqualTo("ultitools-prod_2024");
            assertThat(mysqlConfig.getConnectionTestQuery()).isEqualTo("/* ping */ SELECT 1");
        }

        @Test
        @DisplayName("应该正确处理负数配置值")
        void shouldHandleNegativeIntValues() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("localhost");
            when(mockConfig.getInt("mysql.port")).thenReturn(-1);
            when(mockConfig.getString("mysql.username")).thenReturn("root");
            when(mockConfig.getString("mysql.password")).thenReturn("pass");
            when(mockConfig.getString("mysql.database")).thenReturn("db");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(-1000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(-500);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(-100);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(-5);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(false);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(-10);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(-20);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.getPort()).isEqualTo(-1);
            assertThat(mysqlConfig.getConnectionTimeout()).isEqualTo(-1000);
            assertThat(mysqlConfig.getKeepaliveTime()).isEqualTo(-500);
            assertThat(mysqlConfig.getMaxLifetime()).isEqualTo(-100);
            assertThat(mysqlConfig.getMaximumPoolSize()).isEqualTo(-5);
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isEqualTo(-10);
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isEqualTo(-20);
        }

        @Test
        @DisplayName("应该正确处理大整数配置值")
        void shouldHandleLargeIntValues() {
            // Arrange
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("localhost");
            when(mockConfig.getInt("mysql.port")).thenReturn(65535);
            when(mockConfig.getString("mysql.username")).thenReturn("root");
            when(mockConfig.getString("mysql.password")).thenReturn("pass");
            when(mockConfig.getString("mysql.database")).thenReturn("db");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(Integer.MAX_VALUE);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(Integer.MAX_VALUE);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(Integer.MAX_VALUE);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(1000);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(Integer.MAX_VALUE);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(Integer.MAX_VALUE);

            // Act
            MysqlConfig mysqlConfig = new MysqlConfig(mockConfig);

            // Assert
            assertThat(mysqlConfig.getPort()).isEqualTo(65535);
            assertThat(mysqlConfig.getConnectionTimeout()).isEqualTo(Integer.MAX_VALUE);
            assertThat(mysqlConfig.getKeepaliveTime()).isEqualTo(Integer.MAX_VALUE);
            assertThat(mysqlConfig.getMaxLifetime()).isEqualTo(Integer.MAX_VALUE);
            assertThat(mysqlConfig.getMaximumPoolSize()).isEqualTo(1000);
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isEqualTo(Integer.MAX_VALUE);
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("Getter 方法测试")
    class GetterTests {

        private MysqlConfig mysqlConfig;

        @BeforeEach
        void setUp() {
            when(mockConfig.getBoolean("mysql.enable")).thenReturn(true);
            when(mockConfig.getString("mysql.host")).thenReturn("test-host");
            when(mockConfig.getInt("mysql.port")).thenReturn(3306);
            when(mockConfig.getString("mysql.username")).thenReturn("test-user");
            when(mockConfig.getString("mysql.password")).thenReturn("test-pass");
            when(mockConfig.getString("mysql.database")).thenReturn("test-db");
            when(mockConfig.getInt("mysql.connectionTimeout")).thenReturn(30000);
            when(mockConfig.getInt("mysql.keepaliveTime")).thenReturn(60000);
            when(mockConfig.getInt("mysql.maxLifetime")).thenReturn(1800000);
            when(mockConfig.getString("mysql.connectionTestQuery")).thenReturn("SELECT 1");
            when(mockConfig.getInt("mysql.maximumPoolSize")).thenReturn(10);
            when(mockConfig.getBoolean("mysql.cachePrepStmts")).thenReturn(true);
            when(mockConfig.getInt("mysql.prepStmtCacheSize")).thenReturn(250);
            when(mockConfig.getInt("mysql.prepStmtCacheSqlLimit")).thenReturn(2048);
            
            mysqlConfig = new MysqlConfig(mockConfig);
        }

        @Test
        @DisplayName("isEnable 应该返回正确的值")
        void isEnableShouldReturnCorrectValue() {
            assertThat(mysqlConfig.isEnable()).isTrue();
        }

        @Test
        @DisplayName("getHost 应该返回正确的值")
        void getHostShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getHost()).isEqualTo("test-host");
        }

        @Test
        @DisplayName("getPort 应该返回正确的值")
        void getPortShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getPort()).isEqualTo(3306);
        }

        @Test
        @DisplayName("getUsername 应该返回正确的值")
        void getUsernameShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getUsername()).isEqualTo("test-user");
        }

        @Test
        @DisplayName("getPassword 应该返回正确的值")
        void getPasswordShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getPassword()).isEqualTo("test-pass");
        }

        @Test
        @DisplayName("getDatabase 应该返回正确的值")
        void getDatabaseShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getDatabase()).isEqualTo("test-db");
        }

        @Test
        @DisplayName("getConnectionTimeout 应该返回正确的值")
        void getConnectionTimeoutShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getConnectionTimeout()).isEqualTo(30000);
        }

        @Test
        @DisplayName("getKeepaliveTime 应该返回正确的值")
        void getKeepaliveTimeShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getKeepaliveTime()).isEqualTo(60000);
        }

        @Test
        @DisplayName("getMaxLifetime 应该返回正确的值")
        void getMaxLifetimeShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getMaxLifetime()).isEqualTo(1800000);
        }

        @Test
        @DisplayName("getConnectionTestQuery 应该返回正确的值")
        void getConnectionTestQueryShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getConnectionTestQuery()).isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("getMaximumPoolSize 应该返回正确的值")
        void getMaximumPoolSizeShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getMaximumPoolSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("isCachePrepStmts 应该返回正确的值")
        void isCachePrepStmtsShouldReturnCorrectValue() {
            assertThat(mysqlConfig.isCachePrepStmts()).isTrue();
        }

        @Test
        @DisplayName("getPrepStmtCacheSize 应该返回正确的值")
        void getPrepStmtCacheSizeShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getPrepStmtCacheSize()).isEqualTo(250);
        }

        @Test
        @DisplayName("getPrepStmtCacheSqlLimit 应该返回正确的值")
        void getPrepStmtCacheSqlLimitShouldReturnCorrectValue() {
            assertThat(mysqlConfig.getPrepStmtCacheSqlLimit()).isEqualTo(2048);
        }
    }
}
