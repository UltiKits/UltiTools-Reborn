package com.ultikits.ultitools.interfaces.impl.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@DisplayName("QueryImpl Tests")
class QueryImplTest {

    private DataOperator<TestEntity> operator;
    private Query<TestEntity> query;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        operator = Mockito.mock(DataOperator.class);
        query = new QueryImpl<>(operator);
    }

    // === Test Entity ===

    @Data
    @EqualsAndHashCode(callSuper = true)
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestEntity extends AbstractDataEntity {
        @Column("player_name")
        private String playerName;

        @Column("score")
        private int score;

        @Column("balance")
        private double balance;

        static TestEntity of(Object id, String name, int score, double balance) {
            TestEntity e = new TestEntity(name, score, balance);
            e.setId(id);
            return e;
        }
    }

    // === Helper ===

    private List<TestEntity> sampleData() {
        List<TestEntity> list = new ArrayList<>();
        list.add(TestEntity.of(1, "Alice", 100, 50.0));
        list.add(TestEntity.of(2, "Bob", 200, 30.0));
        list.add(TestEntity.of(3, "Charlie", 150, 80.0));
        list.add(TestEntity.of(4, "Diana", 300, 10.0));
        list.add(TestEntity.of(5, "Eve", 100, 60.0));
        return list;
    }

    // === Equality Conditions (delegated to DataOperator) ===

    @Nested
    @DisplayName("Equality Conditions")
    class EqualityConditions {

        @Test
        @DisplayName("Should delegate eq condition to DataOperator.getAll()")
        void shouldDelegateEqToDataOperator() {
            TestEntity alice = TestEntity.of(1, "Alice", 100, 50.0);
            when(operator.getAll(any(WhereCondition[].class)))
                    .thenReturn(Collections.singletonList(alice));

            List<TestEntity> results = query.where("player_name").eq("Alice").list();

            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0).getPlayerName());
            verify(operator).getAll(any(WhereCondition[].class));
        }

        @Test
        @DisplayName("Should delegate multiple eq conditions")
        void shouldDelegateMultipleEqConditions() {
            TestEntity alice = TestEntity.of(1, "Alice", 100, 50.0);
            when(operator.getAll(any(WhereCondition[].class)))
                    .thenReturn(Collections.singletonList(alice));

            List<TestEntity> results = query
                    .where("player_name").eq("Alice")
                    .and("score").eq(100)
                    .list();

            assertEquals(1, results.size());
            verify(operator).getAll(any(WhereCondition[].class));
        }

        @Test
        @DisplayName("Should return empty list when no matches")
        void shouldReturnEmptyWhenNoMatches() {
            when(operator.getAll(any(WhereCondition[].class)))
                    .thenReturn(Collections.emptyList());

            List<TestEntity> results = query.where("player_name").eq("Nobody").list();

            assertTrue(results.isEmpty());
        }
    }

    // === Non-Equality Operators (in-memory filters) ===

    @Nested
    @DisplayName("Non-Equality Operators")
    class NonEqualityOperators {

        @Test
        @DisplayName("ne() should filter out matching values")
        void neFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("player_name").ne("Alice").list();

            assertEquals(4, results.size());
            assertTrue(results.stream().noneMatch(e -> "Alice".equals(e.getPlayerName())));
        }

        @Test
        @DisplayName("gt() should return entities with field greater than value")
        void gtFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("score").gt(150).list();

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.getScore() > 150));
        }

        @Test
        @DisplayName("lt() should return entities with field less than value")
        void ltFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("score").lt(150).list();

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.getScore() < 150));
        }

        @Test
        @DisplayName("gte() should return entities with field >= value")
        void gteFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("score").gte(150).list();

            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(e -> e.getScore() >= 150));
        }

        @Test
        @DisplayName("lte() should return entities with field <= value")
        void lteFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("score").lte(150).list();

            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(e -> e.getScore() <= 150));
        }

        @Test
        @DisplayName("like() should match SQL LIKE pattern with %")
        void likeFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            // Starts with "Al"
            List<TestEntity> results = query.where("player_name").like("Al%").list();

            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0).getPlayerName());
        }

        @Test
        @DisplayName("like() should match contains pattern")
        void likeContainsTest() {
            when(operator.getAll()).thenReturn(sampleData());

            // Contains "li"
            List<TestEntity> results = query.where("player_name").like("%li%").list();

            assertEquals(2, results.size()); // Alice, Charlie
        }

        @Test
        @DisplayName("like() should be case-insensitive")
        void likeCaseInsensitiveTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("player_name").like("alice").list();

            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0).getPlayerName());
        }

        @Test
        @DisplayName("in() should match values in collection")
        void inFilterTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query
                    .where("player_name").in(Arrays.asList("Alice", "Bob", "Eve"))
                    .list();

            assertEquals(3, results.size());
        }

        @Test
        @DisplayName("in() with empty collection should return no results")
        void inEmptyCollectionTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query
                    .where("player_name").in(Collections.emptyList())
                    .list();

            assertTrue(results.isEmpty());
        }
    }

    // === Combined Conditions ===

    @Nested
    @DisplayName("Combined Conditions")
    class CombinedConditions {

        @Test
        @DisplayName("eq + gt should combine equality and range filter")
        void eqPlusGtTest() {
            // eq("score", 100) returns Alice(100) and Eve(100)
            List<TestEntity> eqResults = new ArrayList<>();
            eqResults.add(TestEntity.of(1, "Alice", 100, 50.0));
            eqResults.add(TestEntity.of(5, "Eve", 100, 60.0));
            when(operator.getAll(any(WhereCondition[].class))).thenReturn(eqResults);

            List<TestEntity> results = query
                    .where("score").eq(100)
                    .and("balance").gt(55.0)
                    .list();

            assertEquals(1, results.size());
            assertEquals("Eve", results.get(0).getPlayerName());
        }

        @Test
        @DisplayName("Multiple non-eq filters should all apply (AND logic)")
        void multipleFiltersAndLogicTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query
                    .where("score").gt(100)
                    .and("balance").lt(50.0)
                    .list();

            // Bob(200, 30.0) and Diana(300, 10.0)
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> e.getScore() > 100 && e.getBalance() < 50.0));
        }
    }

    // === Ordering ===

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("orderBy() should sort ascending")
        void orderByAscTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.orderBy("score").list();

            assertEquals(5, results.size());
            // scores: 100, 100, 150, 200, 300
            assertTrue(results.get(0).getScore() <= results.get(1).getScore());
            assertTrue(results.get(1).getScore() <= results.get(2).getScore());
            assertTrue(results.get(2).getScore() <= results.get(3).getScore());
            assertTrue(results.get(3).getScore() <= results.get(4).getScore());
        }

        @Test
        @DisplayName("orderByDesc() should sort descending")
        void orderByDescTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.orderByDesc("score").list();

            assertEquals(5, results.size());
            assertEquals(300, results.get(0).getScore());
            assertEquals(200, results.get(1).getScore());
            assertEquals(150, results.get(2).getScore());
        }

        @Test
        @DisplayName("orderBy() with String column should sort alphabetically")
        void orderByStringTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.orderBy("player_name").list();

            assertEquals("Alice", results.get(0).getPlayerName());
            assertEquals("Bob", results.get(1).getPlayerName());
            assertEquals("Charlie", results.get(2).getPlayerName());
            assertEquals("Diana", results.get(3).getPlayerName());
            assertEquals("Eve", results.get(4).getPlayerName());
        }
    }

    // === Pagination ===

    @Nested
    @DisplayName("Pagination")
    class Pagination {

        @Test
        @DisplayName("limit() should restrict result count")
        void limitTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.limit(3).list();

            assertEquals(3, results.size());
        }

        @Test
        @DisplayName("offset() should skip results")
        void offsetTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.orderBy("score").offset(2).list();

            assertEquals(3, results.size());
            // After sorting by score: [100,100,150,200,300], offset 2 → [150,200,300]
            assertTrue(results.get(0).getScore() >= 150);
        }

        @Test
        @DisplayName("limit + offset should paginate correctly")
        void limitAndOffsetTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query
                    .orderBy("score")
                    .offset(1)
                    .limit(2)
                    .list();

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("offset beyond result size should return empty")
        void offsetBeyondSizeTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.offset(100).list();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("limit(0) should return empty list")
        void limitZeroTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.limit(0).list();

            assertTrue(results.isEmpty());
        }
    }

    // === Terminal Operations ===

    @Nested
    @DisplayName("Terminal Operations")
    class TerminalOperations {

        @Test
        @DisplayName("first() should return first matching entity")
        void firstTest() {
            when(operator.getAll()).thenReturn(sampleData());

            TestEntity result = query.orderBy("player_name").first();

            assertNotNull(result);
            assertEquals("Alice", result.getPlayerName());
        }

        @Test
        @DisplayName("first() should return null when no results")
        void firstNullTest() {
            when(operator.getAll()).thenReturn(Collections.emptyList());

            TestEntity result = query.where("player_name").ne("anything").first();

            assertNull(result);
        }

        @Test
        @DisplayName("exists() should return true when results exist")
        void existsTrueTest() {
            when(operator.getAll()).thenReturn(sampleData());

            assertTrue(query.where("score").gt(200).exists());
        }

        @Test
        @DisplayName("exists() should return false when no results")
        void existsFalseTest() {
            when(operator.getAll()).thenReturn(sampleData());

            assertFalse(query.where("score").gt(500).exists());
        }

        @Test
        @DisplayName("exists() with only eq conditions should use DataOperator.exist()")
        void existsDelegatesForEqOnly() {
            when(operator.exist(any(WhereCondition[].class))).thenReturn(true);

            boolean result = query.where("player_name").eq("Alice").exists();

            assertTrue(result);
            verify(operator).exist(any(WhereCondition[].class));
        }

        @Test
        @DisplayName("count() should return number of matching entities")
        void countTest() {
            when(operator.getAll()).thenReturn(sampleData());

            long count = query.where("score").gte(150).count();

            assertEquals(3, count);
        }

        @Test
        @DisplayName("delete() should delete matching entities and return count")
        void deleteTest() {
            when(operator.getAll()).thenReturn(sampleData());

            int deleted = query.where("score").lt(150).delete();

            assertEquals(2, deleted);
            verify(operator, times(2)).delById(any());
        }

        @Test
        @DisplayName("delete() with no matches should return 0")
        void deleteNoMatchesTest() {
            when(operator.getAll()).thenReturn(sampleData());

            int deleted = query.where("score").gt(500).delete();

            assertEquals(0, deleted);
            verify(operator, never()).delById(any());
        }
    }

    // === Field Resolution ===

    @Nested
    @DisplayName("Field Resolution")
    class FieldResolution {

        @Test
        @DisplayName("Should resolve field by @Column annotation value")
        void resolveByColumnAnnotation() {
            when(operator.getAll()).thenReturn(sampleData());

            // "player_name" is the @Column value, "playerName" is the field name
            List<TestEntity> results = query.where("player_name").ne("Alice").list();

            assertEquals(4, results.size());
        }

        @Test
        @DisplayName("Should resolve field by Java field name")
        void resolveByFieldName() {
            when(operator.getAll()).thenReturn(sampleData());

            // "playerName" is the Java field name
            List<TestEntity> results = query.where("playerName").ne("Alice").list();

            assertEquals(4, results.size());
        }

        @Test
        @DisplayName("Should resolve id field from parent class")
        void resolveIdFromParent() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.where("id").ne(1).list();

            assertEquals(4, results.size());
        }
    }

    // === Edge Cases ===

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Query with no conditions should return all records")
        void noConditionsReturnsAll() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query.list();

            assertEquals(5, results.size());
        }

        @Test
        @DisplayName("Empty dataset should return empty results")
        void emptyDataset() {
            when(operator.getAll()).thenReturn(Collections.emptyList());

            List<TestEntity> results = query.list();

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("Query should be composable with filter + order + limit")
        void fullCompositionTest() {
            when(operator.getAll()).thenReturn(sampleData());

            List<TestEntity> results = query
                    .where("score").gte(100)
                    .and("balance").gt(20.0)
                    .orderByDesc("balance")
                    .limit(3)
                    .list();

            // Entities with score>=100 AND balance>20: Alice(100,50), Bob(200,30), Charlie(150,80), Eve(100,60)
            // Ordered by balance desc: Charlie(80), Eve(60), Alice(50), Bob(30)
            // Limit 3: Charlie, Eve, Alice
            assertEquals(3, results.size());
            assertEquals("Charlie", results.get(0).getPlayerName());
            assertEquals("Eve", results.get(1).getPlayerName());
            assertEquals("Alice", results.get(2).getPlayerName());
        }

        @Test
        @DisplayName("Numeric comparison across int and double types should work")
        void crossTypeNumericComparison() {
            when(operator.getAll()).thenReturn(sampleData());

            // Compare int field with double value
            List<TestEntity> results = query.where("score").gt(150.0).list();

            assertEquals(2, results.size()); // Bob(200), Diana(300)
        }
    }
}
