package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplay;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplayElement;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;

import mc.obliviate.inventory.Gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Drives the full {@code Supplier<Widget>} -&gt; {@link GuiRenderer#performBuild()} -&gt;
 * Inventory path, end to end, over MockBukkit.
 * <p>
 * This is the GUI lane's tracer assertion for WIRE-02 / D-09 items 1-3 (05-VALIDATION.md's
 * governing constraint). {@code RenderNodeDifferTest} is green at HEAD and can hand the differ
 * two distinct, hand-built {@link com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode}
 * instances and observe a diff — but the production path (before this plan) never produces two
 * distinct nodes: {@link ItemDisplayElement}'s {@code getRenderNode()} always returns the same
 * cached instance, and {@link GuiRenderer} used to store that same live reference into
 * {@code lastRenderNodes}, so the differ compared every node against itself. Nothing short of
 * driving {@link GuiRenderer#performBuild()} twice and reading the resulting {@code Inventory}
 * can prove or disprove this defect — a hit count or a hand-built-node unit test proves nothing
 * here (05-VALIDATION.md's success criterion 2, stated verbatim).
 * <p>
 * <b>{@code RenderNodeDiffer.iconsEqual} is not touched by this plan.</b> It already starts with
 * {@code ItemStack.equals(...)}, and Bukkit's {@code ItemStack}/{@code ItemMeta} equality already
 * covers lore, display name and material — so a defect in the comparator's granularity was never
 * the cause. Root {@code CLAUDE.md} gotcha #12 blamed the comparator; the real defect was object
 * identity one layer up in {@code RenderObjectElement}/{@code GuiRenderer}, which this test proves
 * by observing the Inventory, not by inspecting the differ in isolation.
 */
@DisplayName("GuiRenderer repaint seam (D-09 items 1-3 / WIRE-02)")
class GuiRendererRepaintTest {

    private ServerMock server;
    private Plugin mockPlugin;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Test 1 (THE SEAM): a lore-only change repaints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A lore-only change, across two performBuild() passes, changes the inventory")
    void loreOnlyChangeRepaintsTheInventory() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicReference<String[]> lore = new AtomicReference<>(new String[]{"v1"});
        Supplier<Widget> supplier = () -> ItemDisplay.builder(new ItemStack(Material.DIAMOND))
                .slot(0)
                .lore(lore.get())
                .build();

        renderer.initialize(supplier, context);
        assertEquals(Collections.singletonList("v1"), loreAt(gui, 0),
                "first frame must render the initial lore");

        lore.set(new String[]{"v2"});
        renderer.scheduleBuild();

        assertEquals(Collections.singletonList("v2"), loreAt(gui, 0),
                "RED at HEAD: a lore-only change across two performBuild() passes must reach "
                        + "the inventory. Before this plan, RenderObjectElement.getRenderNode() "
                        + "always returned the same mutated instance and GuiRenderer stored that "
                        + "same live reference in lastRenderNodes, so the differ compared each "
                        + "node against itself and this assertion failed with the stale 'v1' lore.");
    }

    // ------------------------------------------------------------------
    // Test 2 (the broader claim): display-name-only and item-type changes also repaint
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A display-name-only change also repaints")
    void displayNameOnlyChangeRepaintsTheInventory() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicReference<String> name = new AtomicReference<>("Name A");
        Supplier<Widget> supplier = () -> ItemDisplay.builder(new ItemStack(Material.DIAMOND))
                .slot(0)
                .name(name.get())
                .build();

        renderer.initialize(supplier, context);
        assertEquals("Name A", displayNameAt(gui, 0));

        name.set("Name B");
        renderer.scheduleBuild();

        assertEquals("Name B", displayNameAt(gui, 0),
                "a display-name-only change must also repaint — the phase criterion names lore "
                        + "because that is the case root CLAUDE.md's gotcha #12 got wrong, not "
                        + "because it is the only repaintable case");
    }

    @Test
    @DisplayName("An item-type change also repaints")
    void itemTypeChangeRepaintsTheInventory() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicReference<Material> material = new AtomicReference<>(Material.DIAMOND);
        Supplier<Widget> supplier = () -> ItemDisplay.builder(new ItemStack(material.get()))
                .slot(0)
                .build();

        renderer.initialize(supplier, context);
        assertEquals(Material.DIAMOND, itemAt(gui, 0).getType());

        material.set(Material.GOLD_INGOT);
        renderer.scheduleBuild();

        assertEquals(Material.GOLD_INGOT, itemAt(gui, 0).getType(),
                "an item-type change must also repaint");
    }

    // ------------------------------------------------------------------
    // Test 3: no state change produces no inventory write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two passes with no state change write to the inventory only once")
    void noStateChangeProducesNoAdditionalWrite() {
        TestGui realGui = newGui(1);
        TestGui gui = spy(realGui);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        Widget widget = ItemDisplay.builder(new ItemStack(Material.DIAMOND)).slot(0).lore("same").build();
        renderer.initialize(() -> widget, context);

        // The very first frame legitimately writes the initial content — only the SECOND
        // (no-op) frame is under test here.
        clearInvocations(gui);

        renderer.scheduleBuild();

        verify(gui, never()).addItem(anyInt(), any(mc.obliviate.inventory.Icon.class));
    }

    // ------------------------------------------------------------------
    // Test 4: the widget supplier is invoked exactly once per performBuild()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The widget supplier is invoked exactly once per performBuild(), including the first")
    void supplierIsInvokedExactlyOncePerBuild() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicInteger invocations = new AtomicInteger(0);
        Supplier<Widget> supplier = () -> {
            invocations.incrementAndGet();
            return ItemDisplay.builder(new ItemStack(Material.DIAMOND)).slot(0).build();
        };

        renderer.initialize(supplier, context);
        assertEquals(1, invocations.get(),
                "the very first frame must call the supplier exactly once — not zero, not twice");

        invocations.set(0);
        renderer.scheduleBuild();
        assertEquals(1, invocations.get(),
                "a subsequent performBuild() must also call the supplier exactly once");
    }

    // ------------------------------------------------------------------
    // Test 5: an updated element is marked dirty and rebuilt; an untouched sibling is not
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An element whose widget was updated is rebuilt; an untouched sibling is not")
    void updatedElementIsRebuiltUntouchedSiblingIsNot() {
        BuildContext context = rootContext(1);

        ItemDisplay widgetA1 = ItemDisplay.builder(new ItemStack(Material.STONE)).slot(0).build();
        ItemDisplay widgetB1 = ItemDisplay.builder(new ItemStack(Material.DIRT)).slot(1).build();

        ItemDisplayElement elementA = spy((ItemDisplayElement) widgetA1.createElement());
        elementA.assignContext(context);
        elementA.mount(null);

        ItemDisplayElement elementB = spy((ItemDisplayElement) widgetB1.createElement());
        elementB.assignContext(context);
        elementB.mount(null);

        // Both elements are clean immediately after mount — mount() explicitly resets dirty.
        assertFalse(elementA.isDirty());
        assertFalse(elementB.isDirty());

        // Only A's widget is updated. B is never touched at all — this is what a truly
        // "untouched sibling" means once Container/GridView's own dual-child cascading
        // (D-09 item 5, plan 05-13) is out of scope for this test.
        ItemDisplay widgetA2 = ItemDisplay.builder(new ItemStack(Material.STONE)).slot(0).lore("changed").build();
        elementA.update(widgetA2);

        assertTrue(elementA.isDirty(), "the updated element must be marked dirty (D-09 item 2)");
        assertFalse(elementB.isDirty(), "the untouched sibling must remain clean");

        // Mirror GuiRenderer.rebuildElement's exact conditional shape: only dirty elements
        // get performRebuild() invoked.
        if (elementA.isDirty()) {
            elementA.performRebuild();
        }
        if (elementB.isDirty()) {
            elementB.performRebuild();
        }

        verify(elementA, times(1)).performRebuild();
        verify(elementB, never()).performRebuild();
    }

    // ------------------------------------------------------------------
    // Test 6: a later in-place mutation cannot retroactively alter the previous frame's record
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A third performBuild() pass correctly detects change even though frame 2's "
            + "record shares no live reference with frame 3's mutation")
    void thirdPassDetectsChangeDespiteInPlaceMutation() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicReference<String[]> lore = new AtomicReference<>(new String[]{"A"});
        Supplier<Widget> supplier = () -> ItemDisplay.builder(new ItemStack(Material.DIAMOND))
                .slot(0)
                .lore(lore.get())
                .build();

        // Frame 1: content A
        renderer.initialize(supplier, context);
        assertEquals(Collections.singletonList("A"), loreAt(gui, 0));

        // Frame 2: content B — proves the first transition (same territory as Test 1).
        lore.set(new String[]{"B"});
        renderer.scheduleBuild();
        assertEquals(Collections.singletonList("B"), loreAt(gui, 0));

        // Frame 3: content C — this is the load-bearing assertion. Without RenderNode.copy(),
        // frame 2's "record" in lastRenderNodes is the SAME live RenderNode instance that frame
        // 3's performRebuild() mutates in place BEFORE the diff runs, so by the time diff()
        // executes, oldNodes and newNodes would already show identical (C) content and the
        // B -> C transition would be silently swallowed — the inventory would incorrectly stay
        // showing "B".
        lore.set(new String[]{"C"});
        renderer.scheduleBuild();
        assertEquals(Collections.singletonList("C"), loreAt(gui, 0),
                "the B -> C transition must be detected — a snapshot (copy()) is required so "
                        + "frame 2's diff record is not silently overwritten by frame 3's "
                        + "in-place mutation of the live RenderNode");
    }

    // ------------------------------------------------------------------
    // Remount path: the supplier returns a root-incompatible widget type
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A root-incompatible widget type remounts instead of throwing")
    void incompatibleRootTypeRemountsInstead() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui);
        BuildContext context = rootContext(1);

        AtomicReference<Boolean> useTextButton = new AtomicReference<>(false);
        Supplier<Widget> supplier = () -> useTextButton.get()
                ? TextButton.builder().text("Confirm").slot(0).build()
                : ItemDisplay.builder(new ItemStack(Material.DIAMOND)).slot(0).build();

        renderer.initialize(supplier, context);
        assertEquals(Material.DIAMOND, itemAt(gui, 0).getType());

        useTextButton.set(true);

        assertDoesNotThrow(renderer::scheduleBuild,
                "Element.update() throws IllegalArgumentException when canUpdate() is false; "
                        + "GuiRenderer must catch that case itself and remount rather than let "
                        + "the exception escape into the scheduled frame (T-05-52)");

        assertNotEquals(Material.DIAMOND, itemAt(gui, 0).getType(),
                "the remounted root must actually render the new widget type");
        assertEquals("Confirm", displayNameAt(gui, 0),
                "the remounted TextButton must show its own text as the display name");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private BuildContext rootContext(int rows) {
        return BuildContext.root(player, "test-gui", rows);
    }

    private TestGui newGui(int rows) {
        TestGui gui = new TestGui(player, "test-gui", "Test", rows);
        gui.setInventory(Bukkit.createInventory(gui, rows * 9));
        return gui;
    }

    /**
     * frameIntervalMs = 0 makes {@link GuiScheduler#scheduleFrame} always compute a zero delay,
     * so — combined with MockBukkit treating the test thread as the primary thread — every
     * {@code scheduleBuild()} call in this test class executes synchronously. This is a
     * test-harness choice only; it does not require changing {@code GuiRenderer.performBuild()}'s
     * visibility.
     */
    private GuiRenderer newRenderer(Gui gui) {
        GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);
        return new GuiRenderer(gui, player, scheduler);
    }

    private ItemStack itemAt(Gui gui, int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        assertNotNull(stack, "slot " + slot + " should hold an item");
        return stack;
    }

    private List<String> loreAt(Gui gui, int slot) {
        ItemMeta meta = itemAt(gui, slot).getItemMeta();
        assertNotNull(meta, "slot " + slot + " item should carry ItemMeta");
        List<String> lore = meta.getLore();
        assertNotNull(lore, "slot " + slot + " item should carry lore");
        return lore;
    }

    private String displayNameAt(Gui gui, int slot) {
        ItemMeta meta = itemAt(gui, slot).getItemMeta();
        assertNotNull(meta, "slot " + slot + " item should carry ItemMeta");
        return meta.getDisplayName();
    }

    /**
     * Minimal concrete {@link Gui} for tests — {@code Gui} is abstract only to force
     * subclassing; every method it declares already has a body.
     */
    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }
    }
}
