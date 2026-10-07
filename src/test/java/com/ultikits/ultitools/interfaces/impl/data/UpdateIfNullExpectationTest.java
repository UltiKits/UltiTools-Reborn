package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * UltiTools-Reborn#640: {@code DataOperator#updateIf} reads a {@code null} expected value under the
 * default {@code EQUAL} comparison as {@code IS NULL} (JSON: the field is absent or JSON null), so a
 * compare-and-set against a still-unset column detects a value another server wrote into it
 * (finding F1 of the UltiWorlds gate-1 review; maintainer rule of 2026-10-06). Every other refusal
 * stays, and the read and delete builders are unchanged.
 * <p>
 * Outcomes are captured with {@code catchThrowable}, so on a framework that still refuses a null
 * expected value these tests fail by assertion, not by an uncaught exception. The SQLite operator
 * runs over H2 in MySQL mode, as {@code ConditionalUpdateTest} does: the SQLite JDBC driver is not on
 * the test classpath (Paper ships it at runtime), and this plan adds no dependency.
 * <br>
 * #640：{@code updateIf} 在默认 EQUAL 比较下把 null 期望值读作 {@code IS NULL}（JSON：字段不存在或为 null）。
 */
@DisplayName("#640 updateIf: a null expected value means IS NULL")
class UpdateIfNullExpectationTest {

    private static HikariDataSource dataSource;

    @TempDir
    Path tempDir;

    @Table("nullexp_world")
    public static class WorldRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("difficulty")
        private String difficulty;

        @Column("description")
        private String description;

        @Column(value = "balance", type = "DOUBLE")
        private double balance;

        public WorldRow() {
        }

        public WorldRow(String difficulty, String description, double balance) {
            this.difficulty = difficulty;
            this.description = description;
            this.balance = balance;
        }

        public String getDifficulty() {
            return difficulty;
        }

        public void setDifficulty(String difficulty) {
            this.difficulty = difficulty;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public double getBalance() {
            return balance;
        }

        public void setBalance(double balance) {
            this.balance = balance;
        }
    }

    /** The outcome of one call: the value it returned, or what it threw. */
    private static final class Outcome {
        private final Boolean applied;
        private final Throwable thrown;

        private Outcome(Boolean applied, Throwable thrown) {
            this.applied = applied;
            this.thrown = thrown;
        }

        @Override
        public String toString() {
            return thrown != null ? "threw " + thrown : "returned " + applied;
        }
    }

    private static Outcome updateIf(DataOperator<WorldRow> operator, WorldRow row, WhereCondition... expected) {
        Boolean[] result = new Boolean[1];
        Throwable thrown = catchThrowable(() -> result[0] = operator.updateIf(row, expected));
        return new Outcome(result[0], thrown);
    }

    private static void assertApplied(Outcome outcome, String label) {
        assertThat(outcome.thrown).as("%s: updateIf threw", label).isNull();
        assertThat(outcome.applied).as("%s: updateIf result", label).isTrue();
    }

    private static void assertNotApplied(Outcome outcome, String label) {
        assertThat(outcome.thrown).as("%s: updateIf threw", label).isNull();
        assertThat(outcome.applied).as("%s: updateIf result", label).isFalse();
    }

    private static WhereCondition isNull(String column) {
        return WhereCondition.builder().column(column).value(null).build();
    }

    private static WhereCondition isNull(String column, Comparison comparison) {
        return WhereCondition.builder().column(column).value(null).comparison(comparison).build();
    }

    private static final class Backend {
        private final String label;
        private final Supplier<DataOperator<WorldRow>> factory;

        private Backend(String label, Supplier<DataOperator<WorldRow>> factory) {
            this.label = label;
            this.factory = factory;
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
        config.setJdbcUrl("jdbc:h2:mem:updateifnull;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    @BeforeEach
    void dropTable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS nullexp_world");
        }
    }

