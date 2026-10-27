package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;
import org.jetbrains.annotations.ApiStatus;

/**
 * Safe server.properties manager for remote editing via WebSocket.
 * Only exposes a curated whitelist of non-sensitive keys.
 * <p>
 * <b>Why a panel edit cannot overwrite operator content</b> (maintainer decisions of 2026-10-04, "operator-written
 * configuration is never overwritten" and "what code may write, by file type": a panel edit writes only the named
 * key; UltiKits/UltiTools-Reborn#607). The file is never re-emitted: an edit replaces the value text of the one
 * physical line that defines the key and nothing else - the header and operator comments, key order, the key text
 * and separator as written, line terminators and every other value keep their bytes. The file is decoded the way
 * the running server reads it (measured from the Paper 1.21.11 jar, {@code Settings#loadFromFile}: strict UTF-8,
 * falling back to ISO-8859-1 when the file is not valid UTF-8), the new value is escaped with the
 * {@link Properties} value grammar in a form that reader returns exactly, and the result is re-encoded in the
 * charset it was decoded with. Before publishing, every other line is checked byte for byte and a fresh parse must
 * return the new value and every other key unchanged; a key defined on more than one line, or over continuation
 * lines, is refused with a reason. Publication is atomic through {@link AtomicConfigWriter}, and only while the file
 * still holds the bytes the edit was made against. A refusal writes nothing and logs one WARNING naming the file,
 * the key and the reason, never a value.
 */
@ApiStatus.Internal
public class ServerPropertiesManager {
    private static final Logger LOGGER = Logger.getLogger(ServerPropertiesManager.class.getName());
    /** Serializes panel edits of the file within this server run. */
    private static final Object WRITE_LOCK = new Object();
    private static final String FILE_NAME = "server.properties";

    private final File serverRoot;
    @SuppressWarnings("removal") // the kept v1 sender holds the deprecated v1 client until 6.4.0 (S-67)
    private UltiPanelWebSocketClient webSocketClient;

    private static final Set<String> SAFE_KEYS = new HashSet<>(Arrays.asList(
        "motd", "max-players", "view-distance", "simulation-distance",
        "spawn-protection", "difficulty", "gamemode", "pvp",
        "allow-nether", "allow-flight", "spawn-animals", "spawn-monsters",
        "spawn-npcs", "enable-command-block"
    ));

    /**
     * The keys the frozen protocol 2.0.0 contract lets a panel read, in contract order.
     * {@link #SAFE_KEYS} stays its own set: this list decides what {@link #readVersioned(int)}
     * reports, not what the v1 write path accepts.
     */
    private static final List<String> V2_READABLE_KEYS = Collections.unmodifiableList(Arrays.asList(
        "motd", "max-players", "difficulty", "gamemode", "spawn-protection", "view-distance",
        "simulation-distance", "pvp", "allow-flight", "spawn-monsters", "allow-nether",
        "enable-command-block", "online-mode", "white-list"));

    /**
     * The keys a remote update may change: the contract's twelve. It is deliberately independent of
     * {@link #SAFE_KEYS}, which still contains the v1-only {@code spawn-animals} and
     * {@code spawn-npcs}. {@code online-mode} and {@code white-list} must never join it: turning
     * server authentication or the whitelist off from the panel is an elevation of privilege the
     * operator has not granted (maintainer decision, user decision 15), so those two keys are
     * readable and never writable.
     */
    private static final List<String> V2_WRITABLE_KEYS = Collections.unmodifiableList(Arrays.asList(
        "motd", "max-players", "difficulty", "gamemode", "spawn-protection", "view-distance",
        "simulation-distance", "pvp", "allow-flight", "spawn-monsters", "allow-nether",
        "enable-command-block"));

    public ServerPropertiesManager(File serverRoot) {
        this.serverRoot = serverRoot;
    }

    /**
     * Sets the protocol v1 client the v1 senders of this manager reply over.
     *
     * @deprecated There is no replacement: the framework never calls it, and its own UltiCloud session
     *     (protocol 2.0.0) carries the panel requests. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    @SuppressWarnings("removal") // takes the deprecated v1 client; kept until 6.4.0 (S-67)
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        V1CloudNotice.once(ServerPropertiesManager.class, "setWebSocketClient");
        this.webSocketClient = client;
    }

    /**
     * The whitelisted keys the file defines, decoded the way the server reads them (see the class description).
     *
     * @return key to value; empty when the file is absent, unreadable or unparseable
     */
    public Map<String, String> getSafeProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        File propsFile = new File(serverRoot, FILE_NAME);
        if (!propsFile.exists()) return result;

        Properties props;
        try {
            props = PropertiesText.read(propsFile.toPath()).parse();
        } catch (IOException | IllegalArgumentException e) {
            return result;
        }

