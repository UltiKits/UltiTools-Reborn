package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;

/**
 * #557: the class-load audit and the module-scan diagnostics reach the operator through the framework's
 * own plugin logger, at their true level, instead of a private handler that wrote to {@code System.err}
 * (which Paper prints as WARN, behind a second, JUL-formatted header line).
 * <p>
 * The plugin logger here is a test logger with a capturing handler, so what arrives there is exactly what
 * the server console would receive.
 */
@DisplayName("Audit and scan diagnostics reach the plugin logger at their true level (#557)")
class AuditReachesPluginLoggerTest {

    private MockedStatic<UltiTools> ultiToolsMock;
    private final List<LogRecord> atPluginLogger = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        atPluginLogger.clear();
        Logger pluginLogger = Logger.getAnonymousLogger();
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.setLevel(Level.ALL);
        pluginLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                atPluginLogger.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        });
        UltiTools ultiTools = mock(UltiTools.class);
        lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger);
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(ultiTools);
    }

    @AfterEach
    void tearDown() {
        ultiToolsMock.close();
    }

    private List<LogRecord> infoOrAbove() {
        synchronized (atPluginLogger) {
            return atPluginLogger.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.INFO.intValue())
                    .collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("a non-clean audit arrives at the plugin logger as one INFO line naming the module and the count")
    void nonCleanAuditIsOneInfoLineAtThePluginLogger() {
        ClassloadFilterAudit.record("plain-words-module", "com.example.thirdparty.Foo");
        ClassloadFilterAudit.record("plain-words-module", "com.example.thirdparty.Bar");
        ClassloadFilterAudit.emitSummary("plain-words-module");

        List<LogRecord> info = infoOrAbove();
        assertThat(info).hasSize(1);
        assertThat(info.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(info.get(0).getMessage()).contains("plain-words-module").contains("2 class");
    }

    @Test
    @DisplayName("a clean audit prints nothing at INFO or above at the plugin logger")
    void cleanAuditIsSilentAtInfo() {
        ClassloadFilterAudit.record("quiet-module", "com.ultikits.ultitools.UltiTools");
        ClassloadFilterAudit.emitSummary("quiet-module");

        assertThat(infoOrAbove()).isEmpty();
    }

    @Test
    @DisplayName("a module that skipped classes is reported by one SEVERE line at the plugin logger, without a Throwable")
    void skippedClassesAreOneSevereLineWithoutAThrowable() {
        ModuleScanDiagnostics.recordSkippedClass("skipping-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        ModuleScanDiagnostics.emitSummary("skipping-module");

        List<LogRecord> severe = infoOrAbove();
        assertThat(severe).hasSize(1);
        assertThat(severe.get(0).getLevel()).isEqualTo(Level.SEVERE);
        assertThat(severe.get(0).getMessage()).contains("skipping-module").contains("com.example.Gone");
        assertThat(severe.get(0).getThrown())
                .as("a SEVERE record carrying a Throwable would be auto-reported by the log handler")
                .isNull();
    }
}
