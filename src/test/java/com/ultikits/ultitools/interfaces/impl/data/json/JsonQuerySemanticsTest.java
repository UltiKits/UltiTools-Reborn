package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.EqualsAndHashCode;

/**
 * JSON 后端的查询语义测试。
 * <p>
 * 覆盖两条互相咬合的缺陷：
 * <ul>
 *   <li>issue #176 —— JSON 侧按 Java 字段名匹配，而所有调用方给的是 {@code @Column} 里的
 *       SQL 列名，查询恒零命中；</li>
 *   <li>issue #192 —— 多条件 AND 在首个条件零命中时退化成「采信后一个条件」，
 *       在 {@code del} 上是一条数据丢失路径。</li>
 * </ul>
 * 两者必须同时修：只修 #176 会把 #192 从「被零命中遮蔽」变成可触发。
 *
 * @author UltiKits Test Suite
 * @since 6.2.5
 */
@DisplayName("JSON 后端查询语义（#176 列名匹配 / #192 AND 交集）")
class JsonQuerySemanticsTest {

    @TempDir
    Path tempDir;

    private static DataSource dataSource;
    private SimpleJsonDataOperator<PlayerRecord> json;

    /**
     * 与 UltiLogin 的真实形状一致：SQL 列名是 snake_case，Java 字段名是 camelCase。
     * 这个差异正是 #176 的触发条件——列名与字段名相同的实体测不出该缺陷。
     */
    @EqualsAndHashCode(callSuper = true)
    @Table("player_record")
    public static class PlayerRecord extends BaseDataEntity<String> {
        @Column("player_uuid")
        private String playerUuid;

        @Column("player_name")
        private String playerName;

        @Column(value = "login_count", type = "INT")
        private int loginCount;

        public PlayerRecord() {
        }

        public PlayerRecord(String id, String playerUuid, String playerName, int loginCount) {
            this.setId(id);
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.loginCount = loginCount;
        }

        public String getPlayerUuid() {
            return playerUuid;
        }

        public void setPlayerUuid(String playerUuid) {
            this.playerUuid = playerUuid;
        }

        public String getPlayerName() {
            return playerName;
        }

        public void setPlayerName(String playerName) {
            this.playerName = playerName;
        }

        public int getLoginCount() {
            return loginCount;
        }

        public void setLoginCount(int loginCount) {
            this.loginCount = loginCount;
        }
    }

    @BeforeAll
    static void setUpClass() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:jsonquerysemantics;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // 刻意不调用 setPassword：内存库首次连接时就以「无口令」创建，
        // 传一个空字符串反而会被静态分析当成硬编码口令（Codacy/Opengrep 的
        // Semgrep_java_password_rule-HardcodePassword）。这里没有凭据可言。
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() {
        json = new SimpleJsonDataOperator<>(tempDir.toFile().getAbsolutePath(), PlayerRecord.class);
    }

    private static WhereCondition eq(String column, Object value) {
        return WhereCondition.builder().column(column).value(value).build();
    }

    private static List<String> idsOf(List<PlayerRecord> records) {
        return records.stream().map(PlayerRecord::getId).sorted().collect(Collectors.toList());
    }

    // ==================== #176 列名解析 ====================

    @Nested
    @DisplayName("#176 列名解析")
    class ColumnNameResolution {

        @BeforeEach
        void seed() {
            json.insert(new PlayerRecord("1", "uuid-aaa", "Alice", 3));
            json.insert(new PlayerRecord("2", "uuid-bbb", "Bob", 7));
        }

