package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** A named logger, as Paper's plugin logger is named after the plugin; an anonymous one has no name. */
    private static final String PLUGIN_LOGGER_NAME = "UltiTools-AuditReachesPluginLoggerTest";

    private MockedStatic<UltiTools> ultiToolsMock;
    private final List<LogRecord> atPluginLogger = Collections.synchronizedList(new ArrayList<>());
    private Logger pluginLogger;

    @BeforeEach
    void setUp() {
        atPluginLogger.clear();
        pluginLogger = Logger.getLogger(PLUGIN_LOGGER_NAME);
        for (Handler stale : pluginLogger.getHandlers()) {
            pluginLogger.removeHandler(stale);
        }
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

        // Both classes attach their bridge in a static initializer, once per JVM. Initialize them here,
        // then restore the bridge if an earlier test in the same JVM stripped the logger's handlers
        // (the handler list of a named JUL logger is global state; MockBukkit-based tests are known to
        // leave it empty). That the initializers do attach a bridge is asserted on the source text in
        // ClassloadAuditQuietStartTest, which does not depend on this state.
        ClassloadFilterAudit.classify(null);
        ModuleScanDiagnostics.emitSummary(null);
        ensureBridge(Logger.getLogger(ClassloadFilterAudit.class.getName()), ClassloadFilterAudit.FORWARD_LEVEL);
        ensureBridge(Logger.getLogger(ModuleScanDiagnostics.class.getName()), Level.ALL);
    }

    private static void ensureBridge(Logger logger, Level level) {
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof PluginLoggerBridge) {
                return;
            }
        }
        logger.addHandler(new PluginLoggerBridge(level));
    }

    @AfterEach
    void tearDown() {
        ultiToolsMock.close();
        for (Handler handler : pluginLogger.getHandlers()) {
            pluginLogger.removeHandler(handler);
        }
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

    @Test
    @DisplayName("a forwarded line carries the plugin logger's name, so Paper prints [UltiTools], not [null]")
    void forwardedLineCarriesThePluginLoggerName() {
        // Logger#log(LogRecord) does not stamp a logger name; Paper's console takes the bracketed prefix
        // from it, so a copy without one is printed as "[null] Module ...".
        ClassloadFilterAudit.record("named-module", "com.example.thirdparty.Foo");
        ClassloadFilterAudit.emitSummary("named-module");
        ModuleScanDiagnostics.recordSkippedClass("named-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        ModuleScanDiagnostics.emitSummary("named-module");

        List<LogRecord> info = infoOrAbove();
        assertThat(info).hasSize(2);
        assertThat(info).extracting(LogRecord::getLoggerName).containsOnly(PLUGIN_LOGGER_NAME);
    }

    @Test
    @DisplayName("a handler of the plugin logger that throws does not break the scan that emits the line")
    void throwingPluginLoggerHandlerDoesNotBreakTheCaller() {
        addThrowingPluginLoggerHandler();
        ClassloadFilterAudit.record("broken-handler-module", "com.example.thirdparty.Foo");

        assertThatCode(() -> ClassloadFilterAudit.emitSummary("broken-handler-module"))
                .doesNotThrowAnyException();
        ModuleScanDiagnostics.recordSkippedClass("broken-handler-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        assertThatCode(() -> ModuleScanDiagnostics.emitSummary("broken-handler-module"))
                .doesNotThrowAnyException();
    }

    @Test
    void throwingStderrFallbackDoesNotBreakTheCaller() {
        addThrowingPluginLoggerHandler();
        PluginLoggerBridge bridge = new PluginLoggerBridge(Level.ALL);
        PrintStream originalStderr = System.err;
        PrintStream failingStderr = mock(PrintStream.class);
        doThrow(new IllegalStateException("stderr is re-logged through the broken handler"))
                .when(failingStderr).println(anyString());
        try {
            System.setErr(failingStderr);

            assertThatCode(() -> bridge.publish(new LogRecord(Level.WARNING, "diagnostic")))
                    .doesNotThrowAnyException();
            verify(failingStderr).println(anyString());
        } finally {
            System.setErr(originalStderr);
        }
    }

    @Test
    void stderrFallbackCannotReenterTheBridgeAndGuardIsReleasedAfterwards() {
        addThrowingPluginLoggerHandler();
        PluginLoggerBridge bridge = new PluginLoggerBridge(Level.ALL);
        // A second bridge checks that the guard covers both diagnostics loggers on this thread.
        PluginLoggerBridge reenteredBridge = new PluginLoggerBridge(Level.ALL);
        PrintStream originalStderr = System.err;
        PrintStream reLoggingStderr = mock(PrintStream.class);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            // Bound the pre-fix recursion, so a regression fails without exhausting the stack.
            if (writes.incrementAndGet() <= 3) {
                reenteredBridge.publish(new LogRecord(Level.WARNING, "re-logged stderr"));
            }
            return null;
        }).when(reLoggingStderr).println(anyString());
        try {
            System.setErr(reLoggingStderr);

            assertThatCode(() -> bridge.publish(new LogRecord(Level.WARNING, "first diagnostic")))
                    .doesNotThrowAnyException();
            assertThat(writes.get()).isEqualTo(1);
            assertThat(atPluginLogger).hasSize(1);

            bridge.publish(new LogRecord(Level.WARNING, "second diagnostic"));
            assertThat(writes.get()).isEqualTo(2);
            assertThat(atPluginLogger).hasSize(2);
        } finally {
            System.setErr(originalStderr);
        }
    }

    @Test
    void throwingStderrFallbackDoesNotLeaveTheBridgeGuardSet() {
        addThrowingPluginLoggerHandler();
        PluginLoggerBridge bridge = new PluginLoggerBridge(Level.ALL);
        PrintStream originalStderr = System.err;
        PrintStream failingStderr = mock(PrintStream.class);
        doThrow(new IllegalStateException("broken stderr"))
                .when(failingStderr).println(anyString());
        try {
            System.setErr(failingStderr);

            assertThatCode(() -> bridge.publish(new LogRecord(Level.WARNING, "first diagnostic")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> bridge.publish(new LogRecord(Level.WARNING, "second diagnostic")))
                    .doesNotThrowAnyException();
            verify(failingStderr, times(2)).println(anyString());
            assertThat(atPluginLogger).hasSize(2);
        } finally {
            System.setErr(originalStderr);
        }
    }

    private void addThrowingPluginLoggerHandler() {
        pluginLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("a broken downstream handler");
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
    }
}
