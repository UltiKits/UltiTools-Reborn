package com.ultikits.ultitools.websocket;

import com.ultikits.ultitools.UltiTools;
import io.socket.client.IO;
import io.socket.client.Socket;
import lombok.Getter;

import java.net.URISyntaxException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Websocket client
 * <p>
 * Websocket客户端
 * <p>
 * 通过传入的url和id连接到SocketIO服务器
 * <p>
 * Connect to the SocketIO server through the url and id passed in
 */
@Getter
public class WebsocketClient {
    private final Socket socket;
    private final String serverId;

    public WebsocketClient(String url, String id, String token) throws URISyntaxException {
        IO.Options options = new IO.Options();
        options.transports = new String[]{"websocket"};
        options.reconnectionAttempts = 20;
        options.reconnectionDelay = 10000;
        options.timeout = 10000;
        serverId = id;
        socket = IO.socket(url + "?serverId=" + serverId + "&token=" + token, options);
        socket.on(Socket.EVENT_CONNECT, args1 -> {
            UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("成功连接到UltiKits服务器！"));
        }).on(Socket.EVENT_DISCONNECT, args1 -> {
            UltiTools.getInstance().getLogger().log(Level.WARNING, UltiTools.getInstance().i18n("已与UltiKits服务器断开连接！"));
        }).on(Socket.EVENT_CONNECT_ERROR, args1 -> {
            UltiTools.getInstance().getLogger().log(Level.WARNING, UltiTools.getInstance().i18n("无法连接到UltiKits服务器：") + args1[0]);
        });
    }

    /**
     * Connect to the server
     * <p>
     * 连接到服务器
     *
     * @param consumer logic to be executed before the connection is established <p> 连接建立前要执行的逻辑
     */
    public void connect(Consumer<WebsocketClient> consumer) {
        consumer.accept(this);
        socket.connect();
    }

    /**
     * Disconnect from the server
     * <p>
     * 从服务器断开连接
     */
    public void stop() {
        socket.disconnect();
    }

    /**
     * Disconnect from the server
     * <p>
     * 从服务器断开连接
     *
     * @param consumer logic to be executed before the disconnection <p> 断开连接前要执行的逻辑
     */
    public void stop(Consumer<WebsocketClient> consumer) {
        consumer.accept(this);
        socket.disconnect();
    }
}