        for (String key : SAFE_KEYS) {
            String value = props.getProperty(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    public boolean setProperty(String key, String value) {
        return writeProperty(key, value).outcome == WriteOutcome.WRITTEN;
    }

    /**
     * The keys a remote update may change: the contract's twelve.
     *
     * @return an unmodifiable list of the writable keys, in contract order
     */
    public static List<String> writableKeys() {
        return V2_WRITABLE_KEYS;
    }

    /**
     * Reads {@code server.properties} for the protocol 2.0.0 panel: the fourteen readable keys
     * that are present and parse to their contract type, the writable key set, and the version a
     * later update must send back as its {@code baseVersion}.
     *
     * <p>The version is the SHA-256 of exactly the bytes this call read, so the values and the
     * version always describe one file state; a key whose value does not parse to its type is left
     * out rather than reported with a wrong type. The contract's RUNTIME_CHECKS {@code
     * file-content} rule applies here too — the file must be valid UTF-8, within the connection's
     * limit and free of C0 control characters other than TAB, LF and CR — because the panel reads
     * this file to write it back.</p>
     *
     * @param maxContentBytes the connection's negotiated {@code fileMaxBytes}
     * @return the snapshot, or which refusal prevented one
     */
    public PropertiesSnapshot readVersioned(int maxContentBytes) {
        File propsFile = new File(serverRoot, FILE_NAME);
        if (!propsFile.exists()) {
            return PropertiesSnapshot.notFound();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(propsFile.toPath());
        } catch (IOException unreadable) {
            LOGGER.log(Level.WARNING, "server.properties " + propsFile.getAbsolutePath()
                    + " could not be read: " + unreadable.getClass().getSimpleName());
            return PropertiesSnapshot.unreadable();
        }
        PropertiesText text = PropertiesText.decode(bytes);
        if (!isEditableContent(bytes, text, maxContentBytes)) {
            return PropertiesSnapshot.fileNotEditable();
        }
        Properties props;
        try {
            props = text.parse();
        } catch (IllegalArgumentException malformedEscape) {
            return PropertiesSnapshot.fileNotEditable();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : V2_READABLE_KEYS) {
            String value = props.getProperty(key);
            if (value != null) {
                values.put(key, value);
            }
        }
        return PropertiesSnapshot.ok(values, V2_WRITABLE_KEYS, versionOf(bytes));
    }

    /**
     * Applies a protocol 2.0.0 properties update: for each requested key, replaces the value text
     * of the physical line that defines it and nothing else, and only while {@code server.properties}
     * still hashes to {@code baseVersion}.
     *
     * <p><b>Why this path cannot overwrite operator content</b> (configuration-layer rule): the
     * version is computed from the bytes read inside {@link #WRITE_LOCK}, the comparison happens
     * there too, and publication goes through {@link AtomicConfigWriter}, which re-reads the file
     * under its own lock and replaces it only while it still holds those bytes. A panel edit
     * therefore lands only when the operator is writing back the very content the panel read; any
     * other state answers {@link PropertiesUpdateResult.Outcome#FILE_CHANGED} and leaves every byte
     * in place. Keys outside the contract's twelve are refused per key even when a caller reaches
     * this method directly — that refusal is the defence in depth behind the strict payload schema,
     * so {@code online-mode} and {@code white-list} cannot be changed remotely by any route.</p>
     *
     * @param baseVersion the SHA-256 the panel read, or null to have the update refused as changed
     * @param changes     the requested keys and their new values, already in their text form
     * @return the per-key outcome, the version now on disk and, for a refusal, its reason
     */
    @SuppressWarnings("PMD.NPathComplexity") // Each per-key refusal is checked and reported separately.
    public PropertiesUpdateResult updateWithBaseVersion(String baseVersion, Map<String, String> changes) {
        File propsFile = new File(serverRoot, FILE_NAME);
        if (!propsFile.exists()) {
            return PropertiesUpdateResult.notFound();
        }
        Map<String, String> requested = changes == null
                ? Collections.<String, String>emptyMap() : new LinkedHashMap<>(changes);

        synchronized (WRITE_LOCK) {
            Path path = propsFile.toPath();
            PropertiesText original;
            Properties before;
            try {
                original = PropertiesText.read(path);
                before = original.parse();
            } catch (IOException unreadable) {
                LOGGER.log(Level.WARNING, "server.properties " + propsFile.getAbsolutePath()
                        + " could not be read for an update: " + unreadable.getClass().getSimpleName());
                return PropertiesUpdateResult.refused("server.properties could not be read");
            } catch (IllegalArgumentException malformedEscape) {
                return PropertiesUpdateResult.refused(
                        "server.properties cannot be parsed (a malformed \\u escape)");
            }

            String currentVersion = versionOf(original.bytes);
            if (baseVersion == null || !baseVersion.equals(currentVersion)) {
                return PropertiesUpdateResult.fileChanged();
            }

            List<PropertyKeyResult> keys = new ArrayList<>();
            byte[] buffer = original.bytes;
            Properties current = before;
            boolean anyApplied = false;
            boolean anyRefused = false;
            for (Map.Entry<String, String> change : requested.entrySet()) {
                String key = change.getKey();
                String value = change.getValue();
                if (!V2_WRITABLE_KEYS.contains(key)) {
                    keys.add(PropertyKeyResult.refused(key,
                            "this key is not writable through the panel"));
                    anyRefused = true;
                    continue;
                }
                if (value == null) {
                    keys.add(PropertyKeyResult.refused(key, "no value was supplied for this key"));
                    anyRefused = true;
                    continue;
                }
                if (current.getProperty(key) == null) {
                    keys.add(PropertyKeyResult.refused(key, "server.properties does not define this key"));
                    anyRefused = true;
                    continue;
                }
                PropertiesText view = PropertiesText.decode(buffer);
                List<Definition> definitions = view.definitionsOf(key);
                if (definitions.size() > 1) {
                    keys.add(PropertyKeyResult.refused(key,
                            "the key is defined on more than one line (lines " + lineNumbers(definitions) + ")"));
                    anyRefused = true;
                    continue;
                }
                if (definitions.isEmpty()) {
                    keys.add(PropertyKeyResult.refused(key, "the line defining the key cannot be located"));
                    anyRefused = true;
                    continue;
                }
                Definition definition = definitions.get(0);
                if (definition.continued) {
                    keys.add(PropertyKeyResult.refused(key, "the key's definition continues onto the next line (line "
                            + (definition.line + 1) + ")"));
                    anyRefused = true;
                    continue;
                }
                byte[] next;
                Properties after;
                try {
                    next = view.replaceValue(definition, value);
                    after = PropertiesText.decode(next).parse();
                } catch (CharacterCodingException unencodable) {
                    keys.add(PropertyKeyResult.refused(key,
                            "the new value cannot be encoded in the file's character encoding"));
                    anyRefused = true;
                    continue;
                } catch (IllegalArgumentException malformedEscape) {
                    keys.add(PropertyKeyResult.refused(key, "the edited file would not parse"));
                    anyRefused = true;
                    continue;
                }
                String failure = view.verify(next, definition, key, value, current);
                if (failure != null) {
                    keys.add(PropertyKeyResult.refused(key, failure));
                    anyRefused = true;
                    continue;
                }
                buffer = next;
                current = after;
                anyApplied = true;
                keys.add(PropertyKeyResult.applied(key));
            }

            if (!anyApplied) {
                return PropertiesUpdateResult.partial(keys, currentVersion);
            }
            if (!Arrays.equals(buffer, original.bytes)) {
                try {
                    AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(path, buffer);
                    if (!staged.commitIfUnchanged(original.bytes)) {
                        return PropertiesUpdateResult.fileChanged();
                    }
                } catch (IOException failed) {
                    LOGGER.log(Level.WARNING, "server.properties " + propsFile.getAbsolutePath()
                            + " was not updated: " + failed.getClass().getSimpleName());
                    return PropertiesUpdateResult.refused("server.properties could not be written");
                }
            }
            String newVersion = versionOf(buffer);
            return anyRefused
                    ? PropertiesUpdateResult.partial(keys, newVersion)
                    : PropertiesUpdateResult.applied(keys, newVersion);
        }
    }

    /**
     * The contract's RUNTIME_CHECKS {@code file-content} rule over {@code server.properties}: within
     * the connection's limit, valid UTF-8 (the manager falls back to ISO-8859-1 for the server's own
     * reading, but the panel only ever writes UTF-8) and no C0 control character other than TAB, LF
     * and CR, and no DEL.
     */
    private static boolean isEditableContent(byte[] bytes, PropertiesText text, int maxContentBytes) {
        if (bytes.length > maxContentBytes || !StandardCharsets.UTF_8.equals(text.charset)) {
            return false;
        }
        for (int i = 0; i < text.text.length(); i++) {
            char character = text.text.charAt(i);
            boolean allowed = character == '\t' || character == '\n' || character == '\r';
            if (!allowed && (character < 0x20 || character == 0x7F)) {
                return false;
            }
        }
        return true;
    }

    private static String versionOf(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unattainable) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", unattainable);
        }
    }

