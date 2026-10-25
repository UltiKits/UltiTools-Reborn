package com.ultikits.ultitools.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RecordingChannel implements CloudChannel {
    private final List<JsonObject> frames = new ArrayList<>();
    private CloudLimits limits = CloudLimits.defaults();
    private boolean open = true;
    private long sequence;

    public synchronized List<JsonObject> frames() {
        List<JsonObject> copy = new ArrayList<>();
        for (JsonObject frame : frames) { copy.add(frame.deepCopy()); }
        return copy;
    }

    public synchronized void clear() { frames.clear(); }
    public synchronized void setOpen(boolean open) { this.open = open; }
    public synchronized void setLimits(CloudLimits limits) { this.limits = limits; }
    @Override public synchronized boolean isOpen() { return open; }
    @Override public synchronized CloudLimits limits() { return limits; }

    public synchronized boolean accept(String text) {
        if (!open) { return false; }
        JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
        ContractSchemas.assertFrameValid(frame);
        frames.add(frame.deepCopy());
        return true;
    }

    @Override public boolean send(String type, JsonObject payload) { return sendTracked(type, payload) != null; }
    @Override public String sendTracked(String type, JsonObject payload) { return recordTracked(null, type, payload); }
    @Override public boolean reply(String replyTo, String type, JsonObject payload) { return recordTracked(replyTo, type, payload) != null; }

    @Override
    public boolean replyOk(String replyTo, String type, JsonElement data) {
        JsonObject payload = new JsonObject(); payload.addProperty("status", "ok"); payload.add("data", data);
        return reply(replyTo, type, payload);
    }

    @Override
    public boolean replyPartial(String replyTo, String type, JsonElement data, String codeOrNull, String messageOrNull) {
        JsonObject payload = new JsonObject(); payload.addProperty("status", "partial"); payload.add("data", data);
        if (codeOrNull != null) { payload.addProperty("code", codeOrNull); }
        if (messageOrNull != null) { payload.addProperty("message", messageOrNull); }
        return reply(replyTo, type, payload);
    }

    @Override
    public boolean replyError(String replyTo, String type, String code, String message, JsonObject detailsOrNull) {
        JsonObject payload = new JsonObject();
        if (!"error".equals(type)) { payload.addProperty("status", "error"); }
        payload.addProperty("code", code); payload.addProperty("message", message);
        if (detailsOrNull != null) { payload.add("details", detailsOrNull); }
        return reply(replyTo, type, payload);
    }

    private synchronized String recordTracked(String replyTo, String type, JsonObject payload) {
        if (!open) { return null; }
        String id = String.format(Locale.ROOT, "%026d", ++sequence);
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 2);
        frame.addProperty("id", id);
        frame.addProperty("type", type); frame.addProperty("ts", 1000);
        if (replyTo != null) { frame.addProperty("replyTo", replyTo); }
        frame.add("payload", payload);
        return accept(frame.toString()) ? id : null;
    }
}
