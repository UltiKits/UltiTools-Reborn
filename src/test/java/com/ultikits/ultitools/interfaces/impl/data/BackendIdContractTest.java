package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.EqualsAndHashCode;

/**
 * JSON 与关系型后端在 id 生成上的共同契约。
 * <p>
 * 这两个实现没有共同基类：{@link SimpleJsonDataOperator} 是独立写的，
 * {@link SQLiteDataOperator} 继承 {@code AbstractRelationalDataOperator}。
 * 于是关系型侧每加一条隐含行为，JSON 侧就多欠一笔，而模块作者是按关系型的
 * 行为写代码的——全部 Modules 加起来的 {@code setId} 调用是 0 次。issue #275
 * 就是这么来的：JSON 侧不补 id，null 进 ConcurrentHashMap 当 key，写入必 NPE。
 * <p>
 * 所以这个类刻意<b>不</b>测任何一个后端的私有行为，只测两边必须一致的那部分。
 * 新增一条跨后端约定时，断言应当加在这里而不是各自的测试类里——分开写就是
 * 当初漂移的成因。
 */
@DisplayName("后端间的 id 生成契约")
class BackendIdContractTest {

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    private SimpleJsonDataOperator<ContractEntity> json;
    private SQLiteDataOperator<ContractEntity> sqlite;

    @EqualsAndHashCode(callSuper = true)
    @Table("id_contract_entity")
    public static class ContractEntity extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public ContractEntity() {
        }

        public ContractEntity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @BeforeAll
    static void initBackends() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:idcontract;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // 刻意不调用 setPassword：内存库首次连接时就以「无口令」创建，
        // 传一个空字符串反而会被静态分析当成硬编码口令（Codacy/Opengrep 的
        // Semgrep_java_password_rule-HardcodePassword）。这里没有凭据可言。
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS id_contract_entity");
        }
        json = new SimpleJsonDataOperator<>(tempDir.toFile().getAbsolutePath(), ContractEntity.class);
        sqlite = new SQLiteDataOperator<>(dataSource, ContractEntity.class);
    }

    @Nested
    @DisplayName("insert")
    class InsertContract {

        @Test
        @DisplayName("未设 id 的实体，两个后端都要替它生成一个")
        void unsetIdShouldBeGeneratedByBothBackends() {
            ContractEntity forJson = new ContractEntity("json");
            ContractEntity forSqlite = new ContractEntity("sqlite");

            json.insert(forJson);
            sqlite.insert(forSqlite);

            assertThat(forJson.getId())
                    .as("JSON 后端未给实体生成 id —— 这正是 #275 的形状")
                    .isNotNull().isNotEmpty();
            assertThat(forSqlite.getId())
                    .as("关系型后端未给实体生成 id")
                    .isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("未设 id 插入之后，两个后端都要能把它取回来")
        void entityInsertedWithoutIdShouldBeRetrievableOnBothBackends() {
            ContractEntity forJson = new ContractEntity("json");
            ContractEntity forSqlite = new ContractEntity("sqlite");

            json.insert(forJson);
            sqlite.insert(forSqlite);

            // 取回用的是后端刚生成的那个 id：调用方除此之外没有别的句柄，
            // 所以「生成了 id」和「拿得回来」必须一起成立才算数。
            assertThat(json.getById(forJson.getId()))
                    .as("JSON 后端取不回刚插入的实体")
                    .isNotNull()
                    .extracting(ContractEntity::getName).isEqualTo("json");
            assertThat(sqlite.getById(forSqlite.getId()))
                    .as("关系型后端取不回刚插入的实体")
                    .isNotNull()
                    .extracting(ContractEntity::getName).isEqualTo("sqlite");
        }

        @Test
        @DisplayName("已显式设 id 的实体，两个后端都不许覆盖它")
        void explicitIdShouldSurviveOnBothBackends() {
            ContractEntity forJson = new ContractEntity("json");
            forJson.setId("explicit-json");
            ContractEntity forSqlite = new ContractEntity("sqlite");
            forSqlite.setId("explicit-sqlite");

            json.insert(forJson);
            sqlite.insert(forSqlite);

            assertThat(forJson.getId())
                    .as("JSON 后端覆盖了调用方设定的 id")
                    .isEqualTo("explicit-json");
            assertThat(sqlite.getById("explicit-sqlite"))
                    .as("关系型后端没按调用方设定的 id 存进去")
                    .isNotNull();
            assertThat(json.getById("explicit-json"))
                    .as("JSON 后端没按调用方设定的 id 存进去")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("insertAll")
    class InsertAllContract {

        @Test
        @DisplayName("批量插入未设 id 的实体，两个后端都要各给一个且互不相同")
        void unsetIdsShouldBeGeneratedAndDistinctOnBothBackends() {
            // 关系型侧的 insertAll 是自己重写的、又抄了一遍 id 生成；JSON 侧的
            // insertAll 委托给 insert。两条路径不同，所以要单独钉一次。
            List<ContractEntity> jsonBatch = Arrays.asList(
                    new ContractEntity("a"), new ContractEntity("b"), new ContractEntity("c"));
            List<ContractEntity> sqliteBatch = Arrays.asList(
                    new ContractEntity("a"), new ContractEntity("b"), new ContractEntity("c"));

            json.insertAll(jsonBatch);
            sqlite.insertAll(sqliteBatch);

            assertThat(jsonBatch).extracting(ContractEntity::getId)
                    .as("JSON 后端批量插入后有 id 为空的实体")
                    .doesNotContainNull().doesNotHaveDuplicates();
            assertThat(sqliteBatch).extracting(ContractEntity::getId)
                    .as("关系型后端批量插入后有 id 为空的实体")
                    .doesNotContainNull().doesNotHaveDuplicates();

            assertThat(json.getAll())
                    .as("JSON 后端批量插入的条数不对")
                    .hasSize(3);
            assertThat(sqlite.getAll())
                    .as("关系型后端批量插入的条数不对")
                    .hasSize(3);
        }
    }
}
