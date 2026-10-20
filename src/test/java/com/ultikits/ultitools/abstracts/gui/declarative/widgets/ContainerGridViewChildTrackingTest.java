package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for {@link ContainerElement} and {@link GridViewElement}'s child-tracking
 * contract: {@code getChildren()} (inherited from {@link Element}, backed by {@code _children})
 * must reflect additions, removals and unmounted-child cleanup performed by
 * {@code updateChildren()} across repeated updates.
 * <p>
 * These tests originated as PR #130 code-review evidence for a dual child-list desync in both
 * classes — each maintained a local {@code childElements} list, correctly updated by
 * {@code updateChildren()}, alongside the inherited {@code _children} list that
 * {@code GuiRenderer.collectRenderNodesRecursive()} actually reads via {@code getChildren()},
 * which was never kept in sync. Phase 5 plan 05-13 converged both classes onto one shared,
 * keyed {@code Element.updateChildren(...)} implementation, closing this desync structurally —
 * there is no longer a second list to fall out of sync with the first. Split out of the former
 * {@code BugEvidenceTest} by Phase 5 plan 05-14 (D-10); every assertion carried across unchanged.
 */
public class ContainerGridViewChildTrackingTest {

    private BuildContext rootContext;

    @BeforeEach
    void setUp() {
        Player mockPlayer = Mockito.mock(Player.class);
        rootContext = BuildContext.root(mockPlayer, "test-gui", 6);
    }

    // ========================================================================
    // ContainerElement previously maintained TWO child lists:
    //   1. Local `childElements` (ArrayList) — used internally by updateChildren()
    //   2. Inherited `_children` (from Element) — returned by getChildren()
    //
    // mountChildren() correctly populated BOTH lists; updateChildren() only
    // updated `childElements`, leaving `_children` — and therefore getChildren(),
    // and therefore GuiRenderer.collectRenderNodesRecursive() — stale after any
    // update. Fixed by Phase 5 plan 05-13's shared Element.updateChildren(...).
    // ========================================================================

    @Nested
    @DisplayName("ContainerElement.getChildren() tracks additions, removals and unmounts")
    class ContainerChildTracking {

        @Test
        @DisplayName("getChildren() should include newly added children after update")
        void getChildren_includesNewChildren_afterUpdate() {
            // Mount container with 2 children
            Container container1 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(container1);
            element.assignContext(rootContext);
            element.mount(null);

            // Verify initial state — both lists are in sync after mount
            assertEquals(2, element.getChildren().size(),
                    "Initial mount: getChildren() should have 2 children");

            // Update to 3 children (add one)
            Container container2 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())  // newly added
                    .build();

            element.update(container2);

            // BUG: getChildren() returns 2 instead of 3.
            // updateChildren() adds the new child to local `childElements` but
            // does NOT call addChild() to update inherited `_children`.
            // The renderer uses getChildren() → sees only 2 → new child invisible.
            assertEquals(3, element.getChildren().size(),
                    "After update: getChildren() should return 3 (2 existing + 1 new). " +
                    "BUG: Returns 2 because updateChildren() only adds to local childElements, " +
                    "not to inherited _children. The 3rd child is invisible to GuiRenderer.");
        }

        @Test
        @DisplayName("getChildren() should exclude removed children after update")
        void getChildren_excludesRemovedChildren_afterUpdate() {
            // Mount container with 3 children
            Container container1 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(container1);
            element.assignContext(rootContext);
            element.mount(null);

            assertEquals(3, element.getChildren().size(),
                    "Initial mount: getChildren() should have 3 children");

            // Update to 2 children (remove one)
            Container container2 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            element.update(container2);

            // BUG: getChildren() returns 3 instead of 2.
            // updateChildren() removes from local `childElements` and calls
            // unmount() on the removed child, but does NOT remove from `_children`.
            // The renderer sees 3 children including the unmounted ghost.
            assertEquals(2, element.getChildren().size(),
                    "After update: getChildren() should return 2 (1 removed). " +
                    "BUG: Returns 3 because updateChildren() only removes from local " +
                    "childElements. The removed (unmounted) child still haunts _children.");
        }

