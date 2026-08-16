/**
 * 声明式 Inventory GUI 框架。
 * <p>
 * 本框架基于 Flutter 的设计理念，提供声明式的 UI 构建方式。
 * 核心理念：<b>UI = f(state)</b>
 * <p>
 * <h2>⚠ 当前状态（6.2.5）</h2>
 * <p>
 * <b>本包及其子包标记为 {@code @ApiStatus.Experimental}，API 可能在任何版本发生不兼容变动。</b>
 * <p>
 * <b>状态驱动的重绘尚未实现。</b>{@code UI = f(state)} 是本框架的设计目标，但渲染引擎的三个接缝
 * 目前都还没接上：{@code build(BuildContext)} 只在 {@code onOpen} 中被调用一次；
 * {@code Element.update()} 不置脏标记；{@code State.setState} 的信号沿 Element 树上溯到根节点后
 * 静默终止，进不了调度器。因此下面「主要特性」中的 <i>setState 触发局部重建</i> 目前不生效，
 * 依赖 {@code setState} 的导航子系统（{@code Navigator.push} / {@code pop}）同样惰性。
 * 现阶段可靠可用的是<b>静态页面</b>：build 一次、渲染一次。
 * <p>
 * <b>State-driven repaint is not implemented yet.</b> {@code UI = f(state)} is the design
 * goal, but all three rendering seams are still open: {@code build(BuildContext)} is called
 * exactly once from {@code onOpen}; {@code Element.update()} never marks anything dirty; and
 * the {@code State.setState} signal walks up the element tree and stops silently at the root
 * without reaching the scheduler. The <i>setState triggers a partial rebuild</i> bullet below
 * therefore does not hold today, and the navigation subsystem is inert for the same reason.
 * What works today is the <b>static page</b>: build once, render once.
 * <p>
 * 修复排期见 <a href="https://github.com/UltiKits/UltiTools-Reborn/issues/200">issue #200</a>（6.3.0）。
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
 *             .background(IconWrapper.builder(glassPane).name(" ").build())
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
 *                     .rows(4)
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
