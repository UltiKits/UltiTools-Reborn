package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@code Query#delete()} returns the number of rows the backend actually removed (#521). It used
 * to return how many rows the query matched, and to skip a matched row with a null id while still
 * counting it.
 */
@DisplayName("Query#delete() returns the rows actually removed (#521)")
class QueryDeleteCountTest {

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    @Table("query_delete_entity")
    public static class DeleteEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        @Column("score")
        private int score;

        public DeleteEntity() {
        }

        public DeleteEntity(String name, int score) {
            this.name = name;
            this.score = score;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
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
        config.setJdbcUrl("jdbc:h2:mem:querydelete;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // No setPassword: the in-memory database is created without one on first connect.
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void dropTable() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS query_delete_entity");
        }
    }

    private SimpleJsonDataOperator<DeleteEntity> json() {
        return new SimpleJsonDataOperator<>(tempDir.toFile().getAbsolutePath(), DeleteEntity.class);
    }

    private static void seed(DataOperator<DeleteEntity> operator) {
        operator.insert(new DeleteEntity("low-a", 1));
        operator.insert(new DeleteEntity("low-b", 2));
        operator.insert(new DeleteEntity("high", 9));
    }

    @Nested
    @DisplayName("the count equals the rows removed")
    class CountEqualsRowsRemoved {

        @Test
        @DisplayName("JSON: delete() returns 2 for two removed rows and a re-query finds neither")
        void jsonReturnsRemovedCount() {
            SimpleJsonDataOperator<DeleteEntity> operator = json();
            seed(operator);

            int removed = operator.query().where("score").lt(5).delete();

            assertThat(removed).isEqualTo(2);
            assertThat(operator.query().where("score").lt(5).list()).isEmpty();
            assertThat(operator.getAll()).extracting(DeleteEntity::getName).containsExactly("high");
        }

        @Test
        @DisplayName("SQLite: delete() returns 2 for two removed rows and a re-query finds neither")
        void sqliteReturnsRemovedCount() {
            SQLiteDataOperator<DeleteEntity> operator = new SQLiteDataOperator<>(dataSource, DeleteEntity.class);
            seed(operator);

            int removed = operator.query().where("score").lt(5).delete();

            assertThat(removed).isEqualTo(2);
            assertThat(operator.query().where("score").lt(5).list()).isEmpty();
            assertThat(operator.getAll()).extracting(DeleteEntity::getName).containsExactly("high");
        }

