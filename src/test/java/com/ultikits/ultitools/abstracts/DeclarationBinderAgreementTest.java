package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.google.common.reflect.TypeToken;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.convert.ConversionResult;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.config.convert.builtin.ConversionTypes;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * #633 (maintainer decision of 2026-10-06): the declaration check reaches exactly the element types the config binder
 * binds, for every value kind of the support matrix and every generic shape found by review (wildcards either way, a
 * bounded type variable, nested wildcards, an inherited container element, a legacy parser). For each declared type the
 * test binds a sample value through the module's real converter registry - the call the load makes - collects the runtime
 * classes of the bound leaves (everything that is not a list, set, map or array), and requires that set to equal the
 * element types {@link AbstractConfigEntity#boundElementClasses(Type)} reports for the declaration. A future change to one
 * side's type resolution that the other side does not share fails here.
 */
@DisplayName("The declaration check and the binder agree on every declared element type (#633)")
class DeclarationBinderAgreementTest {

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    /** One field per declared shape; the field's generic type is the declaration under test. */
    @SuppressWarnings("unused") // read reflectively
    static class Shapes<T extends ConstraintFixtures.Plain> {
        String text;
        int whole;
        long wide;
        double decimal;
        float single;
        BigDecimal exact;
        boolean flag;
        char letter;
        UUID id;
        ConstraintSupportMatrixTest.Mode mode;
        List<String> list;
        Set<String> set;
        Map<String, String> map;
        String[] array;
        int[] numbers;
        org.bukkit.util.Vector vector;
        ConstraintFixtures.Plain plain;
        Map<String, ConstraintFixtures.Plain> elementField;
        List<List<String>> nested;
        List<? super ConstraintFixtures.Plain> superWildcard;
        List<? extends ConstraintFixtures.Plain> extendsWildcard;
        List<T> typeVariable;
        Map<String, List<? super ConstraintFixtures.Plain>> nestedSuper;
        Map<? extends String, ? extends List<? extends ConstraintFixtures.Plain>> nestedExtends;
        ConstraintFixtures.PlainList inherited;
        @ConfigEntry(path = "tags", parser = ConfigBindingEdgeCaseTest.JoinedSetParser.class)
        Set<String> legacyParser;
    }

    static List<Arguments> shapes() {
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("==", "Vector");
        vector.put("x", 1.0);
        vector.put("y", 2.0);
        vector.put("z", 3.0);
        return Arrays.asList(
                Arguments.of("text", "ab"),
                Arguments.of("whole", 5),
                Arguments.of("wide", 5),
                Arguments.of("decimal", 0.5),
                Arguments.of("single", 0.5),
                Arguments.of("exact", 0.5),
                Arguments.of("flag", true),
                Arguments.of("letter", "a"),
                Arguments.of("id", "00000000-0000-0000-0000-000000000001"),
                Arguments.of("mode", "A"),
                Arguments.of("list", Collections.singletonList("ab")),
                Arguments.of("set", Collections.singletonList("ab")),
                Arguments.of("map", Collections.singletonMap("a", "ab")),
                Arguments.of("array", Collections.singletonList("ab")),
                Arguments.of("numbers", Arrays.asList(1, 2)),
                Arguments.of("vector", vector),
                Arguments.of("plain", "ab"),
                Arguments.of("elementField", Collections.singletonMap("a", "ab")),
                Arguments.of("nested", Collections.singletonList(Collections.singletonList("ab"))),
                Arguments.of("superWildcard", Collections.singletonList("ab")),
                Arguments.of("extendsWildcard", Collections.singletonList("ab")),
                Arguments.of("typeVariable", Collections.singletonList("ab")),
                Arguments.of("nestedSuper", Collections.singletonMap("a", Collections.singletonList("ab"))),
                Arguments.of("nestedExtends", Collections.singletonMap("a", Collections.singletonList("ab"))),
                Arguments.of("inherited", Collections.singletonList("ab")),
                Arguments.of("legacyParser", Collections.singletonMap("joined", "x,y")));
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("AgreementModule");
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
        Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** The runtime classes of the bound value's leaves, boxed: everything below lists, sets, maps and arrays. */
    private static void leaves(Object value, Set<Class<?>> out) {
        if (value instanceof Collection) {
            for (Object element : (Collection<?>) value) { leaves(element, out); }
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                leaves(entry.getKey(), out);
                leaves(entry.getValue(), out);
            }
        } else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) { leaves(Array.get(value, index), out); }
        } else if (value != null) {
            out.add(value.getClass());
        }
    }

    private static Set<Class<?>> boxed(Set<Class<?>> classes) {
        Set<Class<?>> boxed = new LinkedHashSet<>();
        for (Class<?> type : classes) { boxed.add(ConversionTypes.boxed(type)); }
        return boxed;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void checkAndBinderReachTheSameElementTypes(String name, Object plain) throws Exception {
        Field field = Shapes.class.getDeclaredField(name);
        Type declared = TypeToken.of(Shapes.class).resolveType(field.getGenericType()).getType();
        ConversionResult<Object> bound = ConverterRegistry.forModule(plugin).fromPlainResult(plain, declared,
                "agreement.yml", Collections.singletonList(name), field.getAnnotation(ConfigEntry.class));
        assertThat(bound.failures()).as("the sample binds cleanly").isEmpty();

        Set<Class<?>> binder = new LinkedHashSet<>();
        leaves(bound.value(), binder);
        assertThat(binder).as("the sample produced at least one leaf").isNotEmpty();
        assertThat(boxed(AbstractConfigEntity.boundElementClasses(declared)))
                .as("element types the declaration check reaches for %s (%s)", name, declared.getTypeName())
                .containsExactlyInAnyOrderElementsOf(boxed(binder));
    }
}