    /**
     * One typed read of {@code server.properties} for the protocol 2.0.0 panel, as returned by
     * {@link #readVersioned(int)}.
     */
    @ApiStatus.Internal
    public static final class PropertiesSnapshot {
        /** Why {@link #readVersioned(int)} did or did not produce values. */
        public enum Outcome {
            /** Values, writable keys and version describe one file state. */
            OK,
            /** The server root holds no server.properties. */
            NOT_FOUND,
            /** The file breaks the contract's file-content rule, or the server could not parse it. */
            FILE_NOT_EDITABLE,
            /** The file exists but could not be read. */
            UNREADABLE
        }

        private final Outcome outcome;
        private final Map<String, String> values;
        private final List<String> writable;
        private final String version;

        private PropertiesSnapshot(Outcome outcome, Map<String, String> values, List<String> writable,
                                   String version) {
            this.outcome = outcome;
            this.values = values;
            this.writable = writable;
            this.version = version;
        }

        private static PropertiesSnapshot ok(Map<String, String> values, List<String> writable, String version) {
            return new PropertiesSnapshot(Outcome.OK, Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                    writable, version);
        }

        private static PropertiesSnapshot notFound() {
            return new PropertiesSnapshot(Outcome.NOT_FOUND, Collections.<String, String>emptyMap(),
                    Collections.<String>emptyList(), null);
        }

        private static PropertiesSnapshot fileNotEditable() {
            return new PropertiesSnapshot(Outcome.FILE_NOT_EDITABLE, Collections.<String, String>emptyMap(),
                    Collections.<String>emptyList(), null);
        }

        private static PropertiesSnapshot unreadable() {
            return new PropertiesSnapshot(Outcome.UNREADABLE, Collections.<String, String>emptyMap(),
                    Collections.<String>emptyList(), null);
        }

        /** @return whether the read produced values, and if not, why */
        public Outcome getOutcome() {
            return outcome;
        }

        /** @return the readable keys present in the file with their raw text values, in contract order */
        public Map<String, String> getValues() {
            return values;
        }

        /** @return the writable key set the panel may offer as editable */
        public List<String> getWritable() {
            return writable;
        }

        /** @return the SHA-256 of the bytes the values were read from, or {@code null} without values */
        public String getVersion() {
            return version;
        }
    }

    /**
     * The per-key result of {@link #updateWithBaseVersion(String, Map)}.
     */
    @ApiStatus.Internal
    public static final class PropertiesUpdateResult {
        /** The overall outcome of one update. */
        public enum Outcome {
            /** Every requested key was written. */
            APPLIED,
            /** At least one key was refused or absent; the accepted keys were written. */
            PARTIAL,
            /** The file no longer hashed to the base version; nothing was written. */
            FILE_CHANGED,
            /** The server root holds no server.properties. */
            NOT_FOUND,
            /** The file could not be read or written; nothing was written. */
            REFUSED
        }

        private final Outcome outcome;
        private final List<PropertyKeyResult> keys;
        private final String version;
        private final String reason;

        private PropertiesUpdateResult(Outcome outcome, List<PropertyKeyResult> keys, String version, String reason) {
            this.outcome = outcome;
            this.keys = keys == null ? Collections.<PropertyKeyResult>emptyList() : Collections.unmodifiableList(keys);
            this.version = version;
            this.reason = reason;
        }

        private static PropertiesUpdateResult applied(List<PropertyKeyResult> keys, String version) {
            return new PropertiesUpdateResult(Outcome.APPLIED, keys, version, null);
        }

        private static PropertiesUpdateResult partial(List<PropertyKeyResult> keys, String version) {
            return new PropertiesUpdateResult(Outcome.PARTIAL, keys, version, null);
        }

        private static PropertiesUpdateResult fileChanged() {
            return new PropertiesUpdateResult(Outcome.FILE_CHANGED, null, null, null);
        }

