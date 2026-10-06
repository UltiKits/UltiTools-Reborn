package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #631 (maintainer decision of 2026-10-06, option A): a constraint annotation on a value type it can never check - or
 * on a field of an element type reached through a declared setting, which the framework never validates - refuses the
 * module at load with one message naming the field, the annotation and why; nothing is silently ignored. {@code @Size}
 * is extended to count a map's entries. On the base every declaration below loads silently.
 */
@DisplayName("A constraint the framework cannot check refuses the module at load; @Size counts map entries (#631)")
class ConstraintDeclarationTest {

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    static class PatternOnList extends AbstractConfigEntity {
        @Pattern(regex = "[a-z]+")
        @ConfigEntry(path = "names")
        List<String> names = new ArrayList<>(Collections.singletonList("ab"));

        PatternOnList(String path) { super(path); }
    }

    static class PlainList extends AbstractConfigEntity {
        @ConfigEntry(path = "names")
        List<String> names = new ArrayList<>(Collections.singletonList("ab"));

        PlainList(String path) { super(path); }
    }

    static class RangeOnText extends AbstractConfigEntity {
        @Range(min = 0, max = 10)
        @ConfigEntry(path = "limit")
        String limit = "5";

        RangeOnText(String path) { super(path); }
    }

    static class RangeOnList extends AbstractConfigEntity {
        @Range(min = 0, max = 10)
        @ConfigEntry(path = "limits")
        List<Integer> limits = new ArrayList<>(Collections.singletonList(5));

        RangeOnList(String path) { super(path); }
    }

    static class SizeOnInteger extends AbstractConfigEntity {
        @Size(max = 3)
        @ConfigEntry(path = "count")
        Integer count = 5;

        SizeOnInteger(String path) { super(path); }
    }

    static class ElementFields extends AbstractConfigEntity {
        @ConfigEntry(path = "outputs")
        Map<String, ConstraintFixtures.Item> outputs =
                new LinkedHashMap<>(Collections.singletonMap("stone", new ConstraintFixtures.Item("STONE", 1)));

        ElementFields(String path) { super(path); }
    }

    static class NestedElementFields extends AbstractConfigEntity {
        @ConfigEntry(path = "recipes")
        Map<String, ConstraintFixtures.Recipe> recipes = new LinkedHashMap<>(Collections.singletonMap("r",
                new ConstraintFixtures.Recipe(new ConstraintFixtures.Item("STONE", 1))));

        NestedElementFields(String path) { super(path); }
    }

    static class BackReference extends AbstractConfigEntity {
        @ConfigEntry(path = "links")
        Map<String, ConstraintFixtures.OwnerLink> links =
                new LinkedHashMap<>(Collections.singletonMap("a", new ConstraintFixtures.OwnerLink("a")));

        BackReference(String path) { super(path); }
    }

    static class TransientCache extends AbstractConfigEntity {
        @ConfigEntry(path = "links")
        Map<String, ConstraintFixtures.TransientLink> links =
                new LinkedHashMap<>(Collections.singletonMap("a", new ConstraintFixtures.TransientLink("a")));

        TransientCache(String path) { super(path); }
    }

    static class NotAnEntry extends AbstractConfigEntity {
        @ConfigEntry(path = "limit")
        int limit = 5;

        @Range(min = 0, max = 10)
        int unbound = 5;

        NotAnEntry(String path) { super(path); }
    }

    static class SizedMap extends AbstractConfigEntity {
        @Size(max = 2)
        @ConfigEntry(path = "aliases")
        Map<String, String> aliases = new LinkedHashMap<>(Collections.singletonMap("a", "b"));

        SizedMap(String path) { super(path); }
    }

    static class SizedArray extends AbstractConfigEntity {
        @Size(max = 2)
        @ConfigEntry(path = "aliases")
        String[] aliases = {"a"};

        SizedArray(String path) { super(path); }
    }

    static class TwoErrors extends AbstractConfigEntity {
        @Range(min = 0, max = 10)
        @ConfigEntry(path = "limit")
        String limit = "5";

        @Size(max = 3)
        @ConfigEntry(path = "count")
        Integer count = 1;

        TwoErrors(String path) { super(path); }
    }

    static class PatternOnChar extends AbstractConfigEntity {
        @Pattern(regex = "[a-z]")
        @ConfigEntry(path = "code")
        char code = 'a';

