package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConfigConverterFor;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.PackageScanUtils;

/**
 * #630 with the maintainer decision of 2026-10-06 (option A): when an empty {@code @NotEmpty} list or map is replaced by
 * its declared default, the field's other constraints hold for that default - the default must itself satisfy them, and
 * a default that does not is a declaration error refused at load. UltiSideBar's shape: {@code @NotEmpty @Size(min = 1,
 * max = 15) List<String> lines}. A panel write that would empty a {@code @NotEmpty} list or map is refused like any other
 * violation, and nothing is written.
 */
@DisplayName("@NotEmpty list or map: the declared default satisfies the field's other constraints; panel [] refused (#630)")
class NotEmptyDefaultConstraintsTest {

    private static final List<String> THREE = Arrays.asList("Welcome", "", "Online: %online%");

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    /** UltiSideBar's {@code lines}: {@code @NotEmpty} plus {@code @Size(1..15)}, three default lines. */
    static class SideBar extends AbstractConfigEntity {
        @NotEmpty
        @Size(min = 1, max = 15)
        @ConfigEntry(path = "lines")
        List<String> lines = new ArrayList<>(THREE);

        @NotEmpty
        @ConfigEntry(path = "aliases")
        Map<String, String> aliases = new LinkedHashMap<>(Collections.singletonMap("sb", "sidebar"));

        SideBar(String path) { super(path); }
    }

    /** The same shape with a declared default of sixteen lines, outside its own {@code @Size}. */
    static class TooLongDefault extends AbstractConfigEntity {
        @NotEmpty
        @Size(min = 1, max = 15)
        @ConfigEntry(path = "lines")
        List<String> lines = new ArrayList<>(Collections.nCopies(16, "line"));

        TooLongDefault(String path) { super(path); }
    }

    /** A module converter that writes a set as one map entry ({@code joined: 'a,b'}) - PR #632 Codex run 1. */
    @ConfigConverterFor(value = Set.class, exact = true)
    public static class JoinedSetConverter implements ConfigConverter<Set<String>> {
        @Override
        public Object toPlain(Set<String> value, ConversionContext ctx) {
            return new LinkedHashMap<>(Collections.singletonMap("joined", String.join(",", value)));
        }

        @Override
        public Set<String> fromPlain(Object plain, ConversionContext ctx) {
            Set<String> result = new LinkedHashSet<>();
            Object joined = plain instanceof Map ? ((Map<?, ?>) plain).get("joined") : null;
            for (String part : String.valueOf(joined == null ? "" : joined).split(",")) {
                if (!part.isEmpty()) { result.add(part); }
            }
            return result;
        }
    }

    /** A @NotEmpty @Size set whose converter writes it as one map entry ({@code joined: 'a,b'}) - PR #632 Codex run 1. */
    static class ParserShapedDefault extends AbstractConfigEntity {
        @NotEmpty
        @Size(min = 2, max = 3)
        @ConfigEntry(path = "tags")
        Set<String> tags = new LinkedHashSet<>(Arrays.asList("a", "b"));

        ParserShapedDefault(String path) { super(path); }
    }

    /** The same shape with a one-element default, outside its own @Size(min = 2). */
    static class ParserShapedShortDefault extends AbstractConfigEntity {
        @NotEmpty
        @Size(min = 2, max = 3)
        @ConfigEntry(path = "tags")
        Set<String> tags = new LinkedHashSet<>(Collections.singletonList("a"));

        ParserShapedShortDefault(String path) { super(path); }
    }

    /** Edit diagnostics must use declared YAML paths, not the different Java member names (#598). */
    static class EditableConfig extends AbstractConfigEntity {
        @Range(min = 1, max = 5)
        @ConfigEntry(path = "retry-count")
        int retryCount = 3;

        @NotEmpty
        @ConfigEntry(path = "allowed-commands")
        List<String> allowedCommands = new ArrayList<>(Collections.singletonList("login"));

        @Size(min = 2, max = 8)
        @ConfigEntry(path = "display-name")
        String displayName = "name";

        @Pattern(regex = "[a-z]+")
        @ConfigEntry(path = "mode-code")
        String modeCode = "abc";

