package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.command.CmdExecutor;

/**
 * Census guard for annotations whose only automatic consumer is the per-plugin component scan.
 * <p>
 * <b>The defect class.</b> {@code ComponentScanner} runs against a module's packages, so an
 * annotation it honours does nothing at all on a class the framework constructs itself. Four
 * separate defects in 6.3.0 were the same mistake:
 * <ul>
 *   <li>#384 -- {@code PlayerCacheManager} carried {@code @Scheduled}; its expiry sweep never ran.</li>
 *   <li>#387 -- {@code EnhancedPlayerEventListener} carried {@code @EventListener}; its seven
 *       handlers never fired.</li>
 *   <li>#393 -- {@code NoOpGameMailService} carried {@code @Service}; it reached no container, so
 *       {@code @Autowired GameMailService} had nothing to resolve.</li>
 *   <li>{@code UltiToolsBean} carried {@code @Configuration} with five {@code @Bean} methods; none
 *       was ever processed.</li>
 * </ul>
 * Each shipped green. Unit tests confirmed the annotations were present and the methods behaved --
 * which is true and beside the point, because nothing invoked them. {@code UltiToolsBeanTest} had
 * fifteen such tests.
 * <p>
 * <b>What this enforces.</b> Not "is it wired" -- that differs per annotation and cannot be
 * computed from the class file. Instead: the set of framework-owned classes carrying each of these
 * annotations must exactly equal a reviewed census. Adding the annotation to a new framework class
 * fails the build, and the only way to pass is to add it here alongside a note saying which
 * registration site covers it -- which is the question that went unasked four times.
 * <p>
 * Equality, not subset, in both directions: an entry left behind after its annotation was removed
 * reads as coverage that is no longer there.
 * <p>
 * {@code @Scheduled} is deliberately absent -- {@link FrameworkScheduledWiringTest} already
 * enforces it against {@code PluginManager.FRAMEWORK_SCHEDULED_OWNER_TYPES}, which is a stronger
 * check because that set is the thing actually registered rather than a census beside it.
 *
 * @since 6.3.0
 */
@DisplayName("Framework scan-only annotation wiring")
class FrameworkAnnotationWiringTest {

    private static final String FRAMEWORK_PACKAGE = "com.ultikits.ultitools";

    /**
     * {@code @EventListener} on a framework class does nothing; {@code ListenerManager} only walks
     * a plugin's container.
     * <p>
     * Registration site: {@code UltiTools.onEnable()} calls
     * {@code Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this)} directly.
     * The annotation is redundant there, and harmless, but it must not be the only thing a future
     * listener relies on.
     */
    private static final Set<String> EVENT_LISTENERS = new LinkedHashSet<>(Arrays.asList(
            "com.ultikits.ultitools.listeners.PlayerJoinListener"));

    /**
     * {@code @Service} on a framework class does nothing.
     * <p>
     * Registration site: all four are {@code new}-ed and handed to
     * {@code context.registerSingleton(...)} in {@code DependenceManagers.initCoreServices()}.
     * {@code NoOpGameMailService} was the one that was not, until #393.
     */
    private static final Set<String> SERVICES = new LinkedHashSet<>(Arrays.asList(
            "com.ultikits.ultitools.services.impl.DefaultEmailService",
            "com.ultikits.ultitools.services.impl.InMemeryTeleportService",
            "com.ultikits.ultitools.services.impl.InMemoryNotificationService",
            "com.ultikits.ultitools.services.impl.NoOpGameMailService"));

    /**
     * {@code @Configuration} on a framework class does nothing:
     * {@code SimpleContainer.processConfigurationClass} is never invoked against
     * {@code com.ultikits.ultitools} in production, only from tests.
     * <p>
     * Registration site: none, and none needed. {@code ContextConfig} is an empty marker whose own
     * javadoc records that its scan declaration was removed in 6.3.0 for exactly this reason. A
     * {@code @Configuration} class here that declares {@code @Bean} methods would be inert --
     * which is what {@code UltiToolsBean} was before it was deleted.
     */
    private static final Set<String> CONFIGURATIONS = new LinkedHashSet<>(Arrays.asList(
            "com.ultikits.ultitools.context.ContextConfig"));

    /**
     * {@code @CmdExecutor} on a framework class does nothing by itself.
     * <p>
     * Registration site: {@code UltiTools.registerCommands()} calls
     * {@code commandManager.registerCoreCommand(new ...())} for each of these three.
     */
    private static final Set<String> COMMAND_EXECUTORS = new LinkedHashSet<>(Arrays.asList(
            "com.ultikits.ultitools.commands.CloudLoginCommand",
            "com.ultikits.ultitools.commands.PluginInstallCommands",
            "com.ultikits.ultitools.commands.UltiToolsCommands"));

    @Test
    @DisplayName("@EventListener census matches the classes actually registered by hand")
    void eventListenerCensusIsExact() throws Exception {
        assertCensus(EventListener.class, EVENT_LISTENERS,
                "com.ultikits.ultitools.listeners.PlayerJoinListener",
                "registered by UltiTools.onEnable() via Bukkit's registerEvents");
    }

