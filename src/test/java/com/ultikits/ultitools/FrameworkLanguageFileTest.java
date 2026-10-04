package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * #608 (plan 17-69): the framework's own language under the maintainer's 2026-10-04 decisions --
 * its official files are written to {@code plugins/UltiTools/lang/} so an operator can copy them and
 * are framework-owned (an in-place edit is restored at every start, backed up and logged); a custom
 * name selects {@code lang/<name>.json} there, filled key by key from the official language the name
 * starts with; a custom file is never written.
 * <p>
 * Each test runs {@code UltiTools#initLanguage} -- the language step of a start and of {@code /ul
 * reload} -- on a mocked framework instance.
 */
@DisplayName("#608: the framework's language files")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // drives UltiTools' private language step and field
class FrameworkLanguageFileTest {

    private static final String KEY = "Module '%s' reloaded.";

    /** The catalogue key of the restore line, spelled out so the operator-visible text is pinned here. */
    private static final String RESTORED_LINE_KEY = "Language file '%s' of module '%s' differed from the version "
            + "bundled with this release and was restored to it: official language files belong to UltiTools and "
            + "are restored at every start. The previous file was kept as '%s'. To customise messages, copy the "
            + "official file under a new name that starts with its language code and a hyphen, keeping the "
            + "extension (for example zh-myserver), edit the copy, and set language: zh-myserver in "
            + "plugins/UltiTools/config.yml.";

    @TempDir
    File tempDir;

    private File dataFolder;
    private YamlConfiguration config;
    private Logger logger;
    private UltiTools ultiTools;

    @BeforeEach
    void setUp() {
        dataFolder = new File(tempDir, "UltiTools");
        config = new YamlConfiguration();
        config.set("language", "zh");
        logger = Mockito.mock(Logger.class);
        ultiTools = TestHelper.mockUltiToolsInstance(mock -> {
            Mockito.lenient().when(mock.getConfig()).thenReturn(config);
            Mockito.lenient().when(mock.getDataFolder()).thenReturn(dataFolder);
            Mockito.lenient().when(mock.getLogger()).thenReturn(logger);
        });
    }

    private Language initLanguage() throws Exception {
        Method initLanguage = UltiTools.class.getDeclaredMethod("initLanguage");
        initLanguage.setAccessible(true);
        initLanguage.invoke(ultiTools);
        Field languageField = UltiTools.class.getDeclaredField("language");
        languageField.setAccessible(true);
        return (Language) languageField.get(ultiTools);
    }

    private File lang(String name) {
        return new File(new File(dataFolder, "lang"), name);
    }

    private String[] langListing() {
        String[] names = lang("x").getParentFile().list();
        Arrays.sort(names);
        return names;
    }