        private static PropertiesUpdateResult notFound() {
            return new PropertiesUpdateResult(Outcome.NOT_FOUND, null, null, null);
        }

        private static PropertiesUpdateResult refused(String reason) {
            return new PropertiesUpdateResult(Outcome.REFUSED, null, null, reason);
        }

        /** @return what happened overall */
        public Outcome getOutcome() {
            return outcome;
        }

        /** @return one result per requested key, in request order; empty when nothing was attempted */
        public List<PropertyKeyResult> getKeys() {
            return keys;
        }

        /** @return the SHA-256 of the bytes now on disk, or {@code null} when nothing was written */
        public String getVersion() {
            return version;
        }

        /** @return a safe refusal reason for {@link Outcome#REFUSED}, or {@code null} */
        public String getReason() {
            return reason;
        }
    }

    /**
     * What happened to one key of a protocol 2.0.0 properties update.
     */
    @ApiStatus.Internal
    public static final class PropertyKeyResult {
        private final String key;
        private final boolean ok;
        private final String message;

        private PropertyKeyResult(String key, boolean ok, String message) {
            this.key = key;
            this.ok = ok;
            this.message = message;
        }

        private static PropertyKeyResult applied(String key) {
            return new PropertyKeyResult(key, true, null);
        }

        private static PropertyKeyResult refused(String key, String message) {
            return new PropertyKeyResult(key, false, message);
        }

        /** @return the key this result is about */
        public String getKey() {
            return key;
        }

        /** @return whether the key's new value is now in the file */
        public boolean isOk() {
            return ok;
        }

        /** @return why the key was not written, or {@code null} when it was */
        public String getMessage() {
            return message;
        }
    }

    /**
     * Why this exists next to {@link #setProperty(String, String)}: the boolean is lossy.
     * A {@code false} could mean "the key is not on the whitelist", "the running server
     * server's server.properties does not have the key", "there is no server.properties to write to", or "the write
     * itself failed" — four situations with four different fixes, collapsed into one value.
     * The batch path has to tell the caller which one happened, so the real outcome is
     * produced here and {@code setProperty} keeps its original contract by narrowing it.
     * <p>
     * The write changes only the line defining {@code key} (see the class description for why nothing else can
     * change); a refusal carries its reason.
     */
    @SuppressWarnings("PMD.NPathComplexity") // Each refusal is checked and reported separately before the one-line write.
    private WriteResult writeProperty(String key, String value) {
        if (!SAFE_KEYS.contains(key)) return WriteResult.of(WriteOutcome.REJECTED);

        File propsFile = new File(serverRoot, FILE_NAME);
        if (!propsFile.exists()) return WriteResult.of(WriteOutcome.FAILED);

        synchronized (WRITE_LOCK) {
            Path path = propsFile.toPath();
            PropertiesText read;
            Properties props;
            try {
                read = PropertiesText.read(path);
                props = read.parse();
            } catch (IOException e) {
                return WriteResult.of(WriteOutcome.FAILED);
            } catch (IllegalArgumentException malformedEscape) {
                return refuse(path, key, "the file cannot be parsed (a malformed \\u escape)");
            }

            // D-15 / SAFE_KEYS issue: SAFE_KEYS is a ceiling across every Paper version this
            // framework supports (plugin.yml declares api-version: 1.19), not a promise that every
            // key exists on the version actually running. Writing a key Paper does not read is
            // silently ignored by the platform, so the operator must be told before it happens --
            // not after a "success" response that never took effect.
            if (props.getProperty(key) == null) {
                return WriteResult.of(WriteOutcome.NOT_PRESENT_ON_THIS_SERVER);
            }

            List<Definition> definitions = read.definitionsOf(key);
            if (definitions.size() > 1) {
                return refuse(path, key, "the key is defined on more than one line (lines " + lineNumbers(definitions) + ")");
            }
            if (definitions.isEmpty()) {
                return refuse(path, key, "the line defining the key cannot be located");
            }
            Definition definition = definitions.get(0);
            if (definition.continued) {
                return refuse(path, key, "the key's definition continues onto the next line (line "
                        + (definition.line + 1) + ")");
            }

            byte[] next;
            try {
                next = read.replaceValue(definition, value);
            } catch (CharacterCodingException unencodable) {
                return refuse(path, key, "the new value cannot be encoded in the file's character encoding");
            }
            String failure = read.verify(next, definition, key, value, props);
            if (failure != null) {
                return refuse(path, key, failure);
            }
            if (Arrays.equals(next, read.bytes)) {
                return WriteResult.of(WriteOutcome.WRITTEN);
            }
            try {
                AtomicConfigWriter.StagedWrite staged = AtomicConfigWriter.stage(path, next);
                if (!staged.commitIfUnchanged(read.bytes)) {
                    return refuse(path, key, "the file changed while the edit was being prepared");
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "server.properties " + path.toAbsolutePath() + " was not written for key "
                        + key + ": " + e.getClass().getSimpleName());
                return WriteResult.of(WriteOutcome.FAILED);
            }
            return WriteResult.of(WriteOutcome.WRITTEN);
        }
    }

    private static WriteResult refuse(Path path, String key, String reason) {
        // Values are deliberately omitted.
        LOGGER.warning("server.properties " + path.toAbsolutePath() + " was not written: " + reason + ". Key: " + key
                + ". The file is unchanged.");
        return new WriteResult(WriteOutcome.FAILED, "server.properties was not written: " + reason);
    }

    private static String lineNumbers(List<Definition> definitions) {
        List<String> numbers = new ArrayList<>();
        for (Definition definition : definitions) {
            numbers.add(String.valueOf(definition.line + 1));
        }
        return String.join(", ", numbers);
    }

    /** An outcome, and for a refusal the reason (no value). */
    private static final class WriteResult {
        private final WriteOutcome outcome;
        private final String reason;

