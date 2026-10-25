package com.ultikits.ultitools.utils;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * The {@link DedupeLedger} every inbound handler of the framework shares (plan 22-21, D-17 as narrowed by S-52, S-59):
 * the command-id set and the receipts live in the current session's {@link CommandDedupeSet}, and
 * {@link #persistNow()} writes them, with the outbox, to {@code cloud-outbox.json} through the session's
 * {@link CloudStateStore}.
 * <p>
 * <b>Lifetime.</b> One instance lives as long as the plugin is enabled, so the monitor stays the same across
 * reconnects and across the session replacements of a login or logout. Each call asks the {@link Target} supplier for
 * the state of the session that is current at that moment; with no adopted credential (no target, or one without a
 * store) {@link #firstSeen} reports every id as new, no receipt exists and {@link #persistNow()} fails, which the
 * callers already treat as "continue with what is in memory".
 * <p>
 * <b>Locks.</b> Callers hold this ledger's monitor around a mutation and its persistence. {@link #persistNow()} then
 * takes the store's JVM-wide write lock and, inside it, the dedupe set's leaf monitor; it never takes a session
 * monitor, so it cannot invert the lock order of {@link CloudSession} (S-32, S-37). It is synchronous and runs on the
 * thread that handles the request -- the agent thread or the player-link handler thread, never the server thread. A
 * module may complete its player-link result on any thread, the server thread included; {@link PlayerLinkChannel}
 * moves the persistence of that result onto the player-link handler thread first.
 */
final class SessionDedupeLedger implements DedupeLedger {

    private final Supplier<Target> target;

    /**
     * @param target supplies the current session's state, or {@code null} while no credential is adopted
     */
    SessionDedupeLedger(Supplier<Target> target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /**
     * @param dedupe   the id set
     * @param store    the state file writer, or {@code null} for an in-memory ledger
     * @param serverId the linked server's id
     * @return a ledger bound to exactly that state
     */
    static SessionDedupeLedger fixed(CommandDedupeSet dedupe, CloudStateStore store, String serverId) {
        final Target fixed = new Target(dedupe, store, serverId);
        return new SessionDedupeLedger(() -> fixed);
    }

    @Override
    public boolean firstSeen(String id) {
        Target current = target.get();
        return current == null || current.dedupe.firstSeen(id);
    }

    /**
     * Writes the ids, their receipts and the outbox now, under the store's write lock.
     *
     * @throws IOException when no store is adopted or the write fails; the dirty flags then stay set, so the next
     *                     periodic flush retries
     */
    @Override
    public void persistNow() throws IOException {
        Target current = target.get();
        if (current == null || current.store == null || current.serverId == null) {
            throw new IOException("no state file is adopted");
        }
        try {
            current.store.flush(current.serverId);
        } catch (RuntimeException failure) {
            throw new IOException("the state file could not be written", failure);
        }
    }

    @Override
    public JsonObject receiptFor(String id) {
        Target current = target.get();
        return current == null ? null : current.dedupe.receiptFor(id);
    }

    /**
     * Records the receipt with its id. A receipt larger than {@link CommandDedupeSet#MAX_RECEIPT_BYTES} is not
     * recorded and logs one WARNING: the reply is still sent, and a redelivery of that id asks the module again.
     */
    @Override
    public void recordReceipt(String id, JsonObject receipt) {
        Target current = target.get();
        if (current == null) {
            return;
        }
        if (!current.dedupe.recordReceipt(id, receipt)) {
            PanelConnectionLog.log(Level.WARNING, "UltiCloud: the player-link result " + id + " is larger than "
                    + CommandDedupeSet.MAX_RECEIPT_BYTES + " bytes and was not recorded; it is still sent, and a "
                    + "redelivery of that completion asks the module again.");
        }
    }

    /** The state of one session: its id set, its state file and the server id the file is written for. */
    static final class Target {
        final CommandDedupeSet dedupe;
        final CloudStateStore store;
        final String serverId;

        /**
         * @param dedupe   the id set
         * @param store    the state file writer, or {@code null}
         * @param serverId the linked server's id
         */
        Target(CommandDedupeSet dedupe, CloudStateStore store, String serverId) {
            this.dedupe = Objects.requireNonNull(dedupe, "dedupe");
            this.store = store;
            this.serverId = serverId;
        }
    }
}
