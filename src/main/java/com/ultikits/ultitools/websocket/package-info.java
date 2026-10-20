/**
 * UltiPanel WebSocket client internals.
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
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;