    private List<Backend> backends() {
        List<Backend> backends = new ArrayList<>();
        String jsonDir = tempDir.resolve("json").toFile().getAbsolutePath();
        backends.add(new Backend("json", () -> new SimpleJsonDataOperator<>(jsonDir, WorldRow.class)));
        backends.add(new Backend("sqlite", () -> new SQLiteDataOperator<>(dataSource, WorldRow.class)));
        backends.add(new Backend("mysql", () -> new MysqlDataOperator<>(dataSource, WorldRow.class)));
        return backends;
    }

    @Nested
    @DisplayName("two servers on one SQLite database")
    class TwoServers {

        @Test
        @DisplayName("B fills a still-unset column between A's read and A's write: A's write does not apply, B's value stays")
        void otherServersValueInAnUnsetColumnIsKept() {
            SQLiteDataOperator<WorldRow> serverA = new SQLiteDataOperator<>(dataSource, WorldRow.class);
            SQLiteDataOperator<WorldRow> serverB = new SQLiteDataOperator<>(dataSource, WorldRow.class);
            WorldRow world = new WorldRow(null, "start", 0.0);
            serverA.insert(world);

            WorldRow readByA = serverA.getById(world.getId());
            WorldRow readByB = serverB.getById(world.getId());
            readByB.setDifficulty("HARD");
            assertApplied(updateIf(serverB, readByB, isNull("difficulty")), "B claims the unset column");

            readByA.setDescription("A");
            Outcome a = updateIf(serverA, readByA, isNull("difficulty"),
                    WhereCondition.builder().column("description").value("start").build());

            assertNotApplied(a, "A after B filled the column");
            WorldRow stored = serverA.getById(world.getId());
            assertThat(stored.getDifficulty()).as("B's value").isEqualTo("HARD");
            assertThat(stored.getDescription()).as("A wrote nothing").isEqualTo("start");
        }

        @Test
        @DisplayName("control: with no interleaving the same call applies and writes")
        void withoutInterleavingItApplies() {
            SQLiteDataOperator<WorldRow> serverA = new SQLiteDataOperator<>(dataSource, WorldRow.class);
            WorldRow world = new WorldRow(null, "start", 0.0);
            serverA.insert(world);

            WorldRow readByA = serverA.getById(world.getId());
            readByA.setDescription("A");

            assertApplied(updateIf(serverA, readByA, isNull("difficulty")), "A alone");
            WorldRow stored = serverA.getById(world.getId());
            assertThat(stored.getDescription()).isEqualTo("A");
            assertThat(stored.getDifficulty()).isNull();
        }
    }

    @Nested
    @DisplayName("on every backend (JSON, SQLite, MySQL)")
    class EveryBackend {

        @Test
        @DisplayName("a null expectation on a NULL column applies; on a column holding a value it does not and writes nothing")
        void nullExpectationAppliesOnlyWhileTheColumnIsNull() {
            for (Backend backend : backends()) {
                dropTableQuietly();
                DataOperator<WorldRow> operator = backend.factory.get();
                WorldRow unset = new WorldRow(null, "u", 1.0);
                WorldRow set = new WorldRow("EASY", "s", 1.0);
                operator.insert(unset);
                operator.insert(set);

                WorldRow claimUnset = operator.getById(unset.getId());
                claimUnset.setDifficulty("claimed");
                assertApplied(updateIf(operator, claimUnset, isNull("difficulty")), backend.label + " NULL column");
                assertThat(operator.getById(unset.getId()).getDifficulty()).as(backend.label).isEqualTo("claimed");

                WorldRow claimSet = operator.getById(set.getId());
                claimSet.setDifficulty("claimed");
                claimSet.setDescription("overwritten");
                assertNotApplied(updateIf(operator, claimSet, isNull("difficulty")), backend.label + " set column");
                WorldRow stored = operator.getById(set.getId());
                assertThat(stored.getDifficulty()).as(backend.label).isEqualTo("EASY");
                assertThat(stored.getDescription()).as(backend.label).isEqualTo("s");
            }
        }

