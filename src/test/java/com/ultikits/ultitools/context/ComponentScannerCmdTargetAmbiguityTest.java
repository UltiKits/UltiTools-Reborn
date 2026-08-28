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

import com.ultikits.ultitools.context.scan.cmdtargetambiguity.LateralCommand;
import com.ultikits.ultitools.context.scan.cmdtargetambiguity.LegalSiblingCommand;
import com.ultikits.ultitools.context.scan.cmdtargetambiguity.WideningCommand;
import com.ultikits.ultitools.context.scan.cmdtargetlegalonly.IdenticalCommand;
import com.ultikits.ultitools.context.scan.cmdtargetlegalonly.NarrowingCommand;

/**
 * Proves that an ambiguous {@code @CmdTarget} composition (D-01: WIDENING or LATERAL) is refused
 * registration at plugin load - never becomes a bean definition - while every other command
 * class in the same module still registers. See T-01-01b in this plan's threat model: the check
 * runs inside {@code ComponentScanner.registerComponent}'s existing per-class try/catch so the
 * blast radius is one command class, not the whole module.
 * <p>
 * {@code com.ultikits.ultitools.context.scan.cmdtargetambiguity} holds exactly one WIDENING
 * fixture, one LATERAL fixture, and one legal sibling, so a scan of that package alone can
 * assert both halves deterministically: the offenders absent, the sibling present.
 * {@code com.ultikits.ultitools.context.scan.cmdtargetlegalonly} holds nothing but legal
 * compositions, proving the check refuses nothing it should not.
 * <p>
 * 验证歧义的 @CmdTarget 组合（放宽或横向切换）在插件加载期被拒绝注册——永远不会成为
 * bean 定义——而同一模块中的其他指令类仍正常注册。
 * <p>
 * The refusal was converted from a direct standard-error write to a leveled
 * {@code Logger}/{@code Level.SEVERE} call by 03-07 (D-24: a refusal is a registration failure,
 * not a skip) - this test now captures {@link LogRecord}s from
 * {@code Logger.getLogger(ComponentScanner.class.getName())} instead of {@code System.err}.
 */
@DisplayName("ComponentScanner @CmdTarget ambiguity refusal")
class ComponentScannerCmdTargetAmbiguityTest {

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

    /** Concatenates every captured {@code Level.SEVERE} message, mirroring the old stderr capture's shape. */
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
    @DisplayName("Test 1 & 2: the offender is absent, the sibling is present, and the scan "
            + "does not throw")
    void refusesOffenderKeepsSiblingWithoutThrowing() {
        assertDoesNotThrow(() ->
                scanner.scanPackage("com.ultikits.ultitools.context.scan.cmdtargetambiguity"));

        assertFalse(container.containsBean(beanNameOf(WideningCommand.class)),
                "WideningCommand must not become a bean definition");
        assertTrue(container.containsBean(beanNameOf(LegalSiblingCommand.class)),
                "LegalSiblingCommand must still register even though its sibling was refused");
    }

    @Test
    @DisplayName("Test 3: the refusal message names the offender's class and method")
    void refusalMessageNamesClassAndMethod() {
        scanner.scanPackage("com.ultikits.ultitools.context.scan.cmdtargetambiguity");

        String severe = severeMessages();
        assertTrue(severe.contains(WideningCommand.class.getName()), severe);
        assertTrue(severe.contains("widensToBoth"), severe);
    }

    @Test
    @DisplayName("Test 4: a package containing only legal command classes registers all of them")
    void legalOnlyPackageRegistersEverything() {
        scanner.scanPackage("com.ultikits.ultitools.context.scan.cmdtargetlegalonly");

        assertTrue(container.containsBean(beanNameOf(IdenticalCommand.class)));
        assertTrue(container.containsBean(beanNameOf(NarrowingCommand.class)));
    }

    @Test
    @DisplayName("Test 5: a lateral transition is refused on the same path as a widening one")
    void lateralIsRefusedSameAsWidening() {
        scanner.scanPackage("com.ultikits.ultitools.context.scan.cmdtargetambiguity");

        assertFalse(container.containsBean(beanNameOf(LateralCommand.class)),
                "LateralCommand must not become a bean definition");

        String severe = severeMessages();
        assertTrue(severe.contains(LateralCommand.class.getName()), severe);
        assertTrue(severe.contains("switchesToConsole"), severe);
    }

    /** Mirrors {@code ComponentScanner.getBeanName}'s default: decapitalized simple name. */
    private static String beanNameOf(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
