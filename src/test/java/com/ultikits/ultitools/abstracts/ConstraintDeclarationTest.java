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

    @Test
    @DisplayName("constraints on a field of a map's element type (UltiRecipe's OutputItem shape) are refused in one message")
    void elementTypeFieldsAreRefused() throws Exception {
        write("outputs:\n  stone: {material: STONE, amount: 1}\n");
        Throwable refusal = catchThrowable(() -> new ElementFields("decl.yml").init(plugin));
        assertDeclarationRefusal(refusal, "field 'outputs'", "ConstraintFixtures.Item.material", "@NotEmpty",
                "ConstraintFixtures.Item.amount", "@Range");
        assertThat(refusal.getMessage().split("ConstraintFixtures.Item.material", -1)).as("named once").hasSize(2);
    }

    @Test
    @DisplayName("an element type two levels below the setting is walked too")
    void nestedElementTypeFieldsAreRefused() throws Exception {
        write("recipes:\n  r: {output: {material: STONE, amount: 1}}\n");
        assertDeclarationRefusal(
                catchThrowable(() -> new NestedElementFields("decl.yml").init(plugin)),
                "field 'recipes'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("an element type inherited from a container superclass (ItemList extends ArrayList<Item>) is walked (PR #632 Codex run 1)")
    void inheritedElementTypeIsWalked() throws Exception {
        write("items: [{material: STONE, amount: 99}]\n");
        assertDeclarationRefusal(catchThrowable(() -> new InheritedElements("decl.yml").init(plugin)),
                "field 'items'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("List<? super Item> reaches Item, the type the binder binds (#633)")
    void superWildcardReachesItsLowerBound() throws Exception {
        write("items: []\n");
        assertDeclarationRefusal(catchThrowable(() -> new SuperWildcard("decl.yml").init(plugin)),
                "field 'items'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("List<? extends Item> reaches Item (#633)")
    void extendsWildcardReachesItsUpperBound() throws Exception {
        write("items: []\n");
        assertDeclarationRefusal(catchThrowable(() -> new ExtendsWildcard("decl.yml").init(plugin)),
                "field 'items'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("List<T> with T extends Item reaches Item, the type variable's bound (#633)")
    void boundedTypeVariableReachesItsBound() throws Exception {
        write("items: []\n");
        assertDeclarationRefusal(catchThrowable(() -> new BoundedTypeVariable<>("decl.yml").init(plugin)),
                "field 'items'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("nested wildcards in map values reach Item through every level (#633)")
    void nestedWildcardsReachTheElement() throws Exception {
        write("groups: {}\nothers: {}\n");
        Throwable refusal = catchThrowable(() -> new NestedWildcards("decl.yml").init(plugin));
        assertDeclarationRefusal(refusal, "field 'groups'", "field 'others'", "ConstraintFixtures.Item.material");
    }

    @Test
    @DisplayName("Optional<Item> bound by a module converter: Item's constraints are refused (top-up T1)")
    void optionalContentIsReached() throws Exception {
        write("opt: {material: '', amount: 999}\n");
        assertDeclarationRefusal(catchThrowable(() -> new OptionalItem("decl.yml").init(plugin)),
                "field 'opt'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("AtomicReference<Item> bound by a module converter: Item's constraints are refused (top-up T1)")
    void atomicReferenceContentIsReached() throws Exception {
        write("ref: {material: '', amount: 999}\n");
        assertDeclarationRefusal(catchThrowable(() -> new AtomicItem("decl.yml").init(plugin)),
                "field 'ref'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("Guava Multimap<String, Item> bound by a module converter: Item's constraints are refused (top-up T1)")
    void multimapValuesAreReached() throws Exception {
        write("multi: {}\n");
        assertDeclarationRefusal(catchThrowable(() -> new MultimapItem("decl.yml").init(plugin)),
                "field 'multi'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("a list subclass's own constrained field is refused, as the setting itself and as an element (top-up T1)")
    void containerSubclassOwnFieldsAreReached() throws Exception {
        write("capped: []\n");
        assertDeclarationRefusal(catchThrowable(() -> new CappedListSetting("decl.yml").init(plugin)),
                "field 'capped'", "ConstraintFixtures.CappedList.cap", "@Range");
        assertDeclarationRefusal(catchThrowable(() -> new CappedListElements("decl.yml").init(plugin)),
                "field 'capped'", "ConstraintFixtures.CappedList.cap", "@Range");
    }

    @Test
    @DisplayName("a map subclass's own constrained field is refused (top-up T1)")
    void mapSubclassOwnFieldsAreReached() throws Exception {
        write("capped: {}\n");
        assertDeclarationRefusal(catchThrowable(() -> new CappedMapSetting("decl.yml").init(plugin)),
                "field 'capped'", "ConstraintFixtures.CappedMap.label", "@NotEmpty");
    }

    @Test
    @DisplayName("one generic holder used with nine type arguments: the ninth's constraints are still refused (top-up T2)")
    void manyParameterizationsAreAllWalked() throws Exception {
        write("many: m\n");
        assertDeclarationRefusal(catchThrowable(() -> new ManyHoldersSetting("decl.yml").init(plugin)),
                "field 'many'", "ConstraintFixtures.Item.material", "ConstraintFixtures.Item.amount");
    }

    @Test
    @DisplayName("a recursive generic whose type grows at every level is refused, never skipped (top-up T2)")
    void unboundedRecursiveGenericIsRefused() throws Exception {
        write("tree: t\n");
        assertDeclarationRefusal(catchThrowable(() -> new TreeSetting("decl.yml").init(plugin)),
                "field 'tree'", "ConstraintFixtures.Tree", "nests");
    }

    @Test
    @DisplayName("control: a value type referring to itself again (the same type) loads")
    void selfReferenceLoads() throws Exception {
        write("chain: c\n");
        ChainSetting config = new ChainSetting("decl.yml");
        config.init(plugin);
        assertThat(config.chain.name).isEqualTo("c");
    }

    @Test
    @DisplayName("an interface-typed field whose module implementation is constrained refuses, naming field and class")
    void interfaceWithConstrainedImplementationIsRefused() throws Exception {
        write("drawing: v\n");
        assertDeclarationRefusal(catchThrowable(() -> new DrawingSetting("decl.yml").init(plugin)),
                "ConstraintFixtures.Drawing.shape", "ConstraintFixtures.Shape", "ConstraintFixtures.Circle",
                "ConstraintFixtures.Circle.radius");
    }

    @Test
    @DisplayName("an interface-typed field whose module implementations carry no constraint loads")
    void interfaceWithUnconstrainedImplementationsLoads() throws Exception {
        write("tagged: v\n");
        TaggedSetting config = new TaggedSetting("decl.yml");
        config.init(plugin);
        assertThat(config.value.name).isEqualTo("v");
    }

    @Test
    @DisplayName("an abstract-typed field whose module subclass is constrained refuses, naming field and class")
    void abstractBaseWithConstrainedSubclassIsRefused() throws Exception {
        write("prize: v\n");
        assertDeclarationRefusal(catchThrowable(() -> new PrizeSetting("decl.yml").init(plugin)),
                "ConstraintFixtures.Prize.reward", "ConstraintFixtures.Reward", "ConstraintFixtures.CoinReward",
                "ConstraintFixtures.CoinReward.currency");
    }

    @Test
    @DisplayName("an abstract-typed field whose module subclasses carry no constraint loads")
    void abstractBaseWithUnconstrainedSubclassesLoads() throws Exception {
        write("aura: v\n");
        AuraSetting config = new AuraSetting("decl.yml");
        config.init(plugin);
        assertThat(config.value.name).isEqualTo("v");
    }

    @Test
    @DisplayName("a generic interface field Slot<Item>: the implementation ContentSlot<T> holds an Item, refused")
    void genericInterfaceImplementationIsResolvedAndRefused() throws Exception {
        write("rack: v\n");
        assertDeclarationRefusal(catchThrowable(() -> new ItemRackSetting("decl.yml").init(plugin)),
                "ConstraintFixtures.ItemRack.slot", "ConstraintFixtures.ContentSlot", "ConstraintFixtures.Item.material");
    }

    @Test
    @DisplayName("control: the same generic interface field as Slot<Plain> loads")
    void genericInterfaceWithUnconstrainedArgumentLoads() throws Exception {
        write("rack: v\n");
        PlainRackSetting config = new PlainRackSetting("decl.yml");
        config.init(plugin);
        assertThat(config.value.name).isEqualTo("v");
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
