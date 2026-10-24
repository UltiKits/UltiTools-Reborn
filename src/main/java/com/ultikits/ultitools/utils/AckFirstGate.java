package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Implements persist-id, acknowledge, then execute for ack-first requests.
 * Result receipts are not recorded here; completed-link handlers own that step.
 */
final class AckFirstGate {
    private static final Logger LOGGER = Logger.getLogger(AckFirstGate.class.getName());

    private AckFirstGate() { }

    /**
     * Records and persists a new id under the shared ledger monitor, then releases
     * the monitor before acknowledging and executing. Duplicates are acknowledged
     * again but never executed. IOException during persistence is logged without
     * exception text; acknowledgement and execution continue with in-memory dedupe.
     * A refused send does not undo the recorded id or prevent first-time execution.
     *
     * @param request the validated ack-first request
     * @param channel the acknowledgement channel
     * @param ledger the shared session ledger and synchronization monitor
     * @param execute the first-delivery action, invoked outside the ledger monitor
     */
    static void handle(CloudRequest request, CloudChannel channel, DedupeLedger ledger, Runnable execute) {
        boolean first;
        synchronized (ledger) {
            first = ledger.firstSeen(request.id());
            if (first) {
                try {
                    ledger.persistNow();
                } catch (IOException ex) {
                    LOGGER.warning("UltiCloud: could not persist the command id " + request.id()
                            + "; continuing with in-memory deduplication.");
                }
            }
        }
        channel.reply(request.id(), "command.ack", new JsonObject());
        if (first) {
            execute.run();
        }
    }
}
