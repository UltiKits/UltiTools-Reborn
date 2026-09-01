package com.ultikits.ultitools.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModuleScanDiagnostics}'s per-module skipped-class accumulator (07-04 D-19).
 * <br>
 * {@link ModuleScanDiagnostics} 按模块统计跳过类的累加器测试（07-04 D-19）。
 *
 * <p>Captures {@link ModuleScanDiagnostics}'s own logger directly, following the same pattern
 * used throughout this test tree (e.g. {@code ComponentScannerTest},
 * {@code SecurityPolicyJarValidationTest}) — no live Bukkit server is required.</p>
 */
@DisplayName("ModuleScanDiagnostics 累加器测试")
class ModuleScanDiagnosticsTest {

    private final List<LogRecord> captured = new ArrayList<>();
    private Logger diagnosticsLogger;
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        captured.clear();
        diagnosticsLogger = Logger.getLogger(ModuleScanDiagnostics.class.getName());
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        diagnosticsLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.removeHandler(captureHandler);
    }

    private List<LogRecord> withLevel(Level level) {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord record : captured) {
            if (level.equals(record.getLevel())) {
                result.add(record);
            }
        }
        return result;
    }

    @Test
    @DisplayName("Test 1 (empty): a module with zero skipped classes emits no summary at all")
    void emptyModuleEmitsNoSummary() {
        ModuleScanDiagnostics.emitSummary("empty-module-" + System.nanoTime());

        assertTrue(captured.isEmpty(), "the emitter must not be invoked at all for a healthy module, "
                + "not merely produce an empty-text record");
    }

    @Test
    @DisplayName("Test 2 (single): one skipped class produces exactly one SEVERE record naming the "
            + "module, the class, and COMPATIBILITY.md")
    void singleSkippedClassProducesOneSevereRecord() {
        String moduleName = "single-module-" + System.nanoTime();
        String className = "com.example.stale.StaleCommand";

        ModuleScanDiagnostics.recordSkippedClass(moduleName, className, new LinkageError("boom"));
        ModuleScanDiagnostics.emitSummary(moduleName);

        List<LogRecord> severe = withLevel(Level.SEVERE);
        assertEquals(1, severe.size(), "exactly one SEVERE record must be emitted for one skipped class");
        String message = severe.get(0).getMessage();
        assertTrue(message.contains(moduleName), "the SEVERE message must name the module: " + message);
        assertTrue(message.contains(className), "the SEVERE message must name the skipped class: " + message);
        assertTrue(message.contains("COMPATIBILITY.md"),
                "the SEVERE message must point at COMPATIBILITY.md: " + message);
    }

    @Test
    @DisplayName("Test 3 (many): fifty skipped classes still produce exactly ONE SEVERE record, not fifty")
    void manySkippedClassesStillProduceOneSevereRecord() {
        String moduleName = "many-module-" + System.nanoTime();
        List<String> classNames = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String className = "com.example.stale.StaleClass" + i;
            classNames.add(className);
            ModuleScanDiagnostics.recordSkippedClass(moduleName, className, new ClassNotFoundException());
        }

        ModuleScanDiagnostics.emitSummary(moduleName);

        List<LogRecord> severe = withLevel(Level.SEVERE);
        assertEquals(1, severe.size(), "fifty skipped classes must still collapse into one SEVERE record");
        String message = severe.get(0).getMessage();
        for (String className : classNames) {
            assertTrue(message.contains(className), "the single summary must list every skipped class: "
                    + className);
        }
    }

    @Test
    @DisplayName("Test 4: per-class detail is available at FINE level, matching D-14's shape")
    void perClassDetailIsLoggedAtFineLevel() {
        String moduleName = "fine-detail-module-" + System.nanoTime();
        String className = "com.example.stale.FineDetailClass";
        Throwable cause = new NoClassDefFoundError("com/example/Removed");

        ModuleScanDiagnostics.recordSkippedClass(moduleName, className, cause);

        List<LogRecord> fine = withLevel(Level.FINE);
        boolean found = fine.stream().anyMatch(r ->
                r.getMessage() != null
                        && r.getMessage().contains(moduleName)
                        && r.getMessage().contains(className)
                        && r.getThrown() == cause);
        assertTrue(found, "expected a FINE record naming the module and class, with the cause attached");

        // The FINE detail must not itself have produced a SEVERE record before emitSummary runs.
        assertTrue(withLevel(Level.SEVERE).isEmpty(),
                "recording a skip must not by itself emit the module summary");
    }

    @Test
    @DisplayName("Test 5 (isolation): accumulating for module A then module B produces two "
            + "independent summaries; A's classes never leak into B's")
    void moduleSummariesAreIsolatedFromEachOther() {
        long id = System.nanoTime();
        String moduleA = "module-a-" + id;
        String moduleB = "module-b-" + id;
        String classA = "com.example.a.OnlyInA";
        String classB = "com.example.b.OnlyInB";

        ModuleScanDiagnostics.recordSkippedClass(moduleA, classA, new LinkageError());
        ModuleScanDiagnostics.recordSkippedClass(moduleB, classB, new LinkageError());

        ModuleScanDiagnostics.emitSummary(moduleA);
        ModuleScanDiagnostics.emitSummary(moduleB);

        List<LogRecord> severe = withLevel(Level.SEVERE);
        assertEquals(2, severe.size(), "each module must produce its own independent summary");

        String summaryA = severe.stream().filter(r -> r.getMessage().contains(moduleA))
                .findFirst().map(LogRecord::getMessage).orElse("");
        String summaryB = severe.stream().filter(r -> r.getMessage().contains(moduleB))
                .findFirst().map(LogRecord::getMessage).orElse("");

        assertTrue(summaryA.contains(classA), "module A's summary must contain its own skipped class");
        assertFalse(summaryA.contains(classB), "module A's summary must never contain module B's class");
        assertTrue(summaryB.contains(classB), "module B's summary must contain its own skipped class");
        assertFalse(summaryB.contains(classA), "module B's summary must never contain module A's class");
    }

    @Test
    @DisplayName("Test 6: the accumulator resets after emitting, so a re-scan of the same module "
            + "does not double-report")
    void accumulatorResetsAfterEmit() {
        String moduleName = "reset-module-" + System.nanoTime();
        String firstClass = "com.example.stale.FirstScanClass";
        String secondClass = "com.example.stale.SecondScanClass";

        ModuleScanDiagnostics.recordSkippedClass(moduleName, firstClass, new LinkageError());
        ModuleScanDiagnostics.emitSummary(moduleName);

        // A re-scan of the same module with nothing new skipped must stay silent.
        ModuleScanDiagnostics.emitSummary(moduleName);

        List<LogRecord> severeAfterQuietRescan = withLevel(Level.SEVERE);
        assertEquals(1, severeAfterQuietRescan.size(),
                "an empty re-scan must not repeat the previous summary");

        // A genuine second scan that skips a different class produces a fresh, independent summary.
        ModuleScanDiagnostics.recordSkippedClass(moduleName, secondClass, new LinkageError());
        ModuleScanDiagnostics.emitSummary(moduleName);

        List<LogRecord> severeAfterSecondScan = withLevel(Level.SEVERE);
        assertEquals(2, severeAfterSecondScan.size(), "a genuine second scan must produce its own summary");
        String secondSummary = severeAfterSecondScan.get(1).getMessage();
        assertTrue(secondSummary.contains(secondClass), secondSummary);
        assertFalse(secondSummary.contains(firstClass),
                "the second summary must not repeat the first scan's already-reported class: " + secondSummary);
    }

    @Test
    @DisplayName("Test 7 (null/blank input): a null or blank class name is ignored, not recorded as literal \"null\"")
    void nullOrBlankClassNameIsIgnored() {
        String moduleName = "blank-input-module-" + System.nanoTime();

        ModuleScanDiagnostics.recordSkippedClass(moduleName, null, new LinkageError());
        ModuleScanDiagnostics.recordSkippedClass(moduleName, "   ", new LinkageError());
        ModuleScanDiagnostics.recordSkippedClass(moduleName, "", new LinkageError());

        assertTrue(withLevel(Level.FINE).isEmpty(), "a null/blank class name must not even reach the "
                + "per-class FINE detail");

        ModuleScanDiagnostics.emitSummary(moduleName);

        assertTrue(withLevel(Level.SEVERE).isEmpty(),
                "a module that only ever received null/blank class names must stay silent");
    }
}
