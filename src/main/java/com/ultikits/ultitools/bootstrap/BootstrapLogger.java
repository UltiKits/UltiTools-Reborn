package com.ultikits.ultitools.bootstrap;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/** Keeps the module JUL contract while routing early output through Paper's bootstrap logger. */
final class BootstrapLogger extends Logger {
    private BootstrapLogger(String name) {
        super(name, null);
        setLevel(Level.ALL);
    }

    static Logger paper(ComponentLogger target) {
        BootstrapLogger logger = new BootstrapLogger("UltiTools");
        logger.setUseParentHandlers(false);
        logger.addHandler(new PaperHandler(target));
        return logger;
    }

    static Logger module(Logger parent, String name) {
        BootstrapLogger logger = new BootstrapLogger("UltiTools/" + name);
        logger.setParent(parent);
        return logger;
    }

    private static final class PaperHandler extends Handler {
        private final ComponentLogger target;
        private final Formatter messages = new Formatter() {
            @Override public String format(LogRecord record) { return formatMessage(record); }
        };

        PaperHandler(ComponentLogger target) {
            this.target = target;
        }

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            String message = messages.format(record);
            String name = record.getLoggerName();
            if (name != null && name.startsWith("UltiTools/")) {
                message = "[" + name + "] " + message;
            }
            Throwable failure = record.getThrown();
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                target.error(message, failure);
            } else if (level >= Level.WARNING.intValue()) {
                target.warn(message, failure);
            } else if (level >= Level.INFO.intValue()) {
                target.info(message, failure);
            } else if (level >= Level.FINE.intValue()) {
                target.debug(message, failure);
            } else {
                target.trace(message, failure);
            }
        }

        @Override public void flush() { /* Paper owns the destination and its buffers. */ }
        @Override public void close() { /* Paper owns the destination's lifetime. */ }
    }
}
