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

    @Override
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
        target.log(copy);
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