        @Test
        @DisplayName("JSON: a matched row removed by someone else before delete() runs is not counted")
        void jsonDoesNotCountARowItDidNotRemove() {
            SimpleJsonDataOperator<DeleteEntity> operator =
                    new SimpleJsonDataOperator<DeleteEntity>(tempDir.toFile().getAbsolutePath(), DeleteEntity.class) {
                        @Override
                        public List<DeleteEntity> getAll() {
                            List<DeleteEntity> all = super.getAll();
                            // A concurrent writer removes one matched row between the read and the delete.
                            super.delById(all.stream().filter(e -> "low-a".equals(e.getName())).findFirst()
                                    .orElseThrow(IllegalStateException::new).getId());
                            return all;
                        }
                    };
            seed(operator);

            assertThat(operator.query().where("score").lt(5).delete())
                    .as("delete() counted a row another writer had already removed")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("SQLite: a matched row removed by someone else before delete() runs is not counted")
        void sqliteDoesNotCountARowItDidNotRemove() {
            SQLiteDataOperator<DeleteEntity> operator =
                    new SQLiteDataOperator<DeleteEntity>(dataSource, DeleteEntity.class) {
                        @Override
                        public List<DeleteEntity> getAll() {
                            List<DeleteEntity> all = super.getAll();
                            super.delById(all.stream().filter(e -> "low-a".equals(e.getName())).findFirst()
                                    .orElseThrow(IllegalStateException::new).getId());
                            return all;
                        }
                    };
            seed(operator);

            assertThat(operator.query().where("score").lt(5).delete())
                    .as("delete() counted a row another writer had already removed")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an operator whose delete removes nothing is reported as 0, not as the match count")
        void foreignOperatorThatRemovesNothingCountsZero() {
            @SuppressWarnings("unchecked")
            DataOperator<DeleteEntity> operator = mock(DataOperator.class);
            DeleteEntity row = new DeleteEntity("low-a", 1);
            row.setId("row-1");
            List<DeleteEntity> rows = new ArrayList<>(Collections.singletonList(row));
            when(operator.getAll()).thenReturn(rows);
            // delById is a no-op on this mock; the row is still there afterwards.
            when(operator.exist(any(WhereCondition[].class))).thenReturn(true);

            assertThat(new QueryImpl<>(operator).delete()).isZero();
        }
    }

    @Nested
    @DisplayName("an operator that does not report affected rows")
    class UncountedOperator {

        @Test
        @DisplayName("a matched row another writer removed before this delete is not counted")
        void rowGoneBeforeTheDeleteIsNotCounted() {
            @SuppressWarnings("unchecked")
            DataOperator<DeleteEntity> operator = mock(DataOperator.class);
            DeleteEntity row = new DeleteEntity("low-a", 1);
            row.setId("row-1");
            when(operator.getAll()).thenReturn(new ArrayList<>(Collections.singletonList(row)));
            // Gone already when delete() looks, and still gone afterwards: this delete removed nothing.
            when(operator.exist(any(WhereCondition[].class))).thenReturn(false);

            assertThat(new QueryImpl<>(operator).delete()).isZero();
        }

        @Test
        @DisplayName("a row present before and gone after its delById is counted")
        void rowRemovedByThisDeleteIsCounted() {
            @SuppressWarnings("unchecked")
            DataOperator<DeleteEntity> operator = mock(DataOperator.class);
            DeleteEntity row = new DeleteEntity("low-a", 1);
            row.setId("row-1");
            when(operator.getAll()).thenReturn(new ArrayList<>(Collections.singletonList(row)));
            when(operator.exist(any(WhereCondition[].class))).thenReturn(true, false);

            assertThat(new QueryImpl<>(operator).delete()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a matched row with a null id")
    class NullIdRow {

        private void writeRowWithoutId(String fileName) throws IOException {
            File file = new File(tempDir.toFile(), fileName + ".json");
            Files.write(file.toPath(), "{\"name\":\"no-id\",\"score\":1}".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("JSON: delete() refuses it with DataAccessException naming the type, and deletes nothing")
        void jsonRefusesNullIdRow() throws IOException {
            writeRowWithoutId("legacy");
            SimpleJsonDataOperator<DeleteEntity> operator = json();
            operator.insert(new DeleteEntity("low-b", 2));

            assertThatThrownBy(() -> operator.query().where("score").lt(5).delete())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(DeleteEntity.class.getName());
            assertThat(operator.query().where("score").lt(5).list())
                    .as("a refused delete() must not have removed the other matched row")
                    .hasSize(2);
        }

        @Test
        @DisplayName("any operator: delete() refuses a matched null-id row before calling delById")
        void refusesBeforeDeletingAnything() {
            @SuppressWarnings("unchecked")
            DataOperator<DeleteEntity> operator = mock(DataOperator.class);
            DeleteEntity withId = new DeleteEntity("low-a", 1);
            withId.setId("row-1");
            DeleteEntity withoutId = new DeleteEntity("low-b", 2);
            List<DeleteEntity> rows = new ArrayList<>();
            rows.add(withId);
            rows.add(withoutId);
            when(operator.getAll()).thenReturn(rows);

            assertThatThrownBy(() -> new QueryImpl<>(operator).delete())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(DeleteEntity.class.getName());
            verify(operator, never()).delById(any());
        }
    }
}
