package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.DataScope;

/**
 * One test pair per behaviour the shipped modules rely on when they call
 * {@code DataOperator#transaction(...)} on {@code datasource.type: json}, reproduced with
 * framework APIs only (no module class is imported):
 * <ul>
 *   <li>a <b>characterization</b> test, which passes on the 6.2.x-style engine too and must keep
 *       passing: the behaviour the module code depends on;</li>
 *   <li>an <b>isolation or atomicity</b> test: what another thread sees while the callback is
 *       open, or whether two operators of one plugin commit and roll back together.</li>
 * </ul>
 * In every isolation test the second thread only reads. The operators come from one
 * {@link JsonStore} for one {@link DataScope}, as a module receives them.
 */
@Timeout(60)
@DisplayName("JSON transaction(...) behaviours the shipped modules rely on")
class JsonModuleTransactionBehaviourTest {

    private static final long HANDSHAKE_SECONDS = 10;

    @TempDir
    Path tempDir;

    private DataOperator<ModuleRow> rows;
    private DataOperator<ModuleOther> others;

    @BeforeAll
    static void setUpServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(mockServer);
        }
    }

    @BeforeEach
    void setUpStore() throws Exception {
        File dataFolder = Files.createDirectory(tempDir.resolve("module-data")).toFile();
        DataScope scope = JsonTransactionEngineTest.newDataScope("json-module-behaviour", dataFolder,
                ModuleRow.class, ModuleOther.class);
        JsonStore store = new JsonStore(tempDir.resolve("store").toString());
        rows = store.getOperator(scope, ModuleRow.class);
        others = store.getOperator(scope, ModuleOther.class);
    }

    // ===== Chest lock: deletes, then a conditional query over them (UltiEssentials) =====

    @Test
    @DisplayName("chest lock (characterization): deletes inside the callback are seen by a conditional query in it; a throw keeps every record")
    void chestLock_readsOwnStagedDeletes() throws Exception {
        seedLocks();

        List<ModuleRow> inside = rows.transaction(() -> {
            rows.delById("lock-1");
            rows.delById("lock-2");
            return rows.query().where("owner").eq("alice").where("world").eq("world").list();
        });

        assertThat(inside).as("the callback's own query sees its deletes").isEmpty();
        assertThat(rows.getById("lock-1")).isNull();
        assertThat(rows.getById("lock-2")).isNull();
        assertThat(rows.getById("lock-3")).as("another owner's record").isNotNull();

        seedLocks();
        assertThatThrownBy(() -> rows.transaction(() -> {
            rows.delById("lock-1");
            rows.delById("lock-2");
            assertThat(rows.query().where("owner").eq("alice").where("world").eq("world").list()).isEmpty();
            throw new IllegalStateException("chest lock removal fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows.getById("lock-1")).as("a failed callback keeps every record").isNotNull();
        assertThat(rows.getById("lock-2")).as("a failed callback keeps every record").isNotNull();
        assertThat(rows.query().where("owner").eq("alice").where("world").eq("world").list()).hasSize(2);
    }

    @Test
    @DisplayName("chest lock (isolation): another thread still finds the records until the callback commits")
    void chestLock_otherThreadSeesRecordsUntilCommit() throws Exception {
        seedLocks();

        withOpenTransaction(rows, () -> {
            rows.delById("lock-1");
            rows.delById("lock-2");
        }, () -> {
            assertThat(rows.getById("lock-1")).as("getById while the delete is staged").isNotNull();
            assertThat(rows.query().where("owner").eq("alice").where("world").eq("world").list())
                    .as("conditional query while the deletes are staged").hasSize(2);
        });

        assertThat(rows.getById("lock-1")).as("after commit").isNull();
        assertThat(rows.query().where("owner").eq("alice").where("world").eq("world").list()).isEmpty();
    }

    // ===== Id backfill: delete then insert the same id, read back (UltiEssentials) =====

    @Test
    @DisplayName("backfill (characterization): delById then insert of the same id is read back by getById inside the callback")
    void backfill_deleteThenInsertSameIdReadsBack() throws Exception {
        rows.insert(row("entity-1", "legacy"));

        String inside = rows.transaction(() -> {
            rows.delById("entity-1");
            rows.insert(row("entity-1", "migrated"));
            return rows.getById("entity-1").getName();
        });

        assertThat(inside).isEqualTo("migrated");
        assertThat(rows.getById("entity-1").getName()).isEqualTo("migrated");
    }

    @Test
    @DisplayName("backfill (characterization): del(WhereCondition) then insert of the same id is read back by getById inside the callback")
    void backfill_delWhereThenInsertSameIdReadsBack() throws Exception {
        rows.insert(row("entity-2", "legacy"));

        String inside = rows.transaction(() -> {
            rows.del(WhereCondition.builder().column("id").value("entity-2").build());
            rows.insert(row("entity-2", "migrated"));
            return rows.getById("entity-2").getName();
        });

        assertThat(inside).isEqualTo("migrated");
        assertThat(rows.getById("entity-2").getName()).isEqualTo("migrated");
    }

    @Test
    @DisplayName("backfill (characterization): a throw inside the callback restores every original entry")
    void backfill_failureRestoresEveryEntry() {
        rows.insert(row("entity-1", "legacy-1"));
        rows.insert(row("entity-2", "legacy-2"));

        assertThatThrownBy(() -> rows.transaction(() -> {
            rows.delById("entity-1");
            rows.insert(row("entity-1", "migrated-1"));
            rows.del(WhereCondition.builder().column("id").value("entity-2").build());
            rows.insert(row("entity-2", "migrated-2"));
            throw new IllegalStateException("backfill fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows.getById("entity-1").getName()).isEqualTo("legacy-1");
        assertThat(rows.getById("entity-2").getName()).isEqualTo("legacy-2");
    }

    @Test
    @DisplayName("backfill (isolation): another thread sees the old entry until the callback commits")
    void backfill_otherThreadSeesOldEntryUntilCommit() throws Exception {
        rows.insert(row("entity-1", "legacy"));

        withOpenTransaction(rows, () -> {
            rows.delById("entity-1");
            rows.insert(row("entity-1", "migrated"));
        }, () -> assertThat(rows.getById("entity-1").getName()).as("while the replacement is staged")
                .isEqualTo("legacy"));

        assertThat(rows.getById("entity-1").getName()).as("after commit").isEqualTo("migrated");
    }

    // ===== updateIf over a row staged earlier in the same callback (UltiTrade, UltiRemoteBag) =====

    @Test
    @DisplayName("updateIf (characterization): a row staged earlier in the callback is the one updateIf checks and replaces")
    void updateIf_overStagedRow() throws Exception {
        boolean[] results = rows.transaction(() -> {
            rows.insert(status("trade-1", "pending"));
            boolean mismatch = rows.updateIf(status("trade-1", "returned"), statusIs("claimed"));
            boolean match = rows.updateIf(status("trade-1", "returned"), statusIs("pending"));
            assertThat(rows.getById("trade-1").getStatus()).isEqualTo("returned");
            return new boolean[] {mismatch, match};
        });

        assertThat(results[0]).as("updateIf whose condition the staged row does not meet").isFalse();
        assertThat(results[1]).as("updateIf whose condition the staged row meets").isTrue();
        assertThat(rows.getById("trade-1").getStatus()).isEqualTo("returned");
    }

    @Test
    @DisplayName("updateIf (isolation): neither the staged row nor the staged update is visible to another thread before commit")
    void updateIf_stagedRowInvisibleOutside() throws Exception {
        rows.insert(status("trade-0", "pending"));

        withOpenTransaction(rows, () -> {
            rows.insert(status("trade-1", "pending"));
            assertThat(rows.updateIf(status("trade-1", "returned"), statusIs("pending"))).isTrue();
            assertThat(rows.updateIf(status("trade-0", "returned"), statusIs("pending"))).isTrue();
        }, () -> {
            assertThat(rows.getById("trade-1")).as("row staged inside the callback").isNull();
            assertThat(rows.getById("trade-0").getStatus()).as("committed row updated inside the callback")
                    .isEqualTo("pending");
        });

        assertThat(rows.getById("trade-1").getStatus()).isEqualTo("returned");
        assertThat(rows.getById("trade-0").getStatus()).isEqualTo("returned");
    }

    // ===== Insert with a null id (all three modules) =====

    @Test
    @DisplayName("auto id (characterization): an insert with a null id gets a generated id that getById finds inside the callback")
    void autoIdInsert_visibleInsideCallback() throws Exception {
        ModuleRow entity = row(null, "generated");

        String found = rows.transaction(() -> {
            rows.insert(entity);
            assertThat(entity.getId()).as("a generated id").isNotNull();
            return rows.getById(entity.getId()).getName();
        });

        assertThat(found).isEqualTo("generated");
        assertThat(rows.getById(entity.getId())).isNotNull();
    }

    @Test
    @DisplayName("auto id (isolation): the generated-id row is invisible to another thread until commit")
    void autoIdInsert_invisibleOutsideUntilCommit() throws Exception {
        ModuleRow entity = row(null, "generated");

        withOpenTransaction(rows, () -> rows.insert(entity),
                () -> assertThat(rows.getById(entity.getId())).as("while the insert is staged").isNull());

        assertThat(rows.getById(entity.getId())).as("after commit").isNotNull();
    }

    // ===== Conditional reads over the merged view (all three modules) =====

    @Test
    @DisplayName("conditional queries (characterization): getAll, exist and query() terminals see staged inserts, updates and deletes")
    void conditionalQueries_seeMergedView() throws Exception {
        seedKinds();

        rows.transaction(() -> {
            stageKindChanges();
            assertThat(ids(rows.getAll(kindIs("x")))).containsExactly("d");
            assertThat(rows.exist(kindIs("x"))).isTrue();
            assertThat(rows.exist(kindIs("z"))).isFalse();
            assertThat(rows.query().where("kind").eq("x").first().getId()).isEqualTo("d");
            assertThat(rows.query().where("kind").eq("y").exists()).isTrue();
            assertThat(rows.query().where("kind").eq("y").count()).isEqualTo(2);
            assertThat(rows.query().where("kind").eq("y").delete()).isEqualTo(2);
            assertThat(rows.getAll(kindIs("y"))).isEmpty();
            return null;
        });

        assertThat(ids(rows.getAll())).containsExactly("d");
    }

    @Test
    @DisplayName("conditional queries (isolation): another thread's conditional reads see committed data only")
    void conditionalQueries_otherThreadSeesCommittedOnly() throws Exception {
        seedKinds();

        withOpenTransaction(rows, this::stageKindChanges, () -> {
            assertThat(ids(rows.getAll(kindIs("x")))).containsExactlyInAnyOrder("a", "b");
            assertThat(rows.exist(kindIs("x"))).isTrue();
            assertThat(rows.query().where("kind").eq("y").count()).isEqualTo(1);
            assertThat(rows.query().where("kind").eq("y").first().getId()).isEqualTo("c");
            assertThat(rows.query().where("kind").eq("x").exists()).isTrue();
            assertThat(ids(rows.getAll())).containsExactlyInAnyOrder("a", "b", "c");
        });

        assertThat(ids(rows.getAll(kindIs("x")))).containsExactly("d");
        assertThat(ids(rows.getAll(kindIs("y")))).containsExactlyInAnyOrder("b", "c");
    }

    // ===== Two operators of one module in one callback (UltiRemoteBag) =====

    @Test
    @DisplayName("two operators (characterization): writes to two operators of one plugin inside one callback both commit")
    void twoOperators_commitTogether() throws Exception {
        rows.transaction(() -> {
            rows.insert(row("page-1", "fence"));
            others.insert(new ModuleOther("slot-1", "written"));
            return null;
        });

        assertThat(rows.getById("page-1")).isNotNull();
        assertThat(others.getById("slot-1")).isNotNull();
    }

    @Test
    @DisplayName("two operators (atomicity): a throw inside the callback leaves neither operator's write")
    void twoOperators_rollBackTogether() {
        assertThatThrownBy(() -> rows.transaction(() -> {
            rows.insert(row("page-1", "fence"));
            others.insert(new ModuleOther("slot-1", "written"));
            throw new IllegalStateException("bag write fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows.getById("page-1")).as("first operator's write").isNull();
        assertThat(others.getById("slot-1")).as("second operator's write, same plugin, same callback").isNull();
    }

    // ===== Helpers =====

    /**
     * Opens {@code operator.transaction(...)} on a second thread, runs {@code stagedWrites} in it,
     * then holds it open while this thread runs {@code outsideReads}, then lets it commit. This
     * thread only reads.
     */
    private void withOpenTransaction(DataOperator<?> operator, Runnable stagedWrites, Runnable outsideReads)
            throws InterruptedException {
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                operator.transaction((Callable<Void>) () -> {
                    stagedWrites.run();
                    staged.countDown();
                    JsonTransactionEngineTest.awaitQuietly(release);
                    return null;
                });
            } catch (Throwable t) {
                failure.set(t);
                staged.countDown();
            }
        }, "module-behaviour-writer");
        writer.start();
        try {
            assertThat(staged.await(HANDSHAKE_SECONDS, TimeUnit.SECONDS)).as("the callback staged its writes").isTrue();
            assertThat(failure.get()).as("the callback's writes").isNull();
            outsideReads.run();
        } finally {
            release.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(HANDSHAKE_SECONDS));
        }
        assertThat(writer.isAlive()).as("the callback finished").isFalse();
        assertThat(failure.get()).as("the callback committed").isNull();
    }

    private void seedLocks() {
        rows.insert(lock("lock-1", "alice"));
        rows.insert(lock("lock-2", "alice"));
        rows.insert(lock("lock-3", "bob"));
    }

    private void seedKinds() {
        rows.insert(kind("a", "x"));
        rows.insert(kind("b", "x"));
        rows.insert(kind("c", "y"));
    }

    private void stageKindChanges() {
        rows.insert(kind("d", "x"));
        JsonTransactionEngineTest.updateQuietly(rows, kind("b", "y"));
        rows.delById("a");
    }

    private static ModuleRow row(String id, String name) {
        ModuleRow row = new ModuleRow();
        row.setId(id);
        row.setName(name);
        return row;
    }

    private static ModuleRow lock(String id, String owner) {
        ModuleRow row = new ModuleRow();
        row.setId(id);
        row.setOwner(owner);
        row.setWorld("world");
        return row;
    }

    private static ModuleRow status(String id, String status) {
        ModuleRow row = new ModuleRow();
        row.setId(id);
        row.setStatus(status);
        return row;
    }

    private static ModuleRow kind(String id, String kind) {
        ModuleRow row = new ModuleRow();
        row.setId(id);
        row.setKind(kind);
        return row;
    }

    private static WhereCondition statusIs(String status) {
        return WhereCondition.builder().column("status").value(status).build();
    }

    private static WhereCondition kindIs(String kind) {
        return WhereCondition.builder().column("kind").value(kind).build();
    }

    private static List<String> ids(List<ModuleRow> entities) {
        List<String> ids = new ArrayList<>();
        for (ModuleRow entity : entities) {
            ids.add(entity.getId());
        }
        return ids;
    }

    // ===== Fixtures =====

    @Table("module_rows")
    public static class ModuleRow extends BaseDataEntity<String> {
        @Column("name")
        private String name;
        @Column("owner")
        private String owner;
        @Column("world")
        private String world;
        @Column("status")
        private String status;
        @Column("kind")
        private String kind;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getWorld() {
            return world;
        }

        public void setWorld(String world) {
            this.world = world;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }
    }

    @Table("module_other")
    public static class ModuleOther extends BaseDataEntity<String> {
        @Column("label")
        private String label;

        public ModuleOther() {
        }

        public ModuleOther(String id, String label) {
            setId(id);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
