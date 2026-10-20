package com.ultikits.ultitools.interfaces.impl.data.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Entry;
import com.ultikits.ultitools.interfaces.impl.data.json.JsonJournal.Kind;

/**
 * Entity id to file name rules of the JSON backend (question 12 A of the Phase 20.4 decisions).
 * <p>
 * An id names its file {@code <id>.json}. When an insert would create a new file, the id is
 * refused on every operating system if no common file system can hold that name: a Windows
 * device name with or without an extension (as sanitize-filename, filename-reserved-regex and
 * Mercurial's store encoding treat it), a trailing dot or space, {@code .} or {@code ..}, a
 * control character, a path separator, or a file name longer than 255 UTF-8 bytes (Git's
 * {@code check-ref-format} and pathvalidate's universal rules). A character only some file
 * systems reject ({@code < > : " | ? *} on Windows) is refused only where the server's own file
 * system rejects the name; Linux keeps accepting {@code :} (UltiRemoteBag's {@code <uuid>:<page>}).
 * A file that already exists is never renamed: it loads, and updates and deletes of its id keep
 * working. A checkpoint or a recovery that meets an id its file system cannot name fails with a
 * checked, named error instead of an unchecked {@link InvalidPathException}.
 * <p>
 * The tests run on Linux; the Windows-only branch resolves names through
 * {@link JsonFileNames}'s resolver seam, replaced by one that rejects what Windows rejects.
 */
@Timeout(60)
@DisplayName("JSON backend: entity id to file name rules")
class JsonEntityFileNameTest {

    /** The characters a Windows file name cannot contain, besides the separators and control characters. */
    private static final String WINDOWS_ONLY_CHARACTERS = "<>:\"|?*";

    /** The directory name, next to a table directory, that models another Windows drive. */
    private static final String OTHER_DRIVE_PREFIX = "drive-";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setUpServer() {
        if (Bukkit.getServer() == null) {
            Server mockServer = mock(Server.class);
            when(mockServer.getLogger()).thenReturn(mock(Logger.class));
            org.mockito.Mockito.lenient().when(mockServer.getVersion()).thenReturn("MockBukkit (MC: 1.21.11)");
            org.mockito.Mockito.lenient().when(mockServer.getBukkitVersion()).thenReturn("1.21.11-R0.1-SNAPSHOT");
            Bukkit.setServer(mockServer);
        }
    }

    @AfterEach
    void resetResolver() {
        JsonFileNames.resetResolver();
    }

