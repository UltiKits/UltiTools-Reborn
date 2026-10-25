package com.ultikits.ultitools.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.AgentMessageRouter;
import com.ultikits.ultitools.utils.ContractPrimitives;
import com.ultikits.ultitools.utils.PayloadReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFoundationAccessTest {
    private static final String ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

    @Test
    void managerHandlersCanUseEveryPayloadReaderOperation() {
        JsonObject payload = new JsonObject();
        payload.addProperty("path", "plugins/example/config.yml");
        payload.addProperty("count", 2);
        payload.addProperty("ratio", 1.5);
        payload.addProperty("enabled", true);
        payload.addProperty("mode", "read");
        JsonArray items = new JsonArray();
        items.add("item");
        payload.add("items", items);
        payload.add("options", new JsonObject());
        PayloadReader reader = new PayloadReader(ID, payload).keys(
                new String[]{"path", "count", "ratio", "enabled", "mode", "items", "options"}, "optional");

        assertThat(reader.string("path", 1, 1024, ContractPrimitives.SERVER_PATH))
                .isEqualTo("plugins/example/config.yml");
        assertThat(reader.required("path").getAsString()).isEqualTo("plugins/example/config.yml");
        assertThat(reader.optional("path").getAsString()).isEqualTo("plugins/example/config.yml");
        assertThat(reader.optional("optional")).isNull();
        assertThat(reader.integer("count", 1, 3)).isEqualTo(2);
        assertThat(reader.number("ratio", 0, 2)).isEqualTo(1.5);
        assertThat(reader.bool("enabled")).isTrue();
        assertThat(reader.enumValue("mode", "read", "write")).isEqualTo("read");
        assertThat(reader.array("items", 1, 2).get(0).getAsString()).isEqualTo("item");
        assertThat(reader.object("options").entrySet()).isEmpty();
    }

    @Test
    void managerEncodersCanUseTheSharedContractPrimitives() {
        assertThat(ContractPrimitives.ULID.matcher(ID).matches()).isTrue();
        assertThat(ContractPrimitives.TYPE_NAME.matcher("files.read").matches()).isTrue();
        assertThat(ContractPrimitives.SEMVER.matcher("6.3.1-SNAPSHOT").matches()).isTrue();
        assertThat(ContractPrimitives.MC_UUID.matcher("00000000-0000-0000-0000-000000000001").matches()).isTrue();
        assertThat(ContractPrimitives.MC_NAME.matcher(".Steve").matches()).isTrue();
        assertThat(ContractPrimitives.SHA256_HEX.matcher(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").matches()).isTrue();
        assertThat(ContractPrimitives.MODULE_IDENTIFIER.matcher("ultikits.example").matches()).isTrue();
        assertThat(ContractPrimitives.CAPABILITY.matcher("modules:ultikits.example@1.0.0").matches()).isTrue();
        String credential = ContractFixtures.validFrames("credential.rotate").get(0).frame()
                .getAsJsonObject("payload").get("credential").getAsString();
        assertThat(ContractPrimitives.SERVER_CREDENTIAL.matcher(credential).matches()).isTrue();
        assertThat(ContractPrimitives.isModuleCapability("modules:ultikits.example@1.0.0")).isTrue();
        assertThat(ContractPrimitives.isModuleCapability("files:read")).isFalse();
    }

    @Test
    void managerHandlersCanDispatchValidAndInvalidPayloadsThroughTheRouter() {
        AgentMessageRouter router = new AgentMessageRouter(capability -> true, capability -> "disabled", () -> 1000L);
        router.register("files.read", (request, channel) -> {
            new PayloadReader(request.id(), request.payload()).keys(new String[]{"path"})
                    .string("path", 1, 1024, ContractPrimitives.SERVER_PATH);
            channel.replyError(request.id(), "files.read.result", "not_found", "File not found.", null);
        });
        RecordingChannel channel = new RecordingChannel();
        JsonObject request = ContractFixtures.validFrames("files.read").get(0).frame();
        router.dispatch(request.toString(), channel);
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("type").getAsString()).isEqualTo("files.read.result");
        assertThat(channel.frames().get(0).get("replyTo")).isEqualTo(request.get("id"));

        channel.clear();
        request.getAsJsonObject("payload").addProperty("path", "../credentials.json");
        router.dispatch(request.toString(), channel);
        assertThat(channel.frames()).hasSize(1);
        assertThat(channel.frames().get(0).get("type").getAsString()).isEqualTo("error");
        assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                .isEqualTo("invalid_message");
    }

    @Test
    void managerTestsCanAlsoUseTheRouterWithItsSystemClock() {
        AgentMessageRouter router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        RecordingChannel channel = new RecordingChannel();
        router.register("files.read", (request, replies) -> replies.replyError(
                request.id(), "files.read.result", "not_found", "File not found.", null));
        router.dispatch(ContractFixtures.validFrames("files.read").get(0).frame().toString(), channel);
        assertThat(channel.frames()).hasSize(1);
    }
}
