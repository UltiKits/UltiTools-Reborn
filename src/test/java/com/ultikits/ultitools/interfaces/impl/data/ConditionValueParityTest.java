package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A {@code null} or non-finite (infinite or NaN) {@link WhereCondition} value is refused on every
 * backend with the same {@link DataAccessException} ({@link ErrorCode#DATA_ENTITY_INVALID}) before
 * anything is read, deleted or written (6.4.0, #815, #696). Before, SQLite and MySQL bound such a
 * value (a null {@code EQUAL} matched no row, a null {@code INCLUDE} matched the text {@code null},
 * NaN matched no row) while JSON threw {@code NullPointerException} or Gson's
 * {@code IllegalArgumentException}.
 * <p>
 * Each nested lane runs {@link Contract} on one backend: real SQLite (the driver Paper bundles),
 * {@code SQLiteDataOperator} and {@code MysqlDataOperator} over H2 in MySQL mode, real MySQL (only
 * when {@value RelationalTestEngines#MYSQL_URL_PROPERTY} names a throwaway database; the real-MySQL CI
 * job supplies it) and JSON. Controls prove each refusal is about the value: the same call with a
 * finite value answers normally, and {@code updateIf}'s documented {@code null EQUAL} ({@code IS NULL},
 * #640) still works.
 */
@DisplayName("Null and non-finite condition values are refused alike on every backend (#815, #696)")
class ConditionValueParityTest {

    static final String TABLE = "condition_value_row";

    /** Every non-finite value the contract tries, boxed as a caller would pass it. */
    static final List<Object> NON_FINITE = Collections.unmodifiableList(Arrays.<Object>asList(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));

    @Table(TABLE)
    public static class ValueRow extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        @Column("score")
        private Double score;

        @Column("note")
        private String note;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    @BeforeAll
    static void mockServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(mockServer);
        }
    }

    static WhereCondition condition(String column, Comparison comparison, Object value) {
        return WhereCondition.builder().column(column).comparison(comparison).value(value).build();
    }

    /** The contract every lane runs. */
    abstract class Contract {

        abstract DataOperator<ValueRow> operator();

        ValueRow insert(String label, Double score, String note) {
            ValueRow row = new ValueRow();
            row.setLabel(label);
            row.setScore(score);
            row.setNote(note);
            operator().insert(row);
            return row;
        }

        List<String> labels() {
            List<String> labels = new ArrayList<>();
            for (ValueRow row : operator().getAll()) {
                labels.add(row.getLabel());
            }
            Collections.sort(labels);
            return labels;
        }

        /** Every way a condition reaches a backend: the reads, the delete and the conditional write. */
        List<Function<WhereCondition, Object>> calls(ValueRow target) {
            return Arrays.<Function<WhereCondition, Object>>asList(
                    condition -> operator().getAll(condition),
                    condition -> operator().exist(condition),
                    condition -> operator().page(1, 10, condition),
                    condition -> {
                        operator().del(condition);
                        return null;
                    },
                    condition -> operator().updateIf(target, condition));
        }

        void assertRefused(Object outcome, Throwable thrown, String description) {
            assertThat(thrown).as(description + " (returned " + outcome + ")")
                    .isInstanceOf(DataAccessException.class);
            assertThat(((DataAccessException) thrown).getErrorCode()).as(description)
                    .isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
        }

        @Test
        @DisplayName("a null value is refused under every comparison by getAll, exist, page and del")
        void nullValueIsRefusedByReadsAndDelete() {
            ValueRow kept = insert("kept", 5.0, null);
            insert("other", 7.0, "text null");
            for (Comparison comparison : Comparison.values()) {
                for (String column : Arrays.asList("note", "score")) {
                    List<Function<WhereCondition, Object>> calls = calls(kept).subList(0, 4);
                    for (int call = 0; call < calls.size(); call++) {
                        WhereCondition condition = condition(column, comparison, null);
                        Object[] outcome = new Object[1];
                        int index = call;
                        Throwable thrown = catchThrowable(() -> outcome[0] = calls.get(index).apply(condition));
                        assertRefused(outcome[0], thrown, "call " + call + " with " + column + " " + comparison + " null");
                    }
                }
            }
            assertThat(labels()).containsExactly("kept", "other");
        }

        @Test
        @DisplayName("updateIf refuses a null value under every comparison except EQUAL")
        void updateIfRefusesNullOutsideEqual() {
            ValueRow row = insert("row", 5.0, null);
            for (Comparison comparison : Comparison.values()) {
                if (comparison == Comparison.EQUAL) {
                    continue;
                }
                row.setLabel("changed");
                Throwable thrown = catchThrowable(() -> operator().updateIf(row, condition("note", comparison, null)));
                assertRefused(null, thrown, "updateIf note " + comparison + " null");
            }
            assertThat(labels()).containsExactly("row");
        }

        @Test
        @DisplayName("control: updateIf with a null EQUAL value still means IS NULL (#640)")
        void updateIfNullEqualStillMeansIsNull() {
            ValueRow row = insert("row", 5.0, null);
            row.setLabel("written");

            boolean written = operator().updateIf(row, condition("note", Comparison.EQUAL, null));

            assertThat(written).isTrue();
            assertThat(labels()).containsExactly("written");
        }

        @Test
        @DisplayName("a non-finite value is refused under every comparison by every call")
        void nonFiniteValueIsRefusedEverywhere() {
            ValueRow target = insert("kept", 5.0, "n");
            // A refused updateIf must not write this label, so "change no row" covers updateIf too.
            target.setLabel("changed");
            assertSoftly(softly -> {
                for (Object value : NON_FINITE) {
                    for (Comparison comparison : Comparison.values()) {
                        List<Function<WhereCondition, Object>> calls = calls(target);
                        for (int call = 0; call < calls.size(); call++) {
                            WhereCondition condition = condition("score", comparison, value);
                            int index = call;
                            Throwable thrown = catchThrowable(() -> calls.get(index).apply(condition));
                            String description = "call " + call + " with score " + comparison + " "
                                    + value + " (" + value.getClass().getSimpleName() + ")";
                            softly.assertThat(thrown).as(description).isInstanceOf(DataAccessException.class);
                            if (thrown instanceof DataAccessException) {
                                softly.assertThat(((DataAccessException) thrown).getErrorCode()).as(description)
                                        .isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
                            }
                        }
                    }
                }
            });
            assertThat(labels()).containsExactly("kept");
        }

        @Test
        @DisplayName("control: the same calls with a finite value answer normally")
        void finiteValueAnswers() {
            insert("low", 1.0, "a");
            ValueRow high = insert("high", 9.0, "b");

            assertSoftly(softly -> {
                softly.assertThat(operator().getAll(condition("score", Comparison.GREATER, 5.0)))
                        .extracting(ValueRow::getLabel).containsExactly("high");
                softly.assertThat(operator().exist(condition("score", Comparison.LESS, 5.0))).isTrue();
                softly.assertThat(operator().page(1, 10, condition("note", Comparison.EQUAL, "a")))
                        .extracting(ValueRow::getLabel).containsExactly("low");
                high.setNote("c");
                softly.assertThat(operator().updateIf(high, condition("score", Comparison.GREATER, 5.0f))).isTrue();
            });
            operator().del(condition("score", Comparison.LESS, 5.0));
            assertThat(labels()).containsExactly("high");
        }

        @Test
        @DisplayName("del refuses an empty condition with the typed exception")
        void delRefusesAnEmptyCondition() {
            insert("kept", 5.0, null);

            Throwable thrown = catchThrowable(() -> operator().del(WhereCondition.empty()));

            assertRefused(null, thrown, "del(WhereCondition.empty())");
            assertThat(labels()).containsExactly("kept");
        }

        @Test
        @DisplayName("exist(entity) answers false for an entity without an id")
        void existOfAnEntityWithoutIdIsFalse() {
            insert("kept", 5.0, null);

            assertThat(operator().exist(new ValueRow())).isFalse();
        }
    }

    @Nested
    @DisplayName("Real SQLite (the driver Paper bundles)")
    class RealSqlite extends Contract {
        @TempDir
        Path tempDir;

        private HikariDataSource pool;
        private SQLiteDataOperator<ValueRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.realSqlite(tempDir.toFile());
            operator = new SQLiteDataOperator<>(pool, ValueRow.class);
        }

        @AfterEach
        void close() {
            pool.close();
        }

        @Override
        DataOperator<ValueRow> operator() {
            return operator;
        }
    }

    @Nested
    @DisplayName("SQLiteDataOperator over H2 in MySQL mode")
    class H2SqliteOperator extends Contract {
        private HikariDataSource pool;
        private SQLiteDataOperator<ValueRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("cv_h2_sqlite_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new SQLiteDataOperator<>(pool, ValueRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<ValueRow> operator() {
            return operator;
        }
    }

    @Nested
    @DisplayName("MysqlDataOperator over H2 in MySQL mode")
    class H2MysqlOperator extends Contract {
        private HikariDataSource pool;
        private MysqlDataOperator<ValueRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("cv_h2_mysql_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new MysqlDataOperator<>(pool, ValueRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<ValueRow> operator() {
            return operator;
        }
    }

    @Nested
    @DisplayName("Real MySQL (throwaway local database)")
    @EnabledIfSystemProperty(named = RelationalTestEngines.MYSQL_URL_PROPERTY, matches = ".+")
    class RealMysql extends Contract {
        private HikariDataSource pool;
        private MysqlDataOperator<ValueRow> operator;

        @BeforeEach
        void open() throws SQLException {
            pool = RelationalTestEngines.realMysql();
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            operator = new MysqlDataOperator<>(pool, ValueRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<ValueRow> operator() {
            return operator;
        }
    }

    @Nested
    @DisplayName("JSON")
    class Json extends Contract {
        @TempDir
        Path tempDir;

        private SimpleJsonDataOperator<ValueRow> operator;

        @BeforeEach
        void open() throws IOException {
            Path tableDirectory = Files.createDirectories(tempDir.resolve("json").resolve("rows"));
            operator = new SimpleJsonDataOperator<>(tableDirectory.toFile().getAbsolutePath(), ValueRow.class);
        }

        @Override
        DataOperator<ValueRow> operator() {
            return operator;
        }
    }
}
