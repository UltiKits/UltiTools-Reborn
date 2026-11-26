package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.manager.ConfigManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Only the effective message text, not an earlier override or a legacy language file, can claim a comment. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomCatalogueCommentRecognitionTest {
    private static final String CONFIG_PATH = "config/recognition.yml";
    private static final String OVERRIDE_FILE = "messages/zh.yml";
    private static final FileTime OLD = FileTime.fromMillis(1_500_000_000_000L);
    private static final String EN_A = "Setting A";
    private static final String ZH_A = "设置 A";
    private static final String MYSERVER_A = "本服：设置 A";
    private static final String MYSERVER_A_EDITED = "本服：设置 A（修订）";

    @TempDir Path tempDir;
    private ConfigMessageModuleFixture fixture;
    private UltiToolsPlugin plugin;
    private ConfigManager configManager;

    @SuppressWarnings("unused") // read reflectively by the binder
    @ConfigEntity(CONFIG_PATH)
    static class RecognitionConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "a", comment = "{config.a}")
        int a = 1;

        @ConfigEntry(path = "b")
        int b = 2;

        RecognitionConfig(String path) {
            super(path);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        configManager = new ConfigManager();
        fixture = new ConfigMessageModuleFixture(tempDir, configManager, null);
        fixture.shipped("en", "config.a: '<green>" + EN_A + "</green>'\n")
                .shipped("zh", "config.a: '<gold>" + ZH_A + "</gold>'\n");
    }

    @AfterEach
    void tearDown() throws IOException {
        fixture.close();
    }

    private void start(String code) throws Exception {
        fixture.fallback(code);
        plugin = fixture.construct();
        plugin.commitLanguageProvenance();
        configManager.register(plugin, new RecognitionConfig(CONFIG_PATH));
    }

    private void reload(String code) throws IOException {
        fixture.fallback(code);
        assertThat(plugin.reloadWithReport().isPartial()).isFalse();
    }

    private Path configFile() {
        return fixture.disk(CONFIG_PATH);
    }

    private String text() throws IOException {
        return Files.readString(configFile());
    }

    private void putConfig(String text) throws IOException {
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), text);
        Files.setLastModifiedTime(configFile(), OLD);
    }

    private static String file(String comment) {
        return "# " + comment + "\na: 1\nb: 2\n";
    }

    private static String override(String text) {
        return "config.a: '<gold>" + text + "</gold>'\n";
    }

    @Test
    void aCommentFromALegacyCustomLanguageFileIsNeverClaimed() throws Exception {
        Path legacy = fixture.onDisk("lang/zh-myserver.yml", "config.a: \"" + MYSERVER_A + "\"\n")
                .disk("lang/zh-myserver.yml");
        byte[] before = Files.readAllBytes(legacy);
        putConfig(file(MYSERVER_A));

        start("en");
        assertThat(text()).as("the old translated line survives startup").isEqualTo(file(MYSERVER_A));
        reload("zh");
        assertThat(text()).as("the old translated line survives a switching reload").isEqualTo(file(MYSERVER_A));
        assertThat(Files.getLastModifiedTime(configFile())).isEqualTo(OLD);
        assertThat(legacy).hasBinaryContent(before);
    }

    @Test
    void handWrittenCommentIsNeverRewritten() throws Exception {
        String hand = file("我的说明：活动周末保持 1");
        fixture.onDisk(OVERRIDE_FILE, override(MYSERVER_A));
        putConfig(hand);

        for (String code : List.of("zh", "en", "zh", "en")) {
            start(code);
            assertThat(text()).as(code).isEqualTo(hand);
        }
        reload("en");
        assertThat(text()).isEqualTo(hand);
        assertThat(Files.getLastModifiedTime(configFile())).isEqualTo(OLD);
    }

    @Test
    void operatorMessageOverridesAreOnlyRead() throws Exception {
        fixture.onDisk(OVERRIDE_FILE, override(MYSERVER_A));
        Path file = fixture.disk(OVERRIDE_FILE);
        Files.setLastModifiedTime(file, OLD);
        byte[] before = Files.readAllBytes(file);
        start("zh");
        assertThat(text()).contains("# " + MYSERVER_A + "\n");

        start("en");
        reload("zh");
        assertThat(file).hasBinaryContent(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(OLD);
        try (var paths = Files.list(file.getParent())) {
            assertThat(paths.map(Path::getFileName).map(Path::toString)).containsExactlyInAnyOrder("zh.yml", "reference");
        }
    }

    @Test
    void commentFromAnEarlierCustomTextIsOperatorContent() throws Exception {
        fixture.onDisk(OVERRIDE_FILE, override(MYSERVER_A));
        start("zh");
        String written = text();
        assertThat(written).contains("# " + MYSERVER_A + "\n");
        List<String> listing = resourceFolderListing();

        fixture.onDisk(OVERRIDE_FILE, override(MYSERVER_A_EDITED));
        start("en");
        assertThat(text()).as("start after the edit and a switch to en").isEqualTo(written);
        reload("en");
        assertThat(text()).as("/ul reload").isEqualTo(written);
        reload("zh");
        assertThat(text()).as("/ul reload with a switch to zh").isEqualTo(written);
        start("zh");
        assertThat(text()).as("start back under the edited override").isEqualTo(written);
        assertThat(resourceFolderListing()).isEqualTo(listing);
    }

    private List<String> resourceFolderListing() throws IOException {
        Path directory = configFile().getParent().getParent();
        try (var paths = Files.walk(directory)) {
            return paths.map(directory::relativize).map(Path::toString).sorted().toList();
        }
    }
}
