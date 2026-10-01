package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;

/** Every entity write uses converter plain output, never Java class tags (#560). */
class ConfigClassTagFreeTest {
    @TempDir Path directory;
    private UltiToolsPlugin plugin;
    enum Mode { FAST, SLOW }
    @SerializableAs("ConfigEntityHolder")
    public static class Holder implements ConfigurationSerializable {
        final String value;
        public Holder(String value) { this.value = value; }
        public static Holder deserialize(Map<String, Object> data) { return new Holder((String) data.get("value")); }
        @Override public Map<String, Object> serialize() {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("value", value); return result;
        }
    }
    public static class Unknown implements ConfigurationSerializable {
        @Override public Map<String, Object> serialize() { return new LinkedHashMap<>(); }
    }
    static class Values extends AbstractConfigEntity {
        @ConfigEntry UUID id = new UUID(0, 1);
        @ConfigEntry Map<String, Mode> modes = new LinkedHashMap<>();
        @ConfigEntry Map<String, Holder> holders = new LinkedHashMap<>();
        public Values(String path) { super(path); modes.put("first", Mode.FAST); holders.put("one", new Holder("hello")); }
    }
    static class Unsupported extends AbstractConfigEntity {
        @ConfigEntry Unknown unknown = new Unknown();
        public Unsupported(String path) { super(path); }
    }
    static class ObjectValue extends AbstractConfigEntity {
        @ConfigEntry Object value = "plain";
        public ObjectValue(String path) { super(path); }
    }
    @BeforeEach void setup() {
        ConfigurationSerialization.registerClass(Holder.class);
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("TagModule");
        lenient().when(plugin.getConfigFolder()).thenReturn(directory.toString());
        lenient().when(plugin.getConfigFile(anyString())).thenAnswer(
                call -> new File(directory.toFile(), call.<String>getArgument(0)));
    }
    @AfterEach void teardown() { ConfigurationSerialization.unregisterClass(Holder.class); }
    @Test void firstBootAndSaveAreClassTagFreeAndLoadEqual() throws Exception {
        Values first = new Values("values.yml"); first.init(plugin); check(first);
        first.save(); check(first);
        Values next = new Values("values.yml"); next.init(plugin);
        assertThat(next.id).isEqualTo(first.id); assertThat(next.modes).isEqualTo(first.modes);
        assertThat(next.holders.get("one").value).isEqualTo("hello");
    }
    private void check(Values values) throws Exception {
        String text = new String(Files.readAllBytes(directory.resolve("values.yml")), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("!!").doesNotContain(UUID.class.getName()).doesNotContain(Holder.class.getName());
        assertThat(values.isModifiedSinceSnapshot()).isFalse();
    }
    @Test void uuidTextBindsToUuid() throws Exception {
        Files.write(directory.resolve("values.yml"), ("id: 00000000-0000-0000-0000-000000000002\nmodes: {}\nholders: {}\n")
                .getBytes(StandardCharsets.UTF_8));
        Values values = new Values("values.yml"); values.init(plugin); assertThat(values.id).isEqualTo(new UUID(0, 2));
    }
    @Test void unregisteredDeclaredTypeFailsBeforeFileReadOrCreation() {
        assertThatThrownBy(() -> new Unsupported("unknown.yml").init(plugin))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("unknown.yml")
                .hasMessageContaining("unknown").hasMessageContaining("ConfigConverterFor");
        assertThat(directory.resolve("unknown.yml")).doesNotExist();
    }
    @Test void unknownRuntimeObjectSaveRefusesWithoutChangingFile() throws Exception {
        ObjectValue values = new ObjectValue("object.yml"); values.init(plugin);
        byte[] before = Files.readAllBytes(directory.resolve("object.yml")); values.value = new Object();
        assertThatThrownBy(values::save).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("object.yml").hasMessageContaining("value").hasMessageContaining("java.lang.Object");
        assertThat(Files.readAllBytes(directory.resolve("object.yml"))).isEqualTo(before);
    }
}