        @Test
        @DisplayName("a null expectation together with a non-null one: both must hold")
        void nullAndNonNullExpectationsBothMustHold() {
            for (Backend backend : backends()) {
                dropTableQuietly();
                DataOperator<WorldRow> operator = backend.factory.get();
                WorldRow world = new WorldRow(null, "d", 10.0);
                operator.insert(world);
                WorldRow read = operator.getById(world.getId());
                read.setBalance(20.0);

                assertNotApplied(updateIf(operator, read, isNull("difficulty"),
                        WhereCondition.builder().column("balance").value(99.0).build()),
                        backend.label + " the non-null one fails");
                assertThat(operator.getById(world.getId()).getBalance()).as(backend.label).isEqualTo(10.0);
                assertApplied(updateIf(operator, read, isNull("difficulty"),
                        WhereCondition.builder().column("balance").value(10.0).build()),
                        backend.label + " both hold");
                assertThat(operator.getById(world.getId()).getBalance()).as(backend.label).isEqualTo(20.0);
            }
        }

        @Test
        @DisplayName("still refused: a null value under GREATER, LESS, INCLUDE, STARTSWITH, ENDSWITH")
        void nullUnderAnotherComparisonIsRefused() {
            for (Backend backend : backends()) {
                dropTableQuietly();
                DataOperator<WorldRow> operator = backend.factory.get();
                WorldRow world = new WorldRow(null, "d", 1.0);
                operator.insert(world);
                WorldRow read = operator.getById(world.getId());
                read.setDescription("changed");
                for (Comparison comparison : new Comparison[]{Comparison.GREATER, Comparison.LESS,
                        Comparison.INCLUDE, Comparison.STARTSWITH, Comparison.ENDSWITH}) {
                    Outcome outcome = updateIf(operator, read, isNull("difficulty", comparison));
                    assertThat(outcome.thrown).as("%s %s", backend.label, comparison)
                            .isInstanceOf(DataAccessException.class);
                }
                assertThat(operator.getById(world.getId()).getDescription()).as(backend.label).isEqualTo("d");
            }
        }

        @Test
        @DisplayName("still refused: an unmapped column (also with a null value), a null id, a null condition")
        void otherRefusalsStand() {
            for (Backend backend : backends()) {
                dropTableQuietly();
                DataOperator<WorldRow> operator = backend.factory.get();
                WorldRow world = new WorldRow(null, "d", 1.0);
                operator.insert(world);
                WorldRow read = operator.getById(world.getId());

                assertThat(updateIf(operator, read, isNull("difficult")).thrown)
                        .as("%s unmapped column with a null value", backend.label).isInstanceOf(DataAccessException.class);
                assertThat(updateIf(operator, read, WhereCondition.builder().column("difficult").value("x").build()).thrown)
                        .as("%s unmapped column", backend.label).isInstanceOf(DataAccessException.class);
                assertThat(updateIf(operator, new WorldRow(null, "no-id", 1.0), isNull("difficulty")).thrown)
                        .as("%s null id", backend.label).isInstanceOf(DataAccessException.class);
                assertThat(updateIf(operator, read, (WhereCondition) null).thrown)
                        .as("%s null condition", backend.label).isInstanceOf(DataAccessException.class);
            }
        }
    }

    @Nested
    @DisplayName("JSON: absent and JSON null both match")
    class Json {

