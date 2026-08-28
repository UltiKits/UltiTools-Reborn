package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.AliasFor;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.annotations.I18n;
import com.ultikits.ultitools.annotations.UltiToolsModule;
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
}
