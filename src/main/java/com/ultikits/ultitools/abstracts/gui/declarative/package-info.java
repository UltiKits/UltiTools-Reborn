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
 * {@code performBuild()} call, not just once from {@code onOpen}; {@code Element.update()}
 * marks itself dirty unconditionally; and {@code State.setState()} — including a call on a
 * nested {@code StatefulWidget} — now reliably reaches a scheduled repaint. The
 * <i>setState triggers a partial rebuild</i> bullet below now holds, and the navigation
 * subsystem that depends on it ({@code Navigator.push} / {@code pop}) is usable. Click
 * dispatch was also tightened: {@code GuiRenderer} bounds-checks {@code event.getRawSlot()}
 * before any handler lookup, so a numerically-colliding slot in the player's own inventory can
 * no longer trigger a GUI handler. {@code GridView} now writes its computed slot onto any
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
 *   <li>RenderNode tree performs efficient diff-based updates</li>
 *   <li>Supports both StatelessWidget and StatefulWidget</li>
 *   <li>setState triggers a partial rebuild</li>
 *   <li>All updates run on the main thread automatically</li>
 *   <li>Compatible with obliviate-invs' Gui lifecycle</li>
 * </ul>
 * <p>
 * <h2>Quick Start:</h2>
 * <pre>{@code
 * // 1. 创建你的 GUI 页面
 * public class ShopPage extends DeclarativeGui {
 *     private final List<ItemStack> items;
 *
 *     public ShopPage(Player player, List<ItemStack> items) {
 *         super(player, "shop", "Item Shop", 6);
 *         this.items = items;
 *     }
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Container.builder()
 *             .children(
 *                 // 标题
 *                 TextButton.builder()
 *                     .text("§6§lItem Shop")
 *                     .slot(4)
 *                     .build(),
 *
 *                 // 物品网格
 *                 GridView.<ItemStack>builder()
 *                     .startSlot(10)
 *                     .columns(7)
 *                     .items(items, item -> ItemDisplay.builder(item)
 *                         .onClick(() -> buyItem(item))
 *                         .build())
 *                     .build(),
 *
 *                 // 关闭按钮
 *                 TextButton.builder()
 *                     .text("§cClose")
 *                     .color("RED")
 *                     .slot(49)
 *                     .onClick(player::closeInventory)
 *                     .build()
 *             )
 *             .build();
 *     }
 *
 *     private void buyItem(ItemStack item) {
 *         // 处理购买逻辑
 *     }
 * }
 *
 * // 2. 打开 GUI
 * new ShopPage(player, items).open();
 * }</pre>
 * <p>
 * <h2>StatefulWidget Example:</h2>
 * <pre>{@code
 * public class CounterPage extends DeclarativeGui {
 *     private int count = 0;
 *
 *     public CounterPage(Player player) {
 *         super(player, "counter", "Counter", 3);
 *     }
 *
 *     @Override
 *     public Widget build(BuildContext context) {
 *         return Container.builder()
 *             .child(TextButton.builder()
 *                 .text("Count: " + count)
 *                 .slot(13)
 *                 .onClick(() -> {
 *                     // 修改状态并触发重建
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
