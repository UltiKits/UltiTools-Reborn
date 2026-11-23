package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderDepthGuard;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderNode;
import com.ultikits.ultitools.abstracts.gui.declarative.core.RenderObjectElement;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.ui.Grid;
import com.ultikits.ultitools.ui.GridSlot;
import com.ultikits.ultitools.ui.SlotBinding;
import com.ultikits.ultitools.ui.SlotContainer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the element tree and emits a complete immutable {@link Grid} after each successful build.
 * No inventory, click table or backend diff is kept here. A failed build never calls the sink,
 * so the backend retains the previous complete picture.
 * @since 6.2.0
 */
public class GuiRenderer {
    private static final Logger LOGGER = Logger.getLogger(GuiRenderer.class.getName());
    private final Text title;
    private final int rows;
    private final GuiScheduler scheduler;
    private final Consumer<Grid> sink;
    private final Runnable frameTask = this::performBuild;
    private @Nullable Element rootElement;
    private @Nullable Supplier<Widget> widgetSupplier;
    private @Nullable BuildContext rootContext;
    private boolean buildInProgress;
    private boolean rebuildRequestedDuringBuild;

    /**
     * Creates a snapshot renderer; the caller supplies the first frame to UiService and later frames to its session.
     * @param title deferred screen title
     * @param rows the fixed row count, 1..6
     * @param scheduler the viewer's frame scheduler
     * @param sink receiver of complete frames
     */
    public GuiRenderer(Text title, int rows, GuiScheduler scheduler, Consumer<Grid> sink) {
        Grid.builder(title, rows); // Validate before any element is mounted.
        this.title = title;
        this.rows = rows;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Builds the first frame now on the owning thread, or submits it to that thread.
     * The supplier is evaluated exactly once per frame, including the first.
     * @param widgetSupplier the current widget tree
     * @param context root build context, reused after remounts
     */
    public void initialize(@NotNull Supplier<Widget> widgetSupplier, @NotNull BuildContext context) {
        this.widgetSupplier = Objects.requireNonNull(widgetSupplier, "widgetSupplier");
        this.rootContext = Objects.requireNonNull(context, "context");
        scheduler.runOnMainThread(frameTask);
    }

    /** Requests one later frame; requests during a build are replayed after it exits, never re-entrantly. */
    public void scheduleBuild() {
        if (scheduler.isOnMainThread() && buildInProgress) {
            rebuildRequestedDuringBuild = true;
            return;
        }
        scheduler.scheduleFrame(frameTask);
    }

    private void performBuild() {
        if (!scheduler.isOnMainThread()) {
            scheduler.runOnMainThread(frameTask);
            return;
        }
        if (widgetSupplier == null) {
            return;
        }
        if (buildInProgress) {
            rebuildRequestedDuringBuild = true;
            return;
        }
        buildInProgress = true;
        try {
            Widget widget = widgetSupplier.get();
            if (rootElement == null) {
                mountRoot(widget);
            } else if (rootElement.canUpdate(widget)) {
                rootElement.update(widget);
            } else {
                rootElement.unmount();
                mountRoot(widget);
            }
            rebuildElement(rootElement);
            SnapshotBuilder snapshot = new SnapshotBuilder(title, rows);
            for (RenderNode node : collectRenderNodes(rootElement)) {
                snapshot.add(node);
            }
            sink.accept(snapshot.grid.build());
        } finally {
            buildInProgress = false;
            if (rebuildRequestedDuringBuild) {
                rebuildRequestedDuringBuild = false;
                scheduleBuild();
            }
        }
    }

    private void mountRoot(Widget widget) {
        BuildContext context = Objects.requireNonNull(rootContext, "initialize must supply a root context");
        rootElement = widget.createElement();
        rootElement.assignContext(context);
        // Register before mount: initState/subscriptions may synchronously request the next frame.
        rootElement.setRootBuildScheduler(this::scheduleBuild);
        rootElement.mount(null);
    }

    private void rebuildElement(Element element) {
        RenderDepthGuard.check("GuiRenderer.rebuildElement", RenderDepthGuard.depthOf(element));
        if (element.isDirty()) {
            element.performRebuild();
        }
        for (Element child : element.getChildren()) {
            rebuildElement(child);
        }
    }

    private List<RenderNode> collectRenderNodes(Element element) {
        List<RenderNode> nodes = new ArrayList<>();
        collectRenderNodesRecursive(element, nodes);
        return nodes;
    }

    private void collectRenderNodesRecursive(Element element, List<RenderNode> nodes) {
        RenderDepthGuard.check("GuiRenderer.collectRenderNodesRecursive", RenderDepthGuard.depthOf(element));
        for (Element child : element.getChildren()) {
            collectRenderNodesRecursive(child, nodes);
        }
        if (element instanceof RenderObjectElement renderElement) {
            for (RenderNode leaf : renderElement.getRenderNode().getAllLeaves()) {
                nodes.add(leaf.copy());
            }
        }
    }

    /** Cancels submitted work, detaches build notifications and releases every owned element. */
    public void dispose() {
        scheduler.cancelAll();
        widgetSupplier = null;
        rootContext = null;
        rebuildRequestedDuringBuild = false;
        if (rootElement != null) {
            Element previous = rootElement;
            rootElement = null;
            previous.setRootBuildScheduler(null);
            previous.unmount();
        }
    }

    /** @return the viewer's frame scheduler */
    public @NotNull GuiScheduler getScheduler() { return scheduler; }

    /** Collects valid slots without letting one invalid node reject a complete frame. */
    private static final class SnapshotBuilder {
        private final Grid.Builder grid;
        private final int size;
        private final Map<Integer, SlotBinding> bindings = new HashMap<>();
        private final Map<SlotContainer, Map<Integer, Integer>> indexes = new IdentityHashMap<>();

        SnapshotBuilder(Text title, int rows) {
            grid = Grid.builder(title, rows);
            size = rows * Grid.COLUMNS;
        }

        void add(RenderNode node) {
            if (node.getItem() == null && node.getBinding() == null) {
                return;
            }
            int slot = node.getSlotIndex();
            if (slot < 0 || slot >= size) {
                LOGGER.warning("Skipping declarative node outside the grid: " + node);
                return;
            }
            SlotBinding binding = node.getBinding();
            if (binding != null && invalidBinding(binding, slot)) {
                LOGGER.warning("Skipping declarative node with an invalid or duplicate container index: " + node);
                return;
            }
            removePreviousBinding(slot);
            if (binding == null) {
                grid.set(GridSlot.ofIndex(slot), node.getItem());
            } else {
                grid.bind(GridSlot.ofIndex(slot), binding.container(), binding.index(), binding.mode());
                bindings.put(slot, binding);
                indexes.computeIfAbsent(binding.container(), ignored -> new HashMap<>()).put(binding.index(), slot);
            }
        }

        private boolean invalidBinding(SlotBinding binding, int slot) {
            if (binding.index() < 0 || binding.index() >= binding.container().size()) {
                return true;
            }
            Map<Integer, Integer> used = indexes.get(binding.container());
            Integer previous = used == null ? null : used.get(binding.index());
            return previous != null && previous.intValue() != slot;
        }

        private void removePreviousBinding(int slot) {
            SlotBinding previous = bindings.remove(slot);
            if (previous != null) {
                indexes.get(previous.container()).remove(previous.index());
            }
        }
    }
}
