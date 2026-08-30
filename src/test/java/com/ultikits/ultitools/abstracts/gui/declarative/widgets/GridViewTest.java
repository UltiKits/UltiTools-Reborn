package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import mc.obliviate.inventory.Gui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GridView tests.
 * <p>
 * Task 2 (05-13, WIRE-03 / D-11): {@code GridView} positions ANY widget type by writing
 * parent data at render time -- {@link GridViewElement#performRebuild()} computes each
 * child's slot and writes it onto the {@link RenderNode}(s) that child's subtree produced,
 * instead of the pre-plan {@code GridView.Builder.items()} special-casing {@code ItemDisplay}
 * only and hand-copying six fields at build time.
 * <p>
 * Task 3 (D-09, Container.background and its no-op peers) also lives in this file per the
 * plan's own file list.
 *
 * @author UltiTools Team
 * @since 6.3.0
 */
public class GridViewTest {

    private ServerMock server;
    private Plugin mockPlugin;
    private Player player;
    private BuildContext rootContext;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        rootContext = BuildContext.root(player, "test-gui", 6);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private GridViewElement mountGridView(GridView<?> gridView) {
        GridViewElement element = new GridViewElement(gridView);
        element.assignContext(rootContext);
        element.mount(null);
        return element;
    }

    private static void collectLeaves(Element element, List<RenderNode> out) {
        if (element instanceof RenderObjectElement) {
            out.add(((RenderObjectElement) element).getRenderNode());
        }
        for (Element child : element.getChildren()) {
            collectLeaves(child, out);
        }
    }

    private static List<RenderNode> leavesOf(Element element) {
        List<RenderNode> out = new ArrayList<>();
        collectLeaves(element, out);
        return out;
    }

    private static RenderNode soleLeafOf(Element element) {
        List<RenderNode> leaves = leavesOf(element);
        assertEquals(1, leaves.size(), "expected exactly one RenderNode leaf under " + element);
        return leaves.get(0);
    }

    /** Captures java.util.logging records emitted by GridViewElement's logger during a test. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private CapturingHandler attachCapturingHandler() {
        Logger logger = Logger.getLogger(GridViewElement.class.getName());
        CapturingHandler handler = new CapturingHandler();
        logger.addHandler(handler);
        return handler;
    }

    // ------------------------------------------------------------------
    // Task 2 Test 1 (WIRE-03's acceptance): a GridView of TextButton widgets
    // -- not ItemDisplay -- renders each at its own computed slot.
    // ------------------------------------------------------------------

    @Test
    void gridViewOfTextButtonsPositionsEachAtItsOwnComputedSlot() {
        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(TextButton.builder().text("A").build())
                .child(TextButton.builder().text("B").build())
                .child(TextButton.builder().text("C").build())
                .build();

        GridViewElement element = mountGridView(gridView);
        List<Element> children = element.getChildren();

        assertEquals(0, soleLeafOf(children.get(0)).getSlotIndex());
        assertEquals(1, soleLeafOf(children.get(1)).getSlotIndex());
        assertEquals(2, soleLeafOf(children.get(2)).getSlotIndex());
    }

    // ------------------------------------------------------------------
    // Task 2 Test 2: a GridView of ItemDisplay widgets renders at the same
    // slots it does today -- regression guard for deleting the hand-copied
    // ItemDisplay-only special case.
    // ------------------------------------------------------------------

    @Test
    void gridViewOfItemDisplaysStillPositionsAtTheSameSlots() {
        List<ItemStack> items = java.util.Arrays.asList(
                new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT), new ItemStack(Material.IRON_INGOT));

        GridView<ItemStack> gridView = GridView.<ItemStack>builder()
                .startSlot(0)
                .columns(9)
                .items(items, item -> ItemDisplay.builder(item).build())
                .build();

        GridViewElement element = mountGridView(gridView);
        List<Element> children = element.getChildren();

        assertEquals(0, soleLeafOf(children.get(0)).getSlotIndex());
        assertEquals(1, soleLeafOf(children.get(1)).getSlotIndex());
        assertEquals(2, soleLeafOf(children.get(2)).getSlotIndex());
    }

    // ------------------------------------------------------------------
    // Task 2 Test 3: a mix of widget types positions all of them.
    // ------------------------------------------------------------------

    @Test
    void mixedTypeGridViewPositionsEveryChild() {
        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(TextButton.builder().text("A").build())
                .child(ItemDisplay.builder(new ItemStack(Material.DIAMOND)).build())
                .child(TextButton.builder().text("C").build())
                .build();

        GridViewElement element = mountGridView(gridView);
        List<Element> children = element.getChildren();

        assertEquals(0, soleLeafOf(children.get(0)).getSlotIndex());
        assertEquals(1, soleLeafOf(children.get(1)).getSlotIndex());
        assertEquals(2, soleLeafOf(children.get(2)).getSlotIndex());
    }

    // ------------------------------------------------------------------
    // Task 2 Test 4 (the conflict rule): a child inside a GridView that
    // carries an explicit slot is positioned by the GridView, AND a
    // warning is emitted.
    // ------------------------------------------------------------------

    @Test
    void explicitChildSlotInsideGridViewIsOverriddenAndWarns() {
        CapturingHandler handler = attachCapturingHandler();
        try {
            GridView<Void> gridView = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(TextButton.builder().text("A").slot(50).build())
                    .build();

            GridViewElement element = mountGridView(gridView);
            RenderNode leaf = soleLeafOf(element.getChildren().get(0));

            assertEquals(0, leaf.getSlotIndex(), "the GridView-computed slot must win over the explicit one");
            assertTrue(handler.records.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                    "an explicit child slot conflicting with the GridView must emit a WARNING");
        } finally {
            Logger.getLogger(GridViewElement.class.getName()).removeHandler(handler);
        }
    }

    // ------------------------------------------------------------------
    // Task 2 Test 5: a child with no explicit slot produces no warning.
    // ------------------------------------------------------------------

    @Test
    void childWithNoExplicitSlotProducesNoWarning() {
        CapturingHandler handler = attachCapturingHandler();
        try {
            GridView<Void> gridView = GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(TextButton.builder().text("A").build())
                    .build();

            mountGridView(gridView);

            assertTrue(handler.records.isEmpty(), "no explicit slot means no conflict, so no warning");
        } finally {
            Logger.getLogger(GridViewElement.class.getName()).removeHandler(handler);
        }
    }

    @Test
    void childLegitimatelyAtSlotZeroProducesNoWarning() {
        // A GridView whose first computed slot is not 0 (startSlot offset), so a child
        // explicitly declaring slot 0 is genuinely "at slot 0", not merely defaulted there.
        CapturingHandler handler = attachCapturingHandler();
        try {
            GridView<Void> gridView = GridView.<Void>builder()
                    .startSlot(9)
                    .columns(9)
                    .child(TextButton.builder().text("A").build())
                    .build();

            mountGridView(gridView);

            assertTrue(handler.records.isEmpty(),
                    "slot 0 is this codebase's builder default and must never itself trigger the "
                            + "conflict warning, since ItemDisplay/TextButton have no separate unset flag");
        } finally {
            Logger.getLogger(GridViewElement.class.getName()).removeHandler(handler);
        }
    }

    // ------------------------------------------------------------------
    // Task 2 Test 6 (empty and single): an empty GridView renders nothing
    // and throws nothing; a single-item GridView renders at the first slot.
    // ------------------------------------------------------------------

    @Test
    void emptyGridViewRendersNothingAndThrowsNothing() {
        GridView<Void> gridView = GridView.<Void>builder().startSlot(0).columns(9).build();

        assertDoesNotThrow(() -> mountGridView(gridView));
    }

    @Test
    void singleItemGridViewRendersAtFirstComputedSlot() {
        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(20)
                .columns(9)
                .child(TextButton.builder().text("only").build())
                .build();

        GridViewElement element = mountGridView(gridView);
        assertEquals(20, soleLeafOf(element.getChildren().get(0)).getSlotIndex());
    }

    // ------------------------------------------------------------------
    // Task 2 Test 7: overflow behaves per calculateSlotForChild's existing
    // contract (row/col arithmetic, no capping) -- pinned, not invented.
    // ------------------------------------------------------------------

    @Test
    void overflowFollowsCalculateSlotForChildsExistingArithmetic() {
        GridView.Builder<Void> builder = GridView.<Void>builder().startSlot(0).columns(9);
        for (int i = 0; i < 12; i++) {
            builder.child(TextButton.builder().text("i" + i).build());
        }
        GridView<Void> gridView = builder.build();

        GridViewElement element = mountGridView(gridView);
        List<Element> children = element.getChildren();

        // index 9 -> row 1, col 0 -> slot 9 (columns=9); this is calculateSlotForChild's
        // existing, uncapped arithmetic -- the row/col math is pinned as-is per this task's
        // instruction not to invent a new overflow rule.
        assertEquals(9, soleLeafOf(children.get(9)).getSlotIndex());
        assertEquals(11, soleLeafOf(children.get(11)).getSlotIndex());
    }

    // ------------------------------------------------------------------
    // Task 2 Test 8 (nested containers, flagged assumption): a Container
    // nested inside a GridView cell has the computed slot propagated to
    // its descendant render objects.
    // ------------------------------------------------------------------

    @Test
    void nestedContainerWithOneLeafGetsTheComputedSlotPropagated() {
        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(Container.builder()
                        .child(TextButton.builder().text("nested").build())
                        .build())
                .build();

        GridViewElement element = mountGridView(gridView);
        assertEquals(0, soleLeafOf(element.getChildren().get(0)).getSlotIndex());
    }

    /**
     * Ambiguous case: a nested Container with MULTIPLE leaves inside one GridView cell. There
     * is no single "correct" position for two distinct render objects sharing one computed
     * slot -- D-11 does not settle this. This plan's documented resolution (see
     * 05-13-SUMMARY.md) is to propagate the cell's computed slot to every descendant leaf
     * rather than let an arbitrary traversal order pick a "winner" -- deterministic, but a
     * genuine collision the maintainer should define more precisely in a follow-up plan.
     */
    @Test
    void nestedContainerWithMultipleLeavesPropagatesToAll() {
        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(Container.builder()
                        .child(TextButton.builder().text("first").build())
                        .child(TextButton.builder().text("second").build())
                        .build())
                .build();

        GridViewElement element = mountGridView(gridView);
        List<RenderNode> leaves = leavesOf(element.getChildren().get(0));

        assertEquals(2, leaves.size());
        assertEquals(0, leaves.get(0).getSlotIndex());
        assertEquals(0, leaves.get(1).getSlotIndex(),
                "documented as a collision finding, not a resolved layout -- both leaves share the cell's slot");
    }

    // ------------------------------------------------------------------
    // Task 2 Test 9 (ordering): the parent-data write is visible in the
    // collected render nodes -- a repaint through GuiRenderer.performBuild()
    // places the children at the computed slots.
    // ------------------------------------------------------------------

    @Test
    void repaintThroughGuiRendererPlacesChildrenAtComputedSlots() {
        TestGui gui = new TestGui(player, "test-gui", "Test", 6);
        gui.setInventory(Bukkit.createInventory(gui, 54));
        GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);
        GuiRenderer renderer = new GuiRenderer(gui, player, scheduler);

        GridView<Void> gridView = GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(TextButton.builder().text("A").build())
                .child(TextButton.builder().text("B").build())
                .build();

        renderer.initialize(() -> gridView, rootContext);

        assertNotNull(gui.getInventory().getItem(0), "slot 0 should hold the first child after a real repaint");
        assertNotNull(gui.getInventory().getItem(1), "slot 1 should hold the second child after a real repaint");
    }

    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }
    }

    // ------------------------------------------------------------------
    // Task 1 sentinel: pre-existing widget-level tests (unrelated to slot
    // positioning) continue to construct GridView the same way they always did.
    // ------------------------------------------------------------------

    @Test
    void gridViewBuilderStillExposesStartSlotAndColumns() {
        GridView<Void> gridView = GridView.<Void>builder().startSlot(5).columns(3).build();
        assertEquals(5, gridView.getStartSlot());
        assertEquals(3, gridView.getColumns());
        assertTrue(gridView.getChildren().isEmpty());
    }
}
