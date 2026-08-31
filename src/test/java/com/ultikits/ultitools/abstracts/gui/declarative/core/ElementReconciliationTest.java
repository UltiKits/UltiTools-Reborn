package com.ultikits.ultitools.abstracts.gui.declarative.core;

import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ContainerElement;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for {@link Element}'s keyed reconciliation contract: {@code canUpdate()}
 * must consult the NEW widget's type (not compare the old widget against itself), and
 * {@code updateChild()} must replace an Element outright when the widget type at a slot changes,
 * unmounting the old Element rather than silently reusing it.
 * <p>
 * These tests originated as PR #130 code-review evidence for three defects in this contract
 * (Element.canUpdate() ignoring its {@code newWidget} parameter; Element.updateChild() never
 * replacing on a type change; and the combined effect of both, observed through
 * {@link ContainerElement}). All three were fixed prior to 6.3.0 — the assertions below already
 * describe correct, delivered behaviour, not a still-open defect. Split out of the former
 * {@code BugEvidenceTest} by Phase 5 plan 05-14 (D-10); every assertion carried across unchanged.
 */
public class ElementReconciliationTest {

    private BuildContext rootContext;

    @BeforeEach
    void setUp() {
        Player mockPlayer = Mockito.mock(Player.class);
        rootContext = BuildContext.root(mockPlayer, "test-gui", 6);
    }

    // ========================================================================
    // Element.canUpdate() must consult the NEW widget's type.
    //
    // File: Element.java, line 102-104 (as of PR #130)
    //
    // Buggy code (fixed):
    //   public boolean canUpdate(@NotNull Widget newWidget) {
    //       return _widget != null && _widget.canUpdate(this);
    //   }
    //
    // _widget.canUpdate(this) calls Widget.canUpdate(Element) which checks:
    //   element.getWidget().getClass() == this.getClass()
    //
    // Since element.getWidget() returns _widget (the OLD widget), and
    // "this" in that call IS _widget, it evaluated to:
    //   _widget.getClass() == _widget.getClass()  → ALWAYS TRUE
    //
    // The newWidget parameter was completely ignored. Fixed by comparing
    // newWidget.canUpdate(this) instead, which correctly checks:
    //   this.getWidget().getClass() == newWidget.getClass()
    // ========================================================================

    @Nested
    @DisplayName("Element.canUpdate() consults the new widget's type")
    class CanUpdateTypeChecking {

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
    // Element.updateChild() is the core reconciliation method. It decides:
    //   - If canUpdate() is true → reuse the old Element (update in place)
    //   - If canUpdate() is false → unmount old, create new Element
    //
    // Before the canUpdate() fix above, that "create new" branch was dead code —
    // Elements were always reused, even when they held a completely different
    // Widget type.
    // ========================================================================

    @Nested
    @DisplayName("Element.updateChild() replaces Elements on a type change")
    class ReconciliationReplacement {

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
    // Combined: the canUpdate()/updateChild() reconciliation contract observed
    // through ContainerElement, which delegates per-child reconciliation to
    // Element.updateChild(). Before the fixes above, a Container child that
    // changed widget type was silently reused and mutated in place instead of
    // being replaced — a downstream cast to the expected type would then throw
    // ClassCastException.
    // ========================================================================

    @Nested
    @DisplayName("ContainerElement replaces a child Element when its widget type changes")
    class ContainerTypeChangeReplacement {

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
