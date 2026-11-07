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

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
    private URLClassLoader isolated;
    private Class<?> audit;
    private Class<?> diagnostics;
    private final List<LoggerState> originalLoggers = new ArrayList<>();

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        atPluginLogger.clear();
        pluginLogger = Logger.getLogger(PLUGIN_LOGGER_NAME);
        originalLoggers.add(new LoggerState(pluginLogger));
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.setLevel(Level.INFO);
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

        String prefix = "com.ultikits.ultitools.utils.";
        originalLoggers.add(new LoggerState(Logger.getLogger(prefix + "ClassloadFilterAudit")));
        originalLoggers.add(new LoggerState(Logger.getLogger(prefix + "ModuleScanDiagnostics")));
        isolated = new DiagnosticLoader(new URL[]{UltiTools.class.getProtectionDomain().getCodeSource().getLocation()},
                getClass().getClassLoader());
        // Fresh production static initializers install the bridges. The fixture never installs a handler.
        audit = Class.forName(prefix + "ClassloadFilterAudit", true, isolated);
        diagnostics = Class.forName(prefix + "ModuleScanDiagnostics", true, isolated);
        for (Class<?> type : new Class<?>[]{audit, diagnostics}) {
            Logger logger = Logger.getLogger(type.getName());
            assertThat(logger.getUseParentHandlers()).isFalse();
            assertThat(logger.getHandlers()).hasSize(1);
            assertThat(logger.getHandlers()[0].getClass().getName()).isEqualTo(prefix + "PluginLoggerBridge");
            assertThat(logger.getHandlers()[0].getClass().getClassLoader()).isSameAs(isolated);
        }
    }

    private static final class DiagnosticLoader extends URLClassLoader {
        DiagnosticLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.ultikits.ultitools.utils.ClassloadFilterAudit")
                    && !name.startsWith("com.ultikits.ultitools.utils.ModuleScanDiagnostics")
                    && !name.equals("com.ultikits.ultitools.utils.PluginLoggerBridge")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static final class LoggerState {
        private final Logger logger;
        private final Handler[] handlers;
        private final Level level;
        private final boolean parents;

        LoggerState(Logger logger) {
            this.logger = logger;
            handlers = logger.getHandlers();
            level = logger.getLevel();
            parents = logger.getUseParentHandlers();
            for (Handler handler : handlers) {
                logger.removeHandler(handler);
            }
        }

        void restore() {
            for (Handler handler : logger.getHandlers()) {
                logger.removeHandler(handler);
            }
            logger.setLevel(level);
            logger.setUseParentHandlers(parents);
            for (Handler handler : handlers) {
                logger.addHandler(handler);
            }
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            for (LoggerState state : originalLoggers) {
                state.restore();
            }
        } finally {
            if (isolated != null) {
                isolated.close();
            }
            if (ultiToolsMock != null) {
                ultiToolsMock.close();
            }
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // package-private production class in an isolated loader
    private static void invoke(Class<?> type, String name, Class<?>[] types, Object... args) {
        try {
            Method method = type.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Diagnostic call failed: " + name, e);
        }
    }

    private void recordAudit(String module, String className) {
        invoke(audit, "record", new Class<?>[]{String.class, String.class}, module, className);
    }

    private void recordSkipped(String module, String className, Throwable cause) {
        invoke(diagnostics, "recordSkippedClass", new Class<?>[]{String.class, String.class, Throwable.class},
                module, className, cause);
    }

    private void auditSummary(String module) {
        invoke(audit, "emitSummary", new Class<?>[]{String.class}, module);
    }

    private void scanSummary(String module) {
        invoke(diagnostics, "emitSummary", new Class<?>[]{String.class}, module);
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
        recordAudit("plain-words-module", "com.example.thirdparty.Foo");
        recordAudit("plain-words-module", "com.example.thirdparty.Bar");
        auditSummary("plain-words-module");

        List<LogRecord> info = infoOrAbove();
        assertThat(info).hasSize(1);
        assertThat(info.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(info.get(0).getMessage()).contains("plain-words-module").contains("2 class");
    }

    @Test
    @DisplayName("a clean audit prints nothing at INFO or above at the plugin logger")
    void cleanAuditIsSilentAtInfo() {
        recordAudit("quiet-module", "com.ultikits.ultitools.UltiTools");
        auditSummary("quiet-module");

        assertThat(infoOrAbove()).isEmpty();
    }

    @Test
    @DisplayName("a module that skipped classes is reported by one SEVERE line at the plugin logger, without a Throwable")
    void skippedClassesAreOneSevereLineWithoutAThrowable() {
        recordSkipped("skipping-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        scanSummary("skipping-module");

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
        recordAudit("named-module", "com.example.thirdparty.Foo");
        auditSummary("named-module");
        recordSkipped("named-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        scanSummary("named-module");

        List<LogRecord> info = infoOrAbove();
        assertThat(info).hasSize(2);
        assertThat(info).extracting(LogRecord::getLoggerName).containsOnly(PLUGIN_LOGGER_NAME);
    }

    @Test
    void defaultInfoLoggerFiltersPerClassDetailsButKeepsBothSummaries() {
        recordAudit("default-info", "com.example.Foo");
        recordSkipped("default-info", "com.example.Gone", new NoClassDefFoundError("missing"));
        assertThat(atPluginLogger).as("neither per-class FINE record reaches the default INFO logger").isEmpty();
        auditSummary("default-info");
        scanSummary("default-info");
        assertThat(atPluginLogger).extracting(LogRecord::getLevel).containsExactly(Level.INFO, Level.SEVERE);
    }

    @Test
    void auditBridgeItselfRejectsFineEvenWhenPluginLoggingIsVerbose() {
        pluginLogger.setLevel(Level.ALL);
        recordAudit("verbose-audit", "com.example.Foo");
        assertThat(atPluginLogger).as("the audit bridge, not the plugin threshold, rejects FINE").isEmpty();
        recordSkipped("verbose-scan", "com.example.Gone", new NoClassDefFoundError("missing"));
        assertThat(atPluginLogger).extracting(LogRecord::getLevel).containsExactly(Level.FINE);
        auditSummary("verbose-audit");
        scanSummary("verbose-scan");
        assertThat(atPluginLogger).extracting(LogRecord::getLevel)
                .containsExactly(Level.FINE, Level.INFO, Level.SEVERE);
    }

    @Test
    @DisplayName("a handler of the plugin logger that throws does not break the scan that emits the line")
    void throwingPluginLoggerHandlerDoesNotBreakTheCaller() {
        addThrowingPluginLoggerHandler();
        recordAudit("broken-handler-module", "com.example.thirdparty.Foo");

        assertThatCode(() -> auditSummary("broken-handler-module"))
                .doesNotThrowAnyException();
        recordSkipped("broken-handler-module", "com.example.Gone",
                new NoClassDefFoundError("com/example/Missing"));
        assertThatCode(() -> scanSummary("broken-handler-module"))
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
