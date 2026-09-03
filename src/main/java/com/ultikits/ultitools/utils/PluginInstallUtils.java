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
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class PluginInstallUtils {
    private static final Logger LOGGER = Logger.getLogger(PluginInstallUtils.class.getName());
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .create();

    // Use lazy initialization to avoid static initializer dependency on UltiTools
    private static volatile String baseUrl;
    
    // Custom base URL for testing purposes
    private static String customBaseUrl;
    
    /**
     * Get the base URL for API calls.
     * Uses lazy initialization to avoid requiring UltiTools at class loading time.
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
     *
     * @param url the custom base URL, or null to reset to default
     */
    static void setBaseUrlForTesting(String url) {
        customBaseUrl = url;
    }
    
    /**
     * Reset the base URL to force re-initialization.
     * This should only be used in unit tests.
     */
    static void resetBaseUrl() {
        baseUrl = null;
        customBaseUrl = null;
    }

    private static String normalizeIdentifyString(String idString) {
        if (idString == null) {
            return null;
        }
        String normalized = idString.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String encodeQueryValue(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the Java runtime", e);
        }
    }

    private static String encodePathSegment(String value) {
        return encodeQueryValue(value).replace("+", "%20");
    }

    static String installedJarName(String idString, String version) {
        String normalized = normalizeIdentifyString(idString);
        if (normalized == null || version == null) {
            return null;
        }
        String trimmedVersion = version.trim();
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")
                || !trimmedVersion.matches("[0-9A-Za-z][0-9A-Za-z._-]*")
                || normalized.contains("..") || trimmedVersion.contains("..")) {
            return null;
        }
        return normalized + "-" + trimmedVersion + ".jar";
    }

    /**
     * Extract the "data" field from a backend API envelope response.
     * The backend wraps responses as {"code":"200","msg":"Success","data":...}.
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
     *
     * @param page     the page number
     * @param pageSize the number of items per page
     * @return the plugin list
     */
    public static List<PluginEntity> getPluginList(int page, int pageSize) {
        List<PluginEntity> pluginEntities = new ArrayList<>();
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/list?page=" + page + "&pageSize=" + pageSize)) {
            if (!httpResponse.isOk()) {
                return pluginEntities;
            }
            String body = httpResponse.body();
            Type listType = new TypeToken<List<PluginEntity>>(){}.getType();
            // Backend returns paginated wrapper: {items:[...], total, page, pageSize}
            // Extract items array from the wrapper, falling back to direct list for backwards compat
            try {
                JsonObject wrapper = JsonParser.parseString(body).getAsJsonObject();
                String code = wrapper.has("code") ? wrapper.get("code").getAsString() : null;
                if (!"200".equals(code)) {
                    return pluginEntities;
                }
                JsonElement data = wrapper.get("data");
                if (data == null || data.isJsonNull()) {
                    return pluginEntities;
                }
                // Handle paginated wrapper format: {items: [...], total, page, pageSize}
                if (data.isJsonObject() && data.getAsJsonObject().has("items")) {
                    JsonElement items = data.getAsJsonObject().get("items");
                    List<PluginEntity> result = GSON.fromJson(items, listType);
                    return result != null ? result : pluginEntities;
                }
                // Fallback: direct list format
                List<PluginEntity> result = GSON.fromJson(data, listType);
                return result != null ? result : pluginEntities;
            } catch (Exception e) {
                return pluginEntities;
            }
        }
    }

    /**
     * Get plugin download link.
     *
     * @param idString the plugin ID
     * @param version  the version
     * @return the plugin download link
     */
    public static String getPluginVersionDownloadLink(String idString, String version) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return null;
        }
        if (version == null || version.trim().isEmpty()) {
            return null;
        }
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/" + plugin.getId() + "/" + encodePathSegment(version.trim()) + "/download")) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapStringData(httpResponse.body());
        }
    }

    /**
     * Get plugin versions.
     *
     * @param idString the plugin ID
     * @return the plugin version list
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
     *
     * @param idString the plugin ID
     * @return the plugin's latest version
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
     *
     * @param idString Plugin identify string
     * @return Download link for latest version
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
     *
     * @param idString Plugin identify string
     * @return Plugin entity or null if not found
     */
    public static PluginEntity getPlugin(String idString) {
        String normalized = normalizeIdentifyString(idString);
        if (normalized == null) {
            return null;
        }
        try (Response httpResponse = SimpleHttpClient.get(getBaseUrl() + "/plugin/get?identifyString=" + encodeQueryValue(normalized))) {
            if (!httpResponse.isOk()) {
                return null;
            }
            return unwrapData(httpResponse.body(), PluginEntity.class);
        }
    }

    /**
     * Install latest plugin.
     *
     * @param idString the plugin ID
     * @return whether the install succeeded
     */
    public static boolean installLatestPlugin(String idString) {
        String latestVersion = getPluginLatestVersion(idString);
        String pluginVersionDownloadLink = getPluginVersionDownloadLink(idString, latestVersion);
        String fileName = installedJarName(idString, latestVersion);
        if (pluginVersionDownloadLink == null || fileName == null) {
            return false;
        }

        try {
            HttpDownloadUtils.download(pluginVersionDownloadLink,
                    fileName,
                    UltiTools.getInstance().getDataFolder() + "/plugins");
            return true;
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download plugin: " + e.getMessage());
            return false;
        }
    }

    /**
     * Install plugin.
     *
     * @param idString the plugin ID
     * @param version  the version
     * @return whether the install succeeded
     */
    public static boolean installPlugin(String idString, String version) {
        PluginEntity plugin = getPlugin(idString);
        if (plugin == null) {
            return false;
        }
        String pluginVersionDownloadLink = getPluginVersionDownloadLink(idString, version);
        String fileName = installedJarName(idString, version);
        if (pluginVersionDownloadLink == null || fileName == null) {
            return false;
        }

        try {
            HttpDownloadUtils.download(pluginVersionDownloadLink,
                    fileName,
                    UltiTools.getInstance().getDataFolder() + "/plugins");
            return true;
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download plugin: " + e.getMessage());
            return false;
        }
    }

    /**
     * Find the JAR file for a plugin by its identify-string.
     * Scans all JARs in the given directory, reads plugin.yml from each.
     *
     * @param pluginsFolder the folder to scan
     * @param identifyString the identify-string to match
     * @return the matching JAR file, or null if not found
     */
    public static File findPluginJar(File pluginsFolder, String identifyString) {
        String normalizedIdentifyString = normalizeIdentifyString(identifyString);
        if (pluginsFolder == null || !pluginsFolder.isDirectory() || normalizedIdentifyString == null) {
            return null;
        }
        File[] jars = pluginsFolder.listFiles((f) -> f.getName().endsWith(".jar"));
        if (jars == null) {
            return null;
        }
        for (File jar : jars) {
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
                java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
                if (entry == null) {
                    continue;
                }
                try (InputStream is = jarFile.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
                    String id = config.getString("identify-string");
                    if (normalizedIdentifyString.equals(normalizeIdentifyString(id))) {
                        return jar;
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable plugin JAR: " + jar.getName(), e);
            }
        }
        return null;
    }

    /**
     * Update a plugin module: download latest version and delete old JAR.
     *
     * @param identifyString the plugin identify string
     * @return true if update succeeded
     */
    public static boolean updatePlugin(String identifyString) {
        String latestVersion = getPluginLatestVersion(identifyString);
        String downloadLink = getPluginVersionDownloadLink(identifyString, latestVersion);
        String fileName = installedJarName(identifyString, latestVersion);
        if (downloadLink == null || fileName == null) {
            return false;
        }

        String pluginsPath = UltiTools.getInstance().getDataFolder() + "/plugins";
        File pluginsFolder = new File(pluginsPath);
        File oldJar = findPluginJar(pluginsFolder, identifyString);

        try {
            HttpDownloadUtils.download(downloadLink, fileName, pluginsPath);
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download update: " + e.getMessage());
            return false;
        }

        // Delete old JAR if it's a different file than the new download
        if (oldJar != null && !oldJar.getName().equals(fileName)) {
            oldJar.delete();
        }

        return true;
    }

    /**
     * Uninstall plugin.
     *
     * @param name the plugin name
     * @return whether the uninstall succeeded
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
