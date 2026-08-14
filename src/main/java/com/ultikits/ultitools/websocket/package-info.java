/**
 * UltiPanel WebSocket client internals.
 * <p>
 * UltiPanel WebSocket 客户端内部实现。
 * <p>
 * Connection lifecycle, reconnect strategy and message dispatch for the link to
 * UltiPanel. The whole subsystem is driven by the framework: it is created after
 * a successful cloud login and torn down on shutdown. The wire protocol is a
 * private contract between this client and the panel worker, and both sides
 * change together — nothing here is a stable extension point.
 * <p>
 * 与 UltiPanel 之间连接的生命周期、重连策略与消息分发。整个子系统由框架驱动：
 * 云端登录成功后创建，关服时销毁。线上协议是本客户端与面板 worker 之间的私有约定，
 * 两边一起改——这里没有任何东西属于稳定扩展点。
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.websocket;

import org.jetbrains.annotations.ApiStatus;
