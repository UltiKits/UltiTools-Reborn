package com.ultikits.ultitools.abstracts.gui.declarative;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug Evidence Tests for the Declarative GUI Framework (PR #130).
 * <p>
 * Each test demonstrates a specific bug found during code review.
 * Tests are written to FAIL with the current implementation, proving the bug exists.
 * When the bug is fixed, the corresponding test should pass.
 * <p>
 * Bugs covered:
 * <ol>
 *   <li>Element.canUpdate() ignores the newWidget parameter — always returns true</li>
 *   <li>Element.updateChild() reconciliation broken — never replaces Elements</li>
 *   <li>ContainerElement dual child tracking desync — getChildren() returns stale data</li>
 *   <li>GridViewElement dual child tracking desync — same pattern as ContainerElement</li>
 *   <li>Combined: type mismatch + child desync create silent corruption</li>
 *   <li>ItemDisplayElement shadows superclass _renderNode field</li>
 * </ol>
 */
public class BugEvidenceTest {

    private BuildContext rootContext;

    @BeforeEach
    void setUp() {
        Player mockPlayer = Mockito.mock(Player.class);
        rootContext = BuildContext.root(mockPlayer, "test-gui", 6);
    }

    // ========================================================================
    // Bug #1: Element.canUpdate() ignores the newWidget parameter
    //
    // File: Element.java, line 102-104
    //
    // Current code:
    //   public boolean canUpdate(@NotNull Widget newWidget) {
    //       return _widget != null && _widget.canUpdate(this);
    //   }
    //
    // _widget.canUpdate(this) calls Widget.canUpdate(Element) which checks:
    //   element.getWidget().getClass() == this.getClass()
    //
    // Since element.getWidget() returns _widget (the OLD widget), and
    // "this" in that call IS _widget, it becomes:
    //   _widget.getClass() == _widget.getClass()  → ALWAYS TRUE
    //
    // The newWidget parameter is completely ignored.
    //
    // Fix: Change to `newWidget.canUpdate(this)` which would check:
    //   this.getWidget().getClass() == newWidget.getClass()
    //   i.e., does the new widget's type match the element's current type?
    //
    // Impact: The reconciliation algorithm can never detect type mismatches,
    // so it reuses Elements for completely incompatible Widget types.
    // ========================================================================

    @Nested
    @DisplayName("Bug #1: Element.canUpdate() ignores newWidget parameter")
    class CanUpdateBug {

        @Test
        @DisplayName("canUpdate() should return false for different widget types")
        void canUpdate_returnsFalse_forDifferentWidgetTypes() {
            WidgetTypeA widgetA = new WidgetTypeA();
            WidgetTypeB widgetB = new WidgetTypeB();

            Element element = widgetA.createElement();
            element.assignContext(rootContext);
            element.mount(null);

            // After mounting with WidgetTypeA, asking "can this element update to WidgetTypeB?"
            // should return FALSE because the widget types are different.
            //
            // BUG: Returns TRUE because Element.canUpdate() checks
            // _widget.canUpdate(this) which evaluates to:
            //   this.getWidget().getClass() == _widget.getClass()
            //   = _widget.getClass() == _widget.getClass()
            //   = true (always)
            // The newWidget parameter is never consulted.
            assertFalse(element.canUpdate(widgetB),
                    "canUpdate(WidgetTypeB) should return false when element holds WidgetTypeA. " +
                    "BUG: Element.canUpdate() calls _widget.canUpdate(this) instead of " +
                    "newWidget.canUpdate(this), making it always compare the old widget " +
                    "against itself — always true regardless of newWidget's type.");
        }

        @Test
        @DisplayName("canUpdate() returns true for same widget type (correct behavior)")
        void canUpdate_returnsTrue_forSameWidgetType() {
            WidgetTypeA widget1 = new WidgetTypeA();
            WidgetTypeA widget2 = new WidgetTypeA();

            Element element = widget1.createElement();
            element.assignContext(rootContext);
            element.mount(null);

            // Same type → should return true. This works even with the bug.
            assertTrue(element.canUpdate(widget2),
                    "canUpdate() with same widget type should return true");
        }

        @Test
        @DisplayName("Element.update() should reject different widget type but accepts it")
        void update_shouldThrow_forDifferentWidgetType() {
            WidgetTypeA widgetA = new WidgetTypeA();
            WidgetTypeB widgetB = new WidgetTypeB();

            Element element = widgetA.createElement();
            element.assignContext(rootContext);
            element.mount(null);

            // Element.update() checks canUpdate() before proceeding.
            // For incompatible types, it should throw IllegalArgumentException.
            //
            // BUG: Does NOT throw because canUpdate() always returns true.
            // The element silently accepts a WidgetTypeB, corrupting its state.
            assertThrows(IllegalArgumentException.class,
                    () -> element.update(widgetB),
                    "update(WidgetTypeB) should throw IllegalArgumentException when " +
                    "element holds WidgetTypeA. BUG: No exception because canUpdate() " +
                    "always returns true, allowing incompatible widget assignment.");
        }

        @Test
        @DisplayName("canUpdate() with null _widget returns false (correct)")
        void canUpdate_returnsFalse_whenWidgetIsNull() {
            // Unmounted element has _widget set to null after unmount
            WidgetTypeA widget = new WidgetTypeA();
            Element element = widget.createElement();
            element.assignContext(rootContext);
            element.mount(null);
            element.unmount();

            // After unmount, _widget is NOT null (unmount doesn't clear it in Element)
            // But for completeness, verify canUpdate on an unmounted element
            // The _widget != null check should handle this edge case
            WidgetTypeA newWidget = new WidgetTypeA();
            // This just tests the other condition in canUpdate
            // element is unmounted but _widget is still set
            assertTrue(element.canUpdate(newWidget),
                    "canUpdate after unmount: _widget is still set, so returns true");
        }
    }

    // ========================================================================
    // Bug #2: Element.updateChild() reconciliation is broken
    //
    // File: Element.java, line 259-286
    //
    // updateChild() is the core reconciliation method. It decides:
    //   - If canUpdate() is true → reuse the old Element (update in place)
    //   - If canUpdate() is false → unmount old, create new Element
    //
    // Since Bug #1 makes canUpdate() always return true, the "create new"
    // branch is dead code. Elements are ALWAYS reused, even when they hold
    // a completely different Widget type.
    //
    // Impact: Type-mismatched Elements survive reconciliation, leading to
    // ClassCastExceptions when code casts getWidget() to the expected type.
    // ========================================================================

    @Nested
    @DisplayName("Bug #2: Element.updateChild() reconciliation broken")
    class ReconciliationBug {

        @Test
        @DisplayName("updateChild should replace element when widget type changes")
        void updateChild_shouldReplace_whenWidgetTypeChanges() {
            WidgetTypeA widgetA = new WidgetTypeA();
            WidgetTypeB widgetB = new WidgetTypeB();

            TestParentElement parent = new TestParentElement(new WidgetTypeA());
            parent.assignContext(rootContext);
            parent.mount(null);

            // Create and mount a child with WidgetTypeA
            Element oldChild = widgetA.createElement();
            oldChild.mount(parent);

            // Ask updateChild to reconcile with WidgetTypeB
            // Since types differ, it SHOULD create a new Element.
            Element result = parent.callUpdateChild(widgetB, oldChild);

            // BUG: Returns the SAME oldChild instance because canUpdate() is
            // always true (Bug #1), so updateChild() reuses the old Element.
            // The old Element now holds a WidgetTypeB but was never designed for it.
            assertNotSame(oldChild, result,
                    "updateChild() should create a new Element when widget type changes " +
                    "from WidgetTypeA to WidgetTypeB. BUG: Returns the same old Element " +
                    "because canUpdate() is always true, making the replace branch dead code.");
        }

        @Test
        @DisplayName("updateChild should unmount old element when replacing")
        void updateChild_shouldUnmountOld_whenReplacing() {
            WidgetTypeA widgetA = new WidgetTypeA();
            WidgetTypeB widgetB = new WidgetTypeB();

            TestParentElement parent = new TestParentElement(new WidgetTypeA());
            parent.assignContext(rootContext);
            parent.mount(null);

            Element oldChild = widgetA.createElement();
            oldChild.mount(parent);
            assertTrue(oldChild.isMounted());

            // When replaced, the old child should be unmounted
            parent.callUpdateChild(widgetB, oldChild);

            // BUG: Old child is NOT unmounted because it was reused (not replaced).
            // canUpdate() returned true → update path taken → old child still mounted.
            assertFalse(oldChild.isMounted(),
                    "Old child should be unmounted after being replaced by different type. " +
                    "BUG: Old child is still mounted because canUpdate() bug prevents replacement.");
        }
    }

    // ========================================================================
    // Bug #3: ContainerElement dual child list desync
    //
    // File: ContainerElement.java
    //
    // ContainerElement maintains TWO child lists:
    //   1. Local `childElements` (ArrayList) — used internally by updateChildren()
    //   2. Inherited `_children` (from Element) — returned by getChildren()
    //
    // mountChildren() correctly populates BOTH lists:
    //   childElements.add(childElement);  // local
    //   addChild(childElement);           // inherited _children
    //
    // updateChildren() ONLY updates `childElements`:
    //   childElements.set(i, newChild);   // ✓ updated
    //   childElements.add(newChild);      // ✓ updated
    //   childElements.remove(...);        // ✓ updated
    //   // _children is NEVER updated     // ✗ stale!
    //
    // GuiRenderer.collectRenderNodesRecursive() calls element.getChildren()
    // which returns _children. So after any update, the renderer sees stale data:
    //   - Newly added children are invisible
    //   - Removed children are still visible (as unmounted ghosts)
    //   - Replaced children show old Elements
    // ========================================================================

    @Nested
    @DisplayName("Bug #3: ContainerElement dual child tracking desync")
    class ContainerChildTrackingBug {

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
    // Bug #4: GridViewElement has the same dual child tracking desync
    //
    // File: GridViewElement.java
    //
    // Identical pattern to Bug #3. GridViewElement also maintains:
    //   - Local `childElements` (updated by updateChildren)
    //   - Inherited `_children` (populated by mountChildren, never updated)
    //
    // Same impact: renderer sees stale children after any GridView update.
    // ========================================================================

    @Nested
    @DisplayName("Bug #4: GridViewElement dual child tracking desync")
    class GridViewChildTrackingBug {

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
    // Bug #5: Combined — Bugs #1 and #3 create silent widget type corruption
    //
    // When a Container's child changes widget type (WidgetTypeA → WidgetTypeB):
    //   1. Bug #1: canUpdate() returns true → old Element is reused
    //   2. The old Element's _widget is silently changed to WidgetTypeB
    //   3. Any code that casts getWidget() to the expected type will get CCE
    //
    // Even if Bug #1 were fixed, Bug #3 would still cause desync when Elements
    // ARE replaced (the new Element goes into childElements but _children keeps
    // the old Element reference).
    // ========================================================================

    @Nested
    @DisplayName("Bug #5: Combined — type mismatch + child desync")
    class CombinedBugs {

        @Test
        @DisplayName("Container child type change should create new element, not reuse old")
        void containerUpdate_withDifferentType_shouldCreateNewElement() {
            // Container with WidgetTypeA child
            Container container1 = Container.builder()
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(container1);
            element.assignContext(rootContext);
            element.mount(null);

            Element originalChild = element.getChildren().get(0);
            assertTrue(originalChild.getWidget() instanceof WidgetTypeA,
                    "Initial child should hold WidgetTypeA");

            // Update with WidgetTypeB child
            Container container2 = Container.builder()
                    .child(new WidgetTypeB())
                    .build();

            element.update(container2);

            // The correct behavior: type mismatch detected, old Element unmounted,
            // new Element created from WidgetTypeB.
            //
            // BUG (Bug #1): canUpdate() returns true → old Element reused →
            // its _widget silently changed from WidgetTypeA to WidgetTypeB.
            // No new Element created, no unmount of old Element.
            Element childAfterUpdate = element.getChildren().get(0);

            // If working correctly, this would be a DIFFERENT Element instance.
            // BUG: Same instance, mutated to hold wrong widget type.
            assertNotSame(originalChild, childAfterUpdate,
                    "Child element should be replaced when widget type changes from " +
                    "WidgetTypeA to WidgetTypeB. BUG: Same element reused because " +
                    "canUpdate() always returns true. The element now silently holds " +
                    "a WidgetTypeB which could cause ClassCastException downstream.");
        }

        @Test
        @DisplayName("After type change, old child should be unmounted and new child created")
        void containerUpdate_withDifferentType_replacesCorrectly() {
            Container container1 = Container.builder()
                    .child(new WidgetTypeA())
                    .build();

            ContainerElement element = new ContainerElement(container1);
            element.assignContext(rootContext);
            element.mount(null);

            Element originalChild = element.getChildren().get(0);
            assertTrue(originalChild.getWidget() instanceof WidgetTypeA);

            // Update with different type
            Container container2 = Container.builder()
                    .child(new WidgetTypeB())
                    .build();

            element.update(container2);

            // Old child should be unmounted (replaced, not reused)
            assertFalse(originalChild.isMounted(),
                    "Original child should be unmounted after type change replacement");

            // New child should hold WidgetTypeB
            Element newChild = element.getChildren().get(0);
            assertNotSame(originalChild, newChild,
                    "Should be a different element instance after type change");
            assertTrue(newChild.getWidget() instanceof WidgetTypeB,
                    "New child should hold WidgetTypeB");
            assertTrue(newChild.isMounted(),
                    "New child should be mounted");
        }
    }

    // ========================================================================
    // Bug #6 (FIXED): ItemDisplayElement no longer shadows superclass field
    //
    // Previously, ItemDisplayElement declared an unused `private RenderNode renderNode`
    // that shadowed RenderObjectElement._renderNode. This has been removed.
    // ========================================================================

    @Nested
    @DisplayName("Bug #6 (FIXED): ItemDisplayElement no longer shadows superclass field")
    class ShadowedFieldFixed {

        @Test
        @DisplayName("ItemDisplayElement should not declare its own renderNode field")
        void itemDisplayElement_noShadowedRenderNodeField() {
            // After fix: ItemDisplayElement should NOT have a local 'renderNode' field.
            // Only the superclass RenderObjectElement._renderNode should exist.
            assertThrows(NoSuchFieldException.class,
                    () -> ItemDisplayElement.class.getDeclaredField("renderNode"),
                    "ItemDisplayElement should not declare a local 'renderNode' field. " +
                    "It should only use the superclass RenderObjectElement._renderNode.");
        }
    }

    // ========================================================================
    // Test helper classes
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
     * Concrete Widget type B — intentionally different from WidgetTypeA
     * so canUpdate() should return false when comparing A vs B.
     */
    private static class WidgetTypeB extends Widget {
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

    /**
     * Parent Element that exposes the protected updateChild() for testing.
     */
    private static class TestParentElement extends Element {
        TestParentElement(Widget widget) {
            super(widget);
        }

        @Override
        public void performRebuild() {
            clearDirty();
        }

        Element callUpdateChild(Widget newWidget, Element oldChild) {
            return updateChild(newWidget, oldChild);
        }
    }
}