        PatternOnChar(String path) { super(path); }
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("DeclModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        ConstraintFixtures.prepareModuleConverters(plugin);
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
        Path file = directory.resolve("decl.yml");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void assertDeclarationRefusal(Throwable refusal, String... fragments) {
        assertThat(refusal).isInstanceOf(ConfigurationException.class);
        assertThat(refusal.getMessage()).contains("DeclModule").contains("refused to load").contains("cannot check")
                .contains(fragments);
    }

    @Test
    @DisplayName("@Pattern on a list refuses the module at load naming the field and the annotation; the file is untouched")
    void patternOnListIsRefused() throws Exception {
        Path file = write("names: [ABC]\n");
        byte[] before = Files.readAllBytes(file);

        Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(() -> new PatternOnList("decl.yml").init(plugin));
        assertDeclarationRefusal(refusal, "field 'names'", "@Pattern", "text");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("control: the same list without @Pattern loads")
    void sameListWithoutPatternLoads() throws Exception {
        write("names: [ABC]\n");
        PlainList list = new PlainList("decl.yml");
        list.init(plugin);
        assertThat(list.names).containsExactly("ABC");
    }

    @Test
    @DisplayName("@Range on text is refused at load")
    void rangeOnTextIsRefused() throws Exception {
        write("limit: '99'\n");
        assertDeclarationRefusal(org.assertj.core.api.Assertions.catchThrowable(() -> new RangeOnText("decl.yml").init(plugin)),
                "field 'limit'", "@Range", "numbers");
    }

    @Test
    @DisplayName("@Range on a list of numbers is refused at load")
    void rangeOnListIsRefused() throws Exception {
        write("limits: [99]\n");
        assertDeclarationRefusal(org.assertj.core.api.Assertions.catchThrowable(() -> new RangeOnList("decl.yml").init(plugin)),
                "field 'limits'", "@Range", "numbers");
    }

    @Test
    @DisplayName("@Size on an Integer is refused at load")
    void sizeOnIntegerIsRefused() throws Exception {
        write("count: 12345\n");
        assertDeclarationRefusal(org.assertj.core.api.Assertions.catchThrowable(() -> new SizeOnInteger("decl.yml").init(plugin)),
                "field 'count'", "@Size");
    }

    @Test
    @DisplayName("constraints on a field of a map's element type (UltiRecipe's OutputItem shape) are refused in one message")
    void elementTypeFieldsAreRefused() throws Exception {
        write("outputs:\n  stone: {material: STONE, amount: 1}\n");
        Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(() -> new ElementFields("decl.yml").init(plugin));
        assertDeclarationRefusal(refusal, "field 'outputs'", "ConstraintFixtures.Item.material", "@NotEmpty",
                "ConstraintFixtures.Item.amount", "@Range");
        assertThat(refusal.getMessage().split("ConstraintFixtures.Item.material", -1)).as("named once").hasSize(2);
    }

    @Test
    @DisplayName("an element type two levels below the setting is walked too")
    void nestedElementTypeFieldsAreRefused() throws Exception {
        write("recipes:\n  r: {output: {material: STONE, amount: 1}}\n");
        assertDeclarationRefusal(
                org.assertj.core.api.Assertions.catchThrowable(() -> new NestedElementFields("decl.yml").init(plugin)),
                "field 'recipes'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("a value type's back-reference to a config class is not walked: that class's own entity checks it (gate-1 F1)")
    void backReferenceToAConfigClassLoads() throws Exception {
        write("links: {a: first}\n");
        BackReference config = new BackReference("decl.yml");
        config.init(plugin);
        assertThat(config.links).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("a value type's transient field is not part of the bound value and is not walked (gate-1 F1)")
    void transientFieldIsNotWalked() throws Exception {
        write("links: {a: first}\n");
        TransientCache config = new TransientCache("decl.yml");
        config.init(plugin);
        assertThat(config.links).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("a constraint on a field that is not a @ConfigEntry setting is refused: nothing would check it")
    void constraintOffASettingIsRefused() throws Exception {
        write("limit: 5\n");
        assertDeclarationRefusal(org.assertj.core.api.Assertions.catchThrowable(() -> new NotAnEntry("decl.yml").init(plugin)),
                "field 'unbound'", "@Range", "@ConfigEntry");
    }

    @Test
    @DisplayName("a declaration refusal comes before the file is read or written: a missing file is not created")
    void declarationRefusalWritesNothing() throws Exception {
        Path file = directory.resolve("decl.yml");
        assertThat(file).doesNotExist();
        assertThatThrownBy(() -> new PatternOnList("decl.yml").init(plugin)).isInstanceOf(ConfigurationException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("@Size counts a map's entries: three entries under max 2 refuse the module naming field, size and bounds")
    void sizeCountsMapEntries() throws Exception {
        write("aliases: {a: x, b: y, c: z}\n");
        assertThatThrownBy(() -> new SizedMap("decl.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'aliases' size 3 is out of bounds [0, 2]");
    }

    @Test
    @DisplayName("control: two map entries under max 2 load")
    void twoMapEntriesLoad() throws Exception {
        write("aliases: {a: x, b: y}\n");
        SizedMap map = new SizedMap("decl.yml");
        map.init(plugin);
        assertThat(map.aliases).hasSize(2);
    }

    @Test
    @DisplayName("@Size counts an array's length")
    void sizeCountsArrayLength() throws Exception {
        write("aliases: [a, b, c]\n");
        assertThatThrownBy(() -> new SizedArray("decl.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("field 'aliases' size 3 is out of bounds [0, 2]");
        write("aliases: [a, b]\n");
        SizedArray array = new SizedArray("decl.yml");
        array.init(plugin);
        assertThat(array.aliases).containsExactly("a", "b");
    }

    @Test
    @DisplayName("@Pattern on a char is text: a mismatch refuses like on a String")
    void patternOnCharIsChecked() throws Exception {
        write("code: X\n");
        assertThatThrownBy(() -> new PatternOnChar("decl.yml").init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("field 'code' value 'X' does not match pattern '[a-z]'");
        write("code: b\n");
        PatternOnChar ok = new PatternOnChar("decl.yml");
        ok.init(plugin);
        assertThat(ok.code).isEqualTo('b');
    }

    @Test
    @DisplayName("every declaration error of a class is named in one refusal")
    void allErrorsInOneRefusal() throws Exception {
        write("limit: '5'\ncount: 1\n");
        assertDeclarationRefusal(org.assertj.core.api.Assertions.catchThrowable(() -> new TwoErrors("decl.yml").init(plugin)),
                "2 constraints", "field 'limit'", "@Range", "field 'count'", "@Size");
    }
}
