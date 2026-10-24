package com.ultikits.ultitools.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Handles a validated {@code command.execute} through the shared acknowledgement and dedupe gate.
 *
 * <p>Order is fixed by D-16/D-17/S-52: the envelope id is recorded and persisted in the
 * {@link DedupeLedger} first, the request is acknowledged second, and only a first delivery is
 * handed to {@link CommandExecutionManager} for main-thread execution. A redelivered id is
 * acknowledged again and never executed again; a persistence failure is logged by
 * {@link AckFirstGate} and does not stop the acknowledgement or the first execution.</p>
 *
 * <p>The capability switch is enforced by {@link AgentMessageRouter} before this handler runs;
 * {@link CommandExecutionManager#execute} keeps its own defensive check. Truncation to the
 * negotiated {@code commandOutputMaxBytes} happens here because only the channel knows the limits
 * of the accepted welcome; the per-line and per-item schema caps are applied by the manager.</p>
 */
final class RemoteCommandHandler implements CloudRequestHandler {
    private static final Logger LOGGER = Logger.getLogger(RemoteCommandHandler.class.getName());
    private static final Pattern COMMAND = Pattern.compile("^[^\\r\\n]{1,1000}$");
    /** {@code command.result.payload.details} property values and keys maxLength. */
    private static final int MAX_DETAIL_CHARS = 500;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final CommandExecutionManager manager;
    private final DedupeLedger ledger;
    private final Executor mainThread;
    private final BooleanSupplier disabling;

    RemoteCommandHandler(CommandExecutionManager manager, DedupeLedger ledger, Executor mainThread,
                         BooleanSupplier disabling) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.disabling = Objects.requireNonNull(disabling, "disabling");
    }

    @Override
    public void handle(CloudRequest request, CloudChannel channel) {
        PayloadReader payload = new PayloadReader(request.id(), request.payload()).keys(new String[]{"command"});
        String command = payload.string("command", 1, 1000, COMMAND);
        CommandExecutionManager.CommandResultSink sink = new CommandExecutionManager.CommandResultSink() {
            @Override
            public void blocked(String matched, String message) {
                JsonObject details = new JsonObject();
                details.addProperty("matched", cut(matched, MAX_DETAIL_CHARS));
                channel.replyError(request.id(), "command.result", "command_blocked", message, details);
            }

            @Override
            public void completed(List<String> lines, boolean truncated, long executionMs) {
                BoundedOutput output = fitOutput(lines, channel.limits().commandOutputMaxBytes());
                JsonObject data = new JsonObject();
                data.add("output", output.lines);
                data.addProperty("truncated", truncated || output.truncated);
                data.addProperty("executionMs", executionMs);
                channel.replyOk(request.id(), "command.result", data);
            }

            @Override
            public void failed(String message) {
                channel.replyError(request.id(), "command.result", "command_failed", message, null);
            }
        };
        try {
            AckFirstGate.handle(request, channel, ledger, () -> runUnlessDisabling(
                    () -> manager.execute(command, "panel",
                            action -> mainThread.execute(() -> runUnlessDisabling(action, request, channel)), sink),
                    request, channel));
        } catch (RuntimeException ex) {
            internalError(request, channel, ex);
        }
    }

    /**
     * Keeps as many leading output lines as fit the negotiated cap on the serialized array's UTF-8
     * size, dropping trailing lines. The cap bounds the array itself, not the whole frame.
     */
    private static BoundedOutput fitOutput(List<String> lines, int maxBytes) {
        int[] sizes = new int[lines.size()];
        long total = 2; // the enclosing brackets
        for (int i = 0; i < lines.size(); i++) {
            sizes[i] = GSON.toJson(lines.get(i)).getBytes(StandardCharsets.UTF_8).length;
            total += sizes[i] + (i > 0 ? 1 : 0);
        }
        int keep = lines.size();
        while (keep > 0 && total > maxBytes) {
            total -= sizes[keep - 1] + (keep > 1 ? 1 : 0);
            keep--;
        }
        JsonArray output = new JsonArray();
        for (int i = 0; i < keep; i++) {
            output.add(lines.get(i));
        }
        return new BoundedOutput(output, keep < lines.size());
    }

    private static String cut(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.codePointCount(0, text.length()) <= maxChars
                ? text
                : text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    /**
     * Runs one step of the acknowledged request unless the plugin is disabling. The check is
     * applied twice — once before validation, so a shutting-down plugin sends no outcome at all
     * after the acknowledgement, and once on the main thread, for the window between validation
     * and the dispatch itself (D-16's disabling clause).
     */
    private void runUnlessDisabling(Runnable action, CloudRequest request, CloudChannel channel) {
        try {
            if (disabling.getAsBoolean()) {
                LOGGER.fine("UltiCloud: dropped remote command while the plugin is disabling.");
                return;
            }
            action.run();
        } catch (RuntimeException ex) {
            internalError(request, channel, ex);
        }
    }

    private static void internalError(CloudRequest request, CloudChannel channel, RuntimeException failure) {
        LOGGER.warning("UltiCloud: remote command framework failure; exception=" + failure.getClass().getName());
        channel.replyError(request.id(), "command.result", "internal_error", "The command could not be processed.", null);
    }

    /** An output array plus whether the byte cap forced lines to be dropped. */
    private static final class BoundedOutput {
        private final JsonArray lines;
        private final boolean truncated;

        private BoundedOutput(JsonArray lines, boolean truncated) {
            this.lines = lines;
            this.truncated = truncated;
        }
    }
}
