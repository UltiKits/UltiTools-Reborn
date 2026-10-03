package com.ultikits.ultitools.utils;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;

/**
 * Forwards the records of a dedicated diagnostics logger to the framework's own plugin logger, so they
 * reach the server console at their real level and in the server's normal log format (#557).
 * <p>
 * The two diagnostics that use it, {@code ClassloadFilterAudit} and {@code ModuleScanDiagnostics}, keep
 * their own non-root logger with parent handlers disabled, so a record never propagates on its own to
 * the root logger {@code SystemLogHandler} watches. What this bridge forwards is a copy, logged on the
 * plugin logger; both classes emit only records without a {@link Throwable} at SEVERE, which is what
 * {@code SystemLogHandler} would auto-report, so forwarding does not turn a summary into an error
 * report.
 * <p>
 * Forwarding is skipped, silently, when the plugin instance or its logger is not available (a unit
 * test, or a call before the plugin is enabled): a diagnostic must never break the work it reports on.
 *
 * @since 6.3.0
 */
final class PluginLoggerBridge extends Handler {

    /**
     * @param level the least severe level to forward
     */
    PluginLoggerBridge(Level level) {
        setLevel(level);
    }

    // PMD.AvoidCatchingGenericException: any handler of the plugin logger may throw, and JUL does not
    // catch what a handler throws; the scan that emits the line must not fail because of it.
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        UltiTools instance = UltiTools.getInstance();
        Logger target = instance == null ? null : instance.getLogger();
        if (target == null) {
            return;
        }
        // A copy: the plugin logger may rewrite the message (it prefixes the plugin name), and the
        // original record is still being delivered to this logger's other handlers.
        LogRecord copy = new LogRecord(record.getLevel(), record.getMessage());
        copy.setParameters(record.getParameters());
        copy.setThrown(record.getThrown());
        // Logger#log(LogRecord) does not stamp a logger name (only the log(Level, ...) overloads do), and
        // Paper's console prints the logger name as the bracketed prefix: without this the line reads
        // "[null] Module ..." instead of "[UltiTools] Module ..." (gate 1 of plan 17-55).
        copy.setLoggerName(target.getName());
        try {
            target.log(copy);
        } catch (RuntimeException e) {
            // Standard error, not a logger: the logger is what just failed.
            System.err.println("[UltiTools] A diagnostic line could not be written: " + e);
        }
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing to release
    }
}
