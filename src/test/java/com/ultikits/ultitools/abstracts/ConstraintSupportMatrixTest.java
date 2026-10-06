package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;

/**
 * The constraint support matrix of plan 17-76: every constraint annotation ({@code @NotEmpty}, {@code @Size},
 * {@code @Pattern}, {@code @Range}) on every value kind the config layer binds, each loaded from a file holding a value
 * that violates it. A pair is <b>inert</b> when the module loads on the violating value with nothing said - the
 * declared-but-not-delivered class this milestone removes.
 * <p>
 * Report mode ({@link #ASSERT} {@code false}) prints the measured outcome of every pair and asserts nothing; it is the
 * instrument the plan runs on the base before any remedy. Assertion mode compares every pair with the outcome the
 * maintainer decided on 2026-10-06 (option A) and fails when any pair is inert.
 * <p>
 * Each pair is its own generated config class (one annotated {@code @ConfigEntry} field named {@code value}), so a
 * refusal of one pair cannot hide another.
 */
@DisplayName("Constraint support matrix: no constraint annotation is inert on any value kind (17-76)")
class ConstraintSupportMatrixTest {

    /** Report mode when false: print the table, assert nothing. */
    private static final boolean ASSERT = true;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Map<String, String> TABLE = Collections.synchronizedMap(new java.util.TreeMap<>());

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    /** The outcome of loading a violating value. */
    enum Outcome { REFUSED, DEFAULT_WITH_WARNING, DECLARATION_REFUSED, INERT }

    /** A test enum for the enum kind. */
    public enum Mode { A, B }

    /** One value kind: its declared type, a valid declared default, and the violating value per annotation. */
    static final class Kind {
        final String name;
        final Object type;
        final Object fallback;
        final Map<Class<? extends Annotation>, String> violating;

        Kind(String name, Object type, Object fallback, String notEmpty, String size, String pattern, String range) {
            this.name = name; this.type = type; this.fallback = fallback;
            this.violating = new LinkedHashMap<>();
            violating.put(NotEmpty.class, notEmpty);
            violating.put(Size.class, size);
            violating.put(Pattern.class, pattern);
            violating.put(Range.class, range);
        }
    }

    private static List<Kind> kinds() {
        TypeDescription.Generic listOfString = TypeDescription.Generic.Builder.parameterizedType(List.class, String.class).build();
        TypeDescription.Generic setOfString = TypeDescription.Generic.Builder.parameterizedType(Set.class, String.class).build();
        TypeDescription.Generic mapOfString = TypeDescription.Generic.Builder
                .parameterizedType(Map.class, String.class, String.class).build();
        TypeDescription.Generic mapOfItem = TypeDescription.Generic.Builder
                .parameterizedType(Map.class, String.class, ConstraintFixtures.Item.class).build();
        Map<String, Object> mapDefault = new LinkedHashMap<>(Collections.singletonMap("a", "ab"));
        return Arrays.asList(
                new Kind("text", String.class, "ab", "''", "abcdef", "ABC", "'5'"),
                new Kind("int", int.class, 5, "0", "12345", "5", "99"),
                new Kind("long", long.class, 5L, "0", "12345", "5", "99"),
                new Kind("double", double.class, 0.5, "0.0", "12345.0", "5.0", "99.0"),
                new Kind("float", float.class, 0.5f, "0.0", "12345.0", "5.0", "99.0"),
                new Kind("BigDecimal", BigDecimal.class, new BigDecimal("0.5"), "0", "12345", "5", "99"),
                new Kind("boolean", boolean.class, true, "false", "true", "true", "true"),
                new Kind("char", char.class, 'a', "' '", "x", "X", "x"),
                new Kind("UUID", UUID.class, new UUID(0, 1), "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000002"),
                new Kind("enum", Mode.class, Mode.A, "B", "B", "B", "B"),
                new Kind("list", listOfString, new ArrayList<>(Collections.singletonList("ab")),
                        "[]", "[a, b, c]", "[ABC]", "['5']"),
                new Kind("set", setOfString, new LinkedHashSet<>(Collections.singletonList("ab")),
                        "[]", "[a, b, c]", "[ABC]", "['5']"),
                new Kind("map", mapOfString, mapDefault, "{}", "{a: x, b: y, c: z}", "{a: ABC}", "{a: '5'}"),
                new Kind("array", String[].class, new String[]{"ab"}, "[]", "[a, b, c]", "[ABC]", "['5']"),
                new Kind("Bukkit Vector", org.bukkit.util.Vector.class, new org.bukkit.util.Vector(1, 2, 3),
                        "{==: Vector, x: 9.0, y: 9.0, z: 9.0}", "{==: Vector, x: 9.0, y: 9.0, z: 9.0}",
                        "{==: Vector, x: 9.0, y: 9.0, z: 9.0}", "{==: Vector, x: 9.0, y: 9.0, z: 9.0}"),
                new Kind("module type", ConstraintFixtures.Item.class, new ConstraintFixtures.Item("STONE", 1),
                        "{material: '', amount: 1}", "{material: '', amount: 1}", "{material: '', amount: 1}",
                        "{material: X, amount: 99}"),
                new Kind("element field", mapOfItem,
                        new LinkedHashMap<>(Collections.singletonMap("a", new ConstraintFixtures.Item("STONE", 1))),
                        "{a: {material: '', amount: 1}}", "{a: {material: '', amount: 1}}",
                        "{a: {material: '', amount: 1}}", "{a: {material: X, amount: 99}}"));
    }

