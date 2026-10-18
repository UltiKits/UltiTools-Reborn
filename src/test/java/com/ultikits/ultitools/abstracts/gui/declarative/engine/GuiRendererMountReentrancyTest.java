package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import com.ultikits.ultitools.utils.TestHelper;

import mc.obliviate.inventory.Gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rebuild requests raised while {@link GuiRenderer} is building a frame must be coalesced into
 * one follow-up frame, never executed re-entrantly against a partially mounted Element tree
 * (frozen case {@code 2f791fb2270f5f79ad9dda6980078a1017a16129bc53190799c3c6d2982b86f5}, issue
 * #679).
 * <p>
 * The first frame runs through {@link GuiScheduler#runOnMainThread}, so the scheduler's last
 * frame time is still zero and a request raised during that frame passes the scheduler's
 * "run now" branch. Without a renderer-owned guard, a {@code setState} inside a child's
 * {@code initState} therefore re-enters {@code performBuild} while the parent Container has not
 * yet recorded that child, and reconciliation mounts a second State for the same widget.
 * <p>
 * Reproductions use {@code new GuiScheduler(mockPlugin, 1000L)}: only the request raised while
 * the last frame time is zero runs inline, so the pre-fix re-entrance happens exactly once and
 * fails as an assertion rather than recursing. Deferred frames are drained with
 * {@code performTicks}.
 */
@DisplayName("GuiRenderer coalesces rebuild requests raised during a build (case 2f791fb2)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GuiRendererMountReentrancyTest {

    /** Long enough that only a request raised while the last frame time is zero runs inline. */
    private static final long DEFERRING_INTERVAL_MS = 1000L;

    /** Enough ticks for a frame deferred by the 1000 ms interval (about 20 ticks) to run. */
    private static final int DRAIN_TICKS = 25;

    private ServerMock server;
    private Plugin mockPlugin;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        // DeclarativeGui-based tests use the default GuiScheduler, whose plugin is this mocked
        // instance; executeFrame logs frame failures through plugin.getLogger(), so stub it
        // before the mock is published.
        TestHelper.mockUltiToolsInstance(ultiTools -> when(ultiTools.getLogger())
                .thenReturn(Logger.getLogger(GuiRendererMountReentrancyTest.class.getName())));
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Task 1 (tracer): one Container child calling setState inside initState
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A Container child calling setState in initState gets exactly one State, owned by the Container")
    void containerChildSetStateInInitStateCreatesExactlyOneState() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        Supplier<Widget> supplier = () -> Container.builder()
                .child(new ProbeWidget(probe, () -> new ReadyLabelState("child")))
                .build();

        renderer.initialize(supplier, rootContext(1));

        ProbeState first = probe.statesOf("child").get(0);
        Element owner = first.owner;
        assertNotNull(owner, "the child State must observe its owning Container from build(context)");
        String observed = "createState=" + probe.createCount("child")
                + ", liveStates=" + probe.liveCount("child")
                + ", containerChildren=" + owner.getChildren().size()
                + ", ownsFirstState=" + owner.getChildren().contains(first.element)
                + ", slot0=" + displayNameAt(gui, 0);
        assertEquals("createState=1, liveStates=1, containerChildren=1, ownsFirstState=true, slot0=ready",
                observed,
                "a setState inside initState during mount must be recorded and replayed as one "
                        + "follow-up frame, not re-enter performBuild and mount a second State for "
                        + "the same child");
    }

    // ------------------------------------------------------------------
    // Task 2: synchronous subscriptions, first click, coalescing, sibling
    // requests, root lifecycle order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A value source that delivers on subscribe is subscribed once, its value shown, and released at close")
    void synchronousSubscriptionValueSubscribesOnceAndShowsValue() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        ValueSource source = new ValueSource("v1");
        Supplier<Widget> supplier = () -> Container.builder()
                .child(new ProbeWidget(probe, () -> new SubscribingState("sub", source)))
                .build();

        renderer.initialize(supplier, rootContext(1));
        String afterOpen = "listeners=" + source.listenerCount()
                + ", createState=" + probe.createCount("sub")
                + ", slot0=" + displayNameAt(gui, 0);

        source.emit("v2");
        drain();
        String afterEmit = "listeners=" + source.listenerCount()
                + ", createState=" + probe.createCount("sub")
                + ", slot0=" + displayNameAt(gui, 0);

        renderer.dispose();
        String afterClose = "listeners=" + source.listenerCount()
                + ", disposeCounts=" + probe.disposeCounts("sub");

        assertEquals("open[listeners=1, createState=1, slot0=v1] "
                        + "emit[listeners=1, createState=1, slot0=v2] "
                        + "close[listeners=0, disposeCounts=[1]]",
                "open[" + afterOpen + "] emit[" + afterEmit + "] close[" + afterClose + "]",
                "a value delivered synchronously on subscribe inside initState must produce one "
                        + "subscription, show the value, repaint later values and be released at close");
    }

    @Test
    @DisplayName("The first click after opening keeps its state change on the next frame")
    void firstClickStateChangeSurvivesNextFrame() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        Supplier<Widget> supplier = () -> Container.builder()
                .child(new ProbeWidget(probe, () -> new CounterState("counter")))
                .build();

        renderer.initialize(supplier, rootContext(1));
        renderer.handleClick(clickAt(0));
        drain();

        assertEquals("n=1", displayNameAt(gui, 0),
                "the first click must reach the State that stays mounted, so its increment "
                        + "survives the next frame");
    }

    @Test
    @DisplayName("Requests raised by two children during one build coalesce into exactly one follow-up frame")
    void requestsDuringOneBuildCoalesceIntoOneFollowUpFrame() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        AtomicInteger supplierCalls = new AtomicInteger();
        Supplier<Widget> supplier = () -> {
            supplierCalls.incrementAndGet();
            return Container.builder()
                    .child(new ProbeWidget(probe, () -> new RequestInInitState("a", 0)))
                    .child(new ProbeWidget(probe, () -> new RequestInInitState("b", 1)))
                    .build();
        };

        renderer.initialize(supplier, rootContext(1));
        int afterOpen = supplierCalls.get();
        drain();
        int afterDrain = supplierCalls.get();

        assertEquals("afterOpen=2, afterDrain=2, createState=2",
                "afterOpen=" + afterOpen + ", afterDrain=" + afterDrain
                        + ", createState=" + (probe.createCount("a") + probe.createCount("b")),
                "both initState requests raised during the first build must coalesce into one "
                        + "follow-up frame (initial build plus one), with one State per child");
    }

    @Test
    @DisplayName("A request for a sibling already built in this frame is honoured after the build")
    void requestForAlreadyBuiltSiblingIsHonouredAfterBuild() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        SiblingHolder holder = new SiblingHolder();
        // Children in walk order: A renders the shared text; C switches its own child to D when
        // B asks it to; B (mounted last) makes C dirty. The renderer's rebuild walk visits A
        // first, then rebuilds C, which mounts D; D's initState updates the shared text and
        // requests a rebuild of A, which this frame has already walked past.
        Supplier<Widget> supplier = () -> Container.builder()
                .child(new ProbeWidget(probe, () -> new SharedTextState("a", holder)))
                .child(new ProbeWidget(probe, () -> new SwitchingState("c", holder)))
                .child(new ProbeWidget(probe, () -> new SwitchTriggerState("b", holder)))
                .build();

        renderer.initialize(supplier, rootContext(1));
        drain();

        assertEquals("updated", displayNameAt(gui, 0),
                "a rebuild request raised during a build for an element the build has already "
                        + "walked past must be replayed after the build, not dropped");
    }

    @Test
    @DisplayName("A root StatefulWidget whose initState calls setState builds before any didUpdateWidget")
    void rootStatefulWidgetFirstBuildPrecedesDidUpdateWidget() {
        TestGui gui = newGui(1);
        GuiRenderer renderer = newRenderer(gui, DEFERRING_INTERVAL_MS);
        Probe probe = new Probe();
        Supplier<Widget> supplier = () -> new ProbeWidget(probe, () -> new ReadyLabelState("root"));

        renderer.initialize(supplier, rootContext(1));
        drain();

        List<String> lifecycle = probe.lifecycleOf("root");
        int firstBuild = lifecycle.indexOf("build");
        int firstUpdate = lifecycle.indexOf("didUpdateWidget");
        assertEquals("start=[createState, initState, build], updateBeforeFirstBuild=false, createState=1",
                "start=" + lifecycle.subList(0, Math.min(3, lifecycle.size()))
                        + ", updateBeforeFirstBuild=" + (firstUpdate >= 0 && firstUpdate < firstBuild)
                        + ", createState=" + probe.createCount("root"),
                "the root State must complete its first build before any didUpdateWidget; full "
                        + "lifecycle: " + lifecycle);
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

    private BuildContext rootContext(int rows) {
        return BuildContext.root(player, "mount-reentrancy-gui", rows);
    }

    private TestGui newGui(int rows) {
        TestGui gui = new TestGui(player, "mount-reentrancy-gui", "Test", rows);
        gui.setInventory(Bukkit.createInventory(gui, rows * 9));
        return gui;
    }

    private GuiRenderer newRenderer(Gui gui, long frameIntervalMs) {
        return new GuiRenderer(gui, player, new GuiScheduler(mockPlugin, frameIntervalMs));
    }

    private static String displayNameAt(Gui gui, int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        if (stack == null) {
            return "<empty>";
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? "<no meta>" : meta.getDisplayName();
    }

    /**
     * Minimal concrete {@link Gui}; {@code Gui} is abstract only to force subclassing.
     */
    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
        }
    }

    /**
     * Per-test lifecycle registry. Every {@link ProbeState} reports createState, initState,
     * build, didUpdateWidget and dispose under its logical child id.
     */
    private static final class Probe {
        private final List<String> events = new ArrayList<>();
        private final List<ProbeState> states = new ArrayList<>();

        void created(ProbeState state) {
            states.add(state);
            event(state.id, "createState");
        }

        void event(String id, String name) {
            events.add(id + ":" + name);
        }

        List<ProbeState> statesOf(String id) {
            List<ProbeState> result = new ArrayList<>();
            for (ProbeState state : states) {
                if (state.id.equals(id)) {
                    result.add(state);
                }
            }
            return result;
        }

        int createCount(String id) {
            return statesOf(id).size();
        }

        List<Integer> disposeCounts(String id) {
            List<Integer> counts = new ArrayList<>();
            for (ProbeState state : statesOf(id)) {
                counts.add(state.disposeCount);
            }
            return counts;
        }

        List<String> lifecycleOf(String id) {
            List<String> result = new ArrayList<>();
            String prefix = id + ":";
            for (String event : events) {
                if (event.startsWith(prefix)) {
                    result.add(event.substring(prefix.length()));
                }
            }
            return result;
        }

        int liveCount(String id) {
            int live = 0;
            for (ProbeState state : statesOf(id)) {
                if (state.isMounted() && state.disposeCount == 0) {
                    live++;
                }
            }
            return live;
        }
    }

    @FunctionalInterface
    private interface StateFactory {
        ProbeState create();
    }

    /**
     * A StatefulWidget whose State comes from a per-test factory and is registered with the
     * probe the moment the framework asks for it.
     */
    private static final class ProbeWidget extends StatefulWidget {
        private final Probe probe;
        private final StateFactory factory;

        ProbeWidget(Probe probe, StateFactory factory) {
            this.probe = probe;
            this.factory = factory;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            ProbeState state = factory.create();
            state.probe = probe;
            probe.created(state);
            return state;
        }
    }

    /**
     * Records its lifecycle and, from {@code build(context)}, its own Element
     * ({@code context.getParentElement()}) and that Element's parent (the owner).
     */
    private abstract static class ProbeState extends State<ProbeWidget> {
        final String id;
        Probe probe;
        Element element;
        Element owner;
        int disposeCount;

        ProbeState(String id) {
            this.id = id;
        }

        @Override
        protected void initState() {
            super.initState();
            probe.event(id, "initState");
            onInit();
        }

        @Override
        protected void didUpdateWidget(ProbeWidget oldWidget) {
            probe.event(id, "didUpdateWidget");
        }

        @Override
        public Widget build(BuildContext context) {
            probe.event(id, "build");
            element = context.getParentElement();
            owner = element == null ? null : element.getParent();
            return render();
        }

        @Override
        protected void dispose() {
            probe.event(id, "dispose");
            disposeCount++;
            onDispose();
            super.dispose();
        }

        protected void onInit() {
            // hook
        }

        protected void onDispose() {
            // hook
        }

        protected abstract Widget render();
    }

    /** Sets its label to "ready" from initState through setState; renders it at slot 0. */
    private static final class ReadyLabelState extends ProbeState {
        private String label = "pending";

        ReadyLabelState(String id) {
            super(id);
        }

        @Override
        protected void onInit() {
            setState(() -> label = "ready");
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text(label).slot(0).build();
        }
    }

    /**
     * Subscribes in initState to a source that delivers its current value synchronously; the
     * listener calls setState. Unsubscribes in dispose.
     */
    private static final class SubscribingState extends ProbeState {
        private final ValueSource source;
        private String label = "none";
        private Consumer<String> listener;

        SubscribingState(String id, ValueSource source) {
            super(id);
            this.source = source;
        }

        @Override
        protected void onInit() {
            listener = value -> setState(() -> label = value);
            source.subscribe(listener);
        }

        @Override
        protected void onDispose() {
            source.unsubscribe(listener);
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text(label).slot(0).build();
        }
    }

    /** Calls setState in initState; its button at slot 0 increments a counter on click. */
    private static final class CounterState extends ProbeState {
        private int count = -1;

        CounterState(String id) {
            super(id);
        }

        @Override
        protected void onInit() {
            setState(() -> count = 0);
        }

        @Override
        protected Widget render() {
            return TextButton.builder()
                    .text("n=" + count)
                    .slot(0)
                    .onClick(() -> setState(() -> count++))
                    .build();
        }
    }

    /** Calls setState in initState; renders its id at the given slot. */
    private static final class RequestInInitState extends ProbeState {
        private final int slot;
        private boolean initialised;

        RequestInInitState(String id, int slot) {
            super(id);
            this.slot = slot;
        }

        @Override
        protected void onInit() {
            setState(() -> initialised = true);
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text(initialised ? id : "uninitialised").slot(slot).build();
        }
    }

    /** Shared mutable state for the sibling-request control. */
    private static final class SiblingHolder {
        private String text = "initial";
        private SharedTextState shared;
        private SwitchingState switching;
    }

    /** Child A: renders the holder's text at slot 0. */
    private static final class SharedTextState extends ProbeState {
        private final SiblingHolder holder;

        SharedTextState(String id, SiblingHolder holder) {
            super(id);
            this.holder = holder;
        }

        @Override
        protected void onInit() {
            holder.shared = this;
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text(holder.text).slot(0).build();
        }
    }

    /** Child C: renders a leaf until switched, then a StatefulWidget D that requests A. */
    private static final class SwitchingState extends ProbeState {
        private final SiblingHolder holder;
        private boolean switched;

        SwitchingState(String id, SiblingHolder holder) {
            super(id);
            this.holder = holder;
        }

        @Override
        protected void onInit() {
            holder.switching = this;
        }

        @Override
        protected Widget render() {
            if (!switched) {
                return TextButton.builder().text("c").slot(1).build();
            }
            return new ProbeWidget(probe, () -> new SiblingRequesterState("d", holder));
        }
    }

    /** Child B: asks C to switch from its initState. */
    private static final class SwitchTriggerState extends ProbeState {
        private final SiblingHolder holder;

        SwitchTriggerState(String id, SiblingHolder holder) {
            super(id);
            this.holder = holder;
        }

        @Override
        protected void onInit() {
            SwitchingState target = holder.switching;
            target.setState(() -> target.switched = true);
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text("b").slot(3).build();
        }
    }

    /** D: from initState, updates the shared text and requests a rebuild of A. */
    private static final class SiblingRequesterState extends ProbeState {
        private final SiblingHolder holder;

        SiblingRequesterState(String id, SiblingHolder holder) {
            super(id);
            this.holder = holder;
        }

        @Override
        protected void onInit() {
            holder.shared.setState(() -> holder.text = "updated");
        }

        @Override
        protected Widget render() {
            return TextButton.builder().text("d").slot(2).build();
        }
    }

    /** A value source that invokes a new listener immediately with its current value. */
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
}