    static Stream<Arguments> refusedIds() {
        return Stream.of(
                Arguments.of("Windows device name", "CON"),
                Arguments.of("Windows device name, lower case", "con"),
                Arguments.of("Windows device name with an extension", "Con.txt"),
                Arguments.of("Windows device name NUL with .json", "nul.json"),
                Arguments.of("Windows device name PRN", "prn"),
                Arguments.of("Windows device name AUX", "Aux"),
                Arguments.of("Windows device name COM1", "COM1"),
                Arguments.of("Windows device name COM9 with an extension", "com9.log"),
                Arguments.of("Windows device name LPT1", "lpt1"),
                Arguments.of("Windows device name LPT9 with an extension", "LPT9.x"),
                Arguments.of("Windows device name COM with superscript one", "COM¹"),
                Arguments.of("Windows device name com with superscript two, lower case", "com²"),
                Arguments.of("Windows device name LPT with superscript three and an extension", "Lpt³.txt"),
                Arguments.of("Windows device name LPT with superscript one", "LPT¹"),
                Arguments.of("trailing dot", "page."),
                Arguments.of("trailing space", "page "),
                Arguments.of("dot", "."),
                Arguments.of("dot dot", ".."),
                Arguments.of("control character U+0001", "a\u0001b"),
                Arguments.of("control character U+001F", "a\u001fb"),
                Arguments.of("control character U+007F", "a\u007fb"),
                Arguments.of("tab", "a\tb"),
                Arguments.of("slash", "a/b"),
                Arguments.of("backslash", "a\\b"),
                Arguments.of("file name of 256 bytes", repeat('a', 251)),
                Arguments.of("file name over 255 bytes in UTF-8", repeat('é', 126)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedIds")
    @DisplayName("insert refuses, on every operating system, an id no common file system can name, and stores nothing")
    void insertRefusesAnIdThatCannotNameAPortableFile(String why, String id) throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);

        Throwable thrown = catchThrowable(() -> rows.insert(new FileRow(id, "refused")));

        assertThat(thrown).as(why).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getErrorCode()).isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
        assertThat(thrown.getMessage()).as("the refusal names the id").contains(shown(id));
        assertThat(rows.getAll()).as("nothing was stored").isEmpty();
        rows.flush();
        assertThat(entityFileNames(table)).as("no file was written").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"console", "com0", "com10", "lpt", "auxiliary", "a.b", ".hidden",
            "123e4567-e89b-12d3-a456-426614174000:1", "primary-wallet-merge"})
    @DisplayName("insert accepts ids the rules do not cover, including UltiRemoteBag's <uuid>:<page> on Linux")
    void insertAcceptsOrdinaryIds(String id) throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);

        rows.insert(new FileRow(id, "kept"));
        rows.flush();

        assertThat(entityFileNames(table)).containsExactly(id + ".json");
        assertThat(new SimpleJsonDataOperator<>(table.toString(), FileRow.class).getById(id).getName())
                .as("the entity loads again").isEqualTo("kept");
    }

    @Test
    @DisplayName("A file name of exactly 255 UTF-8 bytes is accepted")
    void insertAcceptsAFileNameOfExactly255Bytes() throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        String id = repeat('a', 250);

        rows.insert(new FileRow(id, "kept"));
        rows.flush();

        assertThat(entityFileNames(table)).containsExactly(id + ".json");
    }

    @Test
    @DisplayName("Inside a transaction a refused id stages nothing and leaves the transaction committable")
    void refusedIdInsideATransactionLeavesItCommittable() throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        List<Throwable> caught = new ArrayList<>();

        rows.transaction(() -> {
            rows.insert(new FileRow("before", "kept"));
            caught.add(catchThrowable(() -> rows.insert(new FileRow("LPT1", "refused"))));
            rows.insert(new FileRow("after", "kept"));
        });

        assertThat(caught).singleElement().isInstanceOf(DataAccessException.class);
        assertThat(rows.getById("before")).isNotNull();
        assertThat(rows.getById("after")).isNotNull();
        assertThat(rows.getById("LPT1")).isNull();
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"<", ">", ":", "\"", "|", "?", "*"})
    @DisplayName("Where the file system rejects a character (Windows), an id containing it is refused at insert")
    void insertRefusesAnIdTheServersFileSystemCannotName(String character) throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindows);
        String id = "123e4567-e89b-12d3-a456-426614174000" + character + "1";

        Throwable thrown = catchThrowable(() -> rows.insert(new FileRow(id, "refused")));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getErrorCode()).isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
        assertThat(thrown.getMessage()).as("the refusal names the id").contains(id);
        assertThat(rows.getAll()).as("nothing was stored").isEmpty();
    }

    // ===== The resolved file must be <table directory>/<id>.json =====

    static Stream<Arguments> drivePrefixedIds() {
        return Stream.of(
                Arguments.of("same drive letter as the table directory (would alias id 'foo')", "c:foo"),
                Arguments.of("same drive letter, upper case", "C:foo"),
                Arguments.of("another drive letter (would land outside the data folder)", "d:foo"),
                Arguments.of("prefix-namespaced UUID", "p:123e4567-e89b-12d3-a456-426614174000"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("drivePrefixedIds")
    @DisplayName("Where the file system reads 'x:' as a drive prefix (Windows), an id starting with it is refused at insert")
    void insertRefusesAnIdTheFileSystemReadsAsADrivePrefix(String why, String id) throws Exception {
        Path table = tempDir.resolve("rows");
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindowsWithDrives);

        Throwable thrown = catchThrowable(() -> rows.insert(new FileRow(id, "refused")));

        assertThat(thrown).as(why).isInstanceOf(DataAccessException.class);
        assertThat(((DataAccessException) thrown).getErrorCode()).isEqualTo(ErrorCode.DATA_ENTITY_INVALID);
        assertThat(thrown.getMessage()).as("the refusal names the id").contains(id);
        assertThat(rows.getAll()).as("nothing was stored").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("drivePrefixedIds")
    @DisplayName("A flush meeting an id the file system reads as a drive prefix fails named, writes no record and no file anywhere")
    void checkpointOfADrivePrefixedIdFailsNamed(String why, String id) throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("checkpoint"));
        Path table = parent.resolve("rows");
        Path journal = parent.resolve("rows" + JsonJournal.DIRECTORY_NAME);
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        rows.insert(new FileRow("foo", "plain"));
        rows.insert(new FileRow(id, "prefixed"));
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindowsWithDrives);

        Throwable thrown = catchThrowable(rows::flush);

        assertThat(thrown).as("%s: a checked, named failure", why).isInstanceOf(DataAccessException.class);
        assertThat(messages(thrown)).as("the failure names the table and the id").contains("rows").contains(id);
        assertThat(recordFileNames(journal)).as("no checkpoint record was left behind").isEmpty();
        assertThat(entityFileNames(table)).as("no entity file was written").isEmpty();
        assertThat(otherDriveFiles(parent)).as("nothing was written outside the table directory").isEmpty();

        JsonFileNames.resetResolver();
        rows.flush();
        assertThat(entityFileNames(table)).as("the entries stayed pending for the next flush")
                .containsExactlyInAnyOrder("foo.json", id + ".json");
    }

    @Test
    @DisplayName("Recovery meeting an id the file system reads as a drive prefix refuses named and changes no file")
    void recoveryOfADrivePrefixedIdRefusesNamed() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("recovery"));
        Path table = Files.createDirectories(parent.resolve("rows"));
        Path journalDirectory = parent.resolve("rows" + JsonJournal.DIRECTORY_NAME);
        write(table.resolve("a.json"), "{\"id\":\"a\",\"name\":\"zero\"}");
        JsonJournal journal = new JsonJournal(parent, journalDirectory);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(
                Entry.put("rows", "c:a", "{\"id\":\"c:a\",\"name\":\"aliased\"}")));
        journal.writeRecord(2L, Kind.COMMIT, Collections.singletonList(
                Entry.put("rows", "d:b", "{\"id\":\"d:b\",\"name\":\"elsewhere\"}")));
        Path record = journalDirectory.resolve(JsonJournal.recordFileName(1L, JsonJournal.COMMITTED_SUFFIX));
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindowsWithDrives);
        List<String> recordsBefore = recordFileNames(journalDirectory);

        Throwable thrown = catchThrowable(() -> new SimpleJsonDataOperator<>(table.toString(), FileRow.class));

        assertThat(thrown).as("a named, checked failure").isInstanceOf(DataAccessException.class);
        assertThat(messages(thrown)).as("the refusal names the record, the table and the id")
                .contains(record.getFileName().toString()).contains("rows").contains("c:a");
        assertThat(entityFileNames(table)).as("no table file changed").containsExactly("a.json");
        assertThat(read(table.resolve("a.json"))).as("id 'a' was not overwritten by id 'c:a'").contains("zero");
        assertThat(otherDriveFiles(parent)).as("nothing was written outside the table directory").isEmpty();
        assertThat(recordFileNames(journalDirectory)).as("no record deleted").isEqualTo(recordsBefore);
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"a/b", "../escaped", "sub/../x", "/absolute", "a//"})
    @DisplayName("On the real file system, an entity file that would not be <table directory>/<id>.json is refused")
    void entityFileRefusesANameThatLeavesTheTableDirectory(String key) {
        Path table = tempDir.resolve("rows");

        Throwable thrown = catchThrowable(() -> JsonFileNames.entityFile(table, key));

        assertThat(thrown).as("'%s' does not name a file in the table directory", key)
                .isInstanceOf(InvalidPathException.class);
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000:1",
            "primary-wallet-merge", "c:foo", "a..b", ".hidden", "con"})
    @DisplayName("On Linux, every id without a separator names exactly <table directory>/<id>.json, UltiRemoteBag's <uuid>:<page> included")
    void entityFileOnLinuxIsTheTableDirectoryAndTheId(String key) {
        Path absolute = tempDir.resolve("rows");
        Path relative = absolute.getFileName();

        assertThat(JsonFileNames.entityFile(absolute, key)).isEqualTo(absolute.resolve(key + ".json"));
        assertThat(JsonFileNames.entityFile(relative, key)).isEqualTo(relative.resolve(key + ".json"));
    }

    // ===== Existing files are never renamed =====

    @Test
    @DisplayName("Files whose names the rules now refuse still load, and updates and deletes of their ids keep working")
    void existingFilesWithRefusedNamesKeepWorking() throws Exception {
        Path table = Files.createDirectories(tempDir.resolve("rows"));
        write(table.resolve("con.json"), "{\"id\":\"con\",\"name\":\"device\"}");
        write(table.resolve("page..json"), "{\"id\":\"page.\",\"name\":\"dot\"}");
        write(table.resolve("lpt1.txt.json"), "{\"id\":\"lpt1.txt\",\"name\":\"extension\"}");

        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        assertThat(rows.getById("con").getName()).as("an existing file loads").isEqualTo("device");
        assertThat(rows.getById("page.").getName()).isEqualTo("dot");

        rows.insert(new FileRow("con", "ignored"));
        rows.update(new FileRow("con", "updated"));
        rows.transaction(() -> rows.update(new FileRow("page.", "updated in a transaction")));
        rows.delById("lpt1.txt");
        rows.flush();

        assertThat(entityFileNames(table)).as("no file renamed or added").containsExactly("con.json", "page..json");
        SimpleJsonDataOperator<FileRow> reopened = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        assertThat(reopened.getById("con").getName()).as("an insert of an existing id changes nothing; the update stays")
                .isEqualTo("updated");
        assertThat(reopened.getById("page.").getName()).isEqualTo("updated in a transaction");
        assertThat(reopened.getById("lpt1.txt")).as("the delete reached the disk").isNull();
    }

    // ===== Checkpoint and recovery name the failure =====

    @Test
    @DisplayName("A flush meeting an id the file system cannot name fails with a named DataAccessException and writes no record")
    void checkpointOfAnUnnameableIdFailsNamed() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("checkpoint"));
        Path table = parent.resolve("rows");
        Path journal = parent.resolve("rows" + JsonJournal.DIRECTORY_NAME);
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        rows.insert(new FileRow("kept", "plain"));
        rows.insert(new FileRow("owner:1", "colon"));
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindows);

        Throwable thrown = catchThrowable(rows::flush);

        assertThat(thrown).as("a checked, named failure, not an unchecked InvalidPathException")
                .isInstanceOf(DataAccessException.class);
        assertThat(messages(thrown)).as("the failure names the table and the id").contains("rows").contains("owner:1");
        assertThat(recordFileNames(journal)).as("no checkpoint record was left behind").isEmpty();
        assertThat(entityFileNames(table)).as("no entity file was written").isEmpty();

        JsonFileNames.resetResolver();
        rows.flush();
        assertThat(entityFileNames(table)).as("the entries stayed pending for the next flush")
                .containsExactlyInAnyOrder("kept.json", "owner:1.json");
    }

    @Test
    @DisplayName("Recovery meeting an id the file system cannot name refuses naming the record, table and id, changes nothing, and recovers once the record is moved aside")
    void recoveryOfAnUnnameableIdRefusesNamedUntilMovedAside() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("recovery"));
        Path table = Files.createDirectories(parent.resolve("rows"));
        Path journalDirectory = parent.resolve("rows" + JsonJournal.DIRECTORY_NAME);
        write(table.resolve("a.json"), "{\"id\":\"a\",\"name\":\"zero\"}");
        JsonJournal journal = new JsonJournal(parent, journalDirectory);
        journal.writeRecord(1L, Kind.COMMIT, Collections.singletonList(
                Entry.put("rows", "a", "{\"id\":\"a\",\"name\":\"one\"}")));
        journal.writeRecord(2L, Kind.COMMIT, Collections.singletonList(
                Entry.put("rows", "owner:1", "{\"id\":\"owner:1\",\"name\":\"colon\"}")));
        Path record = journalDirectory.resolve(JsonJournal.recordFileName(2L, JsonJournal.COMMITTED_SUFFIX));
        JsonFileNames.installResolver(JsonEntityFileNameTest::resolveLikeWindows);
        List<String> before = entityFileNames(table);
        List<String> recordsBefore = recordFileNames(journalDirectory);

        for (int attempt = 1; attempt <= 2; attempt++) {
            Throwable thrown = catchThrowable(() -> new SimpleJsonDataOperator<>(table.toString(), FileRow.class));
            assertThat(thrown).as("attempt %d is refused with a named, checked failure", attempt)
                    .isInstanceOf(DataAccessException.class);
            assertThat(messages(thrown)).as("the refusal names the record, the table and the id")
                    .contains(record.getFileName().toString()).contains("rows").contains("owner:1");
            assertThat(thrown.getMessage()).as("the refusal tells the operator to move the record aside")
                    .contains(journalDirectory.toAbsolutePath().toString());
            assertThat(entityFileNames(table)).as("no table file changed (attempt %d)", attempt).isEqualTo(before);
            assertThat(read(table.resolve("a.json"))).as("record 1 was not applied either").contains("zero");
            assertThat(recordFileNames(journalDirectory)).as("no record deleted").isEqualTo(recordsBefore);
        }

        Files.move(record, parent.resolve("moved-aside.commit"));
        SimpleJsonDataOperator<FileRow> rows = new SimpleJsonDataOperator<>(table.toString(), FileRow.class);
        assertThat(rows.getById("a").getName()).as("record 1 applied once the refused one is gone").isEqualTo("one");
        assertThat(rows.getById("owner:1")).isNull();
    }

    // ===== Helpers =====

    /**
     * Resolves like a Windows file system: a name holding one of {@code < > : " | ? *} is
     * rejected with the exception {@code WindowsPathParser} throws.
     */
    private static Path resolveLikeWindows(Path directory, String fileName) {
        for (int i = 0; i < fileName.length(); i++) {
            if (WINDOWS_ONLY_CHARACTERS.indexOf(fileName.charAt(i)) >= 0) {
                throw new InvalidPathException(fileName, "Illegal char <" + fileName.charAt(i) + ">", i);
            }
        }
        return directory.resolve(fileName);
    }

    /**
     * Resolves like JDK 21's {@code WindowsPathParser} and {@code WindowsPath#resolve}: a name
     * that starts with an ASCII letter and a colon is a drive-relative path, not an error. The
     * table directory is taken to be on drive {@code C:}. For that drive the result is the rest of
     * the name in the table directory ({@code c:foo.json} becomes {@code <table>/foo.json}); for
     * any other drive it is a path on that drive, modelled as a sibling directory
     * {@code drive-<letter>} of the table directory. Any other name resolves as
     * {@link #resolveLikeWindows(Path, String)} does.
     */
    private static Path resolveLikeWindowsWithDrives(Path directory, String fileName) {
        if (fileName.length() >= 2 && fileName.charAt(1) == ':' && isAsciiLetter(fileName.charAt(0))) {
            char drive = Character.toLowerCase(fileName.charAt(0));
            String rest = fileName.substring(2);
            if (drive == 'c') {
                return resolveLikeWindows(directory, rest);
            }
            return resolveLikeWindows(directory.resolveSibling(OTHER_DRIVE_PREFIX + drive), rest);
        }
        return resolveLikeWindows(directory, fileName);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Every file under the modelled other drives next to tables in {@code parent}. */
    private static List<String> otherDriveFiles(Path parent) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(child -> child.getFileName().toString().startsWith(OTHER_DRIVE_PREFIX))
                    .forEach(child -> names.add(child.getFileName().toString()));
        }
        return names;
    }

    /** {@code id} as a refusal shows it: a control character as a {@code \}{@code uXXXX} escape. */
    private static String shown(String id) {
        StringBuilder shown = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                shown.append(String.format("\\u%04x", (int) c));
            } else {
                shown.append(c);
            }
        }
        return shown.toString();
    }

    /** The messages of {@code thrown} and its causes, joined. */
    private static String messages(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static List<String> entityFileNames(Path table) throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(table)) {
            try (Stream<Path> files = Files.list(table)) {
                files.forEach(file -> names.add(file.getFileName().toString()));
            }
        }
        Collections.sort(names);
        return names;
    }

    private static List<String> recordFileNames(Path journal) throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(journal)) {
            try (Stream<Path> files = Files.list(journal)) {
                files.filter(Files::isRegularFile).forEach(file -> names.add(file.getFileName().toString()));
            }
        }
        Collections.sort(names);
        return names;
    }

    @Table("file_name_rows")
    public static class FileRow extends BaseDataEntity<String> {
        @Column("name")
        private String name;

        public FileRow() {
        }

        public FileRow(String id, String name) {
            setId(id);
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
