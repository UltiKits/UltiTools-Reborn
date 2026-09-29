package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Rows left with a NULL id by UltiTools-API 6.2.0 on SQLite (#546; maintainer decision
 * 2026-09-27): table initialisation backfills them with a UUID, writing only the id column and
 * logging one line per table, and update/delete by a null id throw instead of matching nothing.
 * <p>
 * H2 stands in for SQLite as elsewhere in this package. H2 refuses a NULL in a PRIMARY KEY column,
 * so the legacy table is created here without one; SQLite's generated DDL
 * ({@code PRIMARY KEY (`id`)} with no {@code NOT NULL}) accepted NULL, which is how the rows
 * exist. The backfill keys rows by {@code _rowid_}, which both engines provide.
 */
@DisplayName("NULL-id rows: backfill at table init, refuse null-id update/delete (#546)")
class NullIdRowsTest {

    private static DataSource dataSource;
    private static final Logger OPERATOR_LOGGER = Logger.getLogger(AbstractRelationalDataOperator.class.getName());

    @TempDir
    Path tempDir;

    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @Table("null_id_entity")
    public static class LegacyEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        @Column(value = "score", type = "INT")
        private int score;

        public LegacyEntity() {
        }

        public LegacyEntity(String name, int score) {
            this.name = name;
            this.score = score;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @BeforeAll
    static void initDataSource() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:nullidrows;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // No setPassword: the in-memory database is created without one on first connect.
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        execute("DROP TABLE IF EXISTS null_id_entity");
        OPERATOR_LOGGER.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        OPERATOR_LOGGER.removeHandler(capture);
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Every caller passes a string literal from this class; nothing reaches it from input.
            // nosemgrep: java_inject_rule-SqlInjection
            stmt.execute(sql);
        }
    }

    /** The table as 6.2.0 left it: three NULL-id rows and one row that has an id. */
    private static void createLegacyTable() throws Exception {
        execute("CREATE TABLE null_id_entity (`id` VARCHAR(255), `name` VARCHAR(255), `score` INT)");
        execute("INSERT INTO null_id_entity (`id`, `name`, `score`) VALUES "
                + "(NULL, 'world', 1), (NULL, 'world_nether', 2), (NULL, 'world_the_end', 3), ('kept-id', 'kept', 4)");
    }

    /** Every row as "rowid|id|name|score", ordered by rowid. */
    private static List<String> rows() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT _rowid_, `id`, `name`, `score` FROM null_id_entity ORDER BY _rowid_")) {
            while (rs.next()) {
                rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getInt(4));
            }
        }
        return rows;
    }

    private static List<String> withoutIds(List<String> rows) {
        List<String> stripped = new ArrayList<>();
        for (String row : rows) {
            String[] parts = row.split("\\|", -1);
            stripped.add(parts[0] + "|" + parts[2] + "|" + parts[3]);
        }
        return stripped;
    }

    private List<String> backfillLinesFor(String table) {
        List<String> lines = new ArrayList<>();
        for (LogRecord logRecord : logged) {
            if (logRecord.getMessage() != null && logRecord.getMessage().contains("'" + table + "'")) {
                lines.add(logRecord.getMessage());
            }
        }
        return lines;
    }

    private List<String> warningsFor(String table) {
        List<String> lines = new ArrayList<>();
        for (LogRecord logRecord : logged) {
            if (Level.WARNING.equals(logRecord.getLevel()) && logRecord.getMessage() != null
                    && logRecord.getMessage().contains("'" + table + "'")) {
                lines.add(logRecord.getMessage());
            }
        }
        return lines;
    }

    private List<String> backfillLines() {
        List<String> lines = new ArrayList<>();
        for (LogRecord logRecord : logged) {
            if (logRecord.getMessage() != null && logRecord.getMessage().contains("null_id_entity")) {
                lines.add(logRecord.getMessage());
            }
        }
        return lines;
    }

    @Nested
    @DisplayName("backfill at table initialisation")
    class Backfill {

        @Test
        @DisplayName("every NULL-id row gets a distinct UUID and nothing else in the row changes")
        void nullIdRowsGetDistinctIdsAndOtherColumnsStayIdentical() throws Exception {
            createLegacyTable();
            List<String> before = rows();

            new SQLiteDataOperator<>(dataSource, LegacyEntity.class);

            List<String> after = rows();
            assertThat(withoutIds(after)).as("a column other than id changed").isEqualTo(withoutIds(before));
            List<String> ids = new ArrayList<>();
            for (String row : after) {
                ids.add(row.split("\\|", -1)[1]);
            }
            assertThat(ids).doesNotContain("null").doesNotHaveDuplicates().contains("kept-id");
            for (String id : ids) {
                if (!"kept-id".equals(id)) {
                    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                }
            }
        }

        @Test
        @DisplayName("one log line names the table and the count; a second start writes and logs nothing")
        void logsOncePerTableAndIsIdempotent() throws Exception {
            createLegacyTable();

            new SQLiteDataOperator<>(dataSource, LegacyEntity.class);
            List<String> firstLines = backfillLines();
            List<String> afterFirst = rows();

            logged.clear();
            new SQLiteDataOperator<>(dataSource, LegacyEntity.class);

            assertThat(firstLines).hasSize(1);
            assertThat(firstLines.get(0)).contains("null_id_entity").contains("3");
            assertThat(backfillLines()).as("a second start with no NULL ids logged a line").isEmpty();
            assertThat(rows()).as("a second start rewrote ids").isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("a backfilled row can be updated by its new id, and the change survives a new operator")
        void backfilledRowIsUpdatable() throws Exception {
            createLegacyTable();
            SQLiteDataOperator<LegacyEntity> operator = new SQLiteDataOperator<>(dataSource, LegacyEntity.class);

            LegacyEntity world = operator.query().where("name").eq("world").first();
            world.setName("world-renamed");
            operator.update(world);

            SQLiteDataOperator<LegacyEntity> restarted = new SQLiteDataOperator<>(dataSource, LegacyEntity.class);
            assertThat(restarted.getById(world.getId()).getName()).isEqualTo("world-renamed");
        }

        @Test
        @DisplayName("MySQL cannot hold a NULL id, so its operator runs no backfill")
        void mysqlOperatorRunsNoBackfill() throws Exception {
            createLegacyTable();

            new MysqlDataOperator<>(dataSource, LegacyEntity.class);

            assertThat(rows()).filteredOn(row -> row.contains("|null|")).hasSize(3);
            assertThat(backfillLines()).isEmpty();
        }
    }

    /**
     * An entity whose {@code getId()} is derived from another column, the shape UltiEssentials'
     * {@code UuidKeyedDataEntity} and UltiKits' {@code KitClaimData} use: the inherited
     * {@code id} field is never set, and every {@code WHERE id = ?} binds {@code getId()}.
     */
    @Table("derived_id_entity")
    public static class DerivedIdEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("uuid")
        private String uuid;

        @Column("name")
        private String name;

        public DerivedIdEntity() {
        }

        public DerivedIdEntity(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override
        public String getId() {
            return uuid;
        }

        @Override
        public void setId(String id) {
            this.uuid = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static List<String> derivedRows() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT `id`, `uuid`, `name` FROM derived_id_entity ORDER BY _rowid_")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
        }
        return rows;
    }

    @Nested
    @DisplayName("entities whose getId() is derived from another column")
    class DerivedId {

        @BeforeEach
        void dropDerivedTable() throws Exception {
            execute("DROP TABLE IF EXISTS derived_id_entity");
        }

        @Test
        @DisplayName("insert writes getId() into the id column, so update and delete by it reach the row")
        void insertWritesTheReportedId() throws Exception {
            SQLiteDataOperator<DerivedIdEntity> operator = new SQLiteDataOperator<>(dataSource, DerivedIdEntity.class);
            DerivedIdEntity entity = new DerivedIdEntity("11111111-1111-1111-1111-111111111111", "first");
            operator.insert(entity);

            assertThat(derivedRows()).containsExactly("11111111-1111-1111-1111-111111111111|11111111-1111-1111-1111-111111111111|first");

            entity.setName("renamed");
            operator.update(entity);
            assertThat(operator.getById(entity.getId()).getName()).isEqualTo("renamed");

            logged.clear();
            new SQLiteDataOperator<>(dataSource, DerivedIdEntity.class);
            assertThat(backfillLinesFor("derived_id_entity")).as("a row inserted on 6.3.0 had no id").isEmpty();

            operator.delById(entity.getId());
            assertThat(derivedRows()).isEmpty();
        }

        @Test
        @DisplayName("insertAll and updateAll write getId() into the id column too")
        void batchPathsWriteTheReportedId() throws Exception {
            SQLiteDataOperator<DerivedIdEntity> operator = new SQLiteDataOperator<>(dataSource, DerivedIdEntity.class);
            DerivedIdEntity a = new DerivedIdEntity("22222222-2222-2222-2222-222222222222", "a");
            DerivedIdEntity b = new DerivedIdEntity("33333333-3333-3333-3333-333333333333", "b");
            operator.insertAll(Arrays.asList(a, b));
            a.setName("a2");
            b.setName("b2");
            operator.updateAll(Arrays.asList(a, b));

            assertThat(derivedRows()).containsExactly(
                    "22222222-2222-2222-2222-222222222222|22222222-2222-2222-2222-222222222222|a2",
                    "33333333-3333-3333-3333-333333333333|33333333-3333-3333-3333-333333333333|b2");
        }

        @Test
        @DisplayName("the backfill writes the id the entity reports; a row it cannot make addressable is left and warned about")
        void backfillWritesTheReportedId() throws Exception {
            execute("CREATE TABLE derived_id_entity (`id` VARCHAR(255), `uuid` VARCHAR(255), `name` VARCHAR(255))");
            execute("INSERT INTO derived_id_entity (`id`, `uuid`, `name`) VALUES "
                    + "(NULL, '44444444-4444-4444-4444-444444444444', 'home'), "
                    + "(NULL, NULL, 'no-uuid'), "
                    + "(NULL, '44444444-4444-4444-4444-444444444444', 'duplicate')");

            SQLiteDataOperator<DerivedIdEntity> operator = new SQLiteDataOperator<>(dataSource, DerivedIdEntity.class);

            // A random id in the id column would not change what getId() reports (null, or the
            // other row's uuid), so it would not make either row reachable -- and for the
            // duplicate, update(entity)/delById(entity.getId()) would still address "home".
            assertThat(derivedRows()).containsExactly(
                    "44444444-4444-4444-4444-444444444444|44444444-4444-4444-4444-444444444444|home",
                    "null|null|no-uuid",
                    "null|44444444-4444-4444-4444-444444444444|duplicate");
            assertThat(backfillLinesFor("derived_id_entity"))
                    .as("one INFO line for the repaired row and one WARNING for the two left")
                    .hasSize(2);
            assertThat(warningsFor("derived_id_entity")).hasSize(1);
            assertThat(warningsFor("derived_id_entity").get(0)).contains("2");

            DerivedIdEntity home = operator.getById("44444444-4444-4444-4444-444444444444");
            home.setName("home-renamed");
            operator.update(home);
            assertThat(operator.getById("44444444-4444-4444-4444-444444444444").getName())
                    .as("the backfilled row is not reachable by the id its entity reports")
                    .isEqualTo("home-renamed");
        }

        @Test
        @DisplayName("a row that cannot be read as the entity is left without an id, and the others are repaired")
        void unreadableRowIsLeft() throws Exception {
            execute("DROP TABLE IF EXISTS null_id_entity");
            execute("CREATE TABLE null_id_entity (`id` VARCHAR(255), `name` VARCHAR(255), `score` VARCHAR(255))");
            execute("INSERT INTO null_id_entity (`id`, `name`, `score`) VALUES (NULL, 'readable', '1'), (NULL, 'unreadable', 'not-a-number')");

            new SQLiteDataOperator<>(dataSource, LegacyEntity.class);

            List<String> ids = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT `name`, `id` FROM null_id_entity ORDER BY _rowid_")) {
                while (rs.next()) {
                    ids.add(rs.getString(1) + "|" + (rs.getString(2) == null ? "null" : "set"));
                }
            }
            assertThat(ids).containsExactly("readable|set", "unreadable|null");
            assertThat(warningsFor("null_id_entity")).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("update and delete by a null id throw")
    class RefuseNullId {

        private List<DataOperator<LegacyEntity>> operators() {
            return Arrays.asList(
                    new SQLiteDataOperator<>(dataSource, LegacyEntity.class),
                    new SimpleJsonDataOperator<>(tempDir.toFile().getAbsolutePath(), LegacyEntity.class));
        }

        @Test
        @DisplayName("update(T) with a null id")
        void updateEntity() {
            for (DataOperator<LegacyEntity> operator : operators()) {
                assertThatThrownBy(() -> operator.update(new LegacyEntity("no-id", 1)))
                        .as(operator.getClass().getSimpleName())
                        .isInstanceOf(DataAccessException.class);
            }
        }

        @Test
        @DisplayName("update(column, value, id) with a null id")
        void updateColumn() {
            for (DataOperator<LegacyEntity> operator : operators()) {
                assertThatThrownBy(() -> operator.update("name", "x", null))
                        .as(operator.getClass().getSimpleName())
                        .isInstanceOf(DataAccessException.class);
            }
        }

        @Test
        @DisplayName("delById with a null id")
        void deleteById() {
            for (DataOperator<LegacyEntity> operator : operators()) {
                assertThatThrownBy(() -> operator.delById(null))
                        .as(operator.getClass().getSimpleName())
                        .isInstanceOf(DataAccessException.class);
            }
        }

        @Test
        @DisplayName("updateAll with one null-id entity writes nothing")
        void updateAllWritesNothing() throws Exception {
            for (DataOperator<LegacyEntity> operator : operators()) {
                LegacyEntity stored = new LegacyEntity("stored", 1);
                operator.insert(stored);
                LegacyEntity changed = operator.getById(stored.getId());
                changed.setName("changed");

                assertThatThrownBy(() -> operator.updateAll(Arrays.asList(changed, new LegacyEntity("no-id", 2))))
                        .as(operator.getClass().getSimpleName())
                        .isInstanceOf(DataAccessException.class);
                assertThat(operator.getById(stored.getId()).getName())
                        .as("%s: updateAll wrote part of a batch it refused", operator.getClass().getSimpleName())
                        .isEqualTo("stored");
            }
        }
    }
}
