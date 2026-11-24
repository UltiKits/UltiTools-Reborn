package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiRenderer;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiLogCapture;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiScheduler;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiTestHarness;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.SlotKey;
import com.ultikits.ultitools.ui.ContainerSpec;
import com.ultikits.ultitools.ui.ContainerState;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.ultikits.ultitools.scheduling.ManualScheduler;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.BindingMode;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Timeout(30)
class ItemSlotAreaTest {
    @Test
    void actualContainerContentsAndLifetimeSurvivePaging() {
        MockBukkitHelper.ensureCleanState();
        Player viewer = MockBukkit.mock().addPlayer();
        try (GuiTestHarness harness = new GuiTestHarness()) {
            List<ItemStack> initial = new ArrayList<>(Collections.nCopies(18, ItemStack.empty()));
            initial.set(0, new ItemStack(Material.STONE, 8));
            initial.set(9, new ItemStack(Material.DIAMOND, 3));
            SlotContainer container = harness.ui().container(ContainerSpec.template(18).baseline(initial).build());
            List<Grid> frames = new ArrayList<>();
            AtomicInteger first = new AtomicInteger();
            GuiRenderer renderer = new GuiRenderer(Text.plain("Pages"), 1,
                    new GuiScheduler(harness.scheduler(), viewer), frames::add);
            renderer.initialize(() -> ItemSlotArea.builder(container).firstIndex(first.get()).count(9).build(),
                    BuildContext.root(viewer, "pages", 1));
            GridSession session = harness.ui().show(viewer, frames.get(0)); harness.drain();
            List<ItemStack> before = container.contents();
            first.set(9); renderer.scheduleBuild(); harness.tick();
            session.update(frames.get(1)); harness.drain();
            assertThat(harness.fixture().shown(session, GridSlot.ofIndex(0))).isEqualTo(initial.get(9));
            assertThat(container.contents()).isEqualTo(before);
            assertThat(container.state()).isEqualTo(ContainerState.EDITABLE);
            assertThat(container.isViewed()).isTrue();
            first.set(0); renderer.scheduleBuild(); harness.tick();
            session.update(frames.get(2)); harness.drain();
            assertThat(harness.fixture().shown(session, GridSlot.ofIndex(0))).isEqualTo(initial.get(0));
            assertThat(container.contents()).isEqualTo(before);
            renderer.dispose(); session.close(); harness.drain();
        } finally { MockBukkitHelper.safeUnmock(); }
    }

    @Test
    void defaultsKeysAndElementUpdatesKeepOnlyTheNewBindingLeaves() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(9);
        SlotKey key = SlotKey.of("area");
        ItemSlotArea area = ItemSlotArea.builder(container).key(key).build();
        assertThat(area.getKey()).isSameAs(key);
        assertThat(area.getCount()).isEqualTo(9);
        assertThat(area.getFirstIndex()).isZero();
        assertThat(area.getStartSlot()).isZero();
        assertThat(area.getMode()).isEqualTo(BindingMode.VIEW);
        RenderObjectElement element = area.createElement();
        element.assignContext(BuildContext.root(mock(Player.class), "area", 2)); element.mount(null);
        RenderNode root = element.getRenderNode();
        RenderNode previous = root.getChildren().get(0);
        element.update(ItemSlotArea.builder(container).key("area").firstIndex(2).count(2).startSlot(8).mode(BindingMode.VIEW).build());
        element.performRebuild();
        assertThat(root.getChildren()).hasSize(2);
        assertThat(root.getChildren().get(0).getSlotIndex()).isEqualTo(8);
        assertThat(root.getChildren().get(1).getSlotIndex()).isEqualTo(9);
        assertThat(root.getChildren().get(0).getBinding().index()).isEqualTo(2);
        assertThat(root.getChildren().get(1).getBinding().mode()).isEqualTo(BindingMode.VIEW);
        assertThat(previous.getParent()).isNull();
        element.unmount();
        assertThat(ItemSlotArea.builder(container).count(0).key((SlotKey) null).build().getKey()).isNull();
    }

    @Test
    void invalidRangesAndNullArgumentsAreRejected() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(18);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).firstIndex(-1).count(1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).count(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).firstIndex(18).count(1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).firstIndex(18).count(0).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).startSlot(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).startSlot(54).count(0).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).startSlot(53).count(2).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).firstIndex(Integer.MAX_VALUE).count(2).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ItemSlotArea.builder(container).mode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void partialLastPageBindsOnlyExistingIndexesWithoutDiagnostics() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(10);
        Player viewer = mock(Player.class);
        AtomicInteger first = new AtomicInteger();
        List<Grid> frames = new ArrayList<>();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine();
             GuiLogCapture rendererLog = new GuiLogCapture(GuiRenderer.class);
             GuiLogCapture schedulerLog = new GuiLogCapture(GuiScheduler.class)) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Partial"), 1,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> ItemSlotArea.builder(container).firstIndex(first.get()).count(9).build(),
                    BuildContext.root(viewer, "partial", 1));
            first.set(9); renderer.scheduleBuild(); clock.advanceTicks(1);
            assertThat(frames).hasSize(2);
            assertThat(frames.get(1).bindings()).containsOnlyKeys(GridSlot.ofIndex(0));
            assertThat(frames.get(1).binding(GridSlot.ofIndex(0)).orElseThrow().index()).isEqualTo(9);
            assertThat(frames.get(1).items()).isEmpty();
            assertThat(rendererLog.records()).isEmpty();
            assertThat(schedulerLog.records()).isEmpty();
            renderer.dispose();
        }
    }

    @Test
    void emptyContainerProducesNoBindingLeaves() {
        SlotContainer container = mock(SlotContainer.class);
        RenderObjectElement element = ItemSlotArea.builder(container).count(9).build().createElement();
        element.assignContext(BuildContext.root(mock(Player.class), "empty", 1)); element.mount(null);
        assertThat(element.getRenderNode().getChildren()).isEmpty();
        element.unmount();
    }

    @Test
    void pagingChangesBindingsWithoutMutatingContainer() {
        SlotContainer container = mock(SlotContainer.class);
        when(container.size()).thenReturn(18);
        Player viewer = mock(Player.class);
        List<Grid> frames = new ArrayList<>();
        AtomicInteger first = new AtomicInteger();
        try (ManualScheduler.Engine clock = new ManualScheduler.Engine()) {
            GuiRenderer renderer = new GuiRenderer(Text.plain("Pages"), 2,
                    new GuiScheduler(clock.scheduler("UltiTools"), viewer), frames::add);
            renderer.initialize(() -> ItemSlotArea.builder(container).firstIndex(first.get()).count(9)
                    .startSlot(4).mode(BindingMode.EDITABLE).build(), BuildContext.root(viewer, "pages", 2));
            clearInvocations(container);
            first.set(9);
            renderer.scheduleBuild();
            clock.advanceTicks(1);
            assertThat(frames).hasSize(2);
            for (int offset = 0; offset < 9; offset++) {
                GridSlot slot = GridSlot.ofIndex(4 + offset);
                assertThat(frames.get(0).binding(slot).orElseThrow().index()).isEqualTo(offset);
                assertThat(frames.get(1).binding(slot).orElseThrow().index()).isEqualTo(9 + offset);
                assertThat(frames.get(1).binding(slot).orElseThrow().container()).isSameAs(container);
            }
            verify(container, org.mockito.Mockito.atLeastOnce()).size();
            verifyNoMoreInteractions(container);
            renderer.dispose();
        }
    }
}
