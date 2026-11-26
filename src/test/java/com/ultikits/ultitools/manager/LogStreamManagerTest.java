package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.ultikits.ultitools.abstracts.command.CommandMessageTestSupport;
import com.ultikits.ultitools.handler.SystemLogHandler;
import com.ultikits.ultitools.text.internal.FrameworkMessages;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * LogStreamManager after the protocol v1 removal (6.4.0): only the singleton and
 * {@link LogStreamManager#shutdown()} remain. The v1 control messages, direct sends, listeners and
 * configuration parsing were removed with the v1 client; the protocol 2.1.0 log feed is tested with
 * {@code AgentOutboundFeeds}.
 */
@DisplayName("LogStreamManager")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // resets the singleton between tests
class LogStreamManagerTest {

    private LogStreamManager logStreamManager;

    @BeforeEach
    void setUp() throws Exception {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        MockBukkit.createMockPlugin();
        Logger logger = mock(Logger.class);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            lenient().when(ultiTools.getLogger()).thenReturn(logger);
        });
        resetSingleton();
        logStreamManager = LogStreamManager.getInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        removeSystemLogHandlers();
        resetSingleton();
        com.ultikits.ultitools.utils.MockBukkitHelper.safeUnmock();
    }

    private static void resetSingleton() throws Exception {
        Field instanceField = LogStreamManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static void removeSystemLogHandlers() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof SystemLogHandler) {
                root.removeHandler(handler);
            }
        }
    }

    private static long systemLogHandlersOnRoot() {
        return Arrays.stream(Logger.getLogger("").getHandlers()).filter(SystemLogHandler.class::isInstance).count();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh"})
    void listenerRegistrationFailureUsesServerLanguage(String code) throws Exception {
        resetSingleton();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { /* No buffer. */ }
            @Override public void close() { /* No resource. */ }
        });
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(
                plugin -> org.mockito.Mockito.when(plugin.getLogger()).thenReturn(logger));
        try (var support = new CommandMessageTestSupport(code);
                var scope = FrameworkMessages.overrideForTests(
                        support.messages(), Locale.forLanguageTag(code));
                var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenThrow(new IllegalStateException("broken"));
            assertThat(LogStreamManager.getInstance()).isNotNull();
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getLevel()).isEqualTo(Level.WARNING);
            assertThat(records.getFirst().getMessage()).isEqualTo("zh".equals(code)
                    ? "注册事件监听器失败: broken" : "Failed to register the event listener: broken");
        }
    }

    @Test
    @DisplayName("getInstance returns the same instance every time")
    void getInstanceIsASingleton() {
        assertThat(LogStreamManager.getInstance()).isSameAs(logStreamManager);
    }

    @Test
    @DisplayName("shutdown detaches every SystemLogHandler from the root logger, not just one (#181)")
    void shutdownDetachesEverySystemLogHandler() {
        Logger root = Logger.getLogger("");
        root.addHandler(mock(SystemLogHandler.class));
        root.addHandler(mock(SystemLogHandler.class));
        assertThat(systemLogHandlersOnRoot()).as("control: both handlers attached").isEqualTo(2);

        logStreamManager.shutdown();

        assertThat(systemLogHandlersOnRoot()).isZero();
    }

    @Test
    @DisplayName("shutdown with nothing attached does not throw and may be repeated")
    void shutdownWithNothingAttachedIsSafe() {
        logStreamManager.shutdown();
        logStreamManager.shutdown();

        assertThat(systemLogHandlersOnRoot()).isZero();
    }
}
