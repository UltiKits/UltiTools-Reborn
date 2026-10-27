/**
 * UltiPanel WebSocket client internals, and the three stable extension points modules use.
 * <p>
 * Connection lifecycle, reconnect strategy and inbound message dispatch for the link to
 * UltiPanel. The whole subsystem is driven by the framework: it is created after a successful
 * cloud login and torn down on shutdown. The wire protocol is a private contract between this
 * client and the panel worker, and both sides change together, so these internals are not an
 * extension point.
 * <p>
 * <b>Stable extension points.</b> Three classes of this package are public, stable API for
 * modules, as their own javadoc and {@code COMPATIBILITY.md} state:
 * <ul>
 *   <li>{@link PanelResponderRegistry}: the single-owner registry a module uses to claim a
 *       request/response responder for a type the framework does not own (as of 6.3.0, WIRE-16;
 *       as of 6.3.1 served by the UltiCloud protocol v2 {@code module.request});</li>
 *   <li>{@link SensitiveCommandRegistry} (as of 6.3.1): the table of commands whose arguments are
 *       redacted from everything sent to UltiCloud, reached through
 *       {@code UltiTools#getSensitiveCommandRegistry()};</li>
 *   <li>{@link CloudPlayerLinks} (as of 6.3.1): the player-link API, reached through
 *       {@code UltiTools#getCloudPlayerLinks()}.</li>
 * </ul>
 * The package carries {@code @ApiStatus.Internal} for everything else in it. The JetBrains
 * annotations cannot mark one class stable inside an internal package, so an IDE's unstable-API
 * inspection flags these three as well; their javadoc and {@code COMPATIBILITY.md} are the
 * contract.
 * <p>
 * As of 6.3.1 the live dispatch path is the UltiCloud protocol v2 agent connection in
 * {@code com.ultikits.ultitools.utils}. {@link UltiPanelWebSocketClient} and the protocol v1
 * inbound-message dispatch table that used to serve it are no longer used; the dispatch table was
 * removed in 6.3.1.
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
