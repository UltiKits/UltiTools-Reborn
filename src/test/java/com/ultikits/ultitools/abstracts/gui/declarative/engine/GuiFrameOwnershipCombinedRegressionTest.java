package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridView;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

import mc.obliviate.inventory.Gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Combined-head regression for issue #679: one production {@link DeclarativeGui} session that
 * depends on all three corrections at once.
 * <ol>
 * <li>Rebuild requests raised while a frame is mounting are coalesced into one follow-up frame
 * (case {@code 2f791fb2270f5f79ad9dda6980078a1017a16129bc53190799c3c6d2982b86f5}): each cell
 * State subscribes in {@code initState} to a source that delivers its current value
 * synchronously, so opening the page raises a request during the first mount.</li>
 * <li>A GridView rebuilds every component cell's complete subtree before it writes the cells'
 * slots (case {@code e9a31051579695e728999d11e67335c46656c31db21db58db430f6a7b4b77886}): the
 * cells are StatefulWidgets whose leaf TextButtons carry the default slot, and every frame after
 * the first must keep them at their computed slots, with clicks routed accordingly.</li>
 * <li>A reconciliation that throws keeps every mounted child owned by its parent (case
 * {@code d92e7d20b28ecee218c1ad5c8d4b9d1aec89a4367ba9b4d1cb435b8da8d14511}): a replacement made
 * before a failing sibling must still be disposed exactly once when the page closes.</li>
 * </ol>
 * The page uses the production default {@link GuiScheduler} (16 ms frames, plugin = the mocked
 * UltiTools instance), and every request is drained with {@code performTicks(25)}, so the
 * assertions do not depend on whether a given frame ran inline or on a later tick.
 * <p>
 * Bounded failing frame: the only State created in the failing frame ({@code a2}) registers a
 * counting-only listener and never calls {@code setState}, so the failing frame records no
 * rebuild request and nothing is replayed after it.
 */
