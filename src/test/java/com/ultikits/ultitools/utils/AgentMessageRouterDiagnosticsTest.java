package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.RecordingChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMessageRouterDiagnosticsTest {
    @Test
    void handlerCrashNamesTheMessageTypeAndExceptionClassWithoutPayloadOrStackTrace() {
        JsonObject request = ContractFixtures.validFrames("credential.rotate").get(0).frame();
        String secret = request.getAsJsonObject("payload").get("credential").getAsString();
        AgentMessageRouter router = new AgentMessageRouter(capability -> true, capability -> "disabled");
        router.register("credential.rotate", (message, channel) -> {
            throw new IllegalStateException(secret);
        });
        RecordingChannel channel = new RecordingChannel();

        try (AgentMessageRouterTest.LogCapture capture = new AgentMessageRouterTest.LogCapture(AgentMessageRouter.class)) {
            router.dispatch(request.toString(), channel);

            assertThat(capture.warnings()).hasSize(1);
            assertThat(capture.warnings().get(0).getMessage())
                    .contains("internal_error", "credential.rotate", IllegalStateException.class.getName())
                    .doesNotContain(secret, request.getAsJsonObject("payload").toString(), "\tat ");
            assertThat(capture.warnings().get(0).getThrown()).isNull();
            assertThat(capture.warnings().get(0).getParameters()).isNull();
            assertThat(channel.frames()).hasSize(1);
            assertThat(channel.frames().get(0).getAsJsonObject("payload").get("code").getAsString())
                    .isEqualTo("internal_error");
            assertThat(channel.frames().get(0).toString())
                    .doesNotContain(secret, IllegalStateException.class.getName(), "\tat ");
        }
    }
}
