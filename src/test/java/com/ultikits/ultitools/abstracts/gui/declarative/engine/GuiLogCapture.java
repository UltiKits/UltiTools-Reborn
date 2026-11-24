package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Scoped capture of synchronous GUI diagnostics without changing global logger levels. */
public final class GuiLogCapture extends Handler implements AutoCloseable {
    private final Logger logger;
    private final List<LogRecord> records = new ArrayList<>();

    public GuiLogCapture(Class<?> source) {
        logger = Logger.getLogger(source.getName());
        logger.addHandler(this);
    }

    public List<LogRecord> records() { return List.copyOf(records); }
    @Override public void publish(LogRecord record) { records.add(record); }
    @Override public void flush() { /* Records are appended synchronously. */ }
    @Override public void close() { logger.removeHandler(this); }
}
