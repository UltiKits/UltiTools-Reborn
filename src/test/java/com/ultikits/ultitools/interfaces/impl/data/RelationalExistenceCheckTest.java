package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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
    }

    @AfterEach
    void close() {
        sqlitePool.close();
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
}
