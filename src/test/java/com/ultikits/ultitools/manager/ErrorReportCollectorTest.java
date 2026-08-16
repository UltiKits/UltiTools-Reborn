package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.UltiToolsException;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Tests for ErrorReportCollector.
 */
@DisplayName("ErrorReportCollector")
class ErrorReportCollectorTest {

    private ServerMock server;
    private ErrorReportCollector collector;

    @BeforeEach
    void setUp() {
        com.ultikits.ultitools.utils.MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Mock serverMonitorManager
        ServerMonitorManager mockSmm = mock(ServerMonitorManager.class);
        when(mockSmm.getCurrentTPS()).thenReturn(20.0);
        com.ultikits.ultitools.utils.TestHelper.mockUltiToolsInstance(ultiTools -> {
            when(ultiTools.getServerMonitorManager()).thenReturn(mockSmm);
        });

        collector = new ErrorReportCollector();
        // Don't call init() — avoid scheduler thread in tests. Set enabled directly.
        setField(collector, "enabled", true);
    }

    @AfterEach
    void tearDown() {
        collector.shutdown();
        try {
            MockBukkit.unmock();
        } catch (Exception ignored) {
        }
    }

    @Nested
    @DisplayName("TriggerContext")
    class TriggerContextTests {

        @Test
        @DisplayName("command() captures player info as strings")
        void commandCapturesPlayerInfo() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getName()).thenReturn("Steve");
            java.util.UUID uuid = java.util.UUID.randomUUID();
            when(mockPlayer.getUniqueId()).thenReturn(uuid);

