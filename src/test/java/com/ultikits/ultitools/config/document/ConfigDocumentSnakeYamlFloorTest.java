package com.ultikits.ultitools.config.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the public document writer with the SnakeYAML versions supplied by Paper 1.19.2-1.20.1.
 * Only SnakeYAML and compiled framework classes are child-loaded; JDK and Bukkit types stay shared.
 */
class ConfigDocumentSnakeYamlFloorTest {

    private static final String FIXTURE = "# header\n\n# section\na:\n"
            + "  b: 1 # inline\n  c: [x, y]\n# end\n";
    private static final String DOCUMENT_CLASS = ConfigDocument.class.getName();

    @ParameterizedTest(name = "writes without exception on SnakeYAML {0}")
    @ValueSource(strings = {"1.32", "1.33", "2.0"})
    void writesWithoutException(String version) throws Exception {
        byte[] rendered = assertDoesNotThrow(() -> renderIsolated(version),
                "parse, existing/new key, new section and render must work on SnakeYAML " + version);
        assertThat(new String(rendered, StandardCharsets.UTF_8))
                .contains("# header", "# section", "# inline", "c: [x, y]", "b: 2", "d: new", "# hello", "created: true");
    }

    @ParameterizedTest(name = "renders identical bytes on SnakeYAML {0}")
    @ValueSource(strings = {"1.32", "1.33", "2.0"})
    void rendersIdenticalBytes(String version) throws Exception {
        byte[] modern = renderModern();
        assertThat(modern).as("the normal-classloader comparison must have real output").isNotEmpty();
        byte[] floor = assertDoesNotThrow(() -> renderIsolated(version),
                "the floor writer must render before its bytes can be compared on SnakeYAML " + version);
        assertThat(floor).as("SnakeYAML " + version + " preserves the modern writer's UTF-8 bytes")
                .containsExactly(modern);
    }

    /** Also used by the pre-fix baseline capture; both versions run precisely these public operations. */
    static byte[] renderModern() throws Exception {
        return renderModified(ConfigDocument.class);
    }

    private static byte[] renderIsolated(String version) throws Exception {
        Path root = Paths.get(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path fixture = root.resolve("target/snakeyaml-floor/snakeyaml-" + version + ".jar");
        Path classes = root.resolve("target/classes");
        assertThat(fixture).as("required SnakeYAML fixture " + version + " must not be skipped").isRegularFile();
        assertThat(classes).as("compiled framework output must exist").isDirectory();
        URL[] urls = {fixture.toUri().toURL(), classes.toUri().toURL()};
        try (FloorClassLoader loader = new FloorClassLoader(urls, ConfigDocument.class.getClassLoader())) {
            Class<?> document = loader.loadClass(DOCUMENT_CLASS);
            Class<?> yaml = loader.loadClass("org.yaml.snakeyaml.Yaml");
            assertThat(document.getClassLoader()).as("framework classes must not escape to the parent").isSameAs(loader);
            assertThat(yaml.getClassLoader()).as("old SnakeYAML must not escape to the parent").isSameAs(loader);
            assertThat(Paths.get(yaml.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .as("the chosen version is really the copied fixture").isEqualTo(fixture);
            return renderModified(document);
        }
    }

    /** Public reflection crosses the intentionally isolated class boundary; no accessibility is altered. */
    private static byte[] renderModified(Class<?> documentType) throws Exception {
        Method set = documentType.getMethod("set", List.class, Object.class);
        Method comment = documentType.getMethod("setFrameworkComment", List.class, List.class);
        Method render = documentType.getMethod("render");
        Object document = documentType.getMethod("parse", String.class).invoke(null, FIXTURE);
        set.invoke(document, Arrays.asList("a", "b"), 2);
        set.invoke(document, Arrays.asList("a", "d"), "new");
        set.invoke(document, Arrays.asList("x", "y"), Arrays.asList("p", "q"));
        comment.invoke(document, Arrays.asList("a", "d"), Arrays.asList("hello"));
        String parsed = (String) render.invoke(document);
        Object empty = documentType.getMethod("empty").invoke(null);
        set.invoke(empty, Arrays.asList("created"), true);
        comment.invoke(empty, Arrays.asList("created"), Arrays.asList("new document"));
        return (parsed + "---\n" + render.invoke(empty)).getBytes(StandardCharsets.UTF_8);
    }

    private static final class FloorClassLoader extends URLClassLoader {

        FloorClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.yaml.snakeyaml.") && !name.startsWith("com.ultikits.ultitools.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
