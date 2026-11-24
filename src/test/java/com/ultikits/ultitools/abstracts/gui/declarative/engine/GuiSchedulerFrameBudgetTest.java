package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.utils.MockBukkitHelper;

import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiTestHarness.SnapshotProbe;
import com.ultikits.ultitools.text.Text;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One GUI frame runs only the tasks that were queued when the frame started; a task queued while
 * the frame is draining runs on a later frame.
 * <p>
 * Without that budget, a widget whose {@code State.initState} calls {@code setState} and then
 * throws keeps the main thread inside one frame forever: every failing build records the
 * {@code setState} request, replays it from its {@code finally} as a new frame task into the same
 * queue, and the drain picks that task up again before it can return.
 * <p>
 * The probes carry a hard cap instead of relying on the test timeout: after {@link #CAP}
 * creations they stop failing, so the pre-fix drain ends and the test reports the cap instead of
 * hanging the test JVM.
 */
@DisplayName("GuiScheduler runs only the tasks queued when a frame starts")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GuiSchedulerFrameBudgetTest {

    /** Creations after which a probe stops misbehaving; reaching it means the drain was unbounded. */
    private static final int CAP = 50;

    private GuiTestHarness harness;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        harness = new GuiTestHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
        MockBukkitHelper.safeUnmock();
    }

    @Test
    @DisplayName("A task that schedules a new frame task while running runs once per frame")
    void taskQueuedDuringDrainRunsOnLaterFrame() {
        GuiScheduler scheduler = new GuiScheduler(harness.scheduler(), player);
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean capHit = new AtomicBoolean();
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (runs.incrementAndGet() >= CAP) {
                capHit.set(true);
                return;
            }
            scheduler.scheduleFrame(self[0]);
        };

        scheduler.scheduleFrame(self[0]);
        int afterSchedule = runs.get();
        harness.tick();
        int afterTick1 = runs.get();
        harness.tick();
        int afterTick2 = runs.get();

        assertEquals("afterSchedule=0, afterTick1=1, afterTick2=2, capHit=false",
                "afterSchedule=" + afterSchedule + ", afterTick1=" + afterTick1
                        + ", afterTick2=" + afterTick2 + ", capHit=" + capHit.get(),
                "a task queued while a frame drains must wait for the next frame, not extend "
                        + "the current one");
    }

    @Test
    @DisplayName("Repeated requests during a drain coalesce and never run on the same stack")
    void repeatedRequestsDuringDrainStayDeferred() {
        GuiScheduler scheduler = new GuiScheduler(harness.scheduler(), player);
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean capHit = new AtomicBoolean();
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (runs.incrementAndGet() >= CAP) {
                capHit.set(true);
                return;
            }
            scheduler.scheduleFrame(self[0]);
            scheduler.scheduleFrame(self[0]);
        };

        scheduler.scheduleFrame(self[0]);
        int afterSchedule = runs.get();
        harness.tick();
        int afterTick1 = runs.get();
        harness.tick();
        int afterTick2 = runs.get();

        assertEquals("afterSchedule=0, afterTick1=1, afterTick2=2, capHit=false",
                "afterSchedule=" + afterSchedule + ", afterTick1=" + afterTick1
                        + ", afterTick2=" + afterTick2 + ", capHit=" + capHit.get(),
                "repeated requests raised during a drain must coalesce into one next-tick task "
                        + "without extending the current stack");
    }

    @Test
    @DisplayName("A widget whose initState calls setState and throws is retried once per frame, not in a tight loop")
    void throwingInitStateWithSetStateDoesNotLoopInsideOneFrame() {
        SnapshotProbe gui = new SnapshotProbe();
        GuiRenderer renderer = new GuiRenderer(Text.plain("Test"), 1, new GuiScheduler(harness.scheduler(), player), gui);
        AtomicInteger creations = new AtomicInteger();
        AtomicBoolean capHit = new AtomicBoolean();
        Supplier<Widget> supplier = () -> Container.builder()
                .child(new BrokenWidget(creations, capHit))
                .build();

        String initializeOutcome;
        try {
            renderer.initialize(supplier, BuildContext.root(player, "frame-budget-gui", 1));
            initializeOutcome = "returned";
        } catch (BrokenInitStateException e) {
            initializeOutcome = "threw widget failure";
        }
        int afterInitialize = creations.get();
        harness.tick();
        int afterTick1 = creations.get();
        harness.tick();
        int afterTick2 = creations.get();
        renderer.dispose();

        assertEquals("initialize=threw widget failure, afterInitialize=1, afterTick1=2, afterTick2=3, capHit=false",
                "initialize=" + initializeOutcome + ", afterInitialize=" + afterInitialize
                        + ", afterTick1=" + afterTick1 + ", afterTick2=" + afterTick2
                        + ", capHit=" + capHit.get(),
                "a broken widget must be retried at most once per frame; the main thread must "
                        + "leave the frame instead of rebuilding it in a loop");
    }


    /** The module programming error under test. */
    private static final class BrokenInitStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BrokenInitStateException() {
            super("initState failed after setState (deliberate test failure)");
        }
    }

    /** A StatefulWidget whose State calls setState in initState and then throws, up to the cap. */
    private static final class BrokenWidget extends StatefulWidget {
        private final AtomicInteger creations;
        private final AtomicBoolean capHit;

        BrokenWidget(AtomicInteger creations, AtomicBoolean capHit) {
            this.creations = creations;
            this.capHit = capHit;
        }

        @Override
        public State<? extends StatefulWidget> createState() {
            if (creations.incrementAndGet() >= CAP) {
                capHit.set(true);
                return new HealthyState();
            }
            return new BrokenState();
        }
    }

    /** Calls setState in initState, then throws. */
    private static final class BrokenState extends State<BrokenWidget> {
        private boolean requested;

        @Override
        protected void initState() {
            super.initState();
            setState(() -> requested = true);
            throw new BrokenInitStateException();
        }

        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder().text(Text.plain(requested ? "requested" : "pending")).slot(0).build();
        }
    }

    /** Returned once the cap is reached, so an unbounded drain ends instead of hanging the JVM. */
    private static final class HealthyState extends State<BrokenWidget> {
        @Override
        public Widget build(BuildContext context) {
            return TextButton.builder().text(Text.plain("cap")).slot(0).build();
        }
    }
}
