package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
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
import com.google.gson.JsonSyntaxException;
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
     *
     * @param file the file to hash; must exist and be readable
     * @return the lowercase hex-encoded SHA-256 digest of the file's raw bytes
     */
    public static String sha256(File file) {
        try {
            return sha256(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to hash " + file.getPath(), e);
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
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed algorithm (JLS platform requirement); this branch is
            // unreachable on any conforming JVM.
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
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
        } catch (IOException | JsonSyntaxException e) {
            // Malformed or unreadable sidecar degrades to "no record" (T-16-04-03) rather than
            // blocking boot -- see class javadoc.
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
        } catch (IOException e) {
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
