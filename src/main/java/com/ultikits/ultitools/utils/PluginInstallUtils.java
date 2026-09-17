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
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.ultikits.ultitools.manager.PluginManager;
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
        List<File> jars = findPluginJars(pluginsFolder, identifyString);
        return jars.isEmpty() ? null : jars.get(0);
    }

    /**
     * Finds every jar in {@code pluginsFolder} whose {@code plugin.yml} {@code identify-string}
     * matches, in the folder's listing order -- the same matching as {@link #findPluginJar}, which
     * returns only the first.
     *
     * @param pluginsFolder  the folder to search
     * @param identifyString the module's identify string
     * @return every matching jar; empty when none matches or the folder cannot be listed
     */
    private static List<File> findPluginJars(File pluginsFolder, String identifyString) {
        List<File> matches = new ArrayList<>();
        String normalizedIdentifyString = normalizeIdentifyString(identifyString);
        if (pluginsFolder == null || !pluginsFolder.isDirectory() || normalizedIdentifyString == null) {
            return matches;
        }
        File[] jars = pluginsFolder.listFiles((f) -> f.getName().endsWith(".jar"));
        if (jars == null) {
            return matches;
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
                        matches.add(jar);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable plugin JAR: " + jar.getName(), e);
            }
        }
        return matches;
    }

    /**
     * Update a plugin module: download latest version and delete old JAR.
     *
     * @param identifyString the plugin identify string
     * @return true if the new version was downloaded and the old JAR (if any) deleted
     * @throws java.io.UncheckedIOException wrapping a {@link java.nio.file.FileSystemException}
     *     when the new version was downloaded but an older JAR of the module could not be deleted;
     *     {@link java.nio.file.FileSystemException#getFile()} names one such JAR and each further
     *     one is attached as a suppressed {@code FileSystemException}. Every JAR named would
     *     otherwise load next to the new one on restart (#505)
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

        try {
            HttpDownloadUtils.download(downloadLink, fileName, pluginsPath);
        } catch (IOException e) {
            UltiTools.getInstance().getLogger().severe("Failed to download update: " + e.getMessage());
            return false;
        }

        // Delete every other JAR of this module, looked up AFTER the download (review CR-02): a
        // single pre-download lookup returns whichever matching JAR the folder lists first, and on
        // a retry after a failed delete that can be the already-downloaded new JAR, which skipped
        // the delete and reported success with the old JAR still on disk.
        Path downloaded = new File(pluginsFolder, fileName).toPath();
        List<File> olderJars = new ArrayList<>();
        for (File jar : findPluginJars(pluginsFolder, identifyString)) {
            if (!isDownloadedFile(jar, downloaded)) {
                olderJars.add(jar);
            }
        }
        // Report the deletes' real outcome (#505): an old JAR left on disk loads next to the new
        // one on restart, so every failure must reach the operator rather than read as success.
        // Unchecked, because this public method declares no checked exception.
        try {
            deleteAllOrThrow(olderJars);
        } catch (FileSystemException e) {
            throw new UncheckedIOException(e);
        }

        return true;
    }

    /**
     * Whether {@code candidate} is the file {@link #updatePlugin(String)} just downloaded to
     * {@code downloaded}, compared by file identity rather than by name (review r2 WR-01). A
     * second name for the same file -- a differently-cased stored name on a case-insensitive
     * filesystem, a symbolic link, a short name -- otherwise made the download look like an older
     * JAR and deleted it. A candidate that can no longer be examined because it has vanished is
     * not the download, and there is nothing left to delete, so it is skipped by returning
     * {@code true}; any other candidate that cannot be compared is treated as an older JAR, so its
     * delete is attempted and a failure is reported.
     *
     * @param candidate  a JAR of the module found in the plugins folder after the download
     * @param downloaded the path the new version was downloaded to
     * @return whether {@code candidate} must be kept rather than deleted as an older JAR
     */
    private static boolean isDownloadedFile(File candidate, Path downloaded) {
        try {
            return Files.isSameFile(candidate.toPath(), downloaded);
        } catch (IOException e) {
            return !Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS);
        }
    }

    /**
     * Uninstall plugin: unload every loaded module whose runtime name matches, then delete every
     * jar in the plugins folder whose {@code plugin.yml} {@code name} matches.
     *
     * @param name the plugin's runtime name ({@code plugin.yml} {@code name})
     * @return {@code true} if at least one matching jar was found and every matching jar was
     *     deleted; {@code false} if no module of that name was loaded and no jar in the plugins
     *     folder carries that name
     * @throws java.nio.file.NoSuchFileException if a loaded module of that name was unloaded but
     *     no jar in the plugins folder carries its name; {@link
     *     java.nio.file.FileSystemException#getFile()} names the plugins folder
     * @throws IllegalStateException if a matching module's own unload threw. The module has still
     *     been removed from the loaded modules and every matching jar deletion has still been
     *     attempted; the module's exception is the cause, and the jar outcome that would otherwise
     *     have been thrown (a {@code FileSystemException} or {@code NoSuchFileException} as above)
     *     is attached as a suppressed exception -- none is attached when every jar was deleted
     * @throws java.nio.file.FileSystemException if a matching jar could not be deleted; {@link
     *     java.nio.file.FileSystemException#getFile()} names one such jar and each further one is
     *     attached as a suppressed {@code FileSystemException}. Every jar named will load again on
     *     the next restart
     * @throws IOException if another I/O error occurs
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // deliberate barrier: a module's unload failure is collected and reported, see the loop
    public static boolean uninstallPlugin(String name) throws IOException {
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        List<UltiToolsPlugin> matches = new ArrayList<>();
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            if (plugin.getPluginName().equals(name)) {
                matches.add(plugin);
            }
        }
        Throwable unloadFailure = null;
        for (UltiToolsPlugin plugin : matches) {
            // Unload through PluginManager#unregister, the framework's one full unload path
            // (#503): it cancels the module's @Scheduled tasks and releases its @PlayerCache,
            // tab-completion, EventBus, panel-responder and @ConditionalOnConfig registrations
            // before calling unregisterSelf(), then closes the module context. Calling
            // unregisterSelf() directly skipped all of that. The module leaves the plugin list
            // even if its own unload hook throws, because unregister() has closed its context
            // by then and a listed-but-closed module would be reported as still loaded.
            //
            // A throwing unload is collected, not propagated (review WR-01): the module is already
            // unloaded and closed at that point, so the jar is still deleted -- keeping it would
            // bring back on restart a module the operator asked to remove -- and the failure is
            // reported together with the jar outcome below.
            try {
                pluginManager.unregister(plugin);
            } catch (Exception | Error e) {
                LOGGER.log(Level.SEVERE, "Module " + name + " threw while unloading for uninstall; "
                        + "it has been removed from the loaded modules", e);
                if (unloadFailure == null) {
                    unloadFailure = e;
                } else {
                    unloadFailure.addSuppressed(e);
                }
            } finally {
                pluginManager.getPluginList().remove(plugin);
            }
        }
        boolean jarsDeleted;
        try {
            jarsDeleted = deleteModuleJars(name, !matches.isEmpty());
        } catch (IOException jarFailure) {
            if (unloadFailure != null) {
                throw unloadFailed(name, unloadFailure, jarFailure);
            }
            throw jarFailure;
        }
        if (unloadFailure != null) {
            throw unloadFailed(name, unloadFailure, null);
        }
        return jarsDeleted;
    }

    /**
     * The jar half of {@link #uninstallPlugin(String)}: finds and deletes every jar whose {@code
     * plugin.yml} {@code name} matches.
     *
     * @param name           the module name
     * @param moduleUnloaded whether a loaded module of that name was unloaded first
     * @return {@code true} if every matching jar was deleted; {@code false} if no jar matched and
     *     no module was unloaded
     * @throws IOException as documented on {@link #uninstallPlugin(String)}
     */
    private static boolean deleteModuleJars(String name, boolean moduleUnloaded) throws IOException {
        File folder = new File(UltiTools.getInstance().getDataFolder() + "/plugins");
        File[] listFiles = folder.listFiles();
        if (listFiles == null) {
            return noJarFound(folder, name, moduleUnloaded);
        }
        List<File> matchingJars = new ArrayList<>();
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
                matchingJars.add(file);
            }
        }
        if (matchingJars.isEmpty()) {
            return noJarFound(folder, name, moduleUnloaded);
        }
        // Delete every matching jar, not only the first one listed (review WR-02): a second jar of
        // the same module loads it again on restart. Report the real outcome (#501): every jar
        // that stays on disk is named, so success is reported only once all of them are gone.
        deleteAllOrThrow(matchingJars);
        return true;
    }

    /**
     * Builds the failure {@link #uninstallPlugin(String)} throws when a module's own unload threw.
     *
     * @param name          the module name
     * @param unloadFailure the module's exception
     * @param jarFailure    the jar outcome that would otherwise have been thrown, or {@code null}
     *                      when every matching jar was deleted
     * @return the exception to throw
     */
    private static IllegalStateException unloadFailed(String name, Throwable unloadFailure, IOException jarFailure) {
        IllegalStateException failure = new IllegalStateException(
                "Module " + name + " was removed from the loaded modules, but its unload threw", unloadFailure);
        if (jarFailure != null) {
            failure.addSuppressed(jarFailure);
        }
        return failure;
    }

    /**
     * The "no matching jar" outcome of {@link #uninstallPlugin(String)} (review WR-03): {@code
     * false} only when nothing of that name was loaded either, so the name really matched
     * nothing; otherwise the module has just been unloaded, and reporting a misspelling would be
     * false.
     *
     * @param folder        the plugins folder that was searched
     * @param name          the module name that was searched for
     * @param moduleUnloaded whether a loaded module of that name was unloaded
     * @return {@code false} when {@code moduleUnloaded} is false
     * @throws NoSuchFileException when {@code moduleUnloaded} is true, naming {@code folder}
     */
    private static boolean noJarFound(File folder, String name, boolean moduleUnloaded) throws NoSuchFileException {
        if (moduleUnloaded) {
            throw new NoSuchFileException(folder.getAbsolutePath(), null, "no module JAR named " + name);
        }
        return false;
    }

    /**
     * Deletes every file in {@code files}, attempting each even after an earlier one fails.
     *
     * @param files the files to delete
     * @throws FileSystemException if any file could not be deleted; {@link
     *     FileSystemException#getFile()} names the first such file and each further one is
     *     attached as a suppressed {@code FileSystemException}
     */
    private static void deleteAllOrThrow(List<File> files) throws FileSystemException {
        FileSystemException failure = null;
        for (File file : files) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                FileSystemException fileFailure =
                        new FileSystemException(file.getAbsolutePath(), null, e.getMessage());
                fileFailure.initCause(e);
                if (failure == null) {
                    failure = fileFailure;
                } else {
                    failure.addSuppressed(fileFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
