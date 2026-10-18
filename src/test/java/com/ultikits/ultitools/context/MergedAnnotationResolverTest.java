package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.AliasFor;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.utils.AnnotationUtils;

/**
 * Resolver-level coverage for {@link MergedAnnotationResolver} (WIRE-08).
 * <p>
 * Companion to {@code PluginManagerAutoRegisterAliasTest}, which proves the same
 * {@code @AliasFor} switches reach the real caller ({@code PluginManager.registerBukkit}); this
 * class covers the resolver's own merge semantics in isolation.
 */
@DisplayName("MergedAnnotationResolver")
class MergedAnnotationResolverTest {

    // ===== Task 1 fixtures: cross-annotation @AliasFor on @UltiToolsModule =====

    @UltiToolsModule
    static class DefaultModuleFixture {
    }

    @UltiToolsModule(cmdExecutor = false)
    static class CmdExecutorDisabledFixture {
    }

    @UltiToolsModule(eventListener = false)
    static class EventListenerDisabledFixture {
    }

    @UltiToolsModule(i18n = {"zh", "en"})
    static class I18nOverrideFixture {
    }

    // ===== Cyclic meta-annotation graph fixture (T-03-03) =====

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @interface CyclicY {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
    @CyclicY
    @interface CyclicX {
    }

    @CyclicX
    static class CyclicGraphFixture {
    }

    // ===== Behavior =====

    @Test
    @DisplayName("With no explicit attributes, the merged EnableAutoRegister reports meta-annotation defaults")
    void shouldReportMetaAnnotationDefaultsWhenNothingOverridden() {
        EnableAutoRegister merged =
                MergedAnnotationResolver.find(DefaultModuleFixture.class, EnableAutoRegister.class);

        assertNotNull(merged);
        assertTrue(merged.cmdExecutor(), "default cmdExecutor must remain true");
        assertTrue(merged.eventListener(), "default eventListener must remain true");
    }

    @Test
    @DisplayName("@UltiToolsModule(cmdExecutor = false) overrides cmdExecutor without touching the sibling eventListener")
    void shouldOverrideCmdExecutorOnlyWhenExplicitlyDisabled() {
        EnableAutoRegister merged =
                MergedAnnotationResolver.find(CmdExecutorDisabledFixture.class, EnableAutoRegister.class);

        assertNotNull(merged);
        assertFalse(merged.cmdExecutor(), "cmdExecutor = false must reach the merged view");
        assertTrue(merged.eventListener(), "eventListener must stay at its own default, unaffected");
    }

    @Test
    @DisplayName("@UltiToolsModule(eventListener = false) overrides eventListener")
    void shouldOverrideEventListenerWhenExplicitlyDisabled() {
        EnableAutoRegister merged =
                MergedAnnotationResolver.find(EventListenerDisabledFixture.class, EnableAutoRegister.class);

        assertNotNull(merged);
        assertFalse(merged.eventListener(), "eventListener = false must reach the merged view");
    }

    @Test
    @DisplayName("Control: AnnotationUtils.findAnnotation still returns the un-merged annotation for the same fixture")
    void controlAnnotationUtilsFindAnnotationIsUnaffected() {
        // This is the proof that the behaviour change comes from the resolver, not from an edit
        // to the fixture or to @UltiToolsModule -- without this control, a test suite that only
        // asserted the merged result could pass for the wrong reason (e.g. the fixture itself
        // being rewritten).
        EnableAutoRegister unmerged =
                AnnotationUtils.findAnnotation(CmdExecutorDisabledFixture.class, EnableAutoRegister.class);

        assertNotNull(unmerged);
        assertTrue(unmerged.cmdExecutor(),
                "the legacy two-level lookup must still report the meta-annotation default, "
                        + "proving it never inspects @AliasFor");
    }

    @Test
    @DisplayName("A String[]-typed @AliasFor (i18n -> I18n.value) merges correctly, not just boolean-typed ones")
    void shouldMergeStringArrayTypedAlias() {
        I18n merged = MergedAnnotationResolver.find(I18nOverrideFixture.class, I18n.class);

        assertNotNull(merged);
        assertArrayEquals(new String[] {"zh", "en"}, merged.value());
    }

    @Test
    @DisplayName("A cyclic meta-annotation graph terminates instead of overflowing the stack")
    void shouldTerminateOnCyclicMetaAnnotationGraph() {
        assertDoesNotThrow(() -> MergedAnnotationResolver.find(CyclicGraphFixture.class, Deprecated.class),
                "a cycle in the meta-annotation graph must not cause a StackOverflowError");
    }

    @Test
    @DisplayName("Control: @UltiToolsModule's own six @AliasFor declarations pass structural validation")
    void validateAliasesAcceptsUltiToolsModuleItself() {
        // Regression control for Task 2's validateAliases: without this assertion, a validator
        // that rejected every declaration (not just malformed ones) would also make every
        // malformed-case test in this file pass for the wrong reason.
        assertDoesNotThrow(() -> MergedAnnotationResolver.validateAliases(UltiToolsModule.class));
    }

    // ===== @AliasFor whose value() shorthand attribute is used (resolveAttributeName fallback) =====

    @Retention(RetentionPolicy.RUNTIME)
    @interface ShorthandTarget {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @ShorthandTarget
    @interface ShorthandAlias {
        @AliasFor(annotation = ShorthandTarget.class, value = "value")
        String foo() default "";
    }

    @ShorthandAlias(foo = "shorthand-value")
    static class ShorthandFixture {
    }

    @Test
    @DisplayName("@AliasFor's value() shorthand resolves the target attribute name")
    void shouldResolveShorthandAliasForValue() {
        ShorthandTarget merged = MergedAnnotationResolver.find(ShorthandFixture.class, ShorthandTarget.class);

        assertNotNull(merged);
        assertTrue("shorthand-value".equals(merged.value()));
    }

    // ===== Task 2: malformed @AliasFor declarations (D-02) =====

    // Shape 1: annotation() references a type that is NOT meta-present on the declaring
    // annotation.
    @Retention(RetentionPolicy.RUNTIME)
    @interface UnrelatedTarget {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface NotMetaPresentAlias {
        @AliasFor(annotation = UnrelatedTarget.class, attribute = "value")
        String foo() default "";
    }

    @Test
    @DisplayName("Malformed: annotation() targets a type that is not meta-present on the declaring annotation")
    void shouldRejectAliasForWhoseTargetIsNotMetaPresent() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> MergedAnnotationResolver.validateAliases(NotMetaPresentAlias.class));

        assertTrue(thrown.getMessage().contains(NotMetaPresentAlias.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foo"), thrown.getMessage());
    }

    // Shape 2: attribute() names an attribute that does not exist on the target annotation.
    @Retention(RetentionPolicy.RUNTIME)
    @interface SimpleTarget {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @SimpleTarget
    @interface MissingAttributeAlias {
        @AliasFor(annotation = SimpleTarget.class, attribute = "doesNotExist")
        String foo() default "";
    }

    @Test
    @DisplayName("Malformed: attribute() names an attribute that does not exist on the target annotation")
    void shouldRejectAliasForWhoseAttributeDoesNotExist() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> MergedAnnotationResolver.validateAliases(MissingAttributeAlias.class));

        assertTrue(thrown.getMessage().contains(MissingAttributeAlias.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foo"), thrown.getMessage());
    }

    // Shape 3: the aliasing attribute and the target attribute have different return types.
    @Retention(RetentionPolicy.RUNTIME)
    @interface TypedTarget {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @TypedTarget
    @interface MismatchedTypeAlias {
        @AliasFor(annotation = TypedTarget.class, attribute = "value")
        boolean foo() default false;
    }

    @Test
    @DisplayName("Malformed: aliased attributes do not share the same return type")
    void shouldRejectAliasForWithMismatchedReturnType() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> MergedAnnotationResolver.validateAliases(MismatchedTypeAlias.class));

        assertTrue(thrown.getMessage().contains(MismatchedTypeAlias.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foo"), thrown.getMessage());
    }

    // Shape 4: a within-annotation mutual alias where only one side declares @AliasFor back at
    // the other -- not reciprocal.
    @Retention(RetentionPolicy.RUNTIME)
    @interface NonReciprocalMutualAlias {
        @AliasFor("bar")
        String foo() default "";

        String bar() default "";
    }

    @Test
    @DisplayName("Malformed: a within-annotation mutual alias is not reciprocal")
    void shouldRejectNonReciprocalMutualAlias() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> MergedAnnotationResolver.validateAliases(NonReciprocalMutualAlias.class));

        assertTrue(thrown.getMessage().contains(NonReciprocalMutualAlias.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foo"), thrown.getMessage());
    }

    // ===== Task 2: within-annotation mutual alias, well-formed and conflicting instances =====

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface MutualAlias {
        @AliasFor("bar")
        String foo() default "";

        @AliasFor("foo")
        String bar() default "";
    }

    @MutualAlias(foo = "x")
    static class WellFormedMutualAliasFixture {
    }

    @MutualAlias(foo = "x", bar = "y")
    static class ConflictingMutualAliasFixture {
    }

    @Test
    @DisplayName("Control: a well-formed mutual alias declaration passes structural validation")
    void validateAliasesAcceptsWellFormedMutualAlias() {
        assertDoesNotThrow(() -> MergedAnnotationResolver.validateAliases(MutualAlias.class));
    }

    @Test
    @DisplayName("Well-formed mutual alias with only one side set: the set side wins for both, no exception")
    void wellFormedMutualAliasWithOneSideSetResolvesWithoutConflict() {
        MutualAlias merged = MergedAnnotationResolver.find(WellFormedMutualAliasFixture.class, MutualAlias.class);

        assertNotNull(merged);
        assertEquals("x", merged.foo());
        assertEquals("x", merged.bar());
    }

    @Test
    @DisplayName("Mutual alias with different non-default values on the same concrete instance -> ContainerException naming both attributes")
    void conflictingMutualAliasValuesAreRejectedAtResolution() {
        ContainerException thrown = assertThrows(ContainerException.class,
                () -> MergedAnnotationResolver.find(ConflictingMutualAliasFixture.class, MutualAlias.class));

        assertTrue(thrown.getMessage().contains(MutualAlias.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("foo"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("bar"), thrown.getMessage());
    }

    // ===== C45 fixtures: a directly declared target beside @UltiToolsModule, in both orders =====
    // Declared here, not inside the @Nested group: with -source 1.8 an inner class cannot
    // declare static member types.

    @UltiToolsModule
    @ComponentScan("other.pkg")
    static class ModuleThenDirectScanFixture {
    }

    @ComponentScan("other.pkg")
    @UltiToolsModule
    static class DirectScanThenModuleFixture {
    }

    @UltiToolsModule
    @EnableAutoRegister(cmdExecutor = false)
    static class ModuleThenDirectSwitchFixture {
    }

    @EnableAutoRegister(cmdExecutor = false)
    @UltiToolsModule
    static class DirectSwitchThenModuleFixture {
    }

    // Both declared (question 4 of 20.4-DECISIONS.md, option A): the direct one hides the
    // meta-present one completely.

    @UltiToolsModule(scanBasePackages = "a")
    @ComponentScan("b")
    static class BothScanModuleFirstFixture {
    }

    @ComponentScan("b")
    @UltiToolsModule(scanBasePackages = "a")
    static class BothScanDirectFirstFixture {
    }

    @UltiToolsModule(cmdExecutor = false)
    @EnableAutoRegister
    static class BothSwitchModuleFirstFixture {
    }

    @EnableAutoRegister
    @UltiToolsModule(cmdExecutor = false)
    static class BothSwitchDirectFirstFixture {
    }

    @UltiToolsModule(scanBasePackages = "x")
    static class ModuleOnlyScanFixture {
    }

    // Nearest selection across meta distance: @FarScan reaches @ComponentScan at distance 2,
    // @NearScan at distance 1.

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ComponentScan("far")
    @interface FarScanInner {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @FarScanInner
    @interface FarScan {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ComponentScan("near")
    @interface NearScan {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ComponentScan("near-second")
    @interface NearScanSecond {
    }

    @FarScan
    @NearScan
    static class FarDeclaredBeforeNearFixture {
    }

    @NearScanSecond
    @NearScan
    static class EqualDistanceFixture {
    }

    // Superclass fallback: the subclass's own trees reach @ComponentScan at distance 2, the
    // superclass declares one directly.

    @ComponentScan("super")
    static class DirectScanSuperFixture {
    }

    @FarScan
    static class DeepScanSubFixture extends DirectScanSuperFixture {
    }

    static class UnannotatedSubFixture extends DirectScanSuperFixture {
    }

    /**
     * C45 (decision P-10): the nearest declaration of the target wins. A directly declared
     * annotation (distance 0) wins over a meta-present one whatever the declaration order, the
     * meta graph is searched breadth-first with declaration order breaking ties, and the
     * superclass is consulted only after the class's own annotation trees hold no occurrence.
     */
    @Nested
    @DisplayName("C45: a directly declared annotation wins over a meta-present one")
    class DirectDeclarationPrecedenceTests {

        @Test
        @DisplayName("@UltiToolsModule then direct @ComponentScan: the direct value is used")
        void moduleThenDirectComponentScanResolvesDirectValue() {
            ComponentScan merged = MergedAnnotationResolver.find(ModuleThenDirectScanFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"other.pkg"}, merged.value(),
                    "a directly declared @ComponentScan must not be hidden by @UltiToolsModule's meta default");
        }

        @Test
        @DisplayName("Direct @ComponentScan then @UltiToolsModule: the direct value is used")
        void directComponentScanThenModuleResolvesDirectValue() {
            ComponentScan merged = MergedAnnotationResolver.find(DirectScanThenModuleFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"other.pkg"}, merged.value());
        }

        @Test
        @DisplayName("@UltiToolsModule then direct @EnableAutoRegister(cmdExecutor = false): the direct switch is used")
        void moduleThenDirectEnableAutoRegisterResolvesDirectSwitch() {
            EnableAutoRegister merged =
                    MergedAnnotationResolver.find(ModuleThenDirectSwitchFixture.class, EnableAutoRegister.class);

            assertNotNull(merged);
            assertFalse(merged.cmdExecutor(),
                    "a directly declared @EnableAutoRegister must not be hidden by @UltiToolsModule's meta default");
        }

        @Test
        @DisplayName("Direct @EnableAutoRegister(cmdExecutor = false) then @UltiToolsModule: the direct switch is used")
        void directEnableAutoRegisterThenModuleResolvesDirectSwitch() {
            EnableAutoRegister merged =
                    MergedAnnotationResolver.find(DirectSwitchThenModuleFixture.class, EnableAutoRegister.class);

            assertNotNull(merged);
            assertFalse(merged.cmdExecutor());
        }

        @Test
        @DisplayName("Both declared, @UltiToolsModule(scanBasePackages = a) first: only the direct @ComponentScan(b) applies")
        void bothDeclaredModuleFirstDirectComponentScanHidesModulePackages() {
            ComponentScan merged = MergedAnnotationResolver.find(BothScanModuleFirstFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"b"}, merged.value());
            assertArrayEquals(new String[0], merged.basePackages(),
                    "the meta-present scanBasePackages must be hidden, not combined (question 4, option A)");
        }

        @Test
        @DisplayName("Both declared, direct @ComponentScan(b) first: only the direct one applies")
        void bothDeclaredDirectFirstComponentScanHidesModulePackages() {
            ComponentScan merged = MergedAnnotationResolver.find(BothScanDirectFirstFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"b"}, merged.value());
            assertArrayEquals(new String[0], merged.basePackages());
        }

        @Test
        @DisplayName("Both declared, @UltiToolsModule(cmdExecutor = false) first: the direct default @EnableAutoRegister applies")
        void bothDeclaredModuleFirstDirectEnableAutoRegisterHidesModuleSwitch() {
            EnableAutoRegister merged =
                    MergedAnnotationResolver.find(BothSwitchModuleFirstFixture.class, EnableAutoRegister.class);

            assertNotNull(merged);
            assertTrue(merged.cmdExecutor(),
                    "the direct @EnableAutoRegister hides @UltiToolsModule's cmdExecutor = false (question 4, option A)");
        }

        @Test
        @DisplayName("Both declared, direct default @EnableAutoRegister first: it applies")
        void bothDeclaredDirectFirstDirectEnableAutoRegisterHidesModuleSwitch() {
            EnableAutoRegister merged =
                    MergedAnnotationResolver.find(BothSwitchDirectFirstFixture.class, EnableAutoRegister.class);

            assertNotNull(merged);
            assertTrue(merged.cmdExecutor());
        }

        @Test
        @DisplayName("Control: a class with only @UltiToolsModule(scanBasePackages = x) still resolves basePackages x")
        void moduleOnlyStillResolvesScanBasePackages() {
            ComponentScan merged = MergedAnnotationResolver.find(ModuleOnlyScanFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"x"}, merged.basePackages());
            assertArrayEquals(new String[0], merged.value());
        }

        @Test
        @DisplayName("A meta occurrence at distance 1 wins over one at distance 2 declared earlier")
        void nearerMetaOccurrenceWinsOverDeeperOneDeclaredFirst() {
            ComponentScan merged = MergedAnnotationResolver.find(FarDeclaredBeforeNearFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"near"}, merged.value());
        }

        @Test
        @DisplayName("At equal meta distance, declaration order breaks the tie")
        void equalDistanceOccurrencesResolveInDeclarationOrder() {
            ComponentScan merged = MergedAnnotationResolver.find(EqualDistanceFixture.class, ComponentScan.class);

            assertNotNull(merged);
            assertArrayEquals(new String[] {"near-second"}, merged.value());
        }

        @Test
        @DisplayName("The superclass is consulted only after the class's own annotation trees are exhausted")
        void ownMetaOccurrenceWinsOverSuperclassDirectOne() {
            ComponentScan own = MergedAnnotationResolver.find(DeepScanSubFixture.class, ComponentScan.class);
            ComponentScan inherited = MergedAnnotationResolver.find(UnannotatedSubFixture.class, ComponentScan.class);

            assertNotNull(own);
            assertArrayEquals(new String[] {"far"}, own.value(),
                    "the class's own distance-2 occurrence must win over the superclass's direct one");
            assertNotNull(inherited);
            assertArrayEquals(new String[] {"super"}, inherited.value());
        }
    }
}
