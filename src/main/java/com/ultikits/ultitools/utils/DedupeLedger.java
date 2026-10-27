package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import java.io.IOException;

/**
 * Shared command-id and player-link receipt ledger; internal, not for module use.
 *
 * <p>The ledger instance is the synchronization monitor for all callers sharing its
 * state. Hold {@code synchronized (ledger)} around reads and mutations and across
 * each mutation-and-persist sequence: firstSeen/persistNow for ack-first commands,
 * or recordReceipt/persistNow for completed-link results. Implementations must support
 * calls while this monitor is held and must not acquire it in reverse order from
 * their storage locks. Individual thread-safe methods alone do not make a sequence atomic.</p>
 *
 * <p>Release the monitor before sending frames or invoking handlers. AckFirstGate
 * persists ids only; player-link handlers own result receipts and must persist them
 * before sending a completion result. Storage failure handling belongs to the caller:
 * AckFirstGate explicitly continues with in-memory deduplication after IOException.</p>
 *
 * @since 6.3.1
 */
interface DedupeLedger {
    /**
     * Records a previously unseen id in memory, subject to retention bounds.
     * @param id the request envelope ULID
     * @return true for a newly recorded id, false for an unexpired duplicate
     */
    boolean firstSeen(String id);

    /**
     * Synchronously persists the current ids and receipts while the caller holds the ledger monitor.
     * @throws IOException if the updated state could not be persisted
     */
    void persistNow() throws IOException;

    /**
     * Looks up a completion result for replay, without invoking its handler again.
     * @param id the completed-message envelope ULID
     * @return the recorded result payload, or null when absent; callers must not mutate it
     */
    JsonObject receiptFor(String id);

    /**
     * Records a completion result in memory; call persistNow in the same synchronized block.
     * @param id the completed-message envelope ULID
     * @param receipt the result payload; callers must not mutate it after recording
     */
    void recordReceipt(String id, JsonObject receipt);
}
