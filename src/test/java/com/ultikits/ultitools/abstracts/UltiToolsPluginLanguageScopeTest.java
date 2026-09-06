package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * D-03/D-04 (framework issue #412): {@link UltiToolsPlugin#loadLanguageFromJar(String, String)}
 * must resolve a module's own {@code lang/} catalogue from the module's own {@link
 * java.security.CodeSource}, not from the shared {@code URLClassLoader} every internal module is
 * loaded through. Before the fix, {@code getResource(...)} on that shared loader let a module's
 * request for {@code lang/en.json} resolve into the core plugin's own {@code lang/en.json} --
 * silently, before the module's own {@code lang/en.yml} was ever tried.
 * <p>
 * The fixture builds two real jars and a two-level {@link URLClassLoader} chain that mirrors
 * {@code UltiTools.java}'s own {@code new URLClassLoader(getModuleUrls(), getClassLoader())}: a
 * core-like jar shipping only {@code lang/en.json}, and a module-like jar shipping only {@code
 * lang/en.yml} plus a real compiled class ({@link ModuleFixturePlugin}) so an instance loaded
 * through the module loader has that jar as its own {@code CodeSource}. The module loader is
 * child-first for its own classes (mirroring a real Bukkit/Paper per-plugin classloader) but
 * leaves {@code getResource} at the default parent-first {@link ClassLoader} behaviour -- that
 * asymmetry (child-first classes, parent-first resources) is the actual mechanism behind #412, so
 * reproducing it exactly (rather than a looser approximation) is what makes the RED run below
 * proof rather than assertion.
 * <p>
 * Follows the same mock-and-reflect idiom as {@code UltiToolsPluginLanguageFallbackTest}:
 * {@link Objenesis} bypasses every constructor (the same mechanism Mockito's inline mock maker
 * uses) so a real instance of the jar-loaded class can be obtained without ever calling {@code
 * UltiToolsPlugin}'s heavy constructors, and the private resolution methods under test are
 * invoked via reflection so their real bodies run.
 */
@DisplayName("UltiToolsPlugin loadLanguageFromJar module-scope resolution (D-03/D-04, issue #412)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of private resolution methods
class UltiToolsPluginLanguageScopeTest {

    // Declared before the nested fixture class below: PMD's FieldDeclarationsShouldBeAtStartOfClass
    // requires fields to precede any inner class.
    @TempDir
    File tempDir;

    /**
     * The compiled fixture class copied into the constructed jars. A {@code static} nested class
     * has no synthetic reference to the enclosing test class, so its {@code .class} file can be
     * lifted out of {@code target/test-classes} and repackaged into an isolated jar without also
     * needing to load the enclosing {@code UltiToolsPluginLanguageScopeTest} class.
     */
    static class ModuleFixturePlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    @BeforeEach
    void stubUltiToolsInstance() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "en");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            Mockito.lenient().when(ultiTools.getConfig()).thenReturn(config);
            Mockito.lenient().when(ultiTools.getLogger())
                    .thenReturn(Logger.getLogger(UltiToolsPluginLanguageScopeTest.class.getName()));
        });
    }

    /**
     * Child-first for its own classes, mirroring a real Bukkit/Paper per-plugin classloader: a
     * module's own class is defined by the module's own loader rather than delegated to the
     * parent, while resource lookup ({@link #getResource(String)}, inherited unmodified from
     * {@link ClassLoader}) stays parent-first. That asymmetry is the mechanism #412 exploits.
     */
    private static final class ChildFirstClassLoader extends URLClassLoader {
        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notShippedByThisJar) {
                        found = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }

    /**
     * Wraps the real test classloader for class loading (unchanged, so {@code UltiToolsPlugin},
     * {@code Language} etc. resolve normally) but hides every {@code lang/*} resource behind it.
     * <p>
     * Without this, {@code moduleLoader.getResource("lang/en.json")}'s parent-first walk reaches
     * all the way up to the real test/application classloader -- which has the framework's own,
     * genuine {@code src/main/resources/lang/en.json} on its classpath -- and resolves there
     * before ever reaching either constructed fixture jar. That would make the RED assertion below
     * fail for the wrong reason (an unrelated real resource, with no {@code probe.marker} key,
     * rather than the fixture's own {@code CORE}-tagged core-like jar), and the same leak would
     * let {@link #unreadableCodeSourceDegradesWithoutThrowing()} pass by accident before the fix
     * lands. Isolating {@code lang/*} here is what makes both directions of the falsification
     * measure the mechanism under test rather than an artifact of the ambient classpath.
     */
    private static final class LangResourceHidingClassLoader extends ClassLoader {
        private final ClassLoader real;

        LangResourceHidingClassLoader(ClassLoader real) {
            super(real);
            this.real = real;
        }

        @Override
        public URL getResource(String name) {
            return name.startsWith("lang/") ? null : real.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return name.startsWith("lang/") ? Collections.emptyEnumeration() : real.getResources(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return name.startsWith("lang/") ? null : real.getResourceAsStream(name);
        }
    }

    private static byte[] compiledFixtureClassBytes() throws IOException {
        String resourceName = ModuleFixturePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = UltiToolsPluginLanguageScopeTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IOException("Compiled fixture class not found on the test classpath: " + resourceName);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    private static File buildJar(File dir, String fileName, Map<String, byte[]> entries) throws IOException {
        File jarFile = new File(dir, fileName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile.toPath()))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    private static Object newModuleFixtureInstance(ClassLoader moduleLoader) throws Exception {
        Class<?> fixtureClass = Class.forName(ModuleFixturePlugin.class.getName(), true, moduleLoader);
        Objenesis objenesis = new ObjenesisStd();
        return objenesis.newInstance(fixtureClass);
    }

    private static Language invokeCreateLanguageFromPath(Object plugin, String folderPath) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("createLanguageFromPath", String.class);
        method.setAccessible(true);
        try {
            return (Language) method.invoke(plugin, folderPath);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object invokeLoadLanguageFromJar(Object plugin, String code, String extension) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("loadLanguageFromJar", String.class, String.class);
        method.setAccessible(true);
        try {
            return method.invoke(plugin, code, extension);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Nested
    @DisplayName("A module resolves its own lang/ catalogue through its own CodeSource, not the shared classloader")
    class ModuleScopedResourceResolution {

        private URLClassLoader coreLoader;
        private ChildFirstClassLoader moduleLoader;
        private Object modulePlugin;

        /**
         * Builds the two-jar fixture shared by the first two tests: a core-like jar shipping
         * {@code lang/en.json} ({@code probe.marker=CORE}), a module-like jar shipping only
         * {@code lang/en.yml} ({@code probe.marker=MODULE}) plus the compiled fixture class, and
         * a module loader whose parent is a loader over the core jar -- exactly the shape {@code
         * UltiTools.java:264} builds at runtime.
         */
        @BeforeEach
        void buildTwoJarFixture() throws Exception {
            File coreJar = buildJar(tempDir, "core.jar",
                    Collections.singletonMap("lang/en.json", "{\"probe.marker\":\"CORE\"}".getBytes(StandardCharsets.UTF_8)));

            Map<String, byte[]> moduleEntries = new LinkedHashMap<>();
            moduleEntries.put("lang/en.yml", "probe:\n  marker: MODULE\n".getBytes(StandardCharsets.UTF_8));
            moduleEntries.put(ModuleFixturePlugin.class.getName().replace('.', '/') + ".class", compiledFixtureClassBytes());
            File moduleJar = buildJar(tempDir, "module.jar", moduleEntries);

            ClassLoader isolatingBase = new LangResourceHidingClassLoader(UltiToolsPluginLanguageScopeTest.class.getClassLoader());
            coreLoader = new URLClassLoader(new URL[]{coreJar.toURI().toURL()}, isolatingBase);
            moduleLoader = new ChildFirstClassLoader(new URL[]{moduleJar.toURI().toURL()}, coreLoader);
            modulePlugin = newModuleFixtureInstance(moduleLoader);
        }

        @AfterEach
        void closeLoaders() throws IOException {
            moduleLoader.close();
            coreLoader.close();
        }

        @Test
        @DisplayName("Module's lang/en.yml wins over the core's lang/en.json -- reproduces and proves the fix for #412")
        void moduleYamlCatalogueWinsOverCoreJsonCatalogue() throws Throwable {
            Language language = invokeCreateLanguageFromPath(modulePlugin, new File(tempDir, "no-such-resource-folder").getAbsolutePath());

            assertThat(language.getLocalizedText("probe.marker")).isEqualTo("MODULE");
        }

        @Test
        @DisplayName("An extension the module does not ship returns null, so the extension loop can continue")
        void absentEntryReturnsNullSoTheExtensionLoopContinues() throws Throwable {
            // The module fixture ships only lang/en.yml -- .yaml is a distinct entry name it never
            // shipped, in either the shared-classloader (before) or CodeSource (after) resolver.
            Object result = invokeLoadLanguageFromJar(modulePlugin, "en", ".yaml");

            assertThat(result).isNull();
        }
    }

    @Test
    @DisplayName("A CodeSource pointing at a directory (not a jar) degrades without throwing")
    void unreadableCodeSourceDegradesWithoutThrowing() throws Throwable {
        File explodedRoot = new File(tempDir, "exploded-module");
        File classFile = new File(explodedRoot, ModuleFixturePlugin.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParentFile().toPath());
        Files.write(classFile.toPath(), compiledFixtureClassBytes());

        ClassLoader isolatingBase = new LangResourceHidingClassLoader(UltiToolsPluginLanguageScopeTest.class.getClassLoader());
        ChildFirstClassLoader directoryLoader = new ChildFirstClassLoader(
                new URL[]{explodedRoot.toURI().toURL()}, isolatingBase);
        try {
            Object plugin = newModuleFixtureInstance(directoryLoader);

            AtomicReference<Object> resultHolder = new AtomicReference<>();
            assertThatCode(() -> resultHolder.set(invokeLoadLanguageFromJar(plugin, "en", ".json")))
                    .doesNotThrowAnyException();

            // CodeSource resolves to a directory, so opening it as a JarFile fails with an
            // IOException -- caught and degraded to an empty dictionary, matching saveResources()'s
            // own logged-and-degraded convention rather than propagating the failure.
            Language result = (Language) resultHolder.get();
            assertThat(result).isNotNull();
            assertThat(result.getLocalizedText("probe.marker")).isEqualTo("probe.marker");
        } finally {
            directoryLoader.close();
        }
    }
}
