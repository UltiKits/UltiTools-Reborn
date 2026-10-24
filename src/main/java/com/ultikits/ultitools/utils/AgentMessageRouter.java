package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.websocket.CloudChannel;
import com.ultikits.ultitools.websocket.CloudRequestHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

final class AgentMessageRouter {
    private final Map<String, CloudRequestHandler> handlers = new ConcurrentHashMap<>();

    AgentMessageRouter(Predicate<String> capabilityEnabled, Function<String, String> refusalForCapability) { }

    void register(String type, CloudRequestHandler handler) {
        if (AgentMessageTypes.spec(type) == null) {
            throw new IllegalArgumentException("Unregistered agent type");
        }
        handlers.put(type, handler);
    }

    void dispatch(String text, CloudChannel channel) {
        Envelope envelope = Envelope.parse(text, channel.limits().maxFrameBytes());
        CloudRequestHandler handler = handlers.get(envelope.type);
        if (handler != null) {
            handler.handle(envelope.request(), channel);
        }
    }
}
