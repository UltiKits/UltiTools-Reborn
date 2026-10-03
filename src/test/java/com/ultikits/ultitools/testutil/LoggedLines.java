package com.ultikits.ultitools.testutil;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.mockito.ArgumentCaptor;

/**
 * Reads back what a mocked plugin {@link Logger} was given, through both ways the framework logs:
 * {@code log(Level, String[, Throwable])} for ordinary lines, and {@code log(LogRecord)} for the
 * panel-connection lines {@code PanelConnectionLog} writes (as of 6.3.0), which carry a marked
 * record so the log stream never sends them back to the panel.
 */
public final class LoggedLines {

    private LoggedLines() {
    }

    /**
     * Every line logged at {@code level}, in either form.
     *
     * @param mockLogger the mocked logger
     * @param level      the level
     * @return the messages
     */
    public static List<String> at(Logger mockLogger, Level level) {
        List<String> lines = new ArrayList<>();
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, atLeast(0)).log(org.mockito.ArgumentMatchers.eq(level), plain.capture());
        lines.addAll(plain.getAllValues());
        ArgumentCaptor<String> withThrown = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, atLeast(0)).log(org.mockito.ArgumentMatchers.eq(level), withThrown.capture(),
                org.mockito.ArgumentMatchers.<Throwable>any());
        lines.addAll(withThrown.getAllValues());
        for (LogRecord record : records(mockLogger)) {
            if (level.equals(record.getLevel())) {
                lines.add(record.getMessage());
            }
        }
        return lines;
    }

    /**
     * Every record passed to {@code log(LogRecord)}.
     *
     * @param mockLogger the mocked logger
     * @return the records
     */
    public static List<LogRecord> records(Logger mockLogger) {
        ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
        verify(mockLogger, atLeast(0)).log(captor.capture());
        return captor.getAllValues();
    }
}
