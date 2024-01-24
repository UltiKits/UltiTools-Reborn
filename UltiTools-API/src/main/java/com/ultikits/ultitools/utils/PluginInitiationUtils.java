package com.ultikits.ultitools.utils;

import cn.hutool.core.net.NetUtil;
import cn.hutool.http.HttpResponse;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.webserver.controller.ConfigEditorController;
import com.ultikits.ultitools.webserver.controller.PluginModuleController;
import com.ultikits.ultitools.webserver.ws.HeartBeatWebSocket;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.logging.Level;

import static spark.Spark.*;

public class PluginInitiationUtils {

    /**
     * Login account.
     * <br>
     * 登录账户。
     *
     * @throws IOException if an I/O error occurs
     */
    public static void loginAccount() throws IOException {
        String username = UltiTools.getInstance().getConfig().getString("account.username");
        String password = UltiTools.getInstance().getConfig().getString("account.password");
        boolean ssl = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable");
        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            return;
        }

        TokenEntity token = HttpRequestUtils.getToken(username, password);
        String uuid = CommonUtils.getUltiToolsUUID();
        HttpResponse uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token);
        int port = UltiTools.getInstance().getConfig().getInt("web-editor.port");
        String domain = UltiTools.getInstance().getConfig().getString("web-editor.https.domain");
        if (uuidResponse.getStatus() == 404) {
            HttpResponse registerResponse = HttpRequestUtils.registerServer(uuid, port, domain, ssl, token);
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
            registerResponse.close();
        } else {
            HttpResponse registerResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token);
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
            registerResponse.close();
        }
    }

    /**
     * Init embed web server.
     * <br>
     * 初始化嵌入式Web服务器。
     */
    public static void initEmbedWebServer() {
        if (UltiTools.getInstance().getConfig().getBoolean("web-editor.enable")) {
            int port = UltiTools.getInstance().getConfig().getInt("web-editor.port");
            if (NetUtil.isUsableLocalPort(port)) {
                port(port);
                if (UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable")) {
                    String keystoreFilePath = UltiTools.getInstance().getConfig().getString("web-editor.https.keystore-file-path");
                    String keystorePassword = UltiTools.getInstance().getConfig().getString("web-editor.https.keystore-password");
                    secure(keystoreFilePath, keystorePassword, null, null);
                }
                webSocket("/heartbeat", HeartBeatWebSocket.class);
                init();
                awaitInitialization();
                new ConfigEditorController().init();
                new PluginModuleController().init();
            }
        }
    }

    public static void stopEmbedWebServer() {
        stop();
        awaitStop();
    }
}
