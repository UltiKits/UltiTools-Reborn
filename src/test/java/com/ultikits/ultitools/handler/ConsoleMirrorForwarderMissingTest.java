package com.ultikits.ultitools.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
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
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.bukkit.craftbukkit.util.ForwardLogHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.utils.TestHelper;

/**
 * UltiTools-Reborn#583 F1: when Paper's forwarder ({@code org.bukkit.craftbukkit.util.ForwardLogHandler})
 * is not on the {@code java.util.logging} root logger -- a fork, a build that relocates CraftBukkit,
 * something else replaced it -- the console mirror must not be installed, because the appender
 * could no longer tell a forwarded plugin line from a console line and every plugin line would
 * reach the stream twice. Here the root logger carries a forwarder under another binary name, which
 * forwards exactly as Paper's does.
 * <br>
 * #583 F1：找不到 Paper 的转发器时不安装控制台镜像，否则每条插件日志都会被推送两次。
 */
@DisplayName("#583 F1 -- the console mirror fails closed without Paper's forwarder")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // resets the once-per-run warning flag between tests
class ConsoleMirrorForwarderMissingTest {

    /** A forwarder doing what Paper's does, under a binary name the mirror does not recognise. */
    static final class RelocatedForwardLogHandler extends ForwardLogHandler {
    }

    private final String pluginLoggerName = "ConsoleMirrorForwarderMissingTest-" + UUID.randomUUID();
    private final List<LogRecord> pluginRecords = new CopyOnWriteArrayList<>();
    private UltiPanelLogTransmitter transmitter;
    private SystemLogHandler handler;
    private Handler forwarder;
    private Handler capture;
    private LoggerContext context;
    private org.apache.logging.log4j.Level previousRootLevel;

    @BeforeEach
    void setUp() throws Exception {
        resetWarnedFlag();
        transmitter = mock(UltiPanelLogTransmitter.class);
        ErrorReportCollector errorReportCollector = mock(ErrorReportCollector.class);
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
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger);
            lenient().when(ultiTools.getErrorReportCollector()).thenReturn(errorReportCollector);
        });

        context = (LoggerContext) LogManager.getContext(LogManager.class.getClassLoader(), false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        previousRootLevel = root.getLevel();
        root.setLevel(org.apache.logging.log4j.Level.INFO);
        context.updateLoggers();

        handler = new SystemLogHandler(transmitter);
        Logger.getLogger("").addHandler(handler);
    }

    @AfterEach
    void tearDown() throws Exception {
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
        resetWarnedFlag();
    }

    /** The once-per-run flag is reached reflectively: on the base it does not exist yet. */
    private static void resetWarnedFlag() throws Exception {
        try {
            Field field = ConsoleMirror.class.getDeclaredField("forwarderMissingWarned");
            field.setAccessible(true);
            field.setBoolean(null, false);
        } catch (NoSuchFieldException absentOnTheBase) {
            // nothing to reset
        }
    }

    private List<String> warnings() {
        return pluginRecords.stream().filter(r -> Level.WARNING.equals(r.getLevel()))
                .map(LogRecord::getMessage).collect(Collectors.toList());
    }

    private void addForwarder(Handler each) {
        forwarder = each;
        Logger.getLogger("").addHandler(forwarder);
    }

    @Test
    @DisplayName("no recognised forwarder: install() is false, no appender, one WARNING, a plugin line once")
    void missingForwarderFailsClosed() {
        addForwarder(new RelocatedForwardLogHandler());

        boolean installed = ConsoleMirror.install();

        assertThat(installed).as("install() without Paper's forwarder").isFalse();
        assertThat(ConsoleMirror.isInstalled()).isFalse();
        assertThat(context.getConfiguration().getRootLogger().getAppenders())
                .as("Log4j root appenders").doesNotContainKey(ConsoleMirror.APPENDER_NAME);
        assertThat(warnings()).as("WARNING lines").hasSize(1);
        assertThat(warnings().get(0)).contains("will not mirror the server console");
        assertThat(Logger.getLogger("").getHandlers()).as("the forwarder is left as it was").contains(forwarder);

        Logger.getLogger(pluginLoggerName).info("module enabled");

        verify(transmitter, times(1)).sendLog(eq("info"), eq("module enabled"), anyString(), isNull());
    }

    @Test
    @DisplayName("a second install() (a reconnect) does not warn again")
    void warnsOncePerRun() {
        addForwarder(new RelocatedForwardLogHandler());

        assertThat(ConsoleMirror.install()).isFalse();
        assertThat(ConsoleMirror.install()).isFalse();

        assertThat(warnings()).as("WARNING lines over two installs").hasSize(1);
    }

    @Test
    @DisplayName("control: with Paper's forwarder the mirror installs and a plugin line arrives once")
    void presentForwarderInstallsAsBefore() {
        addForwarder(new ForwardLogHandler());

        assertThat(ConsoleMirror.install()).isTrue();
        Logger.getLogger(pluginLoggerName).info("module enabled");

        verify(transmitter, times(1)).sendLog(eq("info"), eq("module enabled"), anyString(), isNull());
        assertThat(warnings()).isEmpty();
    }
}
