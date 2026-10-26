package com.ultikits.ultitools.utils;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentMessageRouterTest {
    private static final String ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Test
    void invalidAndUnsupportedFramesProduceCorrelatedSchemaValidErrors() {
        AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "disabled");
        RecordingChannel channel = new RecordingChannel();
        JsonObject invalid = frame("command.execute"); invalid.addProperty("seq", 1);
        router.dispatch(invalid.toString(), channel);
        assertError(channel, ID, "invalid_message");
        channel.clear();
        JsonObject unknown = ContractFixtures.object("fixtures/agent/_unknown/invalid/auth-complete.json").getAsJsonObject("frame");
        router.dispatch(unknown.toString(), channel);
        assertError(channel, unknown.get("id").getAsString(), "unsupported_type");
    }

    @Test
    void disabledOrUnimplementedCapabilitiesCannotReachHandlers() {
        CloudRequestHandler handler = mock(CloudRequestHandler.class);
        AgentMessageRouter router = new AgentMessageRouter(cap -> false,
                cap -> "Enable ultipanel.capabilities.commands in config.yml.");
        router.register("command.execute", handler);
        RecordingChannel channel = new RecordingChannel();
        router.dispatch(frame("command.execute").toString(), channel);
        assertError(channel, ID, "capability_not_accepted");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("message").getAsString())
                .contains("ultipanel.capabilities.commands");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").getAsJsonObject("details")
                .get("capability").getAsString()).isEqualTo("commands");
        verifyNoInteractions(handler);
        channel.clear();
        new AgentMessageRouter(cap -> true, cap -> "disabled").dispatch(frame("backups.list").toString(), channel);
        assertError(channel, ID, "capability_not_accepted");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").getAsJsonObject("details")
                .get("capability").getAsString()).isEqualTo("backups");
    }

    @Test
    void theSwitchRefusalListenerHearsEachGateRefusalAfterItsReplyAndNeverAHandledRequest() {
        List<String> heard = new ArrayList<>();
        RecordingChannel channel = new RecordingChannel();
        AgentMessageRouter refusing = new AgentMessageRouter(cap -> false, cap -> "Enable " + cap + ".");
        refusing.onSwitchRefusal((request, capability, message) -> {
            assertThat(channel.frames()).as("the reply is sent before the listener runs").hasSize(1);
            heard.add(request.type() + "|" + request.id() + "|" + capability + "|" + message);
        });
        refusing.dispatch(frame("command.execute").toString(), channel);
        assertError(channel, ID, "capability_not_accepted");
        assertThat(heard).containsExactly("command.execute|" + ID + "|commands|Enable commands.");

        heard.clear();
        AgentMessageRouter allowing = new AgentMessageRouter(cap -> true, cap -> "unused");
        allowing.register("command.execute", mock(CloudRequestHandler.class));
        allowing.onSwitchRefusal((request, capability, message) -> heard.add(request.type()));
        allowing.dispatch(frame("command.execute").toString(), new RecordingChannel());
        allowing.dispatch(frame("backups.list").toString(), new RecordingChannel());
        assertThat(heard).as("a handled request and a capability without a handler are not switch refusals")
                .isEmpty();

        RecordingChannel throwing = new RecordingChannel();
        refusing.onSwitchRefusal((request, capability, message) -> {
            throw new IllegalStateException("log down");
        });
        refusing.dispatch(frame("command.execute").toString(), throwing);
        assertError(throwing, ID, "capability_not_accepted");
    }

    @Test
    void enabledCapabilitiesAndRegisteredControlHandlersRun() {
        CloudRequestHandler handler = mock(CloudRequestHandler.class);
        AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "disabled");
        router.register("server.power", handler);
        router.dispatch(frame("server.power").toString(), new RecordingChannel());
        verify(handler).handle(any(), any());
        CloudRequestHandler control = mock(CloudRequestHandler.class);
        router.register("welcome", control);
        router.dispatch(ContractFixtures.validFrames("welcome").get(0).frame().toString(), new RecordingChannel());
        verify(control).handle(any(), any());
    }

    @Test
    void moduleRequestsPassTheLiteralSentinelToTheCapabilityPredicate() {
        assertThat(AgentMessageTypes.spec("module.request").capability()).isEqualTo("fromPayload:module");
        List<String> checked = new ArrayList<>();
        AgentMessageRouter router = new AgentMessageRouter(capability -> {
            checked.add(capability);
            return "fromPayload:module".equals(capability);
        }, capability -> "disabled");
        RecordingChannel channel = new RecordingChannel();
        router.register("module.request", (request, replies) ->
                replies.replyOk(request.id(), "module.response", JsonNull.INSTANCE));

        router.dispatch(frame("module.request").toString(), channel);

        assertThat(checked).containsExactly("fromPayload:module");
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("type").getAsString()).isEqualTo("module.response");
        assertThat(channel.frames().get(0).get("replyTo").getAsString()).isEqualTo(ID);
    }

    @Test
    void rejectingTheModuleSentinelPreventsModuleDispatch() {
        CloudRequestHandler handler = mock(CloudRequestHandler.class);
        AgentMessageRouter router = new AgentMessageRouter(capability -> false, capability -> "disabled");
        router.register("module.request", handler);
        RecordingChannel channel = new RecordingChannel();

        router.dispatch(frame("module.request").toString(), channel);

        assertError(channel, ID, "capability_not_accepted");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").getAsJsonObject("details")
                .get("capability").getAsString()).isEqualTo("fromPayload:module");
        verifyNoInteractions(handler);
    }

    @Test
    void malformedPayloadHasInvalidMessageRatherThanInternalError() {
        AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "disabled");
        router.register("command.execute", (request, channel) -> new PayloadReader(request.id(), request.payload()).bool("missing"));
        RecordingChannel channel = new RecordingChannel();
        router.dispatch(frame("command.execute").toString(), channel);
        assertError(channel, ID, "invalid_message");
    }

    @Test
    void unknownIdAndUnhandledControlMessagesAreQuietFineRecords() {
        try (LogCapture capture = new LogCapture(AgentMessageRouter.class)) {
            AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "disabled");
            RecordingChannel channel = new RecordingChannel();
            router.dispatch("not json", channel);
            for (String type : Arrays.asList("error", "ack", "welcome")) {
                router.dispatch(ContractFixtures.validFrames(type).get(0).frame().toString(), channel);
            }
            assertThat(channel.frames()).isEmpty();
            assertThat(capture.records).hasSize(4).allSatisfy(record -> assertThat(record.getLevel()).isEqualTo(Level.FINE));
        }
    }

    @Test
    void handlerErrorsAreRateLimitedPerCodeAndNeverContainCredentials() {
        String secret = ContractFixtures.validFrames("credential.rotate").get(0).frame()
                .getAsJsonObject("payload").get("credential").getAsString();
        AtomicLong now = new AtomicLong(0);
        try (LogCapture capture = new LogCapture(AgentMessageRouter.class)) {
            AgentMessageRouter router = new AgentMessageRouter(cap -> true, cap -> "disabled", now::get);
            router.register("credential.rotate", (request, channel) -> { throw new IllegalStateException(secret); });
            RecordingChannel channel = new RecordingChannel();
            JsonObject request = frame("credential.rotate");
            for (int i = 0; i < 5; i++) { router.dispatch(request.toString(), channel); }
            assertThat(capture.warnings()).hasSize(1);
            now.set(59999); router.dispatch(request.toString(), channel);
            assertThat(capture.warnings()).hasSize(1);
            now.set(60000); router.dispatch(request.toString(), channel);
            assertThat(capture.warnings()).hasSize(2);
            JsonObject invalid = frame("credential.rotate"); invalid.addProperty("extra", secret);
            router.dispatch(invalid.toString(), channel);
            assertThat(capture.warnings()).hasSize(3);
            assertThat(channel.frames()).hasSize(8);
            for (JsonObject response : channel.frames()) {
                assertThat(response.toString()).doesNotContain(secret, "IllegalStateException", "\tat ");
                ContractSchemas.assertFrameValid(response);
            }
            assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString()).isEqualTo("internal_error");
            assertThat(capture.records).allSatisfy(record -> {
                assertThat(record.getMessage()).doesNotContain(secret);
                assertThat(record.getThrown()).isNull();
                assertThat(record.getParameters()).isNull();
            });
        }
    }

    @Test
    void oversizeRepliesAreReplacedAndOversizeEventsAreDropped() {
        RecordingChannel sink = new RecordingChannel();
        JsonObject limitsJson = ContractFixtures.validFrames("welcome").get(0).frame().getAsJsonObject("payload").getAsJsonObject("limits");
        limitsJson.addProperty("maxFrameBytes", 65536);
        CloudLimits limits = CloudLimits.fromWelcome(limitsJson);
        FramedChannel channel = new FramedChannel(sink::accept, () -> true, () -> limits, Ulid::next, () -> 1000L);
        String huge = String.join("", java.util.Collections.nCopies(65536, "😀"));
        assertThat(channel.replyOk(ID, "module.response", new com.google.gson.JsonPrimitive(huge))).isFalse();
        assertError(sink, ID, "payload_too_large");
        sink.clear();
        JsonObject details = new JsonObject(); details.addProperty("oversize", huge);
        assertThat(channel.replyPartial(ID, "config.update.result", details, null, null)).isFalse();
        assertError(sink, ID, "payload_too_large");
        sink.clear();
        assertThat(channel.replyError(ID, "module.response", "internal_error", huge, null)).isFalse();
        assertError(sink, ID, "payload_too_large");
        sink.clear();
        JsonObject payload = new JsonObject(); payload.addProperty("large", huge);
        try (LogCapture capture = new LogCapture(FramedChannel.class)) {
            assertThat(channel.send("status", payload)).isFalse();
            assertThat(sink.frames()).isEmpty();
            assertThat(capture.records).hasSize(1);
            assertThat(capture.records.get(0).getLevel()).isEqualTo(Level.FINE);
        }
    }

    @Test
    void validOutcomeHelpersAndClosedSocketRespectReturnValues() {
        RecordingChannel sink = new RecordingChannel();
        FramedChannel channel = new FramedChannel(sink::accept, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
        assertThat(channel.replyOk(ID, "module.response", JsonNull.INSTANCE)).isTrue();
        assertThat(sink.frames().get(0).getAsJsonObject("payload").get("data")).isEqualTo(JsonNull.INSTANCE);
        assertThat(channel.replyError(ID, "module.response", "module_error", "Handler failed.", null)).isTrue();
        JsonObject partial = ContractFixtures.validFrames("config.update.result").stream().map(ContractFixtures.Fixture::frame)
                .map(frame -> frame.getAsJsonObject("payload")).filter(p -> "partial".equals(p.get("status").getAsString())).findFirst().get();
        assertThat(channel.replyPartial(ID, "config.update.result", partial.get("data"), null, null)).isTrue();
        FramedChannel closed = new FramedChannel(text -> { throw new AssertionError("Closed send"); },
                () -> false, CloudLimits::defaults, Ulid::next, () -> 1000L);
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.send("hello", new JsonObject())).isFalse();
        assertThat(closed.reply(ID, "command.ack", new JsonObject())).isFalse();
        FramedChannel refused = new FramedChannel(text -> false, () -> true, CloudLimits::defaults, Ulid::next, () -> 1000L);
        assertThat(refused.reply(ID, "command.ack", new JsonObject())).isFalse();
    }

    @Test
    void recordingChannelRejectsInvalidOutputImmediately() {
        RecordingChannel channel = new RecordingChannel();
        assertThat(channel.reply(ID, "command.ack", new JsonObject())).isTrue();
        assertThatThrownBy(() -> channel.send("command.ack", new JsonObject())).isInstanceOf(AssertionError.class);
        assertThat(channel.frames()).hasSize(1);
        channel.setOpen(false);
        assertThat(channel.send("hello", new JsonObject())).isFalse();
    }

    private static JsonObject frame(String type) {
        JsonObject frame = ContractFixtures.validFrames(type).get(0).frame();
        frame.addProperty("id", ID);
        return frame;
    }

    private static void assertError(RecordingChannel channel, String id, String code) {
        assertThat(channel.frames()).hasSize(1);
        JsonObject frame = channel.frames().get(0);
        assertThat(frame.get("type").getAsString()).isEqualTo("error");
        assertThat(frame.get("replyTo").getAsString()).isEqualTo(id);
        assertThat(frame.getAsJsonObject("payload").get("code").getAsString()).isEqualTo(code);
        ContractSchemas.assertFrameValid(frame);
    }

    static final class LogCapture extends Handler implements AutoCloseable {
        private final Logger logger;
        private final Level oldLevel;
        private final boolean oldParents;
        final List<LogRecord> records = new ArrayList<>();

        LogCapture(Class<?> type) {
            logger = Logger.getLogger(type.getName());
            oldLevel = logger.getLevel(); oldParents = logger.getUseParentHandlers();
            logger.setUseParentHandlers(false); logger.setLevel(Level.ALL); setLevel(Level.ALL); logger.addHandler(this);
        }

        List<LogRecord> warnings() {
            List<LogRecord> warnings = new ArrayList<>();
            for (LogRecord record : records) { if (record.getLevel() == Level.WARNING) { warnings.add(record); } }
            return warnings;
        }
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { logger.removeHandler(this); logger.setLevel(oldLevel); logger.setUseParentHandlers(oldParents); }
    }
}
