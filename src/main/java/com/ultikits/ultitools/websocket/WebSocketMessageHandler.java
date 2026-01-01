package com.ultikits.ultitools.websocket;

import com.alibaba.fastjson.JSONObject;

/**
 * Interface for handling specific types of WebSocket messages.
 * Implementations should be registered with MessageHandlerRegistry.
 * <p>
 * 处理特定类型 WebSocket 消息的接口。
 * 实现应注册到 MessageHandlerRegistry。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public interface WebSocketMessageHandler {
    
    /**
     * Gets the message type this handler processes.
     * 获取此处理器处理的消息类型。
     *
     * @return the message type string (e.g., "pong", "server_status")
     */
    String getMessageType();
    
    /**
     * Handles the incoming message.
     * 处理传入的消息。
     *
     * @param message the JSON message to handle
     */
    void handle(JSONObject message);
    
    /**
     * Gets the priority of this handler (higher values = higher priority).
     * Handlers with higher priority are executed first if multiple handlers
     * match the same message type.
     * 获取此处理器的优先级（值越高优先级越高）。
     * 如果多个处理器匹配相同的消息类型，优先级高的先执行。
     *
     * @return the priority (default is 0)
     */
    default int getPriority() {
        return 0;
    }
}
