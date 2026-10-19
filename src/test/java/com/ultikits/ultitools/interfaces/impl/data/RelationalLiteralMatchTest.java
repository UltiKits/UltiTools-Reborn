package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.mysql.MysqlDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariDataSource;

/**
 * INCLUDE, STARTSWITH and ENDSWITH match their value literally on every backend (#675, frozen case
 * 5506b809): a value such as {@code world_} or {@code 100%} must not widen a read, a delete or a
 * conditional update.
 * <p>
 * The contract lives in {@link LiteralContract}; each nested lane runs it on one backend: real
 * SQLite (the driver Paper bundles), {@code SQLiteDataOperator} and {@code MysqlDataOperator} over
 * H2 in MySQL mode, real MySQL (only when {@value RelationalTestEngines#MYSQL_URL_PROPERTY} names a
 * throwaway local database), and JSON, which already matched literally and is the parity oracle.
 * Expected sets come from Java's {@code contains}, {@code startsWith} and {@code endsWith} over the
 * fixture, never from hand-written lists. The pins prove that {@code getLike}, {@code Query#like},
 * {@code EQUAL}, a {@code null} INCLUDE value and each backend's letter case are unchanged.
 */
@DisplayName("Literal INCLUDE / STARTSWITH / ENDSWITH on every backend (#675)")
class RelationalLiteralMatchTest {

    static final String TABLE = "literal_match_row";

    /** Every row the matrix stores, by label. Ids are always assigned by the operator. */
    static final List<String> FIXTURE = Collections.unmodifiableList(Arrays.asList(
            "world", "world_", "worldX", "world_1", "x21", "100%", "100X", "%", "_", "a!b", "aXb",
            "a\\b", "plain", "nullable"));

    /** The comparisons the matrix walks; the last two are controls (several matches, none). */
    static final List<Case> CASES = Collections.unmodifiableList(Arrays.asList(
            new Case(Comparison.STARTSWITH, "world_"),
            new Case(Comparison.INCLUDE, "_"),
            new Case(Comparison.ENDSWITH, "%"),
            new Case(Comparison.ENDSWITH, "0%"),
            new Case(Comparison.INCLUDE, "!"),
            new Case(Comparison.STARTSWITH, "a!"),
            new Case(Comparison.INCLUDE, "\\"),
            new Case(Comparison.INCLUDE, "a\\b"),
            new Case(Comparison.ENDSWITH, "_1"),
            new Case(Comparison.STARTSWITH, "%"),
            new Case(Comparison.INCLUDE, "orld"),
            new Case(Comparison.INCLUDE, "zzz")));

    static final String EXIST_PENDING = "Real-SQLite exist is pending the existence-count decision "
            + "(existence-count-fix in .planning/phases/20.5-relational-query-and-commit-outcome/20.5-DECISIONS.md): "
            + "exist reads COUNT(*) as a Long and sqlite-jdbc reports an Integer, so it throws ClassCastException "
            + "on real SQLite for every condition. Plan 20.5-02 removes this assumption.";

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

    /** One comparison and value of the matrix. */
    static final class Case {
        final Comparison comparison;
        final String value;

        Case(Comparison comparison, String value) {
            this.comparison = comparison;
            this.value = value;
        }

        WhereCondition condition() {
            return RelationalLiteralMatchTest.condition(comparison, value);
        }

        @Override
        public String toString() {
            return comparison + " '" + value + "'";
        }
    }

    @BeforeAll
    static void mockServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            Bukkit.setServer(mockServer);
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

    /** The oracle: the fixture labels a literal reading of {@code testCase} matches, in Java. */
    static Set<String> literalMatches(Case testCase) {
        Set<String> matches = new LinkedHashSet<>();
        for (String label : FIXTURE) {
            boolean match;
            switch (testCase.comparison) {
                case INCLUDE:
                    match = label.contains(testCase.value);
                    break;
                case STARTSWITH:
                    match = label.startsWith(testCase.value);
                    break;
                case ENDSWITH:
                    match = label.endsWith(testCase.value);
                    break;
                default:
                    throw new IllegalArgumentException("Not a substring comparison: " + testCase.comparison);
            }
            if (match) {
                matches.add(label);
            }
        }
        return matches;
    }

