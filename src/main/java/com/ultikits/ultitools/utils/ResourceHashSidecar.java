package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param resourcePath   the extracted resource's path, relative to the resource folder, using
     *                       {@code '/'} as separator (matching jar entry names)
     * @param hash           the digest to record (see {@link #sha256(File)})
     */
    public static void record(File resourceFolder, String resourcePath, String hash) {
        Map<String, String> entries = readAll(resourceFolder);
        entries.put(resourcePath, hash);
        writeAll(resourceFolder, entries);
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
     *
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param newEntries     the {@code resourcePath -> hash} pairs to add, preserving every entry
     *                       already recorded
     */
    public static void recordAll(File resourceFolder, Map<String, String> newEntries) {
        if (newEntries.isEmpty()) {
            return;
        }
        Map<String, String> entries = readAll(resourceFolder);
        entries.putAll(newEntries);
        writeAll(resourceFolder, entries);
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
            // alone does not see, letting it escape readRecordedHash/record/recordAll into the
            // plugin constructor and abort module startup.
            LOGGER.log(Level.WARNING, "Ignoring unreadable resource-hash sidecar " + file.getPath(), e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Writes {@code entries} to the sidecar file, via a temporary file in the sidecar's own
     * parent directory that is atomically moved into place only once the full write has
     * succeeded (Codex round 3, P2).
     * <p>
     * Before this fix, this method opened the sidecar's real path directly with {@code
     * TRUNCATE_EXISTING}, which truncates the file as PART OF the {@code open()} call itself --
     * so a failure partway through serialization (e.g. the filesystem filling up) left every
     * previously recorded hash replaced by empty or partial JSON. {@link #readAll(File)} degrades
     * that state to "no record" for every path, not just the one being written, which could
     * misclassify an untouched language file as an operator customisation and skip a legitimate
     * update from the current jar. Writing to a temp file first means a failure never touches the
     * real sidecar at all -- exactly the same fix already applied to {@code UltiToolsPlugin
     * #writeBytes} for the language file itself.
     */
    private static void writeAll(File resourceFolder, Map<String, String> entries) {
        File file = sidecarFile(resourceFolder);
        File parent = file.getParentFile();
        File tempFile = null;
        try {
            if (parent != null && !parent.isDirectory()) {
                Files.createDirectories(parent.toPath());
            }
            tempFile = File.createTempFile(SIDECAR_FILE_NAME, ".tmp", parent);
            try (Writer writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(entries, ENTRY_MAP_TYPE, writer);
            }
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JsonIOException e) {
            // Codex round 6, P2: GSON.toJson wraps an IOException it hits while actively
            // serializing (e.g. the filesystem filling up mid-write) in its own unchecked
            // JsonIOException, which a catch (IOException) alone does not see -- letting it
            // escape record()/recordAll() into the plugin constructor and abort module startup,
            // contrary to this class's own documented best-effort behaviour (see class javadoc).
            LOGGER.log(Level.WARNING, "Failed to write resource-hash sidecar " + file.getPath(), e);
        } finally {
            if (tempFile != null) {
                // A successful move already renamed the temp file away from tempFile's own path,
                // so this is a no-op on the success path and only cleans up a leftover staging
                // file on any failure branch above.
                // noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }
}