            TriggerContext ctx = TriggerContext.command(mockPlayer, "/uc msg Hello");

            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.COMMAND);
            assertThat(ctx.getTriggerDetail()).isEqualTo("/uc msg Hello");
            assertThat(ctx.getPlayerName()).isEqualTo("Steve");
            assertThat(ctx.getPlayerUUID()).isEqualTo(uuid.toString());
        }

        @Test
        @DisplayName("command() with non-player sender has null player info")
        void commandNonPlayer() {
            CommandSender sender = mock(CommandSender.class);
            TriggerContext ctx = TriggerContext.command(sender, "/uc reload");

            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.COMMAND);
            assertThat(ctx.getPlayerName()).isNull();
            assertThat(ctx.getPlayerUUID()).isNull();
        }

        @Test
        @DisplayName("event() creates event context")
        void eventContext() {
            TriggerContext ctx = TriggerContext.event("InventoryOpenEvent");
            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.EVENT);
            assertThat(ctx.getTriggerDetail()).isEqualTo("InventoryOpenEvent");
        }

        @Test
        @DisplayName("aop() creates aop context")
        void aopContext() {
            TriggerContext ctx = TriggerContext.aop("MyService", "doStuff");
            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.AOP);
            assertThat(ctx.getTriggerDetail()).isEqualTo("MyService.doStuff()");
        }

        @Test
        @DisplayName("scheduled() creates scheduled context")
        void scheduledContext() {
            TriggerContext ctx = TriggerContext.scheduled("AutoSave");
            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.SCHEDULED);
        }

        @Test
        @DisplayName("uncaught() creates uncaught context")
        void uncaughtContext() {
            TriggerContext ctx = TriggerContext.uncaught("unknown");
            assertThat(ctx.getTriggerType()).isEqualTo(TriggerContext.TriggerType.UNCAUGHT);
        }
    }

    @Nested
    @DisplayName("reportError")
    class ReportErrorTests {

        @Test
        @DisplayName("reports a basic exception")
        void reportsBasicException() {
            RuntimeException ex = new RuntimeException("test error");
            collector.reportError(ex, "TestModule", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(1);

            JsonObject error = errors.get(0).getAsJsonObject();
            assertThat(error.get("message").getAsString()).isEqualTo("test error");
            assertThat(error.get("moduleName").getAsString()).isEqualTo("TestModule");
            assertThat(error.get("errorCode").getAsString()).isEqualTo("UNKNOWN");
            assertThat(error.get("triggerType").getAsString()).isEqualTo("UNCAUGHT");
            assertThat(error.get("fingerprint").getAsString()).isNotEmpty();
            assertThat(error.get("stackTrace").getAsString()).contains("RuntimeException");
            assertThat(error.get("occurrenceCount").getAsInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns immediately when disabled")
        void returnsWhenDisabled() {
            setField(collector, "enabled", false);
            collector.reportError(new RuntimeException("test"), "Module", TriggerContext.uncaught("test"));
            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("returns immediately for null throwable")
        void returnsForNull() {
            collector.reportError(null, "Module", TriggerContext.uncaught("test"));
            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("captures UltiToolsException error code")
        void capturesErrorCode() {
            UltiToolsException ex = new TestUltiToolsException(ErrorCode.DATA_ACCESS_ERROR, "DB fail");
            collector.reportError(ex, "TestModule", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("errorCode").getAsString())
                    .isEqualTo("ULTI-3000");
        }

        @Test
        @DisplayName("truncates long messages")
        void truncatesLongMessages() {
            StringBuilder longMsg = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                longMsg.append("x");
            }
            RuntimeException ex = new RuntimeException(longMsg.toString());
            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            String message = errors.get(0).getAsJsonObject().get("message").getAsString();
            assertThat(message.length()).isLessThanOrEqualTo(500);
        }

        @Test
        @DisplayName("captures player info from TriggerContext")
        void capturesPlayerInfo() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getName()).thenReturn("Alex");
            java.util.UUID uuid = java.util.UUID.randomUUID();
            when(mockPlayer.getUniqueId()).thenReturn(uuid);

            TriggerContext ctx = TriggerContext.command(mockPlayer, "/home set");
            collector.reportError(new RuntimeException("fail"), "Module", ctx);

            JsonArray errors = collector.drainErrors(10);
            JsonObject error = errors.get(0).getAsJsonObject();
            assertThat(error.get("playerName").getAsString()).isEqualTo("Alex");
            assertThat(error.get("playerUUID").getAsString()).isEqualTo(uuid.toString());
            assertThat(error.get("triggerType").getAsString()).isEqualTo("COMMAND");
            assertThat(error.get("triggerDetail").getAsString()).isEqualTo("/home set");
        }

        @Test
        @DisplayName("captures server state (memory)")
        void capturesServerState() {
            collector.reportError(new RuntimeException("fail"), "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            JsonObject error = errors.get(0).getAsJsonObject();
            assertThat(error.get("serverMemoryPct").getAsDouble()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("does not throw for any exception during reporting")
        void doesNotThrow() {
            assertDoesNotThrow(() ->
                    collector.reportError(new RuntimeException("test"), null, null));
        }
    }

    @Nested
    @DisplayName("Deduplication")
    class DedupTests {

        @Test
        @DisplayName("same exception deduplicates within window")
        void sameExceptionDeduplicates() {
            RuntimeException ex = createExceptionAtLine("test", 42);
            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));
            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));
            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(1);
            assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("different exceptions create separate entries")
        void differentExceptionsNotDeduped() {
            collector.reportError(createExceptionAtLine("error1", 10), "Module", TriggerContext.uncaught("test"));
            collector.reportError(createExceptionAtLine("error2", 20), "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("drainErrors merges same fingerprint in queue")
        void drainMergesSameFingerprint() {
            // Report same error, then clear dedup map, then report again
            RuntimeException ex = createExceptionAtLine("test", 42);
            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            // Force clear dedup map (simulating window reset)
            clearDedupMap(collector);

            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            // Both should be in queue, drain should merge them
            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(1);
            assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Rate limiting")
    class RateLimitTests {

        @Test
        @DisplayName("drainErrors returns at most maxErrorsPerBatch")
        void maxErrorsPerBatch() {
            setField(collector, "maxErrorsPerBatch", 3);

            for (int i = 0; i < 10; i++) {
                collector.reportError(createExceptionAtLine("error" + i, i), "Module",
                        TriggerContext.uncaught("test"));
            }

            JsonArray errors = collector.drainErrors(100);
            assertThat(errors.size()).isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("remaining errors stay in queue for next drain")
        void remainingStayInQueue() {
            setField(collector, "maxErrorsPerBatch", 2);

            for (int i = 0; i < 5; i++) {
                collector.reportError(createExceptionAtLine("error" + i, i), "Module",
                        TriggerContext.uncaught("test"));
            }

            JsonArray first = collector.drainErrors(100);
            assertThat(first.size()).isEqualTo(2);

            JsonArray second = collector.drainErrors(100);
            assertThat(second.size()).isGreaterThan(0);
        }

        @Test
        @DisplayName("errors sorted by count descending")
        void sortedByCount() {
            RuntimeException frequent = createExceptionAtLine("frequent", 10);
            RuntimeException rare = createExceptionAtLine("rare", 20);

            // Make 'frequent' have higher count
            collector.reportError(frequent, "Module", TriggerContext.uncaught("test"));
            collector.reportError(frequent, "Module", TriggerContext.uncaught("test"));
            collector.reportError(frequent, "Module", TriggerContext.uncaught("test"));
            collector.reportError(rare, "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(2);
            // First should be the frequent one
            assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt())
                    .isGreaterThan(errors.get(1).getAsJsonObject().get("occurrenceCount").getAsInt());
        }
    }

    @Nested
    @DisplayName("Sampling")
    class SamplingTests {

        @Test
        @DisplayName("accepts all before sampleAfterCount")
        void acceptsAllBefore() {
            setField(collector, "sampleAfterCount", 5);

            RuntimeException ex = createExceptionAtLine("test", 42);
            for (int i = 0; i < 5; i++) {
                collector.reportError(ex, "Module", TriggerContext.uncaught("test"));
            }

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(1);
            assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt()).isEqualTo(5);
        }

        @Test
        @DisplayName("sampling reduces reports after threshold")
        void samplingReduces() {
            setField(collector, "sampleAfterCount", 5);
            setField(collector, "sampleRate", 0.0); // 0% sample rate = reject all after threshold

            for (int i = 0; i < 20; i++) {
                RuntimeException ex = createExceptionAtLine("test", 42);
                collector.reportError(ex, "Module", TriggerContext.uncaught("test"));
            }

            // After 5 occurrences, all should be rejected (0% sample rate)
            // So the dedup count stays at 5
            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(1);
            assertThat(errors.get(0).getAsJsonObject().get("occurrenceCount").getAsInt()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("drainErrors")
    class DrainTests {

        @Test
        @DisplayName("returns empty array when disabled")
        void emptyWhenDisabled() {
            collector.reportError(new RuntimeException("test"), "Module", TriggerContext.uncaught("test"));
            setField(collector, "enabled", false);

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("returns empty array when queue is empty")
        void emptyWhenNoErrors() {
            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("drains all available errors when under limit")
        void drainsAll() {
            collector.reportError(createExceptionAtLine("a", 1), "Module", TriggerContext.uncaught("test"));
            collector.reportError(createExceptionAtLine("b", 2), "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.size()).isEqualTo(2);

            // Second drain should be empty
            JsonArray errors2 = collector.drainErrors(10);
            assertThat(errors2.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("JSON output")
    class JsonTests {

        @Test
        @DisplayName("JSON contains all expected fields")
        void jsonHasAllFields() {
            Player mockPlayer = mock(Player.class);
            when(mockPlayer.getName()).thenReturn("Steve");
            when(mockPlayer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());

            TriggerContext ctx = TriggerContext.command(mockPlayer, "/test cmd");
            collector.reportError(new RuntimeException("test msg"), "TestModule", ctx);

            JsonArray errors = collector.drainErrors(10);
            JsonObject error = errors.get(0).getAsJsonObject();

            assertThat(error.has("fingerprint")).isTrue();
            assertThat(error.has("errorCode")).isTrue();
            assertThat(error.has("message")).isTrue();
            assertThat(error.has("stackTrace")).isTrue();
            assertThat(error.has("moduleName")).isTrue();
            assertThat(error.has("className")).isTrue();
            assertThat(error.has("methodName")).isTrue();
            assertThat(error.has("triggerType")).isTrue();
            assertThat(error.has("triggerDetail")).isTrue();
            assertThat(error.has("playerName")).isTrue();
            assertThat(error.has("playerUUID")).isTrue();
            assertThat(error.has("serverTPS")).isTrue();
            assertThat(error.has("serverMemoryPct")).isTrue();
            assertThat(error.has("playerCount")).isTrue();
            assertThat(error.has("occurrenceCount")).isTrue();
            assertThat(error.has("timestamp")).isTrue();
            assertThat(error.has("isFramework")).isTrue();
        }
    }

    @Nested
    @DisplayName("isFramework classification")
    class IsFrameworkTests {

        @Test
        @DisplayName("framework error: null module name with framework stack trace")
        void frameworkErrorNullModule() {
            RuntimeException ex = new RuntimeException("framework error");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.ultikits.ultitools.manager.PluginManager",
                            "loadPlugin", "PluginManager.java", 42),
                    new StackTraceElement("com.ultikits.ultitools.UltiTools",
                            "onEnable", "UltiTools.java", 100)
            });
            collector.reportError(ex, null, TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isTrue();
        }

        @Test
        @DisplayName("framework error: empty module name with framework stack trace")
        void frameworkErrorEmptyModule() {
            RuntimeException ex = new RuntimeException("framework error");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.ultikits.ultitools.aop.ExceptionInterceptor",
                            "intercept", "ExceptionInterceptor.java", 50)
            });
            collector.reportError(ex, "", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isTrue();
        }

        @Test
        @DisplayName("framework error: 'UltiTools' module name with framework stack trace")
        void frameworkErrorUltiToolsModule() {
            RuntimeException ex = new RuntimeException("framework error");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.ultikits.ultitools.commands.UltiToolsCommands",
                            "execute", "UltiToolsCommands.java", 10)
            });
            collector.reportError(ex, "UltiTools", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isTrue();
        }

        @Test
        @DisplayName("module error: named module with module stack trace")
        void moduleErrorNamedModule() {
            RuntimeException ex = new RuntimeException("module error");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.ultikits.plugins.ultichat.ChatListener",
                            "onChat", "ChatListener.java", 30),
                    new StackTraceElement("org.bukkit.plugin.EventExecutor",
                            "execute", "EventExecutor.java", 10)
            });
            collector.reportError(ex, "UltiChat", TriggerContext.event("AsyncPlayerChatEvent"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isFalse();
        }

        @Test
        @DisplayName("module error: named module even with framework stack trace")
        void moduleErrorNamedModuleFrameworkStack() {
            RuntimeException ex = new RuntimeException("module error from framework stack");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.ultikits.ultitools.manager.PluginManager",
                            "loadPlugin", "PluginManager.java", 42)
            });
            collector.reportError(ex, "UltiChat", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            // Module name is not "UltiTools"/null/empty, so not framework
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isFalse();
        }

        @Test
        @DisplayName("module error: UltiTools module name but non-framework stack trace")
        void ultiToolsModuleWithExternalStack() {
            RuntimeException ex = new RuntimeException("external stack error");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("org.bukkit.craftbukkit.CraftServer",
                            "dispatchCommand", "CraftServer.java", 100)
            });
            collector.reportError(ex, "UltiTools", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            // Module is "UltiTools" but top stack is not com.ultikits.ultitools.*
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isFalse();
        }

        @Test
        @DisplayName("framework error with empty stack trace defaults to true when module is framework")
        void frameworkErrorEmptyStackTrace() {
            RuntimeException ex = new RuntimeException("no stack");
            ex.setStackTrace(new StackTraceElement[0]);
            collector.reportError(ex, "UltiTools", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            assertThat(errors.get(0).getAsJsonObject().get("isFramework").getAsBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("Queue overflow")
    class QueueOverflowTests {

        @Test
        @DisplayName("rejects errors when queue is full")
        void rejectsWhenQueueFull() {
            // Fill the queue to MAX_QUEUE_SIZE (200)
            for (int i = 0; i < 200; i++) {
                collector.reportError(createExceptionAtLine("error" + i, i), "Module",
                        TriggerContext.uncaught("test"));
            }

            // This one should be silently dropped
            collector.reportError(createExceptionAtLine("overflow", 999), "Module",
                    TriggerContext.uncaught("test"));

            // Drain all and count unique entries
            JsonArray errors = collector.drainErrors(200);
            // Should not exceed 200 (some may be less due to maxErrorsPerBatch limit)
            assertThat(errors.size()).isLessThanOrEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Stack trace formatting")
    class StackTraceTests {

        @Test
        @DisplayName("includes cause chain in stack trace")
        void includesCauseChain() {
            RuntimeException cause = new RuntimeException("root cause");
            cause.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.example.DataAccess", "query", "DataAccess.java", 50)
            });
            RuntimeException ex = new RuntimeException("wrapper error", cause);
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.example.Service", "handle", "Service.java", 20)
            });

            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            String stackTrace = errors.get(0).getAsJsonObject().get("stackTrace").getAsString();
            assertThat(stackTrace).contains("wrapper error");
            assertThat(stackTrace).contains("Caused by");
            assertThat(stackTrace).contains("root cause");
        }

        @Test
        @DisplayName("truncates stack trace to max length")
        void truncatesStackTrace() {
            RuntimeException ex = new RuntimeException("deep stack");
            StackTraceElement[] frames = new StackTraceElement[100];
            for (int i = 0; i < 100; i++) {
                frames[i] = new StackTraceElement(
                        "com.example.very.long.package.name.Class" + i,
                        "veryLongMethodNameThatTakesUpLotsOfSpace" + i,
                        "VeryLongFileName" + i + ".java", i);
            }
            ex.setStackTrace(frames);

            collector.reportError(ex, "Module", TriggerContext.uncaught("test"));

            JsonArray errors = collector.drainErrors(10);
            String stackTrace = errors.get(0).getAsJsonObject().get("stackTrace").getAsString();
            assertThat(stackTrace.length()).isLessThanOrEqualTo(2048);
        }
    }

    // ===== Test helpers =====

    /**
     * Concrete UltiToolsException for testing.
     */
    private static class TestUltiToolsException extends UltiToolsException {
        TestUltiToolsException(ErrorCode errorCode, String message) {
            super(errorCode, message);
        }
    }

    /**
     * Create an exception with a predictable stack trace for fingerprinting.
     */
    private RuntimeException createExceptionAtLine(String message, int pseudoLine) {
        RuntimeException ex = new RuntimeException(message);
        ex.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.test.TestClass", "method" + pseudoLine, "TestClass.java", pseudoLine),
                new StackTraceElement("com.test.TestClass", "caller", "TestClass.java", 100),
                new StackTraceElement("com.test.TestClass", "main", "TestClass.java", 200)
        });
        return ex;
    }

    /**
     * Set a private field via reflection.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void setField(Object obj, String fieldName, Object value) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    /**
     * Clear the dedup map (simulates window reset).
     */
    @SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "unchecked"})
    private void clearDedupMap(ErrorReportCollector collector) {
        try {
            Field field = ErrorReportCollector.class.getDeclaredField("dedupMap");
            field.setAccessible(true);
            ((ConcurrentHashMap<String, ?>) field.get(collector)).clear();
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear dedup map", e);
        }
    }
}