        EditableConfig(String path) { super(path); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry-count=0", "allowed-commands=[]", "display-name=\"x\"", "mode-code=\"ABC\""})
    void editRefusalNamesTheDeclaredKeyAndDoesNotRequestRestart(String candidate) throws Exception {
        Path file = write("retry-count: 3\nallowed-commands: [login]\ndisplay-name: name\nmode-code: abc\n");
        EditableConfig entity = new EditableConfig("sidebar.yml");
        entity.init(plugin);
        byte[] before = Files.readAllBytes(file);
        String key = candidate.substring(0, candidate.indexOf('='));
        JsonObject edit = new JsonObject();
        edit.add(key, JsonParser.parseString(candidate.substring(candidate.indexOf('=') + 1)));

        assertThatThrownBy(() -> entity.validateProposedProperties(edit))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("edit was not applied")
                .hasMessageContaining("key '" + key + "'")
                .hasMessageNotContaining("refused to load").hasMessageNotContaining("restart")
                .hasMessageNotContaining("retryCount").hasMessageNotContaining("allowedCommands")
                .hasMessageNotContaining("displayName").hasMessageNotContaining("modeCode");
        assertThatThrownBy(() -> entity.updateProperties(edit))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("edit was not applied")
                .hasMessageContaining("key '" + key + "'")
                .hasMessageNotContaining("refused to load").hasMessageNotContaining("restart");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(entity.retryCount).isEqualTo(3);
        assertThat(entity.allowedCommands).containsExactly("login");
        assertThat(entity.displayName).isEqualTo("name");
        assertThat(entity.modeCode).isEqualTo("abc");
        assertThat(entity.isModifiedSinceSnapshot()).isFalse();
    }

