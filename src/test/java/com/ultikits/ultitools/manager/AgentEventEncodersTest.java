package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import com.ultikits.ultitools.contract.RecordingChannel;
import org.junit.jupiter.api.Test;

/**
 * Contract shapes for the event payloads: {@code player.command} carries the normalised name only,
 * {@code player.join}/{@code player.leave} carry the two player fields, and {@code error.report}
 * carries the contract's field set with its bounds and without anything the contract dropped.
 */
class AgentEventEncodersTest {

    @Test
    void aCommandEventCarriesTheNormalisedNameOnly() {
        JsonObject expected = ContractFixtures.object("fixtures/agent/player.command/valid/name-only.json")
                .getAsJsonObject("payload");
        JsonObject actual = AgentEventEncoders.playerCommand("login");
        assertThat(actual).isEqualTo(expected);

        RecordingChannel channel = new RecordingChannel();
        assertThat(channel.send("player.command", actual)).isTrue();
        assertThat(channel.frames().get(0).getAsJsonObject("payload")).isEqualTo(expected);
    }

    @Test
    void aCommandNameThatCannotSatisfyTheSchemaIsNotEncoded() {
        assertThat(AgentEventEncoders.playerCommand(null)).isNull();
        String[] invalid = {"ultilogin:login", "/login", "Login", "login secret", "", repeat('a', 65)};
        for (String name : invalid) {
            assertThat(AgentEventEncoders.playerCommand(name)).as(name).isNull();
        }
    }

    @Test
    void joinAndLeavePayloadsCarryTheTwoContractFields() {
        for (String type : new String[] {"player.join", "player.leave"}) {
            JsonObject payload = ContractFixtures.object("fixtures/agent/" + type + "/valid/basic.json")
                    .getAsJsonObject("payload");
            JsonObject actual = AgentEventEncoders.playerRef(
                    text(payload, "playerUuid"), text(payload, "playerName"));
            assertThat(actual).isEqualTo(payload);
            RecordingChannel channel = new RecordingChannel();
            assertThat(channel.send(type, actual)).isTrue();
        }

        JsonObject floodgate = ContractFixtures.object("fixtures/agent/player.join/valid/floodgate-prefix.json")
                .getAsJsonObject("payload");
        assertThat(AgentEventEncoders.playerRef(text(floodgate, "playerUuid"), ".Steve")).isEqualTo(floodgate);
    }

    @Test
    void aPlayerReferenceThatCannotSatisfyTheSchemaIsNotEncoded() {
        String uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        assertThat(AgentEventEncoders.playerRef("069A79F4-44E9-4726-A5BE-FCA90E38AAF5", "Steve")).isNull();
        assertThat(AgentEventEncoders.playerRef("069a79f444e94726a5befca90e38aaf5", "Steve")).isNull();
        assertThat(AgentEventEncoders.playerRef(uuid, repeat('a', 33))).isNull();
        assertThat(AgentEventEncoders.playerRef(uuid, "Steve Smith")).isNull();
        assertThat(AgentEventEncoders.playerRef(null, "Steve")).isNull();
        assertThat(AgentEventEncoders.playerRef(uuid, null)).isNull();
    }

    @Test
    void theVendoredErrorSamplesAreReproducedFieldForField() {
        for (String sample : new String[] {"full", "framework-no-player"}) {
            JsonObject payload = ContractFixtures.object("fixtures/agent/error.report/valid/" + sample + ".json")
                    .getAsJsonObject("payload");
            JsonObject encoded = AgentEventEncoders.errorReport(viewOf(payload));
            assertThat(encoded).as(sample).isEqualTo(payload);
            RecordingChannel channel = new RecordingChannel();
            assertThat(channel.send("error.report", encoded)).isTrue();
        }
        JsonObject full = ContractFixtures.object("fixtures/agent/error.report/valid/full.json")
                .getAsJsonObject("payload");
        assertThat(AgentEventEncoders.errorReport(new View())).isEqualTo(full);
    }

