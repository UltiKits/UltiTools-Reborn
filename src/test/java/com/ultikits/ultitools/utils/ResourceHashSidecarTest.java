package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Assumptions;
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
    @DisplayName("sha256(File) on an unreadable file throws UncheckedIOException specifically -- "
            + "NOT a checked IOException (Codex round 5, P1). UltiToolsPlugin#saveResources()'s own "
            + "catch clause used to catch only IOException, so this exact type let a "
            + "post-extraction hash failure escape and abort module construction even though the "
            + "resource had already extracted successfully; its catch clause now also catches "
            + "UncheckedIOException. A full write-then-unreadable reproduction through "
            + "saveResources() itself would need native umask/ACL control JVM file APIs do not "
            + "expose, so this test pins the exact type gap at its source instead.")
    void sha256ThrowsUncheckedIOExceptionSpecificallyForAnUnreadableFile() throws IOException {
        File file = new File(tempDir, "unreadable.json");
        Files.write(file.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(file.setReadable(false)).isTrue();
        try {
            assertThatThrownBy(() -> ResourceHashSidecar.sha256(file))
                    .isInstanceOf(UncheckedIOException.class)
                    // A catch (IOException e) clause does NOT catch UncheckedIOException --
                    // it is a RuntimeException wrapping one, not a subtype of IOException itself.
                    .isNotInstanceOf(IOException.class);
        } finally {
            file.setReadable(true);
        }
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
    @DisplayName("a transient read failure on an EXISTING sidecar aborts the write instead of "
            + "wiping every previously recorded hash (Codex round 9, P2, discussion on "
            + "ResourceHashSidecar.java:155)")
    void transientReadFailureOnExistingSidecarAbortsWriteRatherThanWipingPriorEntries() throws IOException {
        // Before this fix: readAll() degrades ANY read failure to an empty map (correct for
        // readRecordedHash's own contract -- T-16-04-03), but record()/recordAll() then WROTE
        // that empty-derived map straight back, permanently discarding every previously recorded
        // hash over what may be a purely transient failure (e.g. a momentary network-filesystem
        // hiccup). The atomic move itself only needs the containing DIRECTORY's write
        // permission, never the target file's own -- so the replacement succeeds even while the
        // sidecar file itself is unreadable, silently completing the data loss.
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        byte[] beforeBytes = Files.readAllBytes(sidecarFile.toPath());

        assertThat(sidecarFile.setReadable(false)).isTrue();
        try {
            ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh-v1");
        } finally {
            assertThat(sidecarFile.setReadable(true)).isTrue();
        }

        // The sidecar must be byte-for-byte untouched -- not overwritten with a partial map.
        assertThat(Files.readAllBytes(sidecarFile.toPath())).isEqualTo(beforeBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-v1");
    }

    @Test
    @DisplayName("recordAll(...) has the identical abort-on-existing-read-failure behaviour as "
            + "record(...) (Codex round 9, P2)")
    void recordAllAbortsOnTransientReadFailureOfExistingSidecarToo() throws IOException {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        byte[] beforeBytes = Files.readAllBytes(sidecarFile.toPath());

        Map<String, String> newEntries = new LinkedHashMap<>();
        newEntries.put("lang/zh.json", "hash-zh-v1");
        assertThat(sidecarFile.setReadable(false)).isTrue();
        try {
            ResourceHashSidecar.recordAll(tempDir, newEntries);
        } finally {
            assertThat(sidecarFile.setReadable(true)).isTrue();
        }

        assertThat(Files.readAllBytes(sidecarFile.toPath())).isEqualTo(beforeBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-v1");
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
    @DisplayName("recordAll(...) persists every entry in one call, alongside anything already recorded "
            + "(Codex round 4, P2: batch instead of one read-modify-write cycle per entry)")
    void recordAllPersistsEveryEntryAlongsideAlreadyRecordedOnes() {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-existing");

        Map<String, String> newEntries = new LinkedHashMap<>();
        newEntries.put("res/icon.png", "hash-icon");
        newEntries.put("config/default.yml", "hash-config");
        ResourceHashSidecar.recordAll(tempDir, newEntries);

        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-existing");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "res/icon.png")).contains("hash-icon");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "config/default.yml")).contains("hash-config");
    }

    @Test
    @DisplayName("recordAll(...) with an empty map never touches the sidecar file at all")
    void recordAllWithEmptyMapNeverTouchesSidecarFile() {
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        assertThat(sidecarFile).doesNotExist();

        ResourceHashSidecar.recordAll(tempDir, Collections.emptyMap());

        assertThat(sidecarFile).doesNotExist();
    }

    @Test
    @DisplayName("updating an existing sidecar preserves its POSIX permissions instead of replacing "
            + "them with createTempFile's process-owned defaults (round 3, Codex finding on "
            + "ResourceHashSidecar.java:285, thread PRRT_kwDOIcF9Es6i00gb, P2)")
    void sidecarUpdatePreservesExistingPosixPermissions() throws IOException {
        // Mirrors UltiToolsPluginLanguageFallbackTest#refreshPreservesOriginalPosixPermissions --
        // same finding shape, same fix (PosixAttributePreserver), now proven on the sidecar's own
        // atomic-replace path rather than the language-file one.
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        PosixFileAttributeView view = Files.getFileAttributeView(sidecarFile.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping this permission-"
                        + "preservation test.");

        // Deliberately distinctive: File.createTempFile's default (commonly rw------- / 0600)
        // must NOT survive the update -- this permission set adds group-read, which a naive "just
        // create a new temp file" implementation would silently drop.
        Set<PosixFilePermission> distinctivePermissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(sidecarFile.toPath(), distinctivePermissions);

        ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh-v1");

        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-v1");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/zh.json")).contains("hash-zh-v1");
        assertThat(Files.getPosixFilePermissions(sidecarFile.toPath())).isEqualTo(distinctivePermissions);
    }

    @Test
    @DisplayName("updating an existing sidecar preserves its owner and group, not just its "
            + "permission bits (round 3, Codex finding on ResourceHashSidecar.java:285, thread "
            + "PRRT_kwDOIcF9Es6i00gb, P2)")
    void sidecarUpdatePreservesExistingOwnerAndGroup() throws IOException {
        // Cannot be a RED-then-GREEN pair for OWNERSHIP specifically: both the sidecar and the
        // replacement temp file are created by this SAME test process, so owner/group already
        // trivially match before this fix too -- a genuinely foreign owner needs a file
        // provisioned by a different user, which cannot be constructed without root in this
        // sandbox (mirrors UltiToolsPluginLanguageFallbackTest#refreshPreservesOriginalOwnerAndGroup's
        // own documented limitation for the identical reason). This is a regression guard
        // exercising the new self-chown code path on the sidecar's own write, not a demonstration
        // that the old code corrupted ownership -- the permission-bits test above is this
        // finding's true RED/GREEN pair. What this test does NOT assert: behaviour when the
        // sidecar's owner differs from this process's own user -- that requires privileges this
        // sandbox does not have.
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        PosixFileAttributeView view = Files.getFileAttributeView(sidecarFile.toPath(), PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null,
                "Filesystem does not support POSIX file attributes; skipping.");
        PosixFileAttributes before = view.readAttributes();

        ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh-v1");

        PosixFileAttributes after = Files.readAttributes(sidecarFile.toPath(), PosixFileAttributes.class);
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.group()).isEqualTo(before.group());
    }

    @Test
    @DisplayName("a resource-hash sidecar that is a symbolic link is treated as operator-pinned -- "
            + "never replaced with a regular file, and the write is refused entirely rather than "
            + "written through to the link's target (round following "
            + "PosixAttributePreserver.java:104, symlink finding on ResourceHashSidecar.java:320, "
            + "thread PRRT_kwDOIcF9Es6i2I49, P2)")
    void symlinkSidecarIsTreatedAsOperatorPinnedAndNeverReplaced() throws IOException {
        // Mirrors UltiToolsPluginLanguageFallbackTest#symlinkLanguageFileIsTreatedAsOperatorPinnedAndNeverReplaced
        // -- same finding shape (a property UltiToolsPlugin#writeBytes already guarded, missing
        // entirely from the sidecar's own write path), same fix (both now delegate to
        // PosixAttributePreserver#replaceInPlace), now proven on the sidecar's own atomic-replace
        // path. The contract, confirmed by reading writeBytes's own symlink guard before writing
        // this test: REFUSE, not write-through-to-target -- a symlinked target is left completely
        // untouched, exactly like an operator-pinned read-only file.
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        byte[] originalBytes = Files.readAllBytes(sidecarFile.toPath());

        // Simulates an operator-managed shared/persisted provenance layout: the sidecar is a
        // symlink to a separate real file elsewhere, carrying the same bytes as the current
        // sidecar content.
        File linkTarget = new File(tempDir, "shared-resource-hashes.json");
        Files.write(linkTarget.toPath(), originalBytes);
        Files.delete(sidecarFile.toPath());
        try {
            Files.createSymbolicLink(sidecarFile.toPath(), linkTarget.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links not supported on this filesystem; skipping. " + e);
            return;
        }

        ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh-v1");

        // Not refreshed: the symlink survives, still pointing at the original content -- the
        // atomic move must never have replaced it with a regular file. The new entry must not
        // have been recorded anywhere: not in a replaced regular file, and not written through
        // to the link's own target either (REFUSE, not write-through, per writeBytes's contract).
        assertThat(Files.isSymbolicLink(sidecarFile.toPath())).isTrue();
        assertThat(Files.readAllBytes(sidecarFile.toPath())).isEqualTo(originalBytes);
        assertThat(Files.readAllBytes(linkTarget.toPath())).isEqualTo(originalBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-v1");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/zh.json")).isEmpty();
    }

    @Test
    @DisplayName("sha256(File) streams a larger file without reading it fully into memory, and still "
            + "matches the digest of an in-memory computation over the same bytes")
    void sha256StreamsLargerFileAndMatchesInMemoryDigest() throws Exception {
        // Deliberately not java.util.Random -- this only needs non-uniform, reproducible bytes
        // large enough to exercise the chunked read loop across multiple buffer fills, never
        // anything resembling a security-sensitive value, so no RNG (weak or otherwise) is used.
        byte[] bytes = new byte[5 * 1024 * 1024];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        File file = new File(tempDir, "large.bin");
        Files.write(file.toPath(), bytes);

        assertThat(ResourceHashSidecar.sha256(file)).isEqualTo(referenceSha256(bytes));
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
    @DisplayName("a write failure never loses a previously recorded hash (Codex round 3, P2: "
            + "writeAll must be atomic, not a direct truncating write)")
    void recordWriteFailureNeverLosesPreviouslyRecordedHash() throws IOException {
        ResourceHashSidecar.record(tempDir, "lang/en.json", "hash-en-v1");
        File sidecarFile = new File(tempDir, ".ultitools-resource-hashes.json");
        assertThat(sidecarFile).isFile();
        byte[] beforeBytes = Files.readAllBytes(sidecarFile.toPath());

        // Removing WRITE from the containing directory blocks creating/renaming a directory
        // entry -- exactly what the fixed (temp-file + atomic-move) implementation needs to
        // stage its write -- without touching the sidecar FILE's own permissions, which a direct
        // truncating write only needs. This is the same fault-injection idiom
        // UltiToolsPluginLanguageFallbackTest.overwriteWriteFailureDoesNotRecordJarHashOrLogSuccess
        // already uses for writeBytes()'s identical atomic-replace contract.
        assertThat(tempDir.setWritable(false)).isTrue();
        try {
            ResourceHashSidecar.record(tempDir, "lang/zh.json", "hash-zh-v1");
        } finally {
            assertThat(tempDir.setWritable(true)).isTrue();
        }

        // The failed write must never have touched the real sidecar file at all -- not the
        // previously recorded entry, and not by adding the new one either.
        assertThat(Files.readAllBytes(sidecarFile.toPath())).isEqualTo(beforeBytes);
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/en.json")).contains("hash-en-v1");
        assertThat(ResourceHashSidecar.readRecordedHash(tempDir, "lang/zh.json")).isEmpty();
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
    @DisplayName("hashes already accumulated before a later entry aborts extraction are still "
            + "persisted (Codex round 10, P2, discussion on UltiToolsPlugin.java:1314)")
    void saveResourcesPersistsQueuedHashesEvenWhenALaterEntryAbortsExtraction() throws Throwable {
        // A NUL byte in a jar entry name is a legal zip-format entry name (no such restriction in
        // the format itself, confirmed by round-tripping through JarOutputStream/JarFile), but
        // File.getCanonicalPath() throws IOException("Invalid file path") for one -- deterministic
        // and portable, unlike trying to corrupt zip bytes or force getInputStream() to fail. This
        // reproduces exactly the escape Codex describes: the exception fires OUTSIDE
        // saveResources()'s inner per-entry try/catch (it sits between the zip-slip check and that
        // try block), so it propagates to the outer catch, skipping the rest of the while loop
        // entirely -- including any entries not yet visited.
        // saveResources()'s outer catch (IOException e) logs via getLogger(), which routes
        // through UltiTools.getInstance().getLogger() -- unlike this class's other saveResources()
        // tests, which never reach that catch clause and so never needed either mocked.
        TestHelper.mockUltiToolsInstance(ultiTools ->
                org.mockito.Mockito.when(ultiTools.getLogger())
                        .thenReturn(java.util.logging.Logger.getLogger("ResourceHashSidecarTest")));

        char nul = (char) 0;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("lang/en.json", "{\"greeting\":\"Hi\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("lang/bad" + nul + "name.json", "{}".getBytes(StandardCharsets.UTF_8));
        File jar = buildFixtureJar("fixture-abort.jar", entries);

        File resourceFolder = new File(tempDir, "resource-folder-abort");
        Files.createDirectories(resourceFolder.toPath());

        try (ChildFirstClassLoader loader = new ChildFirstClassLoader(new URL[]{jar.toURI().toURL()},
                ResourceHashSidecarTest.class.getClassLoader())) {
            Object plugin = newFixtureInstance(loader);
            setResourceFolderPath(plugin, resourceFolder.getAbsolutePath());

            invokeSaveResources(plugin);

            // The first entry extracted fine, BEFORE the second entry's canonical-path resolution
            // threw and aborted the loop -- its hash must still be persisted, not discarded.
            File extractedLang = new File(resourceFolder, "lang" + File.separator + "en.json");
            assertThat(extractedLang).isFile();
            assertThat(ResourceHashSidecar.readRecordedHash(resourceFolder, "lang/en.json"))
                    .contains(ResourceHashSidecar.sha256(extractedLang));
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
