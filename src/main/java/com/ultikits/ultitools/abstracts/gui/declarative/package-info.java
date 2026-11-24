/**
 * Declarative Inventory GUI framework.
 * <p>
 * Built on Flutter's design philosophy, this framework provides a declarative way to build UI.
 * Core idea: <b>UI = f(state)</b>
 * <p>
 * <h2>Status (6.3.0)</h2>
 * <p>
 * <b>This package and its subpackages are still marked {@code @ApiStatus.Experimental}; the API
 * may change incompatibly in a future release.</b>
 * <p>
 * <b>Three rendering seams were fixed in 6.3.0 — {@code UI = f(state)} now holds:</b>
 * {@code GuiRenderer} re-derives the Widget tree from a {@code Supplier<Widget>} on every
 * {@code performBuild()} call, not just once from {@code open()}; {@code Element.update()}
 * marks itself dirty unconditionally; and {@code State.setState()} — including a call on a
 * nested {@code StatefulWidget} — now reliably reaches a scheduled repaint. The
 * <i>setState triggers a partial rebuild</i> bullet below now holds, and the navigation
 * subsystem that depends on it ({@code Navigator.push} / {@code pop}) is usable. Click
 * dispatch uses actions carried by immutable {@code GridItem}s; {@code UiService} dispatches
 * only actions belonging to the displayed grid, without an engine-owned inventory-event map.
 * {@code GridView} writes its computed slot onto any
 * widget type's render node(s) at render time (previously only {@code ItemDisplay} positioned
 * correctly).
 * <p>
 * <b>Why the marker stays.</b> As of this release, none of these three changes has real-server
 * feedback from a downstream module — zero adoption is no more grounds for declaring the API
 * stable than it would be for deleting it. The marker is retained for at least one more
 * release pending that feedback.
 * <p>
 * The three seams are tracked by
 * <a href="https://github.com/UltiKits/UltiTools-Reborn/issues/200">issue #200</a>.
 *
 * <h2>Main Features:</h2>
 * <ul>
 *   <li>Widget tree describes the UI structure</li>
 *   <li>Element tree manages Widget instances and their lifecycle</li>
 *   <li>RenderNode tree describes immutable Grid snapshots; the backend diffs slots</li>
 *   <li>Supports both StatelessWidget and StatefulWidget</li>
 *   <li>setState triggers a partial rebuild</li>
 *   <li>State mutations run on the calling thread; rebuilds run in the viewer's entity context.
 *       Hand off background results with the module's scheduler before mutating state.</li>
 *   <li>UiService owns screen sessions, actions and close results</li>
 * </ul>
 * <p>
 * <h2>Quick Start:</h2>
 * <pre>{@code
 * // 1. Create your GUI page
 * public class ShopPage extends DeclarativeGui {
 *     private final List<ItemSpec> items;
 *
 *     public ShopPage(UiService ui, Scheduler scheduler, Player player, List<ItemSpec> items) {
 *         super(ui, scheduler, player, "shop", Text.plain("Item Shop"), 6);
 *         this.items = items;
 *     }
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Container.builder()
 *             .children(
 *                 // Title
 *                 TextButton.builder()
 *                     .text(Text.plain("Item Shop"))
 *                     .slot(4)
 *                     .build(),
 *
 *                 // Item grid
 *                 GridView.<ItemSpec>builder()
 *                     .startSlot(10)
 *                     .columns(7)
 *                     .items(items, item -> ItemDisplay.builder(item)
 *                         .onClick(() -> buyItem(item))
 *                         .build())
 *                     .build(),
 *
 *                 // Close button
 *                 TextButton.builder()
 *                     .text(Text.plain("Close"))
 *                     .color("RED")
 *                     .slot(49)
 *                     .onClick(click -> click.session().close())
 *                     .build()
 *             )
 *             .build();
 *     }
 *
 *     private void buyItem(ItemSpec item) {
 *         // Handle purchase logic
 *     }
 * }
 *
 * // 2. Use the module's injected UiService and Scheduler on the viewer's entity thread.
 * new ShopPage(ui, scheduler, player, items).open();
 *
 * // 3. Bind an item area. The last page may contain fewer than nine indexes.
 * ItemSlotArea.builder(container).firstIndex(page * 9).count(9).build(); // VIEW by default
 * ItemSlotArea.builder(container).firstIndex(page * 9).count(9)
 *     .mode(BindingMode.EDITABLE).build(); // Moving items requires explicit opt-in
 * }</pre>
 * <p>
 * <h2>StatefulWidget Example:</h2>
 * <pre>{@code
 * public class CounterPage extends DeclarativeGui {
 *     private int count = 0;
 *
 *     public CounterPage(UiService ui, Scheduler scheduler, Player player) {
 *         super(ui, scheduler, player, "counter", Text.plain("Counter"), 3);
 *     }
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Container.builder()
 *             .child(TextButton.builder()
 *                 .text(Text.plain("Count: " + count))
 *                 .slot(13)
 *                 .onClick(() -> {
 *                     // Mutate state and trigger a rebuild
 *                     count++;
 *                     markNeedsBuild();
 *                 })
 *                 .build())
 *             .build();
 *     }
 * }
 * }</pre>
 * <p>
 * <h2>Package Structure:</h2>
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.core} - core abstract classes</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.engine} - rendering engine</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.widgets} - built-in widget library</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.util} - utility classes</li>
 * </ul>
 *
 * @author qianmo
 * @version 1.0.0
 * @since 6.2.0
 * @see com.ultikits.ultitools.abstracts.gui.declarative.engine.DeclarativeGui
 * @see com.ultikits.ultitools.abstracts.gui.declarative.core.Widget
 * @see com.ultikits.ultitools.abstracts.gui.declarative.core.StatefulWidget
 */
@ApiStatus.Experimental
package com.ultikits.ultitools.abstracts.gui.declarative;

import org.jetbrains.annotations.ApiStatus;
