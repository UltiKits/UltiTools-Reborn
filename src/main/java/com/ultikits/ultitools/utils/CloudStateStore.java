package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.exceptions.DataAccessException;

/**
 * Persists the reliable-delivery state -- the {@link CloudOutbox} and the {@link CommandDedupeSet} -- in
 * {@code <server root>/.ultikits/cloud-outbox.json} (D-15, D-17, S-15), so unacknowledged events and recently seen
 * command ids survive a restart.
 * <p>
 * <b>Why this write path cannot overwrite operator content (D-31).</b> {@code cloud-outbox.json} is framework-owned,
 * replayable state, not operator configuration: only this class writes it, no shipped file or documentation asks an
 * operator to edit it, and it lives in {@code .ultikits/} beside {@code credentials.json}, outside every default
 * editable root of the remote file surface. Its content is buffered events and command ids, never an operator's
 * setting, and losing it costs at most the buffered player join/leave events (a documented limitation).
 * <p>
 * <b>Format.</b> {@code {"version":1, "serverId":..., "events":[{"id","type","ts","payload"}], "dropped":N,
 * "commandIds":[{"id","seenAt"}]}}, with {@code ts} and {@code seenAt} in epoch milliseconds (contract answer 5).
 * Written through {@link OwnerOnlyAtomicFile}, the writer {@link CredentialStore} uses: owner-only on POSIX, forced
 * to disk, atomically moved into place.
 */
final class CloudStateStore {

    /** The state file's bare name, beside {@code credentials.json}. */
    static final String FILE_NAME = "cloud-outbox.json";

    /** The only format version this class reads and writes. */
    static final int FORMAT_VERSION = 1;

    private static final String TEMP_SUFFIX = ".tmp";
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
     */
    static Path defaultPath() {
        return CredentialStore.stateDirectory().resolve(FILE_NAME);
    }

    CloudOutbox outbox() {
        return outbox;
    }

    CommandDedupeSet dedupe() {
        return dedupe;
    }

    /**
     * Replaces the in-memory state with the file's content.
     *
     * @param expectedServerId the linked server's id
     */
    void load(String expectedServerId) {
        synchronized (WRITE_LOCK) {
            if (!Files.exists(file)) {
                outbox.restore(new ArrayList<>(), 0L);
                dedupe.restore(new ArrayList<>());
                return;
            }
            try {
                JsonObject root = JsonParser.parseString(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
                List<CloudOutbox.Entry> events = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("events")) {
                    JsonObject o = element.getAsJsonObject();
                    events.add(new CloudOutbox.Entry(o.get("id").getAsString(), o.get("type").getAsString(),
                            o.get("ts").getAsLong(), o.getAsJsonObject("payload")));
                }
                List<CommandDedupeSet.Seen> seen = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("commandIds")) {
                    JsonObject o = element.getAsJsonObject();
                    seen.add(new CommandDedupeSet.Seen(o.get("id").getAsString(), o.get("seenAt").getAsLong()));
                }
                outbox.restore(events, root.get("dropped").getAsLong());
                dedupe.restore(seen);
            } catch (IOException e) {
                throw new DataAccessException("Failed to read " + file, e);
            }
        }
    }

    /**
     * Writes the state if either structure changed since the last successful write.
     *
     * @param serverId the linked server's id
     * @return whether a write happened
     */
    boolean flushIfDirty(String serverId) {
        synchronized (WRITE_LOCK) {
            if (!outbox.isDirty() && !dedupe.isDirty()) {
                return false;
            }
            writeLocked(serverId);
            return true;
        }
    }

    /**
     * Writes the state unconditionally.
     *
     * @param serverId the linked server's id
     * @throws DataAccessException if the write fails; the previous file is left intact and the dirty flags stay
     *                             set, so the next flush retries
     */
    void flush(String serverId) {
        synchronized (WRITE_LOCK) {
            writeLocked(serverId);
        }
    }

    private void writeLocked(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        CloudOutbox.Snapshot events = outbox.snapshot();
        List<CommandDedupeSet.Seen> seen;
        long dedupeMod;
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
            seenArray.add(o);
        }
        root.add("commandIds", seenArray);

        String tempName = FILE_NAME + "." + Ulid.next() + TEMP_SUFFIX;
        try {
            OwnerOnlyAtomicFile.write(file, tempName, writer -> GSON.toJson(root, writer));
        } catch (IOException e) {
            throw new DataAccessException("Failed to write the UltiCloud state file " + file, e);
        }
        outbox.clearDirtyIfUnchanged(events.modCount());
        dedupe.clearDirtyIfUnchanged(dedupeMod);
    }
}
