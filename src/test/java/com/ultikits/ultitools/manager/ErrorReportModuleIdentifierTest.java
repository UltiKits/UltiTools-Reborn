package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        final PluginManager modules = mock(PluginManager.class);
        lenient().when(modules.getPluginList()).thenReturn(Collections.singletonList(chat));
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