        @Test
        @DisplayName("按 @Column 的 SQL 列名查询应命中（修复前恒为空）")
        void shouldMatchBySqlColumnName() {
            List<PlayerRecord> found = json.getAll(eq("player_uuid", "uuid-aaa"));

            assertThat(found)
                    .as("调用方给的是 @Column(\"player_uuid\")，JSON 侧必须把它翻译成字段 playerUuid")
                    .hasSize(1);
            assertThat(found.get(0).getPlayerName()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("按 Java 字段名查询仍应命中（向后兼容，未知列名原样透传）")
        void shouldStillMatchByJavaFieldName() {
            assertThat(json.getAll(eq("playerUuid", "uuid-bbb")))
                    .as("旧调用直接写字段名，不能因为引入映射而失效")
                    .hasSize(1);
        }

        @Test
        @DisplayName("列名大小写不敏感，与关系型后端一致")
        void shouldResolveColumnCaseInsensitively() {
            assertThat(json.getAll(eq("PLAYER_UUID", "uuid-aaa"))).hasSize(1);
        }

        @Test
        @DisplayName("完全未知的列名返回空集而不是抛异常")
        void shouldReturnEmptyForUnknownColumn() {
            assertThat(json.getAll(eq("no_such_column", "whatever"))).isEmpty();
        }

        @Test
        @DisplayName("getLike 也应认 SQL 列名")
        void getLikeShouldResolveColumnName() {
            assertThat(json.getLike("player_name", "Ali", LikeType.START))
                    .as("getLike 与 getAll 走同一套列名域，不能只修一半")
                    .hasSize(1);
        }

        @Test
        @DisplayName("update(列名, 值, id) 应真正写入而不是静默丢弃")
        void updateByColumnShouldActuallyWrite() {
            json.update("player_name", "Alice2", "1");

            assertThat(json.getById("1").getPlayerName())
                    .as("列名不解析时 putByPath 会写进一个 Gson 反序列化时丢弃的新键，表现为静默丢写")
                    .isEqualTo("Alice2");
        }
    }

    // ==================== #176 跨后端一致性 ====================

    @Nested
    @DisplayName("#176 json 与 sqlite 两个后端结果集一致")
    class CrossBackendParity {

        private SQLiteDataOperator<PlayerRecord> sqlite;

        @BeforeEach
        void setUpBackends() throws Exception {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS player_record");
            }
            sqlite = new SQLiteDataOperator<>(dataSource, PlayerRecord.class);

            for (PlayerRecord record : new PlayerRecord[]{
                    new PlayerRecord("1", "uuid-aaa", "Alice", 3),
                    new PlayerRecord("2", "uuid-bbb", "Bob", 7),
                    new PlayerRecord("3", "uuid-ccc", "Alice", 7)}) {
                json.insert(record);
                sqlite.insert(record);
            }
        }

        @Test
        @DisplayName("单条件 where(@Column 名) 两个后端返回同一结果集")
        void singleConditionShouldAgree() {
            WhereCondition condition = eq("player_name", "Alice");

            assertThat(idsOf(json.getAll(condition)))
                    .as("同一个 @Column 列名，两个后端必须给出同一批行")
                    .isEqualTo(idsOf(sqlite.getAll(condition)))
                    .containsExactly("1", "3");
        }

        @Test
        @DisplayName("多条件 AND 两个后端返回同一结果集")
        void multiConditionShouldAgree() {
            WhereCondition[] conditions = {eq("player_name", "Alice"), eq("login_count", 7)};

            assertThat(idsOf(json.getAll(conditions)))
                    .isEqualTo(idsOf(sqlite.getAll(conditions)))
                    .containsExactly("3");
        }

        @Test
        @DisplayName("首个条件零命中时两个后端都返回空集")
        void firstConditionMissShouldAgree() {
            WhereCondition[] conditions = {eq("player_uuid", "uuid-none"), eq("login_count", 7)};

            assertThat(idsOf(json.getAll(conditions)))
                    .as("SQL 的 AND 在这里给空集，JSON 侧不能给出第二个条件的命中集")
                    .isEqualTo(idsOf(sqlite.getAll(conditions)))
                    .isEmpty();
        }
    }

    // ==================== #192 AND 交集 ====================

    @Nested
    @DisplayName("#192 多条件 AND 是交集")
    class AndIntersection {

        @BeforeEach
        void seed() {
            json.insert(new PlayerRecord("1", "uuid-aaa", "Alice", 7));
            json.insert(new PlayerRecord("2", "uuid-bbb", "Bob", 7));
            json.insert(new PlayerRecord("3", "uuid-ccc", "Carol", 9));
        }

        @Test
        @DisplayName("首个条件零命中 → getAll 返回空集")
        void firstConditionEmptyYieldsEmptyResult() {
            List<PlayerRecord> found = json.getAll(
                    eq("player_uuid", "uuid-does-not-exist"),
                    eq("login_count", 7));

            assertThat(found)
                    .as("修复前 results 为空会导致第二个条件的命中集被整体采纳，AND 退化成 OR")
                    .isEmpty();
        }

        @Test
        @DisplayName("首个条件零命中 → del 一行都不删")
        void firstConditionEmptyDeletesNothing() {
            json.del(
                    eq("player_uuid", "uuid-does-not-exist"),
                    eq("login_count", 7));

            assertThat(json.getAll())
                    .as("这是本 issue 的真实危害：修复前会删掉第二个条件命中的全部行")
                    .hasSize(3);
        }

        @Test
        @DisplayName("中间条件零命中 → 同样得到空集")
        void middleConditionEmptyYieldsEmptyResult() {
            assertThat(json.getAll(
                    eq("login_count", 7),
                    eq("player_name", "NoSuchPlayer"),
                    eq("player_uuid", "uuid-aaa")))
                    .isEmpty();
        }

        @Test
        @DisplayName("正常交集语义不受影响")
        void normalIntersectionStillWorks() {
            assertThat(idsOf(json.getAll(eq("login_count", 7), eq("player_name", "Bob"))))
                    .containsExactly("2");
        }

        @Test
        @DisplayName("del 的正常交集语义不受影响")
        void normalDeleteIntersectionStillWorks() {
            json.del(eq("login_count", 7), eq("player_name", "Bob"));

            assertThat(idsOf(json.getAll())).containsExactly("1", "3");
        }
    }

    // ==================== 空条件语义 ====================

    @Nested
    @DisplayName("空条件语义")
    class EmptyConditionSemantics {

        @BeforeEach
        void seed() {
            json.insert(new PlayerRecord("1", "uuid-aaa", "Alice", 3));
            json.insert(new PlayerRecord("2", "uuid-bbb", "Bob", 7));
        }

        @Test
        @DisplayName("getAll() 与 getAll(empty) 返回全量")
        void emptyConditionReturnsEverything() {
            assertThat(json.getAll()).hasSize(2);
            assertThat(json.getAll(WhereCondition.empty())).hasSize(2);
        }

        @Test
        @DisplayName("零个条件返回空集（page/exist 依赖此历史行为）")
        void zeroConditionsReturnEmpty() {
            assertThat(json.getAll(new WhereCondition[0])).isEmpty();
        }

        @Test
        @DisplayName("空条件与实条件混用时，空条件不施加约束")
        void emptyConditionDoesNotOverrideRealOne() {
            assertThat(idsOf(json.getAll(eq("player_name", "Bob"), WhereCondition.empty())))
                    .as("空条件排在后面时，原先会中途 return 全量，把真条件整个吃掉")
                    .containsExactly("2");
        }
    }
}