    @Test
    void bindingRangeEditRefusalUsesTheSameEditDiagnostic() throws Exception {
        Path file = write("retry-count: 3\nallowed-commands: [login]\ndisplay-name: name\nmode-code: abc\n");
        EditableConfig entity = new EditableConfig("sidebar.yml");
        entity.init(plugin);
        entity.addBindingRange("retry-count", "must be at most 3 seconds", seconds -> seconds != null && seconds <= 3);
        byte[] before = Files.readAllBytes(file);
        JsonObject edit = new JsonObject();
        edit.addProperty("retry-count", 4);
        assertThatThrownBy(() -> entity.validateProposedProperties(edit))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("edit was not applied")
                .hasMessageContaining("key 'retry-count'").hasMessageNotContaining("refused to load")
                .hasMessageNotContaining("restart");
        assertThatThrownBy(() -> entity.updateProperties(edit))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("edit was not applied")
                .hasMessageContaining("key 'retry-count'").hasMessageNotContaining("restart");
        assertThat(entity.retryCount).isEqualTo(3);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    void aRefusedEditDoesNotChangeLaterLoadOrReloadDiagnostics() throws Exception {
        Path file = write("retry-count: 3\nallowed-commands: [login]\ndisplay-name: name\nmode-code: abc\n");
        EditableConfig entity = new EditableConfig("sidebar.yml");
        entity.init(plugin);
        JsonObject edit = new JsonObject();
        edit.addProperty("retry-count", 0);
        assertThatThrownBy(() -> entity.updateProperties(edit)).isInstanceOf(ConfigurationException.class);
        Files.write(file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("retry-count: 3", "retry-count: 0").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(entity::reload).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("did not reload").hasMessageContaining("field 'retryCount'")
                .hasMessageContaining("reload again").hasMessageNotContaining("edit was not applied");
        assertThat(entity.retryCount).isEqualTo(3);
        assertThatThrownBy(() -> new EditableConfig("sidebar.yml").init(plugin))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'retryCount'")
                .hasMessageContaining("restart");
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SideBarModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.ensureCleanState();
    }

    /** Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (17-74 gate-1 F1). */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private Path write(String text) throws Exception {
        Path file = directory.resolve("sidebar.yml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String lines(int count) {
        StringBuilder yaml = new StringBuilder("lines:\n");
        for (int i = 0; i < count; i++) { yaml.append("  - line").append(i).append('\n'); }
        return yaml.toString();
    }

    @Test
    @DisplayName("lines: [] runs the three default lines with one warning, and the module loads")
    void emptyLinesRunTheDefault() throws Exception {
        Path file = write("lines: []\naliases: {sb: sidebar}\n");
        byte[] before = Files.readAllBytes(file);
        SideBar sideBar = new SideBar("sidebar.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            sideBar.init(plugin);
            assertThat(sideBar.lines).containsExactlyElementsOf(THREE);
            assertThat(capture.messages()).hasSize(1);
            assertThat(capture.messages().get(0)).contains("'lines'").contains("@NotEmpty").contains("Welcome");
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("lines: ~ (null) runs the default too, with the warning naming null")
    void nullLinesRunTheDefault() throws Exception {
        write("lines: ~\naliases: {sb: sidebar}\n");
        SideBar sideBar = new SideBar("sidebar.yml");

        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            sideBar.init(plugin);
            assertThat(sideBar.lines).containsExactlyElementsOf(THREE);
            assertThat(capture.messages()).singleElement().asString().contains("'lines'").contains("found null");
        }
    }

    @Test
    @DisplayName("sixteen lines in the file are refused by @Size as before")
    void sixteenLinesAreRefusedBySize() throws Exception {
        write(lines(16) + "aliases: {sb: sidebar}\n");

        assertThatThrownBy(() -> new SideBar("sidebar.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'lines' size 16 is out of bounds [1, 15]");
    }

    @Test
    @DisplayName("a declared default of sixteen lines is a declaration error naming the field and @Size, with [] in the file")
    void defaultOutsideItsSizeIsADeclarationError() throws Exception {
        Path file = write("lines: []\n");
        byte[] before = Files.readAllBytes(file);

        assertThatThrownBy(() -> new TooLongDefault("sidebar.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("SideBarModule").hasMessageContaining("cannot hold")
                .hasMessageContaining("field 'lines'").hasMessageContaining("declared default")
                .hasMessageContaining("@Size").hasMessageContaining("16");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("the declared default is checked whatever the file holds: three valid lines do not hide it")
    void defaultIsCheckedEvenWhenTheFileIsValid() throws Exception {
        write(lines(3));

        assertThatThrownBy(() -> new TooLongDefault("sidebar.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("declared default").hasMessageContaining("@Size");
    }

    @Test
    @DisplayName("the default's size is the Java value's, not its serialized shape: a converter writing it as one map entry loads")
    void defaultIsMeasuredOnTheJavaValue() throws Exception {
        try (MockedStatic<PackageScanUtils> scanner = mockStatic(PackageScanUtils.class)) {
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> call.getArgument(0) == ConfigConverterFor.class
                            ? Collections.singleton(JoinedSetConverter.class) : Collections.emptySet());
            ConverterRegistry.prepareModule(plugin, new String[]{"fixture.joined"}, getClass().getClassLoader());
        }
        write("tags:\n  joined: x,y\n");
        ParserShapedDefault config = new ParserShapedDefault("sidebar.yml");
        config.init(plugin);
        assertThat(config.tags).containsExactly("x", "y");

        assertThatThrownBy(() -> new ParserShapedShortDefault("sidebar.yml").init(plugin))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining("holds 1 entries")
                .hasMessageContaining("@Size [2, 3]");
    }

    @Test
    @DisplayName("a panel write of [] to a @NotEmpty list is refused naming the field; nothing is written")
    void panelEmptyListIsRefused() throws Exception {
        Path file = write(lines(3) + "aliases: {sb: sidebar}\n");
        SideBar sideBar = new SideBar("sidebar.yml");
        sideBar.init(plugin);
        byte[] before = Files.readAllBytes(file);

        JsonObject edit = new JsonObject();
        edit.add("lines", new JsonArray());
        assertThatThrownBy(() -> sideBar.updateProperties(edit)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("key 'lines' must not be empty");
        assertThat(sideBar.lines).hasSize(3);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThatThrownBy(() -> sideBar.validateProposedProperties(edit)).isInstanceOf(ConfigurationException.class);
    }

    @Test
    @DisplayName("a panel write of {} to a @NotEmpty map is refused too")
    void panelEmptyMapIsRefused() throws Exception {
        Path file = write(lines(3) + "aliases: {sb: sidebar}\n");
        SideBar sideBar = new SideBar("sidebar.yml");
        sideBar.init(plugin);
        byte[] before = Files.readAllBytes(file);

        JsonObject edit = new JsonObject();
        edit.add("aliases", new JsonObject());
        assertThatThrownBy(() -> sideBar.updateProperties(edit)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("key 'aliases' must not be empty");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("control: a panel write of two lines is written")
    void panelNonEmptyListIsWritten() throws Exception {
        Path file = write(lines(3) + "aliases: {sb: sidebar}\n");
        SideBar sideBar = new SideBar("sidebar.yml");
        sideBar.init(plugin);

        JsonObject edit = new JsonObject();
        JsonArray two = new JsonArray();
        two.add("a");
        two.add("b");
        edit.add("lines", two);
        sideBar.updateProperties(edit);
        assertThat(sideBar.lines).containsExactly("a", "b");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("- a").contains("- b");
    }
}
