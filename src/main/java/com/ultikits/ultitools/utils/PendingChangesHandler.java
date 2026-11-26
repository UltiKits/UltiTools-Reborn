package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.PendingChanges.Kind;
import com.ultikits.ultitools.utils.PendingChanges.PendingChange;
import com.ultikits.ultitools.utils.PendingChanges.Requester;
import com.ultikits.ultitools.utils.PendingChanges.RevokeResult;
import com.ultikits.ultitools.utils.PendingChanges.State;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import com.ultikits.ultitools.websocket.PanelConnectionLog;
import org.jetbrains.annotations.ApiStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Serves the panel's pending module changes on the connection's serial request worker. */
@ApiStatus.Internal
final class PendingChangesHandler implements CloudRequestHandler {
    private static final String LIST = "upm.pending.list";
    private static final String LIST_RESULT = "upm.pending.list.result";
    private static final String REVOKE = "upm.pending.revoke";
    private static final String REVOKE_RESULT = "upm.pending.revoke.result";
    static final List<String> TYPES = List.of(LIST, REVOKE);

    private static final int MAX_ITEMS = 200;
    private static final int MAX_FILES = 16;
    private static final int MAX_CONTENT_KINDS = 16;
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final Pattern ENTRY_ID = Pattern.compile("^(install|update|remove)-[0-9a-f]{16}$");
    private static final Pattern REVISION = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern JAR_NAME = Pattern.compile("^[^/\\\\]+\\.jar$");
    private static final long MAX_USER_ID = 9_007_199_254_740_991L;
    private static final long MAX_TIMESTAMP = 8_640_000_000_000_000L;
    private static final String INTERNAL_ERROR = "internal_error";

    private final Supplier<PendingChanges> pending;
    private final Supplier<List<StartOutcome>> startOutcomes;
    private final ShutdownGate shutdown;
    private final Supplier<Logger> logger;

    PendingChangesHandler(Supplier<PendingChanges> pending, Supplier<List<StartOutcome>> startOutcomes,
                          ShutdownGate shutdown, Supplier<Logger> logger) {
        this.pending = pending;
        this.startOutcomes = startOutcomes;
        this.shutdown = shutdown;
        this.logger = logger;
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        String replyTo = request.id();
        switch (request.type()) {
            case LIST -> {
                new PayloadReader(replyTo, request.payload()).keys(new String[0]);
                replySafely(channel, replyTo, LIST_RESULT, () -> channel.replyOk(replyTo, LIST_RESULT,
                        listData(replyTo, channel.limits().maxFrameBytes())));
            }
            case REVOKE -> {
                Revocation revocation = readRevocation(request);
                replySafely(channel, replyTo, REVOKE_RESULT, () -> revoke(channel, replyTo, revocation));
            }
            default -> throw new IllegalArgumentException("Not a pending-change request type");
        }
    }

    private JsonObject listData(String replyTo, int maxBytes) {
        PendingChanges.Snapshot snapshot = pending.get().snapshot();
        List<JsonObject> entries = new ArrayList<>(snapshot.entries().size());
        for (PendingChange entry : snapshot.entries()) {
            if (hasExpectedRevision(entry)) {
                entries.add(entryData(entry));
            }
        }
        boolean omitted = entries.size() != snapshot.entries().size();
        if (omitted) {
            logger.get().warning("UltiCloud: skipped pending module changes with an inconsistent revision.");
        }
        List<StartOutcome> outcomes = startOutcomes.get();
        List<JsonObject> lastStart = outcomes.stream().limit(MAX_ITEMS).map(PendingChangesHandler::startData).toList();
        boolean truncated = omitted || snapshot.truncated() > 0 || outcomes.size() > MAX_ITEMS;
        return fitList(replyTo, maxBytes, entries, lastStart, truncated);
    }

    private static boolean hasExpectedRevision(PendingChange entry) {
        return (entry.revision() == null) == (entry.state() == State.DOWNLOADING);
    }

