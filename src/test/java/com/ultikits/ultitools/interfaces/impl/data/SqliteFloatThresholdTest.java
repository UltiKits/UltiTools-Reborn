package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * GREATER and LESS on a {@code float} field with a threshold of every value class (#675 review IN-06).
 * <p>
 * SQLite compares a float field through its 15-significant-digit text on both sides (review WR-01).
 * That conversion keeps the order of finite numbers, but it reads an infinite value as the text
 * {@code Inf}, which converts back to 0, and a number too large for a double the same way, and it
 * reads any text that is not a number as 0. Only a number whose double value is finite is converted;
 * every other value is bound as it is, as before the conversion existed. The value-class table this
 * follows is {@code rv2-value-class-table.md} in the phase evidence.
 * <p>
 * Each lane runs the contract on a default {@code VARCHAR(255)} column and on a column declared
 * {@code REAL}. A number's expected rows come from Java: the stored float compared exactly with the
 * value ({@code <} and {@code >} on doubles for a {@code Float} or {@code Double} value, so NaN matches
 * nothing). A text value has no Java answer; its expected rows come from the SQL the operator rendered
 * before the conversion, {@code CAST(weight AS DOUBLE) < ?}, run on the same database.
 */
@DisplayName("GREATER / LESS on a float field with a threshold of any value class, SQLite (#675 review IN-06)")
class SqliteFloatThresholdTest {

    static final String TABLE = "float_threshold_row";
    static final String REAL_TABLE = "float_threshold_real_row";

    /** The stored weights by code; 2^60 and Float.MAX_VALUE need more than 15 significant digits. */
    static final Map<String, Float> FIXTURE;

    static {
        Map<String, Float> fixture = new LinkedHashMap<>();
        fixture.put("neg", -2.5f);
        fixture.put("zero", 0.0f);
        fixture.put("p1", 0.1f);
        fixture.put("p11", 1.1f);
        fixture.put("p33", 3.3f);
        fixture.put("p202", 20.2f);
        fixture.put("big", (float) (1L << 60));
        fixture.put("max", Float.MAX_VALUE);
        FIXTURE = Collections.unmodifiableMap(fixture);
    }

    /** Number thresholds every SQLite lane follows Java on, on both column types. */
    // Deliberate: the exact decimal expansion of the float 0.1f, not the shortest text 0.1.
    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    static final List<Object[]> NUMBER_CASES = Collections.unmodifiableList(Arrays.asList(
            new Object[]{Comparison.GREATER, Float.POSITIVE_INFINITY},
            new Object[]{Comparison.LESS, Float.POSITIVE_INFINITY},
            new Object[]{Comparison.GREATER, Float.NEGATIVE_INFINITY},
            new Object[]{Comparison.LESS, Float.NEGATIVE_INFINITY},
            new Object[]{Comparison.GREATER, Double.POSITIVE_INFINITY},
            new Object[]{Comparison.LESS, Double.NEGATIVE_INFINITY},
            new Object[]{Comparison.GREATER, new BigDecimal("1E+400")},
            new Object[]{Comparison.LESS, new BigDecimal("1E+400")},
            new Object[]{Comparison.GREATER, new BigDecimal("-1E+400")},
            new Object[]{Comparison.LESS, BigInteger.TEN.pow(400)},
            new Object[]{Comparison.GREATER, 1L << 60},
            new Object[]{Comparison.LESS, 1L << 60},
            new Object[]{Comparison.GREATER, BigInteger.ONE.shiftLeft(60)},
            new Object[]{Comparison.LESS, new BigDecimal((double) 0.1f)},
            new Object[]{Comparison.GREATER, new BigDecimal((double) 0.1f)},
            new Object[]{Comparison.GREATER, 0},
            new Object[]{Comparison.LESS, (short) 20},
            new Object[]{Comparison.GREATER, (byte) 1},
            new Object[]{Comparison.LESS, Long.MAX_VALUE},
            new Object[]{Comparison.LESS, 0.1f},
            new Object[]{Comparison.GREATER, 1.1f},
            new Object[]{Comparison.LESS, 3.3f},
            new Object[]{Comparison.GREATER, 20.2f},
            new Object[]{Comparison.GREATER, Float.MAX_VALUE},
            new Object[]{Comparison.LESS, Float.MAX_VALUE}));

    /** Text thresholds; none has a Java answer, so each must select what the pre-conversion SQL selects. */
    static final List<Object[]> TEXT_CASES = Collections.unmodifiableList(Arrays.asList(
            new Object[]{Comparison.LESS, "abc"},
            new Object[]{Comparison.GREATER, "abc"},
            new Object[]{Comparison.LESS, ""},
            new Object[]{Comparison.GREATER, "Infinity"},
            new Object[]{Comparison.LESS, "1e999"},
            new Object[]{Comparison.GREATER, "10"}));

    /** What the contract needs from both entity shapes. */
    interface WeightRow {
        void setUp(String code, Float weight);

        String getCode();

        String getMarker();

        void setMarker(String marker);
    }

    /** A float field on the default {@code @Column} type, a {@code VARCHAR(255)} column. */
    @Table(TABLE)
    public static class DefaultRow extends BaseDataEntity<String> implements WeightRow {
        @Column("weight")
        private Float weight;

        @Column("code")
        private String code;

        @Column("marker")
        private String marker;

        @Override
        public void setUp(String code, Float weight) {
            this.code = code;
            this.weight = weight;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMarker() {
            return marker;
        }

        @Override
        public void setMarker(String marker) {
            this.marker = marker;
        }
    }

    /** The same float field on a column declared {@code REAL}. */
    @Table(REAL_TABLE)
    public static class RealRow extends BaseDataEntity<String> implements WeightRow {
        @Column(value = "weight", type = "REAL")
        private Float weight;

        @Column("code")
        private String code;

        @Column("marker")
        private String marker;

        @Override
        public void setUp(String code, Float weight) {
            this.code = code;
            this.weight = weight;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMarker() {
            return marker;
        }

        @Override
        public void setMarker(String marker) {
            this.marker = marker;
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

    static WhereCondition weight(Comparison comparison, Object value) {
        return WhereCondition.builder().column("weight").comparison(comparison).value(value).build();
    }

    static String describe(Comparison comparison, Object value) {
        return "weight " + comparison + " " + value.getClass().getSimpleName() + " " + value;
    }

    static List<String> codesOf(List<? extends WeightRow> rows) {
        List<String> codes = new ArrayList<>();
        for (WeightRow row : rows) {
            codes.add(row.getCode());
        }
        return codes;
    }

    /** Java's answer: the codes whose stored float satisfies the comparison with {@code value}. */
    static Set<String> javaMatches(Comparison comparison, Object value) {
        Set<String> matches = new LinkedHashSet<>();
        for (Map.Entry<String, Float> row : FIXTURE.entrySet()) {
            double stored = row.getValue();
            boolean holds;
            if (value instanceof Float || value instanceof Double) {
                double threshold = ((Number) value).doubleValue();
                holds = comparison == Comparison.GREATER ? stored > threshold : stored < threshold;
            } else {
                // Deliberate: the exact decimal expansion of the double, not its shortest text form.
                @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
                int sign = new BigDecimal(stored).compareTo(exact((Number) value));
                holds = comparison == Comparison.GREATER ? sign > 0 : sign < 0;
            }
            if (holds) {
                matches.add(row.getKey());
            }
        }
        return matches;
    }

    static BigDecimal exact(Number value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return BigDecimal.valueOf(value.longValue());
    }

    static Set<String> allCodes() {
        return new LinkedHashSet<>(FIXTURE.keySet());
    }

    /** Runs both column checks and reports both: the second's failure is attached to the first's. */
    static void bothColumns(Runnable defaultColumn, Runnable realColumn) {
        AssertionError failure = null;
        try {
            defaultColumn.run();
        } catch (AssertionError failed) {
            failure = failed;
        }
        try {
            realColumn.run();
        } catch (AssertionError failed) {
            if (failure == null) {
                failure = failed;
            } else {
                failure.addSuppressed(failed);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The contract every SQLite lane runs, on the default column and on the REAL column. */
    abstract class FloatThresholdContract {

        abstract DataSource dataSource();

        abstract DataOperator<DefaultRow> defaultOperator();

        abstract DataOperator<RealRow> realOperator();

        <R extends BaseDataEntity<String> & WeightRow> List<R> insertFixture(DataOperator<R> op, Supplier<R> factory) {
            List<R> rows = new ArrayList<>();
            for (Map.Entry<String, Float> entry : FIXTURE.entrySet()) {
                R row = factory.get();
                row.setUp(entry.getKey(), entry.getValue());
                row.setMarker("initial");
                op.insert(row);
                rows.add(row);
            }
            return rows;
        }

        <R extends BaseDataEntity<String>> void removeRows(DataOperator<R> op, List<R> rows) {
            for (R row : rows) {
                op.delById(row.getId());
            }
        }

        /**
         * The rows the operator selected before the float conversion: {@code CAST(weight AS DOUBLE)}
         * against the plainly bound value, run on the lane's database; {@code null} when that SQL fails.
         */
        /** Fixed statements for the two constant test tables; no part of the SQL is built from input. */
        private String preConversionSql(String table, Comparison comparison) {
            boolean greater = comparison == Comparison.GREATER;
            if (TABLE.equals(table)) {
                return greater ? "SELECT code FROM float_threshold_row WHERE CAST(weight AS DOUBLE) > ?"
                        : "SELECT code FROM float_threshold_row WHERE CAST(weight AS DOUBLE) < ?";
            }
            return greater ? "SELECT code FROM float_threshold_real_row WHERE CAST(weight AS DOUBLE) > ?"
                    : "SELECT code FROM float_threshold_real_row WHERE CAST(weight AS DOUBLE) < ?";
        }

        Set<String> preConversionMatches(String table, Comparison comparison, Object value) {
            String sql = preConversionSql(table, comparison);
            try (Connection connection = dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, value);
                Set<String> codes = new LinkedHashSet<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        codes.add(resultSet.getString(1));
                    }
                }
                return codes;
            } catch (SQLException failed) {
                return null;
            }
        }

        @Test
        @DisplayName("del weight GREATER +Infinity or GREATER BigDecimal 1E+400 deletes nothing, on both columns")
        // The assertions are made through assertSoftly in the check helper this test calls.
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void delGreaterThanEveryFloatDeletesNothing() {
            bothColumns(() -> checkDelDeletesNothing(defaultOperator(), DefaultRow::new, "default column"),
                    () -> checkDelDeletesNothing(realOperator(), RealRow::new, "REAL column"));
        }

        private <R extends BaseDataEntity<String> & WeightRow> void checkDelDeletesNothing(
                DataOperator<R> op, Supplier<R> factory, String column) {
            List<Object> thresholds = Arrays.asList(Float.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    new BigDecimal("1E+400"), BigInteger.TEN.pow(400));
            assertSoftly(softly -> {
                for (Object threshold : thresholds) {
                    List<R> rows = insertFixture(op, factory);
                    op.del(weight(Comparison.GREATER, threshold));
                    softly.assertThat(codesOf(op.getAll()))
                            .as("%s: rows left after del %s", column, describe(Comparison.GREATER, threshold))
                            .containsExactlyInAnyOrderElementsOf(allCodes());
                    removeRows(op, rows);
                }
            });
        }

        @Test
        @DisplayName("updateIf weight LESS +Infinity writes every finite row, on both columns")
        // The assertions are made through assertSoftly in the check helper this test calls.
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void updateIfLessThanInfinityWrites() {
            bothColumns(() -> checkUpdateIfWrites(defaultOperator(), DefaultRow::new, "default column"),
                    () -> checkUpdateIfWrites(realOperator(), RealRow::new, "REAL column"));
        }

        private <R extends BaseDataEntity<String> & WeightRow> void checkUpdateIfWrites(
                DataOperator<R> op, Supplier<R> factory, String column) {
            List<R> rows = insertFixture(op, factory);
            List<String> applied = new ArrayList<>();
            for (R row : rows) {
                row.setMarker("changed");
                if (op.updateIf(row, weight(Comparison.LESS, Float.POSITIVE_INFINITY))) {
                    applied.add(row.getCode());
                }
            }
            List<String> written = new ArrayList<>();
            for (R stored : op.getAll()) {
                if ("changed".equals(stored.getMarker())) {
                    written.add(stored.getCode());
                }
            }
            removeRows(op, rows);
            assertSoftly(softly -> {
                softly.assertThat(applied).as("%s: updateIf weight LESS +Infinity applied", column)
                        .containsExactlyInAnyOrderElementsOf(allCodes());
                softly.assertThat(written).as("%s: rows written by updateIf weight LESS +Infinity", column)
                        .containsExactlyInAnyOrderElementsOf(allCodes());
            });
        }

        @Test
        @DisplayName("getAll and exist follow Java for a number threshold of every class, on both columns")
        // The assertions are made through assertSoftly in the check helper this test calls.
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void numberThresholdsFollowJava() {
            bothColumns(() -> checkNumbersFollowJava(defaultOperator(), DefaultRow::new, "default column"),
                    () -> checkNumbersFollowJava(realOperator(), RealRow::new, "REAL column"));
        }

        private <R extends BaseDataEntity<String> & WeightRow> void checkNumbersFollowJava(
                DataOperator<R> op, Supplier<R> factory, String column) {
            List<R> rows = insertFixture(op, factory);
            Map<String, List<String>> selected = new LinkedHashMap<>();
            Map<String, Boolean> existing = new LinkedHashMap<>();
            for (Object[] c : NUMBER_CASES) {
                Comparison comparison = (Comparison) c[0];
                selected.put(describe(comparison, c[1]), codesOf(op.getAll(weight(comparison, c[1]))));
                existing.put(describe(comparison, c[1]), op.exist(weight(comparison, c[1])));
            }
            removeRows(op, rows);
            assertSoftly(softly -> {
                for (Object[] c : NUMBER_CASES) {
                    Comparison comparison = (Comparison) c[0];
                    Set<String> expected = javaMatches(comparison, c[1]);
                    softly.assertThat(selected.get(describe(comparison, c[1])))
                            .as("%s: getAll %s", column, describe(comparison, c[1]))
                            .containsExactlyInAnyOrderElementsOf(expected);
                    softly.assertThat(existing.get(describe(comparison, c[1])))
                            .as("%s: exist %s", column, describe(comparison, c[1]))
                            .isEqualTo(!expected.isEmpty());
                }
            });
        }

        @Test
        @DisplayName("Whole numbers and exact decimals equal to a stored float do not select it")
        // The assertions are made through assertSoftly in the check helper this test calls.
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void valueEqualToStoredFloatIsNotSelected() {
            bothColumns(() -> checkEqualValues(defaultOperator(), DefaultRow::new, "default column"),
                    () -> checkEqualValues(realOperator(), RealRow::new, "REAL column"));
        }

        private <R extends BaseDataEntity<String> & WeightRow> void checkEqualValues(
                DataOperator<R> op, Supplier<R> factory, String column) {
            List<R> rows = insertFixture(op, factory);
            List<String> aboveTwoTo60 = codesOf(op.getAll(weight(Comparison.GREATER, 1L << 60)));
            // Deliberate: the exact decimal expansion of the float 0.1f, not the shortest text 0.1.
            @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
            List<String> belowExactTenth = codesOf(op.getAll(weight(Comparison.LESS, new BigDecimal((double) 0.1f))));
            removeRows(op, rows);
            assertSoftly(softly -> {
                softly.assertThat(aboveTwoTo60).as("%s: getAll weight GREATER Long 2^60", column)
                        .doesNotContain("big").containsExactly("max");
                softly.assertThat(belowExactTenth).as("%s: getAll weight LESS BigDecimal exactly 0.1f", column)
                        .doesNotContain("p1").containsExactlyInAnyOrder("neg", "zero");
            });
        }

        @Test
        @DisplayName("A text threshold selects what the operator selected before the float conversion")
        // The assertions are made through assertSoftly in the check helper this test calls.
        @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert")
        void textThresholdsSelectAsBefore() {
            bothColumns(() -> checkTextAsBefore(defaultOperator(), DefaultRow::new, TABLE, "default column"),
                    () -> checkTextAsBefore(realOperator(), RealRow::new, REAL_TABLE, "REAL column"));
        }

        private <R extends BaseDataEntity<String> & WeightRow> void checkTextAsBefore(
                DataOperator<R> op, Supplier<R> factory, String table, String column) {
            List<R> rows = insertFixture(op, factory);
            try {
                assertSoftly(softly -> {
                    for (Object[] c : TEXT_CASES) {
                        Comparison comparison = (Comparison) c[0];
                        Set<String> before = preConversionMatches(table, comparison, c[1]);
                        if (before == null) {
                            softly.assertThatThrownBy(() -> op.getAll(weight(comparison, c[1])))
                                    .as("%s: getAll %s fails as the pre-conversion SQL does", column,
                                            describe(comparison, c[1]))
                                    .isInstanceOf(DataAccessException.class);
                        } else {
                            softly.assertThat(codesOf(op.getAll(weight(comparison, c[1]))))
                                    .as("%s: getAll %s", column, describe(comparison, c[1]))
                                    .containsExactlyInAnyOrderElementsOf(before);
                        }
                    }
                });
            } finally {
                removeRows(op, rows);
            }
        }
    }

    @Nested
    @DisplayName("Real SQLite (the driver Paper bundles)")
    class RealSqlite extends FloatThresholdContract {
        @TempDir
        Path tempDir;

        private HikariDataSource pool;
        private SQLiteDataOperator<DefaultRow> defaultOperator;
        private SQLiteDataOperator<RealRow> realOperator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.realSqlite(tempDir.toFile());
            defaultOperator = new SQLiteDataOperator<>(pool, DefaultRow.class);
            realOperator = new SQLiteDataOperator<>(pool, RealRow.class);
        }

        @AfterEach
        void close() {
            pool.close();
        }

        @Override
        DataSource dataSource() {
            return pool;
        }

        @Override
        DataOperator<DefaultRow> defaultOperator() {
            return defaultOperator;
        }

        @Override
        DataOperator<RealRow> realOperator() {
            return realOperator;
        }

        @Test
        @DisplayName("del weight LESS -Infinity and NaN thresholds delete nothing; the text is not touched")
        void nonFiniteThresholdsDeleteNothing() {
            List<Object[]> cases = Arrays.asList(
                    new Object[]{Comparison.LESS, Float.NEGATIVE_INFINITY},
                    new Object[]{Comparison.GREATER, Float.NaN},
                    new Object[]{Comparison.LESS, Double.NaN});
            assertSoftly(softly -> {
                for (Object[] c : cases) {
                    List<DefaultRow> rows = insertFixture(defaultOperator, DefaultRow::new);
                    defaultOperator.del(weight((Comparison) c[0], c[1]));
                    softly.assertThat(codesOf(defaultOperator.getAll()))
                            .as("rows left after del %s", describe((Comparison) c[0], c[1]))
                            .containsExactlyInAnyOrderElementsOf(allCodes());
                    removeRows(defaultOperator, rows);
                }
            });
        }

        /**
         * A finite double follows Java here; the H2 stand-in is not asked, because its {@code REAL} is
         * single precision and the conversion rounds a double value to the float it holds there.
         */
        @Test
        @DisplayName("A finite double threshold follows Java, on both columns")
        void finiteDoubleThresholdsFollowJava() {
            List<Object> doubles = Arrays.asList(0.1, 1.1, 1e300, -1e300, Double.MAX_VALUE, Double.MIN_VALUE);
            for (DataOperator<? extends WeightRow> op : Arrays.<DataOperator<? extends WeightRow>>asList(
                    defaultOperator, realOperator)) {
                String column = op == defaultOperator ? "default column" : "REAL column";
                List<DefaultRow> defaults = op == defaultOperator ? insertFixture(defaultOperator, DefaultRow::new) : null;
                List<RealRow> reals = op == realOperator ? insertFixture(realOperator, RealRow::new) : null;
                assertSoftly(softly -> {
                    for (Object value : doubles) {
                        for (Comparison comparison : Arrays.asList(Comparison.GREATER, Comparison.LESS)) {
                            softly.assertThat(codesOf(op.getAll(weight(comparison, value))))
                                    .as("%s: getAll %s", column, describe(comparison, value))
                                    .containsExactlyInAnyOrderElementsOf(javaMatches(comparison, value));
                        }
                    }
                });
                if (defaults != null) {
                    removeRows(defaultOperator, defaults);
                }
                if (reals != null) {
                    removeRows(realOperator, reals);
                }
            }
        }

        /**
         * The documented limitation: a double that agrees with a stored float to 15 significant digits
         * but differs after them compares as equal to it, on the default column (which keeps 15 digits)
         * and, since the conversion, on a REAL column too.
         */
        @Test
        @DisplayName("Documented limitation: a 17-digit double next to a stored float compares as equal to it")
        void seventeenDigitDoubleNextToStoredFloatComparesAsEqual() {
            double justBelow = Math.nextDown((double) 0.1f);
            List<DefaultRow> defaults = insertFixture(defaultOperator, DefaultRow::new);
            List<RealRow> reals = insertFixture(realOperator, RealRow::new);
            List<String> defaultAbove = codesOf(defaultOperator.getAll(weight(Comparison.GREATER, justBelow)));
            List<String> realAbove = codesOf(realOperator.getAll(weight(Comparison.GREATER, justBelow)));
            removeRows(defaultOperator, defaults);
            removeRows(realOperator, reals);
            assertSoftly(softly -> {
                softly.assertThat(javaMatches(Comparison.GREATER, justBelow)).as("Java selects the 0.1f row")
                        .contains("p1");
                softly.assertThat(defaultAbove).as("default column: GREATER nextDown((double) 0.1f)")
                        .doesNotContain("p1").contains("p11");
                softly.assertThat(realAbove).as("REAL column: GREATER nextDown((double) 0.1f)")
                        .doesNotContain("p1").contains("p11");
            });
        }
    }

    @Nested
    @DisplayName("SQLiteDataOperator over H2 in MySQL mode")
    class H2SqliteOperator extends FloatThresholdContract {
        private HikariDataSource pool;
        private SQLiteDataOperator<DefaultRow> defaultOperator;
        private SQLiteDataOperator<RealRow> realOperator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("float_threshold_" + UUID.randomUUID().toString().replace("-", ""));
            defaultOperator = new SQLiteDataOperator<>(pool, DefaultRow.class);
            realOperator = new SQLiteDataOperator<>(pool, RealRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            RelationalTestEngines.dropTableIfExists(pool, REAL_TABLE);
            pool.close();
        }

        @Override
        DataSource dataSource() {
            return pool;
        }

        @Override
        DataOperator<DefaultRow> defaultOperator() {
            return defaultOperator;
        }

        @Override
        DataOperator<RealRow> realOperator() {
            return realOperator;
        }
    }

    /**
     * JSON's existing behaviour, recorded, not changed: Gson refuses to write an infinite or NaN value
     * as JSON, so a condition with one throws {@link IllegalArgumentException} as soon as it is
     * evaluated, under any comparison, and nothing is deleted or written.
     */
    @Nested
    @DisplayName("JSON (an infinite or NaN value throws)")
    class Json {
        @TempDir
        Path tempDir;

        private SimpleJsonDataOperator<DefaultRow> operator;

        @BeforeEach
        void open() throws IOException {
            Path tableDirectory = Files.createDirectories(tempDir.resolve("json").resolve("rows"));
            operator = new SimpleJsonDataOperator<>(tableDirectory.toFile().getAbsolutePath(), DefaultRow.class);
        }

        @Test
        @DisplayName("getAll, exist, page, del and updateIf with +Infinity, -Infinity or NaN throw; nothing changes")
        void nonFiniteValuesThrow() {
            List<DefaultRow> rows = new ArrayList<>();
            for (Map.Entry<String, Float> entry : FIXTURE.entrySet()) {
                DefaultRow row = new DefaultRow();
                row.setUp(entry.getKey(), entry.getValue());
                row.setMarker("initial");
                operator.insert(row);
                rows.add(row);
            }
            DefaultRow first = rows.get(0);
            List<Object> values = Arrays.asList(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
                    Double.POSITIVE_INFINITY, Double.NaN);
            assertSoftly(softly -> {
                for (Object value : values) {
                    for (Comparison comparison : Arrays.asList(Comparison.GREATER, Comparison.LESS, Comparison.EQUAL)) {
                        WhereCondition condition = weight(comparison, value);
                        String what = describe(comparison, value);
                        softly.assertThatThrownBy(() -> operator.getAll(condition)).as("getAll %s", what)
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("is not a valid double value as per JSON specification");
                        softly.assertThatThrownBy(() -> operator.exist(condition)).as("exist %s", what)
                                .isInstanceOf(IllegalArgumentException.class);
                        softly.assertThatThrownBy(() -> operator.page(1, 10, condition)).as("page %s", what)
                                .isInstanceOf(IllegalArgumentException.class);
                        softly.assertThatThrownBy(() -> operator.del(condition)).as("del %s", what)
                                .isInstanceOf(IllegalArgumentException.class);
                        first.setMarker("changed");
                        softly.assertThatThrownBy(() -> operator.updateIf(first, condition)).as("updateIf %s", what)
                                .isInstanceOf(IllegalArgumentException.class);
                    }
                }
            });
            List<String> markers = new ArrayList<>();
            for (DefaultRow stored : operator.getAll()) {
                markers.add(stored.getMarker());
            }
            assertThat(codesOf(operator.getAll())).as("rows after the refused calls")
                    .containsExactlyInAnyOrderElementsOf(codesOf(rows));
            assertThat(markers).as("markers after the refused updateIf calls").containsOnly("initial");
        }

        @Test
        @DisplayName("updateIf on a row that does not exist returns false before it evaluates the value")
        void updateIfOnMissingRowReturnsFalse() {
            DefaultRow missing = new DefaultRow();
            missing.setUp("missing", 1.0f);
            missing.setId(UUID.randomUUID().toString());
            assertThat(operator.updateIf(missing, weight(Comparison.LESS, Float.POSITIVE_INFINITY)))
                    .as("updateIf weight LESS +Infinity on a missing id").isFalse();
        }
    }
}
