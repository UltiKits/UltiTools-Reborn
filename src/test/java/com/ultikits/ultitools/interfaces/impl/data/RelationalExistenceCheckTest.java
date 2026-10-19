package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

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
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The relational existence check answers whether a row matches, whichever Java type the JDBC
 * driver uses for {@code COUNT(*)}. sqlite-jdbc, the driver Paper bundles, reports an
 * {@code Integer}; H2 reports a {@code Long}. Before 6.3.0, {@code exist} read the count as a
 * {@code Long}, so on real SQLite it threw {@code ClassCastException} instead of answering.
 * <p>
 * Every existence entry point is checked on real SQLite: {@code exist(WhereCondition...)},
 * {@code exist(entity)}, and {@code Query#exists()} with only {@code eq} conditions, which delegates
 * to {@code exist}. The same three checks run on {@code SQLiteDataOperator} over H2 in MySQL mode
 * as a control: the operator is the same, only the driver differs.
 */
@DisplayName("Existence check counts rows on every relational driver")
class RelationalExistenceCheckTest {

    static final String TABLE = "existence_check_row";

    /** One stored row with a single default-typed text column. */
    @Table(TABLE)
    public static class ExistenceRow extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public ExistenceRow() {
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    @TempDir
    Path tempDir;

    private HikariDataSource sqlitePool;
    private SQLiteDataOperator<ExistenceRow> sqliteOperator;
    private HikariDataSource h2Pool;
    private SQLiteDataOperator<ExistenceRow> h2Operator;

    @BeforeAll
    static void mockServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
        }
    }

    @BeforeEach
    void open() {
        sqlitePool = RelationalTestEngines.realSqlite(tempDir.toFile());
        sqliteOperator = new SQLiteDataOperator<>(sqlitePool, ExistenceRow.class);
        h2Pool = RelationalTestEngines.h2MysqlMode("existence_h2_" + UUID.randomUUID().toString().replace("-", ""));
        h2Operator = new SQLiteDataOperator<>(h2Pool, ExistenceRow.class);
    }

    @AfterEach
    void close() {
        sqlitePool.close();
        h2Pool.close();
    }

    static WhereCondition labelEquals(String value) {
        return WhereCondition.builder().column("label").comparison(Comparison.EQUAL).value(value).build();
    }

    static ExistenceRow row(String label) {
        ExistenceRow row = new ExistenceRow();
        row.setLabel(label);
        return row;
    }

    @Test
    @DisplayName("exist(WhereCondition...) on real SQLite is true for a stored match and false for none")
    void existCountsMatchingRowsOnRealSqlite() {
        sqliteOperator.insert(row("kept"));
        boolean[] answers = new boolean[2];

        assertThatCode(() -> {
            answers[0] = sqliteOperator.exist(labelEquals("kept"));
            answers[1] = sqliteOperator.exist(labelEquals("missing"));
        }).as("exist on the sqlite-jdbc driver").doesNotThrowAnyException();
        assertThat(answers[0]).as("exist label = kept").isTrue();
        assertThat(answers[1]).as("exist label = missing").isFalse();
    }

    @Test
    @DisplayName("exist(entity) on real SQLite is true for the stored entity and false for an unstored one")
    void existByEntityOnRealSqlite() {
        ExistenceRow stored = row("kept");
        sqliteOperator.insert(stored);
        ExistenceRow unstored = row("kept");
        unstored.setId("not-" + stored.getId());
        boolean[] answers = new boolean[2];

        assertThatCode(() -> {
            answers[0] = sqliteOperator.exist(stored);
            answers[1] = sqliteOperator.exist(unstored);
        }).as("exist(entity) on the sqlite-jdbc driver").doesNotThrowAnyException();
        assertThat(stored.getId()).as("id assigned at insert").isNotNull();
        assertThat(answers[0]).as("exist stored entity").isTrue();
        assertThat(answers[1]).as("exist unstored entity with another id").isFalse();
    }

    @Test
    @DisplayName("An eq-only Query#exists() on real SQLite is true for a stored value and false for none")
    void queryExistsOnRealSqlite() {
        sqliteOperator.insert(row("kept"));
        boolean[] answers = new boolean[2];

        assertThatCode(() -> {
            answers[0] = sqliteOperator.query().where("label").eq("kept").exists();
            answers[1] = sqliteOperator.query().where("label").eq("missing").exists();
        }).as("Query#exists() on the sqlite-jdbc driver").doesNotThrowAnyException();
        assertThat(answers[0]).as("query label = kept exists").isTrue();
        assertThat(answers[1]).as("query label = missing exists").isFalse();
    }

    @Test
    @DisplayName("Control: exist(WhereCondition...) on H2 in MySQL mode answers the same")
    void existCountsMatchingRowsOnH2MysqlMode() {
        h2Operator.insert(row("kept"));

        assertThat(h2Operator.exist(labelEquals("kept"))).as("exist label = kept").isTrue();
        assertThat(h2Operator.exist(labelEquals("missing"))).as("exist label = missing").isFalse();
    }

    @Test
    @DisplayName("Control: exist(entity) on H2 in MySQL mode answers the same")
    void existByEntityOnH2MysqlMode() {
        ExistenceRow stored = row("kept");
        h2Operator.insert(stored);
        ExistenceRow unstored = row("kept");
        unstored.setId("not-" + stored.getId());

        assertThat(stored.getId()).as("id assigned at insert").isNotNull();
        assertThat(h2Operator.exist(stored)).as("exist stored entity").isTrue();
        assertThat(h2Operator.exist(unstored)).as("exist unstored entity with another id").isFalse();
    }

    @Test
    @DisplayName("Control: an eq-only Query#exists() on H2 in MySQL mode answers the same")
    void queryExistsOnH2MysqlMode() {
        h2Operator.insert(row("kept"));

        assertThat(h2Operator.query().where("label").eq("kept").exists()).as("query label = kept exists").isTrue();
        assertThat(h2Operator.query().where("label").eq("missing").exists()).as("query label = missing exists").isFalse();
    }
}
