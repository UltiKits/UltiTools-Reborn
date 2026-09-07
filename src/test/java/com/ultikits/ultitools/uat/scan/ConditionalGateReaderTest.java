package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.uat.fixtures.ConditionalGateFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ConditionalGateReader} matches {@code ConditionalRegistrationEvaluator
 * .shouldRegister}'s real runtime discovery exactly (Phase 10, Codex review of PR #427): a
 * subclass of a {@code @ConditionalOnConfig} class that does not redeclare the annotation is
 * never itself gated, since {@code @ConditionalOnConfig} carries no {@code @Inherited}
 * meta-annotation and the runtime checks {@code Class#getAnnotation} directly, which does not
 * walk the superclass hierarchy for a non-inherited annotation.
 */
@DisplayName("ConditionalGateReader")
class ConditionalGateReaderTest {

    @Test
    @DisplayName("a class directly carrying @ConditionalOnConfig produces a gate and a conditional row")
    void directAnnotationProducesGateAndRow() {
        List<Class<?>> classes = Arrays.asList(ConditionalGateFixtures.GatedBase.class);
        ConditionalGateReader reader = new ConditionalGateReader();

        Map<String, Map<String, Object>> gates = reader.collectGates(classes);
        List<Map<String, Object>> rows = reader.scanConditionalRows("Fixture", classes);

        assertThat(gates).containsKey(ConditionalGateFixtures.GatedBase.class.getName());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("kind")).isEqualTo("conditional");
    }

    @Test
    @DisplayName("a subclass not redeclaring @ConditionalOnConfig produces no gate and no conditional row -- it is registered unconditionally at runtime")
    void subclassNotRedeclaringGateProducesNothing() {
        List<Class<?>> classes = Arrays.asList(ConditionalGateFixtures.SubclassNotRedeclaringGate.class);
        ConditionalGateReader reader = new ConditionalGateReader();

        Map<String, Map<String, Object>> gates = reader.collectGates(classes);
        List<Map<String, Object>> rows = reader.scanConditionalRows("Fixture", classes);

        assertThat(gates).isEmpty();
        assertThat(rows).isEmpty();
    }
}
