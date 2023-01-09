package com.ultikits.ultitools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.api.VersionWrapper;
import com.ultikits.ultitools.commands.ReloadPluginsCommand;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.entities.vo.ServerEntityVO;
import com.ultikits.ultitools.interfaces.DataStore;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.manager.*;
import com.ultikits.ultitools.services.TeleportService;
import com.ultikits.ultitools.services.impl.InMemeryTeleportService;
import com.ultikits.ultitools.services.registers.TeleportServiceRegister;
import com.ultikits.ultitools.tasks.DataStoreWaitingTask;
import com.ultikits.ultitools.webserver.ConfigEditorController;
import com.ultikits.utils.VersionAdaptor;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Contract;
import spark.Spark;
import spark.utils.SparkUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static spark.Spark.*;

public final class UltiTools extends JavaPlugin implements Localized {
    private static UltiTools ultiTools;
    @Getter
    private final ListenerManager listenerManager = new ListenerManager();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Setter
    @Getter
    private DataStore dataStore;
    @Getter
    private VersionWrapper versionWrapper;
    @Getter
    private Language language;
    @Getter
    private PluginManager pluginManager;
    @Getter
    private ConfigManager configManager;

    public static UltiTools getInstance() {
        return ultiTools;
    }

    @Contract(pure = true)
    public static int getPluginVersion() {
        return 600;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        ultiTools = this;
        saveDefaultConfig();
        initEmbedWebServer();
        try {
            loginAccount();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        configManager = new ConfigManager();

        String storeType = getConfig().getString("datasource.type");
        dataStore = DataStoreManager.getDatastore(storeType);
        if (dataStore == null) {
            new DataStoreWaitingTask().runTaskTimerAsynchronously(this, 0L, 20L);
            dataStore = DataStoreManager.getDatastore("json");
        }
        File file = new File(getDataFolder() + File.separator + "plugins");
        FileUtil.mkdir(file);
        try {
            pluginManager = new PluginManager();
            pluginManager.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.versionWrapper = new VersionAdaptor().match();
        String lanPath = "lang/" + getConfig().getString("language") + ".json";
        InputStream in = getResource(lanPath);
        String result = new BufferedReader(new InputStreamReader(in))
                .lines().collect(Collectors.joining(""));
        this.language = new Language(result);

        getCommandManager().register(new ReloadPluginsCommand(), "", "Reload Plugins", "replugins", "rps");

        new TeleportServiceRegister(TeleportService.class, new InMemeryTeleportService());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        stop();
        awaitStop();
        pluginManager.close();
        DataStoreManager.close();
        getConfigManager().saveAll();
    }

    public void reloadPlugins() throws IOException {
        pluginManager.reload();
    }

    @Override
    public List<String> supported() {
        return Arrays.asList("en", "zh");
    }

    public String i18n(String str) {
        return this.language.getLocalizedText(str);
    }

    private void initEmbedWebServer() {
        if (getConfig().getBoolean("web-editor.enable")) {
            int port = getConfig().getInt("web-editor.port");
            if (NetUtil.isUsableLocalPort(port)){
                port(port);
                init();
                awaitInitialization();
                new ConfigEditorController().init();
            }
            if (getConfig().getBoolean("web-editor.https.enable")) {
                String keystoreFilePath = getConfig().getString("web-editor.https.keystore-file-path");
                String keystorePassword = getConfig().getString("web-editor.https.keystore-password");
                secure(keystoreFilePath, keystorePassword, null, null);
            }
        }
    }

    private void loginAccount() throws IOException {
        File dataFile = new File(getDataFolder(), "data.json");
        JSON json = new cn.hutool.json.JSONObject();
        if (dataFile.exists()) {
            json = JSONUtil.readJSON(dataFile, StandardCharsets.UTF_8);
        } else {
            json.putByPath("uuid", IdUtil.simpleUUID());
            json.write(new FileWriter(dataFile));
        }

        String username = getConfig().getString("account.username");
        String password = getConfig().getString("account.password");
        if (username == null || password == null || username.equals("") || password.equals("")) {
            return;
        }

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("username", username);
        paramMap.put("password", password);
        String tokenJson = HttpUtil.post("https://api.v2.ultikits.com/user/getToken", paramMap);
        TokenEntity token = JSONObject.parseObject(tokenJson, TokenEntity.class);
        HttpResponse uuidResponse = HttpRequest.get("https://api.v2.ultikits.com/server/getByUUID?uuid=" + json.getByPath("uuid"))
                .bearerAuth(token.getAccess_token())
                .execute();
        int port = getConfig().getInt("web-editor.port");
        if (uuidResponse.getStatus() == 404) {
            ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                    .uuid(json.getByPath("uuid").toString())
                    .name("MC Server")
                    .port(port)
                    .build();
            HttpResponse registerResponse = HttpRequest.post("https://api.v2.ultikits.com/editor/register?id=" + token.getId())
                    .bearerAuth(token.getAccess_token())
                    .body(serverEntityVO.toString())
                    .execute();
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
        } else {
            ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                    .uuid(json.getByPath("uuid").toString())
                    .port(port)
                    .build();
            HttpResponse registerResponse = HttpRequest.post("https://api.v2.ultikits.com/editor/updateServer?id=" + token.getId())
                    .bearerAuth(token.getAccess_token())
                    .body(serverEntityVO.toString())
                    .execute();
            if (!registerResponse.isOk()) {
                Bukkit.getLogger().log(Level.WARNING, registerResponse.body());
            }
        }
    }

}
