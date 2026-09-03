package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import mc.obliviate.inventory.Gui;
import mc.obliviate.inventory.Icon;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * GuiRenderer is responsible for coordinating Widget tree construction, diffing, and Inventory
 * updates.
 * <p>
 * It is the declarative framework's core engine, with the main responsibilities of:
 * <ul>
 * <li>managing the Element tree's lifecycle</li>
 * <li>scheduling rebuilds (with frame coalescing)</li>
 * <li>running the diff algorithm</li>
 * <li>applying changes to the actual Inventory</li>
 * </ul>
 *
 * <p><strong>Workflow:</strong></p>
 *
 * <pre>
 * 1. Initialize: createRootElement → build the RenderNode tree
 * 2. Render: diff → apply to Inventory
 * 3. Update: setState → scheduleBuild → rebuild → diff → apply
 * </pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GuiRenderer {

    private final Gui gui;
    private final Player player;
    private final GuiScheduler scheduler;
    private final RenderNodeDiffer differ;

    @Nullable
    private Element rootElement;
    @Nullable
    private List<RenderNode> lastRenderNodes;
    @Nullable
    private Supplier<Widget> widgetSupplier;
    @Nullable
    private BuildContext rootContext;

    // Slot-to-click-handler mapping -- the key is "slot index within the top GUI", the same
    // space used by the key handleClick() looks up once event.getRawSlot()'s bounds check
    // passes (D-09 item 4). This is the sole record of slot ownership: there is no second
    // mapping that looks things up via event.getSlot() directly and could drift from this one.
    private final Map<Integer, Consumer<InventoryClickEvent>> clickHandlers = new HashMap<>();

    /**
     * Creates a GuiRenderer.
     *
     * @param gui    the associated Gui instance
     * @param player the player
     */
    public GuiRenderer(@NotNull Gui gui, @NotNull Player player) {
        this(gui, player, new GuiScheduler());
    }

    /**
     * Creates a GuiRenderer with the given scheduler.
     *
     * @param gui       the associated Gui instance
     * @param player    the player
     * @param scheduler the scheduler
     */
    public GuiRenderer(@NotNull Gui gui, @NotNull Player player, @NotNull GuiScheduler scheduler) {
        this.gui = gui;
        this.player = player;
        this.scheduler = scheduler;
        this.differ = new RenderNodeDiffer();
    }

    /**
     * Initializes the renderer.
     * <p>
     * D-09 item 1: no longer accepts a Widget built once up front; instead it accepts a
     * {@link Supplier} that is called again at the start of every frame, re-deriving the Widget
     * tree from the current state -- this is where the framework's {@code UI = f(state)} tagline
     * first actually holds. Creating the root Element is deferred until inside the first
     * {@link #performBuild()} call, so the supplier is called exactly once on the first frame too,
     * rather than once here and again inside performBuild().
     *
     * @param widgetSupplier the source re-evaluated every frame to produce the Widget tree
     * @param context        the build context (used for the root Element; later frames' rebuilds
     *                       and remounts reuse the same one)
     */
    public void initialize(@NotNull Supplier<Widget> widgetSupplier, @NotNull BuildContext context) {
        this.widgetSupplier = widgetSupplier;
        this.rootContext = context;
        scheduler.runOnMainThread(this::performBuild);
    }

    /**
     * Schedules a rebuild.
     * <p>
     * Uses frame coalescing, so multiple calls within a short window trigger only one rebuild.
     */
    public void scheduleBuild() {
        scheduler.scheduleFrame(this::performBuild);
    }

    /**
     * Runs a rebuild immediately (must be called on the main thread).
     * <p>
     * D-09 item 1: {@link #widgetSupplier} is called again at the start of every frame, and its
     * result is fed to the already-mounted root Element ({@link Element#update}, whose base-class
     * implementation now marks it dirty -- see D-09 item 2), rather than being built once at
     * {@link #initialize} time and never re-derived again.
     */
    private void performBuild() {
        if (!scheduler.isOnMainThread()) {
            scheduler.runOnMainThread(this::performBuild);
            return;
        }

        if (widgetSupplier == null) {
            return;
        }

        // Re-derive the Widget tree from the current state -- called exactly once per frame
        Widget widget = widgetSupplier.get();

        if (rootElement == null) {
            mountRoot(widget);
        } else if (rootElement.canUpdate(widget)) {
            rootElement.update(widget);
        } else {
            // The supplier returned a Widget incompatible with the mounted root's type
            // (T-05-52): remount explicitly. Element.update()'s IllegalArgumentException must
            // not be allowed to escape into the scheduled frame, which would leave the
            // Inventory half-written.
            rootElement.unmount();
            mountRoot(widget);
        }

        // Rebuild the Element tree (only the dirty subtrees are rebuilt)
        rebuildElement(rootElement);

        // Collect RenderNodes -- a snapshot, not a live reference; see collectRenderNodesRecursive
        List<RenderNode> newRenderNodes = collectRenderNodes(rootElement);

        // Diff
        List<RenderNode> oldNodes = lastRenderNodes != null ? lastRenderNodes : Collections.emptyList();
        DiffResult diffResult = differ.diff(oldNodes, newRenderNodes);

        // Apply the changes
        applyDiff(diffResult);

        // Save the current state (a snapshot -- not retroactively changed by the next frame's
        // in-place mutation of the live RenderNodes)
        lastRenderNodes = newRenderNodes;

        // Update the click handlers
        updateClickHandlers(newRenderNodes);
    }

    /**
     * Creates and mounts the root Element.
     * <p>
     * Shared by both the first build and the "supplier returned an incompatible type" case.
     *
     * @param widget the root Widget
     */
    private void mountRoot(@NotNull Widget widget) {
        BuildContext context = Objects.requireNonNull(rootContext,
                "rootContext must be assigned by initialize() before performBuild() can mount a root");
        rootElement = widget.createElement();
        rootElement.assignContext(context);
        // The root Element's markNeedsBuild()/markChildNeedsBuild() bubbling terminates
        // in a no-op once it reaches an Element with no parent — that IS the mounted root.
        // Without this registration, State.setState() on a nested StatefulWidget mutates
        // its field and marks the tree dirty, but nothing ever calls scheduleBuild(), so
        // the mutation never reaches the Inventory. This is what closes the "setState ->
        // automatic repaint" half of D-09/WIRE-02 for the documented CounterButton shape.
        rootElement.setRootBuildScheduler(this::scheduleBuild);
        rootElement.mount(null);
    }

    /**
     * Recursively rebuilds the Element tree.
     *
     * @param element the Element to rebuild
     */
    private void rebuildElement(@NotNull Element element) {
        if (element.isDirty()) {
            element.performRebuild();
        }

        for (Element child : element.getChildren()) {
            rebuildElement(child);
        }
    }

    /**
     * Collects every RenderNode (post-order traversal).
     *
     * @param element the root Element
     * @return the list of RenderNodes
     */
    @NotNull
    private List<RenderNode> collectRenderNodes(@NotNull Element element) {
        List<RenderNode> nodes = new ArrayList<>();
        collectRenderNodesRecursive(element, nodes);
        return nodes;
    }

    private void collectRenderNodesRecursive(@NotNull Element element, @NotNull List<RenderNode> nodes) {
        // Collect the children first
        for (Element child : element.getChildren()) {
            collectRenderNodesRecursive(child, nodes);
        }

        // If this is a RenderObjectElement, collect a snapshot of its RenderNode
        //
        // D-09 item 3: getRenderNode() always returns the same instance for a given Element
        // (RenderObjectElement only creates it on first access, and mutates it in place after
        // that). If this live reference were stuffed directly into lastRenderNodes, the "old
        // node" and "new node" RenderNodeDiffer.diff() sees on the next frame would be the same
        // object -- a comparison that is always equal to itself, so the diff would never see any
        // change. .copy() (a method already written in RenderNode.java, previously with zero
        // callers) snapshots this frame's state here, so a later frame's in-place mutation of the
        // live node cannot retroactively rewrite this snapshot.
        if (element instanceof RenderObjectElement) {
            RenderNode node = ((RenderObjectElement) element).getRenderNode();
            if (node != null) {
                nodes.add(node.copy());
            }
        }
    }

    /**
     * Applies the diff result to the Inventory.
     *
     * @param diffResult the diff result
     */
    private void applyDiff(@NotNull DiffResult diffResult) {
        if (diffResult.isEmpty()) {
            return;
        }

        // 1. Handle removals
        for (RenderNode removed : diffResult.getRemoved()) {
            clearSlot(removed.getSlotIndex());
        }

        // 2. Handle moves (clear the original position first)
        for (DiffResult.RenderNodeMove move : diffResult.getMoved()) {
            clearSlot(move.getFromSlot());
        }

        // 3. Handle additions
        for (RenderNode added : diffResult.getAdded()) {
            setSlot(added.getSlotIndex(), added.getIcon());
        }

        // 4. Handle updates
        for (DiffResult.RenderNodeUpdate update : diffResult.getUpdated()) {
            setSlot(update.getSlotIndex(), update.getNewNode().getIcon());
        }

        // 5. Handle moves (set the new position)
        for (DiffResult.RenderNodeMove move : diffResult.getMoved()) {
            setSlot(move.getToSlot(), move.getNode().getIcon());
        }
    }

    /**
     * Updates the click-handler mapping.
     *
     * @param renderNodes the current list of RenderNodes
     */
    private void updateClickHandlers(@NotNull List<RenderNode> renderNodes) {
        clickHandlers.clear();

        for (RenderNode node : renderNodes) {
            if (node.getClickHandler() != null) {
                clickHandlers.put(node.getSlotIndex(), node.getClickHandler());
            }
        }
    }

    /**
     * Handles a click event.
     * <p>
     * D-09 item 4: obliviate-invs' {@code InvListener.onClick} unconditionally calls
     * {@code Gui.onClick(event)} (i.e. {@link DeclarativeGui#onClick}, which forwards here)
     * before applying its own {@code getRawSlot()} check -- this was confirmed from the jar's
     * bytecode, not assumed. That means this method sees <b>every</b> click, including the
     * player clicking their own inventory; it cannot assume the library has already filtered
     * anything out.
     * <p>
     * This uses {@link InventoryClickEvent#getRawSlot()} rather than
     * {@link InventoryClickEvent#getSlot()} for the bounds check: {@code getRawSlot()} is an
     * absolute index relative to the whole combined view (top GUI + player inventory) -- the top
     * GUI occupies {@code [0, gui.getSize())} and the player's own inventory starts at
     * {@code gui.getSize()} -- whereas {@code getSlot()} is numbered independently relative to
     * "whichever Inventory was clicked": the top GUI and the player inventory each count from 0
     * on their own, so the same value (say, 4) could be both the GUI's 4th slot and the player
     * inventory's 4th slot. Clicking outside the window entirely (e.g. dropping an item outside
     * it) makes Bukkit report a rawSlot of -999, which this same bounds check also rejects
     * without throwing.
     * <p>
     * {@link #clickHandlers} is the sole record of this click routing -- it is populated by
     * {@link #updateClickHandlers} using {@code RenderNode.getSlotIndex()} (the same "slot within
     * the top GUI" numbering space), and this method looks things up in that same space; there is
     * no second record that could drift from it.
     *
     * @param event the click event
     */
    public void handleClick(@NotNull InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= gui.getSize()) {
            // Out of bounds: the player clicked their own inventory, or clicked entirely outside
            // the window (rawSlot == -999). Neither case is dispatched, and neither throws.
            return;
        }

        Consumer<InventoryClickEvent> handler = clickHandlers.get(rawSlot);
        if (handler != null) {
            handler.accept(event);
        }
    }

    /**
     * Sets a slot's content.
     *
     * @param slot the slot index
     * @param icon the icon
     */
    private void setSlot(int slot, @Nullable Icon icon) {
        if (slot < 0 || slot >= gui.getSize()) {
            return;
        }
        if (icon != null) {
            gui.addItem(slot, icon);
        }
    }

    /**
     * Clears a slot.
     *
     * @param slot the slot index
     */
    private void clearSlot(int slot) {
        if (slot < 0 || slot >= gui.getSize()) {
            return;
        }
        // Clear it using an AIR ItemStack
        gui.addItem(slot, new Icon(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR)));
    }

    /**
     * Destroys the renderer and releases its resources.
     */
    public void dispose() {
        scheduler.cancelAll();

        if (rootElement != null) {
            rootElement.setRootBuildScheduler(null);
            rootElement.unmount();
            rootElement = null;
        }

        lastRenderNodes = null;
        clickHandlers.clear();
        widgetSupplier = null;
        rootContext = null;
    }

    /**
     * Gets the associated Gui instance.
     *
     * @return the Gui instance
     */
    @NotNull
    public Gui getGui() {
        return gui;
    }

    /**
     * Gets the scheduler.
     *
     * @return the GuiScheduler
     */
    @NotNull
    public GuiScheduler getScheduler() {
        return scheduler;
    }
}