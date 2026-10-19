package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * GREATER and LESS follow the entity field's numeric Java type (#675, frozen case 611b6f58): a
 * numeric field with the default {@code @Column} type is a {@code VARCHAR(255)} column, which
 * SQLite gives TEXT affinity, so without a typed comparison {@code 2 > 10} is true there and
 * {@code updateIf} writes although its numeric precondition is false.
 * <p>
 * The contract lives in {@link OrderingContract}; each nested lane runs it on one backend.
 */
@DisplayName("Typed GREATER / LESS ordering on every backend (#675)")
class RelationalOrderingContractTest {

    static final String TABLE = "ordering_row";

    /** One stored row; every numeric column keeps the default {@code @Column} type. */
    @Table(TABLE)
    public static class OrderingRow extends BaseDataEntity<String> {
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

        public OrderingRow() {
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

        public void setStamp(Long stamp) {
            this.stamp = stamp;
        }

        public double getRatio() {
            return ratio;
        }

        public void setRatio(double ratio) {
            this.ratio = ratio;
        }

        public Float getWeight() {
            return weight;
        }

        public void setWeight(Float weight) {
            this.weight = weight;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMarker() {
            return marker;
        }

        public void setMarker(String marker) {
            this.marker = marker;
        }
    }

    static WhereCondition condition(String column, Comparison comparison, Object value) {
        return WhereCondition.builder().column(column).comparison(comparison).value(value).build();
    }

    static List<Integer> levelsOf(List<OrderingRow> rows) {
        List<Integer> levels = new ArrayList<>();
        for (OrderingRow row : rows) {
            levels.add(row.getLevel());
        }
        return levels;
    }

    /** The contract every lane runs. */
    abstract class OrderingContract {

        /** The operator under test, built fresh for each test by the lane. */
        abstract DataOperator<OrderingRow> operator();

        OrderingRow insertLevel(int level) {
            OrderingRow row = new OrderingRow();
            row.setLevel(level);
            row.setStamp((long) level);
            row.setRatio(level);
            row.setWeight((float) level);
            row.setCode(String.valueOf(level));
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
    }

    @Nested
    @DisplayName("Real SQLite (the driver Paper bundles)")
    class RealSqlite extends OrderingContract {
        @TempDir
        Path tempDir;

        private HikariDataSource pool;
        private SQLiteDataOperator<OrderingRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.realSqlite(tempDir.toFile());
            operator = new SQLiteDataOperator<>(pool, OrderingRow.class);
        }

        @AfterEach
        void close() {
            pool.close();
        }

        @Override
        DataOperator<OrderingRow> operator() {
            return operator;
        }
    }
}
