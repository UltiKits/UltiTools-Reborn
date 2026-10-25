package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.entities.Capability;
import com.ultikits.ultitools.manager.RemoteActionLog;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PowerActionEndToEndTest {
    @Test
    void disabledStopIsAcknowledgedAndDeniedWithoutTouchingTheExecutor() throws Exception {
        String fixture = ContractFixtures.text("fixtures/agent/server.power/valid/stop.json");
        String id = JsonParser.parseString(fixture).getAsJsonObject().get("id").getAsString();
        RecordingChannel channel = spy(new RecordingChannel());
        DedupeLedger ledger = mock(DedupeLedger.class);
        when(ledger.firstSeen(id)).thenReturn(true);
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        Runnable hook = mock(Runnable.class);
        List<RemoteActionLog.Entry> audit = new ArrayList<>();
        AgentMessageRouter router = router(new PowerActionHandler(ledger, () -> false, executor, hook, audit::add));

        router.dispatch(fixture, channel);

        assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                .containsExactly("command.ack", "server.power.result");
        JsonObject result = channel.frames().get(1);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("error");
        assertThat(result.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("power_disabled");
        assertThat(result.getAsJsonObject("payload").get("message").getAsString())
                .isEqualTo(Capability.POWER_ACTIONS.refusalMessage()).contains("ultipanel.capabilities.power-actions");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.DENIED);
        assertThat(audit.get(0).getReason()).isEqualTo(Capability.POWER_ACTIONS.refusalMessage());
        assertThat(audit.get(0).getCapability()).isEqualTo("POWER_ACTIONS");
        assertThat(audit.get(0).getActor()).isEqualTo("panel");
        InOrder order = inOrder(ledger, channel);
        order.verify(ledger).firstSeen(id);
        order.verify(ledger).persistNow();
        order.verify(channel).reply(eq(id), eq("command.ack"), any(JsonObject.class));
        verifyNoInteractions(executor, hook);
    }

    @Test
    void enabledStopSendsResultBeforeTheHookAndScheduleCall() throws Exception {
        String fixture = ContractFixtures.text("fixtures/agent/server.power/valid/stop.json");
        String id = JsonParser.parseString(fixture).getAsJsonObject().get("id").getAsString();
        RecordingChannel channel = spy(new RecordingChannel());
        DedupeLedger ledger = mock(DedupeLedger.class);
        when(ledger.firstSeen(id)).thenReturn(true);
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        Runnable hook = mock(Runnable.class);
        doAnswer(call -> {
            assertThat(channel.frames()).extracting(frame -> frame.get("type").getAsString())
                    .containsExactly("command.ack", "server.power.result");
            return null;
        }).when(hook).run();
        List<RemoteActionLog.Entry> audit = new ArrayList<>();

        router(new PowerActionHandler(ledger, () -> true, executor, hook, audit::add)).dispatch(fixture, channel);

        JsonObject result = channel.frames().get(1);
        assertThat(result.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(result.getAsJsonObject("payload").get("status").getAsString()).isEqualTo("ok");
        assertThat(result.getAsJsonObject("payload").getAsJsonObject("data").get("action").getAsString())
                .isEqualTo("stop");
        InOrder order = inOrder(ledger, channel, hook, executor);
        order.verify(ledger).firstSeen(id);
        order.verify(ledger).persistNow();
        order.verify(channel).reply(eq(id), eq("command.ack"), any(JsonObject.class));
        order.verify(channel).replyOk(eq(id), eq("server.power.result"), any(JsonObject.class));
        order.verify(hook).run();
        order.verify(executor).scheduleStop();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getVerdict()).isEqualTo(RemoteActionLog.Verdict.ALLOWED);
        assertThat(audit.get(0).getAction()).isEqualTo("server.power.stop");
        assertThat(audit.get(0).getTarget()).isEqualTo("stop");
    }

    @Test
    void invalidActionIsRejectedBeforeRecordingOrAcknowledgingTheId() {
        DedupeLedger ledger = mock(DedupeLedger.class);
        PowerActionHandler.PowerExecutor executor = mock(PowerActionHandler.PowerExecutor.class);
        Runnable hook = mock(Runnable.class);
        RecordingChannel channel = new RecordingChannel();
        List<RemoteActionLog.Entry> audit = new ArrayList<>();

        router(new PowerActionHandler(ledger, () -> true, executor, hook, audit::add))
                .dispatch(ContractFixtures.object("fixtures/agent/server.power/invalid/kill.json")
                        .getAsJsonObject("frame").toString(), channel);

        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("type").getAsString()).isEqualTo("error");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("invalid_message");
        verifyNoInteractions(ledger, executor, hook);
        assertThat(audit).isEmpty();
    }

    private AgentMessageRouter router(PowerActionHandler handler) {
        AgentMessageRouter router = new AgentMessageRouter("power"::equals, capability -> "not accepted");
        router.register("server.power", handler);
        return router;
    }
}
