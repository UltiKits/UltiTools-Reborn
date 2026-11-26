package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractFixtures.Fixture;
import com.ultikits.ultitools.stub.UltiCloudStub;
import com.ultikits.ultitools.websocket.CloudLimits;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PendingChangesHandlerContractTest {
    private static final String LIST = "upm.pending.list";
    private static final String REVOKE = "upm.pending.revoke";
    private static final UltiCloudStub.Contract CONTRACT = UltiCloudStub.Contract.load(null);

    private final PendingChanges pending = mock(PendingChanges.class);
    private final List<String> wire = new ArrayList<>();
    private final FramedChannel channel = new FramedChannel(wire::add, () -> true, CloudLimits::defaults,
            Ulid::next, System::currentTimeMillis);

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("validRequests")
    void acceptsEveryValidDownstreamFixture(Fixture fixture) {
        JsonObject frame = fixture.frame();
        JsonObject payload = frame.getAsJsonObject("payload");
        String type = frame.get("type").getAsString();
        if (LIST.equals(type)) {
            when(pending.snapshot()).thenReturn(new PendingChanges.Snapshot(List.of(), 0, 0));
        } else {
            String revision = payload.get("revision").isJsonNull() ? null : payload.get("revision").getAsString();
            when(pending.revoke(payload.get("id").getAsString(), revision, true))
                    .thenReturn(PendingChanges.RevokeResult.notFound());
        }
        JsonObject reply = dispatch(frame);
        assertThat(reply.get("type").getAsString()).isEqualTo(type + ".result");
        assertThat(reply.getAsJsonObject("payload").get("status").getAsString())
                .isEqualTo(LIST.equals(type) ? "ok" : "error");
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidRequests")
    void rejectsEveryInvalidDownstreamFixture(Fixture fixture) {
        JsonObject frame = fixture.frame();
        JsonObject reply = dispatch(frame);
        assertThat(reply.get("type").getAsString()).isEqualTo("error");
        assertThat(reply.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        verifyNoInteractions(pending);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"id\":\"install-0123456789abcdef\",\"revision\":\"bad\",\"actor\":{\"userId\":1}}",
        "{\"id\":\"install-0123456789abcdef\",\"actor\":{\"userId\":1}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":0}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":1.1}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":9007199254740992}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":\"1\"}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":1,\"extra\":true}}",
        "{\"id\":\"install-0123456789abcdef\",\"revision\":null,\"actor\":{\"userId\":1},\"extra\":true}"
    })
    void invalidActorRevisionOrExtraFieldsCannotReachTheJournal(String json) {
        JsonObject frame = ContractFixtures.validFrames(REVOKE).getFirst().frame();
        frame.add("payload", JsonParser.parseString(json));
        JsonObject reply = dispatch(frame);
        assertThat(reply.get("type").getAsString()).isEqualTo("error");
        assertThat(reply.getAsJsonObject("payload").get("code").getAsString()).isEqualTo("invalid_message");
        verifyNoInteractions(pending);
    }

    private JsonObject dispatch(JsonObject frame) {
        var router = new AgentMessageRouter(capability -> true, capability -> "Unavailable");
        var handler = new PendingChangesHandler(() -> pending, List::of,
                new ShutdownGate(ShutdownGate.Signal.OPEN, null), Logger::getAnonymousLogger);
        router.register(LIST, handler);
        router.register(REVOKE, handler);
        router.dispatch(frame.toString(), channel);
        assertThat(wire).as(frame.toString()).hasSize(1);
        JsonObject reply = JsonParser.parseString(wire.getFirst()).getAsJsonObject();
        assertThat(reply.get("replyTo")).isEqualTo(frame.get("id"));
        String schema = UltiCloudStub.Contract.frameSchema(reply.get("type").getAsString());
        assertThat(CONTRACT.firstIssue(schema, reply)).isNull();
        return reply;
    }

    private static Stream<Fixture> validRequests() {
        return Stream.of(LIST, REVOKE).flatMap(type -> ContractFixtures.validFrames(type).stream());
    }

    private static Stream<Fixture> invalidRequests() {
        return Stream.of(LIST, REVOKE).flatMap(type -> ContractFixtures.invalidFrames(type).stream());
    }
}
