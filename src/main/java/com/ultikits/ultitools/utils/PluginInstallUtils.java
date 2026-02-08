package com.ultikits.ultitools.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;

/**
 * Utility class for plugin installation and management operations.
 * Provides methods to download, install, and uninstall UltiTools plugins
 * from the remote plugin repository.
 * <br>
 * 插件安装和管理操作的实用工具类。
 * 提供从远程插件仓库下载、安装和卸载UltiTools插件的方法。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class PluginInstallUtils {
    private static final Gson GSON = new Gson();

    // Use lazy initialization to avoid static initializer dependency on UltiTools
    private static volatile String baseUrl;
    
    // Custom base URL for testing purposes
    private static String customBaseUrl;
    
    /**
     * Get the base URL for API calls.
     * Uses lazy initialization to avoid requiring UltiTools at class loading time.
     * <br>
     * 获取API调用的基础URL。
     * 使用延迟初始化以避免在类加载时需要UltiTools。
     *
     * @return the base URL
     */
    static String getBaseUrl() {
        if (customBaseUrl != null) {
            return customBaseUrl;
        }
        if (baseUrl == null) {
            synchronized (PluginInstallUtils.class) {
                if (baseUrl == null) {
                    baseUrl = UltiTools.getEnv().getString("api-url");
                }
            }
        }
        return baseUrl;
    }
    
    /**
     * Set a custom base URL for testing purposes.
     * This should only be used in unit tests.
     * <br>
     * 设置自定义的基础URL，仅用于测试目的。
     *
     * @param url the custom base URL, or null to reset to default
     */
    static void setBaseUrlForTesting(String url) {
        customBaseUrl = url;
    }
    
    /**
     * Reset the base URL to force re-initialization.
     * This should only be used in unit tests.
     * <br>
     * 重置基础URL以强制重新初始化，仅用于测试目的。
     */
    static void resetBaseUrl() {
        baseUrl = null;
        customBaseUrl = null;
    }

    /**
     * Extract the "data" field from a backend API envelope response.
     * The backend wraps responses as {"code":"200","msg":"Success","data":...}.
     * <br>
     * 从后端API信封响应中提取"data"字段。
     *
     * @param body the raw JSON response body
     * @param clazz the class to deserialize the data field into
     * @param <T> the target type
     * @return the deserialized data, or null if absent or response indicates error
     */
    private static <T> T unwrapData(String body, Class<T> clazz) {
        try {
            JsonObject wrapper = JsonParser.parseString(body).getAsJsonObject();
            String code = wrapper.has("code") ? wrapper.get("code").getAsString() : null;
            if (!"200".equals(code)) {
                return null;
            }
            JsonElement data = wrapper.get("data");
            if (data == null || data.isJsonNull()) {
                return null;
            }
            return GSON.fromJson(data, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the "data" field from a backend API envelope response (generic type version).
     * <br>
     * 从后端API信封响应中提取"data"字段（泛型版本）。
     *
     * @param body the raw JSON response body
     * @param type the Type to deserialize the data field into
     * @param <T> the target type
     * @return the deserialized data, or null if absent or response indicates error
     */
    private static <T> T unwrapData(String body, Type type) {
        try {
            JsonObject wrapper = JsonParser.parseString(body).getAsJsonObject();
            String code = wrapper.has("code") ? wrapper.get("code").getAsString() : null;
            if (!"200".equals(code)) {
                return null;
            }
            JsonElement data = wrapper.get("data");
            if (data == null || data.isJsonNull()) {
                return null;
            }
            return GSON.fromJson(data, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract a String value from the "data" field of a backend API envelope response.
     * Handles both quoted JSON strings and primitive values.
     * <br>
     * 从后端API信封响应的"data"字段中提取字符串值。
     *
     * @param body the raw JSON response body
     * @return the data as a String, or null if absent or response indicates error
     */
    private static String unwrapStringData(String body) {
        try {
            JsonObject wrapper = JsonParser.parseString(body).getAsJsonObject();
            String code = wrapper.has("code") ? wrapper.get("code").getAsString() : null;
            if (!"200".equals(code)) {
                return null;
            }
            JsonElement data = wrapper.get("data");
            if (data == null || data.isJsonNull()) {
                return null;
            }
            if (data.isJsonPrimitive()) {
                return data.getAsString();
            }
            return data.toString();
        } catch (Exception e) {
            return null;
        }
    }

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
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/list?page=" + page + "&pageSize=" + pageSize)) {
            if (!httpResponse.isOk()) {
                return pluginEntities;
            }
            String body = httpResponse.body();
            Type listType = new TypeToken<List<PluginEntity>>(){}.getType();
            List<PluginEntity> result = unwrapData(body, listType);
            return result != null ? result : pluginEntities;
        }
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
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/" + plugin.getId() + "/" + version + "/download")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapStringData(httpResponse.body());
        }
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
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/" + plugin.getId() + "/versions")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            Type listType = new TypeToken<List<String>>(){}.getType();
            return unwrapData(httpResponse.body(), listType);
        }
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
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/" + plugin.getId() + "/latest")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapStringData(httpResponse.body());
        }
    }

    /**
     * Get plugin latest version download link.
     * <br>
     * 获取插件最新版本下载链接。
     *
     * @param idString Plugin identify string <br> 插件ID
     * @return Download link for latest version <br> 插件最新版本下载链接
     */
    public static String getPluginLatestDownloadLink(String idString) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/" + plugin.getId() + "/latest/download")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapStringData(httpResponse.body());
        }
    }

    /**
     * Get plugin information by identify string.
     * <br>
     * 通过标识字符串获取插件信息。
     *
     * @param idString Plugin identify string <br> 插件ID
     * @return Plugin entity or null if not found <br> 插件信息，未找到返回null
     */
    public static PluginEntity getPlugin(String idString) {
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/get?identifyString=" + idString)) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapData(httpResponse.body(), PluginEntity.class);
        }
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

        try {
            HttpDownloadUtils.download(pluginVersionDownloadLink,
                    pluginVersionDownloadLink.substring(pluginVersionDownloadLink.lastIndexOf("/") + 1),
                    UltiTools.getInstance().getDataFolder() + "/plugins");
            return true;
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download plugin: " + e.getMessage());
            return false;
        }
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

        try {
            HttpDownloadUtils.download(pluginVersionDownloadLink,
                    pluginVersionDownloadLink.substring(pluginVersionDownloadLink.lastIndexOf("/") + 1),
                    UltiTools.getInstance().getDataFolder() + "/plugins");
            return true;
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download plugin: " + e.getMessage());
            return false;
        }
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
        File[] listFiles = folder.listFiles();
        if (listFiles == null) {
            return false;
        }
        for (File file : listFiles) {
            URL url = URI.create("jar:file:" + file.getAbsolutePath() + "!/plugin.yml").toURL();
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