        @Test
        @DisplayName("an entry whose field is absent and one whose field is JSON null both match; a present value does not")
        void absentAndJsonNullMatch() throws Exception {
            Path dir = tempDir.resolve("json-null");
            Files.createDirectories(dir);
            Files.write(dir.resolve("absent.json"),
                    "{\"id\":\"absent\",\"description\":\"a\",\"balance\":1.0}".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("jsonnull.json"),
                    "{\"id\":\"jsonnull\",\"difficulty\":null,\"description\":\"n\",\"balance\":1.0}"
                            .getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("present.json"),
                    "{\"id\":\"present\",\"difficulty\":\"NORMAL\",\"description\":\"p\",\"balance\":1.0}"
                            .getBytes(StandardCharsets.UTF_8));
            SimpleJsonDataOperator<WorldRow> operator = new SimpleJsonDataOperator<>(dir.toString(), WorldRow.class);

            for (String id : new String[]{"absent", "jsonnull"}) {
                WorldRow read = operator.getById(id);
                read.setDifficulty("HARD");
                assertApplied(updateIf(operator, read, isNull("difficulty")), id);
                assertThat(operator.getById(id).getDifficulty()).as(id).isEqualTo("HARD");
            }
            WorldRow present = operator.getById("present");
            present.setDifficulty("HARD");
            assertNotApplied(updateIf(operator, present, isNull("difficulty")), "present");
            assertThat(operator.getById("present").getDifficulty()).isEqualTo("NORMAL");
        }
    }

    /**
     * No MySQL server is on the test classpath, so the statement text {@code MysqlDataOperator}
     * prepares is pinned instead: a {@link DataSource} wrapper records every prepared statement and
     * its bound values while delegating to the H2 MySQL-mode database.
     */
    @Nested
    @DisplayName("MySQL statement shape")
    class MysqlStatementShape {

        @Test
        @DisplayName("prepares `difficulty IS NULL` and `balance = ?`, never `difficulty = ?`; binds SET values, the id and the non-null value only")
        void rendersIsNullAndBindsNothingForIt() {
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            MysqlDataOperator<WorldRow> operator = new MysqlDataOperator<>(recording, WorldRow.class);
            WorldRow world = new WorldRow(null, "d", 7.0);
            operator.insert(world);
            WorldRow read = operator.getById(world.getId());
            read.setDescription("e");
            recording.statements.clear();

            Outcome outcome = updateIf(operator, read, isNull("difficulty"),
                    WhereCondition.builder().column("balance").value(7.0).build());

            assertApplied(outcome, "mysql");
            List<Recorded> updates = new ArrayList<>();
            for (Recorded each : recording.statements) {
                if (each.sql.trim().toUpperCase().startsWith("UPDATE")) {
                    updates.add(each);
                }
            }
            assertThat(updates).as("UPDATE statements prepared").hasSize(1);
            Recorded update = updates.get(0);
            String where = update.sql.substring(update.sql.toUpperCase().indexOf(" WHERE "));
            assertThat(where).contains("difficulty IS NULL").contains("balance = ?").doesNotContain("difficulty = ?");
            int setPlaceholders = countOccurrences(update.sql.substring(0, update.sql.toUpperCase().indexOf(" WHERE ")), "?");
            List<Object> bound = new ArrayList<>(update.bound.values());
            assertThat(bound).as("bound values").hasSize(setPlaceholders + 2);
            assertThat(bound.subList(setPlaceholders, bound.size()))
                    .as("WHERE binds: the id, then the non-null expectation").containsExactly(world.getId(), 7.0);
        }
    }

    /**
     * {@code getAll}, {@code exist}, {@code del} and {@code page} keep what they did with a null
     * condition value before #640 (measured at the base and recorded in the review file): the
     * relational builders match nothing, the JSON operator throws {@code NullPointerException}.
     */
    @Nested
    @DisplayName("the read and delete builders are unchanged")
    class ReadAndDeleteUnchanged {

