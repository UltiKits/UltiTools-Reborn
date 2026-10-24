package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import java.io.IOException;
import java.util.logging.Logger;

final class AckFirstGate {
    private static final Logger LOGGER = Logger.getLogger(AckFirstGate.class.getName());

    private AckFirstGate() { }

    static void handle(CloudRequest request, CloudChannel channel, DedupeLedger ledger, Runnable execute) {
        boolean first;
        synchronized (ledger) {
            first = ledger.firstSeen(request.id());
            if (first) {
                try {
                    ledger.persistNow();
                } catch (IOException ex) {
                    LOGGER.warning("UltiCloud: could not persist the command id; continuing with in-memory deduplication.");
                }
            }
        }
        channel.reply(request.id(), "command.ack", new JsonObject());
        if (first) {
            execute.run();
        }
    }
}
