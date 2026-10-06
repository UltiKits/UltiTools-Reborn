package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;

/**
 * Final review F1 of plan 17-76: a constraint on a {@code @ConfigEntry} field declared as a type that can hold a value the
 * annotation checks - {@code Object}, {@code Serializable}, {@code Comparable}, {@code CharSequence}, {@code Number}, any
 * supertype of a checkable kind - is not a declaration error: the runtime validator checks the value bound at load, as it
 * did before 6.3.0. Only a declared type that can never hold a checkable value is refused at load ({@code @Range} on a
 * {@code CharSequence}, {@code @Pattern} on a {@code Number}).
 */
@DisplayName("A constraint on a supertype of a checkable kind is checked on the bound value, not refused (final review F1)")
class ConstraintSupertypeTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TempDir
    Path directory;

    private UltiToolsPlugin plugin;

    /** declared type, annotation, a valid file value, a file value that violates the annotation. */
    static List<Arguments> checkable() {
        return Arrays.asList(
                Arguments.of(Object.class, Range.class, "5", "99"),
                Arguments.of(Object.class, Pattern.class, "abc", "ABC"),
                Arguments.of(Object.class, NotEmpty.class, "x", "''"),
                Arguments.of(Object.class, Size.class, "[a]", "[a, b, c]"),
                Arguments.of(java.io.Serializable.class, Range.class, "5", "99"),
                Arguments.of(java.io.Serializable.class, Pattern.class, "abc", "ABC"),
                Arguments.of(java.io.Serializable.class, NotEmpty.class, "x", "''"),
                Arguments.of(java.io.Serializable.class, Size.class, "[a]", "[a, b, c]"),
                Arguments.of(Comparable.class, Range.class, "5", "99"),
                Arguments.of(Comparable.class, Pattern.class, "abc", "ABC"),
                Arguments.of(Comparable.class, NotEmpty.class, "x", "''"),
                Arguments.of(Comparable.class, Size.class, "ab", "abcdef"),
                Arguments.of(CharSequence.class, Pattern.class, "abc", "ABC"),
                Arguments.of(CharSequence.class, NotEmpty.class, "x", "''"),
                Arguments.of(CharSequence.class, Size.class, "ab", "abcdef"),
                Arguments.of(Number.class, Range.class, "5", "99"));
    }

    /** declared type and an annotation it can never satisfy a check for: still refused at load. */
    static List<Arguments> neverCheckable() {
        return Arrays.asList(
                Arguments.of(CharSequence.class, Range.class),
                Arguments.of(Number.class, Pattern.class),
                Arguments.of(Number.class, Size.class),
                Arguments.of(Number.class, NotEmpty.class));
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        clearLeakedUltiToolsInstance();
        MockBukkitHelper.ensureCleanState();
        plugin = Mockito.mock(UltiToolsPlugin.class);
        lenient().when(plugin.getPluginName()).thenReturn("SupertypeModule");
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

    private static AnnotationDescription describe(Class<? extends Annotation> annotation) {
        AnnotationDescription.Builder builder = AnnotationDescription.Builder.ofType(annotation);
        if (annotation == Size.class) { builder = builder.define("max", 2); }
        if (annotation == Pattern.class) { builder = builder.define("regex", "[a-z]+"); }
        if (annotation == Range.class) { builder = builder.define("min", 0.0).define("max", 10.0); }
        return builder.build();
    }

    private AbstractConfigEntity entity(Class<?> declared, Class<? extends Annotation> annotation)
            throws ReflectiveOperationException {
        AnnotationDescription entry = AnnotationDescription.Builder.ofType(ConfigEntry.class).define("path", "value").build();
        Class<? extends AbstractConfigEntity> generated = new ByteBuddy()
                .subclass(AbstractConfigEntity.class, ConstructorStrategy.Default.IMITATE_SUPER_CLASS_PUBLIC)
                .name("com.ultikits.ultitools.abstracts.generated.Supertype" + SEQUENCE.incrementAndGet())
                .defineField("value", declared, Visibility.PUBLIC)
                .annotateField(Arrays.asList(entry, describe(annotation)))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        return generated.getConstructor(String.class).newInstance("supertype.yml");
    }

    private void write(String value) throws Exception {
        Files.write(directory.resolve("supertype.yml"), ("value: " + value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "@{1} on {0}: a valid value loads, a violating value is refused by the runtime check")
    @MethodSource("checkable")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reads the generated field
    void supertypeOfACheckableKindIsCheckedAtRuntime(Class<?> declared, Class<? extends Annotation> annotation,
            String valid, String violating) throws Exception {
        write(valid);
        AbstractConfigEntity loaded = entity(declared, annotation);
        loaded.init(plugin);
        Field value = loaded.getClass().getDeclaredField("value");
        value.setAccessible(true);
        assertThat(value.get(loaded)).as("the valid value is bound").isNotNull();

        write(violating);
        assertThatThrownBy(() -> entity(declared, annotation).init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("refused to load").hasMessageContaining("field 'value'")
                .hasMessageNotContaining("cannot check");
    }

    @ParameterizedTest(name = "@{1} on {0}: no value of the type is checkable, refused at load")
    @MethodSource("neverCheckable")
    void typeThatCanNeverHoldACheckableValueIsRefused(Class<?> declared, Class<? extends Annotation> annotation)
            throws Exception {
        write("5");
        assertThatThrownBy(() -> entity(declared, annotation).init(plugin)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cannot check").hasMessageContaining("@" + annotation.getSimpleName());
    }
}
