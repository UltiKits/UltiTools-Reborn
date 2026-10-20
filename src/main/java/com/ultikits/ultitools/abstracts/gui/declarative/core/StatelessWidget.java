package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

/**
 * StatelessWidget is a Widget that needs no mutable state.
 * <p>
 * It builds the UI entirely from the configuration parameters supplied at construction time.
 * When the configuration changes, a new StatelessWidget instance is created, and the framework
 * automatically diffs and updates whatever needs to change.
 * <p>
 * <b>When to use it:</b>
 * <ul>
 *   <li>purely presentational UI (e.g. titles, icons, static text)</li>
 *   <li>UI whose state is entirely controlled by its parent Widget</li>
 *   <li>side-effect-free, purely functional components</li>
 * </ul>
 *
 * <p><strong>Usage example:</strong></p>
 * <pre>{@code
 * public class ItemRow extends StatelessWidget {
 *     private final ItemStack item;
 *     private final String name;
 *     private final Runnable onClick;
 *     
 *     public ItemRow(ItemStack item, String name, Runnable onClick) {
 *         this.item = item;
 *         this.name = name;
 *         this.onClick = onClick;
 *     }
 *     
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Row.builder()
 *             .children(
 *                 ItemDisplay.builder(item).build(),
 *                 TextButton.builder()
 *                     .text(name)
 *                     .onClick(onClick)
 *                     .build()
 *             )
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see StatefulWidget
 * @see Widget
 */
public abstract class StatelessWidget extends Widget {

    /**
     * Creates a new StatelessWidget.
     */
    protected StatelessWidget() {
        super();
    }

    /**
     * Creates a new StatelessWidget with the given key.
     *
     * @param key the key used to stably identify this Widget
     */
    protected StatelessWidget(SlotKey key) {
        super(key);
    }

    /**
     * Builds this Widget's subtree.
     * <p>
     * This method is called:
     * <ul>
     *   <li>when the Widget is first created</li>
     *   <li>when the parent Widget rebuilds</li>
     *   <li>when a depended-upon piece of data changes</li>
     * </ul>
     * <p>
     * <b>Important:</b> the build method should be a pure function with no side effects.
     * Do not mutate state, perform I/O, or register listeners inside build.
     *
     * @param context the build context, carrying the player, GUI configuration, and similar info
     * @return the child Widget tree
     */
    @NotNull
    public abstract Widget build(@NotNull BuildContext context);

    @Override
    @NotNull
    public Element createElement() {
        return new StatelessElement(this);
    }
}
