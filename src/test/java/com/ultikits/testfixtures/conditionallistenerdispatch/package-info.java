/**
 * Fixtures for proving that a listener whose {@code @ConditionalOnConfig} condition evaluates
 * {@code false} receives no events after {@code ListenerManager.registerAll(plugin, packageName)}
 * runs -- asserted through an actual Bukkit event dispatch and an observed side effect, not
 * through inspecting a registration bookkeeping list (ROADMAP Criterion 4). Distinct from
 * {@code com.ultikits.testfixtures.conditionallistener} (Task 1), whose fixtures only prove the
 * registration-count half; these two additionally carry a real {@code @EventHandler} method and a
 * static hit counter so a fired event's effect is directly observable.
 * <br>
 * 用于证明一个 {@code @ConditionalOnConfig} 条件为 {@code false} 的监听器，在
 * {@code ListenerManager.registerAll(plugin, packageName)} 运行之后不会收到任何事件——通过真实
 * 的 Bukkit 事件分发与可观察的副作用来验证，而不是检查注册台账列表（ROADMAP 验收标准 4）。
 * 与 {@code com.ultikits.testfixtures.conditionallistener}（Task 1）不同，那里的 fixture 只证明
 * "注册数量"这一半；这里的两个类额外携带真实的 {@code @EventHandler} 方法和一个静态命中计数器，
 * 使事件被触发后的效果可以被直接观察到。
 */
package com.ultikits.testfixtures.conditionallistenerdispatch;
