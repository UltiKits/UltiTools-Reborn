package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * Real-machine finding, phase 13, PR #418 (Laojun UAT 2026-09-06, rows UltiChat #9 / F-C1 and
 * UltiWorlds #13 / F-W2): on an upgraded server, a module jar ships a language key that the copy
 * of that language file already sitting in the module's data folder -- extracted by an older jar
 * -- does not contain. Before this fix, {@link UltiToolsPlugin#createLanguageFromPath(String)}
 * used whichever {@link Language} it found first (the on-disk file, if present at all) exclusively,
 * so a key missing from disk rendered as its own raw key even though the newly-shipped jar had a
 * translation for it.
 * <p>
 * Mirrors {@code UltiToolsPluginLanguageScopeTest}'s directory-{@link java.security.CodeSource}
 * fixture technique (13-REVIEW CR-01 / issue #412): an exploded module directory stands in for the
 * module's own jar-bundled {@code lang/} catalogue, while a separate directory plays the role of
 * the module's on-disk {@code resourceFolderPath}. The two are deliberately different key sets so
 * the assertions below distinguish "disk wins for a key it has", "falls back to the jar for a key
 * it lacks", and "falls back to the raw key when neither has it" -- exactly the three rows the task
 * requires.
 */
@DisplayName("UltiToolsPlugin createLanguageFromPath per-key disk->jar->key fallback (#418)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of a private resolution method
class UltiToolsPluginLanguagePerKeyFallbackTest {

    // Declared before the nested loader classes below: PMD's FieldDeclarationsShouldBeAtStartOfClass
    // requires fields to precede any inner class.
    @TempDir
    File tempDir;

    private ChildFirstClassLoader directoryLoader;

    @BeforeEach
    void stubUltiToolsInstance() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "en");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            Mockito.lenient().when(ultiTools.getConfig()).thenReturn(config);
            Mockito.lenient().when(ultiTools.getLogger())
                    .thenReturn(Logger.getLogger(UltiToolsPluginLanguagePerKeyFallbackTest.class.getName()));
        });
    }

    /**
     * Same asymmetry as {@code UltiToolsPluginLanguageScopeTest}'s {@code ChildFirstClassLoader}:
     * child-first for the module's own classes, parent-first (default) for resources.
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
     * Hides every {@code lang/*} resource from the real test/application classloader, so a
     * {@code getResource("lang/en.json")} walk cannot accidentally reach the framework's own
     * genuine {@code src/main/resources/lang/en.json} instead of this fixture's directory.
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
        String resourceName = UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream in = UltiToolsPluginLanguagePerKeyFallbackTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
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

    private static Object newModuleFixtureInstance(ClassLoader moduleLoader) throws Exception {
        // The class name is a compile-time constant, never attacker-controllable; loading through
        // the given moduleLoader is required to give the instance that loader's own CodeSource.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> fixtureClass = Class.forName(
                UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName(), true, moduleLoader);
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

    @AfterEach
    void closeLoader() throws IOException {
        if (directoryLoader != null) {
            directoryLoader.close();
        }
    }

    @Test
    @DisplayName("disk lacks a key the jar has -> the jar's translation is used, not the raw key")
    void diskMissingKeyFallsBackToJarTranslation() throws Throwable {
        Language language = buildMergedLanguage();

        assertThat(language.getLocalizedText("onlyInJar")).isEqualTo("&cJar-only translation.");
    }

    @Test
    @DisplayName("disk has the key with a different value -> the disk value wins")
    void diskValuePresentInBothWins() throws Throwable {
        Language language = buildMergedLanguage();

        assertThat(language.getLocalizedText("known")).isEqualTo("&cDisk-customised translation.");
    }

    @Test
    @DisplayName("neither disk nor jar has the key -> falls back to the raw key, unchanged")
    void keyInNeitherFallsBackToTheRawKey() throws Throwable {
        Language language = buildMergedLanguage();

        assertThat(language.getLocalizedText("neitherHasThis")).isEqualTo("neitherHasThis");
    }

    /**
     * Builds one exploded module directory (stands in for the module's own jar-bundled {@code
     * lang/en.json}) and one separate on-disk resource folder (stands in for {@code
     * resourceFolderPath/lang/en.json}, as an older jar's {@code saveResources()} would have
     * extracted it), then invokes the real, unmodified {@code createLanguageFromPath} through
     * reflection so its actual body runs.
     */
    private Language buildMergedLanguage() throws Throwable {
        File explodedRoot = new File(tempDir, "exploded-module-jar-catalogue");
        File classFile = new File(explodedRoot,
                UltiToolsPluginLanguageScopeTest.ModuleFixturePlugin.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParentFile().toPath());
        Files.write(classFile.toPath(), compiledFixtureClassBytes());

        File jarLangFile = new File(explodedRoot, "lang" + File.separator + "en.json");
        Files.createDirectories(jarLangFile.getParentFile().toPath());
        Files.write(jarLangFile.toPath(),
                ("{\"known\":\"&cJar-shipped translation.\","
                        + "\"onlyInJar\":\"&cJar-only translation.\"}").getBytes(StandardCharsets.UTF_8));

        File diskResourceFolder = new File(tempDir, "disk-resource-folder");
        File diskLangFile = new File(diskResourceFolder, "lang" + File.separator + "en.json");
        Files.createDirectories(diskLangFile.getParentFile().toPath());
        // Deliberately missing "onlyInJar" -- the exact shape of an older jar's extraction that
        // predates the new key, and deliberately a different value for "known" than the jar's, so
        // "disk wins for a key it has" is unambiguous.
        Files.write(diskLangFile.toPath(),
                "{\"known\":\"&cDisk-customised translation.\"}".getBytes(StandardCharsets.UTF_8));

        ClassLoader isolatingBase = new LangResourceHidingClassLoader(
                UltiToolsPluginLanguagePerKeyFallbackTest.class.getClassLoader());
        directoryLoader = new ChildFirstClassLoader(new URL[]{explodedRoot.toURI().toURL()}, isolatingBase);
        Object plugin = newModuleFixtureInstance(directoryLoader);

        return invokeCreateLanguageFromPath(plugin, diskResourceFolder.getAbsolutePath());
    }
}
