package com.ultikits.ultitools.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import com.google.gson.JsonObject;

/**
 * The ids of commands the UltiCloud gateway delivered recently, so a redelivered command is acknowledged but not run
 * twice (D-16, D-17). An id is remembered until either bound is reached first: the most recent
 * {@link #DEFAULT_DEDUPE_MAX} ids, or {@link #DEFAULT_DEDUPE_TTL_MS} after it was first seen. An id older than the TTL
 * (strictly more than the TTL ago) or pushed out by newer ids is first-seen again.
 * <p>
 * <b>Thread safety.</b> Every method is {@code synchronized} on this set and every eviction runs inside the mutation
 * that caused it; the monitor is a leaf lock. Dirty tracking matches {@link CloudOutbox}: a mutation sets the flag and
 * increments a modification count, and {@link CloudStateStore} clears the flag only if nothing changed since its
 * snapshot.
 * <p>
 * <b>Receipts (plan 22-21, S-59, S-65).</b> An id may carry one receipt: the result payload the framework answered a
 * {@code player.link.completed} with, so a redelivery is answered again from the record without asking the module.
 * A receipt lives and dies with its id -- it is evicted by the same count bound and expires with the same TTL -- and
 * is at most {@link #MAX_RECEIPT_BYTES} of compact JSON; a larger one is not recorded.
 */
final class CommandDedupeSet {

    /** The default number of remembered ids; {@code welcome.limits.commandDedupeMax} overrides it (D-13, 22-08). */
    static final int DEFAULT_DEDUPE_MAX = 1000;

    /** The default memory of one id, 24 h; {@code welcome.limits.commandDedupeTtlSeconds} overrides it (D-13). */
    static final long DEFAULT_DEDUPE_TTL_MS = 86_400_000L;

    /** The largest receipt recorded, 4 KiB of compact UTF-8 JSON (S-65). */
    static final int MAX_RECEIPT_BYTES = 4096;

    private final LongSupplier clock;
    /** Id to first-seen time, in the order the ids were first seen. */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    /** Id to its receipt; every key is also a key of {@link #seen}. */
    private final Map<String, JsonObject> receipts = new HashMap<>();
    private int maxEntries;
    private long ttlMillis;
    private boolean dirty;
    private long modCount;

    /**
     * @param maxEntries the most ids remembered, at least 1
     * @param ttlMillis  how long one id is remembered, at least 1 ms
     * @param clock      milliseconds since the Unix epoch
     */
    CommandDedupeSet(int maxEntries, long ttlMillis, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        checkLimits(maxEntries, ttlMillis);
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
    }

