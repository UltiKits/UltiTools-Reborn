package com.ultikits.ultitools.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.manager.UltiPanelLogTransmitter;

/**
 * #485: the shipped {@code excluded-loggers} defaults contain only entries that can match a record
 * this handler receives.
 * <p>
 * The capture is one {@code java.util.logging} root handler. The logger names that reach it are the
 * ones JUL loggers carry: {@code Minecraft} (Bukkit's server logger, which is what
 * {@code Bukkit.getLogger()} returns), a plugin's own logger, and {@code com.ultikits.ultitools.*}
 * class loggers. Libraries that log through Log4j or SLF4J — authlib, Netty, HikariCP, Jetty — never
 * produce a JUL record, and {@code ErrorReportCollector} never logs through JUL at all, so the six
 * entries shipped before 6.3.0 could match nothing: the list was inert (measured on a real server in
 * Phase 16, and by reading Paper's {@code CraftServer}: {@code Logger.getLogger("Minecraft")}).
 */
@DisplayName("excluded-loggers defaults can match what the handler receives (#485)")
class ExcludedLoggerDefaultsTest {

    /** The logger names a JUL root handler on a Paper server actually receives. */
    private static final List<String> RECEIVED_SHAPES = Arrays.asList(
            "Minecraft", "UltiTools", "com.ultikits.ultitools.manager.PluginManager");

    private final UltiPanelLogTransmitter transmitter = mock(UltiPanelLogTransmitter.class);
    private UltiTools previousInstance;
    private boolean instanceSwapped;

    @AfterEach
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // UltiTools' singleton has no setter
    void restoreInstance() throws ReflectiveOperationException {
        if (instanceSwapped) {
            Field field = UltiTools.class.getDeclaredField("ultiTools");
            field.setAccessible(true);
            field.set(null, previousInstance);
        }
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // UltiTools' singleton has no setter
    private void setInstance(UltiTools instance) throws ReflectiveOperationException {
        Field field = UltiTools.class.getDeclaredField("ultiTools");
        field.setAccessible(true);
        if (!instanceSwapped) {
            previousInstance = (UltiTools) field.get(null);
            instanceSwapped = true;
        }
        field.set(null, instance);
    }

    private static LogRecord record(String loggerName) {
        LogRecord record = new LogRecord(Level.INFO, "line from " + loggerName);
        record.setLoggerName(loggerName);
        return record;
    }

    @Test
    @DisplayName("every default entry matches a logger name the handler receives, so no inert entry ships")
    void everyDefaultEntryCanMatchAReceivedLoggerName() {
        SystemLogHandler handler = new SystemLogHandler(transmitter);

        for (String entry : handler.getExcludedLoggers()) {
            assertTrue(RECEIVED_SHAPES.stream().anyMatch(name -> name.startsWith(entry)),
                    "default entry '" + entry + "' can never match a record this handler receives");
        }
        assertEquals(Collections.emptySet(), handler.getExcludedLoggers(),
                "none of the pre-6.3.0 defaults can match; excluding a received logger by default would hide "
                        + "real lines, so the shipped default is empty");
    }

    @Test
    @DisplayName("a configured list is used as given, with no inert entry forced back in")
    void configuredListIsUsedAsGiven() throws ReflectiveOperationException {
        YamlConfiguration config = new YamlConfiguration();
        config.set("ultipanel.logging.excluded-loggers", Collections.singletonList("Minecraft"));
        UltiTools ultiTools = mock(UltiTools.class);
        when(ultiTools.getConfig()).thenReturn(config);
        when(ultiTools.getLogger()).thenReturn(Logger.getLogger("ExcludedLoggerDefaultsTest"));
        when(ultiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
        setInstance(ultiTools);
        SystemLogHandler handler = new SystemLogHandler(transmitter);

        handler.loadConfiguration();

        assertEquals(Collections.singleton("Minecraft"), handler.getExcludedLoggers());
    }

    @Test
    @DisplayName("control: an entry naming a received logger does exclude its records, and only those")
    void anEntryNamingAReceivedLoggerExcludesIt() {
        SystemLogHandler handler = new SystemLogHandler(transmitter);
        handler.addExcludedLogger("Minecraft");

        handler.publish(record("Minecraft"));
        verify(transmitter, never()).sendLog(anyString(), anyString(), anyString(), any());

        handler.publish(record("com.ultikits.ultitools.manager.PluginManager"));
        verify(transmitter).sendLog(anyString(), anyString(), anyString(), any());
    }
}
