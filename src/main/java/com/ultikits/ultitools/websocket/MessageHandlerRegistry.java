package com.ultikits.ultitools.websocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.alibaba.fastjson.JSONObject;

/**
 * Registry for WebSocket message handlers.
 * Manages registration and dispatch of message handlers.
 * <p>
 * WebSocket 消息处理器的注册表。
 * 管理消息处理器的注册和分发。
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class MessageHandlerRegistry {
    
    private static final Logger logger = Logger.getLogger(MessageHandlerRegistry.class.getName());
    
    /**
     * Message type field name in JSON messages.
     */
    public static final String TYPE_FIELD = "type";
    
    private final Map<String, List<WebSocketMessageHandler>> handlers = new ConcurrentHashMap<>();
    private final List<WebSocketMessageHandler> globalHandlers = Collections.synchronizedList(new ArrayList<>());
    
    /**
     * Registers a handler for its declared message type.
     * 为其声明的消息类型注册处理器。
     *
     * @param handler the handler to register
     */
    public void register(WebSocketMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null");
        }
        
        String type = handler.getMessageType();
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Handler must have a non-empty message type");
        }
        
        handlers.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(handler);
        
        // Sort by priority (descending)
        handlers.get(type).sort(Comparator.comparingInt(WebSocketMessageHandler::getPriority).reversed());
        
        logger.fine("Registered handler for message type: " + type);
    }
    
    /**
     * Registers a global handler that receives all messages.
     * 注册接收所有消息的全局处理器。
     *
     * @param handler the global handler to register
     */
    public void registerGlobal(WebSocketMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler must not be null");
        }
        globalHandlers.add(handler);
        globalHandlers.sort(Comparator.comparingInt(WebSocketMessageHandler::getPriority).reversed());
    }
    
    /**
     * Unregisters a handler.
     * 注销处理器。
     *
     * @param handler the handler to unregister
     */
    public void unregister(WebSocketMessageHandler handler) {
        if (handler == null) {
            return;
        }
        
        String type = handler.getMessageType();
        List<WebSocketMessageHandler> typeHandlers = handlers.get(type);
        if (typeHandlers != null) {
            typeHandlers.remove(handler);
            if (typeHandlers.isEmpty()) {
                handlers.remove(type);
            }
        }
        
        globalHandlers.remove(handler);
    }
    
    /**
     * Unregisters all handlers for a message type.
     * 注销某消息类型的所有处理器。
     *
     * @param messageType the message type to clear
     */
    public void unregisterAll(String messageType) {
        handlers.remove(messageType);
    }
    
    /**
     * Clears all registered handlers.
     * 清除所有已注册的处理器。
     */
    public void clear() {
        handlers.clear();
        globalHandlers.clear();
    }
    
    /**
     * Dispatches a message to the appropriate handlers.
     * 将消息分发给适当的处理器。
     *
     * @param message the JSON message to dispatch
     * @return true if at least one handler processed the message
     */
    public boolean dispatch(JSONObject message) {
        if (message == null) {
            return false;
        }
        
        String type = message.getString(TYPE_FIELD);
        boolean handled = false;
        
        // Process global handlers first
        for (WebSocketMessageHandler handler : new ArrayList<>(globalHandlers)) {
            try {
                handler.handle(message);
                handled = true;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in global message handler", e);
            }
        }
        
        // Process type-specific handlers
        if (type != null && !type.isEmpty()) {
            List<WebSocketMessageHandler> typeHandlers = handlers.get(type);
            if (typeHandlers != null && !typeHandlers.isEmpty()) {
                for (WebSocketMessageHandler handler : new ArrayList<>(typeHandlers)) {
                    try {
                        handler.handle(message);
                        handled = true;
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in handler for type '" + type + "'", e);
                    }
                }
            } else {
                logger.fine("No handler registered for message type: " + type);
            }
        }
        
        return handled;
    }
    
    /**
     * Dispatches a message from raw JSON string.
     * 从原始 JSON 字符串分发消息。
     *
     * @param jsonString the JSON string to parse and dispatch
     * @return true if at least one handler processed the message
     */
    public boolean dispatch(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return false;
        }
        
        try {
            JSONObject message = JSONObject.parseObject(jsonString);
            return dispatch(message);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse message: " + jsonString, e);
            return false;
        }
    }
    
    /**
     * Checks if a handler is registered for a message type.
     * 检查是否为某消息类型注册了处理器。
     *
     * @param messageType the message type to check
     * @return true if at least one handler is registered
     */
    public boolean hasHandler(String messageType) {
        List<WebSocketMessageHandler> typeHandlers = handlers.get(messageType);
        return typeHandlers != null && !typeHandlers.isEmpty();
    }
    
    /**
     * Gets the number of handlers registered for a type.
     * 获取某类型注册的处理器数量。
     *
     * @param messageType the message type
     * @return the number of handlers
     */
    public int getHandlerCount(String messageType) {
        List<WebSocketMessageHandler> typeHandlers = handlers.get(messageType);
        return typeHandlers != null ? typeHandlers.size() : 0;
    }
    
    /**
     * Gets all registered message types.
     * 获取所有已注册的消息类型。
     *
     * @return unmodifiable set of message types
     */
    public java.util.Set<String> getRegisteredTypes() {
        return Collections.unmodifiableSet(handlers.keySet());
    }
}