    @Test
    void valuesAreClampedIntoTheContractBounds() {
        View view = new View();
        view.fingerprint = "1a2b3";
        view.message = repeat('m', 900);
        view.stackTrace = repeat('s', 20_000);
        view.triggerDetail = repeat('d', 300);
        view.playerName = "Steve Smith";
        view.occurrences = 0;

        JsonObject payload = AgentEventEncoders.errorReport(view);
        assertThat(payload).isNotNull();
        assertThat(payload.get("fingerprint").getAsString()).isEqualTo("0001a2b3");
        assertThat(payload.get("message").getAsString()).hasSize(500);
        assertThat(payload.get("stackTrace").getAsString()).hasSize(16384);
        assertThat(payload.getAsJsonObject("trigger").get("detail").getAsString()).hasSize(256);
        assertThat(payload.get("playerName")).isEqualTo(JsonNull.INSTANCE);
        assertThat(payload.get("occurrences").getAsInt()).isEqualTo(1);
        assertThat(payload.keySet()).containsExactlyInAnyOrder("fingerprint", "exceptionType", "message",
                "stackTrace", "moduleName", "trigger", "playerName", "location", "snapshot", "occurrences",
                "firstSeenAt", "lastSeenAt");

        RecordingChannel channel = new RecordingChannel();
        assertThat(channel.send("error.report", payload)).isTrue();
    }

    @Test
    void emptyOptionalValuesBecomeNullAndTheDroppedFieldsNeverAppear() {
        View view = new View();
        view.moduleName = "";
        view.triggerDetail = "";
        view.location = null;
        view.tps = null;
        view.memUsedMb = null;
        view.memMaxMb = null;
        view.onlinePlayers = null;
        view.triggerKind = "reload";

        JsonObject payload = AgentEventEncoders.errorReport(view);
        assertThat(payload.get("moduleName")).isEqualTo(JsonNull.INSTANCE);
        assertThat(payload.get("location")).isEqualTo(JsonNull.INSTANCE);
        assertThat(payload.getAsJsonObject("trigger").get("detail")).isEqualTo(JsonNull.INSTANCE);
        assertThat(payload.getAsJsonObject("trigger").get("kind").getAsString()).isEqualTo("other");
        assertThat(payload.keySet()).doesNotContain("errorCode", "isFramework", "playerUUID");

        JsonObject snapshot = payload.getAsJsonObject("snapshot");
        assertThat(snapshot.keySet()).containsExactlyInAnyOrder("tps", "memUsedMb", "memMaxMb", "onlinePlayers");
        assertThat(snapshot.get("tps")).isEqualTo(JsonNull.INSTANCE);

        RecordingChannel channel = new RecordingChannel();
        assertThat(channel.send("error.report", payload)).isTrue();
    }

    @Test
    void theV1IsFrameworkSampleStaysInvalid() {
        JsonObject frame = ContractFixtures.object("fixtures/agent/error.report/invalid/v1-is-framework.json")
                .getAsJsonObject("frame");
        assertThat(ContractSchemas.isValid(frame)).isFalse();
    }

    @Test
    void theCollectorsTriggerTypesMapOntoTheContractKinds() {
        for (TriggerContext.TriggerType type : TriggerContext.TriggerType.values()) {
            View view = new View();
            view.triggerKind = type.name();
            JsonObject payload = AgentEventEncoders.errorReport(view);
            assertThat(payload).as(type.name()).isNotNull();
            assertThat(payload.getAsJsonObject("trigger").get("kind").getAsString())
                    .as(type.name())
                    .isEqualTo(contractKind(type));
            RecordingChannel channel = new RecordingChannel();
            assertThat(channel.send("error.report", payload)).as(type.name()).isTrue();
        }
    }

    @Test
    void theContractKindNamesAreAlsoAcceptedWhateverTheirCase() {
        for (String[] pair : new String[][] {{"Command", "command"}, {"event", "event"},
                {"scheduler", "scheduler"}, {"Scheduled", "scheduler"}}) {
            View view = new View();
            view.triggerKind = pair[0];
            JsonObject payload = AgentEventEncoders.errorReport(view);
            assertThat(payload.getAsJsonObject("trigger").get("kind").getAsString())
                    .as(pair[0])
                    .isEqualTo(pair[1]);
        }
    }

