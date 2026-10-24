package com.ultikits.ultitools.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.manager.CommandExecutionManager;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

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
                details.addProperty("matched", matched);
                channel.replyError(request.id(), "command.result", "command_blocked", message, details);
            }

            @Override
            public void completed(List<String> lines, boolean truncated, long executionMs) {
                JsonArray output = new JsonArray();
                for (String line : lines) {
                    output.add(line);
                }
                JsonObject data = new JsonObject();
                data.add("output", output);
                data.addProperty("truncated", truncated);
                data.addProperty("executionMs", executionMs);
                channel.replyOk(request.id(), "command.result", data);
            }

            @Override
            public void failed(String message) {
                channel.replyError(request.id(), "command.result", "command_failed", message, null);
            }
        };
        try {
            AckFirstGate.handle(request, channel, ledger, () -> manager.execute(command, "panel",
                    action -> mainThread.execute(() -> runUnlessDisabling(action, request, channel)), sink));
        } catch (RuntimeException ex) {
            internalError(request, channel, ex);
        }
    }

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
}
