package com.ultikits.ultitools.websocket;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;

/**
 * Writes the framework's lines about the panel connection itself to the server console, marked so
 * the panel's log stream never sends them back to the panel (as of 6.3.0).
 * <p>
 * <b>Why the mark exists.</b> The panel answers a message it rejects with an {@code error} reply,
 * and it counts the rejected message against the client's quota (50 messages in 10 seconds). The
 * framework used to log each {@code error} reply as a SEVERE line through the shared plugin logger;
 * the log stream sent that line to the panel, the panel rejected it as over quota and replied with
 * another {@code error}. Measured on a real server with {@code ultipanel.logging.batch.enabled:
 * false}: 42,066 "Rate limit exceeded" lines in one run. {@code SystemLogHandler}'s same-thread
 * guard cannot stop this, because each reply is a separate inbound message, and its logger-name
 * filter cannot either, because every line logged through the shared plugin logger carries that
 * logger's name.
 * <p>
 * <b>What it covers.</b> Every line whose text comes from the panel connection or describes it:
 * the panel's replies and notifications, inbound messages the framework cannot use, the client's
 * connect, disconnect, heartbeat and reconnect lines, and the warnings about a message that could
 * not be sent. Lines about work the panel asked for (a command, a file operation) are not
 * connection lines and stay on the stream.
 * <p>
 * <b>How.</b> The line is logged through the plugin logger as before -- so it reaches the console
 * with the plugin's own {@code [UltiTools]} prefix and at its real level -- but as a record of a
 * private {@link LogRecord} subclass. {@code SystemLogHandler} never hands a record for which
 * {@link #isPanelConnectionRecord(LogRecord)} is true to the stream (its error report for a SEVERE
 * record with an exception still runs), and {@code EarlyLogCapture} does not keep one. Paper's
 * plugin logger hands the same record object to every handler up the logger chain, so the mark
 * survives the trip to the root logger. A dedicated logger was not used because the console prints
 * a record's logger name as its bracketed prefix, so these lines would lose the {@code [UltiTools]}
 * prefix operators search for.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class PanelConnectionLog {

    private PanelConnectionLog() {
    }

    /**
     * Logs a panel-connection line through the plugin logger.
     *
     * @param level   the level
     * @param message the text, already formatted
     */
    public static void log(Level level, String message) {
        log(level, message, null);
    }

    /**
     * Logs a panel-connection line, with its exception, through the plugin logger. Nothing is
     * written when the plugin instance or its logger is not available (a call before the plugin is
     * enabled, or a unit test without one).
     *
     * @param level   the level
     * @param message the text, already formatted
     * @param thrown  the exception, or {@code null}
     */
    public static void log(Level level, String message, Throwable thrown) {
        UltiTools instance = UltiTools.getInstance();
        Logger target = instance == null ? null : instance.getLogger();
        if (target == null) {
            return;
        }
        LogRecord record = new PanelConnectionRecord(level, message);
        record.setThrown(thrown);
        // Logger#log(LogRecord) does not stamp a logger name, and Paper's console prints the logger
        // name as the bracketed prefix: without this the line would read "[null] ...".
        record.setLoggerName(target.getName());
        target.log(record);
    }

    /**
     * Whether {@code record} was written by this class, and so must not be sent to the panel.
     *
     * @param record the record, possibly {@code null}
     * @return whether it is a panel-connection line
     */
    public static boolean isPanelConnectionRecord(LogRecord record) {
        return record instanceof PanelConnectionRecord;
    }

    /** The mark: a record of this type is a panel-connection line. */
    private static final class PanelConnectionRecord extends LogRecord {

        private static final long serialVersionUID = 1L;

        PanelConnectionRecord(Level level, String message) {
            super(level, message);
        }
    }
}