    private static void checkLimits(int maxEntries, long ttlMillis) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, was " + maxEntries);
        }
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("ttlMillis must be at least 1, was " + ttlMillis);
        }
    }

    /**
     * Records {@code id} and reports whether it is new.
     *
     * @param id a command's envelope id
     * @return {@code true} the first time {@code id} is seen within both bounds, {@code false} for a repeat
     */
    synchronized boolean firstSeen(String id) {
        Objects.requireNonNull(id, "id");
        long now = clock.getAsLong();
        boolean changed = expire(now);
        boolean first = !seen.containsKey(id);
        if (first) {
            seen.put(id, now);
            evictToMax();
            changed = true;
        }
        if (changed) {
            touch();
        }
        return first;
    }

    /**
     * Changes both bounds and applies them at once.
     *
     * @param newMaxEntries the most ids remembered, at least 1
     * @param newTtlMillis  how long one id is remembered, at least 1 ms
     */
    synchronized void setLimits(int newMaxEntries, long newTtlMillis) {
        checkLimits(newMaxEntries, newTtlMillis);
        maxEntries = newMaxEntries;
        ttlMillis = newTtlMillis;
        boolean expired = expire(clock.getAsLong());
        if (evictToMax() || expired) {
            touch();
        }
    }

    /** @return the remembered ids with their first-seen times, oldest first */
    synchronized List<Seen> snapshot() {
        List<Seen> out = new ArrayList<>(seen.size());
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            out.add(new Seen(e.getKey(), e.getValue(), receipts.get(e.getKey())));
        }
        return out;
    }

    /**
     * Replaces the content with ids loaded from the state file, applying both bounds. Marked dirty only when the
     * bounds removed something, so a load alone does not cause a rewrite.
     *
     * @param loaded the ids, oldest first; a repeated id keeps its first occurrence
     */
    synchronized void restore(List<Seen> loaded) {
        seen.clear();
        receipts.clear();
        for (Seen s : loaded) {
            if (!seen.containsKey(s.id())) {
                seen.put(s.id(), s.seenAt());
                if (s.receipt() != null) {
                    receipts.put(s.id(), s.receipt());
                }
            }
        }
        boolean expired = expire(clock.getAsLong());
        if (evictToMax() || expired) {
            touch();
        } else {
            dirty = false;
            modCount++;
        }
    }

    /** Forgets every id. */
    synchronized void clear() {
        seen.clear();
        receipts.clear();
        touch();
    }

    /**
     * Records {@code receipt} for {@code id}, remembering the id as first seen now when it is not remembered yet
     * (a completion is recorded only once it is answered). A receipt over {@link #MAX_RECEIPT_BYTES} is not recorded
     * and the set is left unchanged.
     *
     * @param id      the envelope id the receipt answers
     * @param receipt the result payload; not copied, so the caller must not mutate it afterwards
     * @return {@code true} when recorded, {@code false} when the receipt is too large
     */
    synchronized boolean recordReceipt(String id, JsonObject receipt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receipt, "receipt");
        if (!fitsReceipt(receipt)) {
            return false;
        }
        long now = clock.getAsLong();
        expire(now);
        if (!seen.containsKey(id)) {
            seen.put(id, now);
        }
        receipts.put(id, receipt);
        evictToMax();
        touch();
        return true;
    }

    /**
     * @param id an envelope id
     * @return its receipt, or {@code null} when the id has none or is no longer remembered (expired or evicted); the
     *         caller must not mutate it
     */
    synchronized JsonObject receiptFor(String id) {
        if (expire(clock.getAsLong())) {
            touch();
        }
        return receipts.get(id);
    }

    /**
     * @param receipt a candidate receipt
     * @return whether its compact UTF-8 JSON is at most {@link #MAX_RECEIPT_BYTES}
     */
    static boolean fitsReceipt(JsonObject receipt) {
        return receipt.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_RECEIPT_BYTES;
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

    private boolean expire(long now) {
        boolean removed = false;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > ttlMillis) {
                receipts.remove(entry.getKey());
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    private boolean evictToMax() {
        boolean removed = false;
        Iterator<String> it = seen.keySet().iterator();
        while (seen.size() > maxEntries && it.hasNext()) {
            receipts.remove(it.next());
            it.remove();
            removed = true;
        }
        return removed;
    }

    private void touch() {
        dirty = true;
        modCount++;
    }

    /** One remembered command id, when it was first seen and its receipt, if any. */
    static final class Seen {
        private final String id;
        private final long seenAt;
        private final JsonObject receipt;

        /**
         * @param id     the command's envelope id
         * @param seenAt when it was first seen, epoch milliseconds
         */
        Seen(String id, long seenAt) {
            this(id, seenAt, null);
        }

        /**
         * @param id      the envelope id
         * @param seenAt  when it was first seen, epoch milliseconds
         * @param receipt its recorded result payload, or {@code null}
         */
        Seen(String id, long seenAt, JsonObject receipt) {
            this.id = Objects.requireNonNull(id, "id");
            this.seenAt = seenAt;
            this.receipt = receipt;
        }

        /** @return the recorded result payload, or {@code null}; must not be mutated */
        JsonObject receipt() {
            return receipt;
        }

        String id() {
            return id;
        }

        long seenAt() {
            return seenAt;
        }
    }
}
