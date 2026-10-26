package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

/**
 * Plan 22-22 Task 2 (D-14, D-20, S-43, S-55): the producers' contract payloads. The error report the collector groups
 * leaves as one schema-valid {@code error.report} whose fingerprint is the collector's group key padded to eight hex
 * digits, redacted as 22-04 redacts it, and counted rather than resent; join, leave and command payloads come only
 * from the 22-13 encoders. {@link RecordingChannel} validates every frame against the frozen schema.
 */
@DisplayName("Outbound event payloads from the framework's producers")
class OutboundEventWiringTest {

    private ErrorReportCollector collector;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
        registry.register("login");
        final ServerMonitorManager monitor = mock(ServerMonitorManager.class);
        lenient().when(monitor.getCurrentTPS()).thenReturn(19.5);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getServerMonitorManager()).thenReturn(monitor);
            lenient().when(ultiTools.getSensitiveCommandRegistry()).thenReturn(registry);
        });
        collector = new ErrorReportCollector();
    }

    @AfterEach
    void tearDown() {
        collector.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("failed while running /login secret");
    }

    @Test
    @DisplayName("one collected error becomes one schema-valid error.report with the padded group key")
    void collectedErrorBecomesOneReport() {
        IllegalStateException error = failure();
        collector.reportError(error, "UltiChat", TriggerContext.scheduled("/login secret"));

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);

        assertThat(payloads).hasSize(1);
        RecordingChannel channel = new RecordingChannel();
        assertThat(channel.send("error.report", payloads.get(0))).as("schema-valid").isTrue();
        JsonObject payload = payloads.get(0);
        String groupKey = ErrorReportCollector.fingerprintOf(error);
        StringBuilder padded = new StringBuilder(groupKey);
        while (padded.length() < 8) {
            padded.insert(0, '0');
        }
        assertThat(payload.get("fingerprint").getAsString()).isEqualTo(padded.toString()).matches("^[0-9a-f]{8,64}$");
        assertThat(payload.get("exceptionType").getAsString()).isEqualTo(IllegalStateException.class.getName());
        // No module named UltiChat is loaded here, so the report is not attributed (22-26 fix, item 8):
        // moduleName is a catalogue identifier or null, never free text (ErrorReportModuleIdentifierTest).
        assertThat(payload.get("moduleName").isJsonNull()).isTrue();
        assertThat(payload.getAsJsonObject("trigger").get("kind").getAsString()).isEqualTo("scheduler");
        assertThat(payload.get("location").getAsString())
                .isEqualTo("OutboundEventWiringTest.failure");
        assertThat(payload.get("occurrences").getAsInt()).isEqualTo(1);
        assertThat(payload.getAsJsonObject("snapshot").get("tps").getAsDouble()).isEqualTo(19.5);
        assertThat(payload.getAsJsonObject("snapshot").get("memMaxMb").getAsLong()).isPositive();
        assertThat(payload.get("firstSeenAt").getAsLong()).isPositive()
                .isLessThanOrEqualTo(payload.get("lastSeenAt").getAsLong());
    }

    @Test
    @DisplayName("message, stack trace and trigger detail are redacted as 22-04 does; the local report keeps the text")
    void reportIsRedacted() {
        collector.reportError(failure(), null, TriggerContext.scheduled("/login secret"));

        JsonObject payload = collector.drainErrorReportPayloads(10).get(0);

        assertThat(payload.toString()).doesNotContain("secret").contains("/login ***");
        assertThat(payload.get("message").getAsString()).isEqualTo("failed while running /login ***");
        assertThat(payload.getAsJsonObject("trigger").get("detail").getAsString()).isEqualTo("/login ***");
        assertThat(payload.get("moduleName").isJsonNull()).isTrue();
    }

    @Test
    @DisplayName("a repeat inside the dedupe window is counted in the pending report, and not resent after it left")
    void repeatsAreCountedNotResent() {
        IllegalStateException error = failure();
        collector.reportError(error, null, null);
        collector.reportError(error, null, null);

        List<JsonObject> first = collector.drainErrorReportPayloads(10);
        collector.reportError(error, null, null);
        List<JsonObject> second = collector.drainErrorReportPayloads(10);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).get("occurrences").getAsInt()).isEqualTo(2);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("a disabled collector drains nothing")
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // the switch is read from config.yml at init()
    void disabledCollectorDrainsNothing() throws Exception {
        java.lang.reflect.Field enabled = ErrorReportCollector.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        collector.reportError(failure(), null, null);
        enabled.set(collector, false);

        assertThat(collector.drainErrorReportPayloads(10)).isEmpty();
    }

    @Test
    @DisplayName("join, leave and command payloads come from the encoders: only the contract fields, or nothing")
    void playerPayloadsComeFromTheEncoders() {
        String uuid = UUID.randomUUID().toString();
        JsonObject ref = AgentOutboundFeeds.playerRefPayload(uuid, ".Steve");
        assertThat(ref.keySet()).containsExactlyInAnyOrder("playerUuid", "playerName");
        assertThat(new RecordingChannel().send("player.join", ref)).isTrue();
        assertThat(AgentOutboundFeeds.playerRefPayload(uuid, "has space")).isNull();

        JsonObject command = AgentOutboundFeeds.playerCommandPayload("/ultilogin:Login secret");
        assertThat(command.toString()).isEqualTo("{\"name\":\"login\"}");
        assertThat(new RecordingChannel().send("player.command", command)).isTrue();
        assertThat(AgentOutboundFeeds.playerCommandPayload("/")).isNull();
    }
}
