package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * An update by a non-null id that matches no row writes nothing and logs one WARNING naming the
 * table and the id, every time, on JSON, SQLite and MySQL (#558, maintainer 2026-09-29:
 * 「不写，并告诉调用方没写成」). Before, the relational backends returned silently and the JSON
 * backend threw a raw {@code NullPointerException}.
 */
@DisplayName("An update by an id no row has writes nothing and warns, on every backend (#558)")
class MissingRowUpdateTest {

    private static final String TABLE = "missing_row_entity";
    private static final String MISSING_ID = "no-such-id";

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    private final List<LogRecord> warnings = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            if (Level.WARNING.equals(logRecord.getLevel())) {
                warnings.add(logRecord);
            }
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
    private final List<Logger> loggers = Arrays.asList(
            Logger.getLogger(AbstractRelationalDataOperator.class.getName()),
            Logger.getLogger(SimpleJsonDataOperator.class.getName()));

    @Table(TABLE)
    public static class Row extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        public Row() {
        }

        public Row(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static final class Backend {
        final String label;
        final Supplier<DataOperator<Row>> factory;

        Backend(String label, Supplier<DataOperator<Row>> factory) {
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
        config.setJdbcUrl("jdbc:h2:mem:missingrowupdate;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // No setPassword: the in-memory database is created without one on first connect.
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() {
        for (Logger logger : loggers) {
            logger.addHandler(capture);
        }
    }

    @AfterEach
    void tearDown() {
        for (Logger logger : loggers) {
            logger.removeHandler(capture);
        }
    }

    private static void dropTable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    List<Backend> backends() {
        String jsonDir = tempDir.resolve(TABLE).toFile().getAbsolutePath();
        return Arrays.asList(
                new Backend("json", () -> new SimpleJsonDataOperator<>(jsonDir, Row.class)),
                new Backend("sqlite", () -> new SQLiteDataOperator<>(dataSource, Row.class)),
                new Backend("mysql", () -> new MysqlDataOperator<>(dataSource, Row.class)));
    }

    DataOperator<Row> fresh(Backend backend) throws Exception {
        dropTable();
        warnings.clear();
        DataOperator<Row> operator = backend.factory.get();
        for (Row row : operator.getAll()) {
            operator.delById(row.getId());
        }
        operator.insert(new Row("present", "stored"));
        warnings.clear();
        return operator;
    }

    private List<String> warningsNaming(String id) {
        List<String> lines = new ArrayList<>();
        for (LogRecord logRecord : warnings) {
            String message = logRecord.getMessage();
            if (message != null && message.contains(TABLE) && message.contains("'" + id + "'")) {
                lines.add(message);
            }
        }
        return lines;
    }

    @Test
    @DisplayName("update(T): no exception, nothing written, one WARNING per call")
    void updateEntity() throws Exception {
        for (Backend backend : backends()) {
            DataOperator<Row> operator = fresh(backend);
            Row missing = new Row(MISSING_ID, "ghost");

            assertThatCode(() -> operator.update(missing)).as(backend.label).doesNotThrowAnyException();
            assertThatCode(() -> operator.update(missing)).as(backend.label).doesNotThrowAnyException();

            assertThat(operator.getById(MISSING_ID)).as("%s: the update created a row", backend.label).isNull();
            assertThat(operator.getAll()).as(backend.label).hasSize(1);
            assertThat(warningsNaming(MISSING_ID)).as("%s: one WARNING per call", backend.label).hasSize(2);
        }
    }

    @Test
    @DisplayName("update(column, value, id): no exception, nothing written, one WARNING per call")
    void updateColumn() throws Exception {
        for (Backend backend : backends()) {
            DataOperator<Row> operator = fresh(backend);

            assertThatCode(() -> operator.update("name", "ghost", MISSING_ID))
                    .as(backend.label).doesNotThrowAnyException();

            assertThat(operator.getById(MISSING_ID)).as(backend.label).isNull();
            assertThat(operator.getAll()).as(backend.label).hasSize(1);
            assertThat(warningsNaming(MISSING_ID)).as(backend.label).hasSize(1);
        }
    }

    @Test
    @DisplayName("updateAll: the present row is written, the missing one warns once")
    void updateAll() throws Exception {
        for (Backend backend : backends()) {
            DataOperator<Row> operator = fresh(backend);
            Row present = operator.getById("present");
            present.setName("renamed");

            assertThatCode(() -> operator.updateAll(Arrays.asList(present, new Row(MISSING_ID, "ghost"))))
                    .as(backend.label).doesNotThrowAnyException();

            assertThat(operator.getById("present").getName()).as(backend.label).isEqualTo("renamed");
            assertThat(operator.getById(MISSING_ID)).as(backend.label).isNull();
            assertThat(warningsNaming(MISSING_ID)).as(backend.label).hasSize(1);
            assertThat(warningsNaming("present")).as("%s: a matched row warned", backend.label).isEmpty();
        }
    }
}
