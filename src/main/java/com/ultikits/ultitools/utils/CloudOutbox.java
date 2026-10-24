package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import com.google.gson.JsonObject;

/**
 * The bounded buffer of reliable events not yet acknowledged by the UltiCloud gateway (D-11, D-14, D-15). An event
 * gets its envelope id (a ULID) when it is enqueued, stays until an {@code ack { ids }} names it, and is resent after
 * every reconnect.
 * <p>
 * <b>Bounded.</b> The buffer never holds more than its capacity (default {@link #DEFAULT_CAPACITY}). Enqueueing into
 * a full buffer, lowering the capacity, or loading more entries than fit drops the oldest entries and adds them to
 * {@link #droppedSinceLastReport()}, which the session reports once as {@code outbox.overflow { dropped }}. The count
 * is reduced by the number actually reported ({@link #markOverflowReported(long)}), so drops that happen while a
 * report is in flight are kept for the next report.
 * <p>
 * <b>Thread safety.</b> Every method is {@code synchronized} on this buffer, and every eviction runs inside the same
 * synchronized mutation that caused it. The monitor is a leaf lock: no other lock is taken while it is held.
 * <p>
 * <b>Dirty tracking.</b> Every mutation sets a dirty flag and increments a modification count; {@link CloudStateStore}
 * clears the flag after a successful write only if nothing changed since its snapshot
 * ({@link #clearDirtyIfUnchanged(long)}), so a mutation that races a flush is never lost.
 */
final class CloudOutbox {

    /** The default capacity; {@code welcome.limits.outboxMax} overrides it per connection (D-13, plan 22-08). */
    static final int DEFAULT_CAPACITY = 1000;

    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private int capacity;
    private long dropped;
    private boolean dirty;
    private long modCount;

    /**
     * @param capacity the maximum number of entries, at least 1
     * @param clock    milliseconds since the Unix epoch, used for each event's {@code ts}
     */
    CloudOutbox(int capacity, LongSupplier clock) {
        this.capacity = requirePositive(capacity);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static int requirePositive(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        return capacity;
    }

    /**
     * Adds an event with a new ULID id and the current time, dropping the oldest entry when full.
     *
     * @param type    the envelope type, for example {@code player.join}
     * @param payload the event payload; copied, so later changes by the caller do not reach the buffer
     * @return the stored entry
     */
    synchronized Entry enqueue(String type, JsonObject payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Entry entry = new Entry(Ulid.next(), type, clock.getAsLong(), payload.deepCopy());
        entries.put(entry.id(), entry);
        evictToCapacity();
        touch();
        return entry;
    }

    /**
     * @return a snapshot of every unacknowledged entry, oldest first; later mutations do not change it
     */
    synchronized List<Entry> unacked() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Removes the entries the gateway acknowledged.
     *
     * @param ids the acknowledged envelope ids; unknown ids are ignored
     * @return the number of entries removed; the dirty flag changes only if it is positive
     */
    synchronized int ack(Collection<String> ids) {
        int removed = 0;
        for (String id : ids) {
            if (id != null && entries.remove(id) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            touch();
        }
        return removed;
    }

    /**
     * @return the number of entries dropped for lack of space since the last reported overflow
     */
    synchronized long droppedSinceLastReport() {
        return dropped;
    }

    /**
     * Records that an {@code outbox.overflow} carrying {@code reported} was sent. Only that many are subtracted, so
     * drops counted after the report was built remain for the next one.
     *
     * @param reported the {@code dropped} value that was sent
     */
    synchronized void markOverflowReported(long reported) {
        long next = Math.max(0L, dropped - Math.max(0L, reported));
        if (next != dropped) {
            dropped = next;
            touch();
        }
    }

    /**
     * Changes the capacity; a smaller capacity drops the oldest entries at once and counts them.
     *
     * @param newCapacity the new maximum, at least 1
     */
    synchronized void setCapacity(int newCapacity) {
        capacity = requirePositive(newCapacity);
        if (evictToCapacity()) {
            touch();
        }
    }

    /** Removes every entry and resets the dropped count. */
    synchronized void clear() {
        entries.clear();
        dropped = 0L;
        touch();
    }

    /**
     * Replaces the content with entries loaded from the state file. Entries beyond the capacity keep the newest and
     * add the rest to the dropped count. The buffer is marked dirty only when the loaded content had to be
     * trimmed and clean otherwise, so a load alone does not cause a rewrite.
     *
     * @param loaded        the entries, oldest first
     * @param loadedDropped the persisted dropped count
     */
    synchronized void restore(List<Entry> loaded, long loadedDropped) {
        entries.clear();
        for (Entry entry : loaded) {
            entries.put(entry.id(), entry);
        }
        dropped = Math.max(0L, loadedDropped);
        if (evictToCapacity()) {
            touch();
        } else {
            dirty = false;
            modCount++;
        }
    }

    /** @return whether anything changed since the last successful write */
    synchronized boolean isDirty() {
        return dirty;
    }

    /** Clears the dirty flag unconditionally. */
    synchronized void clearDirty() {
        dirty = false;
    }

    /** @return the modification count, for {@link #clearDirtyIfUnchanged(long)} */
    synchronized long modCount() {
        return modCount;
    }

    /**
     * Clears the dirty flag only if nothing changed since {@code expectedModCount} was read.
     *
     * @param expectedModCount the {@link #modCount()} captured with the persisted snapshot
     */
    synchronized void clearDirtyIfUnchanged(long expectedModCount) {
        if (modCount == expectedModCount) {
            dirty = false;
        }
    }

    /**
     * @return the entries, dropped count and modification count, read together under this buffer's monitor
     */
    synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(entries.values()), dropped, modCount);
    }

    private boolean evictToCapacity() {
        boolean evicted = false;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
            dropped++;
            evicted = true;
        }
        return evicted;
    }

    private void touch() {
        dirty = true;
        modCount++;
    }

    /** The persisted view of the buffer, read atomically by {@link #snapshot()}. */
    static final class Snapshot {
        private final List<Entry> entries;
        private final long dropped;
        private final long modCount;

        private Snapshot(List<Entry> entries, long dropped, long modCount) {
            this.entries = entries;
            this.dropped = dropped;
            this.modCount = modCount;
        }

        List<Entry> entries() {
            return entries;
        }

        long dropped() {
            return dropped;
        }

        long modCount() {
            return modCount;
        }
    }

    /** One unacknowledged reliable event. Immutable; {@link #payload()} returns a copy. */
    static final class Entry {
        private final String id;
        private final String type;
        private final long ts;
        private final JsonObject payload;

        /**
         * @param id      the envelope id, a ULID
         * @param type    the envelope type
         * @param ts      the event time in epoch milliseconds (contract answer 5)
         * @param payload the payload; stored as given, so callers pass a copy they no longer touch
         */
        Entry(String id, String type, long ts, JsonObject payload) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.ts = ts;
            this.payload = Objects.requireNonNull(payload, "payload");
        }

        String id() {
            return id;
        }

        String type() {
            return type;
        }

        long ts() {
            return ts;
        }

        /** @return a copy of the payload; changing it does not change the entry */
        JsonObject payload() {
            return payload.deepCopy();
        }
    }
}
