package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Confirmation review of plan 17-41 (#523, #542): every framework write puts a value into the file in
 * a form the loader reads back and a module reads as plain data - a map inside a list stays a map, a
 * panel write of an enum or a {@code Set} does not produce a file the loader refuses - and a
 * comment-only rewrite that failed is not recorded as a clean snapshot.
 */
@DisplayName("AbstractConfigEntity - the form each write puts into the file (#523, #542)")
class ConfigFileFormTest {

    private static final String PATH = "config/forms.yml";

    @TempDir
    Path tempDir;

    private UltiToolsPlugin plugin;

    @SuppressWarnings("unused") // read reflectively by the binder and by the assertions below
    static class FormsConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "servers")
        List<Map<String, String>> servers = defaults();

        public FormsConfig(String configFilePath) {
            super(configFilePath);
        }

        private static List<Map<String, String>> defaults() {
            Map<String, String> lobby = new LinkedHashMap<>();
            lobby.put("name", "lobby");
            lobby.put(".", "dot");
            List<Map<String, String>> list = new ArrayList<>();
            list.add(lobby);
            return list;
        }
    }

    @SuppressWarnings("unused")
    static class PanelConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "unit")
        TimeUnit unit = TimeUnit.SECONDS;

        @ConfigEntry(path = "tags")
        Set<String> tags = new LinkedHashSet<>();

        public PanelConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @SuppressWarnings("unused")
    static class CommentConfig extends AbstractConfigEntity {
        @ConfigEntry(path = "limit", comment = "{config.limit}")
        int limit = 10;

        public CommentConfig(String configFilePath) {
            super(configFilePath);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("FormsModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(tempDir.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                invocation -> new File(tempDir.toFile(), invocation.<String>getArgument(0)));
        lenient().when(plugin.i18n(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(plugin.i18n("config.limit")).thenReturn("Maximum");
    }

    private Path file() {
        return tempDir.resolve(PATH);
    }

    @Test
    @DisplayName("a map inside a list stays a map in getConfig() and the panel payload after first boot and save")
    void mapInsideAListStaysAMap() throws IOException {
        FormsConfig config = new FormsConfig(PATH);
        config.init(plugin);
        assertThat(config.getConfig().getMapList("servers")).hasSize(1);
        assertThatCode(config::toJsonObject).doesNotThrowAnyException();

        assertThatCode(config::save).doesNotThrowAnyException();
        assertThat(config.getConfig().getMapList("servers")).hasSize(1);
        JsonObject payload = config.toJsonObject();
        assertThat(payload.get("servers")).isInstanceOf(JsonArray.class);

        FormsConfig second = new FormsConfig(PATH);
        second.init(plugin);
        assertThat(second.servers).hasSize(1);
        // #553 (orchestrator ruling 2026-09-30): a map inside a list goes through the same refusal point,
        // so its dotted key "." is left out like any other; the rest of the element is kept.
        assertThat(second.servers.get(0)).containsEntry("name", "lobby").doesNotContainKey(".");
    }

    @Test
    @DisplayName("a panel write of an enum and a Set field leaves a file the next start loads")
    void panelWriteOfEnumAndSetIsReadable() throws IOException {
        PanelConfig config = new PanelConfig(PATH);
        config.init(plugin);

        JsonObject payload = new JsonObject();
        payload.addProperty("unit", "MINUTES");
        JsonArray tags = new JsonArray();
        tags.add("red");
        tags.add("blue");
        payload.add("tags", tags);
        config.updateProperties(payload);

        PanelConfig second = new PanelConfig(PATH);
        second.init(plugin);
        assertThat(second.isLastLoadUnparseable()).isFalse();
        assertThat(second.unit).isEqualTo(TimeUnit.MINUTES);
        assertThat(second.tags).containsExactly("red", "blue");
    }

    @Test
    @DisplayName("a comment-only rewrite that failed leaves the entity modified, so the shutdown save writes the file again")
    void failedCommentRewriteIsNotACleanSnapshot() throws IOException {
        Files.createDirectories(file().getParent());
        Files.write(file(), "# old\nlimit: 25\n".getBytes(StandardCharsets.UTF_8));
        assertThat(file().toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(file()), "needs a non-root user so the write really fails");
            CommentConfig config = new CommentConfig(PATH);
            config.init(plugin);
            assertThat(config.limit).isEqualTo(25);
            assertThat(config.isModifiedSinceSnapshot()).isTrue();
        } finally {
            assertThat(file().toFile().setWritable(true)).isTrue();
        }
    }
}
