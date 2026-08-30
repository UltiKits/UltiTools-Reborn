/**
 * 声明式 Inventory GUI 框架。
 * <p>
 * 本框架基于 Flutter 的设计理念，提供声明式的 UI 构建方式。
 * 核心理念：<b>UI = f(state)</b>
 * <p>
 * <h2>状态说明（6.3.0）</h2>
 * <p>
 * <b>本包及其子包仍标记为 {@code @ApiStatus.Experimental}，API 可能在未来版本发生不兼容变动。</b>
 * <p>
 * <b>三个渲染接缝已在 6.3.0 修复，{@code UI = f(state)} 现在成立：</b>{@code GuiRenderer} 通过
 * {@code Supplier<Widget>} 在每次 {@code performBuild()} 时重新生成 Widget 树（而非只在
 * {@code onOpen} 调用一次）；{@code Element.update()} 无条件置脏标记；{@code State.setState()}
 * ——包括嵌套 {@code StatefulWidget} 上的调用——现在能可靠地触发一次已调度的重绘。因此下面
 * 「主要特性」中的 <i>setState 触发局部重建</i> 现已生效，依赖它的导航子系统
 * （{@code Navigator.push} / {@code pop}）同样可用。点击分发同时收紧：{@code GuiRenderer} 会在
 * 查找处理器之前校验 {@code event.getRawSlot()}，玩家自身背包中数值相同的槽位不会再触发
 * GUI 的点击处理器。{@code GridView} 会在渲染时把计算出的槽位写给任意 Widget 类型的子节点
 * （此前只有 {@code ItemDisplay} 能正确定位）。
 * <p>
 * <b>标记为何保留：</b>截至本次发布，这三处改动尚未收到任何下游模块的真机反馈——正如零下游
 * 采用不足以证明 API 已经稳定，它同样不足以构成删除标记的理由。标记至少保留到下一个版本，
 * 待收到真机反馈后再作决定。
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
 * 三个接缝均由 <a href="https://github.com/UltiKits/UltiTools-Reborn/issues/200">issue #200</a>
 * 追踪。The three seams are tracked by
 * <a href="https://github.com/UltiKits/UltiTools-Reborn/issues/200">issue #200</a>.
 *
 * <h2>主要特性：</h2>
 * <ul>
 *   <li>Widget 树描述 UI 结构</li>
 *   <li>Element 树管理 Widget 实例和生命周期</li>
 *   <li>RenderNode 树进行高效的 diff 更新</li>
 *   <li>支持 StatelessWidget 和 StatefulWidget</li>
 *   <li>setState 触发局部重建</li>
 *   <li>所有更新自动在主线程执行</li>
 *   <li>兼容 obliviate-invs 的 Gui 生命周期</li>
 * </ul>
 * <p>
 * <h2>快速开始：</h2>
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
 * <h2>StatefulWidget 示例：</h2>
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
 * <h2>包结构：</h2>
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.core} - 核心抽象类</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.engine} - 渲染引擎</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.widgets} - 预置组件库</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.gui.declarative.util} - 工具类</li>
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
