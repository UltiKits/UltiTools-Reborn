package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplay;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemSlotArea;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.SlotContainer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.mockito.Mockito.when;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Timeout(30)
class GuiRendererGridSnapshotTest {
    @Test
    void setStateEmitsSecondSnapshotChangingOnlyItsSlot() {
        Player viewer = mock(Player.class);
        List<Grid> frames = new ArrayList<>();
        Counter counter = new Counter();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Snapshots"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> Container.builder().children(counter,
                    ItemDisplay.builder(ItemSpec.builder(Key.key("minecraft:stone")).build()).slot(1).build()).build(),
                    BuildContext.root(viewer, "snapshots", 1));
            counter.state.increment();
            clock.advanceTicks(1);
            assertThat(frames).hasSize(2);
            Grid first = frames.get(0);
            Grid next = frames.get(1);
            assertThat(first.item(GridSlot.ofIndex(0)).orElseThrow().icon().amount()).isEqualTo(1);
            assertThat(next.item(GridSlot.ofIndex(0)).orElseThrow().icon().amount()).isEqualTo(2);
            assertThat(next.item(GridSlot.ofIndex(1))).isEqualTo(first.item(GridSlot.ofIndex(1)));
            assertThat(next.items()).hasSize(2);
            renderer.dispose();
        }
    }

    @Test
    void invalidAndDuplicateNodesAreSkippedWithoutLosingTheRestOfTheFrame() {
        Player viewer = mock(Player.class);
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(9);
        List<Grid> frames = new ArrayList<>();
        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record); }
            @Override public void flush() { /* records are synchronous */ }
            @Override public void close() { /* no owned resources */ }
        };
        Logger logger = Logger.getLogger(GuiRenderer.class.getName());
        logger.addHandler(handler);
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Validation"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            ItemSpec icon = ItemSpec.builder(Key.key("minecraft:stone")).build();
            renderer.initialize(() -> Container.builder().children(
                    ItemDisplay.builder(icon).slot(-1).build(),
                    ItemDisplay.builder(icon).slot(9).build(),
                    ItemDisplay.builder(icon).slot(54).build(),
                    ItemSlotArea.builder(container).count(1).startSlot(0).build(),
                    ItemSlotArea.builder(container).count(1).startSlot(1).build(),
                    ItemDisplay.builder(icon).slot(2).build()).build(), BuildContext.root(viewer, "validation", 1));
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).bindings()).containsOnlyKeys(GridSlot.ofIndex(0));
            assertThat(frames.get(0).items()).containsOnlyKeys(GridSlot.ofIndex(2));
            assertThat(warnings).hasSize(4).allSatisfy(record ->
                    assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING));
            renderer.dispose();
        } finally { logger.removeHandler(handler); }
    }

    @Test
    void replacingBindingsReleasesIndexesAndSameSlotReplacementIsAllowed() {
        Player viewer = mock(Player.class);
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(9);
        List<Grid> frames = new ArrayList<>();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Replacement"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> Container.builder().children(
                    ItemSlotArea.builder(container).count(1).startSlot(0).build(),
                    ItemDisplay.builder(ItemSpec.builder(Key.key("minecraft:stone")).build()).slot(0).build(),
                    ItemSlotArea.builder(container).count(1).startSlot(1).mode(BindingMode.VIEW).build(),
                    ItemSlotArea.builder(container).count(1).startSlot(1).mode(BindingMode.EDITABLE).build()).build(),
                    BuildContext.root(viewer, "replacement", 1));
            assertThat(frames.get(0).bindings()).containsOnlyKeys(GridSlot.ofIndex(1));
            assertThat(frames.get(0).binding(GridSlot.ofIndex(1)).orElseThrow().mode()).isEqualTo(BindingMode.EDITABLE);
            assertThat(frames.get(0).items()).containsOnlyKeys(GridSlot.ofIndex(0));
            renderer.dispose();
        }
    }

    @Test
    void repeatedRendererRequestsInTheSameTickBuildOnlyOnce() {
        Player viewer = mock(Player.class);
        AtomicInteger builds = new AtomicInteger();
        List<Grid> frames = new ArrayList<>();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Coalesced"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> {
                builds.incrementAndGet(); return ItemDisplay.builder(ItemSpec.builder(Key.key("minecraft:stone")).build()).build();
            }, BuildContext.root(viewer, "coalesced", 1));
            renderer.scheduleBuild(); renderer.scheduleBuild();
            clock.advanceTicks(1);
            assertThat(builds).hasValue(2);
            assertThat(frames).hasSize(2);
            renderer.dispose();
        }
    }

    @Test
    void failedFrameRetainsThePreviousWholeSnapshotAndLaterFrameRecovers() {
        Player viewer = mock(Player.class);
        AtomicInteger builds = new AtomicInteger();
        List<Grid> frames = new ArrayList<>();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Atomic"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> {
                int build = builds.incrementAndGet();
                if (build == 2) { throw new IllegalStateException("second build fails"); }
                return ItemDisplay.builder(ItemSpec.builder(Key.key("minecraft:stone")).amount(build).build()).build();
            }, BuildContext.root(viewer, "atomic", 1));
            Grid first = frames.get(0);
            renderer.scheduleBuild(); clock.advanceTicks(1);
            assertThat(frames).containsExactly(first);
            renderer.scheduleBuild(); clock.advanceTicks(1);
            assertThat(frames).hasSize(2);
            assertThat(frames.get(0).item(GridSlot.ofIndex(0)).orElseThrow().icon().amount()).isEqualTo(1);
            assertThat(frames.get(1).item(GridSlot.ofIndex(0)).orElseThrow().icon().amount()).isEqualTo(3);
            renderer.dispose();
        }
    }

    private static final class Counter extends StatefulWidget {
        private CounterState state;
        @Override public State<? extends StatefulWidget> createState() {
            state = new CounterState();
            return state;
        }
    }

    private static final class CounterState extends State<Counter> {
        private int count = 1;
        void increment() { setState(() -> count++); }
        @Override public Widget build(BuildContext context) {
            return ItemDisplay.builder(ItemSpec.builder(Key.key("minecraft:diamond")).amount(count).build()).build();
        }
    }
}
