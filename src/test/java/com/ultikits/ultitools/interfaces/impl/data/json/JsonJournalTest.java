package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Entry;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Kind;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Record;

/**
 * The JSON redo journal in isolation: record format and sequence numbers, the commit point, the
 * entity-file writer and its temporary files, recovery, and an interruption at every journal step
 * (20.4-RESEARCH.md sections 12.7 and 12.9).
 * <p>
 * A crash is simulated by a step hook that throws {@link SimulatedCrash} at a named step while the
 * journal call runs on a dedicated thread; the test then drops that journal object and runs
 * recovery through a new one over the same directories. No process is killed.
 */
@Timeout(60)
class JsonJournalTest {

    private static final String USERS = "users";
    private static final String ORDERS = "orders";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpBukkit() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            Logger mockLogger = mock(Logger.class);
            when(mockServer.getLogger()).thenReturn(mockLogger);
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(mockServer);
        }
    }

    @AfterEach
    void resetSeams() {
        JsonJournal.resetStepHook();
        JsonJournal.resetDirectoryForcer();
    }

    // ===== Record format and sequence numbers =====

    @Test
    @DisplayName("A record is one JSON line followed by the SHA-256 of that line, named by its zero-padded number")
    void recordFormatIsJsonLineThenSha256() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);

        journal.writeRecord(42L, Kind.COMMIT, Arrays.asList(
                Entry.put(USERS, "a", entity("a", 1)),
                Entry.delete(ORDERS, "o1")));

        Path committed = journalDir(root).resolve(String.format("%019d.commit", 42L));
        assertThat(committed).as("the committed record file").exists();
        assertThat(recordNames(root)).containsExactly(String.format("%019d.commit", 42L));

        String text = new String(Files.readAllBytes(committed), StandardCharsets.UTF_8);
        assertThat(text).endsWith("\n");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).isEqualTo(sha256Hex(lines[0]));

        JsonObject record = JsonParser.parseString(lines[0]).getAsJsonObject();
        assertThat(record.get("format").getAsInt()).isEqualTo(1);
        assertThat(record.get("sequence").getAsLong()).isEqualTo(42L);
        assertThat(record.get("kind").getAsString()).isEqualTo("commit");
        JsonArray entries = record.getAsJsonArray("entries");
        assertThat(entries).hasSize(2);
        JsonObject put = entries.get(0).getAsJsonObject();
        assertThat(put.get("table").getAsString()).isEqualTo(USERS);
        assertThat(put.get("key").getAsString()).isEqualTo("a");
        assertThat(put.get("op").getAsString()).isEqualTo("put");
        assertThat(put.get("afterImage").getAsString()).isEqualTo(entity("a", 1));
        JsonObject delete = entries.get(1).getAsJsonObject();
        assertThat(delete.get("table").getAsString()).isEqualTo(ORDERS);
        assertThat(delete.get("key").getAsString()).isEqualTo("o1");
        assertThat(delete.get("op").getAsString()).isEqualTo("delete");
        assertThat(delete.has("afterImage")).isFalse();
    }

    @Test
    @DisplayName("Sequence numbers increase and are seeded from the highest record number on disk plus one")
    void sequenceNumbersIncreaseAndSurviveReopen() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal fresh = journalAt(root);
        assertThat(fresh.nextSequence()).isEqualTo(1L);
        assertThat(fresh.nextSequence()).isEqualTo(2L);
        assertThat(journalDir(root)).as("allocating a number creates nothing").doesNotExist();

        fresh.writeRecord(7L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        assertThat(journalAt(root).nextSequence()).isEqualTo(8L);

        Files.write(journalDir(root).resolve(String.format("%019d.tmp", 12L)), new byte[] {'x'});
        JsonJournal reopened = journalAt(root);
        assertThat(reopened.nextSequence()).isEqualTo(13L);
        assertThat(reopened.nextSequence()).isEqualTo(14L);
    }

    @Test
    @DisplayName("Record names use ASCII digits under any default locale, so numbering and recovery still find them")
    void recordNamesUseAsciiDigitsUnderAnyDefaultLocale() throws Exception {
        Path root = tempDir.resolve("root");
        Locale previousDefault = Locale.getDefault();
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        // Egyptian Arabic formats numbers with Arabic-Indic digits by default.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"));
        try {
            JsonJournal journal = journalAt(root);
            journal.writeRecord(42L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));

            assertThat(recordNames(root)).as("the committed record's file name")
                    .containsExactly("0000000000000000042.commit");
            assertThat(journalAt(root).nextSequence()).as("numbering after a reopen").isEqualTo(43L);
            journalAt(root).recover();
            assertThat(read(root.resolve(USERS).resolve("x.json"))).as("the record is applied at recovery")
                    .isEqualTo(entity("x", 1));
            assertThat(recordNames(root)).as("the applied record is deleted").isEmpty();
        } finally {
            Locale.setDefault(previousDefault);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
        }
    }

    @Test
    @DisplayName("A missing journal directory is an empty journal: recovery creates nothing, the first record creates it")
    void missingJournalDirectoryIsAnEmptyJournal() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root.resolve(USERS));
        JsonJournal journal = journalAt(root);

        journal.recover();
        assertThat(journalDir(root)).doesNotExist();
        assertThat(journal.listCommitted()).isEmpty();

        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        assertThat(journalDir(root)).isDirectory();
        assertThat(recordNames(root)).containsExactly(String.format("%019d.commit", 1L));
    }

    @Test
    @DisplayName("Entry table names and keys cannot leave their table directory")
    void entryNamesStayInsideTheirTableDirectory() {
        assertThatThrownBy(() -> Entry.put(USERS, "../escape", entity("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.put(USERS, "nested/key", entity("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.delete(USERS, "back\\slash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.put("..", "k", entity("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.put("a/b", "k", entity("x", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.delete("", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Entry.delete(USERS, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Record steps carry the record's kind and the rename is the commit point")
    void recordStepsFollowTheRecordKind() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        String temporary = String.format("%019d.tmp", 1L);
        String committed = String.format("%019d.commit", 1L);
        List<String> steps = new CopyOnWriteArrayList<>();
        Map<String, List<String>> namesAtStep = new TreeMap<>();
        JsonJournal.installStepHook(step -> {
            steps.add(step);
            namesAtStep.put(step, recordNames(root));
        });

        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        journal.writeRecord(2L, Kind.CHECKPOINT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 2))));

        assertThat(steps).containsExactly(
                "commit.recordWritten", "commit.recordForced", "commit.renamed", "commit.directoryForced",
                "checkpoint.recordWritten", "checkpoint.recordForced", "checkpoint.renamed",
                "checkpoint.directoryForced");
        assertThat(namesAtStep.get("commit.recordWritten")).containsExactly(temporary);
        assertThat(namesAtStep.get("commit.recordForced")).containsExactly(temporary);
        assertThat(namesAtStep.get("commit.renamed")).containsExactly(committed);
        assertThat(namesAtStep.get("commit.directoryForced")).containsExactly(committed);
    }

    @Test
    @DisplayName("Committed records list in ascending order, verified, and round-trip their content")
    void recordContentRoundTrips() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        String awkward = "{\n  \"id\": \"abc\",\n  \"text\": \"quote \\\" newline \\n tab \\t \\u00e9\"\n}";
        List<Entry> first = Arrays.asList(Entry.put(USERS, "abc", awkward), Entry.put(USERS, 42, entity("42", 4)));
        List<Entry> second = Collections.singletonList(Entry.delete(ORDERS, "o1"));
        List<Entry> third = Collections.singletonList(Entry.put(ORDERS, "o2", entity("o2", 7)));

        journal.writeRecord(3L, Kind.COMMIT, third);
        journal.writeRecord(1L, Kind.COMMIT, first);
        journal.writeRecord(2L, Kind.CHECKPOINT, second);

        List<Record> records = journalAt(root).listCommitted();
        assertThat(records).extracting(Record::sequence).containsExactly(1L, 2L, 3L);
        assertThat(records).extracting(Record::kind).containsExactly(Kind.COMMIT, Kind.CHECKPOINT, Kind.COMMIT);
        assertThat(records.get(0).entries()).containsExactlyElementsOf(first);
        assertThat(records.get(0).entries().get(1).key()).isEqualTo("42");
        assertThat(records.get(1).entries()).containsExactlyElementsOf(second);
        assertThat(records.get(1).entries().get(0).isDelete()).isTrue();
        assertThat(records.get(2).entries()).containsExactlyElementsOf(third);

        Files.createDirectories(root.resolve(ORDERS));
        Files.write(root.resolve(ORDERS).resolve("o1.json"), entity("o1", 0).getBytes(StandardCharsets.UTF_8));
        journalAt(root).recover();
        assertThat(read(root.resolve(USERS).resolve("abc.json"))).isEqualTo(awkward);
        assertThat(read(root.resolve(USERS).resolve("42.json"))).isEqualTo(entity("42", 4));
        assertThat(root.resolve(ORDERS).resolve("o1.json")).doesNotExist();
        assertThat(read(root.resolve(ORDERS).resolve("o2.json"))).isEqualTo(entity("o2", 7));
    }

    @Test
    @DisplayName("listCommitted refuses a record whose checksum does not match, naming the file")
    void listCommittedVerifiesChecksums() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        journal.writeRecord(2L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 2))));
        assertThat(journal.listCommitted()).hasSize(2);

        Path second = journalDir(root).resolve(String.format("%019d.commit", 2L));
        tamperFirstLine(second);

        assertThatThrownBy(() -> journalAt(root).listCommitted())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(second.getFileName().toString());
    }

    // ===== Interruption while writing a record =====

    @Test
    @DisplayName("An interruption before the rename leaves no committed record; recovery deletes the temporary one")
    void interruptionBeforeTheRenameLeavesNoCommittedRecord() throws Exception {
        for (String step : Arrays.asList("commit.recordWritten", "commit.recordForced")) {
            Path root = tempDir.resolve("before-" + step);
            Files.createDirectories(root.resolve(USERS));
            JsonJournal journal = journalAt(root);
            JsonJournal.installStepHook(crashAt(step, 1));

            Throwable thrown = runOnOwnThread(() -> journal.writeRecord(1L, Kind.COMMIT, twoTableEntries()));
            JsonJournal.resetStepHook();

            assertCrashedAt(thrown, step, 1);
            assertThat(recordNames(root)).as(step).containsExactly(String.format("%019d.tmp", 1L));

            JsonJournal reopened = journalAt(root);
            assertThat(reopened.listCommitted()).as(step).isEmpty();
            reopened.recover();
            assertThat(recordNames(root)).as(step).isEmpty();
            assertThat(tableFiles(root)).as(step).isEmpty();
        }
    }

    @Test
    @DisplayName("An interruption after the rename leaves a committed record that recovery applies")
    void interruptionAfterTheRenameAppliesTheRecord() throws Exception {
        for (String step : Arrays.asList("commit.renamed", "commit.directoryForced")) {
            Path root = tempDir.resolve("after-" + step);
            JsonJournal journal = journalAt(root);
            JsonJournal.installStepHook(crashAt(step, 1));

            Throwable thrown = runOnOwnThread(() -> journal.writeRecord(1L, Kind.COMMIT, twoTableEntries()));
            JsonJournal.resetStepHook();

            assertCrashedAt(thrown, step, 1);
            assertThat(recordNames(root)).as(step).containsExactly(String.format("%019d.commit", 1L));

            journalAt(root).recover();
            assertThat(recordNames(root)).as(step).isEmpty();
            assertThat(tableFiles(root)).as(step).isEqualTo(twoTableFiles());
        }
    }

    @Test
    @DisplayName("A record touching two tables is applied to both, or, interrupted before its rename, to neither")
    void twoTableRecordAppliesToBothOrNeither() throws Exception {
        Path neither = tempDir.resolve("neither");
        JsonJournal first = journalAt(neither);
        JsonJournal.installStepHook(crashAt("commit.recordForced", 1));
        assertCrashedAt(runOnOwnThread(() -> first.writeRecord(1L, Kind.COMMIT, twoTableEntries())),
                "commit.recordForced", 1);
        JsonJournal.resetStepHook();
        journalAt(neither).recover();
        assertThat(tableFiles(neither)).isEmpty();

        Path both = tempDir.resolve("both");
        JsonJournal second = journalAt(both);
        JsonJournal.installStepHook(crashAt("commit.renamed", 1));
        assertCrashedAt(runOnOwnThread(() -> second.writeRecord(1L, Kind.COMMIT, twoTableEntries())),
                "commit.renamed", 1);
        JsonJournal.resetStepHook();
        journalAt(both).recover();
        assertThat(tableFiles(both)).isEqualTo(twoTableFiles());
    }

    @Test
    @DisplayName("An IOException while writing a record deletes the temporary record and propagates")
    void ioFailureWhileWritingARecordDeletesTheTemporaryRecord() throws Exception {
        for (String step : Arrays.asList("commit.recordWritten", "commit.recordForced")) {
            Path root = tempDir.resolve("io-" + step);
            JsonJournal journal = journalAt(root);
            IOException failure = new IOException("simulated disk failure at " + step);
            JsonJournal.installStepHook(name -> {
                if (name.equals(step)) {
                    throw failure;
                }
            });

            assertThatThrownBy(() -> journal.writeRecord(1L, Kind.COMMIT, twoTableEntries()))
                    .as(step)
                    .isSameAs(failure);
            JsonJournal.resetStepHook();
            assertThat(recordNames(root)).as(step).isEmpty();
            assertThat(journal.listCommitted()).as(step).isEmpty();
        }
    }

    // ===== Entity-file writer and temporary files =====

    @Test
    @DisplayName("Entity files are written in the journal's tmp directory and moved into place; deletes remove the file")
    void entityFilesPassThroughTheJournalTmpDirectory() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root.resolve(USERS));
        Files.write(root.resolve(USERS).resolve("old.json"), entity("old", 0).getBytes(StandardCharsets.UTF_8));
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Arrays.asList(
                Entry.put(USERS, "a", entity("a", 1)),
                Entry.delete(USERS, "old"),
                Entry.put(ORDERS, "o1", entity("o1", 1))));

        List<String> steps = new CopyOnWriteArrayList<>();
        List<List<String>> tmpAtTempWritten = new CopyOnWriteArrayList<>();
        List<Map<String, String>> tablesAtTempWritten = new CopyOnWriteArrayList<>();
        JsonJournal.installStepHook(step -> {
            steps.add(step);
            if (step.equals("recovery.entityTempWritten")) {
                tmpAtTempWritten.add(names(journalDir(root).resolve("tmp")));
                tablesAtTempWritten.add(tableFiles(root));
            }
        });

        journalAt(root).recover();

        assertThat(steps).containsExactly(
                "recovery.tempsDeleted",
                "recovery.entityTempWritten",
                "recovery.betweenEntityFiles",
                "recovery.betweenEntityFiles",
                "recovery.entityTempWritten",
                "recovery.tableDirsForced",
                "recovery.recordsApplied");
        assertThat(tmpAtTempWritten).hasSize(2);
        assertThat(tmpAtTempWritten).allSatisfy(names -> assertThat(names).hasSize(1));
        assertThat(tablesAtTempWritten.get(0)).containsOnlyKeys("users/old.json");
        assertThat(tablesAtTempWritten.get(1)).containsOnlyKeys("users/a.json");
        assertThat(names(journalDir(root).resolve("tmp"))).isEmpty();
        Map<String, String> expected = new TreeMap<>();
        expected.put("users/a.json", entity("a", 1));
        expected.put("orders/o1.json", entity("o1", 1));
        assertThat(tableFiles(root)).isEqualTo(expected);
    }

    @Test
    @DisplayName("No temporary file enters a table directory; an operator built mid-crash loads only the old entities")
    void temporaryFilesNeverEnterATableDirectory() throws Exception {
        Path root = tempDir.resolve("root");
        Path users = root.resolve(USERS);
        Files.createDirectories(users);
        Files.write(users.resolve("a.json"), entity("a", 0).getBytes(StandardCharsets.UTF_8));
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Arrays.asList(
                Entry.put(USERS, "b", entity("b", 1)),
                Entry.put(USERS, "a", entity("a", 1))));
        Map<String, String> before = tableFiles(root);

        JsonJournal.installStepHook(crashAt("recovery.entityTempWritten", 1));
        JsonJournal crashing = journalAt(root);
        assertCrashedAt(runOnOwnThread(crashing::recover), "recovery.entityTempWritten", 1);
        JsonJournal.resetStepHook();

        assertThat(names(journalDir(root).resolve("tmp"))).hasSize(1);
        assertThat(tableFiles(root)).isEqualTo(before);
        assertThat(names(users)).containsExactly("a.json");

        long severeBefore = severeBukkitLogCount();
        SimpleJsonDataOperator<JournalEntity> operator =
                new SimpleJsonDataOperator<>(users.toString(), JournalEntity.class);
        assertThat(severeBukkitLogCount()).as("no corrupt-entry log").isEqualTo(severeBefore);
        assertThat(operator.getAll()).extracting(JournalEntity::getId).containsExactly("a");
        assertThat(operator.getAll()).extracting(JournalEntity::getValue).containsExactly(0);

        List<String> steps = new CopyOnWriteArrayList<>();
        AtomicReference<List<String>> tmpAfterFirstStep = new AtomicReference<>();
        JsonJournal.installStepHook(step -> {
            if (steps.isEmpty()) {
                tmpAfterFirstStep.set(names(journalDir(root).resolve("tmp")));
            }
            steps.add(step);
        });
        journalAt(root).recover();

        assertThat(steps.get(0)).isEqualTo("recovery.tempsDeleted");
        assertThat(tmpAfterFirstStep.get()).isEmpty();
        assertThat(names(journalDir(root).resolve("tmp"))).isEmpty();
        Map<String, String> expected = new TreeMap<>();
        expected.put("users/a.json", entity("a", 1));
        expected.put("users/b.json", entity("b", 1));
        assertThat(tableFiles(root)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Recovery forces the journal directory and every touched table directory")
    void touchedDirectoriesAreForced() throws Exception {
        Path root = tempDir.resolve("root");
        List<Path> forced = new CopyOnWriteArrayList<>();
        JsonJournal.installDirectoryForcer(forced::add);
        JsonJournal journal = journalAt(root);

        journal.writeRecord(1L, Kind.COMMIT, twoTableEntries());
        assertThat(forced).contains(journalDir(root));

        forced.clear();
        journalAt(root).recover();
        assertThat(forced).contains(root.resolve(USERS), root.resolve(ORDERS), journalDir(root));
    }

    @Test
    @DisplayName("A directory that cannot be opened for a force is logged once at FINE and is not fatal")
    void directoryThatCannotBeOpenedIsLoggedOnceAtFine() throws Exception {
        Path root = tempDir.resolve("root");
        AtomicInteger attempts = new AtomicInteger();
        JsonJournal.installDirectoryForcer(directory -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(directory.toString(), null, "directory force unsupported");
        });
        Logger logger = Logger.getLogger(JsonJournal.class.getName());
        Level previousLevel = logger.getLevel();
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new CapturingHandler(logged);
        logger.addHandler(handler);
        logger.setLevel(Level.FINE);
        try {
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, twoTableEntries());
            assertThat(recordNames(root)).containsExactly(String.format("%019d.commit", 1L));
            journal.recover();
            assertThat(tableFiles(root)).isEqualTo(twoTableFiles());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
        assertThat(attempts.get()).isGreaterThan(1);
        List<LogRecord> forceFailures = logged.stream()
                .filter(record -> record.getThrown() instanceof AccessDeniedException
                        && "directory force unsupported".equals(((AccessDeniedException) record.getThrown()).getReason()))
                .collect(Collectors.toList());
        assertThat(forceFailures).hasSize(1);
        assertThat(forceFailures.get(0).getLevel()).isEqualTo(Level.FINE);
    }

    // ===== Recovery =====

    @Test
    @DisplayName("Recovery applies committed records in ascending order, then deletes them in ascending order")
    void recoverAppliesAndDeletesInAscendingOrder() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        journal.writeRecord(3L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 3))));
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));
        journal.writeRecord(2L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 2))));

        List<String> valueAtBetweenRecords = new CopyOnWriteArrayList<>();
        List<List<String>> recordsAtStep = new CopyOnWriteArrayList<>();
        JsonJournal.installStepHook(step -> {
            if (step.equals("recovery.betweenRecords")) {
                valueAtBetweenRecords.add(read(root.resolve(USERS).resolve("x.json")));
            }
            if (step.equals("recovery.recordsApplied") || step.equals("recovery.betweenDeletions")) {
                recordsAtStep.add(recordNames(root));
            }
        });

        journalAt(root).recover();

        assertThat(valueAtBetweenRecords).containsExactly(entity("x", 1), entity("x", 2));
        assertThat(recordsAtStep).containsExactly(
                Arrays.asList(commitName(1), commitName(2), commitName(3)),
                Arrays.asList(commitName(2), commitName(3)),
                Collections.singletonList(commitName(3)));
        assertThat(recordNames(root)).isEmpty();
        assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 3));
    }

    @Test
    @DisplayName("A second recovery after a completed one changes no file")
    void recoverTwiceIsIdempotent() throws Exception {
        Path root = tempDir.resolve("root");
        seedRecoveryScenario(root);

        journalAt(root).recover();
        Map<String, String> afterFirst = allFiles(root);
        Map<String, FileTime> timesAfterFirst = modificationTimes(root);
        assertThat(tableFiles(root)).isEqualTo(expectedRecoveredTables());

        journalAt(root).recover();
        assertThat(allFiles(root)).isEqualTo(afterFirst);
        assertThat(modificationTimes(root)).isEqualTo(timesAfterFirst);
    }

    @Test
    @DisplayName("A recovery interrupted at any of its steps, run again, ends at the files of an uninterrupted run")
    void interruptedRecoveryRecoversAgain() throws Exception {
        Path control = tempDir.resolve("control");
        seedRecoveryScenario(control);
        journalAt(control).recover();
        Map<String, String> controlFiles = allFiles(control);
        assertThat(tableFiles(control)).isEqualTo(expectedRecoveredTables());

        List<Object[]> cases = Arrays.asList(
                new Object[] {"recovery.tempsDeleted", 1},
                new Object[] {"recovery.entityTempWritten", 1},
                new Object[] {"recovery.entityTempWritten", 3},
                new Object[] {"recovery.betweenEntityFiles", 1},
                new Object[] {"recovery.betweenEntityFiles", 2},
                new Object[] {"recovery.tableDirsForced", 1},
                new Object[] {"recovery.tableDirsForced", 3},
                new Object[] {"recovery.betweenRecords", 1},
                new Object[] {"recovery.betweenRecords", 2},
                new Object[] {"recovery.recordsApplied", 1},
                new Object[] {"recovery.betweenDeletions", 1},
                new Object[] {"recovery.betweenDeletions", 2});
        int index = 0;
        for (Object[] crashCase : cases) {
            String step = (String) crashCase[0];
            int occurrence = (Integer) crashCase[1];
            Path root = tempDir.resolve("case-" + index++);
            seedRecoveryScenario(root);

            JsonJournal.installStepHook(crashAt(step, occurrence));
            JsonJournal crashing = journalAt(root);
            assertCrashedAt(runOnOwnThread(crashing::recover), step, occurrence);
            JsonJournal.resetStepHook();

            journalAt(root).recover();
            assertThat(allFiles(root)).as(step + "#" + occurrence).isEqualTo(controlFiles);
        }
    }

    // ===== Checkpoint =====

    @Test
    @DisplayName("A checkpoint commits its record before writing any entity file, then truncates through itself")
    void checkpointWritesItsRecordBeforeAnyEntityFile() throws Exception {
        Path root = tempDir.resolve("root");
        Path users = root.resolve(USERS);
        Files.createDirectories(users);
        Files.write(users.resolve("x.json"), entity("x", 0).getBytes(StandardCharsets.UTF_8));
        Files.write(users.resolve("gone.json"), entity("gone", 0).getBytes(StandardCharsets.UTF_8));
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));
        Map<String, String> initialTables = tableFiles(root);

        List<String> steps = new CopyOnWriteArrayList<>();
        Map<String, Map<String, String>> tablesAtStep = new TreeMap<>();
        Map<String, List<String>> recordsAtStep = new TreeMap<>();
        JsonJournal.installStepHook(step -> {
            steps.add(step);
            tablesAtStep.put(step, tableFiles(root));
            recordsAtStep.put(step, recordNames(root));
        });

        journal.checkpoint(2L, Arrays.asList(
                Entry.put(USERS, "x", entity("x", 2)),
                Entry.put(USERS, "z", entity("z", 2)),
                Entry.delete(USERS, "gone")));

        assertThat(steps).containsExactly(
                "checkpoint.recordWritten", "checkpoint.recordForced", "checkpoint.renamed",
                "checkpoint.directoryForced", "checkpoint.entityTempWritten", "checkpoint.betweenEntityFiles",
                "checkpoint.entityTempWritten", "checkpoint.betweenEntityFiles", "checkpoint.tableDirsForced",
                "checkpoint.betweenTruncations");
        for (String step : Arrays.asList("checkpoint.recordWritten", "checkpoint.recordForced",
                "checkpoint.renamed", "checkpoint.directoryForced")) {
            assertThat(tablesAtStep.get(step)).as("table files at " + step).isEqualTo(initialTables);
        }
        assertThat(recordsAtStep.get("checkpoint.recordWritten"))
                .containsExactly(commitName(1), String.format("%019d.tmp", 2L));
        assertThat(recordsAtStep.get("checkpoint.renamed")).containsExactly(commitName(1), commitName(2));
        assertThat(recordsAtStep.get("checkpoint.betweenTruncations")).containsExactly(commitName(2));
        assertThat(recordNames(root)).isEmpty();
        Map<String, String> expected = new TreeMap<>();
        expected.put("users/x.json", entity("x", 2));
        expected.put("users/z.json", entity("z", 2));
        assertThat(tableFiles(root)).isEqualTo(expected);
        assertThat(names(journalDir(root).resolve("tmp"))).isEmpty();
    }

    @Test
    @DisplayName("Truncation deletes every record at or below K in ascending order, K last, and nothing above K")
    void truncationIsAscendingWithKLastAndLeavesRecordsAboveK() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));
        journal.writeRecord(3L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 3))));
        journal.writeRecord(6L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "y", entity("y", 6))));
        Files.write(journalDir(root).resolve(String.format("%019d.tmp", 2L)), new byte[] {'x'});
        Files.write(journalDir(root).resolve(String.format("%019d.tmp", 7L)), new byte[] {'x'});

        List<List<String>> recordsAtTruncation = new CopyOnWriteArrayList<>();
        JsonJournal.installStepHook(step -> {
            if (step.equals("checkpoint.betweenTruncations")) {
                recordsAtTruncation.add(recordNames(root));
            }
        });

        journal.checkpoint(4L, Collections.singletonList(Entry.put(USERS, "x", entity("x", 4))));

        String tmp2 = String.format("%019d.tmp", 2L);
        String tmp7 = String.format("%019d.tmp", 7L);
        assertThat(recordsAtTruncation).containsExactly(
                Arrays.asList(commitName(4), commitName(6), tmp2, commitName(3), tmp7).stream()
                        .sorted().collect(Collectors.toList()),
                Arrays.asList(commitName(3), commitName(4), commitName(6), tmp7).stream()
                        .sorted().collect(Collectors.toList()),
                Arrays.asList(commitName(4), commitName(6), tmp7).stream()
                        .sorted().collect(Collectors.toList()));
        assertThat(recordNames(root)).containsExactly(commitName(6), tmp7);
        assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 4));
    }

    @Test
    @DisplayName("A checkpoint interrupted after its entity files but before truncation never brings an older value back")
    void noRegressionAfterCheckpoint() throws Exception {
        for (String step : Arrays.asList("checkpoint.tableDirsForced", "checkpoint.betweenTruncations")) {
            Path root = tempDir.resolve("no-regression-" + step);
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));

            JsonJournal.installStepHook(crashAt(step, 1));
            assertCrashedAt(runOnOwnThread(() -> journal.checkpoint(2L,
                    Collections.singletonList(Entry.put(USERS, "x", entity("x", 2))))), step, 1);
            JsonJournal.resetStepHook();
            assertThat(read(root.resolve(USERS).resolve("x.json"))).as(step).isEqualTo(entity("x", 2));

            journalAt(root).recover();
            assertThat(read(root.resolve(USERS).resolve("x.json"))).as(step).isEqualTo(entity("x", 2));
            assertThat(recordNames(root)).as(step).isEmpty();
        }
    }

    @Test
    @DisplayName("An interruption at every checkpoint step never regresses, and K's value wins once K was renamed")
    void interruptionAtEveryCheckpointStep() throws Exception {
        Path control = tempDir.resolve("control");
        seedCheckpointScenario(control);
        journalAt(control).checkpoint(2L, checkpointEntries());
        journalAt(control).recover();
        Map<String, String> controlFiles = allFiles(control);
        Map<String, String> afterK = new TreeMap<>();
        afterK.put("users/x.json", entity("x", 2));
        afterK.put("users/z.json", entity("z", 2));
        assertThat(tableFiles(control)).isEqualTo(afterK);

        List<Object[]> cases = Arrays.asList(
                new Object[] {"checkpoint.recordWritten", 1, false},
                new Object[] {"checkpoint.recordForced", 1, false},
                new Object[] {"checkpoint.renamed", 1, true},
                new Object[] {"checkpoint.directoryForced", 1, true},
                new Object[] {"checkpoint.entityTempWritten", 1, true},
                new Object[] {"checkpoint.entityTempWritten", 2, true},
                new Object[] {"checkpoint.betweenEntityFiles", 1, true},
                new Object[] {"checkpoint.tableDirsForced", 1, true},
                new Object[] {"checkpoint.betweenTruncations", 1, true});
        int index = 0;
        for (Object[] crashCase : cases) {
            String step = (String) crashCase[0];
            int occurrence = (Integer) crashCase[1];
            boolean renamed = (Boolean) crashCase[2];
            String label = step + "#" + occurrence;
            Path root = tempDir.resolve("checkpoint-case-" + index++);
            seedCheckpointScenario(root);
            Map<String, String> before = tableFiles(root);

            JsonJournal.installStepHook(crashAt(step, occurrence));
            JsonJournal crashing = journalAt(root);
            assertCrashedAt(runOnOwnThread(() -> crashing.checkpoint(2L, checkpointEntries())), step, occurrence);
            JsonJournal.resetStepHook();

            if (!renamed) {
                assertThat(tableFiles(root)).as("no checkpoint entity file before K's rename, " + label)
                        .isEqualTo(before);
            }
            if (step.equals("checkpoint.betweenTruncations")) {
                assertThat(recordNames(root)).as(label).containsExactly(commitName(2));
            }

            journalAt(root).recover();
            if (renamed) {
                assertThat(allFiles(root)).as(label).isEqualTo(controlFiles);
            } else {
                Map<String, String> onlyJ = new TreeMap<>();
                onlyJ.put("users/x.json", entity("x", 1));
                assertThat(tableFiles(root)).as(label).isEqualTo(onlyJ);
                assertThat(recordNames(root)).as(label).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("At every truncation interruption K survives and the remaining records form a suffix ending with K")
    void betweenTruncationsLeavesASuffixEndingWithK() throws Exception {
        for (int occurrence = 1; occurrence <= 3; occurrence++) {
            Path root = tempDir.resolve("suffix-" + occurrence);
            JsonJournal journal = journalAt(root);
            for (long sequence = 1L; sequence <= 3L; sequence++) {
                journal.writeRecord(sequence, Kind.COMMIT,
                        Collections.singletonList(Entry.put(USERS, "x", entity("x", (int) sequence))));
            }

            JsonJournal.installStepHook(crashAt("checkpoint.betweenTruncations", occurrence));
            assertCrashedAt(runOnOwnThread(() -> journal.checkpoint(4L,
                    Collections.singletonList(Entry.put(USERS, "x", entity("x", 4))))),
                    "checkpoint.betweenTruncations", occurrence);
            JsonJournal.resetStepHook();

            List<String> expected = new ArrayList<>();
            for (long sequence = occurrence + 1L; sequence <= 4L; sequence++) {
                expected.add(commitName(sequence));
            }
            assertThat(recordNames(root)).as("after deletion " + occurrence).isEqualTo(expected);

            journalAt(root).recover();
            assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 4));
        }
    }

    /*
     * Deletion durability order. A power loss keeps what the last force of the journal directory
     * made durable, plus any subset of the unlinks issued after it: the file system may persist
     * them out of order. So a record's deletion is only safe to issue once the last force has
     * already removed every record its absence would expose. Each force below records the journal
     * directory's listing; "the force before the deletion" is the last listing that still holds
     * the record.
     */

    @Test
    @DisplayName("Truncation deletes checkpoint K only after the deletions of the records below it are forced to disk")
    void truncationForcesOlderDeletionsBeforeDeletingK() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        for (long sequence = 1L; sequence <= 3L; sequence++) {
            journal.writeRecord(sequence, Kind.COMMIT,
                    Collections.singletonList(Entry.put(USERS, "x", entity("x", (int) sequence))));
        }
        List<List<String>> forcedListings = recordJournalForces(root);

        journal.checkpoint(4L, Collections.singletonList(Entry.put(USERS, "x", entity("x", 4))));

        int firstWithoutK = firstListingWithout(forcedListings, commitName(4));
        assertThat(firstWithoutK).as("forced listings %s", forcedListings).isGreaterThan(0);
        assertThat(forcedListings.get(firstWithoutK - 1))
                .as("the last forced listing before K's deletion, of %s", forcedListings)
                .containsExactly(commitName(4));
        assertThat(recordNames(root)).isEmpty();
    }

    @Test
    @DisplayName("Recovery deletes each applied record only after the deletions of the records below it are forced")
    void recoveryForcesEachDeletionBeforeTheNext() throws Exception {
        Path root = tempDir.resolve("root");
        JsonJournal journal = journalAt(root);
        for (long sequence = 1L; sequence <= 3L; sequence++) {
            journal.writeRecord(sequence, Kind.COMMIT,
                    Collections.singletonList(Entry.put(USERS, "x", entity("x", (int) sequence))));
        }
        List<List<String>> forcedListings = recordJournalForces(root);
        forcedListings.add(recordNames(root));

        journalAt(root).recover();

        for (long sequence = 2L; sequence <= 3L; sequence++) {
            int firstWithout = firstListingWithout(forcedListings, commitName(sequence));
            assertThat(firstWithout).as("forced listings %s", forcedListings).isGreaterThan(0);
            for (long older = 1L; older < sequence; older++) {
                assertThat(forcedListings.get(firstWithout - 1))
                        .as("the last forced listing before record %d's deletion, of %s", sequence, forcedListings)
                        .doesNotContain(commitName(older));
            }
        }
        assertThat(recordNames(root)).isEmpty();
        assertThat(read(root.resolve(USERS).resolve("x.json"))).isEqualTo(entity("x", 3));
    }

    /** Records the journal directory's record names at every force of that directory, from now on. */
    private static List<List<String>> recordJournalForces(Path root) {
        List<List<String>> listings = new CopyOnWriteArrayList<>();
        Path journalDirectory = journalDir(root);
        JsonJournal.installDirectoryForcer(directory -> {
            if (journalDirectory.equals(directory)) {
                listings.add(recordNames(root));
            }
        });
        return listings;
    }

    /** The index of the first listing without {@code name}, or -1. */
    private static int firstListingWithout(List<List<String>> listings, String name) {
        for (int i = 0; i < listings.size(); i++) {
            if (!listings.get(i).contains(name)) {
                return i;
            }
        }
        return -1;
    }

    // ===== Damaged committed record (question 8 option A) =====

    @Test
    @DisplayName("A record whose checksum does not match is refused, naming the file, and nothing changes")
    void damagedChecksumIsRefusedNamingTheFile() throws Exception {
        Path root = tempDir.resolve("root");
        seedDamageScenarioTables(root);
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        Path record = journalDir(root).resolve(commitName(1));
        byte[] bytes = Files.readAllBytes(record);
        int last = bytes.length - 2;
        bytes[last] = (byte) (bytes[last] == '0' ? '1' : '0');
        Files.write(record, bytes);

        assertRefusedWithoutChange(root, record);
    }

    @Test
    @DisplayName("A record whose first line is cut short is refused, naming the file, and nothing changes")
    void cutShortFirstLineIsRefused() throws Exception {
        for (boolean keepChecksumLine : new boolean[] {true, false}) {
            Path root = tempDir.resolve("cut-" + keepChecksumLine);
            seedDamageScenarioTables(root);
            JsonJournal journal = journalAt(root);
            journal.writeRecord(1L, Kind.COMMIT,
                    Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
            Path record = journalDir(root).resolve(commitName(1));
            String text = read(record);
            int newline = text.indexOf('\n');
            String firstHalf = text.substring(0, newline / 2);
            String cut = keepChecksumLine ? firstHalf + text.substring(newline) : firstHalf;
            Files.write(record, cut.getBytes(StandardCharsets.UTF_8));

            assertRefusedWithoutChange(root, record);
        }
    }

    @Test
    @DisplayName("A damaged record after valid ones is refused before any record is applied or deleted")
    void damagedRecordAfterValidOnesChangesNoTableFile() throws Exception {
        Path root = tempDir.resolve("root");
        seedDamageScenarioTables(root);
        seedDamagedThirdRecord(root);

        assertRefusedWithoutChange(root, journalDir(root).resolve(commitName(3)));
    }

    @Test
    @DisplayName("Recovery keeps refusing until the damaged record is moved aside, then applies the rest")
    void recoveryProceedsOnceTheDamagedRecordIsMovedAside() throws Exception {
        Path root = tempDir.resolve("root");
        seedDamageScenarioTables(root);
        seedDamagedThirdRecord(root);
        Path damaged = journalDir(root).resolve(commitName(3));
        byte[] damagedBytes = Files.readAllBytes(damaged);

        assertRefusedWithoutChange(root, damaged);
        assertRefusedWithoutChange(root, damaged);

        Path aside = tempDir.resolve("aside");
        Files.createDirectories(aside);
        Files.move(damaged, aside.resolve(damaged.getFileName()));
        journalAt(root).recover();

        Map<String, String> expected = new TreeMap<>();
        expected.put("users/a.json", entity("a", 1));
        expected.put("users/b.json", entity("b", 1));
        assertThat(tableFiles(root)).isEqualTo(expected);
        assertThat(recordNames(root)).isEmpty();
        assertThat(Files.readAllBytes(aside.resolve(damaged.getFileName()))).isEqualTo(damagedBytes);
    }

    // ===== Scenario and assertion helpers =====

    /** users/x = 0 on disk, then committed record 1 with x = 1. */
    private static void seedCheckpointScenario(Path root) throws IOException {
        Files.createDirectories(root.resolve(USERS));
        Files.write(root.resolve(USERS).resolve("x.json"), entity("x", 0).getBytes(StandardCharsets.UTF_8));
        journalAt(root).writeRecord(1L, Kind.COMMIT,
                Collections.singletonList(Entry.put(USERS, "x", entity("x", 1))));
    }

    /** Checkpoint K = 2: x = 2 and z = 2. */
    private static List<Entry> checkpointEntries() {
        return Arrays.asList(Entry.put(USERS, "x", entity("x", 2)), Entry.put(USERS, "z", entity("z", 2)));
    }

    /** users/a = 0 on disk. */
    private static void seedDamageScenarioTables(Path root) throws IOException {
        Files.createDirectories(root.resolve(USERS));
        Files.write(root.resolve(USERS).resolve("a.json"), entity("a", 0).getBytes(StandardCharsets.UTF_8));
    }

    /** Valid records 1 (a = 1) and 2 (b = 1), then record 3 (a = 3) with a changed first line. */
    private static void seedDamagedThirdRecord(Path root) throws Exception {
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 1))));
        journal.writeRecord(2L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "b", entity("b", 1))));
        journal.writeRecord(3L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 3))));
        tamperFirstLine(journalDir(root).resolve(commitName(3)));
    }

    /**
     * Recovery through a new journal throws a DataAccessException naming the damaged file, and
     * every file under the identity root, records included, is byte-identical afterwards.
     */
    private static void assertRefusedWithoutChange(Path root, Path damaged) throws IOException {
        Map<String, String> before = allFiles(root);
        assertThatThrownBy(() -> journalAt(root).recover())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(damaged.getFileName().toString());
        assertThat(allFiles(root)).isEqualTo(before);
    }

    /**
     * Pre-existing users/b and users/keep, then three committed records: (1) put users/a = 1 and
     * users/b = 1; (2) put orders/o1 = 1 and delete users/b; (3) put users/a = 3.
     */
    private static void seedRecoveryScenario(Path root) throws IOException {
        Path users = root.resolve(USERS);
        Files.createDirectories(users);
        Files.write(users.resolve("b.json"), entity("b", 0).getBytes(StandardCharsets.UTF_8));
        Files.write(users.resolve("keep.json"), entity("keep", 0).getBytes(StandardCharsets.UTF_8));
        JsonJournal journal = journalAt(root);
        journal.writeRecord(1L, Kind.COMMIT, Arrays.asList(
                Entry.put(USERS, "a", entity("a", 1)),
                Entry.put(USERS, "b", entity("b", 1))));
        journal.writeRecord(2L, Kind.COMMIT, Arrays.asList(
                Entry.put(ORDERS, "o1", entity("o1", 1)),
                Entry.delete(USERS, "b")));
        journal.writeRecord(3L, Kind.COMMIT, Collections.singletonList(Entry.put(USERS, "a", entity("a", 3))));
    }

    private static Map<String, String> expectedRecoveredTables() {
        Map<String, String> expected = new TreeMap<>();
        expected.put("orders/o1.json", entity("o1", 1));
        expected.put("users/a.json", entity("a", 3));
        expected.put("users/keep.json", entity("keep", 0));
        return expected;
    }

    private static List<Entry> twoTableEntries() {
        return Arrays.asList(Entry.put(USERS, "u1", entity("u1", 1)), Entry.put(ORDERS, "o1", entity("o1", 1)));
    }

    private static Map<String, String> twoTableFiles() {
        Map<String, String> expected = new TreeMap<>();
        expected.put("orders/o1.json", entity("o1", 1));
        expected.put("users/u1.json", entity("u1", 1));
        return expected;
    }

    static JsonJournal journalAt(Path root) {
        return new JsonJournal(root, journalDir(root));
    }

    static Path journalDir(Path root) {
        return root.resolve(JsonJournal.DIRECTORY_NAME);
    }

    static String entity(String id, int value) {
        return "{\"id\":\"" + id + "\",\"value\":" + value + "}";
    }

    static String commitName(long sequence) {
        return String.format("%019d.commit", sequence);
    }

    static JsonJournal.StepHook crashAt(String step, int occurrence) {
        AtomicInteger seen = new AtomicInteger();
        return name -> {
            if (name.equals(step) && seen.incrementAndGet() == occurrence) {
                throw new SimulatedCrash(step + "#" + occurrence);
            }
        };
    }

    static void assertCrashedAt(Throwable thrown, String step, int occurrence) {
        assertThat(thrown).as("the simulated crash at " + step + "#" + occurrence)
                .isInstanceOf(SimulatedCrash.class)
                .hasMessage(step + "#" + occurrence);
    }

    /**
     * Runs a journal call on its own thread, as a crashing server thread would, and returns what it
     * threw ({@code null} if nothing).
     */
    // PMD.AvoidCatchingThrowable: the simulated crash is an Error; the helper hands it back to the
    // test, which asserts on it.
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    static Throwable runOnOwnThread(JournalCall call) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                call.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "json-journal-crash");
        thread.setDaemon(true);
        thread.start();
        thread.join(10_000L);
        assertThat(thread.isAlive()).as("the journal call finished").isFalse();
        return thrown.get();
    }

    static List<String> recordNames(Path root) throws IOException {
        Path directory = journalDir(root);
        List<String> names = new ArrayList<>();
        for (String name : names(directory)) {
            if (name.endsWith(".commit") || name.endsWith(".tmp")) {
                names.add(name);
            }
        }
        return names;
    }

    static List<String> names(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Every regular file under the identity root except the journal's, by relative path. */
    static Map<String, String> tableFiles(Path root) throws IOException {
        Map<String, String> files = allFiles(root);
        files.keySet().removeIf(name -> name.startsWith(JsonJournal.DIRECTORY_NAME + "/"));
        return files;
    }

    /** Every regular file under the identity root, journal included, by relative path. */
    static Map<String, String> allFiles(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                files.put(root.relativize(path).toString().replace('\\', '/'), read(path));
            }
        }
        return files;
    }

    static Map<String, FileTime> modificationTimes(Path root) throws IOException {
        Map<String, FileTime> times = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                times.put(root.relativize(path).toString(), Files.getLastModifiedTime(path));
            }
        }
        return times;
    }

    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Changes one character of a record's first line, leaving its checksum line as it was. */
    static void tamperFirstLine(Path record) throws IOException {
        byte[] bytes = Files.readAllBytes(record);
        int index = new String(bytes, StandardCharsets.UTF_8).indexOf("\"value\\\":");
        assertThat(index).as("tamper target in " + record).isGreaterThan(0);
        int digit = index + "\"value\\\":".length();
        bytes[digit] = (byte) (bytes[digit] == '9' ? '8' : '9');
        Files.write(record, bytes);
    }

    static String sha256Hex(String line) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /** SEVERE entries Bukkit's logger has received so far (the operator logs a corrupt file there). */
    static long severeBukkitLogCount() {
        Logger logger = Bukkit.getLogger();
        if (mockingDetails(logger).isMock()) {
            return mockingDetails(logger).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("log")
                            && invocation.getArguments().length > 0
                            && Level.SEVERE.equals(invocation.getArguments()[0]))
                    .count();
        }
        return SevereCounter.attachedTo(logger).count();
    }

    /** A journal call that may throw. */
    interface JournalCall {
        void run() throws Exception;
    }

    /** The test-only Error a step hook throws to simulate a crash. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String message) {
            super(message);
        }
    }

    /** Collects every record a logger publishes. */
    static final class CapturingHandler extends Handler {
        private final List<LogRecord> records;

        CapturingHandler(List<LogRecord> records) {
            this.records = records;
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** Counts SEVERE records on a real (non-mock) Bukkit logger, attached once per logger. */
    static final class SevereCounter extends Handler {
        private static final Map<Logger, SevereCounter> ATTACHED = new java.util.IdentityHashMap<>();
        private final AtomicInteger severe = new AtomicInteger();

        static synchronized SevereCounter attachedTo(Logger logger) {
            return ATTACHED.computeIfAbsent(logger, l -> {
                SevereCounter counter = new SevereCounter();
                l.addHandler(counter);
                return counter;
            });
        }

        long count() {
            return severe.get();
        }

        @Override
        public void publish(LogRecord record) {
            if (Level.SEVERE.equals(record.getLevel())) {
                severe.incrementAndGet();
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** A minimal JSON entity for the operator constructed over a table directory. */
    public static class JournalEntity extends BaseDataEntity<String> {
        private int value;

        public JournalEntity() {
            // for Gson
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }
}
