package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

/**
 * D-05/D-06/D-07 (#441): {@link ResourceHashSidecar} records the SHA-256 of every resource file
 * {@link UltiToolsPlugin#saveResources()} extracts, so a later boot can tell "the operator edited
 * this" from "an old jar extracted this and nobody has touched it since".
 * <p>
 * The hash is deliberately computed over raw bytes, never decoded text -- an encoding-preserving
 * rewrite of the same logical content must read as a difference, and a byte-identical copy must
 * not.
 */
@DisplayName("ResourceHashSidecar provenance recording (#441 D-05/D-06/D-07)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // reflective invocation of a private extraction method
class ResourceHashSidecarTest {

    @TempDir
    File tempDir;

    /**
     * Standalone fixture module used only to drive {@code saveResources()} through reflection --
     * declared here (package {@code com.ultikits.ultitools.utils}) rather than reused from an
     * {@code abstracts} package fixture, since that package's own fixtures are package-private and
     * not visible here.
     */
    static class SidecarFixturePlugin extends UltiToolsPlugin {
        @Override
        public boolean registerSelf() {
            return true;
        }
    }

    /**
     * Child-first for the fixture's own class only, mirroring a real Bukkit/Paper per-plugin
     * classloader: without this, {@code Class.forName(name, true, loader)} would resolve {@code
     * SidecarFixturePlugin} from the parent (test) classloader -- where it is already loaded as
     * part of running this very test class -- instead of from the fixture jar, defeating the whole
     * point of giving the instance the jar's own {@link java.security.CodeSource}. Resource lookup
     * stays parent-first (inherited, unmodified). Same idiom as {@code
     * UltiToolsPluginLanguageScopeTest.ChildFirstClassLoader} / {@code
     * UltiToolsPluginLanguagePerKeyFallbackTest.ChildFirstClassLoader}, duplicated here because
     * those are package-private in a different package.
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

    @Test
    @DisplayName("sha256(File) returns the known digest for known bytes")
    void sha256ReturnsKnownDigestForKnownBytes() throws Exception {
        File file = new File(tempDir, "known.txt");
        Files.write(file.toPath(), "hello world".getBytes(StandardCharsets.UTF_8));

        String expected = referenceSha256("hello world".getBytes(StandardCharsets.UTF_8));

        assertThat(ResourceHashSidecar.sha256(file)).isEqualTo(expected);
    }

    @Test
    @DisplayName("same text saved with a different character encoding produces a different digest")
    void sha256DiffersAcrossEncodingsOfSameText() throws Exception {
        String text = "café - stale template placeholder";
        File utf8File = new File(tempDir, "utf8.txt");
        File utf16File = new File(tempDir, "utf16.txt");
        Files.write(utf8File.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.write(utf16File.toPath(), text.getBytes(StandardCharsets.UTF_16));

        assertThat(ResourceHashSidecar.sha256(utf8File)).isNotEqualTo(ResourceHashSidecar.sha256(utf16File));
    }

    @Test
    @DisplayName("a byte-identical copy produces the same digest")
    void sha256MatchesForByteIdenticalCopy() throws Exception {
        byte[] bytes = "{\"greeting\":\"hi\"}".getBytes(StandardCharsets.UTF_8);
        File original = new File(tempDir, "original.json");
        File copy = new File(tempDir, "copy.json");
        Files.write(original.toPath(), bytes);
        Files.write(copy.toPath(), bytes);

        assertThat(ResourceHashSidecar.sha256(original)).isEqualTo(ResourceHashSidecar.sha256(copy));
    }

    @Test
    @DisplayName("readRecordedHash on a resource with no record returns an absent value, not an exception")
    void readRecordedHashReturnsEmptyWhenNoSidecarExists() {
        Optional<String> recorded = ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json");

        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("record(...) followed by readRecordedHash(...) round-trips the digest for the same path")
    void recordThenReadRecordedHashRoundTripsForSamePath() {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "abc123");

        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("abc123");
    }

    @Test
    @DisplayName("two different resource paths do not collide")
    void recordDoesNotCollideAcrossDifferentResourcePaths() {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en");
        ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh");

        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/zh.json")).contains("hash-zh");
    }

    @Test
    @DisplayName("recording a second resource leaves the first entry's digest unchanged")
    void recordingSecondResourceLeavesFirstEntryDigestUnchanged() {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en");

        ResourceHashSidecar.record(tempDir, "res/icon.png", "hash-icon");

        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "res/icon.png")).contains("hash-icon");
    }

    @Test
    @DisplayName("a malformed sidecar file reads as \"no record\" rather than throwing")
    void malformedSidecarFileReadsAsNoRecordRatherThanThrowing() throws IOException {
        File sidecar = new File(tempDir, ".ultitools-resource-hashes.json");
        Files.write(sidecar.toPath(), "{ this is not valid json".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json"))
                .doesNotThrowAnyException();
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).isEmpty();
    }

    @Test
    @DisplayName("saveResources() records a hash for every file it extracts under all three prefixes, "
            + "and none for a file it skipped")
    void saveResourcesRecordsHashForEveryExtractedFileAcrossAllThreePrefixesAndSkipsAlreadyPresentFiles()
            throws Throwable {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("lang/en.json", "{\"greeting\":\"Hi\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("res/icon.png", "fake-png-bytes".getBytes(StandardCharsets.UTF_8));
        entries.put("config/default.yml", "enabled: true\n".getBytes(StandardCharsets.UTF_8));
        File jar = buildFixtureJar("fixture-full.jar", entries);

        File resourceFolder = new File(tempDir, "resource-folder-1");
        Files.createDirectories(resourceFolder.toPath());
        // Pre-existing file under one of the three prefixes: saveResources() must skip it (leave
        // its bytes untouched) and must not write a record for it.
        File preExisting = new File(resourceFolder, "res" + File.separator + "icon.png");
        Files.createDirectories(preExisting.getParentFile().toPath());
        byte[] preExistingBytes = "operator-customised-icon".getBytes(StandardCharsets.UTF_8);
        Files.write(preExisting.toPath(), preExistingBytes);

        try (ChildFirstClassLoader loader = new ChildFirstClassLoader(new URL[]{jar.toURI().toURL()},
                ResourceHashSidecarTest.class.getClassLoader())) {
            Object plugin = newFixtureInstance(loader);
            setResourceFolderPath(plugin, resourceFolder.getAbsolutePath());

            invokeSaveResources(plugin);

            File extractedLang = new File(resourceFolder, "lang" + File.separator + "en.json");
            File extractedConfig = new File(resourceFolder, "config" + File.separator + "default.yml");
            assertThat(extractedLang).isFile();
            assertThat(extractedConfig).isFile();

            assertThat(ResourceHashSidecar.readRecordedHash(resourceFolder, "lang/en.json"))
                    .contains(ResourceHashSidecar.sha256(extractedLang));
            assertThat(ResourceHashSidecar.readRecordedHash(resourceFolder, "config/default.yml"))
                    .contains(ResourceHashSidecar.sha256(extractedConfig));

            // Skipped: bytes untouched, no record written.
            assertThat(Files.readAllBytes(preExisting.toPath())).isEqualTo(preExistingBytes);
            assertThat(ResourceHashSidecar.readRecordedHash(resourceFolder, "res/icon.png")).isEmpty();
        }
    }

    @Test
    @DisplayName("an empty extracted file gets a record whose digest is the digest of zero bytes, "
            + "not an absent record")
    void saveResourcesRecordsZeroByteDigestForEmptyExtractedFileRatherThanNoRecord() throws Throwable {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("res/empty.txt", new byte[0]);
        File jar = buildFixtureJar("fixture-empty.jar", entries);

        File resourceFolder = new File(tempDir, "resource-folder-2");
        Files.createDirectories(resourceFolder.toPath());

        try (ChildFirstClassLoader loader = new ChildFirstClassLoader(new URL[]{jar.toURI().toURL()},
                ResourceHashSidecarTest.class.getClassLoader())) {
            Object plugin = newFixtureInstance(loader);
            setResourceFolderPath(plugin, resourceFolder.getAbsolutePath());

            invokeSaveResources(plugin);

            File extracted = new File(resourceFolder, "res" + File.separator + "empty.txt");
            assertThat(extracted).isFile();
            assertThat(Files.size(extracted.toPath())).isZero();

            String zeroByteDigest = referenceSha256(new byte[0]);
            assertThat(ResourceHashSidecar.readRecordedHash(resourceFolder, "res/empty.txt"))
                    .contains(zeroByteDigest);
        }
    }

    private static String referenceSha256(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] compiledFixtureClassBytes() throws IOException {
        String resourceName = SidecarFixturePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ResourceHashSidecarTest.class.getClassLoader().getResourceAsStream(resourceName)) {
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

    private File buildFixtureJar(String jarName, Map<String, byte[]> entries) throws IOException {
        File jar = new File(tempDir, jarName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            String classEntryName = SidecarFixturePlugin.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(classEntryName));
            output.write(compiledFixtureClassBytes());
            output.closeEntry();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static Object newFixtureInstance(ClassLoader loader) throws Exception {
        // The class name is a compile-time constant, never attacker-controllable; loading through
        // the given loader is required to give the instance that loader's own CodeSource.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        Class<?> fixtureClass = Class.forName(SidecarFixturePlugin.class.getName(), true, loader);
        Objenesis objenesis = new ObjenesisStd();
        return objenesis.newInstance(fixtureClass);
    }

    private static void setResourceFolderPath(Object plugin, String path) throws Exception {
        Field field = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        field.setAccessible(true);
        field.set(plugin, path);
    }

    private static void invokeSaveResources(Object plugin) throws Throwable {
        Method method = UltiToolsPlugin.class.getDeclaredMethod("saveResources");
        method.setAccessible(true);
        try {
            method.invoke(plugin);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
