package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * Records the SHA-256 digest of every resource file {@link
 * com.ultikits.ultitools.abstracts.UltiToolsPlugin#saveResources()} extracts, so a later boot can
 * distinguish "the operator edited this file" from "an old jar extracted this and nobody has
 * touched it since" (#441, D-05/D-06/D-07).
 * <p>
 * The digest is deliberately computed over a file's raw bytes, never over decoded text: an
 * encoding-preserving rewrite of the same logical content must read as a difference, and a
 * byte-identical copy must not.
 * <p>
 * The sidecar file is named {@value #SIDECAR_FILE_NAME}. It lives directly under the module's
 * resource folder root -- never under {@code lang/}, {@code res/} or {@code config/} -- and its
 * name is dot-prefixed and starts with none of those three strings, so it can never collide with a
 * real extracted resource: {@code saveResources()} only ever extracts jar entries whose name
 * starts with {@code "res"}, {@code "lang"} or {@code "config"}.
 * <p>
 * A missing or unparsable sidecar degrades to "no record" rather than throwing (T-16-04-03): an
 * operator who can edit the sidecar can already edit the files it describes, so treating a corrupt
 * sidecar as "unknown provenance" is the safe branch, not a defect to guard against harder.
 * <p>
 * {@code public} only so {@code UltiToolsPlugin} (a different package) can call it -- this is
 * internal framework plumbing, not a documented capability (WR-02): it appears nowhere in {@code
 * FEATURES.md} as its own row, and this milestone's own D-09 decision rejects shipping new public
 * surface in 6.3.0 for exactly this reason. {@link ApiStatus.Internal} carries no binary-
 * compatibility consequence here -- the class is new in this same pull request and has never
 * shipped in a released jar, so no {@code COMPATIBILITY.md} entry or japicmp exclude is needed for
 * this annotation; annotating it now, before the first release that carries it, is what keeps it
 * from becoming one later.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ResourceHashSidecar {

    private static final Logger LOGGER = Logger.getLogger(ResourceHashSidecar.class.getName());
    private static final String SIDECAR_FILE_NAME = ".ultitools-resource-hashes.json";
    private static final Type ENTRY_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ResourceHashSidecar() {
    }

    /**
     * Computes the SHA-256 digest of {@code file}'s raw bytes, hex-encoded lowercase.
     * <p>
     * Codex round 4, P2: streams the file in fixed-size chunks rather than {@link
     * Files#readAllBytes} -- the previous implementation allocated a byte array as large as the
     * entire file, so a sufficiently large bundled resource (a module's web dashboard assets,
     * audio, etc.) could exhaust the server heap and abort module initialization, even though the
     * extraction path that produced the file ({@code saveResources()}) already streams with a
     * fixed-size buffer. Streaming here keeps peak memory bounded regardless of file size.
     *
     * @param file the file to hash; must exist and be readable
     * @return the lowercase hex-encoded SHA-256 digest of the file's raw bytes
     */
    public static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, len);
                }
            }
            return toHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file.getPath(), e);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (JLS platform requirement); this branch is
            // unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
    }

    /**
     * Computes the SHA-256 digest of {@code bytes}, hex-encoded lowercase.
     *
     * @param bytes the raw bytes to hash
     * @return the lowercase hex-encoded SHA-256 digest
     */
    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (JLS platform requirement); this branch is
            // unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /**
     * Returns the digest previously recorded for {@code resourcePath} (e.g. {@code "lang/en.json"})
     * under {@code resourceFolder}, or {@link Optional#empty()} when there is no record --
     * including when the sidecar file itself is absent or cannot be parsed.
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param resourcePath   the extracted resource's path, relative to the resource folder, using
     *                       {@code '/'} as separator (matching jar entry names)
     * @return the recorded digest, or empty if there is no record
     */
    public static Optional<String> readRecordedHash(File resourceFolder, String resourcePath) {
        return Optional.ofNullable(readAll(resourceFolder).get(resourcePath));
    }

    /**
     * Records {@code hash} as the provenance digest for {@code resourcePath} under {@code
     * resourceFolder}, preserving every other entry already recorded.
     * <p>
     * Codex round 9, P2: if the sidecar file EXISTS but cannot currently be read/parsed (e.g. a
     * transient filesystem error), this is a no-op -- see {@link #readAllForWrite(File)}'s own
     * javadoc for why a write must never equate that with "the sidecar does not exist yet".
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param resourcePath   the extracted resource's path, relative to the resource folder, using
     *                       {@code '/'} as separator (matching jar entry names)
     * @param hash           the digest to record (see {@link #sha256(File)})
     */
    public static void record(File resourceFolder, String resourcePath, String hash) {
        Optional<Map<String, String>> entries = readAllForWrite(resourceFolder);
        if (!entries.isPresent()) {
            return;
        }
        Map<String, String> map = entries.get();
        map.put(resourcePath, hash);
        writeAll(resourceFolder, map);
    }

    /**
     * Records every {@code resourcePath -> hash} pair in {@code newEntries} in ONE
     * read-modify-write cycle, instead of the one-cycle-per-entry cost calling {@link #record}
     * once per entry in a loop would pay (Codex round 4, P2): {@code saveResources()} extracts
     * every jar entry across a single pass, so collecting that pass's hashes and persisting them
     * together avoids parsing and atomically rewriting an increasingly large sidecar once per
     * extracted file -- quadratic for a module bundling many resources (e.g. thousands of web
     * dashboard assets). A no-op for an empty map: never touches the sidecar file, or its
     * directory, when there is nothing new to record.
     * <p>
     * Codex round 9, P2: also a no-op if the sidecar file EXISTS but cannot currently be
     * read/parsed -- see {@link #readAllForWrite(File)}'s own javadoc.
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param newEntries     the {@code resourcePath -> hash} pairs to add, preserving every entry
     *                       already recorded
     */
    public static void recordAll(File resourceFolder, Map<String, String> newEntries) {
        if (newEntries.isEmpty()) {
            return;
        }
        Optional<Map<String, String>> entries = readAllForWrite(resourceFolder);
        if (!entries.isPresent()) {
            return;
        }
        Map<String, String> map = entries.get();
        map.putAll(newEntries);
        writeAll(resourceFolder, map);
    }

    private static File sidecarFile(File resourceFolder) {
        return new File(resourceFolder, SIDECAR_FILE_NAME);
    }

    private static Map<String, String> readAll(File resourceFolder) {
        File file = sidecarFile(resourceFolder);
        if (!file.isFile()) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, String> parsed = GSON.fromJson(reader, ENTRY_MAP_TYPE);
            return parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
        } catch (IOException | JsonParseException e) {
            // Malformed or unreadable sidecar degrades to "no record" (T-16-04-03) rather than
            // blocking boot -- see class javadoc. JsonParseException (Codex round 7, P2) is the
            // common superclass of JsonSyntaxException (malformed JSON) AND JsonIOException --
            // Gson wraps an IOException it hits reading from `reader` mid-parse (e.g. a network
            // filesystem hiccup) in the latter, which a catch (IOException | JsonSyntaxException)
            // alone does not see, letting it escape readRecordedHash into the plugin constructor
            // and abort module startup. record()/recordAll() route through readAllForWrite below
            // instead (Codex round 9, P2), which shares this catch clause but does NOT degrade
            // the same way for a WRITE -- see its own javadoc.
            LOGGER.log(Level.WARNING, "Ignoring unreadable resource-hash sidecar " + file.getPath(), e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Like {@link #readAll(File)}, but distinguishes "the sidecar does not exist yet" (fine to
     * proceed from an empty baseline) from "the sidecar exists but could not be read or parsed
     * THIS TIME" (Codex round 9, P2, discussion on {@code ResourceHashSidecar.java:155}).
     * {@link #record} and {@link #recordAll} call this instead of {@link #readAll} for exactly
     * that reason: {@link #readAll}'s "any failure degrades to an empty map" contract is correct
     * for a READ ({@link #readRecordedHash}, per T-16-04-03 -- a caller just gets "no record",
     * nothing is lost), but wrong for a WRITE. Before this fix, record()/recordAll() called
     * readAll() directly, so a transient read failure on an otherwise-valid, non-empty sidecar
     * produced an empty map that was then WRITTEN BACK with only the entry being recorded --
     * permanently discarding every other module's previously recorded hash over a failure that
     * may not even recur on the very next boot.
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @return the parsed entries (a fresh, empty map if the sidecar does not exist), or {@link
     *         Optional#empty()} if the sidecar file exists but could not be read/parsed this
     *         time -- callers writing back MUST treat that as "do nothing", never as "empty"
     */
    private static Optional<Map<String, String>> readAllForWrite(File resourceFolder) {
        File file = sidecarFile(resourceFolder);
        if (!file.isFile()) {
            return Optional.of(new LinkedHashMap<>());
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Map<String, String> parsed = GSON.fromJson(reader, ENTRY_MAP_TYPE);
            return Optional.of(parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>());
        } catch (IOException | JsonParseException e) {
            LOGGER.log(Level.WARNING, "Not updating resource-hash sidecar " + file.getPath()
                    + ": it exists but could not be read or parsed just now, and writing back a "
                    + "map derived from that failure would permanently discard every previously "
                    + "recorded hash over what may be a transient error.", e);
            return Optional.empty();
        }
    }

    /**
     * Writes {@code entries} to the sidecar file, replacing it in place (Codex round 3, P2:
     * atomically, via a temp file, never truncating the real sidecar on a failure partway
     * through).
     * <p>
     * Round following {@code PosixAttributePreserver.java:104} (symlink finding on {@code
     * ResourceHashSidecar.java:320}, thread {@code PRRT_kwDOIcF9Es6i2I49}, P2): this method's
     * entire replace-in-place operation -- the operator-pinned-read-only refusal, the symlink
     * refusal, the temp-file-then-atomic-move mechanism, and POSIX attribute preservation -- now
     * lives in {@link PosixAttributePreserver#replaceInPlace}, the SAME shared operation {@code
     * UltiToolsPlugin#writeBytes} uses for the language-file path, instead of the sidecar copying
     * each of that operation's properties in one at a time across separate rounds (round 3:
     * attribute preservation; round 6 (this same session): an unreadable-attributes edge case;
     * this round: symlink handling, which had never been ported here at all). A third
     * replace-in-place site now gets every one of these properties for free by calling {@link
     * PosixAttributePreserver#replaceInPlace} instead of having to notice each property is
     * missing one at a time.
     * <p>
     * The entries are serialized to a {@code byte[]} BEFORE calling {@link
     * PosixAttributePreserver#replaceInPlace}, rather than streaming {@code Gson.toJson(Object,
     * Type, java.io.Writer)} directly onto the staging file's own {@code Writer} as an earlier version of
     * this method did -- {@code GSON.toJson(entries, ENTRY_MAP_TYPE)}'s {@link String}-returning
     * overload serializes into an in-memory {@link java.io.StringWriter}, which cannot throw a
     * genuine I/O-based {@link JsonIOException} the way writing to a file-backed {@code Writer}
     * could (Codex round 6, P2, on the pre-refactor version of this method). This is a
     * simplification the refactor enables, not merely a byproduct: {@link
     * PosixAttributePreserver#replaceInPlace} takes the exact bytes to write, uniformly for every
     * caller, so the disk-I/O-during-serialization failure mode this class used to guard against
     * separately no longer exists as a distinct case to catch.
     */
    private static void writeAll(File resourceFolder, Map<String, String> entries) {
        File file = sidecarFile(resourceFolder);
        byte[] content;
        try {
            content = GSON.toJson(entries, ENTRY_MAP_TYPE).getBytes(StandardCharsets.UTF_8);
        } catch (JsonIOException e) {
            // Defensive only: serializing into an in-memory StringWriter (see this method's own
            // javadoc) should never actually throw a genuine I/O-based JsonIOException, but
            // degrading here rather than letting it escape keeps this class's documented
            // best-effort contract (see class javadoc) true even if that assumption ever changes.
            LOGGER.log(Level.WARNING, "Failed to serialize resource-hash sidecar " + file.getPath(), e);
            return;
        }
        PosixAttributePreserver.replaceInPlace(file, content, new PosixAttributePreserver.ReplaceInPlaceListener() {
            @Override
            public void onOperatorPinnedReadOnly() {
                LOGGER.log(Level.WARNING, "Resource-hash sidecar " + file.getPath() + " is not "
                        + "writable; treating it as operator-pinned and leaving it untouched "
                        + "instead of updating it.");
            }

            @Override
            public void onSymbolicLink() {
                // Codex finding on ResourceHashSidecar.java:320, thread PRRT_kwDOIcF9Es6i2I49, P2:
                // an operator-managed shared/persisted provenance layout (e.g.
                // .ultitools-resource-hashes.json symlinked to a shared store) had the LINK ITSELF
                // replaced by a regular file on every update, silently breaking the layout. Same
                // REFUSE semantics as the language-file path -- see
                // PosixAttributePreserver.ReplaceInPlaceListener#onSymbolicLink()'s own javadoc.
                LOGGER.log(Level.WARNING, "Resource-hash sidecar " + file.getPath() + " is a "
                        + "symbolic link; treating it as operator-pinned and leaving it untouched "
                        + "instead of replacing the link with a regular file.");
            }

            @Override
            public void onSourceAttributesUnreadable() {
                LOGGER.log(Level.WARNING, "Could not read the current permissions and owner/group "
                        + "of resource-hash sidecar " + file.getPath() + "; treating its identity "
                        + "as unreplicable and leaving it untouched instead of silently replacing "
                        + "it with a process-owned copy.");
            }

            @Override
            public void onPermissionCopyFailure() {
                LOGGER.log(Level.WARNING, "Could not preserve file permissions while refreshing "
                        + "resource-hash sidecar " + file.getPath() + "; the refreshed file may "
                        + "not match the original's permissions.");
            }

            @Override
            public void onOwnershipCopyFailure(String ownerName, String groupName) {
                LOGGER.log(Level.WARNING, "Resource-hash sidecar " + file.getPath() + " is owned "
                        + "by '" + ownerName + ":" + groupName + "', which this process cannot "
                        + "replicate onto the refreshed file; leaving the existing sidecar "
                        + "untouched instead of silently changing its ownership.");
            }

            @Override
            public void onWriteFailure(IOException cause) {
                // Codex round 6, P2 (pre-refactor): this used to also need to catch JsonIOException
                // here for the same reason -- no longer applicable, see this method's own javadoc
                // for why serialization can no longer fail as part of this step.
                LOGGER.log(Level.WARNING, "Failed to write resource-hash sidecar " + file.getPath(), cause);
            }
        });
    }
}
