package org.bukkit.craftbukkit.util;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Test stand-in for Paper's own {@code org.bukkit.craftbukkit.util.ForwardLogHandler}, under the
 * same binary name, doing what the real one does (read from Paper 1.21.11's bytecode): it is
 * attached to the {@code java.util.logging} root logger and forwards every record into Log4j, to a
 * Log4j logger of the record's own logger name, by level. With it on the root logger a plugin line
 * reaches both the framework's JUL handler and Log4j, which is the duplicate the console mirror
 * must not produce.
 */
public class ForwardLogHandler extends ConsoleHandler {

    @Override
    public void publish(LogRecord record) {
        Logger logger = LogManager.getLogger(String.valueOf(record.getLoggerName()));
        String message = getFormatter().formatMessage(record);
        Throwable thrown = record.getThrown();
        Level level = record.getLevel();
        if (Level.SEVERE.equals(level)) {
            logger.error(message, thrown);
        } else if (Level.WARNING.equals(level)) {
            logger.warn(message, thrown);
        } else if (Level.INFO.equals(level)) {
            logger.info(message, thrown);
        } else if (Level.CONFIG.equals(level)) {
            logger.debug(message, thrown);
        } else {
            logger.trace(message, thrown);
        }
    }

    @Override
    public void flush() {
        // Nothing buffered.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
