package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.exceptions.DataAccessException;

/**
 * Persists the reliable-delivery state -- the {@link CloudOutbox} and the {@link CommandDedupeSet} -- in
 * {@code <server root>/.ultikits/cloud-outbox.json} (D-15, D-17, S-15), so unacknowledged events and recently seen
 * command ids survive a restart.
 * <p>
 * <b>Why this write path cannot overwrite operator content (D-31).</b> {@code cloud-outbox.json} is framework-owned,
 * replayable state, not operator configuration: only this class writes it, no shipped file or documentation asks an
 * operator to edit it, and it lives in {@code .ultikits/} beside {@code credentials.json}, outside every default
 * editable root of the remote file surface. It is never read from or written to an operator-editable root. Its content
 * is buffered events and command ids, never an operator's setting, and losing it costs at most the buffered player
 * join/leave events (a documented limitation). That is also why an unreadable file is moved aside and replaced rather
 * than preserved in place, unlike {@link CredentialStore}'s credential file, which is an identity (S-15).
 * <p>
 * <b>Format.</b> {@code {"version":1, "serverId":..., "events":[{"id","type","ts","payload"}], "dropped":N,
 * "commandIds":[{"id","seenAt","receipt"?}]}}, with {@code ts} and {@code seenAt} in epoch milliseconds (contract answer
 * 5). {@code receipt} (plan 22-21, S-65) is optional: the JSON object the framework answered a
 * {@code player.link.completed} with, at most {@link CommandDedupeSet#MAX_RECEIPT_BYTES} of compact JSON, written only
 * for an id that has one. The file stays {@code version: 1}: a file written before receipts existed has no such field
 * and loads with no receipts, and a version 1 reader that predates them never sees one written by a store that
 * knew none.
 * Written through {@link OwnerOnlyAtomicFile}, the writer {@link CredentialStore} uses: owner-only on POSIX, forced
 * to disk, atomically moved into place from a temporary file {@code cloud-outbox.json.<ULID>.tmp} unique to that
 * write.
 * <p>
 * <b>Loading.</b> The file is read whole, up to {@link #MAX_FILE_BYTES}. A file that is larger, is not a JSON object
 * of {@link #FORMAT_VERSION}, or holds any invalid entry (an event id that is not a ULID, a missing type, a payload
 * that is not an object, a repeated id, a malformed command id) is unreadable as a whole: it is moved to
 * {@code cloud-outbox.json.unreadable} (replacing an older one), one WARNING names both paths, and the store starts
 * empty. A crafted or hand-edited file is not repaired entry by entry (the niche-case rule), with one exception: a
 * {@code receipt} that is not a JSON object, or is larger than {@link CommandDedupeSet#MAX_RECEIPT_BYTES}, is dropped
 * with one WARNING and its id kept, because losing a receipt costs only one more question to the module that owns the
 * player-link handler, while losing the id could run a command twice. A file written for
 * another server id is discarded with one INFO line. Payloads are never logged: they hold player names and UUIDs.
 * <p>
 * <b>Detaching.</b> {@link #detachAndDelete()} is used when the server identity is cleared (22-07): it deletes the
 * file and makes every later flush of this store a no-op, so the file cannot reappear for an identity that no longer
 * exists.
 */
final class CloudStateStore {

    /** The state file's bare name, beside {@code credentials.json}. */
    static final String FILE_NAME = "cloud-outbox.json";

    /** Appended to {@link #FILE_NAME} for the moved-aside copy of an unreadable file. */
    static final String UNREADABLE_SUFFIX = ".unreadable";

    /** The only format version this class reads and writes. */
    static final int FORMAT_VERSION = 1;

    /** The largest file {@link #load(String)} reads, 8 MiB; a larger one is unreadable. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final String TEMP_SUFFIX = ".tmp";
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,19}");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Serialises every load, flush, flushIfDirty and detachAndDelete of the state file, across every instance and
     * thread of this JVM: a replaced session's store and its successor's store point at the same file, and the
     * periodic flush, the session invalidation and the shutdown flush run on different threads (S-32).
     * <p>
     * Lock order: taken after any session monitor and before the {@link CloudOutbox} and {@link CommandDedupeSet}
     * monitors, which are leaf locks; never held while waiting for another thread.
     */
    private static final Object WRITE_LOCK = new Object();