        private WriteResult(WriteOutcome outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }

        static WriteResult of(WriteOutcome outcome) {
            return new WriteResult(outcome, null);
        }

        String describe() {
            return reason != null ? reason : describeOutcome(outcome);
        }
    }

    /** One physical line that defines a key, located with the {@link Properties} grammar. */
    private static final class Definition {
        /** Physical line index, 0-based. */
        private final int line;
        /** Whether the logical line continues onto following physical lines. */
        private final boolean continued;
        /** Offset in the decoded text where the value text starts. */
        private final int valueStart;
        /** Offset in the decoded text where the line's content ends (before its terminator). */
        private final int contentEnd;
        /** Whether the key runs to the end of the line, with no separator at all. */
        private final boolean bareKey;

        Definition(int line, boolean continued, int valueStart, int contentEnd, boolean bareKey) {
            this.line = line;
            this.continued = continued;
            this.valueStart = valueStart;
            this.contentEnd = contentEnd;
            this.bareKey = bareKey;
        }
    }

    /**
     * The file's bytes and its text as the server decodes them: strict UTF-8, or ISO-8859-1 when the bytes are not
     * valid UTF-8 (Paper 1.21.11 {@code Settings#loadFromFile}, measured from the server jar). Every line break is a
     * single byte in both encodings, so lines split the same way in the bytes and in the text.
     */
    private static final class PropertiesText {
        private final byte[] bytes;
        private final String text;
        private final Charset charset;

        private PropertiesText(byte[] bytes, String text, Charset charset) {
            this.bytes = bytes;
            this.text = text;
            this.charset = charset;
        }

        static PropertiesText read(Path path) throws IOException {
            return decode(Files.readAllBytes(path));
        }

        static PropertiesText decode(byte[] bytes) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                return new PropertiesText(bytes, text, StandardCharsets.UTF_8);
            } catch (CharacterCodingException notUtf8) {
                return new PropertiesText(bytes, new String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
            }
        }

