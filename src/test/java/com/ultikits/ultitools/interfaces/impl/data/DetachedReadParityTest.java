package com.ultikits.ultitools.interfaces.impl.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.DataOperator.LikeType;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;
import com.ultikits.ultitools.interfaces.impl.data.sqlite.SQLiteDataOperator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Every read path hands out a detached copy on every backend (#522): changing a returned entity
 * without calling {@code update(...)} changes nothing stored, and calling {@code update(...)}
 * does. The JSON store used to hand out the instances it caches, so the same module code had
 * different persistence semantics on {@code datasource.type: json} and on SQLite/MySQL.
 * <p>
 * Each test runs the same scenario on the JSON backend and on the relational backend (H2 in
 * MySQL mode standing in for SQLite, as in {@code BackendIdContractTest}), so a divergence
 * between the two is what fails.
 */
@DisplayName("Read paths return detached copies on every backend (#522)")
class DetachedReadParityTest {

    private static DataSource dataSource;

    @TempDir
    Path tempDir;

    private final List<Backend> backends = new ArrayList<>();

    @Table("detached_read_entity")
    public static class ReadEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("name")
        private String name;

        public ReadEntity() {
        }

        public ReadEntity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class Backend {
        private final String label;
        private final DataOperator<ReadEntity> operator;

        private Backend(String label, DataOperator<ReadEntity> operator) {
            this.label = label;
            this.operator = operator;
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
        config.setJdbcUrl("jdbc:h2:mem:detachedread;DB_CLOSE_DELAY=-1;MODE=MySQL");
        config.setUsername("sa");
        // No setPassword: the in-memory database is created without one on first connect.
        dataSource = new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS detached_read_entity");
        }
        backends.clear();
        backends.add(new Backend("json",
                new SimpleJsonDataOperator<>(tempDir.toFile().getAbsolutePath(), ReadEntity.class)));
        backends.add(new Backend("sqlite", new SQLiteDataOperator<>(dataSource, ReadEntity.class)));
    }

    /**
     * Stores one entity named "stored" on the backend, reads it back through {@code read},
     * renames the returned instance without calling update, and returns what a fresh
     * {@code getById} then reports.
     */
    private String nameAfterMutatingWithoutUpdate(Backend backend, Function<DataOperator<ReadEntity>, ReadEntity> read) {
        ReadEntity entity = new ReadEntity("stored");
        backend.operator.insert(entity);
        String id = entity.getId();
        ReadEntity returned = read.apply(backend.operator);
        assertThat(returned).as("%s: the read path returned nothing", backend.label).isNotNull();
        returned.setName("mutated-without-update");
        return backend.operator.getById(id).getName();
    }

    private void assertEveryBackendKeepsTheStoredValue(String path, Function<DataOperator<ReadEntity>, ReadEntity> read) {
        for (Backend backend : backends) {
            assertThat(nameAfterMutatingWithoutUpdate(backend, read))
                    .as("%s: mutating an entity returned by %s changed the store without update()", backend.label, path)
                    .isEqualTo("stored");
        }
    }

    @Test
    @DisplayName("getAll() hands out a detached copy")
    void getAllReturnsDetachedCopies() {
        assertEveryBackendKeepsTheStoredValue("getAll()", op -> op.getAll().get(0));
    }

    @Test
    @DisplayName("getAll(conditions) hands out a detached copy")
    void getAllWithConditionsReturnsDetachedCopies() {
        assertEveryBackendKeepsTheStoredValue("getAll(conditions)",
                op -> op.getAll(WhereCondition.builder().column("name").value("stored").build()).get(0));
    }

    @Test
    @DisplayName("getById hands out a detached copy")
    void getByIdReturnsDetachedCopy() {
        assertEveryBackendKeepsTheStoredValue("getById", op -> op.getById(op.getAll().get(0).getId()));
    }

    @Test
    @DisplayName("page hands out a detached copy")
    void pageReturnsDetachedCopies() {
        assertEveryBackendKeepsTheStoredValue("page",
                op -> op.page(1, 10, WhereCondition.builder().column("name").value("stored").build()).get(0));
    }

    @Test
    @DisplayName("getLike hands out a detached copy")
    void getLikeReturnsDetachedCopies() {
        assertEveryBackendKeepsTheStoredValue("getLike", op -> op.getLike("name", "sto", LikeType.START).get(0));
    }

    @Test
    @DisplayName("query().first() hands out a detached copy")
    void queryFirstReturnsDetachedCopy() {
        assertEveryBackendKeepsTheStoredValue("query().first()", op -> op.query().where("name").eq("stored").first());
    }

    @Test
    @DisplayName("the entity passed to insert is not the stored instance either")
    void insertedInstanceIsDetached() {
        for (Backend backend : backends) {
            ReadEntity entity = new ReadEntity("stored");
            backend.operator.insert(entity);
            entity.setName("mutated-after-insert");
            assertThat(backend.operator.getById(entity.getId()).getName())
                    .as("%s: mutating the inserted instance changed the store without update()", backend.label)
                    .isEqualTo("stored");
        }
    }

    @Test
    @DisplayName("update(T) on a returned copy persists the change")
    void updatePersistsTheChange() throws Exception {
        for (Backend backend : backends) {
            ReadEntity entity = new ReadEntity("stored");
            backend.operator.insert(entity);
            ReadEntity copy = backend.operator.getById(entity.getId());
            copy.setName("updated");
            backend.operator.update(copy);
            assertThat(backend.operator.getById(entity.getId()).getName())
                    .as("%s: update(T) did not persist the change", backend.label)
                    .isEqualTo("updated");
        }
    }
}