    /**
     * The fixture labels a plain {@code LIKE} would match if the value were wrapped and read as a
     * pattern ({@code %} any run, {@code _} any one character, no escape) -- the defect's reading.
     */
    static Set<String> patternReading(Case testCase) {
        String wrapped;
        switch (testCase.comparison) {
            case INCLUDE:
                wrapped = "%" + testCase.value + "%";
                break;
            case STARTSWITH:
                wrapped = testCase.value + "%";
                break;
            case ENDSWITH:
                wrapped = "%" + testCase.value;
                break;
            default:
                throw new IllegalArgumentException("Not a substring comparison: " + testCase.comparison);
        }
        StringBuilder regex = new StringBuilder();
        for (char c : wrapped.toCharArray()) {
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
        Set<String> matches = new LinkedHashSet<>();
        for (String label : FIXTURE) {
            if (pattern.matcher(label).matches()) {
                matches.add(label);
            }
        }
        return matches;
    }

    /** The fixture labels not in {@code matches}. */
    static Set<String> nonMatches(Set<String> matches) {
        Set<String> rest = new LinkedHashSet<>(FIXTURE);
        rest.removeAll(matches);
        return rest;
    }

    @Test
    @DisplayName("No wildcard case can pass vacuously: its literal set differs from the pattern reading")
    void everyWildcardCaseDiffersFromItsPatternReading() {
        assertSoftly(softly -> {
            int wildcardCases = 0;
            for (Case testCase : CASES) {
                if (testCase.value.indexOf('%') >= 0 || testCase.value.indexOf('_') >= 0) {
                    wildcardCases++;
                    softly.assertThat(literalMatches(testCase)).as("literal vs pattern reading of %s", testCase)
                            .isNotEqualTo(patternReading(testCase));
                }
                if (!"zzz".equals(testCase.value)) {
                    softly.assertThat(literalMatches(testCase)).as("non-control case %s matches something", testCase)
                            .isNotEmpty();
                }
            }
            softly.assertThat(wildcardCases).as("cases holding % or _").isEqualTo(6);
            softly.assertThat(literalMatches(new Case(Comparison.INCLUDE, "orld"))).as("control orld").hasSize(4);
            softly.assertThat(literalMatches(new Case(Comparison.INCLUDE, "zzz"))).as("control zzz").isEmpty();
        });
    }

    /** The contract every lane runs. */
    abstract class LiteralContract {

        /** The operator under test, built fresh for each test by the lane. */
        abstract DataOperator<LiteralRow> operator();

        /** Whether the lane is a relational operator (the pins differ for JSON). */
        boolean relational() {
            return true;
        }

        /** Whether the backend's LIKE ignores letter case. */
        abstract boolean caseInsensitive();

        /** Why this lane skips the exist builder, or {@code null} when it runs it. */
        String existSkipReason() {
            return null;
        }

        /**
         * The table's stored definition, read from the engine, or an empty string when the lane
         * has no table definition to read.
         */
        String tableDefinition() throws SQLException {
            return "";
        }

        List<LiteralRow> insertRows(List<String> labels) {
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

        List<LiteralRow> insertFixture() {
            return insertRows(FIXTURE);
        }

        /** Removes the given rows through delById, by the ids the operator assigned at insert. */
        void removeRows(List<LiteralRow> rows) {
            for (LiteralRow row : rows) {
                operator().delById(row.getId());
            }
        }

        @Test
        @DisplayName("STARTSWITH world_ reads and deletes only rows that literally start with world_")
        void startsWithUnderscoreIsLiteral() throws SQLException {
            String definitionBefore = tableDefinition();
            insertRows(Arrays.asList("world_", "worldX", "world_1"));
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

        @Test
        @DisplayName("getAll returns exactly the literal matches")
        void getAllMatchesLiterally() {
            assertSoftly(softly -> {
                for (Case testCase : CASES) {
                    List<LiteralRow> rows = insertFixture();
                    softly.assertThat(labelsOf(operator().getAll(testCase.condition())))
                            .as("getAll %s", testCase)
                            .containsExactlyInAnyOrderElementsOf(literalMatches(testCase));
                    removeRows(rows);
                }
            });
        }

        @Test
        @DisplayName("exist is true exactly when a literal match exists")
        void existMatchesLiterally() {
            String skipReason = existSkipReason();
            assumeTrue(skipReason == null, skipReason);
            assertSoftly(softly -> {
                for (Case testCase : CASES) {
                    List<LiteralRow> rows = insertFixture();
                    softly.assertThat(operator().exist(testCase.condition()))
                            .as("exist %s", testCase)
                            .isEqualTo(!literalMatches(testCase).isEmpty());
                    removeRows(rows);
                }
            });
        }

        @Test
        @DisplayName("page returns only literal matches, and nothing after the last page")
        void pageMatchesLiterally() {
            assertSoftly(softly -> {
                for (Case testCase : CASES) {
                    List<LiteralRow> rows = insertFixture();
                    Set<String> expected = literalMatches(testCase);
                    WhereCondition condition = testCase.condition();
                    // The operator issues no ORDER BY, so pages are compared as sets.
                    softly.assertThat(labelsOf(operator().page(1, 100, condition)))
                            .as("page(1, 100) %s", testCase)
                            .containsExactlyInAnyOrderElementsOf(expected);
                    List<String> first = labelsOf(operator().page(1, 1, condition));
                    softly.assertThat(first).as("page(1, 1) size %s", testCase)
                            .hasSize(Math.min(1, expected.size()));
                    softly.assertThat(expected).as("page(1, 1) member of the matches %s", testCase)
                            .containsAll(first);
                    softly.assertThat(operator().page(expected.size() + 1, 1, condition))
                            .as("page after the last %s", testCase)
                            .isEmpty();
                    removeRows(rows);
                }
            });
        }

        @Test
        @DisplayName("del removes exactly the literal matches")
        void delRemovesOnlyLiteralMatches() {
            assertSoftly(softly -> {
                for (Case testCase : CASES) {
                    List<LiteralRow> rows = insertFixture();
                    operator().del(testCase.condition());
                    softly.assertThat(labelsOf(operator().getAll()))
                            .as("rows left after del %s", testCase)
                            .containsExactlyInAnyOrderElementsOf(nonMatches(literalMatches(testCase)));
                    removeRows(rows);
                }
            });
        }

        @Test
        @DisplayName("updateIf applies and writes exactly for the literal matches")
        void updateIfWritesOnlyLiteralMatches() {
            assertSoftly(softly -> {
                for (Case testCase : CASES) {
                    List<LiteralRow> rows = insertFixture();
                    Set<String> expected = literalMatches(testCase);
                    for (LiteralRow row : rows) {
                        row.setMarker("changed");
                        softly.assertThat(operator().updateIf(row, testCase.condition()))
                                .as("updateIf %s on %s", testCase, row.getLabel())
                                .isEqualTo(expected.contains(row.getLabel()));
                    }
                    List<String> changed = new ArrayList<>();
                    for (LiteralRow stored : operator().getAll()) {
                        if ("changed".equals(stored.getMarker())) {
                            changed.add(stored.getLabel());
                        }
                    }
                    softly.assertThat(changed).as("rows written by updateIf %s", testCase)
                            .containsExactlyInAnyOrderElementsOf(expected);
                    removeRows(rows);
                }
            });
        }

        @Test
        @DisplayName("getLike, Query#like, EQUAL and a null INCLUDE value keep their behaviour")
        void unchangedOperatorsKeepTheirBehaviour() {
            List<LiteralRow> rows = insertFixture();
            List<String> getLike = labelsOf(operator().getLike("label", "world_", LikeType.START));
            List<String> queryLike = relational()
                    ? labelsOf(operator().query().where("label").like("world_%").list()) : null;
            List<String> equal = relational()
                    ? labelsOf(operator().getAll(condition(Comparison.EQUAL, "world_"))) : null;
            List<String> includeNull = relational()
                    ? labelsOf(operator().getAll(condition(Comparison.INCLUDE, null))) : null;
            removeRows(rows);

            assertSoftly(softly -> {
                if (relational()) {
                    softly.assertThat(getLike).as("getLike START world_ is a pattern")
                            .containsExactlyInAnyOrder("world_", "worldX", "world_1");
                    softly.assertThat(queryLike).as("Query#like world_% is a pattern")
                            .containsExactlyInAnyOrder("world_", "worldX", "world_1");
                    softly.assertThat(equal).as("EQUAL world_").containsExactly("world_");
                    softly.assertThat(includeNull).as("INCLUDE null renders the text null")
                            .containsExactly("nullable");
                } else {
                    softly.assertThat(getLike).as("JSON getLike START world_ (its existing literal result)")
                            .containsExactlyInAnyOrder("world_", "world_1");
                }
            });
        }

        @Test
        @DisplayName("Letter case follows each backend's existing behaviour")
        void letterCaseIsTheBackendsOwn() {
            List<LiteralRow> rows = insertFixture();
            List<String> upper = labelsOf(operator().getAll(condition(Comparison.INCLUDE, "WORLD")));
            removeRows(rows);

            assertSoftly(softly -> {
                if (caseInsensitive()) {
                    softly.assertThat(upper).as("INCLUDE WORLD on a case-insensitive backend")
                            .containsExactlyInAnyOrder("world", "world_", "worldX", "world_1");
                } else {
                    softly.assertThat(upper).as("INCLUDE WORLD on a case-sensitive backend").isEmpty();
                }
            });
        }
    }

    /** Column names and types of {@link #TABLE} as JDBC metadata reports them, in column order. */
    static String jdbcTableDefinition(DataSource dataSource) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), null, "%", "%")) {
                while (resultSet.next()) {
                    if (TABLE.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
                        columns.add(resultSet.getInt("ORDINAL_POSITION") + ":" + resultSet.getString("COLUMN_NAME")
                                + ":" + resultSet.getString("TYPE_NAME") + "(" + resultSet.getInt("COLUMN_SIZE") + ")");
                    }
                }
            }
        }
        Collections.sort(columns);
        return String.join(",", columns);
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
        boolean caseInsensitive() {
            return true;
        }

        @Override
        String existSkipReason() {
            return EXIST_PENDING;
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

    @Nested
    @DisplayName("SQLiteDataOperator over H2 in MySQL mode")
    class H2SqliteOperator extends LiteralContract {
        private HikariDataSource pool;
        private SQLiteDataOperator<LiteralRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("literal_h2_sqlite_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new SQLiteDataOperator<>(pool, LiteralRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<LiteralRow> operator() {
            return operator;
        }

        @Override
        boolean caseInsensitive() {
            return false;
        }

        @Override
        String tableDefinition() throws SQLException {
            return jdbcTableDefinition(pool);
        }
    }

    @Nested
    @DisplayName("MysqlDataOperator over H2 in MySQL mode")
    class H2MysqlOperator extends LiteralContract {
        private HikariDataSource pool;
        private MysqlDataOperator<LiteralRow> operator;

        @BeforeEach
        void open() {
            pool = RelationalTestEngines.h2MysqlMode("literal_h2_mysql_" + UUID.randomUUID().toString().replace("-", ""));
            operator = new MysqlDataOperator<>(pool, LiteralRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<LiteralRow> operator() {
            return operator;
        }

        @Override
        boolean caseInsensitive() {
            return false;
        }

        @Override
        String tableDefinition() throws SQLException {
            return jdbcTableDefinition(pool);
        }
    }

    @Nested
    @DisplayName("Real MySQL (throwaway local database)")
    @EnabledIfSystemProperty(named = RelationalTestEngines.MYSQL_URL_PROPERTY, matches = ".+")
    class RealMysql extends LiteralContract {
        private HikariDataSource pool;
        private MysqlDataOperator<LiteralRow> operator;

        @BeforeEach
        void open() throws SQLException {
            pool = RelationalTestEngines.realMysql();
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            operator = new MysqlDataOperator<>(pool, LiteralRow.class);
        }

        @AfterEach
        void close() throws SQLException {
            RelationalTestEngines.dropTableIfExists(pool, TABLE);
            pool.close();
        }

        @Override
        DataOperator<LiteralRow> operator() {
            return operator;
        }

        @Override
        boolean caseInsensitive() {
            return true;
        }

        @Override
        String tableDefinition() throws SQLException {
            return jdbcTableDefinition(pool);
        }
    }

    @Nested
    @DisplayName("JSON (the parity oracle)")
    class Json extends LiteralContract {
        @TempDir
        Path tempDir;

        private SimpleJsonDataOperator<LiteralRow> operator;

        @BeforeEach
        void open() throws IOException {
            Path tableDirectory = Files.createDirectories(tempDir.resolve("json").resolve("rows"));
            operator = new SimpleJsonDataOperator<>(tableDirectory.toFile().getAbsolutePath(), LiteralRow.class);
        }

        @Override
        DataOperator<LiteralRow> operator() {
            return operator;
        }

        @Override
        boolean relational() {
            return false;
        }

        @Override
        boolean caseInsensitive() {
            return false;
        }
    }
}
