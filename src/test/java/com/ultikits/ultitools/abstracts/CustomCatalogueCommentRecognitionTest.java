package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #615 item 1 (contract-batching row 01:18 of 2026-10-06, superseding row 01:09): a config comment the framework wrote
 * from the operator's custom language file is recognised as framework-written, so it follows later language switches
 * like a comment written from a shipped catalogue. The recognition set includes, for the comment's key, the text of
 * every custom language file in the module's {@code lang/} folder, read without writing, recording or backing up
 * anything.
 * <p>
 * Maintainer decision of 2026-10-06 (option A): a comment written from an EARLIER version of the operator's custom
 * text -- the operator edited that text in the custom file afterwards -- is operator content. It is never rewritten,
 * and no record of rendered texts is stored to recognise it.
 * <p>
 * Every start is a real one: the module's own constructor from a real jar, {@code commitLanguageProvenance()}, then a
 * real {@link AbstractConfigEntity#init(UltiToolsPlugin)} over the module's folder in a temporary directory.
 */
@DisplayName("#615 item 1: a comment written from a custom language file follows language switches; one from an earlier custom text is the operator's")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // clears the framework singleton a test class leaves behind
class CustomCatalogueCommentRecognitionTest {

    private static final String CONFIG_PATH = "config/recognition.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_500_000_000_000L);

    private static final String EN_A = "Setting A";
    private static final String ZH_A = "设置 A";
    private static final String MYSERVER_A = "本服：设置 A";
    private static final String MYSERVER_A_EDITED = "本服：设置 A（修订）";
    private static final String ZH_A_CUSTOM_A = "甲版：设置 A";
    private static final String ZH_B_CUSTOM_A = "乙版：设置 A";

    @TempDir
    File tempDir;

    private BootLanguageFixture fixture;
    private UltiToolsPlugin plugin;
    private RecognitionConfig config;

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(CONFIG_PATH)
    static class RecognitionConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "{cfg_a}")
        int a = 1;

        @ConfigEntry(path = "b")
        int b = 2;

        RecognitionConfig(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        fixture = BootLanguageFixture.create(tempDir);
        fixture.jarEntry("lang/en.yml", "cfg_a: \"" + EN_A + "\"\n")
                .jarEntry("lang/zh.yml", "cfg_a: \"" + ZH_A + "\"\n")
                .onDisk("lang/zh-myserver.yml", "cfg_a: \"" + MYSERVER_A + "\"\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fixture.close();
        MockBukkitHelper.ensureCleanState();
        clearLeakedUltiToolsInstance();
    }

    /**
     * Clears a mocked {@code UltiTools} instance a test class leaves behind (17-74 gate-1 F1): a later class would
     * otherwise log through a mock whose logger is {@code null}.
     */
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        Field instance = UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** A server start with {@code language} set to {@code code}: the module is constructed and its config loaded. */
    private void start(String code) throws Exception {
        fixture.language(code);
        plugin = fixture.construct("6.3.0");
        plugin.commitLanguageProvenance();
        config = new RecognitionConfig(CONFIG_PATH);
        config.init(plugin);
    }

    /** {@code /ul reload} with {@code language} set to {@code code}: the module's reload steps, in their order. */
    private void reload(String code) throws Exception {
        fixture.language(code);
        config.reload();
        plugin.commitLanguageProvenance();
        config.refreshFrameworkComments();
    }

    private Path configFile() {
        return fixture.disk(CONFIG_PATH).toPath();
    }

    private String text() throws IOException {
        return new String(Files.readAllBytes(configFile()), StandardCharsets.UTF_8);
    }

    private void putConfig(String text) throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.write(configFile(), text.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(configFile(), OLD);
    }

    private static String file(String comment) {
        return "# " + comment + "\na: 1\nb: 2\n";
    }

    /** Bytes and modification time of every custom language file, keyed by name. */
    private Map<String, String> customFileState() throws IOException {
        Map<String, String> state = new LinkedHashMap<>();
        File[] files = fixture.disk("lang/x").getParentFile().listFiles();
        Arrays.sort(files);
        for (File each : files) {
            if (each.getName().startsWith("zh-")) {
                state.put(each.getName(), new String(Files.readAllBytes(each.toPath()), StandardCharsets.UTF_8)
                        + " @" + Files.getLastModifiedTime(each.toPath()).toMillis());
            }
        }
        return state;
    }

    private List<String> resourceFolderListing() throws IOException {
        try (Stream<Path> walk = Files.walk(fixture.resourceFolder().toPath())) {
            return walk.map(path -> fixture.resourceFolder().toPath().relativize(path).toString())
                    .sorted().collect(Collectors.toList());
        }
    }

    private String provenanceRecord() throws IOException {
        File record = fixture.provenanceRecord();
        return record.isFile() ? new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8) : "";
    }

    @Test
    @DisplayName("a comment written under zh-myserver becomes the en text after a switch to en; every other byte is unchanged")
    void customCommentFollowsASwitchToEnglish() throws Exception {
        putConfig(file(MYSERVER_A));

        start("zh-myserver");
        assertThat(text()).as("written from the custom file").isEqualTo(file(MYSERVER_A));

        start("en");
        assertThat(text()).isEqualTo(file(EN_A));
    }

    @Test
    @DisplayName("first start under zh-myserver writes the custom text; en replaces it; back to zh-myserver renders it again; a repeat start changes nothing")
    void customCommentFollowsSwitchesBothWaysAndARepeatStartIsANoOp() throws Exception {
        start("zh-myserver");
        String created = text();
        assertThat(created).contains("# " + MYSERVER_A + "\n");

        start("en");
        assertThat(text()).isEqualTo(created.replace("# " + MYSERVER_A + "\n", "# " + EN_A + "\n"));

        start("zh-myserver");
        assertThat(text()).isEqualTo(created);

        Files.setLastModifiedTime(configFile(), OLD);
        start("zh-myserver");
        assertThat(text()).isEqualTo(created);
        assertThat(Files.getLastModifiedTime(configFile())).isEqualTo(OLD);
    }

    @Test
    @DisplayName("/ul reload with a language switch: a comment written under zh-myserver follows to en")
    void customCommentFollowsASwitchAppliedByReload() throws Exception {
        putConfig(file(MYSERVER_A));
        start("zh-myserver");

        reload("en");

        assertThat(text()).isEqualTo(file(EN_A));
    }

    @Test
    @DisplayName("two custom files: a comment written from zh-a is recognised while zh-b is selected")
    void commentFromOneCustomFileFollowsASwitchToAnother() throws Exception {
        fixture.onDisk("lang/zh-a.yml", "cfg_a: \"" + ZH_A_CUSTOM_A + "\"\n")
                .onDisk("lang/zh-b.yml", "cfg_a: \"" + ZH_B_CUSTOM_A + "\"\n");
        putConfig(file(ZH_A_CUSTOM_A));

        start("zh-b");

        assertThat(text()).isEqualTo(file(ZH_B_CUSTOM_A));
    }

    @Test
    @DisplayName("a hand-written comment equal to no known text stays byte for byte through every switch (pinned)")
    void handWrittenCommentIsNeverRewritten() throws Exception {
        String hand = file("我的说明：活动周末保持 1");
        putConfig(hand);

        for (String code : new String[]{"zh-myserver", "en", "zh", "zh-myserver"}) {
            start(code);
            assertThat(text()).as(code).isEqualTo(hand);
        }
        reload("en");
        assertThat(text()).isEqualTo(hand);
        assertThat(Files.getLastModifiedTime(configFile())).isEqualTo(OLD);
    }

    @Test
    @DisplayName("custom files keep their bytes and modification times, gain no backup and no provenance record")
    void customFilesAreOnlyRead() throws Exception {
        fixture.onDisk("lang/zh-a.yml", "cfg_a: \"" + ZH_A_CUSTOM_A + "\"\n");
        for (File each : fixture.disk("lang/x").getParentFile().listFiles()) {
            Files.setLastModifiedTime(each.toPath(), OLD);
        }
        Map<String, String> before = customFileState();

        start("zh-myserver");
        start("en");
        reload("zh-a");
        start("zh-myserver");

        assertThat(customFileState()).isEqualTo(before);
        assertThat(provenanceRecord()).doesNotContain("zh-myserver").doesNotContain("zh-a");
        assertThat(fixture.disk("lang/x").getParentFile().list())
                .noneMatch(name -> name.startsWith("zh-") && !name.endsWith(".yml"));
    }

    @Test
    @DisplayName("option A: a comment from an earlier version of the custom text stays through start, reload and switches; nothing new is stored")
    void commentFromAnEarlierCustomTextIsOperatorContent() throws Exception {
        start("zh-myserver");
        String written = text();
        assertThat(written).contains("# " + MYSERVER_A + "\n");
        List<String> listing = resourceFolderListing();

        fixture.onDisk("lang/zh-myserver.yml", "cfg_a: \"" + MYSERVER_A_EDITED + "\"\n");
        start("en");
        assertThat(text()).as("start after the edit and a switch to en").isEqualTo(written);
        reload("en");
        assertThat(text()).as("/ul reload").isEqualTo(written);
        reload("zh");
        assertThat(text()).as("/ul reload with a switch to zh").isEqualTo(written);
        start("zh-myserver");
        assertThat(text()).as("start back under the edited custom file").isEqualTo(written);

        assertThat(resourceFolderListing()).isEqualTo(listing);
    }
}
