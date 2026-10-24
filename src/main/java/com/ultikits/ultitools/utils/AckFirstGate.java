package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import java.io.IOException;
import java.io.UncheckedIOException;

final class AckFirstGate {
    private AckFirstGate() { }

    static void handle(CloudRequest request, CloudChannel channel, DedupeLedger ledger, Runnable execute) {
        boolean first = ledger.firstSeen(request.id());
        if (first) {
            try {
                ledger.persistNow();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        channel.reply(request.id(), "command.ack", new JsonObject());
        if (first) {
            execute.run();
        }
    }
}
