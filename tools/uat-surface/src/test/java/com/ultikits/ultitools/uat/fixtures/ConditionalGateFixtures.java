package com.ultikits.ultitools.uat.fixtures;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;

/**
 * Fixtures for {@code ConditionalGateReaderTest} (Phase 10, Codex review of PR #427):
 * {@link #GatedBase} carries a real {@code @ConditionalOnConfig} gate, and
 * {@link #SubclassNotRedeclaringGate} extends it without redeclaring the annotation --
 * {@code ConditionalRegistrationEvaluator.shouldRegister} calls {@code clazz.getAnnotation}
 * directly, and {@code @ConditionalOnConfig} is not {@code @Inherited}, so the subclass is
 * actually registered UNCONDITIONALLY at runtime regardless of the superclass's gate.
 *
 * @since 6.3.0
 */
// This class is a namespace for the nested fixtures below, not a utility class with static
// helpers of its own -- the private constructor exists only to block a pointless
// `new ConditionalGateFixtures()`.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class ConditionalGateFixtures {

    private ConditionalGateFixtures() {
    }

    @ConditionalOnConfig(value = "config/config.yml", path = "enableFeature")
    public static class GatedBase {
    }

    public static class SubclassNotRedeclaringGate extends GatedBase {
    }
}
