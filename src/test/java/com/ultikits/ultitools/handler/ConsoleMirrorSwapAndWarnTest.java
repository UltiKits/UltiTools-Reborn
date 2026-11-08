package com.ultikits.ultitools.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.async.StubAsyncLoggerConfig;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.bukkit.craftbukkit.util.ForwardLogHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * UltiTools-Reborn#583 F2 and F4.
 * <p>
 * F2: installing and uninstalling the mirror swaps Paper's forwarder on the {@code java.util.logging}
 * root logger for a wrapper and back. Between the two handler-list calls of a swap, a record logged
 * on another thread must still find a handler that forwards it into Log4j (the console and
 * {@code latest.log}); the test looks at the root logger's handlers at exactly that moment.
 * <p>
 * F4: a deterministic install failure (asynchronous loggers, an exception or linkage error from
 * Log4j) cannot change while the server runs, and {@code install()} runs again on every panel
 * reconnect, so its WARNING is logged once per server run.
 */
@DisplayName("#583 F2/F4 -- forwarder swap loses no line; a failed install warns once")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConsoleMirrorSwapAndWarnTest {

    private final String pluginLoggerName = "ConsoleMirrorSwapAndWarnTest-" + UUID.randomUUID();
    private final List<LogRecord> pluginRecords = new CopyOnWriteArrayList<>();
    private Handler capture;
    private LoggerContext context;
    private org.apache.logging.log4j.Level previousRootLevel;

    @BeforeEach
    void setUp() throws Exception {
        resetWarnedFlags();
        Logger pluginLogger = Logger.getLogger(pluginLoggerName);
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                pluginRecords.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        pluginLogger.addHandler(capture);
        // Kept off the root logger: with LogManager mocked, the forwarder stand-in would fail on it.
        pluginLogger.setUseParentHandlers(false);
        ErrorReportCollector errorReportCollector = mock(ErrorReportCollector.class);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger);
            lenient().when(ultiTools.getErrorReportCollector()).thenReturn(errorReportCollector);
        });

        context = (LoggerContext) LogManager.getContext(LogManager.class.getClassLoader(), false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        previousRootLevel = root.getLevel();
        root.setLevel(org.apache.logging.log4j.Level.INFO);
        context.updateLoggers();

        Logger.getLogger("").addHandler(new ForwardLogHandler());
        Logger.getLogger("").addHandler(new SystemLogHandler(mock(UltiPanelLogTransmitter.class)));
    }

    @AfterEach
    void tearDown() throws Exception {
        ConsoleMirror.swapHook = null;
        ConsoleMirror.uninstall();
        Logger root = Logger.getLogger("");
        for (Handler each : root.getHandlers()) {
            if (each instanceof SystemLogHandler || each instanceof ForwardLogHandler) {
                root.removeHandler(each);
            }
        }
        Logger.getLogger(pluginLoggerName).removeHandler(capture);
        context.getConfiguration().getRootLogger().setLevel(previousRootLevel);
        context.updateLoggers();
        resetWarnedFlags();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // resets the once-per-run warning flags between tests
    private static void resetWarnedFlags() throws Exception {
        for (String name : new String[] {"forwarderMissingWarned", "installFailureWarned"}) {
            try {
                Field field = ConsoleMirror.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setBoolean(null, false);
            } catch (NoSuchFieldException absentOnTheBase) {
                // nothing to reset
            }
        }
    }

    /** Whether a root handler would copy a record into Log4j right now. */
    private static boolean forwardsIntoLog4j() {
        for (Handler each : Logger.getLogger("").getHandlers()) {
            if (each instanceof ForwardLogHandler || each.getClass().getName().endsWith("$ForwardScope")) {
                return true;
            }
        }
        return false;
    }

    private List<String> warnings() {
        return pluginRecords.stream().filter(r -> Level.WARNING.equals(r.getLevel()))
                .map(LogRecord::getMessage).collect(Collectors.toList());
    }

    @Test
    @DisplayName("F2: while install() swaps in the wrapper, a forwarder stays on the root logger")
    void installSwapKeepsAForwarder() {
        List<Boolean> seen = new ArrayList<>();
        ConsoleMirror.swapHook = () -> seen.add(forwardsIntoLog4j());

        assertThat(ConsoleMirror.install()).isTrue();

        assertThat(seen).as("forwarder present between the two swap calls of install()")
                .isNotEmpty().containsOnly(true);
    }

    @Test
    @DisplayName("F2: while uninstall() puts the original back, a forwarder stays on the root logger")
    void uninstallSwapKeepsAForwarder() {
        assertThat(ConsoleMirror.install()).isTrue();
        List<Boolean> seen = new ArrayList<>();
        ConsoleMirror.swapHook = () -> seen.add(forwardsIntoLog4j());

        ConsoleMirror.uninstall();

        assertThat(seen).as("forwarder present between the two swap calls of uninstall()")
                .isNotEmpty().containsOnly(true);
    }

    @Test
    @DisplayName("F2 control: after install and uninstall exactly one forwarder is left, the original")
    void swapLeavesExactlyOneForwarder() {
        assertThat(ConsoleMirror.install()).isTrue();
        long wrappers = 0;
        long originals = 0;
        for (Handler each : Logger.getLogger("").getHandlers()) {
            if (each instanceof ForwardLogHandler) {
                originals++;
            } else if (each.getClass().getName().endsWith("$ForwardScope")) {
                wrappers++;
            }
        }
        assertThat(originals).as("bare forwarders while installed").isZero();
        assertThat(wrappers).as("wrapped forwarders while installed").isEqualTo(1);

        ConsoleMirror.uninstall();
        originals = 0;
        wrappers = 0;
        for (Handler each : Logger.getLogger("").getHandlers()) {
            if (each instanceof ForwardLogHandler) {
                originals++;
            } else if (each.getClass().getName().endsWith("$ForwardScope")) {
                wrappers++;
            }
        }
        assertThat(originals).as("bare forwarders after uninstall").isEqualTo(1);
        assertThat(wrappers).as("wrapped forwarders after uninstall").isZero();
    }

    @Test
    @DisplayName("F4: asynchronous loggers -- two installs (a reconnect) log one WARNING")
    void asyncLoggersWarnOnce() {
        Configuration configuration = context.getConfiguration();
        String name = "ConsoleMirrorSwapAndWarnTest.async." + UUID.randomUUID();
        configuration.addLogger(name, new StubAsyncLoggerConfig(name));
        try {
            assertThat(ConsoleMirror.install()).isFalse();
            assertThat(ConsoleMirror.install()).isFalse();
        } finally {
            configuration.removeLogger(name);
        }

        assertThat(warnings()).as("WARNING lines over two installs").hasSize(1);
        assertThat(warnings().get(0)).contains("asynchronous loggers");
    }

    @Test
    @DisplayName("F4: Log4j throws -- two installs (a reconnect) log one WARNING, install() stays false")
    void failureWarnsOnce() {
        try (MockedStatic<LogManager> logManager = Mockito.mockStatic(LogManager.class)) {
            logManager.when(() -> LogManager.getContext(any(ClassLoader.class), anyBoolean()))
                    .thenThrow(new IllegalStateException("no context"));

            assertThat(ConsoleMirror.install()).isFalse();
            assertThat(ConsoleMirror.install()).isFalse();
        }

        assertThat(warnings()).as("WARNING lines over two installs").hasSize(1);
        assertThat(warnings().get(0)).contains("could not mirror the server console");
    }
}
