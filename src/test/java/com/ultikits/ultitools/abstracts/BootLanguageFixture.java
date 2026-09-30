package com.ultikits.ultitools.abstracts;

import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.mockito.Mockito;

import com.ultikits.testfixtures.bootlanguage.BootFixturePlugin;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * A real module jar plus a real on-disk resource folder, for tests that must run a module's own
 * constructor end to end (resource extraction, language resolution, provenance) rather than
 * invoking the resolution methods reflectively on an Objenesis instance.
 * <p>
 * The module class ({@link BootFixturePlugin}, alone in its own package) is copied into a
 * temporary jar together with the
 * {@code lang/*} entries a test declares, and loaded child-first from that jar, so its {@code
 * CodeSource} -- which {@code saveResources()}, {@code supported()} and the jar-side language
 * lookup all read -- is that jar, exactly as on a server. It is constructed through {@link
 * UltiToolsPlugin}'s connector constructor, which takes the resource folder as an argument instead
 * of deriving it from {@code UltiTools.getInstance().getDataFolder()}.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective construction of the jar-loaded fixture class
public final class BootLanguageFixture implements Closeable {

    /** Module name every fixture instance reports; the resource folder is named after it. */
    public static final String MODULE = BootFixturePlugin.MODULE;

    /** Main-class name every fixture instance reports; the duplicate-version gate compares it. */
    public static final String MAIN_CLASS = BootFixturePlugin.MAIN_CLASS;

    private final File root;
    private final File resourceFolder;
    private final Map<String, byte[]> jarEntries = new LinkedHashMap<>();
    private final Logger logger = mock(Logger.class);
    private final ConfigManager configManager = mock(ConfigManager.class);
    private URLClassLoader loader;

    /**
     * Child-first for the fixture's own class, parent-first for everything else, so the fixture
     * class is defined by this loader (and therefore reports the fixture jar as its CodeSource)
     * while {@code UltiToolsPlugin} and the rest of the framework resolve normally.
     */
    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notInFixtureJar) {
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

    private BootLanguageFixture(File tempDir) {
        this.root = new File(tempDir, "boot-fixture-" + System.nanoTime());
        this.resourceFolder = new File(root, "pluginConfig" + File.separator + MODULE);
    }

    /**
     * Creates a fixture under {@code tempDir} and installs a mocked {@code UltiTools} instance whose
     * configured language is {@code en}, whose logger is {@link #logger()} and whose config manager
     * is a mock.
     */
    public static BootLanguageFixture create(File tempDir) throws IOException {
        return create(tempDir, null);
    }

    /**
     * As {@link #create(File)}, with {@code extraStubbing} applied to the mocked {@code UltiTools}
     * before it is published (see {@link TestHelper#mockUltiToolsInstance(Consumer)}).
     */
    public static BootLanguageFixture create(File tempDir, Consumer<UltiTools> extraStubbing) throws IOException {
        BootLanguageFixture fixture = new BootLanguageFixture(tempDir);
        Files.createDirectories(fixture.resourceFolder.toPath());
        YamlConfiguration config = new YamlConfiguration();
        config.set("language", "en");
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            Mockito.lenient().when(ultiTools.getConfig()).thenReturn(config);
            Mockito.lenient().when(ultiTools.getLogger()).thenReturn(fixture.logger);
            Mockito.lenient().when(ultiTools.getConfigManager()).thenReturn(fixture.configManager);
            if (extraStubbing != null) {
                extraStubbing.accept(ultiTools);
            }
        });
        return fixture;
    }

    /** Adds {@code path} with {@code content} to the module jar. */
    public BootLanguageFixture jarEntry(String path, String content) {
        jarEntries.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Writes {@code content} to {@code path} under the resource folder, as an earlier jar would have. */
    public BootLanguageFixture onDisk(String path, String content) throws IOException {
        File file = disk(path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Records {@code path}'s current on-disk hash in the provenance record. */
    public BootLanguageFixture recordCurrent(String path) {
        ResourceHashSidecar.record(resourceFolder, path, ResourceHashSidecar.sha256(disk(path)));
        return this;
    }

    /** The resource folder, {@code <root>/pluginConfig/BootModule}. */
    public File resourceFolder() {
        return resourceFolder;
    }

    /** The on-disk file at {@code path} under the resource folder (it may not exist). */
    public File disk(String path) {
        return new File(resourceFolder, path.replace('/', File.separatorChar));
    }

    /** The provenance record file under the resource folder (it may not exist). */
    public File provenanceRecord() {
        return new File(resourceFolder, ".ultitools-resource-hashes.json");
    }

    /** The mocked framework logger every module logger writes through. */
    public Logger logger() {
        return logger;
    }

    /** The mocked config manager. */
    public ConfigManager configManager() {
        return configManager;
    }

    /**
     * Builds the jar (first call only) and constructs one {@link BootFixturePlugin} from it with
     * the given version -- the module's own constructor runs in full.
     */
    public UltiToolsPlugin construct(String version) throws Exception {
        if (loader == null) {
            Map<String, byte[]> entries = new LinkedHashMap<>(jarEntries);
            entries.put(classEntryName(), compiledFixtureClassBytes());
            File jar = new File(root, "module-" + System.nanoTime() + ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    out.putNextEntry(new JarEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
            loader = new ChildFirstLoader(new URL[]{jar.toURI().toURL()},
                    BootLanguageFixture.class.getClassLoader());
        }
        // The class name is a compile-time constant, never attacker-controllable; loading through
        // the fixture loader is what makes the fixture jar the class's CodeSource.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> pluginClass = Class.forName(BootFixturePlugin.class.getName(), true, loader);
        Constructor<?> constructor = pluginClass.getConstructor(String.class, String.class);
        try {
            return (UltiToolsPlugin) constructor.newInstance(version, resourceFolder.getAbsolutePath());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    /** Reads {@code file}'s bytes, or an empty array when it does not exist. */
    public static byte[] bytesOf(File file) throws IOException {
        return file.exists() ? Files.readAllBytes(file.toPath()) : new byte[0];
    }

    private static String classEntryName() {
        return BootFixturePlugin.class.getName().replace('.', '/') + ".class";
    }

    private static byte[] compiledFixtureClassBytes() throws IOException {
        try (InputStream in = BootLanguageFixture.class.getClassLoader().getResourceAsStream(classEntryName())) {
            if (in == null) {
                throw new IOException("Compiled fixture class not found on the test classpath: " + classEntryName());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public void close() throws IOException {
        if (loader != null) {
            loader.close();
            loader = null;
        }
    }
}