    private static JsonObject fitList(String replyTo, int maxBytes, List<JsonObject> entries,
                                      List<JsonObject> lastStart, boolean truncated) {
        JsonObject data = new JsonObject();
        data.add("entries", prefix(entries, entries.size()));
        data.add("lastStart", prefix(lastStart, lastStart.size()));
        data.addProperty("truncated", truncated);
        if (fitsListFrame(replyTo, data, maxBytes)) {
            return data;
        }
        data.addProperty("truncated", true);
        data.add("entries", new JsonArray());
        if (fitsListFrame(replyTo, data, maxBytes)) {
            keepFittingPrefix(replyTo, maxBytes, data, "entries", entries);
        } else {
            // A smaller negotiated budget can leave no room for even the startup results alone.
            keepFittingPrefix(replyTo, maxBytes, data, "lastStart", lastStart);
        }
        return data;
    }

    /** Finds the longest prefix by measuring whole frames, without re-encoding for every dropped entry. */
    private static void keepFittingPrefix(String replyTo, int maxBytes, JsonObject data, String key,
                                          List<JsonObject> entries) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            data.add(key, prefix(entries, mid));
            if (fitsListFrame(replyTo, data, maxBytes)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        data.add(key, prefix(entries, low));
    }

    private static JsonArray prefix(List<JsonObject> items, int size) {
        JsonArray array = new JsonArray(size);
        for (int i = 0; i < size; i++) {
            array.add(items.get(i));
        }
        return array;
    }

    private static boolean fitsListFrame(String replyTo, JsonObject data, int maxBytes) {
        // Use FramedChannel.replyOk's payload and Envelope's actual escaping/null rules. The future
        // ULID has the same width as replyTo, and the largest valid timestamp bounds its width.
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok");
        payload.add("data", data);
        String text = new Envelope(replyTo, LIST_RESULT, MAX_TIMESTAMP, replyTo, payload).toJson();
        return text.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    private static JsonObject entryData(PendingChange entry) {
        JsonObject result = changeData(entry.kind(), entry.module(), entry.fromVersion(), entry.toVersion());
        result.addProperty("id", entry.id());
        result.addProperty("revision", entry.revision());
        result.add("files", fileNames(entry.files()));
        result.addProperty("requestedAt", entry.requestedAt());
        result.add("requestedBy", requesterData(entry.requestedBy()));
        result.addProperty("state", stateName(entry.state()));
        result.addProperty("failure", failureText(entry));
        result.add("warnings", warnings(entry.content()));
        return result;
    }

    private static JsonObject changeData(Kind kind, String module, String fromVersion, String toVersion) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", kindName(kind));
        result.addProperty("module", bounded(module, 128));
        result.addProperty("fromVersion", bounded(fromVersion, 64));
        result.addProperty("toVersion", bounded(toVersion, 64));
        return result;
    }

