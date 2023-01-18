package com.ultikits.ultitools.webserver.ws;

import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import spark.Session;

import java.io.IOException;

@WebSocket
public class HeartBeatWebSocket {
    @OnWebSocketMessage
    public void message(Session session, String message) throws IOException {

    }
}
