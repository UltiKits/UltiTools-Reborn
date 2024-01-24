package com.ultikits.ultitools.webserver.controller;

import cn.hutool.json.JSONObject;
import com.alibaba.fastjson.JSONArray;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static spark.Spark.*;

public class PluginModuleController {
    public void init() {
        path("/plugin", () -> {
            before("/*", (request, response) -> {
                Bukkit.getLogger().log(Level.INFO, "Get connection from: " + request.ip() + " access to " + request.uri());
                if (!request.ip().equals("47.242.179.141") &&
                        !UltiTools.getInstance().getConfig().getStringList("web-editor.trustIp")
                                .contains(request.ip())) {
                    halt("Access Denied!");
                }
            });
            get("/list", (request, response) -> {
                List<UltiToolsPlugin> pluginList = UltiTools.getInstance().getPluginManager().getPluginList();
                List<JSONObject> result = new ArrayList<>();
                for (UltiToolsPlugin plugin : pluginList) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.set("name", plugin.getPluginName());
                    jsonObject.set("version", plugin.getVersion());
                    jsonObject.set("author", plugin.getAuthors());
                    jsonObject.set("apiVersion", plugin.getMinUltiToolsVersion());
                    result.add(jsonObject);
                }
                return JSONArray.toJSONString(result);
            });
            after("/*", (request, response) -> {
                response.type("text/json; charset=utf-8");
            });
        });
    }
}
