package com.ultikits.ultitools.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLEncoder;
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
     * Uninstalls a module: unloads every loaded instance of it and deletes its JARs.
     *
     * <p>Unloading goes through {@link PluginManager#unregister(UltiToolsPlugin)}, the framework's
     * one full unload path (#503). Calling {@code plugin.unregisterSelf()} directly, as this method
     * used to, skipped everything {@code unregister} does first -- cancelling the module's
     * {@code @Scheduled} tasks, releasing its {@code @PlayerCache} beans, its tab-completion
     * completers, its EventBus handlers and its conditional-bean records, then closing its context.
     * A module "uninstalled" that way kept running its repeating tasks until the next restart.
     *
     * <p>What it reports is what happened on disk (#501). The old implementation ignored
     * {@link File#delete()}'s result and returned {@code true} whether or not the JAR was removed,
     * and it stopped at the first matching JAR, so a second JAR of the same module loaded it again
     * on restart. Now every JAR whose {@code plugin.yml} {@code name} matches is deleted, and what the
     * method returns or throws says which of those things happened.
     *
     * @param name the module's runtime name, as its {@code plugin.yml} declares it
     * @return {@code true} when every matching JAR was deleted; {@code false} when no JAR matched
     *     and no loaded module of that name was unloaded either -- the "check the spelling" case
     * @throws java.nio.file.FileSystemException when a matching JAR could not be deleted, naming
     *     every JAR still on disk
     * @throws java.nio.file.NoSuchFileException when a loaded module was unloaded but no JAR of it
     *     could be found, which is not a spelling mistake and must not be reported as one
     * @throws IllegalStateException when the module's own unload threw. The module is still removed
     *     from the loaded modules and its JARs are still deleted: {@code unregister} has closed its
     *     context by then, and keeping the JAR would bring back on restart a module the operator
     *     asked to remove. A JAR failure is attached as suppressed.
     * @throws IOException if the modules folder cannot be read
     */
    public static boolean uninstallPlugin(String name) throws IOException {
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        List<UltiToolsPlugin> matches = new ArrayList<>();
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            if (plugin.getPluginName().equals(name)) {
                matches.add(plugin);
            }
        }
        Throwable unloadFailure = unloadEvery(name, matches, pluginManager);
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
     * Unloads every matching module through the framework's full unload path and delists it.
     *
     * <p>A module that throws while unloading is collected rather than propagated: it is already
     * unloaded and its context already closed, so the rest of the uninstall goes ahead and the
     * failure is reported together with the JAR outcome. It leaves the plugin list either way,
     * because a listed module whose context is closed would be reported as still loaded.
     *
     * @param name          the module's runtime name, for the log line
     * @param matches       the loaded instances of it
     * @param pluginManager the manager that owns the plugin list
     * @return the first unload failure with any later one attached, or {@code null}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one module's failure must not stop the uninstall
    private static Throwable unloadEvery(String name, List<UltiToolsPlugin> matches, PluginManager pluginManager) {
        Throwable unloadFailure = null;
        for (UltiToolsPlugin plugin : matches) {
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
        return unloadFailure;
    }

    /**
     * The JAR half of {@link #uninstallPlugin(String)}: deletes every JAR whose {@code plugin.yml}
     * {@code name} matches, not only the first one listed -- a second JAR of the same module loads
     * it again on restart.
     *
     * @param name           the module's runtime name
     * @param moduleUnloaded whether a loaded module of that name was unloaded first
     * @return {@code true} if every matching JAR was deleted
     * @throws IOException as documented on {@link #uninstallPlugin(String)}
     */
    private static boolean deleteModuleJars(String name, boolean moduleUnloaded) throws IOException {
        File folder = new File(UltiTools.getInstance().getDataFolder() + "/plugins");
        File[] listFiles = folder.listFiles();
        if (listFiles == null) {
            return noJarFound(folder, name, moduleUnloaded);
        }
        List<File> matchingJars = new ArrayList<>();
        List<File> unreadableJars = new ArrayList<>();
        for (File file : listFiles) {
            // Anything that is not a readable module JAR is skipped rather than fatal: the old
            // implementation built a `jar:file:` URL for every entry in the folder, so a stray
            // file or a subdirectory failed the whole uninstall (#504). A JAR that cannot be read
            // is kept separately: it says nothing about itself, which is not the same as saying it
            // is not this module's.
            String declared = moduleNameOf(file);
            if (name.equals(declared)) {
                matchingJars.add(file);
            } else if (declared == null && file.isFile() && file.getName().endsWith(".jar")) {
                unreadableJars.add(file);
            }
        }
        if (matchingJars.isEmpty()) {
            if (moduleUnloaded && !unreadableJars.isEmpty()) {
                // The module was loaded from somewhere and no JAR here can say whether it is the
                // one. Reporting them beats reporting that the module has no JAR at all.
                throw unreadableJarsMayBeThisModules(name, unreadableJars);
            }
            List<File> suspects = namedLikeThisModule(unreadableJars, name);
            if (!suspects.isEmpty()) {
                throw unreadableJarsMayBeThisModules(name, suspects);
            }
            return noJarFound(folder, name, moduleUnloaded);
        }
        IOException jarFailure = null;
        try {
            deleteAllOrThrow(matchingJars);
        } catch (IOException e) {
            jarFailure = e;
        }
        // Deleting the JARs that could be identified says nothing about one that could not be read:
        // a second copy of this module loads it again once the file is readable, and the uninstall
        // would have reported that every copy is gone. Only the ones named like this module, since
        // an unrelated unreadable file must not stop an uninstall and the file name is the only
        // evidence left once the metadata cannot be read.
        List<File> suspects = namedLikeThisModule(unreadableJars, name);
        if (!suspects.isEmpty()) {
            java.nio.file.FileSystemException unreadable = unreadableJarsMayBeThisModules(name, suspects);
            if (jarFailure == null) {
                throw unreadable;
            }
            jarFailure.addSuppressed(unreadable);
        }
        if (jarFailure != null) {
            throw jarFailure;
        }
        return true;
    }

    /**
     * The JARs among {@code candidates} whose file name reads like a copy of this module: the
     * framework installs a module as {@code <identify-string>-<version>.jar} and an operator's own
     * copy is normally named after the module too.
     *
     * @param candidates JARs whose metadata could not be read
     * @param name       the module's runtime name
     * @return the subset that cannot be ruled out on its name
     */
    private static List<File> namedLikeThisModule(List<File> candidates, String name) {
        List<File> named = new ArrayList<>();
        String prefix = name.toLowerCase(Locale.ROOT);
        for (File candidate : candidates) {
            String fileName = candidate.getName().toLowerCase(Locale.ROOT);
            if (fileName.startsWith(prefix + "-") || fileName.equals(prefix + ".jar")) {
                named.add(candidate);
            }
        }
        return named;
    }

    /**
     * The failure reported for JARs that could not be read while uninstalling {@code name}. Each is
     * named, because each loads the module again once it is readable.
     *
     * @param name       the module's runtime name
     * @param unreadable the JARs that could not be read
     * @return the exception to throw, one suppressed entry per further JAR
     */
    private static java.nio.file.FileSystemException unreadableJarsMayBeThisModules(String name,
                                                                                    List<File> unreadable) {
        java.nio.file.FileSystemException failure = null;
        for (File jar : unreadable) {
            java.nio.file.FileSystemException next = new java.nio.file.FileSystemException(
                    jar.getAbsolutePath(), null, "could not be read, so it cannot be ruled out as a JAR of module "
                    + name + "; it loads the module again once it is readable");
            if (failure == null) {
                failure = next;
            } else {
                failure.addSuppressed(next);
            }
        }
        LOGGER.severe("Uninstalling module " + name + ": " + unreadable.size() + " JAR(s) could not be read and"
                + " are reported rather than deleted");
        return failure;
    }

    /**
     * The {@code plugin.yml} {@code name} a JAR declares.
     *
     * @param file an entry of the modules folder
     * @return the name, or {@code null} when this is not a JAR that declares one
     */
    private static String moduleNameOf(File file) {
        if (!file.isFile() || !file.getName().endsWith(".jar")) {
            return null;
        }
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) {
                return null;
            }
            try (InputStream is = jarFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return YamlConfiguration.loadConfiguration(reader).getString("name");
            }
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Skipping unreadable plugin JAR: " + file.getName(), e);
            return null;
        }
    }

    /**
     * Deletes every file, and reports the ones that are still there.
     *
     * @param files the JARs to delete
     * @throws java.nio.file.FileSystemException naming the first JAR that survived, with any other
     *     attached as suppressed -- each one of them loads the module again on restart
     */
    private static void deleteAllOrThrow(List<File> files) throws java.nio.file.FileSystemException {
        java.nio.file.FileSystemException failure = null;
        for (File file : files) {
            try {
                // A file already gone is the outcome asked for, not a failure: reporting it would
                // name a JAR that is no longer on disk.
                java.nio.file.Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                java.nio.file.FileSystemException fileFailure =
                        new java.nio.file.FileSystemException(file.getAbsolutePath(), null, e.getMessage());
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

    /**
     * The failure raised when a module's own unload threw, after the uninstall went ahead anyway.
     *
     * @param name          the module's runtime name
     * @param unloadFailure what its unload threw
     * @param jarFailure    the JAR outcome to attach, or {@code null} when the JARs were deleted
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
     * What "no JAR of this module is here" means, which depends on whether one was loaded.
     *
     * @param folder         the modules folder
     * @param name           the module's runtime name
     * @param moduleUnloaded whether a loaded module of that name was unloaded first
     * @return {@code false}, the "no such module" answer, when nothing was unloaded either
     * @throws java.nio.file.NoSuchFileException when a module was unloaded and its JAR is missing
     */
    private static boolean noJarFound(File folder, String name, boolean moduleUnloaded)
            throws java.nio.file.NoSuchFileException {
        if (moduleUnloaded) {
            // The module was loaded from somewhere, so "check the spelling" is the wrong answer:
            // the operator needs to know the module is unloaded and its JAR was not found (#501).
            throw new java.nio.file.NoSuchFileException(folder.getAbsolutePath(), null,
                    "no module JAR named " + name);
        }
        return false;
    }

}
