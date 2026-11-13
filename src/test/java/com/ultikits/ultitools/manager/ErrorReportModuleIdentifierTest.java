package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.google.gson.JsonObject;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.contract.RecordingChannel;
import com.ultikits.ultitools.utils.MockBukkitHelper;

/**
 * {@code error.report.moduleName} is the module's catalogue identifier (backend contract answer of
 * 2026-10-27, 22-26 verification item 8): the server groups a report to a module by matching this
 * field against the catalogue {@code identifier}. A report attributable to a loaded module sends that
 * module's identify-string, lower-case; anything else -- the framework itself, a Bukkit plugin, a
 * command description, a bean name -- sends {@code null}, which the frozen schema allows, rather than
 * free text the server cannot match.
 */
@DisplayName("error.report.moduleName is the catalogue identifier (22-26 fix, item 8)")
class ErrorReportModuleIdentifierTest {

    private ErrorReportCollector collector;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        final ServerMonitorManager monitor = mock(ServerMonitorManager.class);
        lenient().when(monitor.getCurrentTPS()).thenReturn(20.0);
        final UltiToolsPlugin chat = mock(UltiToolsPlugin.class);
        lenient().when(chat.getPluginName()).thenReturn("UltiTools-Chat");
        lenient().when(chat.getIdentifyString()).thenReturn("ultichat");
        final UltiToolsPlugin trade = mock(UltiToolsPlugin.class);
        lenient().when(trade.getPluginName()).thenReturn("UltiTools-Trade");
        lenient().when(trade.getIdentifyString()).thenReturn("ultitrade");
        final PluginManager modules = mock(PluginManager.class);
        lenient().when(modules.getPluginList()).thenReturn(Arrays.asList(chat, trade));
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getServerMonitorManager()).thenReturn(monitor);
            lenient().when(ultiTools.getPluginManager()).thenReturn(modules);
        });
        collector = new ErrorReportCollector();
    }

    @AfterEach
    void tearDown() {
        collector.shutdown();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void errorsUseTheNameFallbackWhenTheLoadedModuleHasNoValidIdentifyString() {
        UltiToolsPlugin chat = com.ultikits.ultitools.UltiTools.getInstance().getPluginManager()
                .getPluginList().get(0);
        lenient().when(chat.getIdentifyString()).thenReturn("invalid_id");
        collector.reportError(new IllegalStateException("fallback"), "UltiTools-Chat", null);
        JsonObject payload = collector.drainErrorReportPayloads(10).get(0);
        assertThat(payload.get("moduleName").getAsString()).isEqualTo("ultitools-chat");
    }

    @Test
    void theSameFailureInTwoModulesHasSeparateCountsAndWireFingerprints() {
        IllegalStateException failure = sharedFailure();
        report(failure, "UltiTools-Chat", 2);
        report(failure, "UltiTools-Trade", 3);

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);
        assertThat(payloads).hasSize(2);
        assertReport(payloads, "ultichat", 2);
        assertReport(payloads, "ultitrade", 3);
        assertThat(payloads).extracting(payload -> payload.get("fingerprint").getAsString())
                .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attributedAndUnattributedFailuresStaySeparate(boolean unattributedFirst) {
        IllegalStateException failure = sharedFailure();
        if (unattributedFirst) {
            report(failure, null, 1);
        }
        report(failure, "UltiTools-Chat", 2);
        if (!unattributedFirst) {
            report(failure, null, 1);
        }

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);
        assertThat(payloads).hasSize(2);
        assertReport(payloads, "ultichat", 2);
        JsonObject unattributed = assertReport(payloads, null, 1);
        String legacyFingerprint = String.format("%8s", ErrorReportCollector.fingerprintOf(failure))
                .replace(' ', '0');
        assertThat(unattributed.get("fingerprint").getAsString()).isEqualTo(legacyFingerprint);
        assertThat(payloads).extracting(payload -> payload.get("fingerprint").getAsString())
                .doesNotHaveDuplicates();
    }

    @Test
    void aliasesOfTheSameModuleStillDeduplicate() {
        IllegalStateException failure = sharedFailure();
        report(failure, "UltiTools-Chat", 1);
        report(failure, "ultichat", 1);
        report(failure, "ULTICHAT", 1);

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);
        assertThat(payloads).hasSize(1);
        assertReport(payloads, "ultichat", 3);
    }

    @Test
    void drainMergesOnlyTheSameModuleAcrossDedupWindows() throws Exception {
        IllegalStateException failure = sharedFailure();
        report(failure, "UltiTools-Chat", 1);
        report(failure, "UltiTools-Trade", 1);
        report(failure, null, 1);
        ((Map<?, ?>) field("dedupMap").get(collector)).clear();
        report(failure, "ultichat", 2);
        report(failure, "ultitrade", 3);
        report(failure, null, 4);

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);
        assertThat(payloads).hasSize(3);
        assertReport(payloads, "ultichat", 3);
        assertReport(payloads, "ultitrade", 4);
        assertReport(payloads, null, 5);
    }

    @Test
    void samplingBudgetsAreIndependentForEachModuleAndUnattributedFailures() throws Exception {
        field("sampleAfterCount").set(collector, 1);
        field("sampleRate").set(collector, 0.0);
        IllegalStateException failure = sharedFailure();
        report(failure, "UltiTools-Chat", 2);
        report(failure, "UltiTools-Trade", 2);
        report(failure, null, 2);

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);
        assertThat(payloads).hasSize(3);
        assertReport(payloads, "ultichat", 1);
        assertReport(payloads, "ultitrade", 1);
        assertReport(payloads, null, 1);
    }

    private void report(Throwable failure, String moduleName, int count) {
        for (int i = 0; i < count; i++) {
            collector.reportError(failure, moduleName, null);
        }
    }

    private static IllegalStateException sharedFailure() {
        IllegalStateException failure = new IllegalStateException("shared failure");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.SharedService", "fail", "SharedService.java", 42)
        });
        return failure;
    }

    private static JsonObject assertReport(List<JsonObject> payloads, String moduleId, int count) {
        JsonObject payload = payloads.stream().filter(candidate -> candidate.get("moduleName").isJsonNull()
                ? moduleId == null : candidate.get("moduleName").getAsString().equals(moduleId)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing report for " + moduleId));
        assertThat(payload.get("occurrences").getAsInt()).isEqualTo(count);
        assertThat(new RecordingChannel().send("error.report", payload)).as("schema-valid").isTrue();
        return payload;
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Configure sampling and simulate a dedup window reset.
    private static Field field(String name) throws NoSuchFieldException {
        Field field = ErrorReportCollector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @ParameterizedTest(name = "reported as ''{0}'' -> {1}")
    @CsvSource(nullValues = "null", value = {
            "UltiTools-Chat, ultichat",
            "ultitools-chat, ultichat",
            "ultichat, ultichat",
            "UltiTools, null",
            "UltiChat, ultichat",
            "UltiTools-ChatX, null",
            "Chat history command, null",
            "chatHistoryService, null",
            "server, null",
            "null, null"
    })
    @DisplayName("the identifier of the loaded module the report names, else null")
    void moduleNameIsTheCatalogueIdentifier(String reportedAs, String expected) {
        collector.reportError(new IllegalStateException("boom"), reportedAs, null);

        List<JsonObject> payloads = collector.drainErrorReportPayloads(10);

        assertThat(payloads).hasSize(1);
        assertThat(new RecordingChannel().send("error.report", payloads.get(0))).as("schema-valid").isTrue();
        JsonObject payload = payloads.get(0);
        if (expected == null) {
            assertThat(payload.get("moduleName").isJsonNull()).as(payload.toString()).isTrue();
        } else {
            assertThat(payload.get("moduleName").getAsString()).isEqualTo(expected);
        }
    }
}
