package com.ultikits.ultitools.abstracts.command.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition.Transition;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.CmdTarget.CmdTargetType;

/**
 * Pins the nine-cell class/method {@code @CmdTarget} composition table decided by D-01: a
 * method-level value may only narrow the class-level one, and lateral switching is its own
 * category rather than being folded into widening. See
 * {@code .planning/phases/01-adjudication-foundations-compatibility-baseline/01-ADJUDICATION.md}.
 * <p>
 * 固定 D-01 决定的类/方法组合九宫格：方法级取值只能收窄类级取值，横向切换是独立类别，
 * 不并入放宽。
 */
@DisplayName("CmdTargetComposition Tests")
class CmdTargetCompositionTest {

    // ========== Fixtures for check(Class<?>) ==========

    /** No class-level {@code @CmdTarget} at all — absent means BOTH, per Test 6. */
    private static class NoClassLevelAnnotationFixture {
        @CmdTarget(CmdTargetType.PLAYER)
        public void restrictedToPlayer() {
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class WideningFixture {
        @CmdTarget(CmdTargetType.BOTH)
        public void widensToBoth() {
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class LateralFixture {
        @CmdTarget(CmdTargetType.CONSOLE)
        public void switchesToConsole() {
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class TwoOffendingMethodsFixture {
        @CmdTarget(CmdTargetType.BOTH)
        public void widensToBoth() {
        }

        @CmdTarget(CmdTargetType.CONSOLE)
        public void switchesToConsole() {
        }
    }

    @CmdTarget(CmdTargetType.CONSOLE)
    private static class LegalFixture {
        @CmdTarget(CmdTargetType.CONSOLE)
        public void identical() {
        }
    }

    @Nested
    @DisplayName("classify(classLevel, methodLevel)")
    class ClassifyTests {

        @Test
        @DisplayName("Test 1: BOTH-to-PLAYER and BOTH-to-CONSOLE are NARROWING")
        void narrowingFromBoth() {
            assertEquals(Transition.NARROWING,
                    CmdTargetComposition.classify(CmdTargetType.BOTH, CmdTargetType.PLAYER));
            assertEquals(Transition.NARROWING,
                    CmdTargetComposition.classify(CmdTargetType.BOTH, CmdTargetType.CONSOLE));
        }

        @Test
        @DisplayName("Test 2: identical class/method values are SAME")
        void identicalIsSame() {
            assertEquals(Transition.SAME,
                    CmdTargetComposition.classify(CmdTargetType.PLAYER, CmdTargetType.PLAYER));
            assertEquals(Transition.SAME,
                    CmdTargetComposition.classify(CmdTargetType.CONSOLE, CmdTargetType.CONSOLE));
            assertEquals(Transition.SAME,
                    CmdTargetComposition.classify(CmdTargetType.BOTH, CmdTargetType.BOTH));
        }

        @Test
        @DisplayName("Test 3: PLAYER-to-BOTH and CONSOLE-to-BOTH are WIDENING")
        void wideningToBoth() {
            assertEquals(Transition.WIDENING,
                    CmdTargetComposition.classify(CmdTargetType.PLAYER, CmdTargetType.BOTH));
            assertEquals(Transition.WIDENING,
                    CmdTargetComposition.classify(CmdTargetType.CONSOLE, CmdTargetType.BOTH));
        }

        @Test
        @DisplayName("Test 4: PLAYER-to-CONSOLE and CONSOLE-to-PLAYER are LATERAL, not WIDENING")
        void lateralSwitching() {
            assertEquals(Transition.LATERAL,
                    CmdTargetComposition.classify(CmdTargetType.PLAYER, CmdTargetType.CONSOLE));
            assertEquals(Transition.LATERAL,
                    CmdTargetComposition.classify(CmdTargetType.CONSOLE, CmdTargetType.PLAYER));
        }
    }

    @Nested
    @DisplayName("resolve(classLevel, method)")
    class ResolveTests {

        @Test
        @DisplayName("Test 5: resolve returns the method's value for SAME and NARROWING, "
                + "and the class value when the method carries no annotation")
        void resolvesToMethodOrClass() throws NoSuchMethodException {
            Method narrowing = WideningFixture.class.getDeclaredMethod("widensToBoth");
            // Reused only for its method-level BOTH annotation; class-level input here is BOTH,
            // so the transition under test is SAME, not the fixture's own WIDENING case.
            assertEquals(CmdTargetType.BOTH,
                    CmdTargetComposition.resolve(CmdTargetType.BOTH, narrowing));

            Method noAnnotation = SilentMethodFixture.class.getDeclaredMethod("silent");
            assertEquals(CmdTargetType.PLAYER,
                    CmdTargetComposition.resolve(CmdTargetType.PLAYER, noAnnotation));
        }

        private class SilentMethodFixture {
            public void silent() {
            }
        }
    }

    @Nested
    @DisplayName("check(Class<?>)")
    class CheckTests {

        @Test
        @DisplayName("Test 6: absent class-level annotation means BOTH, so a method-level "
                + "PLAYER restriction is a narrowing and yields no violation")
        void absentClassLevelIsTreatedAsBoth() {
            List<String> violations = CmdTargetComposition.check(NoClassLevelAnnotationFixture.class);
            assertTrue(violations.isEmpty(), violations.toString());
        }

        @Test
        @DisplayName("Test 7: class PLAYER + method BOTH yields exactly one violation naming "
                + "the class and the offending method")
        void wideningYieldsOneNamedViolation() {
            List<String> violations = CmdTargetComposition.check(WideningFixture.class);
            assertEquals(1, violations.size());
            String violation = violations.get(0);
            assertTrue(violation.contains(WideningFixture.class.getName()), violation);
            assertTrue(violation.contains("widensToBoth"), violation);
        }

        @Test
        @DisplayName("Test 8: class PLAYER + method CONSOLE yields exactly one violation "
                + "naming the lateral transition")
        void lateralYieldsOneViolationNamingLateral() {
            List<String> violations = CmdTargetComposition.check(LateralFixture.class);
            assertEquals(1, violations.size());
            String violation = violations.get(0);
            assertTrue(violation.contains(LateralFixture.class.getName()), violation);
            assertTrue(violation.contains("switchesToConsole"), violation);
            assertTrue(violation.contains(Transition.LATERAL.name()), violation);
        }

        @Test
        @DisplayName("Test 9: one violation per offending method, not one per class")
        void oneViolationPerOffendingMethod() {
            List<String> violations = CmdTargetComposition.check(TwoOffendingMethodsFixture.class);
            assertEquals(2, violations.size());
        }

        @Test
        @DisplayName("A fully legal class (identical class/method values) yields no violations")
        void legalClassYieldsNoViolations() {
            List<String> violations = CmdTargetComposition.check(LegalFixture.class);
            assertTrue(violations.isEmpty(), violations.toString());
        }
    }
}
