package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import com.ultikits.ultitools.annotations.ConfigEntry;

/** File presence and the separate ordered effective baseline (Follow-up 14). */
class ConfigPresentInFileTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    public static class Values extends AbstractConfigEntity {
        @ConfigEntry Map<String, String> values = new LinkedHashMap<>();
        public Values(String path) { super(path); values.put("first", "one"); values.put("second", "two"); }
    }
    @BeforeEach void setup() {
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("PresenceModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
    }
    private static boolean present(Values value, String path) throws Exception {
        return (Boolean) AbstractConfigEntity.class.getMethod("isPresentInFile", String.class).invoke(value, path);
    }
    @Test void presenceDescribesLastLoadIncludingUndeclaredAndNullKeys() throws Exception {
        Path file = directory.resolve("values.yml");
        Files.write(file, "values: {}\na:\n  b: null\nwave.: z\n".getBytes(StandardCharsets.UTF_8));
        Values value = new Values("values.yml"); value.init(plugin);
        assertThat(present(value, "a.b")).isTrue(); assertThat(present(value, "absent")).isFalse();
        assertThat(present(value, "wave.")).isFalse();
        Files.write(file, "values: {}\n".getBytes(StandardCharsets.UTF_8));
        assertThat(present(value, "a.b")).isTrue();
        value.reload(); assertThat(present(value, "a.b")).isFalse();
        assertThat(AbstractConfigEntity.class.getDeclaredMethods()).noneMatch(
                method -> method.getReturnType().getName().equals("org.bukkit.configuration.file.YamlConfiguration"));
    }
    @Test void unreadableAndUnparseableFilesHaveNoPresence() throws Exception {
        Path unreadable = directory.resolve("directory.yml"); Files.createDirectory(unreadable);
        Values value = new Values("directory.yml"); value.init(plugin);
        assertThat(present(value, "values")).isFalse();
        Files.write(directory.resolve("broken.yml"), "values: [\n".getBytes(StandardCharsets.UTF_8));
        Values broken = new Values("broken.yml"); broken.init(plugin);
        assertThat(present(broken, "values")).isFalse();
    }
    @Test void reorderedMapIsDirtyButSaveAcknowledgesSemanticNoOp() throws Exception {
        Path file = directory.resolve("values.yml");
        Files.write(file, "# operator\nvalues:\n  # first comment\n  first: one\n  second: two\n".getBytes(StandardCharsets.UTF_8));
        Values value = new Values("values.yml"); value.init(plugin);
        byte[] before = Files.readAllBytes(file); FileTime time = Files.getLastModifiedTime(file);
        String first = value.values.remove("first"); value.values.put("first", first);
        assertThat(value.isModifiedSinceSnapshot()).isTrue(); value.save();
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(time);
        assertThat(value.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void loadedValueNotDeclaredDefaultIsTheBaseline() throws Exception {
        Files.write(directory.resolve("values.yml"), "values:\n  operator: value\n".getBytes(StandardCharsets.UTF_8));
        Values value = new Values("values.yml"); value.init(plugin);
        assertThat(value.isModifiedSinceSnapshot()).isFalse();
        value.values.clear(); value.values.put("first", "one"); value.values.put("second", "two");
        assertThat(value.isModifiedSinceSnapshot()).isTrue();
    }
}