    /** The contract knows four kinds; the collector's AOP and uncaught have no match of their own. */
    private static String contractKind(TriggerContext.TriggerType type) {
        switch (type) {
            case COMMAND:
                return "command";
            case EVENT:
                return "event";
            case SCHEDULED:
                return "scheduler";
            default:
                return "other";
        }
    }

    @Test
    void unencodableInputReturnsNullAndNeverThrows() {
        View notHex = new View();
        notHex.fingerprint = "xyz";
        assertThat(AgentEventEncoders.errorReport(notHex)).isNull();

        View noType = new View();
        noType.exceptionType = "   ";
        assertThat(AgentEventEncoders.errorReport(noType)).isNull();

        assertThat(AgentEventEncoders.errorReport(null)).isNull();
    }

    /**
     * An equivalent of the collector's report: the values 22-22 maps into the encoder.
     */
    private static final class View implements AgentEventEncoders.ErrorReportView {
        private String fingerprint = "a91f3c2e7b0d";
        private String exceptionType = "java.lang.NullPointerException";
        private String message = "player is null";
        private String stackTrace = "java.lang.NullPointerException: player is null\n"
                + "\tat com.example.ChannelService.send(ChannelService.java:42)";
        private String moduleName = "UltiChat";
        private String triggerKind = "event";
        private String triggerDetail = "AsyncPlayerChatEvent";
        private String playerName = "Steve";
        private String location = "ChannelService.send";
        private Double tps = 19.8;
        private Long memUsedMb = 2048L;
        private Long memMaxMb = 8192L;
        private Long onlinePlayers = 2L;
        private int occurrences = 3;
        private long firstSeenAt = 1761274800000L;
        private long lastSeenAt = 1761274800000L;

        @Override public String fingerprint() { return fingerprint; }
        @Override public String exceptionType() { return exceptionType; }
        @Override public String message() { return message; }
        @Override public String stackTrace() { return stackTrace; }
        @Override public String moduleName() { return moduleName; }
        @Override public String triggerKind() { return triggerKind; }
        @Override public String triggerDetail() { return triggerDetail; }
        @Override public String playerName() { return playerName; }
        @Override public String location() { return location; }
        @Override public Double tps() { return tps; }
        @Override public Long memUsedMb() { return memUsedMb; }
        @Override public Long memMaxMb() { return memMaxMb; }
        @Override public Long onlinePlayers() { return onlinePlayers; }
        @Override public int occurrences() { return occurrences; }
        @Override public long firstSeenAt() { return firstSeenAt; }
        @Override public long lastSeenAt() { return lastSeenAt; }
    }

    private static AgentEventEncoders.ErrorReportView viewOf(JsonObject payload) {
        JsonObject trigger = payload.getAsJsonObject("trigger");
        JsonObject snapshot = payload.getAsJsonObject("snapshot");
        View view = new View();
        view.fingerprint = text(payload, "fingerprint");
        view.exceptionType = text(payload, "exceptionType");
        view.message = text(payload, "message");
        view.stackTrace = text(payload, "stackTrace");
        view.moduleName = text(payload, "moduleName");
        view.triggerKind = text(trigger, "kind");
        view.triggerDetail = text(trigger, "detail");
        view.playerName = text(payload, "playerName");
        view.location = text(payload, "location");
        view.tps = number(snapshot, "tps");
        view.memUsedMb = count(snapshot, "memUsedMb");
        view.memMaxMb = count(snapshot, "memMaxMb");
        view.onlinePlayers = count(snapshot, "onlinePlayers");
        view.occurrences = payload.get("occurrences").getAsInt();
        view.firstSeenAt = payload.get("firstSeenAt").getAsLong();
        view.lastSeenAt = payload.get("lastSeenAt").getAsLong();
        return view;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static Double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsDouble();
    }

    private static Long count(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(character);
        }
        return text.toString();
    }
}
