package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatelessWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import mc.obliviate.inventory.Gui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Second-frame reproductions for GridView cells built by component (wrapper) widgets.
 * <p>
 * {@code GridViewElement} writes each cell's computed slot onto the leaf {@code RenderNode}s as
 * parent data. On every frame after the first, a component cell's leaf is only updated (and
 * marked dirty) by the GridView's own rebuild; if the GridView writes positions before that
 * leaf has rebuilt, the renderer's later visit resets the leaf's slot from its widget and the
 * computed slot is lost. These tests drive real frames through {@link GuiRenderer} and read the
 * Inventory, the click routing and the override warnings, so they observe what a player (and a
 * server operator reading the log) would see after a repaint.
 *
 * @author UltiTools Team
 * @since 6.3.0
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class GridViewComponentCellRepaintTest {

    private static final int GUI_ROWS = 1;
    private static final int GUI_SIZE = GUI_ROWS * 9;
    private static final String EMPTY = "-";
    private static final Pattern OVERRIDE_WARNING =
            Pattern.compile("overrides an explicit slot \\((\\d+)\\) on the child at index (\\d+)");

    private Plugin mockPlugin;
    private Player player;
    private BuildContext rootContext;
    private TestGui gui;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        rootContext = BuildContext.root(player, "test-gui", GUI_ROWS);
        gui = new TestGui(player, "test-gui", "Test", GUI_ROWS);
        gui.setInventory(Bukkit.createInventory(gui, GUI_SIZE));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Task 1 (tracer): stateless wrapper cells through a second frame.
    // ------------------------------------------------------------------

    @Test
    void statelessCellsKeepComputedSlotsOnSecondFrame() {
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(new StatelessLabelCell("A"))
                .child(new StatelessLabelCell("B"))
                .child(new StatelessLabelCell("C"))
                .build());

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 1 (precondition): each stateless cell renders at its computed slot");

        renderer.scheduleBuild();

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 2: each stateless cell must still render at its GridView-computed slot");
    }

    // ------------------------------------------------------------------
    // Task 2: stateful and nested wrappers, click routing, warnings, controls.
    // ------------------------------------------------------------------

    @Test
    void statefulCellsKeepComputedSlotsOnSecondFrame() {
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(new StatefulItemCell(Material.DIAMOND))
                .child(new StatefulItemCell(Material.EMERALD))
                .child(new StatefulItemCell(Material.GOLD_INGOT))
                .build());

        assertEquals("DIAMOND|EMERALD|GOLD_INGOT", labels(0, 1, 2),
                "frame 1 (precondition): each stateful cell renders at its computed slot");

        renderer.scheduleBuild();

        assertEquals("DIAMOND|EMERALD|GOLD_INGOT", labels(0, 1, 2),
                "frame 2: each stateful cell's ItemDisplay must still render at its GridView-computed slot");
    }

    @Test
    void nestedWrapperCellsKeepComputedSlotsOnSecondFrame() {
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(new NestedLabelCell("A"))
                .child(new NestedLabelCell("B"))
                .child(new NestedLabelCell("C"))
                .build());

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 1 (precondition): each two-level wrapper cell renders at its computed slot");

        renderer.scheduleBuild();
        String frame2 = labels(0, 1, 2);
        renderer.scheduleBuild();
        String frame3 = labels(0, 1, 2);

        assertEquals("frame2=A|B|C frame3=A|B|C", "frame2=" + frame2 + " frame3=" + frame3,
                "frames 2 and 3: a StatelessWidget wrapping a StatefulWidget wrapping a TextButton "
                        + "must keep each cell at its GridView-computed slot");
    }

    @Test
    void componentCellClicksRouteToTheirOwnHandlersAfterRepaint() {
        AtomicInteger clicksA = new AtomicInteger();
        AtomicInteger clicksB = new AtomicInteger();
        AtomicInteger clicksC = new AtomicInteger();
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(new ClickCountingCell("A", clicksA))
                .child(new ClickCountingCell("B", clicksB))
                .child(new ClickCountingCell("C", clicksC))
                .build());

        renderer.scheduleBuild();

        renderer.handleClick(clickAtRawSlot(1));
        String afterSlot1 = counts(clicksA, clicksB, clicksC);
        renderer.handleClick(clickAtRawSlot(0));
        String afterSlot0 = counts(clicksA, clicksB, clicksC);

        assertEquals("slot1->A=0,B=1,C=0 slot0->A=1,B=1,C=0",
                "slot1->" + afterSlot1 + " slot0->" + afterSlot0,
                "after frame 2 a click on a component cell's slot must run that cell's own handler "
                        + "exactly once and no other cell's handler");
    }

    @Test
    void componentCellsWithDefaultSlotLogNoOverrideWarningOnRepaint() {
        CapturingHandler handler = attachCapturingHandler();
        try {
            GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new StatelessLabelCell("A"))
                    .child(new StatelessLabelCell("B"))
                    .child(new StatelessLabelCell("C"))
                    .build());
            renderer.scheduleBuild();

            assertEquals(Collections.emptyList(), handler.overrideWarnings(),
                    "component cells that declare no slot must log no override warning on frame 1 or frame 2");
        } finally {
            Logger.getLogger(GridViewElement.class.getName()).removeHandler(handler);
        }
    }

    @Test
    void explicitSlotInsideComponentCellIsWarnedAndOverridden() {
        CapturingHandler handler = attachCapturingHandler();
        try {
            GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                    .startSlot(0)
                    .columns(9)
                    .child(new StatelessLabelCell("A"))
                    .child(new StatelessLabelCell("B", 7))
                    .child(new StatelessLabelCell("C"))
                    .build());

            assertEquals("warnings=[explicit 7 at index 1] slots=A|B|C|-",
                    "warnings=" + handler.overrideWarnings() + " slots=" + labels(0, 1, 2, 7),
                    "frame 1 (precondition): the explicit slot is warned about and overridden");

            handler.records.clear();
            renderer.scheduleBuild();

            assertEquals("warnings=[explicit 7 at index 1] slots=A|B|C|-",
                    "warnings=" + handler.overrideWarnings() + " slots=" + labels(0, 1, 2, 7),
                    "frame 2: the warning must name the leaf's declared slot (7), and the "
                            + "GridView-computed slot (1) must still win");
        } finally {
            Logger.getLogger(GridViewElement.class.getName()).removeHandler(handler);
        }
    }

    @Test
    void directLeafCellsKeepComputedSlotsOnSecondFrame() {
        GuiRenderer renderer = startRenderer(() -> GridView.<Void>builder()
                .startSlot(0)
                .columns(9)
                .child(TextButton.builder().text("A").build())
                .child(TextButton.builder().text("B").build())
                .child(TextButton.builder().text("C").build())
                .build());

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 1 (precondition): each direct leaf cell renders at its computed slot");

        renderer.scheduleBuild();

        assertEquals("A|B|C", labels(0, 1, 2),
                "frame 2 (control): direct leaf cells keep their GridView-computed slots");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private GuiRenderer startRenderer(@NotNull Supplier<Widget> supplier) {
        GuiScheduler scheduler = new GuiScheduler(mockPlugin, 0L);
        GuiRenderer renderer = new GuiRenderer(gui, player, scheduler);
        renderer.initialize(supplier, rootContext);
        return renderer;
    }

    /** Labels of the given slots joined by '|': display name, else material, else "-". */
    private String labels(int... slots) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(labelAt(slots[i]));
        }
        return out.toString();
    }

    private String labelAt(int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        if (stack == null || stack.getType() == Material.AIR) {
            return EMPTY;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return stack.getType().name();
    }

    private static InventoryClickEvent clickAtRawSlot(int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(rawSlot);
        return event;
    }

    private static String counts(AtomicInteger a, AtomicInteger b, AtomicInteger c) {
        return "A=" + a.get() + ",B=" + b.get() + ",C=" + c.get();
    }

    private static CapturingHandler attachCapturingHandler() {
        CapturingHandler handler = new CapturingHandler();
        Logger.getLogger(GridViewElement.class.getName()).addHandler(handler);
        return handler;
    }

    /** Captures java.util.logging records emitted by GridViewElement's logger during a test. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() { /* nothing buffered -- records are added synchronously in publish() */ }

        @Override
        public void close() { /* no resources to release -- this handler owns nothing but the list */ }

        /** Override warnings as "explicit SLOT at index I", in emission order. */
        List<String> overrideWarnings() {
            List<String> out = new ArrayList<>();
            for (LogRecord record : records) {
                if (!Level.WARNING.equals(record.getLevel())) {
                    continue;
                }
                Matcher matcher = OVERRIDE_WARNING.matcher(record.getMessage());
                out.add(matcher.find()
                        ? "explicit " + matcher.group(1) + " at index " + matcher.group(2)
                        : record.getMessage());
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A stateless component cell wrapping one TextButton, optionally with an explicit slot. */
    private static final class StatelessLabelCell extends StatelessWidget {
        private final String label;
        private final int explicitSlot;

        StatelessLabelCell(String label) {
            this(label, -1);
        }

        StatelessLabelCell(String label, int explicitSlot) {
            this.label = label;
            this.explicitSlot = explicitSlot;
        }

        @Override
        @NotNull
        public Widget build(@NotNull BuildContext context) {
            TextButton.Builder builder = TextButton.builder().text(label);
            if (explicitSlot >= 0) {
                builder.slot(explicitSlot);
            }
            return builder.build();
        }
    }

    /** A stateless component cell wrapping one TextButton whose click increments a counter. */
    private static final class ClickCountingCell extends StatelessWidget {
        private final String label;
        private final AtomicInteger clicks;

        ClickCountingCell(String label, AtomicInteger clicks) {
            this.label = label;
            this.clicks = clicks;
        }

        @Override
        @NotNull
        public Widget build(@NotNull BuildContext context) {
            return TextButton.builder().text(label).onClick(() -> clicks.incrementAndGet()).build();
        }
    }

    /** A stateful component cell building one ItemDisplay of the given material. */
    private static final class StatefulItemCell extends StatefulWidget {
        private final Material material;

        StatefulItemCell(Material material) {
            this.material = material;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new StatefulItemCellState();
        }
    }

    private static final class StatefulItemCellState extends State<StatefulItemCell> {
        @Override
        public Widget build(@NotNull BuildContext context) {
            return ItemDisplay.builder(new ItemStack(getWidget().material)).build();
        }
    }

    /** Two composition levels: a StatelessWidget wrapping a StatefulWidget wrapping a TextButton. */
    private static final class NestedLabelCell extends StatelessWidget {
        private final String label;

        NestedLabelCell(String label) {
            this.label = label;
        }

        @Override
        @NotNull
        public Widget build(@NotNull BuildContext context) {
            return new StatefulLabelCell(label);
        }
    }

    private static final class StatefulLabelCell extends StatefulWidget {
        private final String label;

        StatefulLabelCell(String label) {
            this.label = label;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new StatefulLabelCellState();
        }
    }

    private static final class StatefulLabelCellState extends State<StatefulLabelCell> {
        @Override
        public Widget build(@NotNull BuildContext context) {
            return TextButton.builder().text(getWidget().label).build();
        }
    }

    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }
    }
}
