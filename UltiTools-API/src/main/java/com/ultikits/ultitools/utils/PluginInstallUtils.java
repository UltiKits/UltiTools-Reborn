package com.ultikits.ultitools.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.entities.PluginVersionEntity;

import java.util.ArrayList;
import java.util.List;

public class PluginInstallUtils {

    public static List<PluginEntity> getPluginList(int page, int pageSize) {
        List<PluginEntity> pluginEntities = new ArrayList<>();
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/list?page=" + page + "&pageSize=" + pageSize);
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return pluginEntities;
        }
        String body = httpResponse.body();
        httpResponse.close();
        JSONArray jsonArray = JSONUtil.parseArray(body);
        return jsonArray.toBean(new TypeReference<List<PluginEntity>>() {
        });
    }

    public static String getPluginVersionDownloadLink(String idString, String version) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/" + plugin.getId() + "/"+version+"/download");
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        return body;
    }

    public static List<String> getPluginVersions(String idString) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/" + plugin.getId() + "/versions");
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        JSONArray jsonArray = JSONUtil.parseArray(body);
        return jsonArray.toList(String.class);
    }

    public static String getPluginLatestVersion(String idString) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/" + plugin.getId() + "/latest");
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        return body;
    }

    public static String getPluginLatestDownloadLink(String idString) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/" + plugin.getId() + "/latest/download");
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        return body;
    }

    public static PluginEntity getPlugin(String idString) {
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/get?identifyString=" + idString);
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        JSONObject jsonObject = JSONUtil.parseObj(body);
        return jsonObject.toBean(PluginEntity.class);
    }

    public static boolean installLatestPlugin(String idString) {
        String pluginVersionDownloadLink = getPluginLatestDownloadLink(idString);
        if (pluginVersionDownloadLink == null) {
            return false;
        }

        HttpDownloadUtils.download(pluginVersionDownloadLink,
                pluginVersionDownloadLink.substring(pluginVersionDownloadLink.lastIndexOf("/") + 1),
                UltiTools.getInstance().getDataFolder() + "/plugins");
        return true;
    }

    public static boolean installPlugin(String idString, String version) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return false;
        }
        String pluginVersionDownloadLink = getPluginVersionDownloadLink(idString, version);
        if (pluginVersionDownloadLink == null) {
            return false;
        }

        HttpDownloadUtils.download(pluginVersionDownloadLink,
                pluginVersionDownloadLink.substring(pluginVersionDownloadLink.lastIndexOf("/") + 1),
                UltiTools.getInstance().getDataFolder() + "/plugins");
        return true;
    }
}
