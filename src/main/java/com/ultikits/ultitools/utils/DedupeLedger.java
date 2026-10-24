package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import java.io.IOException;

interface DedupeLedger {
    boolean firstSeen(String id);
    void persistNow() throws IOException;
    JsonObject receiptFor(String id);
    void recordReceipt(String id, JsonObject receipt);
}
