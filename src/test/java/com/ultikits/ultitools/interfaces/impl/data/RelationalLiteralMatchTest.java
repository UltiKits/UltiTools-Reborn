package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
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
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * INCLUDE, STARTSWITH and ENDSWITH match their value literally on every backend (#675, frozen case
 * 5506b809): a value such as {@code world_} or {@code 100%} must not widen a read, a delete or a
 * conditional update.
 * <p>
 * The contract lives in {@link LiteralContract}; each nested lane runs it on one backend.
 */
@DisplayName("Literal INCLUDE / STARTSWITH / ENDSWITH on every backend (#675)")
class RelationalLiteralMatchTest {

    static final String TABLE = "literal_match_row";

    /** One stored row: {@code label} holds the fixture text, {@code marker} is what updateIf changes. */
    @Table(TABLE)
    public static class LiteralRow extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        @Column("marker")
        private String marker;

        public LiteralRow() {
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getMarker() {
            return marker;
        }

        public void setMarker(String marker) {
            this.marker = marker;
        }
    }

    static WhereCondition condition(Comparison comparison, Object value) {
        return WhereCondition.builder().column("label").comparison(comparison).value(value).build();
    }

    static List<String> labelsOf(List<LiteralRow> rows) {
        List<String> labels = new ArrayList<>();
        for (LiteralRow row : rows) {
            labels.add(row.getLabel());
        }
        return labels;
    }

    /** The contract every lane runs. */
    abstract class LiteralContract {

        /** The operator under test, built fresh for each test by the lane. */
        abstract DataOperator<LiteralRow> operator();

        /**
         * The table's stored definition, read from the engine, or an empty string when the lane
         * has no table definition to read.
         */
        String tableDefinition() throws SQLException {
            return "";
        }

        List<LiteralRow> insertRows(String... labels) {
            List<LiteralRow> rows = new ArrayList<>();
            for (String label : labels) {
                LiteralRow row = new LiteralRow();
                row.setLabel(label);
                row.setMarker("initial");
                operator().insert(row);
                rows.add(row);
            }
            return rows;
        }

        @Test
        @DisplayName("STARTSWITH world_ reads and deletes only rows that literally start with world_")
        void startsWithUnderscoreIsLiteral() throws SQLException {
            String definitionBefore = tableDefinition();
            insertRows("world_", "worldX", "world_1");
            WhereCondition startsWith = condition(Comparison.STARTSWITH, "world_");

            List<String> read = labelsOf(operator().getAll(startsWith));
            operator().del(startsWith);
            List<String> left = labelsOf(operator().getAll());
            String definitionAfter = tableDefinition();

            assertSoftly(softly -> {
                softly.assertThat(read).as("getAll STARTSWITH world_").containsExactlyInAnyOrder("world_", "world_1");
                softly.assertThat(left).as("rows left after del STARTSWITH world_").containsExactly("worldX");
                softly.assertThat(definitionAfter).as("table definition").isEqualTo(definitionBefore);
            });
        }
    }

    @Nested
    @DisplayName("Real SQLite (the driver Paper bundles)")
    class RealSqlite extends LiteralContract {
        @TempDir
        Path tempDir;

        private HikariDataSource pool;
        private SQLiteDataOperator<LiteralRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.realSqlite(tempDir.toFile());
            operator = new SQLiteDataOperator<>(pool, LiteralRow.class);
        }

        @AfterEach
        void close() {
            pool.close();
        }

        @Override
        DataOperator<LiteralRow> operator() {
            return operator;
        }

        @Override
        String tableDefinition() throws SQLException {
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                statement.setString(1, TABLE);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : "";
                }
            }
        }
    }
}
