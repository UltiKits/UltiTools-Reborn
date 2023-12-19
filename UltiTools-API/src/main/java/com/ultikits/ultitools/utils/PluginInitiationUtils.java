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
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

import static spark.Spark.*;

public class PluginInitiationUtils {

    public static boolean downloadRequiredDependencies() {
        YamlConfiguration env = UltiTools.getEnv();
        List<String> dependencies = env.getStringList("libraries");
        boolean restartRequired = false;
        for (String name : dependencies) {
            File file = new File(UltiTools.getInstance().getDataFolder() + "/lib", name);
            if (file.exists()) {
                continue;
            }
            if (!restartRequired) {
                Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API] Missing required libraries，trying to download...");
                Bukkit.getLogger().log(Level.WARNING, "[UltiTools-API] If have problems in downloading，you can download full version.");
            }
            restartRequired = true;
            String url = env.getString("oss-url") + env.getString("lib-path") + name;
            Bukkit.getLogger().log(Level.INFO, "[UltiTools]Downloading: " + url);
            HttpDownloadUtils.download(url, name, UltiTools.getInstance().getDataFolder() + "/lib");
        }
        return restartRequired;
    }

    public static void loginAccount() throws IOException {
        String username = UltiTools.getInstance().getConfig().getString("account.username");
        String password = UltiTools.getInstance().getConfig().getString("account.password");
        boolean ssl = UltiTools.getInstance().getConfig().getBoolean("web-editor.https.enable");
        if (username == null || password == null || username.equals("") || password.equals("")) {
            return;
        }

        TokenEntity token = HttpRequestUtils.getToken(username, password);
        String uuid = UltiTools.getUltiToolsUUID();
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
