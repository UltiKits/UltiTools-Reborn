package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins {@link SoftDependencySignatureInvariant}'s D-08 rule: no class the container reflects over
 * may mention a soft-dependency plugin's types in a declared method/field/constructor signature.
 * Synthetic cases cover the discrimination logic; the final test runs the rule against every real
 * class in this module's own build output, with the package prefixes read from {@code plugin.yml}
 * rather than hard-coded, so a soft dependency added later is automatically covered once its
 * plugin-name-to-package mapping is added to {@link #KNOWN_PACKAGE_PREFIXES} — the one manual step
 * a new soft dependency needs, since a plugin's display name in {@code softdepend:} carries no
 * information about its Java package on its own.
 */
@DisplayName("SoftDependencySignatureInvariant tests")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SoftDependencySignatureInvariantTest {

    /**
     * Maps a {@code plugin.yml} {@code softdepend:} entry to the Java package prefix its API
     * ships under. This is the one place a future soft dependency needs a manual entry — nothing
     * about a plugin's display name implies its package, so this cannot be derived automatically.
     * Everything else about this guard (which classes to check, which prefixes are active) is
     * derived from {@code plugin.yml} and the real build output.
     */
    private static final Map<String, String> KNOWN_PACKAGE_PREFIXES;

    static {
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("Vault", "net.milkbowl.vault");
        prefixes.put("PlaceholderAPI", "me.clip.placeholderapi");
        KNOWN_PACKAGE_PREFIXES = Collections.unmodifiableMap(prefixes);
    }

    // === Synthetic fixtures for the discrimination tests below ===
    // java.util.Date stands in for "a soft-dependency type" here — the rule takes an arbitrary
    // prefix string, so any real, precisely-named JDK type works as a synthetic stand-in without
    // needing invented fully-qualified names the way OverBroadExclusionInvariantTest's pure
    // string-set inputs do (this rule takes real Class objects, not pre-derived strings).
    private static final String FIXTURE_PREFIX = "java.util.Date";

    @SuppressWarnings("unused")
    private static final class FixtureWithFlaggedField {
        private java.util.Date flagged;
    }

    @SuppressWarnings("unused")
    private static final class FixtureWithFlaggedMethodReturn {
        java.util.Date flaggedReturn() {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final class FixtureWithFlaggedConstructorParam {
        FixtureWithFlaggedConstructorParam(java.util.Date flagged) {
        }
    }

    @SuppressWarnings("unused")
    private static final class FixtureClean {
        private String clean;

        String cleanMethod(String input) {
            return input;
        }
    }

    @Test
    @DisplayName("a field whose type matches a forbidden prefix produces a violation")
    void flaggedFieldProducesViolation() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureWithFlaggedField.class), setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains(FixtureWithFlaggedField.class.getName()).contains("field");
    }

    @Test
    @DisplayName("a method whose return type matches a forbidden prefix produces a violation")
    void flaggedMethodReturnTypeProducesViolation() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureWithFlaggedMethodReturn.class), setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains(FixtureWithFlaggedMethodReturn.class.getName()).contains("method");
    }

    @Test
    @DisplayName("a constructor whose parameter type matches a forbidden prefix produces a violation")
    void flaggedConstructorParameterProducesViolation() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureWithFlaggedConstructorParam.class), setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains(FixtureWithFlaggedConstructorParam.class.getName()).contains("constructor");
    }

    @Test
    @DisplayName("a class with no forbidden-prefix member produces no violation")
    void cleanClassProducesNoViolation() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureClean.class), setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("an allowlisted class never violates, even when it carries a forbidden-prefix member")
    void allowlistedClassNeverViolates() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureWithFlaggedField.class, FixtureWithFlaggedMethodReturn.class),
                setOf(FIXTURE_PREFIX),
                setOf(FixtureWithFlaggedField.class.getName(), FixtureWithFlaggedMethodReturn.class.getName()));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("an empty prefix set produces no violations regardless of input classes")
    void emptyPrefixSetIsClean() {
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(FixtureWithFlaggedField.class), Collections.emptySet(), Collections.emptySet());

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("several violations come back in a deterministic order across repeated runs")
    void violationsAreDeterministicallyOrdered() {
        Set<Class<?>> classes = setOf(FixtureWithFlaggedField.class, FixtureWithFlaggedMethodReturn.class,
                FixtureWithFlaggedConstructorParam.class);

        List<String> firstRun = SoftDependencySignatureInvariant.evaluate(classes, setOf(FIXTURE_PREFIX), Collections.emptySet());
        List<String> secondRun = SoftDependencySignatureInvariant.evaluate(classes, setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(firstRun).hasSize(3).isEqualTo(secondRun);
    }

    @Test
    @DisplayName("the real check: every class in this module's real build output, against the real plugin.yml soft-dependency list, produces zero violations")
    void realBuildOutputAgainstRealPluginYmlIsClean() throws IOException {
        Set<String> prefixes = readRealSoftDependencyPrefixes();
        assertThat(prefixes)
                .as("plugin.yml's softdepend: list must map to at least one known package prefix, or "
                        + "this guard silently checks nothing")
                .isNotEmpty();

        Set<Class<?>> classes = loadFrameworkBuildOutputClasses();
        assertThat(classes)
                .as("target/classes must contain compiled framework classes by the time this test runs")
                .isNotEmpty();

        // D-08's named allowlist: EconomyUtils keeps a genuine Vault-typed public signature
        // (getEconomy(): Economy) for backward compatibility with the six modules that already
        // call it; PlayerJoinListener's PlaceholderApiBridge is the pre-existing lazily-loaded
        // bridge isolating me.clip.placeholderapi the same way VaultEconomyProvider isolates
        // net.milkbowl.vault (though, unlike EconomyUtils, neither actually needs the entry under
        // this signature-only check today -- both are named here anyway, matching 16-06-PLAN.md
        // and 16-CONTEXT.md's D-08 verbatim, so a future signature change to either is still
        // covered by an already-present, already-reasoned allowlist entry rather than a silent gap).
        Set<String> allowlist = setOf(
                "com.ultikits.ultitools.utils.EconomyUtils",
                "com.ultikits.ultitools.listeners.PlayerJoinListener$PlaceholderApiBridge");

        List<String> violations = SoftDependencySignatureInvariant.evaluate(classes, prefixes, allowlist);

        assertThat(violations).isEmpty();
    }

    /**
     * Reads {@code plugin.yml}'s {@code softdepend:} list and maps each entry to its known Java
     * package prefix via {@link #KNOWN_PACKAGE_PREFIXES}, skipping any name with no known mapping
     * rather than failing — an entry with no mapping is simply not covered by this guard yet,
     * which is the "one manual step" documented on {@link #KNOWN_PACKAGE_PREFIXES} itself.
     */
    private static Set<String> readRealSoftDependencyPrefixes() {
        File pluginYml = new File("src/main/resources/plugin.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(pluginYml);
        List<String> softdepend = config.getStringList("softdepend");

        Set<String> prefixes = new LinkedHashSet<>();
        for (String pluginName : softdepend) {
            String prefix = KNOWN_PACKAGE_PREFIXES.get(pluginName);
            if (prefix != null) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }

    /**
     * Walks {@code target/classes} for every {@code com/ultikits/ultitools/**.class} file and
     * loads it (linked but not initialized — {@code Class.forName(name, false, loader)} — so no
     * static initializer runs as a side effect of this scan) via this test's own classloader,
     * under which every current soft dependency (Vault, PlaceholderAPI) IS resolvable ({@code
     * provided} scope covers the test classpath) — unlike {@code HiddenVaultBootstrapTest}'s
     * deliberately hostile classloader, this scan is not trying to reproduce the crash, only to
     * enumerate the classes the crash's mechanism (eager signature resolution) could reach.
     */
    private static Set<Class<?>> loadFrameworkBuildOutputClasses() throws IOException {
        Path classesRoot = Paths.get("target", "classes");
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (!Files.isDirectory(classesRoot)) {
            return classes;
        }
        ClassLoader loader = SoftDependencySignatureInvariantTest.class.getClassLoader();
        List<String> failedToLoad = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            List<Path> classFiles = new ArrayList<>();
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(classFiles::add);
            for (Path path : classFiles) {
                String relative = classesRoot.relativize(path).toString();
                String withoutSuffix = relative.substring(0, relative.length() - ".class".length());
                String fqcn = withoutSuffix.replace(File.separatorChar, '.');
                if (!fqcn.startsWith("com.ultikits.ultitools")) {
                    continue;
                }
                try {
                    // fqcn is derived exclusively from walking this module's own target/classes
                    // build output, filtered to the com.ultikits.ultitools package -- there is no
                    // user- or network-controlled input on this path; this is a build-time
                    // structural guard test enumerating the framework's own compiled classes.
                    // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
                    classes.add(Class.forName(fqcn, false, loader));
                } catch (Throwable t) {
                    // A class this scan cannot even load is reported as a failure rather than
                    // silently dropped from coverage (mirrors SoftDependencySignatureInvariant's
                    // own safeDeclaredX helpers' reasoning) -- collected rather than thrown
                    // immediately so one bad class does not hide every other one behind it.
                    failedToLoad.add(fqcn + ": " + t);
                }
            }
        }
        assertThat(failedToLoad).as("every framework class must be loadable under the normal test classloader").isEmpty();
        return classes;
    }

    private static Set<Class<?>> setOf(Class<?>... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // === Fixtures and tests below: Codex P2, PR #463 ===
    // safeDeclaredFields()/safeDeclaredMethods()/safeDeclaredConstructors() used to swallow a
    // LinkageError or RuntimeException from the JVM's own eager signature resolution into a
    // silent empty array, with no record that enumeration failed at all -- exactly the
    // false-negative outcome this guard exists to prevent, since a class whose member enumeration
    // itself throws is #451's failure mode reproduced one layer down.
    //
    // Reproducing a genuine LinkageError from getDeclaredFields()/getDeclaredMethods()/
    // getDeclaredConstructors() needs a class whose signature references a type this test's own
    // classloader cannot resolve -- the same technique HiddenVaultBootstrapTest already uses and
    // has already proven works for getDeclaredMethods() against a real framework class (#451's own
    // crash chain): define a fresh copy of a target class under a classloader that refuses to load
    // net.milkbowl.vault.*, so resolving a Vault-typed field/return-type/parameter type throws
    // NoClassDefFoundError (a LinkageError) the instant this evaluator asks for that member
    // category. Kept at the top level (not @Nested) to match this file's existing flat structure,
    // and because @Nested test classes are non-static inner classes, which cannot themselves hold
    // static nested classes -- exactly the shape the fixtures and hiding classloader below need.

    private static final String HIDDEN_VAULT_PACKAGE_PREFIX = "net.milkbowl.vault";

    @SuppressWarnings("unused")
    private static final class FixtureWithHiddenTypeField {
        net.milkbowl.vault.economy.Economy hidden;
    }

    @SuppressWarnings("unused")
    private static final class FixtureWithHiddenTypeMethodReturn {
        net.milkbowl.vault.economy.Economy hiddenMethod() {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static final class FixtureWithHiddenTypeConstructorParam {
        FixtureWithHiddenTypeConstructorParam(net.milkbowl.vault.economy.Economy hidden) {
        }
    }

    @Test
    @DisplayName("getDeclaredFields() throwing a LinkageError is reported as a violation, not silently skipped (Codex P2, PR #463)")
    void fieldEnumerationFailure_isReportedAsViolation() {
        assertEnumerationFailureIsReported(FixtureWithHiddenTypeField.class.getName());
    }

    @Test
    @DisplayName("getDeclaredMethods() throwing a LinkageError is reported as a violation, not silently skipped (Codex P2, PR #463)")
    void methodEnumerationFailure_isReportedAsViolation() {
        assertEnumerationFailureIsReported(FixtureWithHiddenTypeMethodReturn.class.getName());
    }

    @Test
    @DisplayName("getDeclaredConstructors() throwing a LinkageError is reported as a violation, not silently skipped (Codex P2, PR #463)")
    void constructorEnumerationFailure_isReportedAsViolation() {
        assertEnumerationFailureIsReported(FixtureWithHiddenTypeConstructorParam.class.getName());
    }

    private static void assertEnumerationFailureIsReported(String targetClassName) {
        VaultHidingClassLoader hidingLoader = new VaultHidingClassLoader(
                SoftDependencySignatureInvariantTest.class.getClassLoader(), targetClassName);
        Class<?> hidden;
        try {
            hidden = Class.forName(targetClassName, false, hidingLoader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        assertThat(hidden.getClassLoader())
                .as("the fixture must actually be defined by the hiding loader, not answered by the "
                        + "parent's already-loaded copy -- otherwise this test proves nothing about "
                        + "the enumeration-failure scenario it claims to reproduce")
                .isSameAs(hidingLoader);

        // The prefix set below is deliberately unrelated to Vault -- the failure this test proves
        // happens at getDeclaredFields()/Methods()/Constructors() itself, before checkMember()
        // ever runs, so which prefixes are being searched for is irrelevant; a non-empty set is
        // only required so evaluate() does not short-circuit at line 66.
        List<String> violations = SoftDependencySignatureInvariant.evaluate(
                setOf(hidden), setOf(FIXTURE_PREFIX), Collections.emptySet());

        assertThat(violations)
                .as("a class this evaluator cannot even enumerate must be reported as its own "
                        + "violation, not silently treated as clean")
                .isNotEmpty();
        assertThat(violations.get(0)).contains(targetClassName);
    }

    /**
     * Hides {@code net.milkbowl.vault.*} from a single, freshly-defined copy of the named target
     * class, generalizing {@code HiddenVaultBootstrapTest}'s {@code VaultHidingClassLoader} (same
     * technique, parameterized on the target class name since this test needs it for three
     * different fixtures rather than one fixed class).
     */
    private static final class VaultHidingClassLoader extends ClassLoader {
        private final String targetClassName;

        VaultHidingClassLoader(ClassLoader parent, String targetClassName) {
            super(parent);
            this.targetClassName = targetClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(HIDDEN_VAULT_PACKAGE_PREFIX)) {
                throw new ClassNotFoundException(
                        "Vault is hidden by " + VaultHidingClassLoader.class.getSimpleName() + ": " + name);
            }
            if (targetClassName.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> alreadyDefined = findLoadedClass(name);
                    Class<?> defined = alreadyDefined != null ? alreadyDefined : findClass(name);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resourcePath = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = readAllBytes(in);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        // Java 8 bytecode target -- InputStream#readAllBytes is a Java 9+ API.
        private static byte[] readAllBytes(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }
}
