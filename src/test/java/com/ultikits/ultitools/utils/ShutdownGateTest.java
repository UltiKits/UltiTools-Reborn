package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * 22-23 shutdown-window round (maintainer decision of 2026-10-26): the router's shutdown gate, swept over every
 * gateway-to-agent type of the frozen 2.0.0 table. While the server is shutting down a state-changing request never
 * reaches its handler and gets its shutting-down failure in a schema-valid shape; every other type still reaches its
 * handler. The gate is ahead of the switch gate and holds its permit for the whole handler call.
 */
@DisplayName("ShutdownGate: every state-changing type refused while shutting down, reads untouched")
class ShutdownGateTest {

    private static final String ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    /** A signal a test opens and closes; counts the permits it gave and got back. */
    static final class TestSignal implements ShutdownGate.Signal {
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger exited = new AtomicInteger();

        @Override
        public boolean enter() {
            if (!open.get()) {
                return false;
            }
            entered.incrementAndGet();
            return true;
        }

        @Override
        public void exit() {
            exited.incrementAndGet();
        }
    }

    /** An in-memory ledger. */
    static final class MemoryLedger implements DedupeLedger {
        final Set<String> seen = new HashSet<>();

        @Override
        public boolean firstSeen(String id) {
            return seen.add(id);
        }

        @Override
        public void persistNow() {
            // in memory only
        }

        @Override
        public JsonObject receiptFor(String id) {
            return null;
        }

        @Override
        public void recordReceipt(String id, JsonObject receipt) {
            // not used here
        }
    }

    private final TestSignal signal = new TestSignal();
    private final MemoryLedger ledger = new MemoryLedger();
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final List<String> switchRefusals = new ArrayList<>();

    private AgentMessageRouter router(boolean switchesOn) {
        AgentMessageRouter router = new AgentMessageRouter(cap -> switchesOn, cap -> "Enable " + cap + ".");
        router.onShutdown(new ShutdownGate(signal, ledger));
        router.onSwitchRefusal((request, capability, message) -> switchRefusals.add(request.type()));
        for (String type : downTypes()) {
            router.register(type, new CloudRequestHandler() {
                @Override
                public void handle(CloudRequest request, CloudChannel channel) {
                    handled.add(request.type());
                }
            });
        }
        return router;
    }

    /** Every gateway-to-agent type of the table except the handshake and the acks the protocol layer consumes. */
    private static List<String> downTypes() {
        List<String> types = new ArrayList<>();
        for (String type : AgentMessageTypes.types()) {
            AgentMessageTypes.Spec spec = AgentMessageTypes.spec(type);
            if (spec.direction() == AgentMessageTypes.Direction.DOWN && !"welcome".equals(type)
                    && !"ack".equals(type)) {
                types.add(type);
            }
        }
        return types;
    }

    private static String frame(String type) {
        JsonObject frame = ContractFixtures.validFrames(type).get(0).frame();
        frame.addProperty("id", ID);
        return frame.toString();
    }

    private static String type(JsonObject frame) {
        return frame.get("type").getAsString();
    }

    private static void assertShuttingDown(JsonObject frame, String type) {
        ContractSchemas.assertFrameValid(frame);
        assertThat(type(frame)).isEqualTo(type);
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(ID);
        JsonObject payload = frame.getAsJsonObject("payload");
        if (!"error".equals(type)) {
            assertThat(payload.get("status").getAsString()).isEqualTo("error");
        }
        assertThat(payload.get("code").getAsString()).isEqualTo("internal_error");
        assertThat(payload.get("message").getAsString()).contains("shutting down");
    }

    @Test
    @DisplayName("the covered set is exactly the state-changing gateway requests and the link completion")
    void coveredSetIsPinned() {
        assertThat(ShutdownGate.coveredTypes()).containsExactly("command.execute", "server.power", "files.write",
                "files.delete", "properties.update", "config.update", "player.action", "module.request",
                "credential.rotate", "player.link.completed");
        assertThat(downTypes()).containsAll(ShutdownGate.coveredTypes());
    }

