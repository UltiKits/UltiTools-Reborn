package com.ultikits.ultitools.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.PluginEntity;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class PluginInstallUtils {

    /**
     * Get plugin list online.
     * <br>
     * 在线获取插件列表。
     *
     * @param page     页数
     * @param pageSize 每页数量
     * @return 插件列表
     */
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

    /**
     * Get plugin download link.
     * <br>
     * 获取插件下载链接。
     *
     * @param idString 插件ID
     * @param version  版本
     * @return 插件下载链接
     */
    public static String getPluginVersionDownloadLink(String idString, String version) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        HttpRequest get = HttpUtil.createGet("https://api.ultikits.com/plugin/" + plugin.getId() + "/" + version + "/download");
        HttpResponse httpResponse = get.execute();
        if (!httpResponse.isOk()) {
            return null;
        }
        String body = httpResponse.body();
        httpResponse.close();
        return body;
    }

    /**
     * Get plugin versions.
     * <br>
     * 获取插件版本列表。
     *
     * @param idString 插件ID
     * @return 插件版本列表
     */
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

    /**
     * Get plugin latest version.
     * <br>
     * 获取插件最新版本。
     *
     * @param idString 插件ID
     * @return 插件最新版本
     */
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

    /**
     * @param idString 插件ID
     * @return 插件最新版本下载链接
     */
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

    /**
     * @param idString 插件ID
     * @return 插件信息
     */
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

    /**
     * Install latest plugin.
     * <br>
     * 安装最新插件。
     *
     * @param idString 插件ID
     * @return 是否安装成功
     */
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

    /**
     * Install plugin.
     * <br>
     * 安装插件。
     *
     * @param idString 插件ID
     * @param version  版本
     * @return 是否安装成功
     */
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

    /**
     * Uninstall plugin.
     * <br>
     * 卸载插件。
     *
     * @param name 插件名称
     * @return 是否卸载成功
     * @throws IOException if an I/O error occurs
     */
    public static boolean uninstallPlugin(String name) throws IOException {
        AtomicReference<UltiToolsPlugin> ultiToolsPluginAtomicReference = new AtomicReference<>();
        UltiTools.getInstance().getPluginManager().getPluginList().stream().filter(plugin -> plugin.getPluginName().equals(name)).forEach(plugin -> {
            ultiToolsPluginAtomicReference.set(plugin);
            plugin.unregisterSelf();
        });
        UltiTools.getInstance().getPluginManager().getPluginList().remove(ultiToolsPluginAtomicReference.get());
        File folder = new File(UltiTools.getInstance().getDataFolder() + "/plugins");
        if (folder.listFiles() == null) {
            return false;
        }
        for (File file : folder.listFiles()) {
            URL url = new URL("jar:file:" + file.getAbsolutePath() + "!/plugin.yml");
            JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
            InputStream inputStream = jarConnection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            YamlConfiguration pluginConfig = YamlConfiguration.loadConfiguration(reader);
            String pluginName = pluginConfig.getString("name");
            if (name.equals(pluginName)) {
                inputStream.close();
                reader.close();
                file.delete();
                return true;
            }
        }
        return false;
    }
}
