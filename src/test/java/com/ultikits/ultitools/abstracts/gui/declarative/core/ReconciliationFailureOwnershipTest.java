package com.ultikits.ultitools.abstracts.gui.declarative.core;

import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;

import mc.obliviate.inventory.Gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A reconciliation that throws part-way through must leave every mounted child owned by its
 * parent, release every child whose own mount failed, and let closing the GUI dispose each owned
 * State exactly once (frozen case
 * {@code d92e7d20b28ecee218c1ad5c8d4b9d1aec89a4367ba9b4d1cb435b8da8d14511}, issue #679).
 * <p>
 * The reported shape: frame 1 mounts {@code a} (type X), {@code b} (type Y) and {@code c}
 * (type X); frame 2 replaces {@code a} with {@code a2} (type Z, so the old element is unmounted
 * and a new one mounted) and then {@code b}'s {@code didUpdateWidget} throws. Before the fix the
 * replacement lived only in a discarded local list, so the parent kept listing the unmounted
 * {@code a} and closing never disposed {@code a2}.
 * <p>
 * Bounded failing frames: no State in this class ever raises a rebuild request (no
 * {@code setState} anywhere, and value-source listeners only register and count), so a failing
 * frame records nothing to replay and every test reaches its assertions in bounded time.
 */
@DisplayName("Child ownership survives reconciliation failures (case d92e7d20)")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReconciliationFailureOwnershipTest {

    private ServerMock server;
    private Plugin mockPlugin;
    private Player player;
    private FrameErrorCounter frameErrors;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        mockPlugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        frameErrors = new FrameErrorCounter();
        mockPlugin.getLogger().addHandler(frameErrors);
    }

    @AfterEach
    void tearDown() {
        mockPlugin.getLogger().removeHandler(frameErrors);
        MockBukkitHelper.safeUnmock();
    }

    // ------------------------------------------------------------------
    // Task 1 (tracer): replacement, then a failing sibling, through close
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A replacement made before a failing sibling stays owned and is disposed once at close")
    void replacementBeforeFailingSiblingIsOwnedAndDisposedOnceOnClose() {
        Lifecycle life = new Lifecycle();
        AtomicInteger frame = new AtomicInteger(1);
        GuiRenderer renderer = newRenderer(newGui());
        renderer.initialize(() -> Container.builder()
                .children(frame.get() == 1 ? reportedShape(life, null) : failingShape(life, null, false))
                .build(), rootContext());

        frame.set(2);
        renderer.scheduleBuild();
        int failingFrameErrors = frameErrors.count();
        renderer.dispose();

        assertEquals("frameErrors=1 disposed{a=[1], a2=[1], b=[1], c=[1]}",
                "frameErrors=" + failingFrameErrors + " disposed" + life.disposeCounts("a", "a2", "b", "c"),
                "the replacement a2 mounted before b's didUpdateWidget threw must stay owned by the "
                        + "Container, so closing disposes it exactly once; every other State is "
                        + "created once and disposed once");
    }

    @Test
    @DisplayName("After a failed update the parent lists only its still-mounted owned children")
    void failedReconciliationListsOnlyMountedOwnedChildren() {
        Lifecycle life = new Lifecycle();
        Element root = mountDirect(Container.builder().children(reportedShape(life, null)).build());
        List<Element> before = new ArrayList<>(root.getChildren());
        Element oldA = before.get(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> root.update(Container.builder().children(failingShape(life, null, false)).build()));

        assertEquals("failure=didUpdateWidget failed for b "
                        + "children=[new:a2:mounted, old1:b:mounted, old2:c:mounted] "
                        + "oldA[listed=false, mounted=false]",
                "failure=" + failure.getMessage()
                        + " children=" + describe(root.getChildren(), before)
                        + " oldA[listed=" + containsIdentity(root.getChildren(), oldA)
                        + ", mounted=" + oldA.isMounted() + "]",
                "after b's didUpdateWidget throws, the Container must own the mounted replacement "
                        + "a2, the original b and the original c, in that order, and must not list "
                        + "the unmounted a");
    }

    // ------------------------------------------------------------------
    // shapes
    // ------------------------------------------------------------------

    /** Frame 1 of the reported shape: a (X), b (Y), c (X) at slots 0, 1, 2. */
    private static List<Widget> reportedShape(Lifecycle life, ValueSource source) {
        return Arrays.asList(
                new TypeX(new Spec(life, "a").slot(0).subscribe(source)),
                new TypeY(new Spec(life, "b").slot(1).subscribe(source)),
                new TypeX(new Spec(life, "c").slot(2).subscribe(source)));
    }

    /**
     * Frame 2 of the reported shape: a replaced by a2 (Z), then b (Y) whose didUpdateWidget
     * throws unless {@code recovered}, then c (X) unchanged. With {@code recovered} it is the
     * same tree with b's failure cleared (the recovery frame).
     */
    private static List<Widget> failingShape(Lifecycle life, ValueSource source, boolean recovered) {
        Spec b = new Spec(life, "b").slot(1).subscribe(source);
        return Arrays.asList(
                new TypeZ(new Spec(life, "a2").slot(0).subscribe(source)),
                new TypeY(recovered ? b : b.failUpdate()),
                new TypeX(new Spec(life, "c").slot(2).subscribe(source)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private BuildContext rootContext() {
        return BuildContext.root(player, "reconciliation-ownership-gui", 1);
    }

    private TestGui newGui() {
        TestGui gui = new TestGui(player, "reconciliation-ownership-gui", "Test", 1);
        gui.setInventory(Bukkit.createInventory(gui, 9));
        return gui;
    }

    /** Zero interval: every scheduleBuild on the test (main) thread runs its frame inline. */
    private GuiRenderer newRenderer(Gui gui) {
        return new GuiRenderer(gui, player, new GuiScheduler(mockPlugin, 0L));
    }

    /** Mounts a layout widget directly as a root element, as ElementReconciliationTest does. */
    private Element mountDirect(Widget widget) {
        Element element = widget.createElement();
        element.assignContext(BuildContext.root(player, "reconciliation-ownership-direct", 6));
        element.mount(null);
        return element;
    }

    private static boolean containsIdentity(List<Element> elements, Element target) {
        for (Element element : elements) {
            if (element == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * Describes each element as {@code origin:id:mounted}, where origin is {@code oldN} for the
     * element at index N of {@code before} (identity) and {@code new} otherwise.
     */
    private static String describe(List<Element> elements, List<Element> before) {
        List<String> parts = new ArrayList<>();
        for (Element element : elements) {
            String origin = "new";
            for (int i = 0; i < before.size(); i++) {
                if (before.get(i) == element) {
                    origin = "old" + i;
                }
            }
            parts.add(origin + ":" + idOf(element) + ":" + (element.isMounted() ? "mounted" : "unmounted"));
        }
        return parts.toString();
    }

    private static String idOf(Element element) {
        Widget widget = element.getWidget();
        return widget instanceof LifecycleWidget ? ((LifecycleWidget) widget).spec.id : widget.toString();
    }

    private static String displayNameAt(Gui gui, int slot) {
        ItemStack stack = gui.getInventory().getItem(slot);
        if (stack == null) {
            return "-";
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? "?" : meta.getDisplayName();
    }

    /** Minimal concrete {@link Gui}; {@code Gui} is abstract only to force subclassing. */
    private static final class TestGui extends Gui {
        TestGui(Player player, String id, String title, int rows) {
            super(player, id, title, rows);
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

    /**
     * Per-test lifecycle registry: every State created for a logical id, in creation order, with
     * its own initState and dispose counts.
     */
    private static final class Lifecycle {
        private final Map<String, List<LifecycleState>> states = new LinkedHashMap<>();

        void created(LifecycleState state) {
            List<LifecycleState> list = states.get(state.id);
            if (list == null) {
                list = new ArrayList<>();
                states.put(state.id, list);
            }
            list.add(state);
        }

        List<LifecycleState> statesOf(String id) {
            List<LifecycleState> list = states.get(id);
            return list == null ? new ArrayList<LifecycleState>() : list;
        }

        /**
         * Per id, the dispose count of each State created for it, in creation order; the list
         * length is therefore the createState count.
         */
        String disposeCounts(String... ids) {
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < ids.length; i++) {
                List<Integer> counts = new ArrayList<>();
                for (LifecycleState state : statesOf(ids[i])) {
                    counts.add(state.disposeCount);
                }
                out.append(i == 0 ? "" : ", ").append(ids[i]).append('=').append(counts);
            }
            return out.append('}').toString();
        }

        int createCount(String id) {
            return statesOf(id).size();
        }
    }

    /**
     * A value source that only records its listeners; it never delivers a value, so a
     * subscription can never raise a rebuild request.
     */
    private static final class ValueSource {
        private final List<Object> listeners = new ArrayList<>();

        void subscribe(Object listener) {
            listeners.add(listener);
        }

        void unsubscribe(Object listener) {
            listeners.remove(listener);
        }

        int listenerCount() {
            return listeners.size();
        }
    }

    /** Per-widget behaviour: logical id, slot, optional key, subscription and failure points. */
    private static final class Spec {
        private final Lifecycle life;
        private final String id;
        private int slot;
        private SlotKey key;
        private ValueSource source;
        private boolean failInit;
        private boolean failUpdate;
        private boolean failDispose;

        Spec(Lifecycle life, String id) {
            this.life = life;
            this.id = id;
        }

        Spec slot(int value) {
            this.slot = value;
            return this;
        }

        Spec key(String value) {
            this.key = SlotKey.of(value);
            return this;
        }

        Spec subscribe(ValueSource value) {
            this.source = value;
            return this;
        }

        Spec failInit() {
            this.failInit = true;
            return this;
        }

        Spec failUpdate() {
            this.failUpdate = true;
            return this;
        }

        Spec failDispose() {
            this.failDispose = true;
            return this;
        }
    }

    /**
     * Base StatefulWidget for the fixture; the concrete subclasses only differ in runtime type,
     * which is what decides reuse versus replacement.
     */
    private abstract static class LifecycleWidget extends StatefulWidget {
        final Spec spec;

        LifecycleWidget(Spec spec) {
            super(spec.key);
            this.spec = spec;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            return new LifecycleState(spec.life, spec.id);
        }
    }

    private static final class TypeX extends LifecycleWidget {
        TypeX(Spec spec) {
            super(spec);
        }
    }

    private static final class TypeY extends LifecycleWidget {
        TypeY(Spec spec) {
            super(spec);
        }
    }

    private static final class TypeZ extends LifecycleWidget {
        TypeZ(Spec spec) {
            super(spec);
        }
    }

    private static final class TypeW extends LifecycleWidget {
        TypeW(Spec spec) {
            super(spec);
        }
    }

    /**
     * Records its own dispose count, subscribes in initState when its widget names a source
     * (unsubscribing in dispose), and throws from initState, didUpdateWidget or dispose when its
     * widget asks it to. Never calls setState.
     */
    private static final class LifecycleState extends State<LifecycleWidget> {
        private final String id;
        private final Object listener = new Object();
        private ValueSource subscribedTo;
        private int disposeCount;

        LifecycleState(Lifecycle life, String id) {
            this.id = id;
            life.created(this);
        }

        @Override
        protected void initState() {
            Spec spec = getWidget().spec;
            if (spec.source != null) {
                subscribedTo = spec.source;
                subscribedTo.subscribe(listener);
            }
            if (spec.failInit) {
                throw new IllegalStateException("init failed");
            }
        }

        @Override
        protected void didUpdateWidget(LifecycleWidget oldWidget) {
            if (getWidget().spec.failUpdate) {
                throw new IllegalStateException("didUpdateWidget failed for " + id);
            }
        }

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder().text(id).slot(getWidget().spec.slot).build();
        }

        @Override
        protected void dispose() {
            disposeCount++;
            if (subscribedTo != null) {
                subscribedTo.unsubscribe(listener);
                subscribedTo = null;
            }
            super.dispose();
            if (getWidget().spec.failDispose) {
                throw new IllegalStateException("dispose failed");
            }
        }
    }
}
