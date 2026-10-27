package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.websocket.SensitiveCommandRegistry;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Error reports sent to UltiCloud carry message, trigger detail and stack trace with registered
 * commands' arguments replaced; the in-memory report and its fingerprint are unchanged.
 * <p>
 * Production callers pass a method or command name as the trigger detail today
 * ({@code BaseCommandExecutor#executeCommand}, {@code CooldownValidator}); this pins the redaction
 * for a detail that carries a whole command line, which {@link TriggerContext#command} allows.
 */
@DisplayName("Error-report redaction of cloud-bound fields")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ErrorReportRedactionTest {

    private final SensitiveCommandRegistry registry = new SensitiveCommandRegistry();
    private ErrorReportCollector collector;
    private Player player;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();

        ServerMonitorManager mockSmm = mock(ServerMonitorManager.class);
        lenient().when(mockSmm.getCurrentTPS()).thenReturn(20.0);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getServerMonitorManager()).thenReturn(mockSmm);
            lenient().when(ultiTools.getSensitiveCommandRegistry()).thenReturn(registry);
        });

        player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());

        collector = newCollector();
    }

    @AfterEach
    void tearDown() {
        collector.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    private static ErrorReportCollector newCollector() throws Exception {
        ErrorReportCollector c = new ErrorReportCollector();
        // No init(): avoids the dedup-reset scheduler thread; enable directly.
        Field enabled = ErrorReportCollector.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.set(c, true);
        return c;
    }

    /** Always thrown from the same line, so every instance has the same fingerprint. */
    private static IllegalStateException loginFailure() {
        return new IllegalStateException("Failed: /login hunter2");
    }

    private JsonObject reportAndDrainOne(ErrorReportCollector target) {
        target.reportError(loginFailure(), "UltiLogin", TriggerContext.command(player, "/login hunter2"));
        JsonArray drained = target.drainErrors(10);
        assertThat(drained.size()).isEqualTo(1);
        return drained.get(0).getAsJsonObject();
    }

    @Test
    @DisplayName("message, triggerDetail and stackTrace are redacted in the cloud JSON")
    void cloudFieldsAreRedacted() {
        registry.register("login");

        JsonObject json = reportAndDrainOne(collector);

        assertThat(json.get("triggerDetail").getAsString()).isEqualTo("/login ***");
        assertThat(json.get("message").getAsString()).isEqualTo("Failed: /login ***");
        assertThat(json.get("stackTrace").getAsString())
                .contains("Failed: /login ***")
                .doesNotContain("hunter2");
    }

    @Test
    @DisplayName("the in-memory report keeps the original text")
    @SuppressWarnings("unchecked")
    void inMemoryReportUnchanged() throws Exception {
        registry.register("login");
        reportAndDrainOne(collector);

        Field dedup = ErrorReportCollector.class.getDeclaredField("dedupMap");
        dedup.setAccessible(true);
        Map<String, ErrorReportCollector.ErrorReport> map =
                (Map<String, ErrorReportCollector.ErrorReport>) dedup.get(collector);
        ErrorReportCollector.ErrorReport report = map.values().iterator().next();
        assertThat(report.message).isEqualTo("Failed: /login hunter2");
        assertThat(report.triggerDetail).isEqualTo("/login hunter2");
        assertThat(report.stackTrace).contains("hunter2");
    }

    @Test
    @DisplayName("with nothing registered, both fields are sent verbatim")
    void nothingRegistered_verbatim() {
        JsonObject json = reportAndDrainOne(collector);

        assertThat(json.get("triggerDetail").getAsString()).isEqualTo("/login hunter2");
        assertThat(json.get("message").getAsString()).isEqualTo("Failed: /login hunter2");
    }

    @Test
    @DisplayName("the fingerprint is computed as before, and de-duplication still groups occurrences")
    void fingerprintAndGroupingUnchanged() throws Exception {
        // Created at one call site, so all three share every stack frame and the fingerprint.
        java.util.List<IllegalStateException> failures = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            failures.add(loginFailure());
        }

        ErrorReportCollector reference = newCollector();
        reference.reportError(failures.get(0), "UltiLogin", TriggerContext.command(player, "/login a"));
        String referenceFingerprint = reference.drainErrors(10).get(0).getAsJsonObject()
                .get("fingerprint").getAsString();
        reference.shutdown();

        registry.register("login");
        collector.reportError(failures.get(1), "UltiLogin", TriggerContext.command(player, "/login a"));
        collector.reportError(failures.get(2), "UltiLogin", TriggerContext.command(player, "/login b"));
        JsonArray drained = collector.drainErrors(10);

        assertThat(drained.size()).isEqualTo(1);
        JsonObject json = drained.get(0).getAsJsonObject();
        assertThat(json.get("occurrenceCount").getAsInt()).isEqualTo(2);
        assertThat(json.get("fingerprint").getAsString()).isEqualTo(referenceFingerprint);
    }
}
