package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression guard for {@link ItemDisplayElement}'s render-node field: it must not declare its
 * own {@code renderNode} field shadowing {@code RenderObjectElement._renderNode}.
 * <p>
 * This test originated as PR #130 code-review evidence for a field-shadowing defect — an unused
 * {@code private RenderNode renderNode} field on {@code ItemDisplayElement} shadowed the
 * superclass's {@code _renderNode}, the field the rest of the reconciliation pipeline actually
 * reads. The shadowing field was removed prior to 6.3.0; this test is a standing regression guard
 * against its reintroduction, not evidence of an open defect. Split out of the former
 * {@code BugEvidenceTest} by Phase 5 plan 05-14 (D-10); the assertion is carried across
 * unchanged.
 */
public class ItemDisplayFieldShadowingTest {

    @Test
    @DisplayName("ItemDisplayElement should not declare its own renderNode field")
    void itemDisplayElement_noShadowedRenderNodeField() {
        // ItemDisplayElement must NOT have a local 'renderNode' field.
        // Only the superclass RenderObjectElement._renderNode should exist.
        assertThrows(NoSuchFieldException.class,
                () -> ItemDisplayElement.class.getDeclaredField("renderNode"),
                "ItemDisplayElement should not declare a local 'renderNode' field. " +
                "It should only use the superclass RenderObjectElement._renderNode.");
    }
}
