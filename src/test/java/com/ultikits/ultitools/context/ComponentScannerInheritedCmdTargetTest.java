package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import com.ultikits.ultitools.context.scan.cmdtargetinherited.BothInheritorCommand;
import com.ultikits.ultitools.context.scan.cmdtargetinherited.ConsoleOnlyInheritorCommand;

/**
 * Case 57 on the real scan path: a {@code @CmdTarget(CONSOLE)} command class that inherits a
 * {@code @CmdTarget(BOTH)} mapping is refused registration at load, exactly like a class that
 * declares the widening itself (see {@code ComponentScannerCmdTargetAmbiguityTest}), while a legal
 * sibling inheriting the same mapping still registers.
 * <p>
 * {@code com.ultikits.ultitools.context.scan.cmdtargetinherited} holds the shared, unannotated base
 * (skipped by the scanner), one offender and one legal sibling.
 */
@DisplayName("ComponentScanner refuses an inherited @CmdTarget widening (case 57)")
class ComponentScannerInheritedCmdTargetTest {

    private static final String FIXTURE_PACKAGE = "com.ultikits.ultitools.context.scan.cmdtargetinherited";

    private SimpleContainer container;
    private ComponentScanner scanner;

    private final List<LogRecord> capturedLogs = new ArrayList<>();
    private Logger scannerLogger;
    private Handler captureHandler;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        container.setClassLoader(getClass().getClassLoader());
        scanner = new ComponentScanner(container);

        capturedLogs.clear();
        scannerLogger = Logger.getLogger(ComponentScanner.class.getName());
        captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedLogs.add(record);
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
        scannerLogger.addHandler(captureHandler);
    }

    @AfterEach
    void tearDown() {
        scannerLogger.removeHandler(captureHandler);
    }

    /** Concatenates every captured {@code Level.SEVERE} message. */
    private String severeMessages() {
        StringBuilder sb = new StringBuilder();
        for (LogRecord record : capturedLogs) {
            if (Level.SEVERE.equals(record.getLevel()) && record.getMessage() != null) {
                sb.append(record.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("the console-only inheritor is refused and logged, the legal sibling registers, the scan does not throw")
    void inheritedWideningIsRefusedAtScan() {
        assertDoesNotThrow(() -> scanner.scanPackage(FIXTURE_PACKAGE));

        assertFalse(container.containsBean(beanNameOf(ConsoleOnlyInheritorCommand.class)),
                "ConsoleOnlyInheritorCommand must not become a bean definition");
        assertTrue(container.containsBean(beanNameOf(BothInheritorCommand.class)),
                "BothInheritorCommand must still register");
        String severe = severeMessages();
        assertTrue(severe.contains(ConsoleOnlyInheritorCommand.class.getName()), severe);
        assertTrue(severe.contains("#all"), severe);
    }

    /** Mirrors {@code ComponentScanner.getBeanName}'s default: decapitalized simple name. */
    private static String beanNameOf(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
