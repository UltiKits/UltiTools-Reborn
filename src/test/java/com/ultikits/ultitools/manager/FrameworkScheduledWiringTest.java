package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Scheduled;

/**
 * Guard against the defect class behind #384: a framework-owned class carrying {@link Scheduled}
 * that nothing ever scans, so the annotation is inert and nothing reports it.
 * <p>
 * {@code TaskManager}'s per-plugin and external entry points both reach their beans by iterating a
 * {@code SimpleContainer}. An object the framework constructs directly belongs to no container and
 * is reached by neither. Before 6.3.0 there was no third path at all, so {@code
 * PlayerCacheManager.sweepExpiredEntries()} -- whose javadoc argues at length for a clock-driven
 * sweep over a hand-rolled {@code BukkitRunnable} -- never ran once, silently, for the whole life
 * of the release it shipped in.
 * <p>
 * <b>What this test enforces.</b> Every class under {@code com.ultikits.ultitools} that declares a
 * {@link Scheduled} method must appear in {@link PluginManager#FRAMEWORK_SCHEDULED_OWNER_TYPES},
 * which is the set {@code PluginManager} actually registers. Adding the annotation without adding
 * the type fails the build, and adding the type is what wires the task -- so satisfying this test
 * and wiring the task are the same act. A test that merely asserted the current sweep runs would
 * have prevented nothing: the next framework class to carry {@code @Scheduled} would break in
 * exactly the same silent way.
 *
 * @since 6.3.0
 */
@DisplayName("Framework @Scheduled wiring")
class FrameworkScheduledWiringTest {

    /**
     * The annotation's descriptor as it appears in a class file's constant pool.
     * <p>
     * Used only as a cheap pre-filter. It cannot be the verdict on its own: a class that merely
     * <em>mentions</em> the type carries the same descriptor. {@code TaskManager} declares a local
     * {@code Scheduled scheduled = method.getAnnotation(...)}, and Maven compiles with debug info
     * by default, so the descriptor lands in its {@code LocalVariableTable} even though
     * {@code TaskManager} carries no {@code @Scheduled} method of its own. The real verdict is
     * reflective, below.
     */
    private static final String DESCRIPTOR = "Lcom/ultikits/ultitools/annotations/Scheduled;";

    private static final String FRAMEWORK_PACKAGE = "com.ultikits.ultitools";

    @Test
    @DisplayName("every framework class declaring a @Scheduled method is in the registered set")
    void everyFrameworkScheduledOwnerIsRegistered() throws Exception {
        Set<Class<?>> declaring = findFrameworkClassesDeclaringScheduledMethods();

        // Positive control. A scan that reads nothing -- wrong root, changed layout, classes not
        // built -- returns an empty set, which is indistinguishable from "no violations" and would
        // make this test pass forever while enforcing nothing. PlayerCacheManager is known to
        // carry the annotation, so if the instrument works it must be found.
        assertThat(declaring)
                .as("positive control: the scan must find PlayerCacheManager, which is known to "
                        + "declare a @Scheduled method. An empty or incomplete result here means "
                        + "the scan is broken, not that the codebase is clean.")
                .contains(PlayerCacheManager.class);

        assertThat(declaring)
                .as("every framework-owned class declaring a @Scheduled method must appear in "
                        + "PluginManager.FRAMEWORK_SCHEDULED_OWNER_TYPES, which is the set that is "
                        + "actually registered. A class missing from it has an annotation that "
                        + "silently never fires -- see #384.")
                .isSubsetOf(PluginManager.FRAMEWORK_SCHEDULED_OWNER_TYPES);
    }

    @Test
    @DisplayName("the registered set contains no type that has stopped declaring a @Scheduled method")
    void registeredSetHasNoStaleEntries() throws Exception {
        Set<Class<?>> declaring = findFrameworkClassesDeclaringScheduledMethods();

        assertThat(PluginManager.FRAMEWORK_SCHEDULED_OWNER_TYPES)
                .as("a type left in the registered set after its @Scheduled method was removed "
                        + "costs a pointless scan and, worse, reads as coverage that is no longer "
                        + "there")
                .isSubsetOf(declaring);
    }

    /**
     * Reflectively determine which framework classes declare a {@link Scheduled} method.
     * <p>
     * Classes are loaded with {@code initialize = false} so no static initialiser runs: several
     * framework classes reach for a live {@code UltiTools.getInstance()} in theirs, and this test
     * has no server. A candidate that cannot be loaded fails the test rather than being skipped --
     * a silent skip would reopen the hole this test exists to close.
     */
    private Set<Class<?>> findFrameworkClassesDeclaringScheduledMethods()
            throws IOException, URISyntaxException {
        Path classesRoot = Paths.get(
                PluginManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        List<String> candidates;
        try (Stream<Path> files = Files.walk(classesRoot)) {
            candidates = files
                    .filter(f -> f.toString().endsWith(".class"))
                    .filter(this::mentionsScheduledDescriptor)
                    .map(f -> toClassName(classesRoot, f))
                    .filter(n -> n.startsWith(FRAMEWORK_PACKAGE))
                    .collect(Collectors.toList());
        }

        assertThat(candidates)
                .as("pre-filter control: scanning %s produced no candidate class files at all, "
                        + "which means the scan is not reading what it thinks it is", classesRoot)
                .isNotEmpty();

        Set<Class<?>> declaring = new LinkedHashSet<>();
        List<String> unloadable = new ArrayList<>();
        for (String name : candidates) {
            Class<?> clazz;
            try {
                // The name is not attacker-controlled: it is derived by walking this build's own
                // target/classes directory, filtered to the com.ultikits.ultitools package, and the
                // load is non-initialising. Rationale precedes the marker deliberately -- an
                // Opengrep marker only counts on its own line or the one immediately above, so
                // putting the justification in between would silently disable the suppression.
                // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
                clazz = Class.forName(name, false, getClass().getClassLoader());
            } catch (Throwable t) {
                unloadable.add(name + " (" + t.getClass().getSimpleName() + ")");
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    declaring.add(clazz);
                    break;
                }
            }
        }

        if (!unloadable.isEmpty()) {
            fail("These candidate classes could not be inspected, so this test cannot say whether "
                    + "they declare an unwired @Scheduled method. Skipping them silently would "
                    + "reopen the hole the test exists to close: " + unloadable);
        }
        return declaring;
    }

    private boolean mentionsScheduledDescriptor(Path classFile) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            return new String(bytes, StandardCharsets.ISO_8859_1).contains(DESCRIPTOR);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + classFile, e);
        }
    }

    private String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative
                .substring(0, relative.length() - ".class".length())
                .replace(java.io.File.separatorChar, '.');
    }
}
