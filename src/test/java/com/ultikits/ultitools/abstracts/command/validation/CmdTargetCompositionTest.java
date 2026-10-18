package com.ultikits.ultitools.abstracts.command.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition.Transition;
import com.ultikits.ultitools.annotations.command.CmdMapping;
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
            // fixture mapping: body intentionally empty
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class WideningFixture {
        @CmdTarget(CmdTargetType.BOTH)
        public void widensToBoth() {
            // fixture mapping: body intentionally empty
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class LateralFixture {
        @CmdTarget(CmdTargetType.CONSOLE)
        public void switchesToConsole() {
            // fixture mapping: body intentionally empty
        }
    }

    @CmdTarget(CmdTargetType.PLAYER)
    private static class TwoOffendingMethodsFixture {
        @CmdTarget(CmdTargetType.BOTH)
        public void widensToBoth() {
            // fixture mapping: body intentionally empty
        }

        @CmdTarget(CmdTargetType.CONSOLE)
        public void switchesToConsole() {
            // fixture mapping: body intentionally empty
        }
    }

    @CmdTarget(CmdTargetType.CONSOLE)
    private static class LegalFixture {
        @CmdTarget(CmdTargetType.CONSOLE)
        public void identical() {
            // fixture mapping: body intentionally empty
        }
    }

    // ========== Fixtures for inherited mappings (case 57) ==========
    // Declared here, not inside the @Nested class that uses them: tests compile with -source 1.8,
    // where an inner class cannot declare static nested classes. Methods are public so the
    // override rule needs no package reasoning.

    /** No class-level target; declares two mappings with method-level targets and one non-mapping. */
    private static class BothMappingBase {
        @CmdMapping(format = "all")
        @CmdTarget(CmdTargetType.BOTH)
        public void all() {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "players")
        @CmdTarget(CmdTargetType.PLAYER)
        public void playersOnly() {
            // fixture mapping: body intentionally empty
        }

        @CmdTarget(CmdTargetType.BOTH)
        public void helperWithTarget() {
            // fixture mapping: body intentionally empty
        }
    }

    /** CONSOLE class inheriting a BOTH mapping (widening) and a PLAYER mapping (lateral). */
    @CmdTarget(CmdTargetType.CONSOLE)
    private static class ConsoleInheritor extends BothMappingBase {
    }

    /** No class-level target: both inherited mappings are narrowings or identical. */
    private static class BothInheritor extends BothMappingBase {
    }

    /** Overrides all() keeping @CmdMapping but not @CmdTarget, and playersOnly() with neither. */
    @CmdTarget(CmdTargetType.CONSOLE)
    private static class OverridingConsoleInheritor extends BothMappingBase {
        @Override
        @CmdMapping(format = "all")
        public void all() {
            // fixture mapping: body intentionally empty
        }

        @Override
        public void playersOnly() {
            // fixture mapping: body intentionally empty
        }
    }

    /** Declares its own mappings under the base's formats, so the base's mappings are shadowed. */
    @CmdTarget(CmdTargetType.CONSOLE)
    private static class ShadowingConsoleInheritor extends BothMappingBase {
        @CmdMapping(format = "all")
        public void own() {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "players")
        public void own2() {
            // fixture mapping: body intentionally empty
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
                // fixture mapping: body intentionally empty
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

    @Nested
    @DisplayName("check(Class<?>) on inherited mappings (case 57)")
    class InheritedMappingTests {

        private String violationFor(List<String> violations, String methodName) {
            for (String violation : violations) {
                if (violation.contains("#" + methodName)) {
                    return violation;
                }
            }
            throw new AssertionError("no violation names #" + methodName + ": " + violations);
        }

        @Test
        @DisplayName("an inherited BOTH mapping (widening) and PLAYER mapping (lateral) under a CONSOLE class are refused")
        void inheritedWideningAndLateralAreRefused() {
            List<String> violations = CmdTargetComposition.check(ConsoleInheritor.class);

            assertEquals(2, violations.size(), violations.toString());
            assertTrue(violationFor(violations, "all").contains(Transition.WIDENING.name()), violations.toString());
            assertTrue(violationFor(violations, "playersOnly").contains(Transition.LATERAL.name()),
                    violations.toString());
        }

        @Test
        @DisplayName("each inherited violation names the checked class, the method and the declaring class")
        void violationNamesTheDeclaringClass() {
            List<String> violations = CmdTargetComposition.check(ConsoleInheritor.class);

            assertEquals(2, violations.size(), violations.toString());
            for (String methodName : new String[]{"all", "playersOnly"}) {
                String violation = violationFor(violations, methodName);
                assertTrue(violation.contains(ConsoleInheritor.class.getName()), violation);
                assertTrue(violation.contains(BothMappingBase.class.getName()), violation);
            }
        }

        @Test
        @DisplayName("control: an unannotated class inheriting the same mappings has no violation")
        void inheritedNarrowingIsLegal() {
            List<String> violations = CmdTargetComposition.check(BothInheritor.class);
            assertTrue(violations.isEmpty(), violations.toString());
        }

        @Test
        @DisplayName("control: an override without @CmdTarget follows the class level; one without @CmdMapping is not a mapping")
        void overrideWithoutTargetFollowsTheClassLevel() {
            List<String> violations = CmdTargetComposition.check(OverridingConsoleInheritor.class);
            assertTrue(violations.isEmpty(), violations.toString());
        }

        @Test
        @DisplayName("control: mappings the class declares under the same formats shadow the base's")
        void shadowedFormatsAreNotChecked() {
            List<String> violations = CmdTargetComposition.check(ShadowingConsoleInheritor.class);
            assertTrue(violations.isEmpty(), violations.toString());
        }
    }
}
