package com.ultikits.ultitools.utils;

import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.webserver.controller.ConfigEditorController;
import com.ultikits.ultitools.webserver.ws.HeartBeatWebSocket;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import static spark.Spark.*;

public class PluginInitiationUtils {

    public static void loginAccount() throws IOException {
        File dataFile = new File(UltiTools.getInstance().getDataFolder(), "data.json");
        JSON json = new cn.hutool.json.JSONObject();
        if (dataFile.exists()) {
            json = JSONUtil.readJSON(dataFile, StandardCharsets.UTF_8);
        } else {
            json.putByPath("uuid", IdUtil.simpleUUID());
            json.write(new FileWriter(dataFile));
        }

        String username = UltiTools.getInstance().getConfig().getString("account.username");
        String password = UltiTools.getInstance().getConfig().getString("account.password");
        boolean ssl = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable");
        if (username == null || password == null || username.equals("") || password.equals("")) {
            return;
        }

        TokenEntity token = HttpRequestUtils.getToken(username, password);
        String uuid = json.getByPath("uuid").toString();
        HttpResponse uuidResponse = HttpRequestUtils.getServerByUUID(uuid, token);
        int port = UltiTools.getInstance().getConfig().getInt("web-editor.port");
        String domain = UltiTools.getInstance().getConfig().getString("web-editor.https.domain");
        if (uuidResponse.getStatus() == 404) {
            HttpResponse registerResponse = HttpRequestUtils.registerServer(uuid, port, domain, ssl, token);
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
        } else {
            HttpResponse registerResponse = HttpRequestUtils.updateServer(uuid, port, domain, ssl, token);
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
        }
    }

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
            }
        }
    }

    public static void stopEmbedWebServer() {
        stop();
        awaitStop();
    }
}