    @Test
    @DisplayName("@Service census matches the classes actually registered by hand")
    void serviceCensusIsExact() throws Exception {
        assertCensus(Service.class, SERVICES,
                "com.ultikits.ultitools.services.impl.DefaultEmailService",
                "registered by DependenceManagers.initCoreServices()");
    }

    @Test
    @DisplayName("@Configuration census matches, and no framework @Configuration declares @Bean")
    void configurationCensusIsExact() throws Exception {
        assertCensus(Configuration.class, CONFIGURATIONS,
                "com.ultikits.ultitools.context.ContextConfig",
                "an empty marker; nothing processes framework @Configuration in production");
    }

    @Test
    @DisplayName("@CmdExecutor census matches the classes actually registered by hand")
    void commandExecutorCensusIsExact() throws Exception {
        assertCensus(CmdExecutor.class, COMMAND_EXECUTORS,
                "com.ultikits.ultitools.commands.UltiToolsCommands",
                "registered by UltiTools.registerCommands() via registerCoreCommand");
    }

    /**
     * Compare the classes actually carrying {@code annotation} against the reviewed census.
     *
     * @param annotation     the scan-only annotation to census
     * @param census         the reviewed set, each entry documented on its constant above
     * @param knownMember    a class certain to carry the annotation, used as a positive control so
     *                       a scan that reads nothing cannot pass as "no violations"
     * @param howItIsWired   named in the failure message, so the reader is told what question to
     *                       answer rather than just which assertion broke
     */
    private void assertCensus(Class<? extends Annotation> annotation, Set<String> census,
                              String knownMember, String howItIsWired) throws Exception {
        Set<String> found = findFrameworkClassesAnnotatedWith(annotation);

        assertThat(found)
                .as("positive control: the scan must find %s. An empty or short result means the "
                        + "scan is broken, not that the codebase is clean.", knownMember)
                .contains(knownMember);

        assertThat(found)
                .as("Framework-owned classes are NOT component-scanned, so @%s on one of them does "
                        + "nothing on its own -- see #384/#387/#393. The census in this test is the "
                        + "reviewed list, and every entry names its registration site (for these: "
                        + "%s).%n%nIf a class was ADDED: wire it at a real registration site, then "
                        + "add it here with a note saying which one. If a class was REMOVED: drop "
                        + "it from the census, since a stale entry reads as coverage that no longer "
                        + "exists.", annotation.getSimpleName(), howItIsWired)
                .containsExactlyInAnyOrderElementsOf(census);
    }

    /**
     * Reflectively determine which framework classes carry {@code annotation} at class level.
     * <p>
     * A byte-level pre-filter on the annotation's descriptor keeps the set of classes actually
     * loaded small: several framework classes reach for a live {@code UltiTools.getInstance()} in
     * their static initialiser, and some link against {@code provided}-scope optional plugins, so
     * loading the whole tree would be both slow and fragile. Loads are non-initialising for the
     * same reason. A candidate that cannot be loaded fails the test rather than being skipped -- a
     * silent skip would reopen the hole this test exists to close.
     */
    private Set<String> findFrameworkClassesAnnotatedWith(Class<? extends Annotation> annotation)
            throws IOException, URISyntaxException {
        String descriptor = "L" + annotation.getName().replace('.', '/') + ";";
        Path classesRoot = Paths.get(
                PluginManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        List<String> candidates;
        try (Stream<Path> files = Files.walk(classesRoot)) {
            candidates = files
                    .filter(f -> f.toString().endsWith(".class"))
                    .filter(f -> mentions(f, descriptor))
                    .map(f -> toClassName(classesRoot, f))
                    .filter(n -> n.startsWith(FRAMEWORK_PACKAGE))
                    .collect(Collectors.toList());
        }

        assertThat(candidates)
                .as("pre-filter control: scanning %s produced no candidate class files for %s at "
                        + "all, which means the scan is not reading what it thinks it is",
                        classesRoot, descriptor)
                .isNotEmpty();

        Set<String> annotated = new LinkedHashSet<>();
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
            // Annotation TYPES are excluded. Several framework annotations are composed from
            // these -- @UltiToolsModule is meta-annotated @Configuration, @Service is
            // meta-annotated @Component -- and a meta-annotation is composition, not an instance
            // that needs registering. Including them would put @UltiToolsModule in the
            // @Configuration census, which says nothing about wiring.
            if (!clazz.isAnnotation() && clazz.isAnnotationPresent(annotation)) {
                annotated.add(clazz.getName());
            }
        }

        if (!unloadable.isEmpty()) {
            fail("These candidate classes could not be inspected, so this test cannot say whether "
                    + "they carry @" + annotation.getSimpleName() + ". Skipping them silently would "
                    + "reopen the hole the test exists to close: " + unloadable);
        }
        return annotated;
    }

    private boolean mentions(Path classFile, String descriptor) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            return new String(bytes, StandardCharsets.ISO_8859_1).contains(descriptor);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + classFile, e);
        }
    }

    private String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace(java.io.File.separatorChar, '.')
                .replace('/', '.');
    }
}