    @Test
    @DisplayName("sweep while shutting down: each covered type answered with its failure, never handled; "
            + "every other type handled")
    void sweepWhileShuttingDown() {
        signal.open.set(false);
        Set<String> notHandled = new LinkedHashSet<>();
        for (String type : downTypes()) {
            RecordingChannel channel = new RecordingChannel();
            handled.clear();
            ledger.seen.clear();
            router(true).dispatch(frame(type), channel);
            List<JsonObject> frames = channel.frames();
            if (!ShutdownGate.covers(type)) {
                assertThat(handled).as("%s still reaches its handler", type).containsExactly(type);
                continue;
            }
            notHandled.add(type);
            assertThat(handled).as("%s never reaches its handler", type).isEmpty();
            switch (ShutdownGate.refusalOf(type)) {
                case ACK_THEN_RESULT:
                    assertThat(frames).as(type).hasSize(2);
                    ContractSchemas.assertFrameValid(frames.get(0));
                    assertThat(type(frames.get(0))).isEqualTo("command.ack");
                    assertShuttingDown(frames.get(1), ShutdownGate.resultTypeOf(type));
                    assertThat(ledger.seen).as("the refused id is recorded").containsExactly(ID);
                    break;
                case RESULT:
                case GENERIC:
                    assertThat(frames).as(type).hasSize(1);
                    assertShuttingDown(frames.get(0), ShutdownGate.resultTypeOf(type));
                    break;
                default:
                    assertThat(frames).as("%s is left for redelivery", type).isEmpty();
                    break;
            }
        }
        assertThat(notHandled).containsExactlyInAnyOrderElementsOf(ShutdownGate.coveredTypes());
        assertThat(signal.entered.get()).isZero();
        assertThat(signal.exited.get()).isZero();
    }

    @Test
    @DisplayName("control: while accepting every covered type reaches its handler, one permit each, all returned")
    void acceptingLetsEveryCoveredTypeIn() {
        AgentMessageRouter router = router(true);
        for (String type : ShutdownGate.coveredTypes()) {
            router.dispatch(frame(type), new RecordingChannel());
        }
        assertThat(handled).containsExactlyInAnyOrderElementsOf(ShutdownGate.coveredTypes());
        assertThat(signal.entered.get()).isEqualTo(ShutdownGate.coveredTypes().size());
        assertThat(signal.exited.get()).isEqualTo(signal.entered.get());
    }

    @Test
    @DisplayName("the shutdown gate is ahead of the switch gate: a switched-off write gets the shutdown failure")
    void shutdownGateIsAheadOfTheSwitchGate() {
        signal.open.set(false);
        RecordingChannel channel = new RecordingChannel();
        router(false).dispatch(frame("files.write"), channel);
        assertThat(channel.frames()).hasSize(1);
        assertShuttingDown(channel.frames().get(0), "files.write.result");
        assertThat(switchRefusals).isEmpty();
    }

    @Test
    @DisplayName("a handler that throws still returns its permit and the request still gets an answer")
    void throwingHandlerReturnsThePermit() {
        AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "unused");
        router.onShutdown(new ShutdownGate(signal, ledger));
        router.register("files.delete", new CloudRequestHandler() {
            @Override
            public void handle(CloudRequest request, CloudChannel channel) {
                throw new IllegalStateException("boom");
            }
        });
        RecordingChannel channel = new RecordingChannel();
        router.dispatch(frame("files.delete"), channel);
        assertThat(signal.entered.get()).isEqualTo(1);
        assertThat(signal.exited.get()).isEqualTo(1);
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("internal_error");
    }

    @Test
    @DisplayName("a redelivered ack-first id while shutting down is acknowledged again and answered nothing more")
    void redeliveredAckFirstIdIsOnlyAcknowledged() {
        signal.open.set(false);
        AgentMessageRouter router = router(true);
        RecordingChannel first = new RecordingChannel();
        router.dispatch(frame("command.execute"), first);
        RecordingChannel again = new RecordingChannel();
        router.dispatch(frame("command.execute"), again);
        assertThat(first.frames()).extracting(ShutdownGateTest::type)
                .isEqualTo(Arrays.asList("command.ack", "command.result"));
        assertThat(again.frames()).extracting(ShutdownGateTest::type).containsExactly("command.ack");
        assertThat(handled).isEmpty();
    }
}