@DisplayName("Mount coalescing, GridView repaint and child ownership hold together in one GUI session (issue #679)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GuiFrameOwnershipCombinedRegressionTest {

    /** Enough ticks for any frame the 16 ms default scheduler deferred to run. */
    private static final int DRAIN_TICKS = 25;

    private ServerMock server;
    private Player player;
    private Logger pageLogger;
    private FrameErrorCounter frameErrors;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        MockBukkit.createMockPlugin();
        player = server.addPlayer();
        pageLogger = Logger.getLogger(GuiFrameOwnershipCombinedRegressionTest.class.getName());
        frameErrors = new FrameErrorCounter();
        pageLogger.addHandler(frameErrors);
        // The default GuiScheduler's plugin is this mocked instance; executeFrame logs frame
        // failures through plugin.getLogger(), so stub it before the mock is published.
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getLogger()).thenReturn(pageLogger));
    }

    @AfterEach
    void tearDown() {
        pageLogger.removeHandler(frameErrors);
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("One State per cell, repainted cells keep their slots and clicks, and close disposes each State once")
    void combinedSessionKeepsOneStatePerCellRoutesClicksAndDisposesEachStateOnce() {
        Lifecycle life = new Lifecycle();
        ValueSource source = new ValueSource("v0");
        List<String> clicks = new ArrayList<>();
        CombinedPage page = new CombinedPage(player, life, source, clicks);
        page.setInventory(Bukkit.createInventory(page, 9));

        // Step 1: open. Every cell subscribes during the first mount and receives v0 at once.
        page.onOpen(mock(InventoryOpenEvent.class));
        drain();

        assertEquals("states{a=[0], b=[0], c=[0]} listeners=3",
                "states" + life.disposeCounts("a", "b", "c") + " listeners=" + source.listenerCount(),
                "step 1 (open): each cell's synchronous subscription during the first mount must be "
                        + "coalesced into a follow-up frame, so each cell has exactly one State and one "
                        + "subscription");
        assertEquals("a:v0|b:v0|c:v0", labels(page),
                "step 1 (open): the follow-up frame must keep each component cell at its "
                        + "GridView-computed slot and show the delivered value");

        // Step 2: a new value repaints every cell; clicks reach the cell shown in the slot.
        source.emit("v1");
        drain();
        String slotsAfterEmit = labels(page);
        page.onClick(clickAt(1));
        String clicksAfterSlot1 = clicks.toString();
        page.onClick(clickAt(2));
        String clicksAfterSlot2 = clicks.toString();

        assertEquals("slots=a:v1|b:v1|c:v1 clickSlot1=[b] clickSlot2=[b, c]",
                "slots=" + slotsAfterEmit + " clickSlot1=" + clicksAfterSlot1
                        + " clickSlot2=" + clicksAfterSlot2,
                "step 2 (repaint): every repainted cell must stay at its computed slot, and a click "
                        + "on a slot must run that cell's own handler exactly once");

        // Step 3: replace a, then b's didUpdateWidget throws; the frame fails and is logged.
        int buildsBeforeFailingFrame = page.builds.get();
        page.replaceFirstCellAndFailSecond();
        drain();

        assertEquals("frameErrors=1 builds=1 states{a=[1], a2=[0], b=[0], c=[0]} listeners=3",
                "frameErrors=" + frameErrors.count()
                        + " builds=" + (page.builds.get() - buildsBeforeFailingFrame)
                        + " states" + life.disposeCounts("a", "a2", "b", "c")
                        + " listeners=" + source.listenerCount(),
                "step 3 (failing frame): the frame must fail once and be logged, the old a must be "
                        + "disposed and its replacement a2 created once, and no rebuild may be replayed");

        // Step 4: close. Every created State is disposed exactly once and nothing builds after.
        page.onClose(mock(InventoryCloseEvent.class));
        int buildsAtClose = page.builds.get();
        drain();

        assertEquals("states{a=[1], a2=[1], b=[1], c=[1]} listeners=0 buildsAfterClose=0",
                "states" + life.disposeCounts("a", "a2", "b", "c")
                        + " listeners=" + source.listenerCount()
                        + " buildsAfterClose=" + (page.builds.get() - buildsAtClose),
                "step 4 (close): the replacement a2 mounted before b's failure must still be owned, "
                        + "so closing disposes every created State exactly once, releases every "
                        + "subscription and builds nothing afterwards");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void drain() {
        server.getScheduler().performTicks(DRAIN_TICKS);
    }

    private static InventoryClickEvent clickAt(int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getSlot()).thenReturn(rawSlot);
        return event;
    }

    private static String labels(Gui gui) {
        return displayNameAt(gui, 0) + "|" + displayNameAt(gui, 1) + "|" + displayNameAt(gui, 2);
    }

    private static String displayNameAt(Gui gui, int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        if (stack == null) {
            return "-";
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? "?" : meta.getDisplayName();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * A one-row page whose build returns a GridView (start slot 0, nine columns) of three cells.
     * {@link #replaceFirstCellAndFailSecond()} swaps cell a for a different widget class and
     * makes cell b's didUpdateWidget throw, then requests a frame.
     */
    private static final class CombinedPage extends DeclarativeGui {
        private final Lifecycle life;
        private final ValueSource source;
        private final List<String> clicks;
        private final AtomicInteger builds = new AtomicInteger();
        private boolean failing;

        CombinedPage(Player player, Lifecycle life, ValueSource source, List<String> clicks) {
            super(player, "gui-frame-ownership-combined-page", "Test", 1);
            this.life = life;
            this.source = source;
            this.clicks = clicks;
        }

        void replaceFirstCellAndFailSecond() {
            failing = true;
            markNeedsBuild();
        }

        @Override
        public Widget build(BuildContext context) {
            builds.incrementAndGet();
            List<Widget> cells = failing
                    ? Arrays.<Widget>asList(
                            new QuietCell(life, "a2", source),
                            new ValueCell(life, "b", source, clicks, true),
                            new ValueCell(life, "c", source, clicks, false))
                    : Arrays.<Widget>asList(
                            new ValueCell(life, "a", source, clicks, false),
                            new ValueCell(life, "b", source, clicks, false),
                            new ValueCell(life, "c", source, clicks, false));
            GridView.Builder<Void> grid = GridView.<Void>builder().startSlot(0).columns(9);
            for (Widget cell : cells) {
                grid.child(cell);
            }
            return grid.build();
        }
    }

    /** Counts frame failures GuiScheduler.executeFrame logs through the plugin logger. */
    private static final class FrameErrorCounter extends Handler {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void publish(LogRecord logRecord) {
            String message = logRecord.getMessage();
            if (message != null && message.startsWith("Error executing GUI frame task")) {
                count.incrementAndGet();
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }

        int count() {
            return count.get();
        }
    }

    /** One created State's identity and dispose count. */
    private static final class Tracked {
        private int disposeCount;
    }

    /** Every State created for a logical cell id, in creation order. */
    private static final class Lifecycle {
        private final Map<String, List<Tracked>> states = new LinkedHashMap<>();

        Tracked created(String id) {
            List<Tracked> list = states.get(id);
            if (list == null) {
                list = new ArrayList<>();
                states.put(id, list);
            }
            Tracked tracked = new Tracked();
            list.add(tracked);
            return tracked;
        }

        /**
         * Per id, the dispose count of each State created for it, in creation order; the list
         * length is therefore the createState count.
         */
        String disposeCounts(String... ids) {
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < ids.length; i++) {
                List<Integer> counts = new ArrayList<>();
                List<Tracked> list = states.get(ids[i]);
                if (list != null) {
                    for (Tracked tracked : list) {
                        counts.add(tracked.disposeCount);
                    }
                }
                out.append(i == 0 ? "" : ", ").append(ids[i]).append('=').append(counts);
            }
            return out.append('}').toString();
        }
    }

    /** A shared value source that delivers its current value to a new listener synchronously. */
    private static final class ValueSource {
        private final List<Consumer<String>> listeners = new ArrayList<>();
        private String value;

        ValueSource(String initial) {
            this.value = initial;
        }

        void subscribe(Consumer<String> listener) {
            listeners.add(listener);
            listener.accept(value);
        }

        void unsubscribe(Consumer<String> listener) {
            listeners.remove(listener);
        }

        void emit(String newValue) {
            value = newValue;
            for (Consumer<String> listener : new ArrayList<>(listeners)) {
                listener.accept(newValue);
            }
        }

        int listenerCount() {
            return listeners.size();
        }
    }

    /** A cell that shows {@code id:value} and records its id when clicked. */
    private static final class ValueCell extends StatefulWidget {
        private final Lifecycle life;
        private final String id;
        private final ValueSource source;
        private final List<String> clicks;
        private final boolean failUpdate;

        ValueCell(Lifecycle life, String id, ValueSource source, List<String> clicks, boolean failUpdate) {
            this.life = life;
            this.id = id;
            this.source = source;
            this.clicks = clicks;
            this.failUpdate = failUpdate;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new ValueCellState(life.created(id));
        }
    }

    /**
     * Subscribes in initState (the listener calls setState), unsubscribes in dispose, and throws
     * from didUpdateWidget when its new widget asks it to. Builds a TextButton at the default
     * slot, so its position comes only from the GridView.
     */
    private static final class ValueCellState extends State<ValueCell> {
        private final Tracked tracked;
        private Consumer<String> listener;
        private ValueSource subscribedTo;
        private String value = "none";

        ValueCellState(Tracked tracked) {
            this.tracked = tracked;
        }

        @Override
        protected void initState() {
            subscribedTo = getWidget().source;
            listener = newValue -> setState(() -> value = newValue);
            subscribedTo.subscribe(listener);
        }

        @Override
        protected void didUpdateWidget(ValueCell oldWidget) {
            if (getWidget().failUpdate) {
                throw new IllegalStateException("didUpdateWidget failed for " + getWidget().id);
            }
        }

        @Override
        public Widget build(BuildContext context) {
            final ValueCell widget = getWidget();
            return TextButton.builder()
                    .text(widget.id + ":" + value)
                    .onClick(() -> widget.clicks.add(widget.id))
                    .build();
        }

        @Override
        protected void dispose() {
            tracked.disposeCount++;
            if (subscribedTo != null) {
                subscribedTo.unsubscribe(listener);
                subscribedTo = null;
            }
            super.dispose();
        }
    }

    /** A different cell class: replacing a ValueCell with it unmounts the old State. */
    private static final class QuietCell extends StatefulWidget {
        private final Lifecycle life;
        private final String id;
        private final ValueSource source;

        QuietCell(Lifecycle life, String id, ValueSource source) {
            this.life = life;
            this.id = id;
            this.source = source;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new QuietCellState(life.created(id));
        }
    }

    /**
     * Registers a counting-only listener in initState and never calls setState, so it can never
     * raise a rebuild request; unsubscribes in dispose.
     */
    private static final class QuietCellState extends State<QuietCell> {
        private final Tracked tracked;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final Consumer<String> listener = newValue -> deliveries.incrementAndGet();
        private ValueSource subscribedTo;

        QuietCellState(Tracked tracked) {
            this.tracked = tracked;
        }

        @Override
        protected void initState() {
            subscribedTo = getWidget().source;
            subscribedTo.subscribe(listener);
        }

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder().text(getWidget().id + ":quiet:" + deliveries.get()).build();
        }

        @Override
        protected void dispose() {
            tracked.disposeCount++;
            if (subscribedTo != null) {
                subscribedTo.unsubscribe(listener);
                subscribedTo = null;
            }
            super.dispose();
        }
    }
}