        @Test
        @DisplayName("getChildren() should not contain unmounted elements")
        void getChildren_doesNotContainUnmountedElements() {
            // Mount container with 3 children
            Container container1 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(container1);
            element.assignContext(rootContext);
            element.mount(null);

            // Capture the 3rd child BEFORE removal
            Element thirdChild = element.getChildren().get(2);
            assertTrue(thirdChild.isMounted(), "3rd child should be mounted initially");

            // Remove the 3rd child by updating to 2 children
            Container container2 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            element.update(container2);

            // The 3rd child was correctly unmounted by updateChildren()
            assertFalse(thirdChild.isMounted(),
                    "Removed child should be unmounted");

            // BUG: The unmounted child is STILL in getChildren()
            // because _children was never cleaned up during updateChildren().
            // The renderer would try to collect render nodes from an unmounted element.
            boolean containsUnmounted = element.getChildren().stream()
                    .anyMatch(child -> !child.isMounted());

            assertFalse(containsUnmounted,
                    "getChildren() should not contain unmounted elements. " +
                    "BUG: Contains stale unmounted element because _children is never " +
                    "cleaned up in updateChildren(). The renderer would encounter " +
                    "an unmounted element during render node collection.");
        }

        @Test
        @DisplayName("Multiple updates accumulate desync progressively")
        void multipleUpdates_accumulateDesync() {
            // Start with 2 children
            Container initial = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(initial);
            element.assignContext(rootContext);
            element.mount(null);

            assertEquals(2, element.getChildren().size());

            // Update 1: add 1 child (2 → 3)
            Container update1 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();
            element.update(update1);

            // Update 2: add another child (3 → 4)
            Container update2 = Container.builder()
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();
            element.update(update2);

            // After 2 updates adding children, getChildren() should return 4.
            // BUG: Still returns 2 (only the initial mount count).
            // Each update adds to childElements but NOT _children.
            // The desync grows with each update.
            assertEquals(4, element.getChildren().size(),
                    "After 2 updates: getChildren() should return 4. " +
                    "BUG: Returns 2 (initial count). Desync accumulates with each update.");
        }
    }

    // ========================================================================
    // GridViewElement had the identical dual child-list pattern as
    // ContainerElement above — a local `childElements` (updated by
    // updateChildren) alongside an inherited `_children` (populated by
    // mountChildren, never updated). Same fix, same 05-13 convergence.
    // ========================================================================

    @Nested
    @DisplayName("GridViewElement.getChildren() tracks additions and removals")
    class GridViewChildTracking {

        @Test
        @DisplayName("getChildren() should include new children after GridView update")
        void getChildren_includesNewChildren_afterGridViewUpdate() {
            GridView<Void> gridView1 = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            GridViewElement element = new GridViewElement(gridView1);
            element.assignContext(rootContext);
            element.mount(null);

            assertEquals(2, element.getChildren().size(),
                    "Initial GridView should have 2 children");

            // Add a child
            GridView<Void> gridView2 = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            element.update(gridView2);

            // BUG: Same as ContainerElement — returns 2 instead of 3
            assertEquals(3, element.getChildren().size(),
                    "GridView getChildren() should return 3 after adding a child. " +
                    "BUG: Same dual tracking desync as ContainerElement — " +
                    "updateChildren() only updates local childElements, not _children.");
        }

        @Test
        @DisplayName("getChildren() should exclude removed children after GridView update")
        void getChildren_excludesRemovedChildren_afterGridViewUpdate() {
            GridView<Void> gridView1 = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            GridViewElement element = new GridViewElement(gridView1);
            element.assignContext(rootContext);
            element.mount(null);

            assertEquals(3, element.getChildren().size());

            // Remove a child
            GridView<Void> gridView2 = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new WidgetTypeA())
                    .child(new WidgetTypeA())
                    .build();

            element.update(gridView2);

            // BUG: Returns 3 instead of 2
            assertEquals(2, element.getChildren().size(),
                    "GridView getChildren() should return 2 after removing a child. " +
                    "BUG: Removed child still in _children.");
        }
    }

    // ========================================================================
    // Test helper class
    // ========================================================================

    /**
     * Concrete Widget type A — for testing type identity checks.
     */
    private static class WidgetTypeA extends Widget {
        @Override
        public Element createElement() {
            return new ConcreteElement(this);
        }
    }

    /**
     * Minimal concrete Element implementation for testing.
     */
    private static class ConcreteElement extends Element {
        ConcreteElement(Widget widget) {
            super(widget);
        }

        @Override
        public void performRebuild() {
            clearDirty();
        }
    }
}
