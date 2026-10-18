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
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private Plugin mockPlugin;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
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
    // helpers
    // ------------------------------------------------------------------

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
}