    private static byte[] bundled(String code) throws IOException {
        try (InputStream in = UltiTools.class.getClassLoader().getResourceAsStream("lang/" + code + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String bundledText(String code, String key) throws IOException {
        return new Language(new String(bundled(code), StandardCharsets.UTF_8)).getLocalizedText(key);
    }

    private void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("writes lang/en.json and lang/zh.json byte-identical to the jar when absent, and records them")
    void officialFilesAreWrittenWhenAbsent() throws Exception {
        initLanguage();

        assertThat(lang("en.json")).isFile();
        assertThat(lang("zh.json")).isFile();
        assertThat(Files.readAllBytes(lang("en.json").toPath())).isEqualTo(bundled("en"));
        assertThat(Files.readAllBytes(lang("zh.json").toPath())).isEqualTo(bundled("zh"));
        assertThat(ResourceHashSidecar.readRecordedHash(dataFolder, "lang/zh.json"))
                .contains(ResourceHashSidecar.sha256(bundled("zh")));
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("a byte-identical official file is not rewritten (its modification time stays)")
    void identicalOfficialFileIsNotRewritten() throws Exception {
        initLanguage();
        assertThat(lang("zh.json")).isFile();
        FileTime pinned = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(lang("zh.json").toPath(), pinned);

        initLanguage();

        assertThat(Files.getLastModifiedTime(lang("zh.json").toPath())).isEqualTo(pinned);
        assertThat(langListing()).containsExactly("en.json", "zh.json");
    }

    @Test
    @DisplayName("ultitools.language.official-file-edit (framework): an edited official file is restored with a backup and one line; a second start changes nothing")
    void editedOfficialFileIsRestoredOnceWithABackup() throws Exception {
        initLanguage();
        String edited = "{\"" + KEY + "\":\"operator edit\"}";
        write(lang("zh.json"), edited);

        Language language = initLanguage();

        assertThat(Files.readAllBytes(lang("zh.json").toPath())).isEqualTo(bundled("zh"));
        assertThat(new String(Files.readAllBytes(lang("zh.json.bak").toPath()), StandardCharsets.UTF_8))
                .isEqualTo(edited);
        assertThat(language.getLocalizedText(KEY)).isEqualTo(bundledText("zh", KEY));
        // The mocked framework's i18n returns the key, so the line is the formatted catalogue key.
        verify(logger, times(1)).warning(String.format(RESTORED_LINE_KEY,
                lang("zh.json").getPath(), "UltiTools", lang("zh.json.bak").getPath()));
        clearInvocations(logger);

        initLanguage();

        assertThat(langListing()).containsExactly("en.json", "zh.json", "zh.json.bak");
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("a file an earlier release wrote and nobody edited is brought up to date without a backup or a line")
    void unchangedFileFromAnEarlierReleaseIsUpdatedSilently() throws Exception {
        String earlier = "{\"" + KEY + "\":\"an earlier release's text\"}";
        write(lang("zh.json"), earlier);
        ResourceHashSidecar.record(dataFolder, "lang/zh.json", ResourceHashSidecar.sha256(lang("zh.json")));

        initLanguage();

        assertThat(Files.readAllBytes(lang("zh.json").toPath())).isEqualTo(bundled("zh"));
        assertThat(langListing()).containsExactly("en.json", "zh.json");
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("ultitools.language.custom-file-framework: zh-myserver reads lang/zh-myserver.json and fills missing keys from official zh")
    void customFrameworkFileIsSelectedAndFilledFromItsBase() throws Exception {
        config.set("language", "zh-myserver");
        write(lang("zh-myserver.json"), "{\"" + KEY + "\":\"本服：模块 '%s' 已重载\"}");

        Language language = initLanguage();

        String missing = "All %d modules reloaded.";
        assertThat(language.getLocalizedText(KEY)).isEqualTo("本服：模块 '%s' 已重载");
        assertThat(language.getLocalizedText(missing)).isEqualTo(bundledText("zh", missing));
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("a custom name without its own framework file uses official zh, and says the file was not found")
    void customNameWithoutAFrameworkFileUsesTheOfficialBase() throws Exception {
        config.set("language", "zh-myserver");

        Language language = initLanguage();

        assertThat(language.getLocalizedText(KEY)).isEqualTo(bundledText("zh", KEY));
        verify(logger).info(argThat((String message) -> message.contains("zh-myserver") && message.contains("not found")));
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("ultitools.language.custom-file-never-written (framework): the custom file is never written, backed up or recorded across starts")
    void customFrameworkFileIsNeverWritten() throws Exception {
        config.set("language", "zh-myserver");
        String custom = "{\"" + KEY + "\":\"本服\"}";
        write(lang("zh-myserver.json"), custom);
        FileTime pinned = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(lang("zh-myserver.json").toPath(), pinned);
        write(lang("zh.json"), "{\"" + KEY + "\":\"edited official\"}");

        initLanguage();
        initLanguage();

        assertThat(new String(Files.readAllBytes(lang("zh-myserver.json").toPath()), StandardCharsets.UTF_8))
                .isEqualTo(custom);
        assertThat(Files.getLastModifiedTime(lang("zh-myserver.json").toPath())).isEqualTo(pinned);
        assertThat(ResourceHashSidecar.readRecordedHash(dataFolder, "lang/zh-myserver.json")).isEmpty();
        assertThat(langListing()).containsExactly("en.json", "zh-myserver.json", "zh.json", "zh.json.bak");
    }

    @Test
    @DisplayName("a name starting with no shipped code uses English for what it lacks and logs one warning naming the name and 'en'")
    void nameWithoutAShippedPrefixFallsBackToEnglish() throws Exception {
        config.set("language", "myserver");
        write(lang("myserver.json"), "{\"" + KEY + "\":\"My server: %s reloaded\"}");

        Language language = initLanguage();

        String missing = "All %d modules reloaded.";
        assertThat(language.getLocalizedText(KEY)).isEqualTo("My server: %s reloaded");
        assertThat(language.getLocalizedText(missing)).isEqualTo(bundledText("en", missing));
        verify(logger, times(1)).warning(anyString());
        verify(logger).warning(argThat((String message) -> message.contains("'myserver'") && message.contains("'en'")));
    }

    @Test
    @DisplayName("T-17-69-01: a path-like language value never reads a file outside lang/")
    void pathLikeLanguageNeverReadsOutsideTheLanguageFolder() throws Exception {
        config.set("language", "../config");
        write(new File(dataFolder, "config.json"), "{\"" + KEY + "\":\"read from outside lang/\"}");

        Language language = initLanguage();

        assertThat(language.getLocalizedText(KEY)).isEqualTo(bundledText("en", KEY));
        verify(logger).warning(argThat((String message) -> message.contains("'../config'")));
    }

    @Test
    @DisplayName("an operator file pinned read-only under an official name is left as it is, with a line saying so")
    void pinnedOfficialFileIsLeftAlone() throws Exception {
        String pinnedText = "{\"" + KEY + "\":\"pinned\"}";
        write(lang("zh.json"), pinnedText);
        assertThat(lang("zh.json").setWritable(false)).isTrue();
        try {
            initLanguage();
        } finally {
            lang("zh.json").setWritable(true);
        }

        assertThat(new String(Files.readAllBytes(lang("zh.json").toPath()), StandardCharsets.UTF_8))
                .isEqualTo(pinnedText);
        assertThat(langListing()).containsExactly("en.json", "zh.json");
        verify(logger).warning(argThat((String message) -> message.contains("not writable")));
    }

    @Test
    @DisplayName("framework language files are read as UTF-8 even when the platform default charset is not UTF-8")
    void frameworkLanguageIsReadAsUtf8UnderANonUtf8Default() throws Exception {
        write(lang("zh-myserver.json"), "{\"" + KEY + "\":\"本服：模块 '%s' 已重载\"}");
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath());
        command.add("-Dfile.encoding=ISO-8859-1");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FrameworkLanguageCharsetProbe.class.getName());
        command.add(dataFolder.getAbsolutePath());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            output = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        assertThat(process.waitFor(50, TimeUnit.SECONDS)).isTrue();

        assertThat(output).contains("CHARSET=ISO-8859-1");
        assertThat(decoded(output, "OFFICIAL=")).isEqualTo(bundledText("zh", KEY));
        assertThat(decoded(output, "CUSTOM=")).isEqualTo("本服：模块 '%s' 已重载");
    }

    private static String decoded(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return new String(Base64.getDecoder().decode(line.substring(prefix.length()).trim()),
                        StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no " + prefix + " line in the probe output:\n" + output);
    }

    @Test
    @DisplayName("R1-2: a framework custom value whose placeholder count no longer matches the official one uses the official value, in memory, with one warning naming file and key")
    void frameworkCustomValueWithLostPlaceholdersUsesTheOfficialValue() throws Exception {
        config.set("language", "zh-myserver");
        String missing = "All %d modules reloaded.";
        String custom = "{\"" + KEY + "\":\"本服：模块已重载\",\"" + missing + "\":\"本服：已重载全部 %d 个模块\"}";
        write(lang("zh-myserver.json"), custom);

        Language language = initLanguage();

        assertThat(language.getLocalizedText(KEY)).isEqualTo(bundledText("zh", KEY));
        assertThat(language.getLocalizedText(missing)).isEqualTo("本服：已重载全部 %d 个模块");
        verify(logger, times(1)).warning(anyString());
        verify(logger).warning(argThat((String message) -> message.contains("'" + KEY + "'")
                && message.contains(lang("zh-myserver.json").getPath())
                && message.contains("different placeholder count") && !message.contains("本服")));
        assertThat(new String(Files.readAllBytes(lang("zh-myserver.json").toPath()), StandardCharsets.UTF_8))
                .isEqualTo(custom);
    }
}