        @Test
        @DisplayName("SQLite: a null condition value matches nothing in getAll, exist, page and del (control: the non-null value matches)")
        void relationalMatchesNothing() {
            SQLiteDataOperator<WorldRow> operator = new SQLiteDataOperator<>(dataSource, WorldRow.class);
            operator.insert(new WorldRow(null, "unset", 1.0));
            operator.insert(new WorldRow("EASY", "set", 1.0));

            assertThat(operator.getAll(isNull("difficulty"))).isEmpty();
            assertThat(operator.exist(isNull("difficulty"))).isFalse();
            assertThat(operator.page(1, 10, isNull("difficulty"))).isEmpty();
            operator.del(isNull("difficulty"));
            assertThat(operator.getAll()).hasSize(2);

            WhereCondition easy = WhereCondition.builder().column("difficulty").value("EASY").build();
            assertThat(operator.getAll(easy)).hasSize(1);
            assertThat(operator.exist(easy)).isTrue();
            assertThat(operator.page(1, 10, easy)).hasSize(1);
            operator.del(easy);
            assertThat(operator.getAll()).hasSize(1);
        }

        @Test
        @DisplayName("JSON: a null condition value throws NullPointerException in getAll, exist, page and del (control: the non-null value matches)")
        void jsonThrowsAsBefore() {
            SimpleJsonDataOperator<WorldRow> operator = new SimpleJsonDataOperator<>(
                    tempDir.resolve("json-read").toString(), WorldRow.class);
            operator.insert(new WorldRow(null, "unset", 1.0));
            operator.insert(new WorldRow("EASY", "set", 1.0));

            assertThat(catchThrowable(() -> operator.getAll(isNull("difficulty")))).isInstanceOf(NullPointerException.class);
            assertThat(catchThrowable(() -> operator.exist(isNull("difficulty")))).isInstanceOf(NullPointerException.class);
            assertThat(catchThrowable(() -> operator.page(1, 10, isNull("difficulty")))).isInstanceOf(NullPointerException.class);
            assertThat(catchThrowable(() -> operator.del(isNull("difficulty")))).isInstanceOf(NullPointerException.class);
            assertThat(operator.getAll()).hasSize(2);

            WhereCondition easy = WhereCondition.builder().column("difficulty").value("EASY").build();
            assertThat(operator.getAll(easy)).hasSize(1);
            assertThat(operator.exist(easy)).isTrue();
            assertThat(operator.page(1, 10, easy)).hasSize(1);
            operator.del(easy);
            assertThat(operator.getAll()).hasSize(1);
        }
    }

    private void dropTableQuietly() {
        try {
            dropTable();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** One prepared statement: its text and its bound values by parameter index. */
    private static final class Recorded {
        private final String sql;
        private final Map<Integer, Object> bound = new TreeMap<>();

        private Recorded(String sql) {
            this.sql = sql;
        }
    }

    /** Records every prepared statement and bound value, delegating everything to {@code target}. */
    private static final class RecordingDataSource implements DataSource {
        private final DataSource target;
        private final List<Recorded> statements = new CopyOnWriteArrayList<>();

        private RecordingDataSource(DataSource target) {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(target.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(target.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                Object result = invoke(connection, method, args);
                if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement) {
                    Recorded recorded = new Recorded((String) args[0]);
                    statements.add(recorded);
                    return recordingStatement((PreparedStatement) result, recorded);
                }
                return result;
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private static PreparedStatement recordingStatement(PreparedStatement statement, Recorded recorded) {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (args != null && args.length >= 2 && args[0] instanceof Integer
                        && (name.startsWith("set") && !"setQueryTimeout".equals(name) && !"setFetchSize".equals(name)
                        && !"setMaxRows".equals(name) && !"setFetchDirection".equals(name)
                        && !"setPoolable".equals(name) && !"setEscapeProcessing".equals(name)
                        && !"setCursorName".equals(name) && !"setLargeMaxRows".equals(name)
                        && !"setMaxFieldSize".equals(name))) {
                    recorded.bound.put((Integer) args[0], "setNull".equals(name) ? null : args[1]);
                }
                return invoke(statement, method, args);
            };
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return target.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return target.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return target.isWrapperFor(iface);
        }
    }
}