    private static String bounded(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() <= maxChars) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return value.substring(0, end);
    }

    private static String failureText(PendingChange entry) {
        if (entry.state() != State.FAILED && entry.state() != State.NEEDS_OPERATOR) {
            return null;
        }
        String failure = bounded(entry.failure(), MAX_MESSAGE_CHARS);
        return failure == null || failure.isBlank() ? "No reason was recorded." : failure;
    }

    private static JsonArray fileNames(List<String> files) {
        JsonArray names = new JsonArray();
        files.stream().filter(PendingChangesHandler::isJarName).limit(MAX_FILES).forEach(names::add);
        return names;
    }

    private static boolean isJarName(String name) {
        return name.length() >= 5 && name.length() <= 255 && JAR_NAME.matcher(name).matches();
    }

    private static JsonElement requesterData(Requester requester) {
        if (requester == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject result = new JsonObject();
        result.addProperty("kind", requester.kind());
        result.addProperty("name", "console".equals(requester.kind()) ? null : bounded(requester.name(), 64));
        return result;
    }

    private static JsonArray warnings(ModuleContent content) {
        JsonArray warnings = new JsonArray();
        if (!content.isEmpty()) {
            JsonObject warning = new JsonObject();
            warning.addProperty("warning", "custom_content");
            JsonArray kinds = new JsonArray();
            content.kinds().stream().limit(MAX_CONTENT_KINDS).map(PendingChangesHandler::contentKind).forEach(kinds::add);
            warning.add("kinds", kinds);
            warnings.add(warning);
        }
        return warnings;
    }

    private static JsonObject contentKind(ModuleContentKind kind) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", kind.kind());
        result.addProperty("count", kind.count()); // ModuleContentKind enforces null for unknown.
        return result;
    }

    private static JsonObject startData(StartOutcome outcome) {
        JsonObject result = changeData(outcome.kind(), outcome.module(), outcome.fromVersion(), outcome.toVersion());
        result.addProperty("result", switch (outcome.result()) {
            case APPLIED -> "applied";
            case ROLLED_BACK -> "rolled_back";
            case NOT_APPLIED -> "not_applied";
            case NEEDS_OPERATOR -> "needs_operator";
            default -> throw new IllegalArgumentException("Unknown startup result: " + outcome.result());
        });
        return result;
    }

    private static String kindName(Kind kind) {
        return switch (kind) {
            case INSTALL -> "install";
            case UPDATE -> "update";
            case UNINSTALL -> "uninstall";
            default -> throw new IllegalArgumentException("Unknown pending change kind: " + kind);
        };
    }

    private static String stateName(State state) {
        return switch (state) {
            case PENDING -> "pending";
            case DOWNLOADING -> "downloading";
            case FAILED -> "failed";
            case NEEDS_OPERATOR -> "needs_operator";
            case IN_PROGRESS -> "in_progress";
            default -> throw new IllegalArgumentException("Unknown pending change state: " + state);
        };
    }

    private record Revocation(String id, String revision, long userId) { }

    private static Revocation readRevocation(CloudRequest request) {
        PayloadReader reader = new PayloadReader(request.id(), request.payload())
                .keys(new String[] {"id", "revision", "actor"});
        String id = reader.string("id", 23, 24, ENTRY_ID);
        String revision = reader.required("revision").isJsonNull() ? null : reader.string("revision", 16, 16, REVISION);
        long userId = new PayloadReader(request.id(), reader.object("actor")).keys(new String[] {"userId"})
                .integer("userId", 1, MAX_USER_ID);
        return new Revocation(id, revision, userId);
    }

    private void revoke(CloudChannel channel, String replyTo, Revocation request) {
        // This request is new to 2.2.0. Hold the same shutdown permit as file writes from the
        // worker, not from the WebSocket thread that queued it.
        if (!shutdown.enter()) {
            channel.replyError(replyTo, REVOKE_RESULT, INTERNAL_ERROR, ShutdownGate.MESSAGE, null);
            return;
        }
        try {
            RevokeResult result = pending.get().revoke(request.id(), request.revision(), true);
            replyRevoked(channel, replyTo, request.userId(), result);
        } finally {
            shutdown.exit();
        }
    }

    private void replyRevoked(CloudChannel channel, String replyTo, long userId, RevokeResult result) {
        switch (result.outcome()) {
            case REVOKED -> {
                PendingChange before = result.before();
                logger.get().info(FrameworkMessages.plain(UpmKeys.REVOKE_PANEL_LOG,
                        Arg.string("user_id", Long.toString(userId)), Arg.string("module", before.module()),
                        Arg.string("action", kindName(before.kind()))));
                JsonObject data = new JsonObject();
                data.add("revoked", entryData(before));
                channel.replyOk(replyTo, REVOKE_RESULT, data);
            }
            case NOT_FOUND -> channel.replyError(replyTo, REVOKE_RESULT, "pending_not_found",
                    "This pending change no longer exists.", null);
            case CHANGED -> channel.replyError(replyTo, REVOKE_RESULT, "pending_changed",
                    "This pending change was replaced or changed.", null);
            case NOT_REVOCABLE -> channel.replyError(replyTo, REVOKE_RESULT, "pending_not_revocable",
                    "This pending change cannot be revoked in its current state.", null);
            case FAILED -> channel.replyError(replyTo, REVOKE_RESULT, "write_denied", writeFailure(result.error()), null);
            default -> throw new IllegalArgumentException("Unknown revocation outcome: " + result.outcome());
        }
    }

    private static String writeFailure(String error) {
        String message = bounded(error, MAX_MESSAGE_CHARS);
        return message == null || message.isBlank() ? "The pending change could not be removed." : message;
    }

    private static void replySafely(CloudChannel channel, String replyTo, String type, Runnable reply) {
        try {
            reply.run();
        } catch (IllegalArgumentException | IllegalStateException buildFailed) {
            PanelConnectionLog.log(Level.WARNING, "UltiCloud: could not build a pending change reply.");
            try {
                channel.replyError(replyTo, type, INTERNAL_ERROR, "The pending change reply could not be built.", null);
            } catch (IllegalArgumentException | IllegalStateException refused) {
                PanelConnectionLog.log(Level.FINE, "UltiCloud: the channel refused the pending change error reply.");
            }
        }
    }
}
