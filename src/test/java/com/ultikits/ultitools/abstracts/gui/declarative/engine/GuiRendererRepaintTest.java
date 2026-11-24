package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthExceededException;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthGuard;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.engine.GuiTestHarness.SnapshotProbe;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.GridView;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplay;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.ItemDisplayElement;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.TextButton;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.GridSession;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Repaint and lifecycle guards now observe immutable snapshots instead of inventory writes. */
@Timeout(30)
class GuiRendererRepaintTest {
    private Player player;
    private GuiTestHarness harness;
    @BeforeEach void setUp() {
        MockBukkitHelper.ensureCleanState();
        player = MockBukkit.mock().addPlayer();
        harness = new GuiTestHarness();
    }
    @AfterEach void tearDown() { harness.close(); MockBukkitHelper.safeUnmock(); }
    private static ItemSpec icon(Material material) { return ItemSpec.builder(material).build(); }
    private BuildContext context() { return BuildContext.root(player, "repaint", 1); }
    private GuiRenderer renderer(SnapshotProbe sink) {
        return new GuiRenderer(Text.plain("Test"), 1, new GuiScheduler(harness.scheduler(), player), sink);
    }

    @Test void loreOnlyChangeRepaintsTheInventory() {
        SnapshotProbe sink = new SnapshotProbe();
        GuiRenderer renderer = renderer(sink);
        AtomicReference<String> lore = new AtomicReference<>("v1");
        renderer.initialize(() -> ItemDisplay.builder(icon(Material.DIAMOND).toBuilder()
                .lore(List.of(Text.plain(lore.get()))).build()).build(), context());
        assertThat(sink.item(0).lore()).containsExactly(Text.plain("v1"));
        lore.set("v2"); renderer.scheduleBuild(); harness.tick();
        assertThat(sink.item(0).lore()).containsExactly(Text.plain("v2"));
    }
    @Test void displayNameOnlyChangeRepaintsTheInventory() {
        SnapshotProbe sink = new SnapshotProbe(); GuiRenderer renderer = renderer(sink);
        AtomicReference<String> name = new AtomicReference<>("Name A");
        renderer.initialize(() -> ItemDisplay.builder(icon(Material.DIAMOND).toBuilder().name(Text.plain(name.get())).build()).build(), context());
        name.set("Name B"); renderer.scheduleBuild(); harness.tick();
        assertThat(sink.label(0)).isEqualTo("Name B");
        assertThat(sink.frames().get(0).item(com.ultikits.ultitools.ui.GridSlot.ofIndex(0)).orElseThrow().icon().name()).isEqualTo(Text.plain("Name A"));
    }
    @Test void itemTypeChangeRepaintsTheInventory() {
        SnapshotProbe sink = new SnapshotProbe(); GuiRenderer renderer = renderer(sink);
        AtomicReference<Material> material = new AtomicReference<>(Material.DIAMOND);
        renderer.initialize(() -> ItemDisplay.builder(icon(material.get())).build(), context());
        material.set(Material.GOLD_INGOT); renderer.scheduleBuild(); harness.tick();
        assertThat(sink.item(0).itemType()).isEqualTo(Material.GOLD_INGOT.getKey());
    }
    @Test void noStateChangeProducesNoAdditionalWrite() {
        Page page = new Page(harness, player, () -> ItemDisplay.builder(icon(Material.DIAMOND)).build());
        harness.open(page);
        page.change(() -> { /* the resulting complete snapshot is unchanged */ }); harness.drain();
        assertThat(page.builds).hasValue(2);
        assertThat(harness.backend().updates()).isEmpty();
    }
    @Test void supplierIsInvokedExactlyOncePerBuild() {
        SnapshotProbe sink = new SnapshotProbe(); GuiRenderer renderer = renderer(sink);
        AtomicInteger calls = new AtomicInteger();
        renderer.initialize(() -> { calls.incrementAndGet(); return ItemDisplay.builder(icon(Material.DIAMOND)).build(); }, context());
        assertThat(calls).hasValue(1);
        renderer.scheduleBuild(); harness.tick();
        assertThat(calls).hasValue(2);
    }
    @Test void updatedElementIsRebuiltUntouchedSiblingIsNot() {
        ItemDisplayElement a = spy((ItemDisplayElement) ItemDisplay.builder(icon(Material.STONE)).build().createElement());
        ItemDisplayElement b = spy((ItemDisplayElement) ItemDisplay.builder(icon(Material.DIRT)).slot(1).build().createElement());
        a.assignContext(context()); b.assignContext(context()); a.mount(null); b.mount(null);
        a.update(ItemDisplay.builder(icon(Material.STONE).toBuilder().addLore(Text.plain("changed")).build()).build());
        assertThat(a.isDirty()).isTrue(); assertThat(b.isDirty()).isFalse();
        if (a.isDirty()) { a.performRebuild(); }
        if (b.isDirty()) { b.performRebuild(); }
        verify(a).performRebuild(); verify(b, never()).performRebuild();
        a.unmount(); b.unmount();
    }
    @Test void thirdPassDetectsChangeDespiteInPlaceMutation() {
        SnapshotProbe sink = new SnapshotProbe(); GuiRenderer renderer = renderer(sink);
        AtomicReference<String> lore = new AtomicReference<>("A");
        renderer.initialize(() -> ItemDisplay.builder(icon(Material.DIAMOND).toBuilder().addLore(Text.plain(lore.get())).build()).build(), context());
        lore.set("B"); renderer.scheduleBuild(); harness.tick();
        lore.set("C"); renderer.scheduleBuild(); harness.tick();
        assertThat(sink.item(0).lore()).containsExactly(Text.plain("C"));
        assertThat(sink.frames().get(1).item(com.ultikits.ultitools.ui.GridSlot.ofIndex(0)).orElseThrow().icon().lore()).containsExactly(Text.plain("B"));
    }
    @Test void incompatibleRootTypeRemountsInstead() {
        SnapshotProbe sink = new SnapshotProbe(); GuiRenderer renderer = renderer(sink);
        AtomicReference<Widget> root = new AtomicReference<>(ItemDisplay.builder(icon(Material.DIAMOND)).build());
        renderer.initialize(root::get, context());
        root.set(TextButton.builder().text(Text.plain("Confirm")).build()); renderer.scheduleBuild(); harness.tick();
        assertThat(sink.label(0)).isEqualTo("Confirm");
        assertThat(sink.item(0).itemType()).isNotEqualTo(Material.DIAMOND.getKey());
    }
    private static Widget nested(int depth) {
        Widget widget = ItemDisplay.builder(ItemSpec.builder(net.kyori.adventure.key.Key.key("minecraft:diamond")).build()).build();
        for (int i = 0; i < depth; i++) { widget = Container.builder().child(widget).build(); }
        return widget;
    }
    @Test void deeplyNestedContainerChainThrowsNamedDepthErrorFromMountViaDirectChildPath() {
        GuiRenderer renderer = renderer(new SnapshotProbe());
        RenderDepthExceededException failure = assertThrows(RenderDepthExceededException.class,
                () -> renderer.initialize(() -> nested(RenderDepthGuard.MAX_DEPTH + 100), context()));
        assertThat(failure).hasMessageContaining("Element.mount").hasMessageContaining(String.valueOf(RenderDepthGuard.MAX_DEPTH));
    }
    @Test void deeplyNestedTreeInsideGridViewCellThrowsNamedDepthErrorFromMountViaGridPath() {
        GuiRenderer renderer = renderer(new SnapshotProbe());
        RenderDepthExceededException failure = assertThrows(RenderDepthExceededException.class,
                () -> renderer.initialize(() -> GridView.builder().child(nested(RenderDepthGuard.MAX_DEPTH + 100)).build(), context()));
        assertThat(failure).hasMessageContaining("Element.mount");
    }
    @Test void treeAtExactlyTheDepthLimitRendersNormally() {
        SnapshotProbe sink = new SnapshotProbe();
        renderer(sink).initialize(() -> nested(RenderDepthGuard.MAX_DEPTH), context());
        assertThat(sink.item(0).itemType()).isEqualTo(Material.DIAMOND.getKey());
    }
    @Test void treeOfDepthOneRendersNormally() {
        SnapshotProbe sink = new SnapshotProbe(); renderer(sink).initialize(() -> nested(1), context());
        assertThat(sink.item(0).itemType()).isEqualTo(Material.DIAMOND.getKey());
    }
    @Test void declarativeGuiSubclassRepaintsFromMutableField() {
        AtomicReference<Material> material = new AtomicReference<>(Material.DIAMOND);
        Page page = new Page(harness, player, () -> ItemDisplay.builder(icon(material.get())).build());
        GridSession session = harness.open(page);
        page.change(() -> material.set(Material.GOLD_INGOT)); harness.drain();
        assertThat(GuiTestHarness.item(session.current(), 0).itemType()).isEqualTo(Material.GOLD_INGOT.getKey());
    }
    @Test void setStateInStatefulWidgetProducesRepaint() {
        Page page = new Page(harness, player, CounterWidget::new);
        harness.open(page); harness.click(page, 0);
        assertThat(GuiTestHarness.label(harness.current(page), 0)).isEqualTo("n=1");
    }
    @Test void buildIsInvokedOnEachFrameNotJustOnce() {
        Page page = new Page(harness, player, () -> ItemDisplay.builder(icon(Material.DIAMOND)).build());
        harness.open(page); page.change(() -> { /* request a fresh build of the same state */ }); harness.drain();
        assertThat(page.builds).hasValue(2);
    }
    @Test void supplierRunsOnMainThreadEvenWhenSetStateCalledOffThread() throws Exception {
        AtomicReference<Thread> builtOn = new AtomicReference<>();
        Page page = new Page(harness, player, () -> {
            builtOn.set(Thread.currentThread()); return ItemDisplay.builder(icon(Material.DIAMOND)).build();
        });
        harness.open(page); builtOn.set(null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> page.change(() -> { /* state hand-off from a worker */ })).get(2, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        assertThat(builtOn.get()).isNull();
        harness.drain();
        assertThat(builtOn.get()).isSameAs(Thread.currentThread());
        assertThat(harness.backend().openScopes()).allSatisfy(scope -> assertThat(scope).isPresent());
    }
    @Test void closingAndReopeningProducesCorrectlyPopulatedInventory() {
        Page page = new Page(harness, player, () -> ItemDisplay.builder(icon(Material.DIAMOND)).build());
        GridSession first = harness.open(page); harness.close(page); GridSession second = harness.open(page);
        assertThat(second).isNotSameAs(first);
        assertThat(GuiTestHarness.item(second.current(), 0).itemType()).isEqualTo(Material.DIAMOND.getKey());
    }
    @Test void statefulDeclarativeGuiOwnSetStateAlsoRepaints() {
        StatefulPage page = new StatefulPage(harness, player);
        GridSession session = harness.open(page); page.bump(); harness.drain();
        assertThat(GuiTestHarness.item(session.current(), 0).itemType()).isEqualTo(Material.GOLD_INGOT.getKey());
        assertThat(page.getState().getGui()).isSameAs(page);
        assertThat(page.getState().viewer()).isSameAs(player);
    }

    private static final class Page extends DeclarativeGui {
        private final Supplier<Widget> tree;
        private final AtomicInteger builds = new AtomicInteger();
        Page(GuiTestHarness harness, Player player, Supplier<Widget> tree) {
            super(harness.ui(), harness.scheduler(), player, "repaint", Text.plain("Test"), 1); this.tree = tree;
        }
        @Override public Widget build(BuildContext context) { builds.incrementAndGet(); return tree.get(); }
        void change(Runnable action) { setState(action); }
    }
    private static final class CounterWidget extends StatefulWidget {
        @Override public State<? extends StatefulWidget> createState() { return new CounterState(); }
    }
    private static final class CounterState extends State<CounterWidget> {
        private int count;
        @Override public Widget build(BuildContext context) {
            return TextButton.builder().text(Text.plain("n=" + count)).onClick(() -> setState(() -> count++)).build();
        }
    }
    private static final class StatefulPage extends StatefulDeclarativeGui<StatefulPage.PageState> {
        StatefulPage(GuiTestHarness harness, Player player) {
            super(harness.ui(), harness.scheduler(), player, "stateful", Text.plain("Test"), 1);
        }
        @Override protected PageState createState() { return new PageState(); }
        @Override public Widget build(BuildContext context) { return ItemDisplay.builder(icon(getState().material)).build(); }
        void bump() { getState().setState(() -> getState().material = Material.GOLD_INGOT); }
        final class PageState extends StatefulDeclarativeGui<PageState>.State { private Material material = Material.DIAMOND; }
    }
}
