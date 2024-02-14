package com.ultikits.ultitools.utils;

import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.websocket.WebsocketClient;
import io.socket.client.Ack;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.logging.Level;

public class PluginInitiationUtils {
    private static WebsocketClient panelWS;
    private static TokenEntity token;

    /**
     * Login account.
     * <br>
     * 登录账户。
     *
     * @throws IOException if an I/O error occurs
     */
    public static boolean loginAccount(String username, String password) throws IOException {
        boolean ssl = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable");
        token = HttpRequestUtils.getToken(username, password);
        String uuid = CommonUtils.getUltiToolsUUID();
        HttpResponse uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token);
        int port = UltiTools.getInstance().getConfig().getInt("web-editor.port");
        String domain = UltiTools.getInstance().getConfig().getString("web-editor.https.domain");
        if (uuidResponse.getStatus() == 404) {
            try (HttpResponse registerResponse = HttpRequestUtils.registerServer(uuid, port, domain, ssl, token)) {
                if (!registerResponse.isOk()) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, registerResponse.body());
                    return false;
                }
            }
        } else {
            try (HttpResponse registerResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token)) {
                if (!registerResponse.isOk()) {
                    UltiTools.getInstance().getLogger().log(Level.WARNING, registerResponse.body());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Initialize websocket.
     * <br>
     * 初始化websocket。
     *
     * @throws URISyntaxException if the URI is invalid
     */
    public static void initWebsocket() throws URISyntaxException {
        panelWS = getPanelWebsocketClient();
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("Websocket已连接!"));
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("data", ConfigEditorUtils.getConfigMapString());
        jsonObject.put("comment", ConfigEditorUtils.getCommentMapString());
        jsonObject.put("serverId", panelWS.getServerId());
        UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("正在上传本地配置..."));
        panelWS.getSocket().emit("upload_config", new JSONObject[]{jsonObject}, ack -> {
            if (ack[0].equals("ok")) {
                UltiTools.getInstance().getLogger().log(Level.INFO, UltiTools.getInstance().i18n("配置上传成功!"));
            } else {
                UltiTools.getInstance().getLogger().log(Level.WARNING, UltiTools.getInstance().i18n("配置上传失败!"));
            }
        });
    }

    public static void stopWebsocket() {
        if (panelWS == null){
            return;
        }
        panelWS.stop();
    }

    @NotNull
    private static WebsocketClient getPanelWebsocketClient() throws URISyntaxException {
        WebsocketClient panelWS = new WebsocketClient("https://ws.ultikits.com", CommonUtils.getUltiToolsUUID(), token.getAccess_token());
        panelWS.connect(client -> {
            client.getSocket().on("update_config", args -> {
                Ack ack = (Ack) args[args.length - 1];
                JSONObject jsonObject = JSONObject.parseObject(args[0].toString());
                try {
                    ConfigEditorUtils.updateConfigMap(jsonObject.getString("data"));
                    ack.call("ok");
                } catch (IOException e) {
                    ack.call("error");
                }
            });
        });
        return panelWS;
    }
}
