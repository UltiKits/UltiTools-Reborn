package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import mc.obliviate.inventory.Gui;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;

/**
 * DeclarativeGui is the base class of the declarative GUI framework.
 * <p>
 * It extends obliviate-invs' {@link Gui}, layering declarative UI capability on top. A subclass
 * only needs to implement {@link #build(BuildContext)}, returning a Widget tree.
 * <p>
 * <b>Usage example:</b>
 * <pre>{@code
 * public class ShopPage extends DeclarativeGui {
 *     private final List<ItemStack> items;
 *
 *     public ShopPage(Player player, List<ItemStack> items) {
 *         super(player, "shop", "Shop", 6);
 *         this.items = items;
 *     }
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Column.builder()
 *             .children(
 *                 // Title row
 *                 Center.builder()
 *                     .child(TextDisplay.title("Item Shop"))
 *                     .build(),
 *
 *                 // Item grid
 *                 GridView.builder()
 *                     .items(items)
 *                     .itemBuilder(item -> ItemButton.builder()
 *                         .item(item)
 *                         .onClick(() -> buyItem(item))
 *                         .build())
 *                     .build(),
 *
 *                 // Pagination controls
 *                 Row.builder()
 *                     .children(
 *                         PrevPageButton.builder().build(),
 *                         PageIndicator.builder().build(),
 *                         NextPageButton.builder().build()
 *                     )
 *                     .build()
 *             )
 *             .build();
 *     }
 *
 *     private void buyItem(ItemStack item) {
 *         // Purchase logic
 *         player.sendMessage("Bought " + item.getType());
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public abstract class DeclarativeGui extends Gui {

    @NotNull
    protected final String id;
    private final GuiRenderer renderer;
    private boolean initialized = false;

    /**
     * Creates a DeclarativeGui.
     *
     * @param player the player
     * @param id     the GUI ID
     * @param title  the title
     * @param rows   the row count
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, int rows) {
        super(player, id, title, rows);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * Creates a DeclarativeGui.
     *
     * @param player        the player
     * @param id            the GUI ID
     * @param title         the title
     * @param inventoryType the inventory type
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, 
                          @NotNull InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * Creates a DeclarativeGui with a Component title.
     *
     * @param player the player
     * @param id     the GUI ID
     * @param title  the title (Component)
     * @param rows   the row count
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull Component title, int rows) {
        super(player, id, title, rows);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * Creates a DeclarativeGui with a Component title.
     *
     * @param player        the player
     * @param id            the GUI ID
     * @param title         the title (Component)
     * @param inventoryType the inventory type
     */
    public DeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull Component title, 
                          @NotNull InventoryType inventoryType) {
        super(player, id, title, inventoryType);
        this.id = id;
        this.renderer = new GuiRenderer(this, player);
    }

    /**
     * Builds the Widget tree.
     * <p>
     * Subclasses must implement this method, returning the Widget tree describing the UI. This
     * method is called again every time the state changes.
     *
     * @param context the build context, carrying the player, GUI configuration, and similar info
     * @return the Widget tree
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    @Override
    public final void onOpen(@NotNull InventoryOpenEvent event) {
        if (!initialized) {
            // Initialize the renderer. What is passed is a Supplier, not a built Widget --
            // GuiRenderer calls build(context) again at the start of every frame, so that a
            // state change actually re-derives the Widget tree (D-09 item 1; GuiRenderer's half
            // of this change came from plan 05-11's Task 1, and updating the call site here
            // belongs to Task 2's scope, done early to keep the build compiling).
            BuildContext context = BuildContext.root(player, getId(), getSize() / 9);
            renderer.initialize(() -> build(context), context);
            initialized = true;
        }

        // Call the subclass hook
        onGuiOpen(event);
    }

    @Override
    public final void onClose(@NotNull InventoryCloseEvent event) {
        // Call the subclass hook
        onGuiClose(event);

        // Release resources
        renderer.dispose();
        initialized = false;
    }

    /**
     * This is the framework's own bounds-check site (D-09 item 4), not a post-filter hook. A
     * subclass overriding this method needs to know that obliviate-invs' {@code InvListener}
     * calls this method unconditionally, BEFORE it applies any bounds check of its own — so
     * every click reaches here, including a click in the player's own inventory. The actual
     * check happens one call down, in {@link GuiRenderer#handleClick}: it rejects (returns
     * without dispatching) any click whose raw slot falls outside the GUI's own inventory, so
     * a click on the player's own inventory can never reach a GUI handler here.
     *
     * @param event the click event
     * @return see {@link #onGuiClick(InventoryClickEvent)}
     */
    @Override
    public final boolean onClick(@NotNull InventoryClickEvent event) {
        // Pass it to the renderer -- the renderer does its own bounds check; see the javadoc
        // above and GuiRenderer.handleClick.
        renderer.handleClick(event);

        // Call the subclass hook
        return onGuiClick(event);
    }

    /**
     * Marks that a rebuild is needed.
     * <p>
     * Typically called in a subclass to trigger a UI update when data changes.
     */
    protected void markNeedsBuild() {
        if (initialized) {
            renderer.scheduleBuild();
        }
    }

    /**
     * Sets state and triggers a rebuild.
     * <p>
     * This is the Flutter-style approach to state management. Modify state inside the callback,
     * and the framework automatically triggers a rebuild.
     * <p>
     * <b>Usage example:</b>
     * <pre>{@code
     * setState(() -> {
     *     counter++;          // modify state
     *     selectedItem = item;
     * });
     * }</pre>
     *
     * @param action the state-modifying operation
     */
    protected void setState(@NotNull Runnable action) {
        action.run();
        markNeedsBuild();
    }

    /**
     * Hook method called when the GUI opens.
     * <p>
     * Subclasses may override this to perform extra initialization work.
     *
     * @param event the open event
     */
    protected void onGuiOpen(@NotNull InventoryOpenEvent event) {
        // Overridden by subclasses
    }

    /**
     * Hook method called when the GUI closes.
     * <p>
     * Subclasses may override this to perform cleanup work.
     *
     * @param event the close event
     */
    protected void onGuiClose(@NotNull InventoryCloseEvent event) {
        // Overridden by subclasses
    }

    /**
     * Hook method called on a GUI click.
     * <p>
     * Note: the click event is handled by the declarative framework first, before this method
     * is called.
     * <p>
     * The return value reads backwards, so read it carefully: returning {@code false}
     * keeps the event cancelled and the player <b>cannot</b> take the clicked item;
     * returning {@code true} lets the click through and the item <b>can</b> be taken.
     * The semantics come from obliviate-invs, whose {@code InvListener} calls
     * {@code setCancelled(false)} when {@code Gui.onClick} returns true and
     * {@code setCancelled(true)} when it returns false. The default of {@code false}
     * matches the library base class and is the safe side.
     *
     * @param event the click event
     * @return {@code false} keeps the event cancelled (default, item cannot be taken);
     *         {@code true} lets it through
     */
    protected boolean onGuiClick(@NotNull InventoryClickEvent event) {
        // Subclasses override. The default keeps the event cancelled, which is the safe side.
        return false;
    }

    /**
     * Gets the renderer.
     *
     * @return the GuiRenderer
     */
    @NotNull
    protected GuiRenderer getRenderer() {
        return renderer;
    }

    /**
     * Checks whether this has been initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the player.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }
}

