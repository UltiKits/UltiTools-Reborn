package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AckFirstGateTest {
    private static CloudRequest request() {
        return Envelope.parse(ContractFixtures.validFrames("command.execute").get(0).frame().toString(), 1048576).request();
    }

    @Test
    void recordsAndPersistsBeforeAckAndExecution() throws IOException {
        CloudRequest request = request();
        DedupeLedger ledger = mock(DedupeLedger.class);
        CloudChannel channel = spy(new RecordingChannel());
        Runnable execute = mock(Runnable.class);
        when(ledger.firstSeen(request.id())).thenReturn(true);
        AckFirstGate.handle(request, channel, ledger, execute);
        InOrder order = inOrder(ledger, channel, execute);
        order.verify(ledger).firstSeen(request.id());
        order.verify(ledger).persistNow();
        order.verify(channel).reply(eq(request.id()), eq("command.ack"), any(JsonObject.class));
        order.verify(execute).run();
    }

    @Test
    void repeatedIdIsAckedAgainButNotPersistedOrExecuted() {
        CloudRequest request = request();
        DedupeLedger ledger = mock(DedupeLedger.class);
        RecordingChannel channel = new RecordingChannel();
        Runnable execute = mock(Runnable.class);
        AckFirstGate.handle(request, channel, ledger, execute);
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(request.id());
        InOrder order = inOrder(ledger);
        order.verify(ledger).firstSeen(request.id());
        order.verifyNoMoreInteractions();
        verifyNoInteractions(execute);
    }

    @Test
    void persistenceFailureWarnsWithoutLeakingAndStillAcksBeforeExecuting() throws IOException {
        CloudRequest request = request();
        DedupeLedger ledger = mock(DedupeLedger.class);
        when(ledger.firstSeen(request.id())).thenReturn(true);
        doThrow(new IOException("private-path-must-not-be-logged")).when(ledger).persistNow();
        CloudChannel channel = spy(new RecordingChannel());
        Runnable execute = mock(Runnable.class);
        try (AgentMessageRouterTest.LogCapture capture = new AgentMessageRouterTest.LogCapture(AckFirstGate.class)) {
            AckFirstGate.handle(request, channel, ledger, execute);
            assertThat(capture.warnings()).hasSize(1);
            assertThat(capture.warnings().get(0).getMessage()).contains(request.id())
                    .doesNotContain("private-path-must-not-be-logged", request.payload().get("command").getAsString(),
                            request.payload().toString(), "\tat ");
            assertThat(capture.warnings().get(0).getThrown()).isNull();
            assertThat(capture.warnings().get(0).getParameters()).isNull();
        }
        InOrder order = inOrder(ledger, channel, execute);
        order.verify(ledger).firstSeen(request.id());
        order.verify(ledger).persistNow();
        order.verify(channel).reply(eq(request.id()), eq("command.ack"), any(JsonObject.class));
        order.verify(execute).run();
    }
}
