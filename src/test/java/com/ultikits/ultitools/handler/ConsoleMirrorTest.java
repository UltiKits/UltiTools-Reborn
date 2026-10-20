package com.ultikits.ultitools.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Logger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.bukkit.craftbukkit.util.ForwardLogHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.manager.EarlyLogCapture;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;
import com.ultikits.ultitools.utils.TestHelper;
import com.ultikits.ultitools.websocket.PanelConnectionLog;

/**
 * The panel's log stream mirrors the server console (as of 6.3.0): Paper prints its own output
 * through Log4j, so {@link ConsoleMirror} feeds Log4j's root-logger events into the same
 * {@link SystemLogHandler} that handles {@code java.util.logging} records. These tests run against
 * the real Log4j core context of the test JVM, with the test stand-in for Paper's
 * {@code ForwardLogHandler} on the JUL root logger, as on a real server.
 */
@DisplayName("ConsoleMirror -- the panel log stream mirrors the server console")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConsoleMirrorTest {

    private static final String PLUGIN_LOGGER = "ConsoleMirrorTestPlugin";

    private UltiPanelLogTransmitter transmitter;
    private ErrorReportCollector errorReportCollector;
    private SystemLogHandler handler;
    private ForwardLogHandler forward;
    private LoggerContext context;
    private Level previousRootLevel;

    @BeforeEach
    void setUp() {
        transmitter = mock(UltiPanelLogTransmitter.class);
        errorReportCollector = mock(ErrorReportCollector.class);
        Logger pluginLogger = Logger.getLogger(PLUGIN_LOGGER);
        TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(pluginLogger);
            lenient().when(ultiTools.getErrorReportCollector()).thenReturn(errorReportCollector);
        });

        context = (LoggerContext) LogManager.getContext(LogManager.class.getClassLoader(), false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        previousRootLevel = root.getLevel();
        root.setLevel(Level.INFO);
        context.updateLoggers();

        // As on Paper: the JUL root logger forwards every record into Log4j.
        forward = new ForwardLogHandler();
        Logger.getLogger("").addHandler(forward);

        handler = new SystemLogHandler(transmitter);
        Logger.getLogger("").addHandler(handler);

        assertThat(ConsoleMirror.install()).as("the mirror installs on a Log4j core context").isTrue();
    }

    @AfterEach
    void tearDown() {
        ConsoleMirror.uninstall();
        EarlyLogCapture.release();
        Logger root = Logger.getLogger("");
        for (Handler each : root.getHandlers()) {
            if (each instanceof SystemLogHandler || each instanceof ForwardLogHandler) {
                root.removeHandler(each);
            }
        }
        context.getConfiguration().getRootLogger().setLevel(previousRootLevel);
        context.updateLoggers();
    }

    @Test
    @DisplayName("a vanilla-style Log4j INFO line reaches the transmitter, ANSI colour stripped")
    void vanillaLog4jLine_reachesTransmitter() {
        LogManager.getLogger("net.minecraft.server.MinecraftServer")
                .info("\u001B[38;5;9mThere are 0 of a max of 10 players online: \u001B[0m");

        verify(transmitter).sendLog(eq("info"), eq("There are 0 of a max of 10 players online: "),
                eq("server"), isNull());
    }

    @Test
    @DisplayName("a module's reply to the console sender (Log4j root logger) reaches the transmitter")
    void consoleSenderReply_reachesTransmitter() {
        // Paper's console sender logs every message it is sent through a logger named after the
        // Log4j root logger (TerminalConsoleCommandSender, read from Paper 1.21.11's bytecode).
        LogManager.getLogger(LogManager.getRootLogger().getName()).info("Cannot delete the default world!");

        verify(transmitter).sendLog(eq("info"), eq("Cannot delete the default world!"), eq("server"), isNull());
    }

    @Test
    @DisplayName("a plugin (JUL) line arrives exactly once, although Paper also forwards it into Log4j")
    void julPluginLine_arrivesExactlyOnce() {
        Logger.getLogger(PLUGIN_LOGGER).info("module enabled");

        verify(transmitter, times(1)).sendLog(eq("info"), eq("module enabled"), anyString(), isNull());
    }

    @Test
    @DisplayName("a configured excluded logger applies to Log4j lines")
    void excludedLogger_appliesToLog4jLines() {
        handler.addExcludedLogger("net.minecraft.server");

        LogManager.getLogger("net.minecraft.server.MinecraftServer").info("excluded line");
        LogManager.getLogger("com.example.Other").info("kept line");

        verify(transmitter, never()).sendLog(anyString(), eq("excluded line"), anyString(), any());
        verify(transmitter).sendLog(eq("info"), eq("kept line"), anyString(), isNull());
    }

    @Test
    @DisplayName("the enabled-level filter applies to Log4j lines")
    void levelFilter_appliesToLog4jLines() {
        handler.removeEnabledLevel("info");

        LogManager.getLogger("net.minecraft.server.MinecraftServer").info("info line");
        LogManager.getLogger("net.minecraft.server.MinecraftServer").warn("warn line");

        verify(transmitter, never()).sendLog(anyString(), eq("info line"), anyString(), any());
        verify(transmitter).sendLog(eq("warning"), eq("warn line"), eq("server"), isNull());
    }

    @Test
    @DisplayName("a panel-connection line is not re-transmitted through the Log4j copy either")
    void panelConnectionLine_isNotRetransmitted() {
        PanelConnectionLog.log(java.util.logging.Level.SEVERE, "Rate limit exceeded");

        verify(transmitter, never()).sendLog(anyString(), eq("Rate limit exceeded"), anyString(), any());
    }

    @Test
    @DisplayName("the WebSocket library's and the transmitter's own Log4j lines are never re-transmitted")
    void ownTransportLines_areNotRetransmitted() {
        LogManager.getLogger("org.java_websocket.WebSocketImpl").error("websocket failure");
        LogManager.getLogger("com.ultikits.ultitools.manager.UltiPanelLogTransmitter").warn("transmitter line");

        verify(transmitter, never()).sendLog(anyString(), eq("websocket failure"), anyString(), any());
        verify(transmitter, never()).sendLog(anyString(), eq("transmitter line"), anyString(), any());
    }

    @Test
    @DisplayName("an error with an exception is reported once, from either logging framework")
    void errorWithException_isReportedOnce() {
        IllegalStateException julFailure = new IllegalStateException("jul failure");
        Logger.getLogger(PLUGIN_LOGGER).log(java.util.logging.Level.SEVERE, "plugin failed", julFailure);
        IllegalStateException log4jFailure = new IllegalStateException("log4j failure");
        LogManager.getLogger("net.minecraft.server.MinecraftServer").error("server failed", log4jFailure);

        verify(errorReportCollector, times(1)).reportError(same(julFailure), anyString(), any());
        verify(errorReportCollector, times(1)).reportError(same(log4jFailure), anyString(), any());
    }

    @Test
    @DisplayName("before the stream starts, Log4j lines are kept by the early capture and replayed")
    void earlyCapture_keepsLog4jLines() {
        Logger.getLogger("").removeHandler(handler);
        EarlyLogCapture.start(Collections.<String>emptyList());

        LogManager.getLogger("net.minecraft.server.MinecraftServer").info("Preparing level \"world\"");
        EarlyLogCapture.drainInto(handler.replayHandler(), () -> Logger.getLogger("").addHandler(handler));

        verify(transmitter).replayLog(eq("info"), eq("Preparing level \"world\""), eq("server"), isNull(), anyLong());
    }

    @Test
    @DisplayName("installing twice leaves one appender; uninstall removes it and restores Paper's handler")
    void uninstall_removesAppenderAndRestoresForwardHandler() {
        assertThat(ConsoleMirror.install()).isTrue();
        assertThat(context.getConfiguration().getRootLogger().getAppenders())
                .containsKey(ConsoleMirror.APPENDER_NAME);

        ConsoleMirror.uninstall();

        assertThat(ConsoleMirror.isInstalled()).isFalse();
        assertThat(context.getConfiguration().getRootLogger().getAppenders())
                .doesNotContainKey(ConsoleMirror.APPENDER_NAME);
        assertThat(Logger.getLogger("").getHandlers()).contains(forward);

        LogManager.getLogger("net.minecraft.server.MinecraftServer").info("after shutdown");
        verify(transmitter, never()).sendLog(anyString(), eq("after shutdown"), anyString(), any());
    }
}
