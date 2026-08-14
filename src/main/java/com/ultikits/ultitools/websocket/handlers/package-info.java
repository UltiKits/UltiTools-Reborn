/**
 * Inbound message handlers for the UltiPanel WebSocket link.
 * <p>
 * UltiPanel WebSocket 链路的入站消息处理器。
 * <p>
 * One handler per message type, registered into {@code MessageHandlerRegistry}
 * by the framework at startup. Message types are a private contract with the
 * panel worker and change on both sides together.
 * <p>
 * 每种消息类型一个处理器，由框架在启动时注册进 {@code MessageHandlerRegistry}。
 * 消息类型是与面板 worker 之间的私有约定，两边一起改。
 * <p>
 * Marked separately from the parent package on purpose: package annotations in
 * Java do not apply to sub-packages, so {@code com.ultikits.ultitools.websocket}
 * being internal says nothing about this package.
 * <p>
 * 单独标注是必要的，不是重复：Java 的包注解不作用于子包，父包
 * {@code com.ultikits.ultitools.websocket} 标了 Internal 并不覆盖这里。
 *
 * @since 6.2.5
 */
@ApiStatus.Internal
package com.ultikits.ultitools.websocket.handlers;

import org.jetbrains.annotations.ApiStatus;
