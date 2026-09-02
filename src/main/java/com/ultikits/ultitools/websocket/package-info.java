/**
 * UltiPanel WebSocket client internals.
 * <p>
 * UltiPanel WebSocket 客户端内部实现。
 * <p>
 * Connection lifecycle, reconnect strategy and inbound message dispatch for the link to
 * UltiPanel. The whole subsystem is driven by the framework: it is created after a successful
 * cloud login and torn down on shutdown. The wire protocol is a private contract between this
 * client and the panel worker, and both sides change together — nothing here is a stable
 * extension point.
 * <p>
 * The live dispatch path is {@link UltiPanelWebSocketClient} plus the inbound-message dispatch
 * table {@code PluginInitiationUtils#handleInboundMessage} builds for the framework's own message
 * types, with {@link PanelResponderRegistry} as the single-owner extension point a module uses to
 * claim a request/response responder for a type the framework does not own (WIRE-16).
 * <p>
 * <b>This package previously also held a second, superseded dispatch mechanism</b> — a
 * registry-plus-interface pair with five per-message-type implementations, formerly the sole
 * public class of a now-deleted {@code handlers} sub-package (WIRE-17) — replaced by the dispatch
 * table above in Phase 1 (plan 01-05) but left in the tree, unreferenced, until it was deleted in
 * 6.3.0 (GEN-11, see {@code compatibility/records/6.3.0.md}). A reader arriving here from an old
 * link to that cluster will not find it; this is why.
 * <p>
 * 线上分发路径是 {@link UltiPanelWebSocketClient} 加上 {@code PluginInitiationUtils
 * #handleInboundMessage} 为框架自身消息类型构建的入站分发表，以及作为模块认领框架未拥有的
 * 类型的单一持有者扩展点的 {@link PanelResponderRegistry}（WIRE-16）。
 * <p>
 * <b>本包此前还存在第二套已被取代的分发机制</b>——一个「注册表 + 接口」组合，外加五个按消息
 * 类型各自实现的子类，曾是现已整体删除的 {@code handlers} 子包中唯一的公开类（WIRE-17）——
 * 在 Phase 1（plan 01-05）就被上面这张分发表取代，却一直留在树中未被引用，直到 6.3.0
 * （GEN-11，见 {@code compatibility/records/6.3.0.md}）才被删除。从旧链接找到这里却找不到那
 * 一套的读者，原因就在这里。
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;