    static List<Arguments> pairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (Kind kind : kinds()) {
            for (Class<? extends Annotation> annotation : kind.violating.keySet()) {
                pairs.add(Arguments.of(annotation.getSimpleName(), kind.name, annotation, kind));
            }
        }
        return pairs;
    }

    /**
     * The decided outcome (maintainer decision 2026-10-06, option A): {@code @Range} acts on numbers, {@code @Pattern}
     * on text (a {@code String} or a {@code char}), {@code @Size} on text, collections, maps and arrays, {@code @NotEmpty}
     * refuses empty text and runs the
     * declared default for an empty collection, map or array; anything else is a declaration the framework cannot check.
     * The "element field" kind carries no constraint on the setting itself: its constraints sit on {@code Item}'s fields.
     */
    static Outcome decided(Class<? extends Annotation> annotation, String kind) {
        boolean number = Arrays.asList("int", "long", "double", "float", "BigDecimal").contains(kind);
        boolean text = "text".equals(kind) || "char".equals(kind);
        boolean container = Arrays.asList("list", "set", "map", "array").contains(kind);
        if (annotation == Range.class) { return number ? Outcome.REFUSED : Outcome.DECLARATION_REFUSED; }
        if (annotation == Pattern.class) { return text ? Outcome.REFUSED : Outcome.DECLARATION_REFUSED; }
        if (annotation == Size.class) { return text || container ? Outcome.REFUSED : Outcome.DECLARATION_REFUSED; }
        if (text) { return Outcome.REFUSED; }
        return container ? Outcome.DEFAULT_WITH_WARNING : Outcome.DECLARATION_REFUSED;
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("MatrixModule");
        lenient().when(plugin.getResourceFolderPath()).thenReturn(directory.toString());
        ConfigFileStubs.stubConfigFolder(plugin, directory.toFile());
        ConstraintFixtures.prepareModuleConverters(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.ensureCleanState();
    }

    @AfterAll
    static void printTable() {
        System.out.println("MATRIX | annotation | value kind | outcome |");
        System.out.println("MATRIX |---|---|---|");
        synchronized (TABLE) {
            for (String row : TABLE.values()) { System.out.println("MATRIX " + row); }
        }
    }

    /** Clears a mocked {@code UltiTools} instance an earlier test class in the same fork left behind (17-74 gate-1 F1). */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the framework singleton is a private static field
    private static void clearLeakedUltiToolsInstance() throws ReflectiveOperationException {
        java.lang.reflect.Field instance = com.ultikits.ultitools.UltiTools.class.getDeclaredField("ultiTools");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static AnnotationDescription describe(Class<? extends Annotation> annotation, Kind kind) {
        AnnotationDescription.Builder builder = AnnotationDescription.Builder.ofType(annotation);
        // A char is one character long, so only a lower bound above 1 can be violated by it.
        if (annotation == Size.class) { builder = "char".equals(kind.name) ? builder.define("min", 2) : builder.define("max", 2); }
        if (annotation == Pattern.class) { builder = builder.define("regex", "[a-z]+"); }
        if (annotation == Range.class) { builder = builder.define("min", 0.0).define("max", 10.0); }
        return builder.build();
    }

    /** One generated config class per pair; the "element field" kind puts no constraint on the setting itself. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the generated field's declared default is set reflectively
    private AbstractConfigEntity entity(Class<? extends Annotation> annotation, Kind kind) throws ReflectiveOperationException {
        AnnotationDescription entry = AnnotationDescription.Builder.ofType(ConfigEntry.class).define("path", "value").build();
        boolean element = "element field".equals(kind.name);
        TypeDescription.Generic declared = kind.type instanceof TypeDescription.Generic ? (TypeDescription.Generic) kind.type
                : net.bytebuddy.description.type.TypeDefinition.Sort.describe((Type) kind.type);
        Class<? extends AbstractConfigEntity> generated = new ByteBuddy()
                .subclass(AbstractConfigEntity.class, ConstructorStrategy.Default.IMITATE_SUPER_CLASS_PUBLIC)
                .name("com.ultikits.ultitools.abstracts.generated.Matrix" + SEQUENCE.incrementAndGet())
                .defineField("value", declared, Visibility.PUBLIC)
                .annotateField(element ? Collections.singletonList(entry) : Arrays.asList(entry, describe(annotation, kind)))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        AbstractConfigEntity instance = generated.getConstructor(String.class).newInstance("matrix.yml");
        Field value = generated.getDeclaredField("value");
        value.setAccessible(true);
        value.set(instance, kind.fallback);
        return instance;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reads the generated field
    private Outcome measure(Class<? extends Annotation> annotation, Kind kind) throws Exception {
        Files.write(directory.resolve("matrix.yml"),
                ("value: " + kind.violating.get(annotation) + "\n").getBytes(StandardCharsets.UTF_8));
        AbstractConfigEntity entity = entity(annotation, kind);
        try (ConfigWarningCapture capture = ConfigWarningCapture.install()) {
            try {
                entity.init(plugin);
            } catch (ConfigurationException refused) {
                String message = String.valueOf(refused.getMessage());
                return message.contains("cannot check") || message.contains("cannot hold")
                        ? Outcome.DECLARATION_REFUSED : Outcome.REFUSED;
            }
            boolean warned = !capture.messagesContaining("@NotEmpty").isEmpty();
            return warned ? Outcome.DEFAULT_WITH_WARNING : Outcome.INERT;
        }
    }

    @ParameterizedTest(name = "@{0} on {1}")
    @MethodSource("pairs")
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // report mode asserts nothing by design; assertion mode does
    void pair(String annotationName, String kindName, Class<? extends Annotation> annotation, Kind kind) throws Exception {
        Outcome measured = measure(annotation, kind);
        String key = String.format("%-9s %-14s", annotationName, kindName);
        TABLE.put(key, "| @" + annotationName + " | " + kindName + " | " + measured + " |");
        if (ASSERT) {
            assertThat(measured).as("@%s on %s", annotationName, kindName).isNotEqualTo(Outcome.INERT)
                    .isEqualTo(decided(annotation, kindName));
        }
    }
}