        /** The table the server reads; {@link IllegalArgumentException} for a malformed <code>&#92;u</code> escape, as the server. */
        Properties parse() {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(text));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return properties;
        }

        /** Physical lines as {@code [start, contentEnd, end]} offsets into {@link #text}. */
        List<int[]> lines() {
            List<int[]> lines = new ArrayList<>();
            int start = 0;
            int length = text.length();
            int i = 0;
            while (i < length) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') {
                    int end = c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
                    lines.add(new int[]{start, i, end});
                    start = end;
                    i = end;
                } else {
                    i++;
                }
            }
            if (start < length) {
                lines.add(new int[]{start, length, length});
            }
            return lines;
        }

        /** Every logical line whose key is {@code key}, with the grammar of {@link Properties#load(java.io.Reader)}. */
        List<Definition> definitionsOf(String key) {
            List<Definition> found = new ArrayList<>();
            List<int[]> lines = lines();
            int i = 0;
            while (i < lines.size()) {
                int[] line = lines.get(i);
                int first = skipWhitespace(line[0], line[1]);
                if (first == line[1] || text.charAt(first) == '#' || text.charAt(first) == '!') {
                    i++;
                    continue;
                }
                StringBuilder logical = new StringBuilder(text.substring(first, line[1]));
                int last = i;
                while (endsWithOddBackslashes(logical)) {
                    logical.setLength(logical.length() - 1);
                    if (last + 1 >= lines.size()) {
                        break;
                    }
                    last++;
                    int[] next = lines.get(last);
                    logical.append(text, skipWhitespace(next[0], next[1]), next[1]);
                }
                int[] split = splitKey(logical);
                if (key.equals(unescape(logical.substring(0, split[0])))) {
                    found.add(new Definition(i, last > i, first + split[1], line[1], first + split[0] == line[1]));
                }
                i = last + 1;
            }
            return found;
        }

        /** The new bytes: only the value text of {@code definition}'s line replaced, re-encoded in the file's charset. */
        byte[] replaceValue(Definition definition, String value) throws CharacterCodingException {
            String replaced = text.substring(0, definition.valueStart) + (definition.bareKey ? "=" : "")
                    + escape(value, StandardCharsets.UTF_8.equals(charset)) + text.substring(definition.contentEnd);
            ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(replaced));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        }

        /**
         * Why {@code next} must not be written, or {@code null}: every line other than the edited one keeps its bytes,
         * and the server's own reading of {@code next} returns {@code value} for {@code key} and every other key
         * unchanged.
         */
        String verify(byte[] next, Definition definition, String key, String value, Properties before) {
            List<byte[]> left = byteLines(bytes);
            List<byte[]> right = byteLines(next);
            if (left.size() != right.size()) {
                return "the edit would change the number of lines";
            }
            for (int i = 0; i < left.size(); i++) {
                if (i != definition.line && !Arrays.equals(left.get(i), right.get(i))) {
                    return "the edit would change line " + (i + 1);
                }
            }
            Properties after;
            try {
                after = decode(next).parse();
            } catch (IllegalArgumentException e) {
                return "the edited file would not parse";
            }
            if (!value.equals(after.getProperty(key))) {
                return "the server would not read the new value back exactly";
            }
            Properties others = new Properties();
            others.putAll(before);
            others.remove(key);
            Properties othersAfter = new Properties();
            othersAfter.putAll(after);
            othersAfter.remove(key);
            if (!others.equals(othersAfter)) {
                return "the edit would change how the server reads other keys";
            }
            return null;
        }

        private int skipWhitespace(int from, int to) {
            int i = from;
            while (i < to && isWhitespace(text.charAt(i))) {
                i++;
            }
            return i;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static boolean endsWithOddBackslashes(CharSequence line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
    }

    /**
     * Where the key ends and the value starts in one logical line, exactly as {@code Properties#load0} decides: the
     * key ends at the first unescaped {@code =}, {@code :} or whitespace; then whitespace, at most one {@code =} or
     * {@code :}, and whitespace are skipped.
     *
     * @return {@code [keyLength, valueStart]}
     */
    private static int[] splitKey(CharSequence line) {
        int limit = line.length();
        int keyLength = 0;
        int valueStart = limit;
        boolean hasSeparator = false;
        boolean precedingBackslash = false;
        while (keyLength < limit) {
            char c = line.charAt(keyLength);
            if ((c == '=' || c == ':') && !precedingBackslash) {
                valueStart = keyLength + 1;
                hasSeparator = true;
                break;
            } else if (isWhitespace(c) && !precedingBackslash) {
                valueStart = keyLength + 1;
                break;
            }
            precedingBackslash = c == '\\' && !precedingBackslash;
            keyLength++;
        }
        while (valueStart < limit) {
            char c = line.charAt(valueStart);
            if (!isWhitespace(c)) {
                if (!hasSeparator && (c == '=' || c == ':')) {
                    hasSeparator = true;
                } else {
                    break;
                }
            }
            valueStart++;
        }
        return new int[]{keyLength, valueStart};
    }

    /** {@code Properties#loadConvert}: <code>&#92;uXXXX</code>, <code>&#92;t &#92;n &#92;r &#92;f</code>, and <code>&#92;x</code> for any other x. */
    private static String unescape(String escaped) {
        StringBuilder out = new StringBuilder(escaped.length());
        int i = 0;
        while (i < escaped.length()) {
            char c = escaped.charAt(i++);
            if (c != '\\' || i >= escaped.length()) {
                out.append(c);
                continue;
            }
            c = escaped.charAt(i++);
            if (c == 'u') {
                if (i + 4 > escaped.length()) {
                    throw new IllegalArgumentException("Malformed \\uxxxx encoding.");
                }
                out.append((char) Integer.parseInt(escaped.substring(i, i + 4), 16));
                i += 4;
            } else if (c == 't') {
                out.append('\t');
            } else if (c == 'r') {
                out.append('\r');
            } else if (c == 'n') {
                out.append('\n');
            } else if (c == 'f') {
                out.append('\f');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@code Properties#saveConvert} for a value: a leading space, <code>&#92;</code>, {@code = : # !} and the four control
     * escapes are escaped; any other control character, a lone surrogate, and - in a file the server reads as
     * ISO-8859-1 - every character above {@code ~} is written as <code>&#92;uXXXX</code>, which the reader decodes in both
     * charsets. In a UTF-8 file other characters are written as they are, as the server itself writes them.
     */
    private static String escape(String value, boolean utf8) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ' ':
                    out.append(i == 0 ? "\\ " : " ");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '=':
                case ':':
                case '#':
                case '!':
                    out.append('\\').append(c);
                    break;
                default:
                    boolean pairedSurrogate = Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))
                            || Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
                    boolean loneSurrogate = Character.isSurrogate(c) && !pairedSurrogate;
                    if (c < 0x20 || c == 0x7f || loneSurrogate || (c > 0x7e && !utf8)) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** Splits bytes into lines, each with its terminator (LF, CR or CRLF). */
    private static List<byte[]> byteLines(byte[] bytes) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < bytes.length) {
            if (bytes[i] == '\n' || bytes[i] == '\r') {
                int end = bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n' ? i + 2 : i + 1;
                lines.add(Arrays.copyOfRange(bytes, start, end));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        if (start < bytes.length) {
            lines.add(Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return lines;
    }

    /**
     * One line naming why a single-key write did not happen, or {@code null} when it
     * {@link WriteOutcome#WRITTEN did}. Exposed through {@link #handleSet(JsonObject)}'s
     * response so a panel operator is told the reason, not just {@code success: false}.
     */
    private static String describeOutcome(WriteOutcome outcome) {
        switch (outcome) {
            case WRITTEN:
                return null;
            case REJECTED:
                return "Key is not in the allowed list";
            case NOT_PRESENT_ON_THIS_SERVER:
                // #473: states only what is known. A key absent from the file may be one this
                // server version does not have, or one the file simply omits (Paper then uses its
                // built-in default); nothing tells the two apart -- the Bukkit Server API lists no
                // server.properties keys (measured on paper-api 1.21.11: no property-listing method
                // on org.bukkit.Server) and the version's own key set lives in version-specific
                // server internals.
                return "This key is not in this server's server.properties";
            default:
                return "Failed to read or write server.properties";
        }
    }

    /** What actually happened to one key. */
    private enum WriteOutcome {
        /** Written to disk. */
        WRITTEN,
        /** Not on {@link #SAFE_KEYS}; never attempted. */
        REJECTED,
        /**
         * On {@link #SAFE_KEYS} (a ceiling across every Paper version this framework supports),
         * but the running server's own {@code server.properties} has no such key -- either an
         * older or newer Paper version than the one that added it, or a file that omits it; the
         * two cannot be told apart (#473). Writing it anyway could be silently ignored by the
         * platform, so this is refused rather than written (D-15, SAFE_KEYS issue).
         */
        NOT_PRESENT_ON_THIS_SERVER,
        /** On the whitelist and present in the file, but reading or writing the file failed. */
        FAILED
    }

    /**
     * Outcome of one {@code set_all} batch.
     * <p>
     * {@link #isSuccess()} means <em>every key the caller asked for is now on disk</em>,
     * which is the batch analogue of what {@code action: "set"} already reports for a
     * single key. Keys carrying an explicit JSON {@code null} are not part of that
     * promise — a {@code null} reads as "leave this one alone", so it is recorded in
     * {@link #getSkipped()} for visibility but never fails the batch.
     */
    @ApiStatus.Internal
    public static final class SetAllResult {
        private final List<String> updated;
        private final List<String> rejected;
        private final List<String> failed;
        private final List<String> skipped;
        private final List<String> malformed;
        private final List<String> notPresentOnServer;
        private final Map<String, String> failureReasons;

        /**
         * Public so callers outside this package can build one. Each list is copied before
         * being wrapped — {@code unmodifiableList} is a view, so wrapping the caller's list
         * directly would leave this "immutable" object mutable through the original reference.
         *
         * @param notPresentOnServer keys allowlisted but absent from THIS server's own
         *        {@code server.properties} (D-15) — Gate-2 finding: kept distinct from
         *        {@code failed} so the response can say "this key is not in this server's server.properties"
         *        rather than making it indistinguishable from a genuine read/write I/O error,
         *        matching what a single-key {@code action: "set"} already reports via
         *        {@link #describeOutcome(WriteOutcome)}.
         */
        public SetAllResult(List<String> updated, List<String> rejected, List<String> failed,
                            List<String> skipped, List<String> malformed, List<String> notPresentOnServer) {
            this(updated, rejected, failed, skipped, malformed, notPresentOnServer, Collections.<String, String>emptyMap());
        }

        /**
         * As the six-list constructor, with the reason a failed key was refused (UltiKits/UltiTools-Reborn#607: a key
         * defined on more than one line, or over continuation lines, is not written). Reasons never carry a value.
         *
         * @param failureReasons failed key to the reason it was refused; keys without a reason failed on I/O
         * @since 6.3.0
         */
        public SetAllResult(List<String> updated, List<String> rejected, List<String> failed,
                            List<String> skipped, List<String> malformed, List<String> notPresentOnServer,
                            Map<String, String> failureReasons) {
            this.failureReasons = Collections.unmodifiableMap(new LinkedHashMap<>(failureReasons));
            this.updated = Collections.unmodifiableList(new ArrayList<>(updated));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(rejected));
            this.failed = Collections.unmodifiableList(new ArrayList<>(failed));
            this.skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
            this.malformed = Collections.unmodifiableList(new ArrayList<>(malformed));
            this.notPresentOnServer = Collections.unmodifiableList(new ArrayList<>(notPresentOnServer));
        }

        /** Keys written to disk. */
        public List<String> getUpdated() { return updated; }

        /** Keys refused because they are not on the whitelist. */
        public List<String> getRejected() { return rejected; }

        /** Whitelisted keys whose write failed. */
        public List<String> getFailed() { return failed; }

        /**
         * Why a failed key was refused, for the keys that were refused rather than failing on I/O.
         *
         * @return failed key to reason (never a value)
         * @since 6.3.0
         */
        public Map<String, String> getFailureReasons() { return failureReasons; }

        /** Keys whose value was an explicit JSON null. */
        public List<String> getSkipped() { return skipped; }

        /** Keys whose value was not a JSON primitive. */
        public List<String> getMalformed() { return malformed; }

        /**
         * Keys on {@link #SAFE_KEYS} but absent from THIS server's own {@code server.properties}
         * (D-15). Distinct from {@link #getFailed()} — writing one of these was never attempted;
         * it is a version mismatch, not an I/O failure.
         */
        public List<String> getNotPresentOnServer() { return notPresentOnServer; }

        /** Every requested key was written. */
        public boolean isSuccess() {
            return rejected.isEmpty() && failed.isEmpty() && malformed.isEmpty() && notPresentOnServer.isEmpty();
        }

        /**
         * One line naming what went wrong, for a log record or an error response.
         * Returns {@code null} when nothing went wrong.
         * <p>
         * The categories stay separate because they need different actions: a rejected key means
         * stop asking for it, a failed key means look at the disk, a malformed key means fix the
         * payload, and a not-present-on-server key means this Paper version does not have it.
         * Collapsing them into one label would send the reader looking in the wrong place —
         * which is the failure mode this whole change exists to remove.
         */
        public String describeFailure() {
            return describe(true);
        }

        /**
         * As {@link #describeFailure()}, but without the failed keys that carry a refusal reason: the manager has
         * already logged each of those once, with its reason, when it refused the key (17-66 review R66-I1), so the
         * batch summary line names only what nothing else has reported. Returns {@code null} when nothing is left.
         */
        String describeUnreportedFailure() {
            return describe(false);
        }

        @SuppressWarnings("PMD.NPathComplexity") // Four independent outcome lists, each named only when non-empty.
        private String describe(boolean includeRefusedWithReason) {
            if (isSuccess()) return null;
            List<String> named = new ArrayList<>();
            for (String key : failed) {
                String reason = failureReasons.get(key);
                if (reason == null) {
                    named.add(key);
                } else if (includeRefusedWithReason) {
                    named.add(key + " (" + reason + ")");
                }
            }
            if (rejected.isEmpty() && named.isEmpty() && malformed.isEmpty() && notPresentOnServer.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(FrameworkText.text("server.properties 批量设置未完全生效"));
            if (!rejected.isEmpty()) {
                sb.append(FrameworkText.text("；不在白名单因而被拒的键: ")).append(String.join(", ", rejected));
            }
            if (!named.isEmpty()) {
                sb.append(FrameworkText.text("；写入失败的键: ")).append(String.join(", ", named));
            }
            if (!malformed.isEmpty()) {
                sb.append(FrameworkText.text("；值不是字符串或数字因而无法写入的键: ")).append(String.join(", ", malformed));
            }
            if (!notPresentOnServer.isEmpty()) {
                sb.append(FrameworkText.text("；本服务器版本没有的键: ")).append(String.join(", ", notPresentOnServer));
            }
            return sb.toString();
        }
    }

    /**
     * Handle WebSocket message for server properties operations.
     *
     * @param data the message data
     * @deprecated Use readVersioned(int) and updateWithBaseVersion(String, Map), which the protocol
     *     2.0.0 session calls; this v1 handler replies only over the client handed to
     *     setWebSocketClient. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    public void handleServerProperties(JsonObject data) {
        if (data == null) return;

        String action = data.has("action") && !data.get("action").isJsonNull()
            ? data.get("action").getAsString() : "get";

        if ("get".equals(action)) {
            handleGet();
        } else if ("set".equals(action)) {
            handleSet(data);
        } else if ("set_all".equals(action)) {
            handleSetAll(data);
        }
    }

    private void handleGet() {
        Map<String, String> props = getSafeProperties();
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        JsonObject payload = new JsonObject();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            payload.addProperty(entry.getKey(), entry.getValue());
        }
        response.add("data", payload);
        sendResponse(response);
    }

    private void handleSet(JsonObject data) {
        String key = data.has("key") ? data.get("key").getAsString() : null;
        String value = data.has("value") ? data.get("value").getAsString() : null;
        if (key == null || value == null) return;

        WriteResult result = writeProperty(key, value);
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set");
        response.addProperty("success", result.outcome == WriteOutcome.WRITTEN);
        response.addProperty("key", key);
        String reason = result.describe();
        if (reason != null) {
            response.addProperty("reason", reason);
        }
        sendResponse(response);
    }

    private void handleSetAll(JsonObject data) {
        JsonObject values = data.has("values") && data.get("values").isJsonObject()
            ? data.getAsJsonObject("values") : null;
        if (values == null) return;

        applySetAll(values);
    }

    /**
     * Apply a batch of properties, report it over the socket, and hand the outcome back.
     * <p>
     * The return value is the point. {@code handleSetAll} does not need it — it only ever
     * builds a message — but {@code update_config} with
     * {@code fileName: server_properties} routes through here too, and its
     * {@code config_update_response} is supposed to distinguish "delivered" from
     * "took effect". Before this returned anything, that path had no way to learn the
     * answer and reported success unconditionally.
     *
     * @param values key → value, an explicit JSON null meaning "leave this key alone"
     * @return what happened to each key
     */
    public SetAllResult applySetAll(JsonObject values) {
        List<String> updated = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        List<String> notPresentOnServer = new ArrayList<>();
        Map<String, String> failureReasons = new LinkedHashMap<>();

        for (String key : values.keySet()) {
            JsonElement value = values.get(key);
            if (value.isJsonNull()) {
                skipped.add(key);
                continue;
            }
            // server.properties is a flat string table, so anything that is not a JSON
            // primitive is a malformed request rather than a value. Gson does not fail
            // uniformly on those, which is why the guard has to come first:
            // a JsonObject throws UnsupportedOperationException, an empty or multi-element
            // JsonArray throws IllegalStateException, and a single-element JsonArray does
            // not throw at all — it silently unwraps, so {"motd": ["Hello"]} would have
            // been written as motd=Hello. One malformed key used to abort the whole batch
            // before any response was built, which is the same "nobody can tell why"
            // shape this issue is about.
            if (!value.isJsonPrimitive()) {
                malformed.add(key);
                continue;
            }
            WriteResult written = writeProperty(key, value.getAsString());
            switch (written.outcome) {
                case WRITTEN:
                    updated.add(key);
                    break;
                case REJECTED:
                    rejected.add(key);
                    break;
                case NOT_PRESENT_ON_THIS_SERVER:
                    // Gate-2 finding: kept distinct from `failed` -- this is a version mismatch
                    // ("this server does not have this key"), not a read/write I/O error, and
                    // collapsing the two made a set_all response indistinguishable from an actual
                    // disk failure even though the single-key action: "set" path already reports
                    // the two separately via describeOutcome(WriteOutcome).
                    notPresentOnServer.add(key);
                    break;
                default:
                    failed.add(key);
                    if (written.reason != null) {
                        failureReasons.put(key, written.reason);
                    }
                    break;
            }
        }

        SetAllResult result = new SetAllResult(updated, rejected, failed, skipped, malformed, notPresentOnServer,
                failureReasons);
        warnIfIncomplete(result);
        sendResponse(buildSetAllResponse(result));
        return result;
    }

    private JsonObject buildSetAllResponse(SetAllResult result) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_properties_result");
        response.addProperty("action", "set_all");
        response.addProperty("success", result.isSuccess());
        // "updated" stays a number — the panel has always read this field; blocked keys
        // go through the newly added array fields.
        response.addProperty("updated", result.getUpdated().size());
        response.add("rejected", toJsonArray(result.getRejected()));
        response.add("failed", toJsonArray(result.getFailed()));
        response.add("skipped", toJsonArray(result.getSkipped()));
        response.add("malformed", toJsonArray(result.getMalformed()));
        response.add("notPresentOnServer", toJsonArray(result.getNotPresentOnServer()));
        // Additive (17-66 review R66-I1): why each failed key was refused, as the single-key "set" reply's
        // "reason" already says. A key that failed on I/O has no entry. Reasons never carry a value.
        JsonObject reasons = new JsonObject();
        for (Map.Entry<String, String> reason : result.getFailureReasons().entrySet()) {
            reasons.addProperty(reason.getKey(), reason.getValue());
        }
        response.add("failureReasons", reasons);
        return response;
    }

    private static JsonArray toJsonArray(List<String> keys) {
        JsonArray array = new JsonArray();
        for (String key : keys) {
            array.add(key);
        }
        return array;
    }

    /**
     * The response above is what the panel reads; this is what a server operator reads.
     * Both are needed: someone changing a setting that silently does not apply otherwise
     * finds nothing on either side.
     */
    private void warnIfIncomplete(SetAllResult result) {
        // A key refused with a reason was already logged once by refuse(); this line names only the rest.
        String failure = result.describeUnreportedFailure();
        if (failure == null) return;
        // This manager is constructed by initWebSocketManagers() in onEnable, so in
        // production the singleton is always ready by the time this runs; the null check
        // exists so a pure-file-logic unit test does not need to stand up a global
        // singleton first.
        UltiTools instance = UltiTools.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().log(Level.WARNING, failure);
        }
    }

    private void sendResponse(JsonObject response) {
        if (webSocketClient != null) {
            response.addProperty("serverId", webSocketClient.getServerId());
            webSocketClient.sendMessage(response);
        }
    }
}
