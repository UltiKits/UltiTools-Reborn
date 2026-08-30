package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1 (05-13, D-09 item 5): keyed child reconciliation, driven identically against
 * {@link ContainerElement} and {@link GridViewElement}.
 * <p>
 * Both classes are literal twins at HEAD -- the same {@code childElements} list, the same
 * index-paired {@code updateChildren()} via {@code Math.min(size)}, neither reading
 * {@link SlotKey}. Every test method here runs against BOTH classes via
 * {@link #elementFactories()} so the convergence itself is what is being asserted, not two
 * independently-authored suites that happen to agree.
 *
 * @author UltiTools Team
 * @since 6.3.0
 */
public class GridViewElementTest {

    private BuildContext rootContext;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Player mockPlayer = Mockito.mock(Player.class);
        rootContext = BuildContext.root(mockPlayer, "test-gui", 6);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // Widget factories -- same test bodies drive both twins (plan Test 7)
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface WidgetFactory {
        @org.jetbrains.annotations.NotNull
        Widget build(@org.jetbrains.annotations.NotNull List<Widget> children);
    }

    private static Widget buildContainer(List<Widget> children) {
        Container.Builder builder = Container.builder();
        children.forEach(builder::child);
        return builder.build();
    }

    private static Widget buildGridView(List<Widget> children) {
        GridView.Builder<Void> builder = GridView.<Void>builder().startSlot(0).columns(9);
        children.forEach(builder::child);
        return builder.build();
    }

    static Stream<WidgetFactory> elementFactories() {
        return Stream.of(GridViewElementTest::buildContainer, GridViewElementTest::buildGridView);
    }

    private Element mount(WidgetFactory factory, Widget... children) {
        Widget root = factory.build(Arrays.asList(children));
        Element element = root.createElement();
        element.assignContext(rootContext);
        element.mount(null);
        return element;
    }

    // ------------------------------------------------------------------
    // Test double widgets
    // ------------------------------------------------------------------

    /** A stable, keyable, otherwise-inert leaf widget of type A. */
    private static class LeafWidgetA extends Widget {
        LeafWidgetA() {
            super();
        }

        LeafWidgetA(SlotKey key) {
            super(key);
        }

        @Override
        public Element createElement() {
            return new LeafElement(this);
        }
    }

    /** A different runtime type from {@link LeafWidgetA}, for the type-change test. */
    private static class LeafWidgetB extends Widget {
        LeafWidgetB(SlotKey key) {
            super(key);
        }

        @Override
        public Element createElement() {
            return new LeafElement(this);
        }
    }

    private static class LeafElement extends Element {
        LeafElement(Widget widget) {
            super(widget);
        }

        @Override
        public void performRebuild() {
            clearDirty();
        }
    }

    /** A keyed StatefulWidget whose State carries a mutable counter, for state-follows-key. */
    private static class CounterWidget extends StatefulWidget {
        CounterWidget(SlotKey key) {
            super(key);
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new CounterState();
        }
    }

    private static class CounterState extends State<CounterWidget> {
        int count = 0;

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder().text("n=" + count).build();
        }
    }

    private static CounterState stateOf(Element element) {
        return (CounterState) ((StatefulElement) element).getState();
    }

    // ------------------------------------------------------------------
    // Test 1: reordering a keyed list of stateful children carries each
    // child's State with its key, not its index.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void reorderingKeyedListPreservesStatePerKey(WidgetFactory factory) {
        CounterWidget a = new CounterWidget(SlotKey.of("a"));
        CounterWidget b = new CounterWidget(SlotKey.of("b"));

        Element element = mount(factory, a, b);
        Element aElement = element.getChildren().get(0);
        Element bElement = element.getChildren().get(1);

        stateOf(aElement).count = 42;
        stateOf(bElement).count = 7;

        // Reorder: b first, a second -- fresh widget instances, same keys.
        element.update(factory.build(Arrays.asList(
                new CounterWidget(SlotKey.of("b")),
                new CounterWidget(SlotKey.of("a")))));

        List<Element> after = element.getChildren();
        assertEquals(2, after.size());

        // Element identity (and therefore State) must follow the key, not the index.
        assertSame(bElement, after.get(0), "the child keyed \"b\" should still be the same Element, now first");
        assertSame(aElement, after.get(1), "the child keyed \"a\" should still be the same Element, now second");
        assertEquals(7, stateOf(after.get(0)).count, "b's state must follow its key across the reorder");
        assertEquals(42, stateOf(after.get(1)).count, "a's state must follow its key across the reorder");
    }

    // ------------------------------------------------------------------
    // Test 2: an unkeyed list still pairs by index (no behavior change
    // for existing callers that never supply a SlotKey).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void unkeyedListStillPairsByIndex(WidgetFactory factory) {
        Element element = mount(factory, new LeafWidgetA(), new LeafWidgetA());
        Element first = element.getChildren().get(0);
        Element second = element.getChildren().get(1);

        element.update(factory.build(Arrays.asList(new LeafWidgetA(), new LeafWidgetA())));

        List<Element> after = element.getChildren();
        assertEquals(2, after.size());
        assertSame(first, after.get(0), "unkeyed pairing must remain positional (index 0)");
        assertSame(second, after.get(1), "unkeyed pairing must remain positional (index 1)");
    }

    // ------------------------------------------------------------------
    // Test 3: a removed keyed child is unmounted, and its former
    // neighbours keep their state.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void removedKeyedChildIsUnmountedNeighboursKeepState(WidgetFactory factory) {
        CounterWidget a = new CounterWidget(SlotKey.of("a"));
        CounterWidget b = new CounterWidget(SlotKey.of("b"));
        CounterWidget c = new CounterWidget(SlotKey.of("c"));

        Element element = mount(factory, a, b, c);
        Element aElement = element.getChildren().get(0);
        Element bElement = element.getChildren().get(1);
        Element cElement = element.getChildren().get(2);
        stateOf(aElement).count = 1;
        stateOf(cElement).count = 3;

        assertTrue(bElement.isMounted());

        // Remove "b".
        element.update(factory.build(Arrays.asList(
                new CounterWidget(SlotKey.of("a")),
                new CounterWidget(SlotKey.of("c")))));

        assertFalse(bElement.isMounted(), "the removed keyed child must be unmounted");

        List<Element> after = element.getChildren();
        assertEquals(2, after.size());
        assertSame(aElement, after.get(0));
        assertSame(cElement, after.get(1));
        assertEquals(1, stateOf(after.get(0)).count, "a's state must survive its neighbour's removal");
        assertEquals(3, stateOf(after.get(1)).count, "c's state must survive its neighbour's removal");
    }

    // ------------------------------------------------------------------
    // Test 4: a keyed child added to the middle of the list is mounted
    // fresh, and existing children keep their state.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void addedKeyedChildInMiddleIsMountedFreshExistingKeepState(WidgetFactory factory) {
        CounterWidget a = new CounterWidget(SlotKey.of("a"));
        CounterWidget c = new CounterWidget(SlotKey.of("c"));

        Element element = mount(factory, a, c);
        Element aElement = element.getChildren().get(0);
        Element cElement = element.getChildren().get(1);
        stateOf(aElement).count = 10;
        stateOf(cElement).count = 30;

        // Insert "b" in the middle.
        element.update(factory.build(Arrays.asList(
                new CounterWidget(SlotKey.of("a")),
                new CounterWidget(SlotKey.of("b")),
                new CounterWidget(SlotKey.of("c")))));

        List<Element> after = element.getChildren();
        assertEquals(3, after.size());
        assertSame(aElement, after.get(0));
        assertSame(cElement, after.get(2));
        assertNotSame(aElement, after.get(1));
        assertNotSame(cElement, after.get(1));
        assertTrue(after.get(1).isMounted(), "the newly-added keyed child must be mounted fresh");
        assertEquals(10, stateOf(after.get(0)).count);
        assertEquals(30, stateOf(after.get(2)).count);
        assertEquals(0, stateOf(after.get(1)).count, "a freshly-mounted child starts with fresh state");
    }

    // ------------------------------------------------------------------
    // Test 5: a widget-type change at the same key remounts rather than
    // throwing -- canUpdate() returning false must produce a fresh
    // Element, not an exception.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void widgetTypeChangeAtSameKeyRemounts(WidgetFactory factory) {
        SlotKey key = SlotKey.of("shape-shifter");
        Element element = mount(factory, new LeafWidgetA(key));
        Element original = element.getChildren().get(0);
        assertTrue(original instanceof LeafElement);
        assertTrue(original.getWidget() instanceof LeafWidgetA);

        assertDoesNotThrow(() -> element.update(factory.build(
                java.util.Collections.singletonList(new LeafWidgetB(key)))));

        List<Element> after = element.getChildren();
        assertEquals(1, after.size());
        assertNotSame(original, after.get(0), "a widget-type change at the same key must remount");
        assertTrue(after.get(0).getWidget() instanceof LeafWidgetB);
        assertFalse(original.isMounted(), "the replaced element must be unmounted");
    }

    // ------------------------------------------------------------------
    // Test 6: a list that shrinks then grows across three builds leaves
    // no orphaned mounted elements.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("elementFactories")
    void shrinkThenGrowLeavesNoOrphanedElements(WidgetFactory factory) {
        Element element = mount(factory,
                new LeafWidgetA(SlotKey.of("1")),
                new LeafWidgetA(SlotKey.of("2")),
                new LeafWidgetA(SlotKey.of("3")));
        List<Element> initial = new ArrayList<>(element.getChildren());
        assertEquals(3, initial.size());

        // Shrink to just "2".
        element.update(factory.build(java.util.Collections.singletonList(new LeafWidgetA(SlotKey.of("2")))));
        List<Element> shrunk = new ArrayList<>(element.getChildren());
        assertEquals(1, shrunk.size());
        assertFalse(initial.get(0).isMounted(), "\"1\" must be unmounted after the shrink");
        assertFalse(initial.get(2).isMounted(), "\"3\" must be unmounted after the shrink");
        assertTrue(shrunk.get(0).isMounted());

        // Grow back to "2", "4", "5".
        element.update(factory.build(Arrays.asList(
                new LeafWidgetA(SlotKey.of("2")),
                new LeafWidgetA(SlotKey.of("4")),
                new LeafWidgetA(SlotKey.of("5")))));
        List<Element> grown = new ArrayList<>(element.getChildren());
        assertEquals(3, grown.size());
        assertSame(shrunk.get(0), grown.get(0), "\"2\" must survive both transitions as the same Element");
        for (Element e : grown) {
            assertTrue(e.isMounted(), "every currently-listed child must be mounted");
        }
        // No orphans: nothing outside the currently-returned list should still be mounted.
        assertFalse(initial.get(0).isMounted());
        assertFalse(initial.get(2).isMounted());
    }

    // ------------------------------------------------------------------
    // BugEvidenceTest baseline sentinel: re-run its two Bug groups here as a smoke check
    // that the shared implementation didn't regress ContainerElement's already-correct
    // getChildren() behaviour. (Full BugEvidenceTest itself is run separately per plan
    // <verify>; this is not a substitute.)
    // ------------------------------------------------------------------

    @Test
    void containerGetChildrenStaysAccurateAfterUpdate() {
        Container c1 = Container.builder().child(new LeafWidgetA()).child(new LeafWidgetA()).build();
        ContainerElement element = new ContainerElement(c1);
        element.assignContext(rootContext);
        element.mount(null);
        assertEquals(2, element.getChildren().size());

        Container c2 = Container.builder()
                .child(new LeafWidgetA()).child(new LeafWidgetA()).child(new LeafWidgetA()).build();
        element.update(c2);
        assertEquals(3, element.getChildren().size());
    }
}
