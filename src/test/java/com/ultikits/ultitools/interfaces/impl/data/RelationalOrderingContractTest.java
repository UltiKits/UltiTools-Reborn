package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * GREATER and LESS follow the entity field's numeric Java type (#675, frozen case 611b6f58): a
 * numeric field with the default {@code @Column} type is a {@code VARCHAR(255)} column, which
 * SQLite gives TEXT affinity, so without a typed comparison {@code 2 > 10} is true there and
 * {@code updateIf} writes although its numeric precondition is false.
 * <p>
 * The contract lives in {@link OrderingContract}; each nested lane runs it on one backend: real
 * SQLite (the driver Paper bundles), {@code SQLiteDataOperator} and {@code MysqlDataOperator} over H2
 * in MySQL mode, and real MySQL (only when {@value RelationalTestEngines#MYSQL_URL_PROPERTY} names a
 * throwaway local database). Expected sets come from two oracles computed in Java over the fixture,
 * never from hand-written lists: the exact oracle ({@code Integer}, {@code Long}, {@code Double} and
 * {@code Float} compare) and the double-precision oracle (both sides as {@code double}). Real MySQL
 * compares a default text column with a number in double precision, which the maintainer decided to
 * document rather than change ({@code mysql-large-integer-ordering: DOCUMENT}), so that lane expects
 * the double-precision oracle on default columns and the exact oracle on numeric-declared ones. The
 * {@link Json} lane pins JSON's existing double-precision ordering.
 */
@DisplayName("Typed GREATER / LESS ordering on every backend (#675)")
class RelationalOrderingContractTest {

    static final String TABLE = "ordering_row";
    static final String TYPED_TABLE = "ordering_typed_row";

    /** 2^53, the largest whole number below which every long is exactly a double. */
    static final long TWO_TO_53 = 9007199254740992L;

    /** The rows the matrix stores; {@code code} is unique and names the row in assertions. */
    static final List<Values> FIXTURE = Collections.unmodifiableList(Arrays.asList(
            new Values(2, 2L, 2.5, 0.5f, "2"),
            new Values(10, TWO_TO_53, 10.0, 10.0f, "10"),
            new Values(9, TWO_TO_53 + 1, -0.75, -0.75f, "9"),
            new Values(-5, -(TWO_TO_53 + 1), -5.0, -5.0f, "-5"),
            new Values(0, 9223372036854775806L, 0.0, 0.0f, "0"),
            new Values(-1, -2L, 0.125, 0.125f, "-1"),
            // Floats with no exact binary form (#675 review WR-01): SQLite keeps 15 significant digits
            // of the double a Float is bound as, which lands below 0.1f and 3.3f and above 1.1f and
            // 20.2f. 20.2f is the largest weight, so "weight GREATER 20.2f" has no exact match.
            new Values(-3, 3L, 0.1, 0.1f, "-3"),
            new Values(-4, 4L, 1.1, 1.1f, "-4"),
            new Values(-6, 6L, 3.3, 3.3f, "-6"),
            new Values(-7, 7L, 20.2, 20.2f, "-7")));

    /** The GREATER and LESS conditions the matrix walks, each threshold of the field's own Java type. */
    static final List<Cond> CONDITIONS = Collections.unmodifiableList(Arrays.asList(
            new Cond("level", Comparison.GREATER, 9),
            new Cond("level", Comparison.GREATER, 10),
            new Cond("level", Comparison.LESS, 0),
            new Cond("level", Comparison.GREATER, -2),
            new Cond("stamp", Comparison.GREATER, TWO_TO_53),
            new Cond("stamp", Comparison.LESS, TWO_TO_53 + 1),
            new Cond("stamp", Comparison.LESS, -TWO_TO_53),
            new Cond("stamp", Comparison.GREATER, 10L),
            new Cond("ratio", Comparison.GREATER, 2.0),
            new Cond("ratio", Comparison.LESS, -0.5),
            new Cond("ratio", Comparison.GREATER, 0.0),
            new Cond("weight", Comparison.GREATER, 0.25f),
            new Cond("weight", Comparison.GREATER, 2.0f),
            new Cond("weight", Comparison.LESS, 0.0f),
            new Cond("weight", Comparison.LESS, 0.1f),
            new Cond("weight", Comparison.GREATER, 1.1f),
            new Cond("weight", Comparison.LESS, 3.3f),
            new Cond("weight", Comparison.GREATER, 20.2f)));

    static final List<String> NUMERIC_FIELDS = Collections.unmodifiableList(
            Arrays.asList("level", "stamp", "ratio", "weight"));

    /** One fixture row's values. */
    static final class Values {
        final int level;
        final long stamp;
        final double ratio;
        final float weight;
        final String code;

        Values(int level, long stamp, double ratio, float weight, String code) {
            this.level = level;
            this.stamp = stamp;
            this.ratio = ratio;
            this.weight = weight;
            this.code = code;
        }

        Object get(String field) {
            switch (field) {
                case "level":
                    return level;
                case "stamp":
                    return stamp;
                case "ratio":
                    return ratio;
                case "weight":
                    return weight;
                default:
                    throw new IllegalArgumentException("Not a numeric field: " + field);
            }
        }
    }

    /** One GREATER or LESS condition of the matrix. */
    static final class Cond {
        final String field;
        final Comparison comparison;
        final Object threshold;

        Cond(String field, Comparison comparison, Object threshold) {
            this.field = field;
            this.comparison = comparison;
            this.threshold = threshold;
        }

        WhereCondition condition() {
            return RelationalOrderingContractTest.condition(field, comparison, threshold);
        }

        /** Whether {@code sign} (a compare result of stored value against threshold) satisfies the comparison. */
        boolean holds(int sign) {
            return comparison == Comparison.GREATER ? sign > 0 : sign < 0;
        }

        @Override
        public String toString() {
            return field + " " + comparison + " " + threshold;
        }
    }

    /** What the contract needs from both entity shapes. */
    interface Row {
        void apply(Values values);

        String getCode();

        String getMarker();

        void setMarker(String marker);
    }

    /** One stored row; every numeric column keeps the default {@code @Column} type. */
    @Table(TABLE)
    public static class OrderingRow extends BaseDataEntity<String> implements Row {
        @Column("level")
        private int level;

        @Column("stamp")
        private Long stamp;

        @Column("ratio")
        private double ratio;

        @Column("weight")
        private Float weight;

        @Column("code")
        private String code;

        @Column("marker")
        private String marker;

        @Override
        public void apply(Values values) {
            level = values.level;
            stamp = values.stamp;
            ratio = values.ratio;
            weight = values.weight;
            code = values.code;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public Long getStamp() {
            return stamp;
        }

        public double getRatio() {
            return ratio;
        }

        public Float getWeight() {
            return weight;
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

    /** The same fields as {@link OrderingRow}, with the numeric columns declared with numeric types. */
    @Table(TYPED_TABLE)
    public static class TypedOrderingRow extends BaseDataEntity<String> implements Row {
        @Column(value = "level", type = "BIGINT")
        private int level;

        @Column(value = "stamp", type = "BIGINT")
        private Long stamp;

        @Column(value = "ratio", type = "DOUBLE")
        private double ratio;

        @Column(value = "weight", type = "DOUBLE")
        private Float weight;

        @Column("code")
        private String code;

        @Column("marker")
        private String marker;

        @Override
        public void apply(Values values) {
            level = values.level;
            stamp = values.stamp;
            ratio = values.ratio;
            weight = values.weight;
            code = values.code;
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

    static WhereCondition condition(String column, Comparison comparison, Object value) {
        return WhereCondition.builder().column(column).comparison(comparison).value(value).build();
    }

    static List<String> codesOf(List<? extends Row> rows) {
        List<String> codes = new ArrayList<>();
        for (Row row : rows) {
            codes.add(row.getCode());
        }
        return codes;
    }

    static List<Integer> levelsOf(List<OrderingRow> rows) {
        List<Integer> levels = new ArrayList<>();
        for (OrderingRow row : rows) {
            levels.add(row.getLevel());
        }
        return levels;
    }

    /** The exact oracle: the codes of the rows whose value satisfies {@code cond} in the field's own type. */
    static Set<String> exactMatches(Cond cond) {
        Set<String> matches = new LinkedHashSet<>();
        for (Values values : FIXTURE) {
            Object stored = values.get(cond.field);
            int sign;
            if (stored instanceof Integer) {
                sign = Integer.compare((Integer) stored, (Integer) cond.threshold);
            } else if (stored instanceof Long) {
                sign = Long.compare((Long) stored, (Long) cond.threshold);
            } else if (stored instanceof Double) {
                sign = Double.compare((Double) stored, (Double) cond.threshold);
            } else {
                sign = Float.compare((Float) stored, (Float) cond.threshold);
            }
            if (cond.holds(sign)) {
                matches.add(values.code);
            }
        }
        return matches;
    }

    /** The double-precision oracle: both sides compared as {@code double}. */
    static Set<String> doubleMatches(Cond cond) {
        Set<String> matches = new LinkedHashSet<>();
        for (Values values : FIXTURE) {
            double stored = ((Number) values.get(cond.field)).doubleValue();
            if (cond.holds(Double.compare(stored, ((Number) cond.threshold).doubleValue()))) {
                matches.add(values.code);
            }
        }
        return matches;
    }

    /** The defect's reading: the stored text compared with the threshold's text. */
    static Set<String> textMatches(Cond cond) {
        Set<String> matches = new LinkedHashSet<>();
        for (Values values : FIXTURE) {
            String stored = String.valueOf(values.get(cond.field));
            if (cond.holds(stored.compareTo(String.valueOf(cond.threshold)))) {
                matches.add(values.code);
            }
        }
        return matches;
    }

    /**
     * The defect's reading on SQLite (#675 review WR-01): the stored side is the 15-significant-digit
     * text SQLite keeps for the double a {@code Float} is bound as, the threshold side that double itself.
     */
    static Set<String> fifteenDigitMatches(Cond cond) {
        Set<String> matches = new LinkedHashSet<>();
        for (Values values : FIXTURE) {
            double stored = ((Number) values.get(cond.field)).doubleValue();
            // Deliberate: the exact decimal expansion of the double, rounded to 15 digits afterwards.
            @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
            double kept = new BigDecimal(stored).round(new MathContext(15)).doubleValue();
            if (cond.holds(Double.compare(kept, ((Number) cond.threshold).doubleValue()))) {
                matches.add(values.code);
            }
        }
        return matches;
    }

    /** The weight conditions whose exact set differs from the 15-digit reading. */
    static List<Cond> weightConditionsWhereFifteenDigitsDiffer() {
        List<Cond> conds = new ArrayList<>();
        for (Cond cond : CONDITIONS) {
            if ("weight".equals(cond.field) && !exactMatches(cond).equals(fifteenDigitMatches(cond))) {
                conds.add(cond);
            }
        }
        return conds;
    }

    /** The fixture codes not in {@code matches}. */
    static Set<String> nonMatches(Set<String> matches) {
        Set<String> rest = new LinkedHashSet<>();
        for (Values values : FIXTURE) {
            if (!matches.contains(values.code)) {
                rest.add(values.code);
            }
        }
        return rest;
    }

    /** The fields with at least one condition whose exact set differs from the text reading. */
    static Set<String> fieldsWhereTextDiffers() {
        Set<String> fields = new LinkedHashSet<>();
        for (Cond cond : CONDITIONS) {
            if (!exactMatches(cond).equals(textMatches(cond))) {
                fields.add(cond.field);
            }
        }
        return fields;
    }

    /** The stamp conditions whose exact set differs from the double-precision set. */
    static List<Cond> stampConditionsWherePrecisionMatters() {
        List<Cond> conds = new ArrayList<>();
        for (Cond cond : CONDITIONS) {
            if ("stamp".equals(cond.field) && !exactMatches(cond).equals(doubleMatches(cond))) {
                conds.add(cond);
            }
        }
        return conds;
    }

    @Test
    @DisplayName("No case can pass vacuously: every field meets the affinity defect and 64-bit precision is exercised")
    void everyFieldAndPrecisionIsExercised() {
        assertSoftly(softly -> {
            softly.assertThat(fieldsWhereTextDiffers()).as("numeric fields with a condition the text reading gets wrong")
                    .containsExactlyInAnyOrderElementsOf(NUMERIC_FIELDS);
            softly.assertThat(stampConditionsWherePrecisionMatters())
                    .as("stamp conditions whose exact set differs from the double-precision set").isNotEmpty();
            List<Cond> floatBoundaries = weightConditionsWhereFifteenDigitsDiffer();
            softly.assertThat(floatBoundaries).as("weight conditions a 15-digit reading gets wrong").hasSize(4);
            boolean someGreater = false;
            boolean someLess = false;
            boolean someWithoutExactMatch = false;
            for (Cond cond : floatBoundaries) {
                someGreater |= cond.comparison == Comparison.GREATER;
                someLess |= cond.comparison == Comparison.LESS;
                someWithoutExactMatch |= exactMatches(cond).isEmpty();
            }
            softly.assertThat(someGreater && someLess).as("15-digit boundary cases in both directions").isTrue();
            softly.assertThat(someWithoutExactMatch).as("a 15-digit boundary case with no exact match (negative exist)")
                    .isTrue();
            boolean someEmpty = false;
            boolean someNonEmpty = false;
            for (Cond cond : CONDITIONS) {
                someEmpty |= exactMatches(cond).isEmpty();
                someNonEmpty |= !exactMatches(cond).isEmpty();
            }
            softly.assertThat(someEmpty).as("a condition with no exact match (negative exist)").isTrue();
            softly.assertThat(someNonEmpty).as("a condition with an exact match (positive exist)").isTrue();
        });
    }

    /** The contract every relational lane runs. */
    abstract class OrderingContract {

        /** The operator over default-typed columns, built fresh for each test by the lane. */
        abstract DataOperator<OrderingRow> operator();

        /** The operator over numeric-declared columns, built fresh for each test by the lane. */
        abstract DataOperator<TypedOrderingRow> typedOperator();

        /** Whether the lane orders default-typed columns exactly (else in double precision). */
        abstract boolean exactOnDefaultColumns();

        /** Whether EQUAL on a default-typed column is exact beyond 2^53 (else in double precision). */
        boolean exactEqualityOnDefaultColumns() {
            return true;
        }

        Set<String> expected(Cond cond) {
            return exactOnDefaultColumns() ? exactMatches(cond) : doubleMatches(cond);
        }

        <R extends BaseDataEntity<String> & Row> List<R> insertFixture(DataOperator<R> op, Supplier<R> factory) {
            List<R> rows = new ArrayList<>();
            for (Values values : FIXTURE) {
                R row = factory.get();
                row.apply(values);
                row.setMarker("initial");
                op.insert(row);
                rows.add(row);
            }
            return rows;
        }

        List<OrderingRow> insertFixture() {
            return insertFixture(operator(), OrderingRow::new);
        }

        /** Removes the given rows through delById, by the ids the operator assigned at insert. */
        <R extends BaseDataEntity<String>> void removeRows(DataOperator<R> op, List<R> rows) {
            for (R row : rows) {
                op.delById(row.getId());
            }
        }

        OrderingRow insertLevel(int level) {
            OrderingRow row = new OrderingRow();
            row.apply(new Values(level, level, level, level, String.valueOf(level)));
            row.setMarker("initial");
            operator().insert(row);
            return row;
        }

        @Test
        @DisplayName("updateIf with level GREATER 10 writes nothing for levels 2 and 9; getAll level GREATER 9 is only 10")
        void updateIfHonoursNumericPrecondition() {
            OrderingRow two = insertLevel(2);
            OrderingRow nine = insertLevel(9);
            insertLevel(10);
            WhereCondition greaterThanTen = condition("level", Comparison.GREATER, 10);

            two.setMarker("changed");
            boolean twoApplied = operator().updateIf(two, greaterThanTen);
            nine.setMarker("changed");
            boolean nineApplied = operator().updateIf(nine, greaterThanTen);
            Map<Integer, String> markers = new HashMap<>();
            for (OrderingRow stored : operator().getAll()) {
                markers.put(stored.getLevel(), stored.getMarker());
            }
            List<Integer> aboveNine = levelsOf(operator().getAll(condition("level", Comparison.GREATER, 9)));

            assertSoftly(softly -> {
                softly.assertThat(twoApplied).as("updateIf level GREATER 10 on the row holding 2").isFalse();
                softly.assertThat(nineApplied).as("updateIf level GREATER 10 on the row holding 9").isFalse();
                softly.assertThat(markers.get(2)).as("stored marker of the row holding 2").isEqualTo("initial");
                softly.assertThat(markers.get(9)).as("stored marker of the row holding 9").isEqualTo("initial");
                softly.assertThat(aboveNine).as("getAll level GREATER 9").containsExactly(10);
            });
        }

        @Test
        @DisplayName("getAll returns exactly the oracle's rows")
        void getAllFollowsTheOracle() {
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<OrderingRow> rows = insertFixture();
                    softly.assertThat(codesOf(operator().getAll(cond.condition())))
                            .as("getAll %s", cond)
                            .containsExactlyInAnyOrderElementsOf(expected(cond));
                    removeRows(operator(), rows);
                }
            });
        }

        @Test
        @DisplayName("exist is true exactly when the oracle has a row")
        void existFollowsTheOracle() {
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<OrderingRow> rows = insertFixture();
                    softly.assertThat(operator().exist(cond.condition()))
                            .as("exist %s", cond)
                            .isEqualTo(!expected(cond).isEmpty());
                    removeRows(operator(), rows);
                }
            });
        }

        @Test
        @DisplayName("page returns only the oracle's rows, and nothing after the last page")
        void pageFollowsTheOracle() {
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<OrderingRow> rows = insertFixture();
                    Set<String> expected = expected(cond);
                    WhereCondition condition = cond.condition();
                    // The operator issues no ORDER BY, so pages are compared as sets.
                    softly.assertThat(codesOf(operator().page(1, 100, condition)))
                            .as("page(1, 100) %s", cond)
                            .containsExactlyInAnyOrderElementsOf(expected);
                    List<String> first = codesOf(operator().page(1, 1, condition));
                    softly.assertThat(first).as("page(1, 1) size %s", cond)
                            .hasSize(Math.min(1, expected.size()));
                    softly.assertThat(expected).as("page(1, 1) member of the matches %s", cond)
                            .containsAll(first);
                    softly.assertThat(operator().page(expected.size() + 1, 1, condition))
                            .as("page after the last %s", cond)
                            .isEmpty();
                    removeRows(operator(), rows);
                }
            });
        }

        @Test
        @DisplayName("del removes exactly the oracle's rows")
        void delRemovesExactlyTheOracle() {
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<OrderingRow> rows = insertFixture();
                    operator().del(cond.condition());
                    softly.assertThat(codesOf(operator().getAll()))
                            .as("rows left after del %s", cond)
                            .containsExactlyInAnyOrderElementsOf(nonMatches(expected(cond)));
                    removeRows(operator(), rows);
                }
            });
        }

        @Test
        @DisplayName("updateIf applies and writes exactly for the oracle's rows")
        void updateIfWritesExactlyTheOracle() {
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<OrderingRow> rows = insertFixture();
                    Set<String> expected = expected(cond);
                    for (OrderingRow row : rows) {
                        row.setMarker("changed");
                        softly.assertThat(operator().updateIf(row, cond.condition()))
                                .as("updateIf %s on %s", cond, row.getCode())
                                .isEqualTo(expected.contains(row.getCode()));
                    }
                    List<String> changed = new ArrayList<>();
                    for (OrderingRow stored : operator().getAll()) {
                        if ("changed".equals(stored.getMarker())) {
                            changed.add(stored.getCode());
                        }
                    }
                    softly.assertThat(changed).as("rows written by updateIf %s", cond)
                            .containsExactlyInAnyOrderElementsOf(expected);
                    removeRows(operator(), rows);
                }
            });
        }

        @Test
        @DisplayName("Columns declared BIGINT and DOUBLE give the exact oracle")
        void numericDeclaredColumnsUnchanged() {
            DataOperator<TypedOrderingRow> typed = typedOperator();
            assertSoftly(softly -> {
                for (Cond cond : CONDITIONS) {
                    List<TypedOrderingRow> rows = insertFixture(typed, TypedOrderingRow::new);
                    softly.assertThat(codesOf(typed.getAll(cond.condition())))
                            .as("getAll on numeric-declared columns %s", cond)
                            .containsExactlyInAnyOrderElementsOf(exactMatches(cond));
                    softly.assertThat(typed.exist(cond.condition()))
                            .as("exist on numeric-declared columns %s", cond)
                            .isEqualTo(!exactMatches(cond).isEmpty());
                    removeRows(typed, rows);
                }
            });
        }

        @Test
        @DisplayName("String ordering and EQUAL keep their behaviour")
        void unchangedOperatorsKeepTheirBehaviour() {
            List<OrderingRow> rows = insertFixture();
            List<String> codeAboveTen = codesOf(operator().getAll(condition("code", Comparison.GREATER, "10")));
            List<String> levelTen = codesOf(operator().getAll(condition("level", Comparison.EQUAL, 10)));
            List<String> stampLarge = codesOf(operator().getAll(
                    condition("stamp", Comparison.EQUAL, TWO_TO_53 + 1)));
            removeRows(operator(), rows);

            assertSoftly(softly -> {
                softly.assertThat(codeAboveTen).as("code GREATER '10' on a String field compares text")
                        .containsExactlyInAnyOrder("2", "9");
                softly.assertThat(levelTen).as("EQUAL level 10").containsExactly("10");
                if (exactEqualityOnDefaultColumns()) {
                    softly.assertThat(stampLarge).as("EQUAL stamp 2^53+1").containsExactly("9");
                } else {
                    // Existing behaviour on MySQL text columns, recorded, not changed by #675.
                    softly.assertThat(stampLarge).as("EQUAL stamp 2^53+1 in double precision")
                            .containsExactlyInAnyOrder("10", "9");
                }
            });
        }
    }

    @Nested
    @DisplayName("Real SQLite (the driver Paper bundles)")
    class RealSqlite extends OrderingContract {
        @TempDir
        Path tempDir;

        private HikariDataSource pool;
        private SQLiteDataOperator<OrderingRow> operator;
        private SQLiteDataOperator<TypedOrderingRow> typedOperator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.realSqlite(tempDir.toFile());
            operator = new SQLiteDataOperator<>(pool, OrderingRow.class);
            typedOperator = new SQLiteDataOperator<>(pool, TypedOrderingRow.class);
        }

        @AfterEach
        void close() {
            pool.close();
        }

        @Override
        DataOperator<OrderingRow> operator() {
            return operator;
        }

        @Override
        DataOperator<TypedOrderingRow> typedOperator() {
            return typedOperator;
        }

        @Override
        boolean exactOnDefaultColumns() {
            return true;
        }

        /**
         * A {@code double} keeps the bound value on the parameter side (#675 review IN-04 and WR-01):
         * SQLite stores a double needing 16 or 17 significant digits with 15 in a default column, and
         * the entity reads back that 15-digit value, so GREATER and LESS compare the threshold with
         * what reads back. Rendering the parameter through text as well would make 1/3.0 compare as
         * equal to a row that reads back below it.
         */
        @Test
        @DisplayName("A double field compares the threshold with the value it reads back")
        void doubleFieldComparesTheValueItReadsBack() {
            OrderingRow row = new OrderingRow();
            row.apply(new Values(1, 1L, 1 / 3.0, 0.5f, "third"));
            row.setMarker("initial");
            operator().insert(row);
            OrderingRow stored = operator().getAll().get(0);
            double readBack = stored.getRatio();

            List<String> lessThanWritten = codesOf(operator().getAll(condition("ratio", Comparison.LESS, 1 / 3.0)));
            List<String> lessThanReadBack = codesOf(operator().getAll(condition("ratio", Comparison.LESS, readBack)));
            List<String> greaterThanReadBack = codesOf(operator().getAll(
                    condition("ratio", Comparison.GREATER, readBack)));
            stored.setMarker("changed");
            boolean applied = operator().updateIf(stored, condition("ratio", Comparison.LESS, readBack));

            assertSoftly(softly -> {
                softly.assertThat(readBack).as("1/3.0 as it reads back from a default column").isEqualTo(0.333333333333333);
                softly.assertThat(lessThanWritten).as("ratio LESS 1/3.0 (Java: %s < %s)", readBack, 1 / 3.0)
                        .containsExactly(Double.compare(readBack, 1 / 3.0) < 0 ? "third" : "none");
                softly.assertThat(lessThanReadBack).as("ratio LESS its own read-back value").isEmpty();
                softly.assertThat(greaterThanReadBack).as("ratio GREATER its own read-back value").isEmpty();
                softly.assertThat(applied).as("updateIf ratio LESS its own read-back value").isFalse();
            });
        }

        @Test
        @DisplayName("A table that already has TEXT columns keeps its definition and values, and compares by type")
        void existingTextTableKeepsDefinitionAndCompares() throws IOException, SQLException {
            Path directory = Files.createDirectories(tempDir.resolve("existing"));
            try (HikariDataSource existing = RelationalTestEngines.realSqlite(directory.toFile())) {
                try (Connection connection = existing.getConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + TABLE + " (id TEXT, level TEXT, stamp TEXT, ratio TEXT,"
                            + " weight TEXT, code TEXT, marker TEXT, PRIMARY KEY (id))");
                }
                Map<String, List<String>> inserted = new LinkedHashMap<>();
                try (Connection connection = existing.getConnection();
                     PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE
                             + " (id, level, stamp, ratio, weight, code, marker) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    int index = 0;
                    for (Values values : FIXTURE) {
                        // An explicit id, so the startup backfill of null ids (#546) has nothing to write.
                        String id = "00000000-0000-4000-8000-00000000000" + index++;
                        List<String> row = Arrays.asList(id, String.valueOf(values.level),
                                String.valueOf(values.stamp), String.valueOf(values.ratio),
                                String.valueOf(values.weight), values.code, "initial");
                        for (int i = 0; i < row.size(); i++) {
                            insert.setString(i + 1, row.get(i));
                        }
                        insert.executeUpdate();
                        inserted.put(id, row);
                    }
                }
                String definitionBefore = sqliteTableDefinition(existing);

                SQLiteDataOperator<OrderingRow> existingOperator = new SQLiteDataOperator<>(existing, OrderingRow.class);
                Map<String, List<String>> afterConstruction = rawRows(existing);
                List<String> types = rawTypes(existing);
                Map<String, Set<String>> actual = new LinkedHashMap<>();
                Map<String, Boolean> existActual = new LinkedHashMap<>();
                for (Cond cond : CONDITIONS) {
                    if ("level".equals(cond.field) || "stamp".equals(cond.field)) {
                        actual.put(cond.toString(), new LinkedHashSet<>(codesOf(existingOperator.getAll(cond.condition()))));
                        existActual.put(cond.toString(), existingOperator.exist(cond.condition()));
                    }
                }
                String definitionAfter = sqliteTableDefinition(existing);
                Map<String, List<String>> afterQueries = rawRows(existing);

                assertSoftly(softly -> {
                    softly.assertThat(definitionAfter).as("CREATE TABLE text after construction and queries")
                            .isEqualTo(definitionBefore)
                            .contains("level TEXT");
                    softly.assertThat(afterConstruction).as("raw ids and values after construction").isEqualTo(inserted);
                    softly.assertThat(afterQueries).as("raw ids and values after the queries").isEqualTo(inserted);
                    softly.assertThat(types).as("typeof of every numeric column of every row")
                            .hasSize(FIXTURE.size() * NUMERIC_FIELDS.size())
                            .containsOnly("text");
                    for (Cond cond : CONDITIONS) {
                        if ("level".equals(cond.field) || "stamp".equals(cond.field)) {
                            softly.assertThat(actual.get(cond.toString())).as("getAll on the TEXT table %s", cond)
                                    .containsExactlyInAnyOrderElementsOf(exactMatches(cond));
                            softly.assertThat(existActual.get(cond.toString())).as("exist on the TEXT table %s", cond)
                                    .isEqualTo(!exactMatches(cond).isEmpty());
                        }
                    }
                });
            }
        }
    }

    /** The {@code CREATE TABLE} text SQLite stored for {@link #TABLE}. */
    static String sqliteTableDefinition(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "";
            }
        }
    }

    /** Every raw row of {@link #TABLE}, by id, as the text SQLite returns for each column. */
    static Map<String, List<String>> rawRows(DataSource dataSource) throws SQLException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT id, level, stamp, ratio, weight, code, marker FROM " + TABLE + " ORDER BY id")) {
            while (resultSet.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= 7; i++) {
                    row.add(resultSet.getString(i));
                }
                rows.put(row.get(0), row);
            }
        }
        return rows;
    }

    /** {@code typeof} of every numeric column of every row of {@link #TABLE}. */
    static List<String> rawTypes(DataSource dataSource) throws SQLException {
        List<String> types = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT typeof(level), typeof(stamp), typeof(ratio),"
                     + " typeof(weight) FROM " + TABLE)) {
            while (resultSet.next()) {
                for (int i = 1; i <= 4; i++) {
                    types.add(resultSet.getString(i));
                }
            }
        }
        return types;
    }

    @Nested
    @DisplayName("SQLiteDataOperator over H2 in MySQL mode")
    class H2SqliteOperator extends OrderingContract {
        private HikariDataSource pool;
        private SQLiteDataOperator<OrderingRow> operator;
        private SQLiteDataOperator<TypedOrderingRow> typedOperator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("ordering_h2_sqlite_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new SQLiteDataOperator<>(pool, OrderingRow.class);
            typedOperator = new SQLiteDataOperator<>(pool, TypedOrderingRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            RelationalTestEngines.dropTableIfExists(pool, TYPED_TABLE);
            pool.close();
        }

        @Override
        DataOperator<OrderingRow> operator() {
            return operator;
        }

        @Override
        DataOperator<TypedOrderingRow> typedOperator() {
            return typedOperator;
        }

        @Override
        boolean exactOnDefaultColumns() {
            return true;
        }
    }

    @Nested
    @DisplayName("MysqlDataOperator over H2 in MySQL mode")
    class H2MysqlOperator extends OrderingContract {
        private HikariDataSource pool;
        private MysqlDataOperator<OrderingRow> operator;
        private MysqlDataOperator<TypedOrderingRow> typedOperator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("ordering_h2_mysql_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new MysqlDataOperator<>(pool, OrderingRow.class);
            typedOperator = new MysqlDataOperator<>(pool, TypedOrderingRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            RelationalTestEngines.dropTableIfExists(pool, TYPED_TABLE);
            pool.close();
        }

        @Override
        DataOperator<OrderingRow> operator() {
            return operator;
        }

        @Override
        DataOperator<TypedOrderingRow> typedOperator() {
            return typedOperator;
        }

        @Override
        boolean exactOnDefaultColumns() {
            // H2 converts a VARCHAR column to the parameter's numeric type, so it is exact without a cast.
            return true;
        }
    }

    /**
     * Real MySQL compares a default text column with a number in double precision: exact within
     * plus or minus 2^53, approximate beyond it. The maintainer decided to document this
     * ({@code mysql-large-integer-ordering: DOCUMENT}), so default columns follow the
     * double-precision oracle here, and the BIGINT-declared control follows the exact oracle.
     */
    @Nested
    @DisplayName("Real MySQL (throwaway local database)")
    @EnabledIfSystemProperty(named = RelationalTestEngines.MYSQL_URL_PROPERTY, matches = ".+")
    class RealMysql extends OrderingContract {
        private HikariDataSource pool;
        private MysqlDataOperator<OrderingRow> operator;
        private MysqlDataOperator<TypedOrderingRow> typedOperator;

        @BeforeEach
        void open() throws SQLException {
            pool = RelationalTestEngines.realMysql();
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            RelationalTestEngines.dropTableIfExists(pool, TYPED_TABLE);
            operator = new MysqlDataOperator<>(pool, OrderingRow.class);
            typedOperator = new MysqlDataOperator<>(pool, TypedOrderingRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            RelationalTestEngines.dropTableIfExists(pool, TYPED_TABLE);
            pool.close();
        }

        @Override
        DataOperator<OrderingRow> operator() {
            return operator;
        }

        @Override
        DataOperator<TypedOrderingRow> typedOperator() {
            return typedOperator;
        }

        @Override
        boolean exactOnDefaultColumns() {
            return false;
        }

        @Override
        boolean exactEqualityOnDefaultColumns() {
            return false;
        }
    }

    /** JSON's existing ordering, recorded: values are compared as {@code double}. */
    @Nested
    @DisplayName("JSON (existing double-precision ordering)")
    class Json {
        @TempDir
        Path tempDir;

        private SimpleJsonDataOperator<OrderingRow> operator;

        @BeforeEach
        void open() throws IOException {
            Path tableDirectory = Files.createDirectories(tempDir.resolve("json").resolve("rows"));
            operator = new SimpleJsonDataOperator<>(tableDirectory.toFile().getAbsolutePath(), OrderingRow.class);
        }

        @Test
        @DisplayName("GREATER and LESS on JSON follow the double-precision oracle")
        void jsonOrdersInDoublePrecision() {
            List<OrderingRow> rows = new ArrayList<>();
            for (Values values : FIXTURE) {
                OrderingRow row = new OrderingRow();
                row.apply(values);
                row.setMarker("initial");
                operator.insert(row);
                rows.add(row);
            }
            assertSoftly(softly -> {
                for (OrderingRow row : rows) {
                    softly.assertThat(row.getId()).as("operator-assigned id of %s", row.getCode())
                            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                }
                for (Cond cond : CONDITIONS) {
                    softly.assertThat(codesOf(operator.getAll(cond.condition())))
                            .as("JSON getAll %s", cond)
                            .containsExactlyInAnyOrderElementsOf(doubleMatches(cond));
                }
            });
        }
    }
}
