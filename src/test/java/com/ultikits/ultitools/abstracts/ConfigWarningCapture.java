package com.ultikits.ultitools.abstracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Test-only capture of the WARNING-or-higher records the configuration binder logs through
 * {@link AbstractConfigEntity}'s logger. Install it before the call under test and close it in a
 * {@code finally} (or try-with-resources) so a failing test does not leave the handler attached.
 */
final class ConfigWarningCapture extends Handler implements AutoCloseable {

    private final Logger logger = Logger.getLogger(AbstractConfigEntity.class.getName());
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

    private ConfigWarningCapture() {
        logger.addHandler(this);
    }

    static ConfigWarningCapture install() {
        return new ConfigWarningCapture();
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            records.add(record);
        }
    }

    @Override
    public void flush() {
        // Records are appended straight to the in-memory list.
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }

    /**
     * @return the messages captured so far, in order
     */
    List<String> messages() {
        List<String> messages = new ArrayList<>();
        synchronized (records) {
            for (LogRecord record : records) {
                messages.add(record.getMessage());
            }
        }
        return messages;
    }

    /**
     * @param fragment text every counted message must contain
     * @return the captured messages containing {@code fragment}
     */
    List<String> messagesContaining(String fragment) {
        List<String> matching = new ArrayList<>();
        for (String message : messages()) {
            if (message != null && message.contains(fragment)) {
                matching.add(message);
            }
        }
        return matching;
    }
}