    private final Path file;
    private final CloudOutbox outbox;
    private final CommandDedupeSet dedupe;

    /** Set by {@link #detachAndDelete()}; once set, no flush writes. Written under {@link #WRITE_LOCK}. */
    private volatile boolean detached;

    private volatile Runnable beforeMoveHook;
    private volatile Logger loggerOverride;

    /**
     * @param file   the state file, normally {@link #defaultPath()}
     * @param outbox the event buffer this store persists
     * @param dedupe the command-id set this store persists
     */
    CloudStateStore(Path file, CloudOutbox outbox, CommandDedupeSet dedupe) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dedupe = Objects.requireNonNull(dedupe, "dedupe");
    }

    /**
     * @return {@code <server root>/.ultikits/cloud-outbox.json}, beside the credential file
     * @throws DataAccessException if no live UltiTools instance (or test override) resolves the server root
     */
    static Path defaultPath() {
        return CredentialStore.stateDirectory().resolve(FILE_NAME);
    }

    /** Test-only: log through {@code logger} instead of the plugin's logger. */
    void setLoggerForTesting(Logger logger) {
        loggerOverride = logger;
    }

    /** Test-only: run {@code hook} between writing the temporary file and the atomic move of every flush. */
    void setBeforeMoveHookForTesting(Runnable hook) {
        beforeMoveHook = hook;
    }

    CloudOutbox outbox() {
        return outbox;
    }

    CommandDedupeSet dedupe() {
        return dedupe;
    }

    /** @return whether {@link #detachAndDelete()} has run on this store */
    boolean isDetached() {
        return detached;
    }

    /**
     * Replaces the in-memory state with the file's content. Deletes leftover {@code cloud-outbox.json.*.tmp} files
     * of an interrupted write first. An absent file loads as empty; an unreadable or foreign file loads as empty and
     * marks both structures dirty, so the next flush writes a fresh file. Does nothing on a detached store.
     *
     * @param expectedServerId the linked server's id
     */
    void load(String expectedServerId) {
        Objects.requireNonNull(expectedServerId, "expectedServerId");
        synchronized (WRITE_LOCK) {
            if (detached) {
                return;
            }
            deleteTempLeftovers();
            if (!Files.exists(file)) {
                outbox.restore(new ArrayList<>(), 0L);
                dedupe.restore(new ArrayList<>());
                return;
            }
            Parsed parsed;
            try {
                parsed = parse(readCapped());
            } catch (UnreadableException e) {
                moveAside(e.getMessage());
                startEmpty();
                return;
            }
            if (!parsed.serverId.equals(expectedServerId)) {
                log(Level.INFO, "The UltiCloud state file " + file + " was written for a different server identity; "
                        + "its buffered events and command ids were discarded and it will be rewritten.");
                startEmpty();
                return;
            }
            if (parsed.droppedReceipts > 0) {
                log(Level.WARNING, "The UltiCloud state file " + file + " held " + parsed.droppedReceipts
                        + " player-link receipt(s) that are not a JSON object of at most "
                        + CommandDedupeSet.MAX_RECEIPT_BYTES + " bytes; they were dropped and their ids kept. "
                        + "A redelivered link completion for such an id asks the module again.");
            }
            outbox.restore(parsed.events, parsed.dropped);
            dedupe.restore(parsed.seen);
        }
    }

    /**
     * Writes the state if either structure changed since the last successful write.
     *
     * @param serverId the linked server's id
     * @return whether a write happened; always {@code false} on a detached store
     * @throws DataAccessException if the write fails, as {@link #flush(String)}
     */
    boolean flushIfDirty(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        synchronized (WRITE_LOCK) {
            if (detached || (!outbox.isDirty() && !dedupe.isDirty())) {
                return false;
            }
            writeLocked(serverId);
            return true;
        }
    }

    /**
     * Writes the state unconditionally; does nothing on a detached store.
     *
     * @param serverId the linked server's id
     * @throws DataAccessException if the write fails; the previous file is left intact, no temporary file remains,
     *                             and the dirty flags stay set, so the next flush retries
     */
    void flush(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        synchronized (WRITE_LOCK) {
            if (detached) {
                return;
            }
            writeLocked(serverId);
        }
    }

    /**
     * Marks this store detached and deletes the state file, under the write lock: a flush that already holds the
     * lock finishes first, and every later flush of this store sees the flag and writes nothing (S-32). A failed
     * delete is logged; the store stays detached.
     *
     * @return whether the file existed
     */
    boolean detachAndDelete() {
        synchronized (WRITE_LOCK) {
            detached = true;
            try {
                return Files.deleteIfExists(file);
            } catch (IOException e) {
                log(Level.WARNING, "Could not delete the UltiCloud state file " + file + ": " + e
                        + ". Delete it by hand; it is no longer used.");
                return true;
            }
        }
    }

    private void startEmpty() {
        outbox.clear();
        dedupe.clear();
    }

    private void deleteTempLeftovers() {
        Path directory = file.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        String prefix = file.getFileName().toString() + ".";
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(TEMP_SUFFIX) && name.length() > prefix.length()
                        + TEMP_SUFFIX.length()) {
                    leftovers.add(entry);
                }
            }
        } catch (IOException e) {
            log(Level.FINE, "Could not list " + directory + " for leftover temporary files: " + e);
            return;
        }
        for (Path leftover : leftovers) {
            try {
                Files.deleteIfExists(leftover);
            } catch (IOException e) {
                log(Level.FINE, "Could not delete the leftover temporary file " + leftover + ": " + e);
            }
        }
    }

    private void moveAside(String reason) {
        Path aside = file.resolveSibling(file.getFileName().toString() + UNREADABLE_SUFFIX);
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            log(Level.WARNING, "The UltiCloud state file " + file + " could not be read (" + reason + "). It was "
                    + "moved to " + aside + " and the event buffer starts empty; buffered player join/leave events "
                    + "in it are not resent.");
        } catch (IOException e) {
            log(Level.WARNING, "The UltiCloud state file " + file + " could not be read (" + reason + "), and moving "
                    + "it to " + aside + " failed: " + e + ". It was left in place, the event buffer starts empty, "
                    + "and the next successful write replaces it.");
        }
    }

    private byte[] readCapped() throws UnreadableException {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new UnreadableException("larger than " + MAX_FILE_BYTES + " bytes");
            }
            try (InputStream in = Files.newInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_FILE_BYTES) {
                        throw new UnreadableException("larger than " + MAX_FILE_BYTES + " bytes");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new UnreadableException("reading it failed: " + e.getClass().getSimpleName());
        }
    }

    private static Parsed parse(byte[] bytes) throws UnreadableException {
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new UnreadableException("not a valid JSON document");
        }
        if (!rootElement.isJsonObject()) {
            throw new UnreadableException("not a JSON object");
        }
        JsonObject root = rootElement.getAsJsonObject();
        if (requireLong(root.get("version"), "version") != FORMAT_VERSION) {
            throw new UnreadableException("unsupported format version");
        }
        String serverId = requireString(root.get("serverId"), "serverId");
        long dropped = requireLong(root.get("dropped"), "dropped");
        if (dropped < 0) {
            throw new UnreadableException("a negative dropped count");
        }

        List<CloudOutbox.Entry> events = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        for (JsonElement element : requireArray(root.get("events"), "events")) {
            JsonObject event = requireObject(element, "an event entry");
            String id = requireUlid(event.get("id"), "an event id");
            if (!eventIds.add(id)) {
                throw new UnreadableException("a repeated event id");
            }
            String type = requireString(event.get("type"), "an event type");
            long ts = requireLong(event.get("ts"), "an event time");
            JsonObject payload = requireObject(event.get("payload"), "an event payload");
            events.add(new CloudOutbox.Entry(id, type, ts, payload));
        }

        List<CommandDedupeSet.Seen> seen = new ArrayList<>();
        Set<String> commandIds = new HashSet<>();
        int droppedReceipts = 0;
        for (JsonElement element : requireArray(root.get("commandIds"), "commandIds")) {
            JsonObject command = requireObject(element, "a command id entry");
            String id = requireUlid(command.get("id"), "a command id");
            if (!commandIds.add(id)) {
                throw new UnreadableException("a repeated command id");
            }
            long seenAt = requireLong(command.get("seenAt"), "a command time");
            JsonObject receipt = null;
            JsonElement stored = command.get("receipt");
            if (stored != null) {
                if (stored.isJsonObject() && CommandDedupeSet.fitsReceipt(stored.getAsJsonObject())) {
                    receipt = stored.getAsJsonObject();
                } else {
                    droppedReceipts++;
                }
            }
            seen.add(new CommandDedupeSet.Seen(id, seenAt, receipt));
        }
        return new Parsed(serverId, events, dropped, seen, droppedReceipts);
    }

    private static JsonArray requireArray(JsonElement element, String what) throws UnreadableException {
        if (element == null || !element.isJsonArray()) {
            throw new UnreadableException(what + " is missing or not an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement element, String what) throws UnreadableException {
        if (element == null || !element.isJsonObject()) {
            throw new UnreadableException(what + " is missing or not an object");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonElement element, String what) throws UnreadableException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isEmpty()) {
            throw new UnreadableException(what + " is missing or not a non-empty string");
        }
        return element.getAsString();
    }

    private static String requireUlid(JsonElement element, String what) throws UnreadableException {
        String value = requireString(element, what);
        if (!Ulid.isValid(value)) {
            throw new UnreadableException(what + " is not a ULID");
        }
        return value;
    }

    /** Accepts only an integer literal: Gson's lenient number conversion would turn {@code 1.5} into 1. */
    private static long requireLong(JsonElement element, String what) throws UnreadableException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new UnreadableException(what + " is missing or not a number");
        }
        String text = ((JsonPrimitive) element).getAsString();
        if (!INTEGER.matcher(text).matches()) {
            throw new UnreadableException(what + " is not an integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new UnreadableException(what + " is out of range");
        }
    }

    private void writeLocked(String serverId) {
        CloudOutbox.Snapshot events = outbox.snapshot();
        List<CommandDedupeSet.Seen> seen;
        long dedupeMod;
        // Same monitor as CommandDedupeSet's synchronized methods, so the list and its modification count match.
        synchronized (dedupe) {
            seen = dedupe.snapshot();
            dedupeMod = dedupe.modCount();
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("serverId", serverId);
        JsonArray eventArray = new JsonArray();
        for (CloudOutbox.Entry entry : events.entries()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", entry.id());
            o.addProperty("type", entry.type());
            o.addProperty("ts", entry.ts());
            o.add("payload", entry.payload());
            eventArray.add(o);
        }
        root.add("events", eventArray);
        root.addProperty("dropped", events.dropped());
        JsonArray seenArray = new JsonArray();
        for (CommandDedupeSet.Seen s : seen) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("seenAt", s.seenAt());
            if (s.receipt() != null) {
                o.add("receipt", s.receipt());
            }
            seenArray.add(o);
        }
        root.add("commandIds", seenArray);

        String tempName = file.getFileName().toString() + "." + Ulid.next() + TEMP_SUFFIX;
        try {
            OwnerOnlyAtomicFile.write(file, tempName, writer -> GSON.toJson(root, writer), beforeMoveHook);
        } catch (IOException e) {
            throw new DataAccessException("Failed to write the UltiCloud state file " + file, e);
        }
        outbox.clearDirtyIfUnchanged(events.modCount());
        dedupe.clearDirtyIfUnchanged(dedupeMod);
    }

    private void log(Level level, String message) {
        Logger override = loggerOverride;
        if (override != null) {
            override.log(level, message);
            return;
        }
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                instance.getLogger().log(level, message);
            }
        } catch (RuntimeException ignored) {
            // Best-effort logging only -- a diagnostic line must never mask the outcome it reports.
        }
    }

    /** The validated content of a state file. */
    private static final class Parsed {
        final String serverId;
        final List<CloudOutbox.Entry> events;
        final long dropped;
        final List<CommandDedupeSet.Seen> seen;
        final int droppedReceipts;

        Parsed(String serverId, List<CloudOutbox.Entry> events, long dropped, List<CommandDedupeSet.Seen> seen,
               int droppedReceipts) {
            this.serverId = serverId;
            this.events = events;
            this.dropped = dropped;
            this.seen = seen;
            this.droppedReceipts = droppedReceipts;
        }
    }

    /** A state file that cannot be used as a whole; the message names the reason, never the content. */
    private static final class UnreadableException extends Exception {
        private static final long serialVersionUID = 1L;

        UnreadableException(String reason) {
            super(reason, null, false, false);
        }
    }
}
