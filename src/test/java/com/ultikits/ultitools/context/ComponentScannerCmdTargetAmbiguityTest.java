package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
 */
@DisplayName("ComponentScanner @CmdTarget ambiguity refusal")
class ComponentScannerCmdTargetAmbiguityTest {

    private SimpleContainer container;
    private ComponentScanner scanner;

    private ByteArrayOutputStream capturedErr;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        container.setClassLoader(getClass().getClassLoader());
        scanner = new ComponentScanner(container);

        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
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

        String stderr = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains(WideningCommand.class.getName()), stderr);
        assertTrue(stderr.contains("widensToBoth"), stderr);
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

        String stderr = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains(LateralCommand.class.getName()), stderr);
        assertTrue(stderr.contains("switchesToConsole"), stderr);
    }

    /** Mirrors {@code ComponentScanner.getBeanName}'s default: decapitalized simple name. */
    private static String beanNameOf(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
