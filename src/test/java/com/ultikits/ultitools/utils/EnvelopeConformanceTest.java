package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractFixtures.Fixture;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.websocket.CloudLimits;
import com.ultikits.ultitools.websocket.CloudRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeConformanceTest {
    private static final String ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final int MAX = 1048576;

    static Stream<Fixture> validFrames() {
        return AgentMessageTypes.types().stream().flatMap(type -> ContractFixtures.validFrames(type).stream());
    }

    static Stream<Fixture> invalidFrames() {
        return Stream.concat(AgentMessageTypes.types().stream(), Stream.of("_unknown"))
                .flatMap(type -> ContractFixtures.invalidFrames(type).stream());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFrames")
    void everyValidFixtureRoundTrips(Fixture fixture) {
        JsonObject input = fixture.frame();
        ContractSchemas.assertFrameValid(input);
        Envelope envelope = Envelope.parse(input.toString(), MAX);
        assertThat(JsonParser.parseString(envelope.toJson())).isEqualTo(input);
        assertThat(JsonParser.parseString(Envelope.parse(envelope.toJson(), MAX).toJson())).isEqualTo(input);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFrames")
    void everyInvalidFixtureHasTheCorrectLayer(Fixture fixture) {
        String path = fixture.path();
        boolean envelopeFault = path.endsWith("/missing-replyTo.json") || path.endsWith("/unexpected-replyTo.json")
                || path.endsWith("/with-serverId-top-level.json") || path.contains("/_unknown/");
        if (envelopeFault) {
            InvalidFrameException error = assertThrows(InvalidFrameException.class,
                    () -> Envelope.parse(fixture.frame().toString(), MAX));
            assertThat(error.code()).isEqualTo(fixture.expect());
            assertThat(error.replyTo()).isEqualTo(fixture.frame().get("id").getAsString());
        } else {
            assertThatCode(() -> Envelope.parse(fixture.frame().toString(), MAX)).doesNotThrowAnyException();
        }
        if (!path.contains("/_unknown/")) {
            assertThat(ContractSchemas.isValid(fixture.frame())).as(path).isFalse();
        }
    }

    @Test
    void strictJsonHeadAndTopLevelChecksPrecedeDispatch() {
        for (String text : Arrays.asList("", "null", "[]", "42", "{} {}", "{unquoted:1}", "{'v':2}", "/*comment*/{}")) {
            assertThat(assertThrows(InvalidFrameException.class, () -> Envelope.parse(text, MAX)).code())
                    .isEqualTo("invalid_message");
        }
        for (String key : Arrays.asList("serverId", "seq", "extra")) {
            JsonObject frame = command();
            frame.addProperty(key, "unexpected");
            assertInvalid(frame, ID);
        }
        for (String key : Arrays.asList("v", "id", "type", "ts", "payload")) {
            JsonObject frame = command();
            frame.remove(key);
            assertInvalid(frame, "id".equals(key) ? null : ID);
        }
        for (JsonElement ts : Arrays.asList(new JsonPrimitive(-1), new JsonPrimitive(1.5),
                new JsonPrimitive("1000"), new JsonPrimitive(8640000000000001L), JsonNull.INSTANCE)) {
            JsonObject frame = command(); frame.add("ts", ts); assertInvalid(frame, ID);
        }
        for (JsonElement v : Arrays.asList(new JsonPrimitive(1), new JsonPrimitive("2"), new JsonPrimitive(true))) {
            JsonObject frame = command(); frame.add("v", v); assertInvalid(frame, ID);
        }
        JsonObject frame = command(); frame.addProperty("type", "Not_a_type"); assertInvalid(frame, ID);
        frame.addProperty("type", "unknown.type"); frame.addProperty("extra", 1);
        assertThat(assertThrows(InvalidFrameException.class, () -> Envelope.parse(frame.toString(), MAX)).code())
                .isEqualTo("unsupported_type");
        JsonObject reply = ContractFixtures.validFrames("command.ack").get(0).frame();
        reply.add("replyTo", JsonNull.INSTANCE); assertInvalid(reply, reply.get("id").getAsString());
    }

    @Test
    void byteLimitIsCheckedBeforeParsing() {
        String text = "not-json-😀";
        InvalidFrameException ex = assertThrows(InvalidFrameException.class,
                () -> Envelope.parse(text, text.getBytes(StandardCharsets.UTF_8).length - 1));
        assertThat(ex.code()).isEqualTo("payload_too_large");
        assertThat(ex.replyTo()).isNull();
        String valid = command().toString();
        assertThatCode(() -> Envelope.parse(valid, valid.getBytes(StandardCharsets.UTF_8).length)).doesNotThrowAnyException();
    }

    @Test
    void primitivesMatchContractExamples() {
        assertThat(ContractPrimitives.ULID.matcher(ID).matches()).isTrue();
        for (String bad : Arrays.asList(ID.toLowerCase(java.util.Locale.ROOT), ID.substring(0, 25) + "I",
                ID.substring(0, 25) + "L", ID.substring(0, 25) + "O", ID.substring(0, 25) + "U")) {
            assertThat(ContractPrimitives.ULID.matcher(bad).matches()).isFalse();
        }
        assertPattern(ContractPrimitives.MC_NAME, Arrays.asList(".Steve", "玩家"), Arrays.asList("a b", "a\nb", ""));
        assertPattern(ContractPrimitives.SERVER_PATH, Arrays.asList("plugins/config.yml", ".hidden/a", "a..b"),
                Arrays.asList("../a", "a/../b", "a\\b", "C:a", "/a", "a/", "a//b", ".", ".."));
        assertPattern(ContractPrimitives.MODULE_IDENTIFIER, Arrays.asList("ultikits.kits", "ulti-mail"),
                Arrays.asList("UltiMail", "ulti__mail", "a..b"));
        assertPattern(ContractPrimitives.SEMVER, Arrays.asList("6.3.1-SNAPSHOT", "1.0.0"), Arrays.asList("01.0.0", "1.0"));
        assertPattern(ContractPrimitives.MC_UUID, Arrays.asList("12345678-1234-1234-1234-123456789abc"), Arrays.asList("ABC", ID));
        assertPattern(ContractPrimitives.SHA256_HEX, Arrays.asList(String.join("", java.util.Collections.nCopies(64, "a"))), Arrays.asList("ABCD"));
        assertPattern(ContractPrimitives.CAPABILITY, Arrays.asList("files:read", "modules:ultikits.kits@1.2.0"), Arrays.asList("Files:read", "modules:a@1"));
        assertThat(ContractPrimitives.isModuleCapability("modules:ultikits.kits@1.2.0")).isTrue();
        assertThat(ContractPrimitives.isModuleCapability("modules:UltiMail@1.2.0")).isFalse();
        assertThat(ContractPrimitives.isModuleCapability("modules:" + String.join("", java.util.Collections.nCopies(65, "a")) + "@1.2.0")).isFalse();
        assertThat(ContractPrimitives.isModuleCapability(null)).isFalse();
        String credential = ContractFixtures.validFrames("credential.rotate").get(0).frame()
                .getAsJsonObject("payload").get("credential").getAsString();
        assertThat(ContractPrimitives.SERVER_CREDENTIAL.matcher(credential).matches()).isTrue();
        assertThat(ContractPrimitives.SERVER_CREDENTIAL.matcher(credential.replace("ucs_", "ucc_")).matches()).isFalse();
    }

    @Test
    void payloadReaderIsStrictAndCountsCodePoints() {
        JsonObject payload = JsonParser.parseString("{\"text\":\"😀\",\"count\":3,\"ratio\":0.5,\"flag\":true,\"mode\":\"on\",\"items\":[1],\"nested\":{}}")
                .getAsJsonObject();
        PayloadReader reader = new PayloadReader(ID, payload).keys(new String[] {"text", "count", "ratio", "flag", "mode", "items", "nested"}, "optional");
        assertThat(reader.string("text", 1, 1, null)).isEqualTo("😀");
        assertThat(reader.integer("count", 1, 3)).isEqualTo(3);
        assertThat(reader.number("ratio", 0, 1)).isEqualTo(0.5);
        assertThat(reader.bool("flag")).isTrue();
        assertThat(reader.enumValue("mode", "on", "off")).isEqualTo("on");
        assertThat(reader.array("items", 1, 1)).hasSize(1);
        assertThat(reader.object("nested").size()).isZero();
        assertThat(reader.optional("optional")).isNull();
        assertThatThrownBy(() -> reader.string("count", 0, 5, null)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.integer("ratio", 0, 1)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.integer("text", 0, 1)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.integer("count", 0, 2)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.number("ratio", 1, 2)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.bool("mode")).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.enumValue("mode", "off")).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.array("items", 2, 3)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.string("text", 2, 3, null)).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.string("text", 1, 2, Pattern.compile("^[a-z]+$"))).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.required("missing")).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.keys(new String[] {"missing"})).isInstanceOf(InvalidFrameException.class);
        assertThatThrownBy(() -> reader.keys(new String[] {"text"})).isInstanceOf(InvalidFrameException.class);
        payload.add("nullValue", JsonNull.INSTANCE);
        assertThatThrownBy(() -> new PayloadReader(ID, payload).string("nullValue", 0, 5, null)).isInstanceOf(InvalidFrameException.class);
        InvalidFrameException error = assertThrows(InvalidFrameException.class, () -> reader.integer("text", 0, 1));
        assertThat(error.code()).isEqualTo("invalid_message");
        assertThat(error.replyTo()).isEqualTo(ID);
    }

    @Test
    void defaultsAndEveryLimitBoundaryMatchTheSchema() {
        JsonObject limits = ContractFixtures.object("fixtures/agent/welcome/valid/default-limits.json").getAsJsonObject("payload").getAsJsonObject("limits");
        CloudLimits parsed = CloudLimits.fromWelcome(limits);
        assertThat(parsed).usingRecursiveComparison().isEqualTo(CloudLimits.defaults());
        String[] paths = {"maxFrameBytes", "log.ratePerSec", "log.burst", "log.lineMaxBytes", "log.batchMaxLines", "outboxMax", "commandDedupeMax", "commandDedupeTtlSeconds", "commandOutputMaxBytes", "fileMaxBytes"};
        int[] mins = {65536, 1, 1, 256, 1, 1, 1, 60, 1024, 1024};
        int[] maxes = {1048576, 1000, 10000, 8192, 100, 10000, 10000, 604800, 65536, 393216};
        for (int i = 0; i < paths.length; i++) {
            for (int value : new int[] {mins[i], maxes[i]}) {
                JsonObject candidate = limits.deepCopy(); setLimit(candidate, paths[i], new JsonPrimitive(value));
                assertThatCode(() -> CloudLimits.fromWelcome(candidate)).doesNotThrowAnyException();
            }
            for (JsonElement value : Arrays.asList(new JsonPrimitive(mins[i] - 1), new JsonPrimitive(maxes[i] + 1),
                    new JsonPrimitive(1.5), new JsonPrimitive("1000"), JsonNull.INSTANCE)) {
                JsonObject candidate = limits.deepCopy(); setLimit(candidate, paths[i], value);
                assertThatThrownBy(() -> CloudLimits.fromWelcome(candidate)).isInstanceOf(IllegalArgumentException.class);
            }
            JsonObject missing = limits.deepCopy(); setLimit(missing, paths[i], null);
            assertThatThrownBy(() -> CloudLimits.fromWelcome(missing)).isInstanceOf(IllegalArgumentException.class);
        }
        JsonObject extra = limits.deepCopy(); extra.addProperty("extra", 1);
        assertThatThrownBy(() -> CloudLimits.fromWelcome(extra)).isInstanceOf(IllegalArgumentException.class);
        JsonObject nestedExtra = limits.deepCopy(); nestedExtra.getAsJsonObject("log").addProperty("extra", 1);
        assertThatThrownBy(() -> CloudLimits.fromWelcome(nestedExtra)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CloudLimits.fromWelcome(ContractFixtures.object("fixtures/agent/welcome/invalid/batch-too-large.json")
                .getAsJsonObject("frame").getAsJsonObject("payload").getAsJsonObject("limits"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void channelRejectsCorrelationProgrammingErrorsForEveryType() {
        AtomicInteger sent = new AtomicInteger();
        FramedChannel channel = new FramedChannel(text -> { sent.incrementAndGet(); return true; }, () -> true,
                CloudLimits::defaults, Ulid::next, () -> 1L);
        for (String type : AgentMessageTypes.types()) {
            AgentMessageTypes.ReplyTo rule = AgentMessageTypes.spec(type).replyTo();
            if (rule == AgentMessageTypes.ReplyTo.REQUIRED) {
                assertThatThrownBy(() -> channel.send(type, new JsonObject())).isInstanceOf(IllegalArgumentException.class);
            } else if (rule == AgentMessageTypes.ReplyTo.FORBIDDEN) {
                assertThatThrownBy(() -> channel.reply(ID, type, new JsonObject())).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> channel.send("unregistered", new JsonObject())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.reply("bad", "command.ack", new JsonObject())).isInstanceOf(IllegalArgumentException.class);
        assertThat(sent).hasValue(0);
    }

    @Test
    void requestCopiesPayloadAndNeverPrintsIt() {
        JsonObject payload = new JsonObject(); payload.addProperty("secret", "not-in-diagnostics");
        CloudRequest request = new CloudRequest(ID, "credential.rotate", 0, null, payload);
        payload.addProperty("secret", "changed"); request.payload().addProperty("secret", "changed-again");
        assertThat(request.payload().get("secret").getAsString()).isEqualTo("not-in-diagnostics");
        assertThat(request.toString()).contains(ID, "credential.rotate").doesNotContain("not-in-diagnostics", "changed");
    }

    private static void setLimit(JsonObject limits, String path, JsonElement value) {
        String key = path;
        JsonObject parent = limits;
        if (path.startsWith("log.")) { parent = limits.getAsJsonObject("log"); key = path.substring(4); }
        if (value == null) { parent.remove(key); } else { parent.add(key, value); }
    }

    private static JsonObject command() {
        JsonObject frame = ContractFixtures.object("fixtures/agent/command.execute/valid/say.json");
        frame.addProperty("id", ID);
        return frame;
    }

    private static void assertInvalid(JsonObject frame, String replyTo) {
        InvalidFrameException ex = assertThrows(InvalidFrameException.class, () -> Envelope.parse(frame.toString(), MAX));
        assertThat(ex.code()).isEqualTo("invalid_message");
        assertThat(ex.replyTo()).isEqualTo(replyTo);
    }

    private static void assertPattern(Pattern pattern, List<String> valid, List<String> invalid) {
        for (String value : valid) { assertThat(pattern.matcher(value).matches()).as(value).isTrue(); }
        for (String value : invalid) { assertThat(pattern.matcher(value).matches()).as(value).isFalse(); }
    }
}
