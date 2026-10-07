package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #631 (maintainer decisions of 2026-10-06): a constraint annotation on a {@code @ConfigEntry} field whose declared type
 * can never hold a value it checks, or on a field of the config class that is not a {@code @ConfigEntry}, refuses the
 * module at load with one message naming the field, the annotation and why. {@code @Size} is extended to count a map's
 * entries. A constraint on a field inside a setting's value type is not checked at all - the documented limit pinned by
 * {@link #nestedConstraintsAreNotChecked}.
 */
@DisplayName("A constraint on a @ConfigEntry field the framework cannot check refuses the module at load; @Size counts map entries (#631)")
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

    static class InheritedElements extends AbstractConfigEntity {
        @ConfigEntry(path = "items")
        ConstraintFixtures.ItemList items = new ConstraintFixtures.ItemList();

        InheritedElements(String path) { super(path); }
    }

    static class SuperWildcard extends AbstractConfigEntity {
        @ConfigEntry(path = "items")
        List<? super ConstraintFixtures.Item> items = new ArrayList<>();

        SuperWildcard(String path) { super(path); }
    }

    static class ExtendsWildcard extends AbstractConfigEntity {
        @ConfigEntry(path = "items")
        List<? extends ConstraintFixtures.Item> items = new ArrayList<>();

        ExtendsWildcard(String path) { super(path); }
    }

    static class BoundedTypeVariable<T extends ConstraintFixtures.Item> extends AbstractConfigEntity {
        @ConfigEntry(path = "items")
        List<T> items = new ArrayList<>();

        BoundedTypeVariable(String path) { super(path); }
    }

    static class NestedWildcards extends AbstractConfigEntity {
        @ConfigEntry(path = "groups")
        Map<String, List<? super ConstraintFixtures.Item>> groups = new LinkedHashMap<>();

        @ConfigEntry(path = "others")
        Map<? extends String, ? extends List<? extends ConstraintFixtures.Item>> others = new LinkedHashMap<>();

        NestedWildcards(String path) { super(path); }
    }

    static class OptionalItem extends AbstractConfigEntity {
        @ConfigEntry(path = "opt")
        java.util.Optional<ConstraintFixtures.Item> opt = java.util.Optional.empty();

        OptionalItem(String path) { super(path); }
    }

    static class AtomicItem extends AbstractConfigEntity {
        @ConfigEntry(path = "ref")
        java.util.concurrent.atomic.AtomicReference<ConstraintFixtures.Item> ref =
                new java.util.concurrent.atomic.AtomicReference<>();

        AtomicItem(String path) { super(path); }
    }

    static class MultimapItem extends AbstractConfigEntity {
        @ConfigEntry(path = "multi")
        com.google.common.collect.Multimap<String, ConstraintFixtures.Item> multi =
                com.google.common.collect.ArrayListMultimap.create();

        MultimapItem(String path) { super(path); }
    }

    static class CappedListSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "capped")
        ConstraintFixtures.CappedList capped = new ConstraintFixtures.CappedList();

        CappedListSetting(String path) { super(path); }
    }

    static class CappedListElements extends AbstractConfigEntity {
        @ConfigEntry(path = "capped")
        List<ConstraintFixtures.CappedList> capped = new ArrayList<>();

        CappedListElements(String path) { super(path); }
    }

    static class CappedMapSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "capped")
        ConstraintFixtures.CappedMap capped = new ConstraintFixtures.CappedMap();

        CappedMapSetting(String path) { super(path); }
    }

    static class ManyHoldersSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "many")
        ConstraintFixtures.ManyHolders many = new ConstraintFixtures.ManyHolders("m");

        ManyHoldersSetting(String path) { super(path); }
    }

    static class TreeSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "tree")
        ConstraintFixtures.Tree<ConstraintFixtures.Plain> tree = new ConstraintFixtures.Tree<>("t");

        TreeSetting(String path) { super(path); }
    }

    static class ChainSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "chain")
        ConstraintFixtures.Chain chain = new ConstraintFixtures.Chain("c");

        ChainSetting(String path) { super(path); }
    }

    static class DrawingSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "drawing")
        ConstraintFixtures.Drawing value = new ConstraintFixtures.Drawing("v");

        DrawingSetting(String path) { super(path); }
    }

    static class TaggedSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "tagged")
        ConstraintFixtures.Tagged value = new ConstraintFixtures.Tagged("v");

        TaggedSetting(String path) { super(path); }
    }

    static class PrizeSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "prize")
        ConstraintFixtures.Prize value = new ConstraintFixtures.Prize("v");

        PrizeSetting(String path) { super(path); }
    }

    static class AuraSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "aura")
        ConstraintFixtures.Aura value = new ConstraintFixtures.Aura("v");

        AuraSetting(String path) { super(path); }
    }

    static class ItemRackSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "rack")
        ConstraintFixtures.ItemRack value = new ConstraintFixtures.ItemRack("v");

        ItemRackSetting(String path) { super(path); }
    }

    static class PlainRackSetting extends AbstractConfigEntity {
        @ConfigEntry(path = "rack")
        ConstraintFixtures.PlainRack value = new ConstraintFixtures.PlainRack("v");

        PlainRackSetting(String path) { super(path); }
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

        Throwable refusal = catchThrowable(() -> new PatternOnList("decl.yml").init(plugin));
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
        assertDeclarationRefusal(catchThrowable(() -> new RangeOnText("decl.yml").init(plugin)),
                "field 'limit'", "@Range", "numbers");
    }

    @Test
    @DisplayName("@Range on a list of numbers is refused at load")
    void rangeOnListIsRefused() throws Exception {
        write("limits: [99]\n");
        assertDeclarationRefusal(catchThrowable(() -> new RangeOnList("decl.yml").init(plugin)),
                "field 'limits'", "@Range", "numbers");
    }

    @Test
    @DisplayName("@Size on an Integer is refused at load")
    void sizeOnIntegerIsRefused() throws Exception {
        write("count: 12345\n");
        assertDeclarationRefusal(catchThrowable(() -> new SizeOnInteger("decl.yml").init(plugin)),
                "field 'count'", "@Size");
    }

    /**
     * Every shape whose constraints sit on fields of a value type, a nested class, or anything a converter produces - not
     * on the {@code @ConfigEntry} field itself. Maintainer decision of 2026-10-06 (the simplest route, superseding the
     * value-type walk): the framework checks constraints only on fields that are themselves {@code @ConfigEntry}
     * settings, so these load, their constraints are not checked, and nothing is logged about them - a pinned,
     * documented limit.
     */
    static List<Arguments> nestedShapes() {
        return java.util.Arrays.asList(
                Arguments.of("element type field (UltiRecipe's OutputItem shape)", (Function<String, AbstractConfigEntity>) ElementFields::new, "outputs:\n  stone: {material: '', amount: 99}\n"),
                Arguments.of("element type two levels below", (Function<String, AbstractConfigEntity>) NestedElementFields::new, "recipes:\n  r: {output: {material: '', amount: 99}}\n"),
                Arguments.of("inherited container element (ItemList extends ArrayList<Item>)", (Function<String, AbstractConfigEntity>) InheritedElements::new, "items: [{material: '', amount: 99}]\n"),
                Arguments.of("List<? super Item>", (Function<String, AbstractConfigEntity>) SuperWildcard::new, "items: []\n"),
                Arguments.of("List<? extends Item>", (Function<String, AbstractConfigEntity>) ExtendsWildcard::new, "items: []\n"),
                Arguments.of("List<T> with T extends Item", (Function<String, AbstractConfigEntity>) BoundedTypeVariable::new, "items: []\n"),
                Arguments.of("nested wildcards in map values", (Function<String, AbstractConfigEntity>) NestedWildcards::new, "groups: {}\nothers: {}\n"),
                Arguments.of("Optional<Item> by a module converter", (Function<String, AbstractConfigEntity>) OptionalItem::new, "opt: {material: '', amount: 999}\n"),
                Arguments.of("AtomicReference<Item> by a module converter", (Function<String, AbstractConfigEntity>) AtomicItem::new, "ref: {material: '', amount: 999}\n"),
                Arguments.of("Guava Multimap<String, Item> by a module converter", (Function<String, AbstractConfigEntity>) MultimapItem::new, "multi: {}\n"),
                Arguments.of("list subclass with its own constrained field", (Function<String, AbstractConfigEntity>) CappedListSetting::new, "capped: []\n"),
                Arguments.of("list of such list subclasses", (Function<String, AbstractConfigEntity>) CappedListElements::new, "capped: []\n"),
                Arguments.of("map subclass with its own constrained field", (Function<String, AbstractConfigEntity>) CappedMapSetting::new, "capped: {}\n"),
                Arguments.of("one holder with nine type arguments", (Function<String, AbstractConfigEntity>) ManyHoldersSetting::new, "many: m\n"),
                Arguments.of("recursive generic growing at every level", (Function<String, AbstractConfigEntity>) TreeSetting::new, "tree: t\n"),
                Arguments.of("self-referencing value type", (Function<String, AbstractConfigEntity>) ChainSetting::new, "chain: c\n"),
                Arguments.of("interface field with a constrained implementation", (Function<String, AbstractConfigEntity>) DrawingSetting::new, "drawing: v\n"),
                Arguments.of("interface field with unconstrained implementations", (Function<String, AbstractConfigEntity>) TaggedSetting::new, "tagged: v\n"),
                Arguments.of("abstract field with a constrained subclass", (Function<String, AbstractConfigEntity>) PrizeSetting::new, "prize: v\n"),
                Arguments.of("abstract field with unconstrained subclasses", (Function<String, AbstractConfigEntity>) AuraSetting::new, "aura: v\n"),
                Arguments.of("generic interface Slot<Item>", (Function<String, AbstractConfigEntity>) ItemRackSetting::new, "rack: v\n"),
                Arguments.of("generic interface Slot<Plain>", (Function<String, AbstractConfigEntity>) PlainRackSetting::new, "rack: v\n"),
                Arguments.of("value type back-referencing a config class", (Function<String, AbstractConfigEntity>) BackReference::new, "links: {a: first}\n"),
                Arguments.of("value type with a transient constrained field", (Function<String, AbstractConfigEntity>) TransientCache::new, "links: {a: first}\n"));
    }

    @ParameterizedTest(name = "{0} loads unchecked")
    @MethodSource("nestedShapes")
    @DisplayName("a constraint on a field inside a value type is not checked: the module loads, nothing is logged (documented limit)")
    void nestedConstraintsAreNotChecked(String shape, Function<String, AbstractConfigEntity> config, String yaml)
            throws Exception {
        write(yaml);
        AbstractConfigEntity entity = config.apply("decl.yml");
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            entity.init(plugin);
            assertThat(capture.messages()).as("nothing is logged for %s", shape).isEmpty();
        }
    }

    @Test
    @DisplayName("a constraint on a field that is not a @ConfigEntry setting is refused: nothing would check it")
    void constraintOffASettingIsRefused() throws Exception {
        write("limit: 5\n");
        assertDeclarationRefusal(catchThrowable(() -> new NotAnEntry("decl.yml").init(plugin)),
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
        assertDeclarationRefusal(catchThrowable(() -> new TwoErrors("decl.yml").init(plugin)),
                "2 constraints", "field 'limit'", "@Range", "field 'count'", "@Size");
    }
}
