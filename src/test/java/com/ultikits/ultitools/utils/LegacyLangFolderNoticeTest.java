package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.text.internal.FrameworkMessages;

class LegacyLangFolderNoticeTest {
    @TempDir Path temporary;

    private final List<LogRecord> records = new ArrayList<>();
    private Path dataFolder;
    private Logger logger;

    @BeforeEach
    void setUp() throws IOException {
        dataFolder = Files.createDirectories(temporary.resolve("plugins/UltiTools"));
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* No buffered output. */ }
            @Override public void close() { /* No external resource. */ }
        });
    }

    @Test
    void absentDataFolderStaysAbsent() throws Exception {
        Files.delete(dataFolder);

        report("en");

        assertThat(dataFolder).doesNotExist();
        assertThat(records).isEmpty();
    }

    @Test
    void noLegacyDirectoriesProducesNoLog() throws Exception {
        Path module = Files.createDirectories(dataFolder.resolve("pluginConfig/UltiChat"));
        Files.createDirectory(module.resolve("messages"));
        Files.createDirectories(module.resolve("nested/lang"));
        List<Path> entries = directoryEntries();

        report("en");

        assertThat(directoryEntries()).containsExactlyElementsOf(entries);
        assertThat(records).isEmpty();
    }

    @Test
    void frameworkDirectoryProducesOneInfoLineWithLiteralMigrationPath() throws Exception {
        Files.createDirectory(dataFolder.resolve("lang"));
        List<Path> entries = directoryEntries();

        report("en");

        assertThat(directoryEntries()).containsExactlyElementsOf(entries);
        assertThat(infoLine()).isEqualTo("UltiTools 7.0 no longer reads lang/ folders: plugins/UltiTools/lang. "
                + "Write any translations you changed to messages/<locale>.yml in the new format.");
    }

    @Test
    void frameworkAndModuleDirectoriesShareOneLineInLexicalOrder() throws Exception {
        Files.createDirectories(dataFolder.resolve("pluginConfig/UltiEssentials/lang"));
        Files.createDirectories(dataFolder.resolve("lang"));
        Files.createDirectories(dataFolder.resolve("pluginConfig/UltiChat/lang"));

        report("en");

        assertThat(infoLine()).contains("plugins/UltiTools/lang, plugins/UltiTools/pluginConfig/UltiChat/lang, "
                + "plugins/UltiTools/pluginConfig/UltiEssentials/lang.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void truncatesAfterTwentySortedPathsWithLocalizedRemainder(String locale) throws Exception {
        for (int i = 22; i >= 0; i--) {
            Files.createDirectories(dataFolder.resolve("pluginConfig/Module" + (100 + i) + "/lang"));
        }
        String[] paths = IntStream.range(100, 120)
                .mapToObj(i -> "plugins/UltiTools/pluginConfig/Module" + i + "/lang")
                .toArray(String[]::new);
        String folders = String.join(", ", paths);

        report(locale);

        assertThat(infoLine()).isEqualTo("en".equals(locale)
                ? "UltiTools 7.0 no longer reads lang/ folders: " + folders + ", and 3 more. "
                    + "Write any translations you changed to messages/<locale>.yml in the new format."
                : "7.0 不再读取 lang/ 文件夹：" + folders + ", 另有 3 个。改过的翻译请按新格式写到 messages/<语言>.yml。");
    }

    @Test
    void ignoresRegularFilesNamedLang() throws Exception {
        Files.createFile(dataFolder.resolve("lang"));
        Path module = Files.createDirectories(dataFolder.resolve("pluginConfig/UltiChat"));
        Files.createFile(module.resolve("lang"));

        report("en");

        assertThat(records).isEmpty();
    }

    @Test
    void ignoresLinkedLangDirectoriesAndLinkedModuleDirectories() throws Exception {
        Path other = Files.createDirectories(temporary.resolve("outside/lang"));
        Path config = Files.createDirectory(dataFolder.resolve("pluginConfig"));
        Path module = Files.createDirectory(config.resolve("UltiChat"));
        Files.createSymbolicLink(dataFolder.resolve("lang"), other);
        Files.createSymbolicLink(module.resolve("lang"), other);
        Files.createSymbolicLink(config.resolve("LinkedModule"), other.getParent());

        report("en");

        assertThat(records).isEmpty();
    }

    @Test
    void findsLegacyDirectoryThroughALinkedDataFolder() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("linked-data-target"));
        Files.createDirectory(target.resolve("lang"));
        Files.delete(dataFolder);
        Files.createSymbolicLink(dataFolder, target);

        report("en");

        assertThat(infoLine()).contains("plugins/UltiTools/lang.").doesNotContain(target.toString());
    }

    @Test
    void findsLegacyDirectoryThroughALinkedPluginConfigDirectory() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("linked-config-target"));
        Files.createDirectories(target.resolve("UltiChat/lang"));
        Files.createSymbolicLink(dataFolder.resolve("pluginConfig"), target);

        report("en");

        assertThat(infoLine()).contains("plugins/UltiTools/pluginConfig/UltiChat/lang.")
                .doesNotContain(target.toString());
    }

    @Test
    void legacyFileBytesModificationTimesAndDirectoryEntriesAreUnchanged() throws Exception {
        Path framework = Files.createDirectories(dataFolder.resolve("lang"));
        Path module = Files.createDirectories(dataFolder.resolve("pluginConfig/UltiChat/lang"));
        Path frameworkFile = writeOldTranslation(framework.resolve("zh.json"));
        Path moduleFile = writeOldTranslation(module.resolve("en.json"));
        List<Path> entries = directoryEntries();
        List<String> hashes = List.of(sha256(frameworkFile), sha256(moduleFile));
        List<FileTime> times = List.of(Files.getLastModifiedTime(frameworkFile), Files.getLastModifiedTime(moduleFile));
        FileTime oldTime = FileTime.from(Instant.parse("2020-01-02T03:04:05Z"));
        Files.setLastModifiedTime(framework, oldTime);
        Files.setLastModifiedTime(module, oldTime);

        report("en");

        assertThat(infoLine()).contains("plugins/UltiTools/lang, plugins/UltiTools/pluginConfig/UltiChat/lang.");
        assertThat(directoryEntries()).containsExactlyElementsOf(entries);
        assertThat(List.of(sha256(frameworkFile), sha256(moduleFile))).containsExactlyElementsOf(hashes);
        assertThat(List.of(Files.getLastModifiedTime(frameworkFile), Files.getLastModifiedTime(moduleFile)))
                .containsExactlyElementsOf(times);
        assertThat(List.of(Files.getLastModifiedTime(framework), Files.getLastModifiedTime(module)))
                .containsExactly(oldTime, oldTime);
    }

    @Test
    void aLineBreakInADirectoryNameDoesNotSplitTheNotice() throws Exception {
        Files.createDirectories(dataFolder.resolve("pluginConfig/Ulti\nChat/lang"));

        report("en");

        assertThat(infoLine()).contains("plugins/UltiTools/pluginConfig/Ulti Chat/lang").doesNotContain("\n");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedConfigScanLogsOnlyOneFineDiagnosticWithoutLosingTheFrameworkMatch(boolean securityFailure)
            throws Exception {
        Path config = Files.createDirectory(dataFolder.resolve("pluginConfig"));
        Files.createDirectory(dataFolder.resolve("lang"));
        try (var filesystem = mockStatic(Files.class, call -> {
            if ("newDirectoryStream".equals(call.getMethod().getName()) && config.equals(call.getArgument(0))) {
                if (securityFailure) {
                    throw new SecurityException("directory listing denied");
                }
                throw new AccessDeniedException(config.toString());
            }
            return call.callRealMethod();
        })) {
            report("en");
        }

        assertThat(records).extracting(LogRecord::getLevel).containsExactly(Level.FINE, Level.INFO);
        assertThat(records.getFirst().getMessage()).contains(securityFailure ? "directory listing denied" : "pluginConfig");
        assertThat(records.getLast().getMessage()).contains("plugins/UltiTools/lang.");
    }

    private void report(String code) throws Exception {
        try (var support = new CommandMessageTestSupport(code);
             var scope = FrameworkMessages.overrideForTests(support.messages(), Locale.forLanguageTag(code))) {
            LegacyLangFolderNotice.report(dataFolder.toFile(), logger);
        }
    }

    private String infoLine() {
        assertThat(records).extracting(LogRecord::getLevel).containsExactly(Level.INFO);
        return records.getFirst().getMessage();
    }

    private List<Path> directoryEntries() throws IOException {
        try (Stream<Path> entries = Files.walk(dataFolder)) {
            return entries.sorted().toList();
        }
    }

    private static Path writeOldTranslation(Path file) throws IOException {
        Files.write(file, "legacy content, not a catalogue".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-02T03:04:05Z")));
        return file;
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
