package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #459, as the maintainer answered it on 2026-09-29 (question 3, option 2): on the first start
 * after upgrading, a language file that has no provenance record and differs from the jar's copy
 * is replaced by the jar's copy and recorded; the previous file is kept as a backup beside it,
 * under a name no catalogue lookup resolves and that never overwrites an earlier file; one log line
 * names the file and its backup. This includes a file an operator edited -- accepted in writing.
 * <p>
 * Each test runs a real module start: the module's own constructor (extraction, then resolution
 * without writes) followed by {@link UltiToolsPlugin#commitLanguageProvenance()}, which is what
 * {@code PluginManager} calls once the load gates accept the module (#460).
 */
@DisplayName("#459: unrecorded language files are replaced on the upgrade start, with a backup")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework-catalogue control reads a private field
class UltiToolsPluginUnrecordedLanguageReplacementTest {

    private static final String LANG = "lang/en.json";
    private static final String OLD = "{\"greeting\":\"Hello from the 6.2 jar\"}";
    private static final String NEW = "{\"greeting\":\"Hello from the 6.3 jar\"}";

    /**
     * The framework catalogue key of the replacement line, spelled out rather than read from the
     * production constant, so the exact operator-visible text is pinned here.
     */
    private static final String REPLACED_LINE_KEY = "Language file '%s' of module '%s' had no provenance "
            + "record and differed from the version bundled with this release, so it was replaced by the "
            + "bundled version: this release cannot tell whether it had been edited. The previous file was "
            + "kept as '%s'; to restore it, stop the server and rename it back.";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = BootLanguageFixture.create(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private UltiToolsPlugin start() throws Exception {
        UltiToolsPlugin plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();
        return plugin;
    }

    private File langDir() {
        return fixture.disk(LANG).getParentFile();
    }

    private String[] langListing() {
        String[] names = langDir().list();
        Arrays.sort(names);
        return names;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void verifyNoLanguageLog() {
        verify(fixture.logger(), never()).warning(anyString());
        verify(fixture.logger(), never()).info(anyString());
        verify(fixture.logger(), never()).severe(anyString());
    }

    @Test
    @DisplayName("unrecorded and different: replaced by the jar's copy, recorded, backup holds the old bytes, one log line names both")
    void unrecordedDifferingFileIsReplacedRecordedAndBackedUpWithOneLogLine() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD);

        UltiToolsPlugin plugin = start();

        File backup = new File(langDir(), "en.json.bak");
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(NEW));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG))
                .contains(ResourceHashSidecar.sha256(fixture.disk(LANG)));
        assertThat(BootLanguageFixture.bytesOf(backup)).isEqualTo(utf8(OLD));
        assertThat(langListing()).containsExactly("en.json", "en.json.bak");
        assertThat(plugin.i18n("greeting")).isEqualTo("Hello from the 6.3 jar");
        verify(fixture.logger(), times(1)).warning(argThat((String message) ->
                message.contains(fixture.disk(LANG).getPath()) && message.contains(backup.getPath())));
        verify(fixture.logger(), times(1)).warning(anyString());
        verify(fixture.logger(), never()).info(anyString());
        verify(fixture.logger(), never()).severe(anyString());
    }

    @Test
    @DisplayName("a second start over that result changes nothing: no new backup, no log line")
    void secondStartChangesNothing() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD);
        start();
        byte[] fileAfterFirst = BootLanguageFixture.bytesOf(fixture.disk(LANG));
        byte[] recordAfterFirst = BootLanguageFixture.bytesOf(fixture.provenanceRecord());
        clearInvocations(fixture.logger());

        start();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(fileAfterFirst);
        assertThat(BootLanguageFixture.bytesOf(fixture.provenanceRecord())).isEqualTo(recordAfterFirst);
        assertThat(langListing()).containsExactly("en.json", "en.json.bak");
        verifyNoLanguageLog();
    }

    @Test
    @DisplayName("unrecorded and byte-identical: recorded, no backup, no log line")
    void unrecordedIdenticalFileIsRecordedWithoutBackupOrLog() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, NEW);

        start();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(NEW));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG))
                .contains(ResourceHashSidecar.sha256(fixture.disk(LANG)));
        assertThat(langListing()).containsExactly("en.json");
        verifyNoLanguageLog();
    }

    @Test
    @DisplayName("recorded, then edited: kept unchanged (today's rule), no backup")
    void recordedThenEditedFileIsKept() throws Exception {
        String edited = "{\"greeting\":\"Hello, edited by the operator\"}";
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD).recordCurrent(LANG).onDisk(LANG, edited);

        UltiToolsPlugin plugin = start();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(edited));
        assertThat(langListing()).containsExactly("en.json");
        assertThat(plugin.i18n("greeting")).isEqualTo("Hello, edited by the operator");
    }

    @Test
    @DisplayName("backup name taken: the new backup gets a distinct name and the existing file is untouched")
    void takenBackupNameIsNeverOverwritten() throws Exception {
        String earlier = "an earlier backup or an operator file of that name";
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD).onDisk("lang/en.json.bak", earlier);

        start();

        assertThat(BootLanguageFixture.bytesOf(new File(langDir(), "en.json.bak"))).isEqualTo(utf8(earlier));
        assertThat(BootLanguageFixture.bytesOf(new File(langDir(), "en.json.1.bak"))).isEqualTo(utf8(OLD));
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(NEW));
        assertThat(langListing()).containsExactly("en.json", "en.json.1.bak", "en.json.bak");
    }

    @Test
    @DisplayName("a language folder holding only a backup: the backup is never loaded as a catalogue")
    void backupIsNeverResolvedAsACatalogue() throws Exception {
        fixture.onDisk("lang/en.json.bak", "{\"greeting\":\"text inside a backup\"}");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("greeting");
        assertThat(Localized.scanLangDirectory(langDir())).isEmpty();
    }

    @Test
    @DisplayName("failure making the backup (folder not writable): original in place, nothing recorded, no backup")
    void failureCreatingTheBackupLeavesTheOriginal() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD);
        UltiToolsPlugin plugin = fixture.construct("6.3.0");
        assertThat(langDir().setWritable(false)).isTrue();
        try {
            plugin.commitLanguageProvenance();
        } finally {
            langDir().setWritable(true);
        }

        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(OLD));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG)).isEmpty();
        assertThat(langListing()).containsExactly("en.json");
    }

    @Test
    @DisplayName("failure moving the staged copy into place (file pinned read-only): original in place, nothing recorded, no backup")
    void failureReplacingTheFileLeavesTheOriginal() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD);
        assertThat(fixture.disk(LANG).setWritable(false)).isTrue();

        start();

        fixture.disk(LANG).setWritable(true);
        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(OLD));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG)).isEmpty();
        assertThat(langListing()).containsExactly("en.json");
    }

    @Test
    @DisplayName("failure recording provenance (record pinned read-only): original restored byte-identical, nothing recorded, no backup")
    void failureRecordingRestoresTheOriginal() throws Exception {
        fixture.jarEntry(LANG, NEW).onDisk(LANG, OLD);
        File record = fixture.provenanceRecord();
        Files.write(record.toPath(), utf8("{}"));
        assertThat(record.setWritable(false)).isTrue();

        UltiToolsPlugin plugin;
        try {
            plugin = start();
        } finally {
            record.setWritable(true);
        }

        assertThat(BootLanguageFixture.bytesOf(fixture.disk(LANG))).isEqualTo(utf8(OLD));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), LANG)).isEmpty();
        assertThat(langListing()).containsExactly("en.json");
        assertThat(plugin.i18n("greeting")).isEqualTo("Hello from the 6.2 jar");
    }

    @Test
    @DisplayName("control: the framework's own catalogue is read from its jar, never from plugins/UltiTools/lang/")
    void frameworkCatalogueIsReadFromItsJarNotFromDisk() throws Exception {
        File dataFolder = new File(tempDir, "UltiTools");
        File staleOnDisk = new File(dataFolder, "lang" + File.separator + "en.json");
        Files.createDirectories(staleOnDisk.getParentFile().toPath());
        Files.write(staleOnDisk.toPath(), utf8("{\"Module '%s' reloaded.\":\"a stale on-disk value\"}"));
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "en");
        UltiTools ultiTools = TestHelper.mockUltiToolsInstance(mock -> {
            Mockito.lenient().when(mock.getConfig()).thenReturn(config);
            Mockito.lenient().when(mock.getDataFolder()).thenReturn(dataFolder);
        });

        Method initLanguage = UltiTools.class.getDeclaredMethod("initLanguage");
        initLanguage.setAccessible(true);
        initLanguage.invoke(ultiTools);
        Field languageField = UltiTools.class.getDeclaredField("language");
        languageField.setAccessible(true);
        Language language = (Language) languageField.get(ultiTools);

        assertThat(language.getLocalizedText("Module '%s' reloaded.")).isEqualTo("Module '%s' reloaded.");
    }

    @Test
    @DisplayName("both shipped framework catalogues translate the replacement line, and it formats with its three arguments")
    void bothCataloguesTranslateTheReplacementLine() throws IOException {
        for (String catalogue : new String[]{"/lang/en.json", "/lang/zh.json"}) {
            Map<String, String> entries;
            try (Reader reader = new InputStreamReader(
                    UltiToolsPlugin.class.getResourceAsStream(catalogue), StandardCharsets.UTF_8)) {
                entries = new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
            }
            String value = entries.get(REPLACED_LINE_KEY);
            assertThat(value).as(catalogue).isNotNull();
            assertThat(String.format(value, "lang/en.json", "UltiDemo", "lang/en.json.bak"))
                    .as(catalogue).contains("lang/en.json", "UltiDemo", "lang/en.json.bak");
        }
    }

    @Test
    @DisplayName("Codex run 1: every bundled language file is migrated on the upgrade start, not only the configured one")
    void everyBundledLanguageFileIsMigratedNotOnlyTheConfiguredOne() throws Exception {
        String oldZh = "{\"greeting\":\"6.2 zh\"}";
        String newZh = "{\"greeting\":\"6.3 zh\"}";
        fixture.jarEntry(LANG, NEW).jarEntry("lang/zh.json", newZh)
                .onDisk(LANG, OLD).onDisk("lang/zh.json", oldZh);

        UltiToolsPlugin plugin = start();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.json"))).isEqualTo(utf8(newZh));
        assertThat(BootLanguageFixture.bytesOf(new File(langDir(), "zh.json.bak"))).isEqualTo(utf8(oldZh));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "lang/zh.json"))
                .contains(ResourceHashSidecar.sha256(fixture.disk("lang/zh.json")));
        verify(fixture.logger(), times(2)).warning(anyString());

        // The reproduction: after the upgrade the operator edits zh.json, switches to zh and reloads;
        // the edit is kept as a customisation, not replaced by the jar's copy.
        String edited = "{\"greeting\":\"zh, edited after the upgrade\"}";
        fixture.onDisk("lang/zh.json", edited).language("zh");
        plugin.reloadSelf();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.json"))).isEqualTo(utf8(edited));
        assertThat(plugin.i18n("greeting")).isEqualTo("zh, edited after the upgrade");
        assertThat(langListing()).containsExactly("en.json", "en.json.bak", "zh.json", "zh.json.bak");
    }

    @Test
    @DisplayName("Codex run 2: a malformed catalogue for a language not in use never blocks the module")
    void malformedUnusedLanguageNeverBlocksTheModule() throws Exception {
        String malformed = "{ this is not json";
        fixture.jarEntry(LANG, NEW).jarEntry("lang/zh.json", malformed);

        UltiToolsPlugin plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();

        assertThat(plugin.i18n("greeting")).isEqualTo("Hello from the 6.3 jar");
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.json"))).isEqualTo(utf8(malformed));
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "lang/zh.json"))
                .contains(ResourceHashSidecar.sha256(fixture.disk("lang/zh.json")));
    }
}

