package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.gson.JsonParseException;

import com.ultikits.ultitools.utils.ResourceHashSidecar;

/**
 * #608 (plan 17-69): custom language files for modules, as the maintainer decided on 2026-10-04.
 * <ul>
 *   <li>Official language files are framework-owned; an operator customises by copying an official
 *       file under a new name and selecting that name as {@code language} in {@code
 *       plugins/UltiTools/config.yml}.</li>
 *   <li>"Custom language file gaps": keys the custom file lacks, and every module with no file of that
 *       name, use the official language the name starts with ({@code zh-myserver} on {@code zh}).</li>
 *   <li>"Official language file edited in place": restored at every start, the edit backed up, one log
 *       line telling the operator to copy, rename and select instead.</li>
 *   <li>The custom file is operator-owned and never written (the foundational never-overwrite rule).</li>
 * </ul>
 * Each start is a real one: the module's own constructor, then {@code commitLanguageProvenance()}.
 */
@DisplayName("#608: custom language files for modules")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CustomLanguageFileTest {

    private static final String EN = "greeting: \"Hello\"\nfarewell: \"Goodbye\"\n";
    private static final String ZH = "greeting: \"你好\"\nfarewell: \"再见\"\n";
    private static final String ZH_OLD = "greeting: \"你好（旧版）\"\nfarewell: \"再见（旧版）\"\n";
    private static final String CUSTOM = "greeting: \"你好，本服欢迎你\"\n";
    private static final String CUSTOM_PATH = "lang/zh-myserver.yml";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = BootLanguageFixture.create(tempDir);
        fixture.jarEntry("lang/en.yml", EN).jarEntry("lang/zh.yml", ZH);
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

    private String[] langListing() {
        String[] names = fixture.disk("lang/x").getParentFile().list();
        Arrays.sort(names);
        return names;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("ultitools.language.custom-file-module: a module with no file under the custom name uses its official zh, silently")
    void moduleWithoutTheCustomFileUsesItsOfficialBase() throws Exception {
        fixture.language("zh-myserver");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("你好");
        assertThat(plugin.i18n("farewell")).isEqualTo("再见");
        verify(fixture.logger(), never()).warning(anyString());
    }

    @Test
    @DisplayName("control: language zh renders official zh")
    void officialCodeRendersItsOfficialFile() throws Exception {
        fixture.language("zh").onDisk(CUSTOM_PATH, CUSTOM);

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("你好");
    }

    @Test
    @DisplayName("getLanguageCode() is the official base and getConfiguredLanguage() the configured name")
    void languageCodeIsTheOfficialBaseAndConfiguredLanguageTheName() throws Exception {
        fixture.language("zh-myserver");
        UltiToolsPlugin plugin = start();

        assertThat(plugin.getLanguageCode()).isEqualTo("zh");
        assertThat(configuredLanguage(plugin)).isEqualTo("zh-myserver");

        fixture.language("zh");
        assertThat(plugin.getLanguageCode()).isEqualTo("zh");
        assertThat(configuredLanguage(plugin)).isEqualTo("zh");
    }

    /**
     * Calls {@code getConfiguredLanguage()} (added in 6.3.0 by #608) through reflection, so this class
     * compiles against the base and its RED evidence there is an assertion, not a compile error.
     */
    private static String configuredLanguage(UltiToolsPlugin plugin) throws Exception {
        Method accessor;
        try {
            accessor = UltiToolsPlugin.class.getMethod("getConfiguredLanguage");
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("UltiToolsPlugin#getConfiguredLanguage() does not exist", missing);
        }
        assertThat(Modifier.isFinal(accessor.getModifiers())).as("getConfiguredLanguage() is final").isTrue();
        return (String) accessor.invoke(plugin);
    }

    @Test
    @DisplayName("ultitools.language.custom-file-missing-keys: a name starting with no shipped code uses English for what it lacks, with one warning naming the name and the fallback")
    void nameWithoutAShippedPrefixFallsBackToEnglishWithOneWarning() throws Exception {
        fixture.language("myserver").onDisk("lang/myserver.yml", "greeting: \"Welcome to my server\"\n");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("Welcome to my server");
        assertThat(plugin.i18n("farewell")).isEqualTo("Goodbye");
        assertThat(plugin.getLanguageCode()).isEqualTo("en");
        verify(fixture.logger(), times(1)).warning(anyString());
        // R1-3: the warning says the custom file is still used and only what it lacks comes from 'en'.
        verify(fixture.logger()).warning(argThat((String message) -> message.contains("'myserver'")
                && message.contains("lang/myserver.* is still used") && message.contains("uses 'en'")
                && !message.contains("falling back")));
    }

    @Test
    @DisplayName("the longest shipped prefix wins: zh-CN-myserver is based on zh-CN when both zh and zh-CN ship")
    void longestShippedPrefixIsTheBase() throws Exception {
        fixture.jarEntry("lang/zh-CN.yml", "greeting: \"你好（简体）\"\nfarewell: \"再见（简体）\"\n")
                .language("zh-CN-myserver").onDisk("lang/zh-CN-myserver.yml", "greeting: \"本服\"\n");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("本服");
        assertThat(plugin.i18n("farewell")).isEqualTo("再见（简体）");
        assertThat(plugin.getLanguageCode()).isEqualTo("zh-CN");
    }

    @Test
    @DisplayName("T-17-69-01: a path-like language value is never a custom name: nothing outside lang/ is read or written; English with a warning")
    void pathLikeLanguageNeverEscapesTheLanguageFolder() throws Exception {
        File outside = new File(fixture.resourceFolder(), "x.yml");
        Files.write(outside.toPath(), utf8("greeting: \"read from outside lang/\"\n"));
        byte[] before = Files.readAllBytes(outside.toPath());
        fixture.language("../x");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("Hello");
        assertThat(Files.readAllBytes(outside.toPath())).isEqualTo(before);
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), "x.yml")).isEmpty();
        verify(fixture.logger()).warning(argThat((String message) -> message.contains("'../x'")));
    }

    /** The state of the official {@code zh.yml} beside the custom file, one per provenance branch. */
    enum OfficialState {
        /** Branch 3: unrecorded and byte-identical to the jar's copy. */
        UNRECORDED_IDENTICAL,
        /** Branch 4: unrecorded and different (a 6.2 extraction). */
        UNRECORDED_DIFFERENT,
        /** Branch 1: recorded and unchanged since, while the jar's copy changed (a module upgrade). */
        RECORDED_UPGRADED,
        /** Branch 2: recorded, then edited in place. */
        RECORDED_EDITED,
        /** No official file on disk yet (first start: extracted). */
        ABSENT
    }

    private void prepareOfficial(OfficialState state) throws IOException {
        switch (state) {
            case UNRECORDED_IDENTICAL:
                fixture.onDisk("lang/zh.yml", ZH);
                break;
            case UNRECORDED_DIFFERENT:
                fixture.onDisk("lang/zh.yml", ZH_OLD);
                break;
            case RECORDED_UPGRADED:
                fixture.onDisk("lang/zh.yml", ZH_OLD).recordCurrent("lang/zh.yml");
                break;
            case RECORDED_EDITED:
                fixture.onDisk("lang/zh.yml", ZH_OLD).recordCurrent("lang/zh.yml")
                        .onDisk("lang/zh.yml", "greeting: \"operator edit\"\n");
                break;
            default:
                break;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OfficialState.class)
    @DisplayName("ultitools.language.custom-file-never-written: the custom file is never written, replaced, backed up or recorded -- start, reload, second start -- on every official-file branch")
    void customFileIsNeverWrittenOnAnyBranch(OfficialState state) throws Exception {
        fixture.language("zh-myserver").onDisk(CUSTOM_PATH, CUSTOM);
        prepareOfficial(state);
        File custom = fixture.disk(CUSTOM_PATH);
        FileTime pinnedTime = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(custom.toPath(), pinnedTime);

        UltiToolsPlugin plugin = start();
        assertThat(plugin.i18n("greeting")).isEqualTo("你好，本服欢迎你");
        assertThat(plugin.i18n("farewell")).isEqualTo("再见");
        plugin.reloadSelf();
        assertThat(plugin.i18n("greeting")).isEqualTo("你好，本服欢迎你");
        start();

        assertThat(Files.readAllBytes(custom.toPath())).isEqualTo(utf8(CUSTOM));
        assertThat(Files.getLastModifiedTime(custom.toPath())).isEqualTo(pinnedTime);
        assertThat(ResourceHashSidecar.readRecordedHash(fixture.resourceFolder(), CUSTOM_PATH)).isEmpty();
        assertThat(langListing()).noneMatch(name -> name.startsWith("zh-myserver.yml.") || name.endsWith(".tmp"));
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.yml"))).isEqualTo(utf8(ZH));
    }

    @Test
    @DisplayName("a custom .json beside a module that ships .yml is read too; never written")
    void customFileInAnotherExtensionIsReadAndNeverWritten() throws Exception {
        fixture.language("zh-myserver").onDisk("lang/zh-myserver.json", "{\"greeting\":\"JSON 自定义\"}");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("JSON 自定义");
        assertThat(plugin.i18n("farewell")).isEqualTo("再见");
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh-myserver.json")))
                .isEqualTo(utf8("{\"greeting\":\"JSON 自定义\"}"));
    }

    @Test
    @DisplayName("a malformed custom JSON file is reported and the official base is used; the file is left as it is")
    void malformedCustomFileFallsBackToTheOfficialBase() throws Exception {
        fixture.language("zh-myserver").onDisk("lang/zh-myserver.json", "{ not json");

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("greeting")).isEqualTo("你好");
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh-myserver.json"))).isEqualTo(utf8("{ not json"));
        verify(fixture.logger(), times(1)).log(eq(Level.SEVERE),
                argThat((String message) -> message.contains("zh-myserver.json")), any(JsonParseException.class));
    }

    @Test
    @DisplayName("ultitools.language.official-file-edit: an official file edited in place is restored at start with a backup and one line; a second start changes nothing")
    void officialFileEditedInPlaceIsRestoredOnceAndThenLeftAlone() throws Exception {
        String edited = "greeting: \"operator edit\"\n";
        fixture.language("zh").onDisk("lang/zh.yml", ZH).recordCurrent("lang/zh.yml").onDisk("lang/zh.yml", edited);

        UltiToolsPlugin plugin = start();

        File backup = new File(fixture.disk("lang/zh.yml").getParentFile(), "zh.yml.bak");
        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.yml"))).isEqualTo(utf8(ZH));
        assertThat(BootLanguageFixture.bytesOf(backup)).isEqualTo(utf8(edited));
        assertThat(plugin.i18n("greeting")).isEqualTo("你好");
        verify(fixture.logger(), times(1)).warning(argThat((String message) ->
                message.contains(fixture.disk("lang/zh.yml").getPath()) && message.contains(backup.getPath())
                        && message.contains("zh-myserver")));
        byte[] recordAfterFirst = BootLanguageFixture.bytesOf(fixture.provenanceRecord());
        clearInvocations(fixture.logger());

        start();

        assertThat(BootLanguageFixture.bytesOf(fixture.disk("lang/zh.yml"))).isEqualTo(utf8(ZH));
        assertThat(BootLanguageFixture.bytesOf(fixture.provenanceRecord())).isEqualTo(recordAfterFirst);
        assertThat(langListing()).containsExactly("en.yml", "zh.yml", "zh.yml.bak");
        verify(fixture.logger(), never()).warning(anyString());
    }

    @Test
    @DisplayName("R1-2: a custom value whose placeholders no longer match the official one uses the official value for that key, in memory, with one warning naming file and key; the file is never written")
    void customValueWithLostPlaceholdersUsesTheOfficialValueInMemory() throws Exception {
        fixture.jarEntry("lang/zh.yml", "items: \"你有 %s 个物品，位于 %s\"\n"
                        + "teleport: \"传送 {PLAYER} 到 {WORLD}\"\n"
                        + "greeting: \"你好 %s\"\n"
                        + "note: \"{PLAYER} 的备注\"\n")
                .language("zh-myserver");
        String custom = "items: \"本服：你有 %s 个物品\"\n"
                + "teleport: \"本服传送 {PLAYER}\"\n"
                + "greeting: \"本服欢迎 %s\"\n"
                + "note: \"{PLAYER} 的备注 {EXTRA}\"\n";
        fixture.onDisk(CUSTOM_PATH, custom);
        File customFile = fixture.disk(CUSTOM_PATH);
        FileTime pinnedTime = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(customFile.toPath(), pinnedTime);

        UltiToolsPlugin plugin = start();

        assertThat(plugin.i18n("items")).isEqualTo("你有 %s 个物品，位于 %s");
        assertThat(plugin.i18n("teleport")).isEqualTo("传送 {PLAYER} 到 {WORLD}");
        // Controls: a reworded value of the same shape, and one that adds a token of its own, are kept.
        assertThat(plugin.i18n("greeting")).isEqualTo("本服欢迎 %s");
        assertThat(plugin.i18n("note")).isEqualTo("{PLAYER} 的备注 {EXTRA}");
        verify(fixture.logger(), times(2)).warning(anyString());
        verify(fixture.logger()).warning(argThat((String message) -> message.contains("'items'")
                && message.contains(customFile.getPath()) && message.contains("different placeholder count")
                && !message.contains("%s")));
        verify(fixture.logger()).warning(argThat((String message) -> message.contains("'teleport'")
                && message.contains(customFile.getPath()) && message.contains("missing a placeholder")
                && !message.contains("{WORLD}")));
        assertThat(Files.readAllBytes(customFile.toPath())).isEqualTo(utf8(custom));
        assertThat(Files.getLastModifiedTime(customFile.toPath())).isEqualTo(pinnedTime);
        assertThat(langListing()).noneMatch(name -> name.startsWith("zh-myserver.yml."));
    }
}
