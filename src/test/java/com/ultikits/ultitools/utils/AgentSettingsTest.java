package com.ultikits.ultitools.utils;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.ContractFixtures;
import com.ultikits.ultitools.contract.ContractSchemas;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two payloads {@link AgentSettings} produces, validated against the frozen contract schemas.
 */
class AgentSettingsTest {

    private static final String MESSAGE_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0TVW";

    @Test
    void helloSettingsReportsPowerActionsDisabledByDefault() {
        JsonObject settings = new AgentSettings(() -> false).helloSettings();

        assertThat(settings.keySet()).containsExactly("powerActionsEnabled");
        assertThat(settings.get("powerActionsEnabled").getAsBoolean()).isFalse();
    }

    @Test
    void nothingIsReportedWhileTheSwitchDoesNotChange() {
        AgentSettings settings = new AgentSettings(() -> false);

        assertThat(settings.changedSinceLastReport()).isEqualTo(settings.helloSettings());

        settings.markReported(settings.helloSettings());

        assertThat(settings.changedSinceLastReport()).isNull();
    }

    @Test
    void oneChangeIsReportedOnceThenClearedByMarkReported() {
        boolean[] enabled = {false};
        AgentSettings settings = new AgentSettings(() -> enabled[0]);
        settings.markReported(settings.helloSettings());

        enabled[0] = true;

        JsonObject changed = settings.changedSinceLastReport();
        assertThat(changed.get("powerActionsEnabled").getAsBoolean()).isTrue();
        assertThat(settings.changedSinceLastReport()).as("still unreported").isEqualTo(changed);

        settings.markReported(changed);

        assertThat(settings.changedSinceLastReport()).as("reported once").isNull();
    }

    @Test
    void theChangedPayloadValidatesAsSettingsChanged() {
        AgentSettings settings = new AgentSettings(() -> true);
        JsonObject frame = frame("settings.changed");
        frame.add("payload", settings.changedSinceLastReport());

        ContractSchemas.assertFrameValid(frame);
    }

    @Test
    void theHelloSettingsValidateInsideAHelloFrame() {
        AgentSettings settings = new AgentSettings(() -> false);
        JsonObject frame = ContractFixtures.object("fixtures/agent/hello/valid/basic.json");
        frame.getAsJsonObject("payload").add("settings", settings.helloSettings());

        ContractSchemas.assertFrameValid(frame);
    }

    private static JsonObject frame(String type) {
        JsonObject frame = new JsonObject();
        frame.addProperty("v", 2);
        frame.addProperty("id", MESSAGE_ID);
        frame.addProperty("type", type);
        frame.addProperty("ts", 1761274800000L);
        return frame;
    }
}
