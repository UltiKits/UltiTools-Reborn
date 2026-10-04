package com.ultikits.ultitools.config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
import com.ultikits.ultitools.config.document.PlainData;

/**
 * Writes named keys of an operator-editable YAML file that a module manages on an operator's behalf - a kit file, a
 * menu file - and nothing else.
 * <p>
 * Such files are created by the operator (or by the module on the operator's explicit create action) and edited by
 * hand. The maintainer's rule for them (2026-10-04, "what code may write, by file type") is that an explicit edit -
 * a kit editor saving a kit's items - writes only the edited part: comments, other keys, unknown keys and values the
 * module itself cannot use stay exactly as the operator wrote them. A module therefore {@link #read(File) reads} the
 * file, decides what to change, and {@link #write(Snapshot, Map) writes} only those whole keys:
 * <pre>{@code
 * OperatorFiles.Snapshot snapshot = OperatorFiles.read(kitFile);
 * // ... parse snapshot.getText() with any YAML reader ...
 * OperatorFiles.WriteResult result = OperatorFiles.write(snapshot,
 *         Collections.singletonMap(Collections.singletonList("items"), serializedItems));
 * }</pre>
 * <p>
 * <b>Why it cannot change anything else.</b> The write goes through the framework's configuration write gate: it
 * owns only the named keys, renders the file, and publishes it only when every line outside those keys - comments,
 * other keys, layout, quoting, a value the module cannot use - is byte-identical to the file as read, and only while
 * the file still holds exactly the bytes of the snapshot (an edit saved after {@code read} is never overwritten).
 * Otherwise nothing is written and the result says why ({@link WriteResult#FILE_CHANGED} or
 * {@link WriteResult#REFUSED}, the latter with one WARNING in the server log naming the file, the keys and the reason,
 * never a value). Publication is atomic. A key path is a list of whole keys, so a key containing {@code .} is one key.
 * <p>
 * <b>What it does not do.</b> It never creates, deletes or renames a file: {@code read} requires an existing file,
 * and {@code write} touches only the file of its snapshot and refuses when that file is gone. Values must be plain
 * data (strings, numbers, booleans, {@code null}, and lists and string-keyed maps of those); a Bukkit object such as
 * an {@code ItemStack}, a {@code UUID} or any other type is refused before anything is read or written - serialize it
 * first. This is not a general file API: it writes YAML keys of a file that already exists (a crash-safe file API for
 * modules is UltiKits/UltiTools-Reborn#545, a later feature), and it is not for a module's {@code @ConfigEntity}
 * files, which have their own save rules.
 *
 * @since 6.3.0
 */
public final class OperatorFiles {

    private OperatorFiles() {
    }

    /** What {@link #write(Snapshot, Map)} did. */
    public enum WriteResult {
        /** The named keys were written; nothing else in the file changed. */
        WRITTEN,
        /** The file already holds those values; nothing was written. */
        UNCHANGED,
        /** The file no longer holds the snapshot's bytes (edited, replaced or deleted since it was read); nothing was written. */
        FILE_CHANGED,
        /**
         * Writing the keys would have changed something else in the file - a layout the renderer cannot keep byte for
         * byte, YAML anchors, aliases or merge keys - or the file cannot be parsed; nothing was written, and one WARNING
         * in the server log names the file, the keys and the reason.
         */
        REFUSED
    }

    /** A file's text exactly as {@link #read(File)} found it, and the fingerprint of its bytes. Only {@code read} creates one. */
    public static final class Snapshot {

        private final File file;
        private final String text;
        private final String fingerprint;

        private Snapshot(File file, String text, String fingerprint) {
            this.file = file;
            this.text = text;
            this.fingerprint = fingerprint;
        }

        /**
         * The file that was read, as an absolute path; {@link #write(Snapshot, Map)} writes only this file.
         *
         * @return the file
         */
        public File getFile() {
            return file;
        }

        /**
         * The file's text, decoded as strict UTF-8.
         *
         * @return the text
         */
        public String getText() {
            return text;
        }

        /**
         * The SHA-256 of the file's bytes, as 64 lower-case hexadecimal digits.
         *
         * @return the fingerprint
         */
        public String getFingerprint() {
            return fingerprint;
        }
    }

    /**
     * Reads an existing file. Nothing is written.
     *
     * @param file the file to read
     * @return its text and fingerprint
     * @throws IOException if the file does not exist, cannot be read, or is not valid UTF-8
     */
    public static Snapshot read(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("No file to read");
        }
        File absolute = file.getAbsoluteFile();
        byte[] bytes = Files.readAllBytes(absolute.toPath());
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        return new Snapshot(absolute, text, sha256(bytes));
    }

    /**
     * Writes {@code values} - each a whole key path and its new value - into the snapshot's file and changes nothing
     * else, only while the file still holds the snapshot's bytes (see the class description for why nothing else can
     * change). A key the file lacks is inserted. Must not be called for a file a {@code @ConfigEntity} manages.
     *
     * @param readAt the snapshot the change was decided on
     * @param values key path (a list of whole keys, at least one) to plain-data value
     * @return what happened; {@link WriteResult#UNCHANGED} when {@code values} is empty
     * @throws IllegalArgumentException if a key path is empty or holds {@code null}, or a value is not plain data -
     *                                  checked before the file is read again, so nothing is written
     * @throws IOException              if publishing fails (an atomic replacement, so the file then holds its old
     *                                  bytes or, after a failed in-place fallback, a complete framework backup
     *                                  remains beside it)
     */
    @SuppressWarnings("PMD.NPathComplexity") // Every argument is validated before any I/O, each with its own message.
    public static WriteResult write(Snapshot readAt, Map<List<String>, Object> values) throws IOException {
        if (readAt == null || values == null) {
            throw new IllegalArgumentException("A write needs the snapshot it was decided on and the values to write");
        }
        Map<List<String>, Object> checked = new LinkedHashMap<>();
        OwnedPaths.Builder owned = OwnedPaths.builder();
        for (Map.Entry<List<String>, Object> entry : values.entrySet()) {
            List<String> path = entry.getKey();
            if (path == null || path.isEmpty() || path.contains(null)) {
                throw new IllegalArgumentException("A key path needs at least one key and no null key");
            }
            List<String> copy = Collections.unmodifiableList(new ArrayList<>(path));
            Object value = PlainData.copy(entry.getValue());
            PlainData.requirePlain(copy, value);
            checked.put(copy, value);
            owned.value(copy);
        }
        if (checked.isEmpty()) {
            return WriteResult.UNCHANGED;
        }
        Path target = readAt.getFile().toPath();
        OperatorFileWriter.Result result = OperatorFileWriter.write(target, owned.build(), readAt.getFingerprint(),
                candidate -> {
                    for (Map.Entry<List<String>, Object> entry : checked.entrySet()) {
                        candidate.set(entry.getKey(), entry.getValue());
                    }
                });
        switch (result.outcome()) {
            case WRITTEN:
                return WriteResult.WRITTEN;
            case UNCHANGED:
                return WriteResult.UNCHANGED;
            case FILE_CHANGED:
                return WriteResult.FILE_CHANGED;
            default:
                return WriteResult.REFUSED;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
