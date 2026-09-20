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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

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

    /** {@link #codeSourceJarOf(Class)} for a loaded module, and the seam a test replaces. */
    static final java.util.function.Function<UltiToolsPlugin, File> DEFAULT_MODULE_CODE_SOURCE =
            module -> codeSourceJarOf(module.getClass());

    /** How the uninstall asks a loaded module which JAR it came from. */
    static java.util.function.Function<UltiToolsPlugin, File> moduleCodeSource = DEFAULT_MODULE_CODE_SOURCE;
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
     * The JAR a module class was loaded from, or {@code null} when it was not loaded from one.
     *
     * <p>A module that is loaded does not have to be identified by metadata at all: UltiTools
     * modules are recognised by {@code @UltiToolsModule}, and {@code PluginManager} selects a
     * module class with {@code UltiToolsPlugin.class.isAssignableFrom} and instantiates it through
     * {@code getDeclaredConstructor().newInstance()} -- no {@code plugin.yml} is consulted. So the
     * framework asks the instance instead, exactly as {@code UltiToolsPlugin} itself does when it
     * reads its own embedded resources.
     *
     * <p>A code source that is a directory is a development checkout rather than an installed JAR,
     * and answers nothing about which file to delete; the caller falls through to the table.
     *
     * @param moduleClass the loaded module's class
     * @return the JAR, or {@code null} when there is none to name
     */
    static File codeSourceJarOf(Class<?> moduleClass) {
        try {
            java.security.CodeSource source = moduleClass.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File location = resolveCodeSourceFile(source.getLocation());
            return location.isFile() ? location : null;
        } catch (SecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.FINE, "Could not read the code source of " + moduleClass, e);
            return null;
        }
    }

    /** A code source URL as a file, whatever escaping it carries. */
    private static File resolveCodeSourceFile(URL location) {
        try {
            return new File(location.toURI());
        } catch (java.net.URISyntaxException e) {
            String rawPath = location.getPath();
            return new File(rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
        }
    }

    /**
     * The JARs the loaded instances of this module were loaded from, read before they are unloaded
     * -- afterwards the instance and its class loader may be gone, and with them the answer.
     *
     * @param modules the loaded instances
     * @return their code-source JARs, by canonical path
     */
    private static Set<String> codeSourceJarsOf(List<UltiToolsPlugin> modules) {
        Set<String> jars = new java.util.HashSet<>();
        for (UltiToolsPlugin module : modules) {
            File jar = moduleCodeSource.apply(module);
            if (jar == null) {
                continue;
            }
            jars.add(canonicalPathOf(jar));
        }
        return jars;
    }

    /** {@code file}'s canonical path, or its absolute path when it cannot be canonicalised. */
    private static String canonicalPathOf(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not canonicalise " + file, e);
            return file.getAbsolutePath();
        }
    }

    /**
     * What one entry of the modules folder is, relative to the module being uninstalled. Every
     * entry is exactly one of these, and each is decided by a positive test -- never by the file's
     * name, which says nothing about what a JAR declares.
     */
    private enum EntryState {
        /**
         * It is a loaded instance's own code-source JAR, or the archive opened and its
         * {@code plugin.yml} declares this module.
         */
        THIS_MODULES,
        /** The archive opened and its {@code plugin.yml} declares another module. */
        NOT_THIS_MODULES,
        /**
         * Nothing could be read from it: the archive would not open, its {@code plugin.yml} entry
         * could not be read, or that file is not valid YAML. Unknown is not "no".
         */
        UNDETERMINED
    }

    /**
     * The entries an uninstall could not read, carried on whatever failure leaves the method.
     *
     * <p>State C outlives every other outcome: a module whose unload threw, a JAR that could not
     * be deleted and "nothing here could be identified as this module's" are all still true beside
     * "these entries said nothing", and one of those entries may be the module's own JAR. An
     * explicit type is what lets a caller tell this apart from the failures it travels with,
     * rather than guessing from the shape of a {@code FileSystemException}.
     */
    @ApiStatus.Internal
    public static final class UndeterminedEntriesException extends java.nio.file.FileSystemException {
        private static final long serialVersionUID = 1L;

        private final List<String> entries;

        private UndeterminedEntriesException(List<String> entries) {
            super(entries.isEmpty() ? null : entries.get(0), null,
                    "could not be read, so whether any of them is a JAR of this module is unknown");
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        /**
         * @param entries the absolute paths of the entries whose identity could not be determined
         * @return the carrier for them
         */
        public static UndeterminedEntriesException of(List<String> entries) {
            return new UndeterminedEntriesException(entries);
        }

        /** @return the absolute paths of the entries whose identity could not be determined */
        public List<String> entries() {
            return entries;
        }
    }

    /**
     * Attaches the undetermined entries to a failure on its way out, when there are any.
     *
     * @param failure the failure leaving the uninstall
     * @param entries the absolute paths of the entries nothing could be read from
     * @param <T>     the failure's type
     * @return {@code failure}
     */
    private static <T extends Throwable> T carrying(T failure, List<String> entries) {
        if (!entries.isEmpty()) {
            failure.addSuppressed(new UndeterminedEntriesException(entries));
        }
        return failure;
    }

    /**
     * What an uninstall did, and what it could not determine.
     *
     * <p>The entries it could not classify are not failures -- an entry nothing can be read from
     * must never stop an uninstall -- but they are not nothing either: if one of them is a copy of
     * this module, it loads again at the next start. Reporting them is what lets the operator
     * decide, which is not the same as guessing on their behalf.
     */
    @ApiStatus.Internal
    public static final class UninstallReport {
        private final boolean jarsDeleted;
        private final List<String> undetermined;

        private UninstallReport(boolean jarsDeleted, List<String> undetermined) {
            this.jarsDeleted = jarsDeleted;
            this.undetermined = Collections.unmodifiableList(new ArrayList<>(undetermined));
        }

        /**
         * @param jarsDeleted  whether every entry identified as this module's was deleted
         * @param undetermined the absolute paths of the entries nothing could be read from
         * @return the report
         */
        public static UninstallReport of(boolean jarsDeleted, List<String> undetermined) {
            return new UninstallReport(jarsDeleted, undetermined);
        }

        /** @return whether every entry identified as this module's was deleted */
        public boolean jarsDeleted() {
            return jarsDeleted;
        }

        /** @return the absolute paths of the entries whose identity could not be determined */
        public List<String> undeterminedEntries() {
            return undetermined;
        }
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
     * on restart. Now every entry the modules folder holds is placed in exactly one
     * {@link EntryState}, and what the method returns or throws says which of those it met.
     *
     * @param name the module's runtime name, as its {@code plugin.yml} declares it
     * @return {@code true} when every entry identified as this module's was deleted; {@code false}
     *     when none was and no loaded module of that name was unloaded either -- the "check the
     *     spelling" case
     * @throws java.nio.file.AccessDeniedException when the modules folder exists but could not be
     *     listed, so nothing can be concluded about what it holds
     * @throws java.nio.file.FileSystemException when an entry identified as this module's could not
     *     be deleted, naming every one still on disk
     * @throws java.nio.file.NoSuchFileException when a loaded module was unloaded but nothing in
     *     the folder could be identified as its JAR, which is not a spelling mistake
     * @throws IllegalStateException when the module's own unload threw. The module is still removed
     *     from the loaded modules and its JARs are still deleted: {@code unregister} has closed its
     *     context by then, and keeping the JAR would bring back on restart a module the operator
     *     asked to remove. A JAR failure is attached as suppressed.
     * @throws IOException if the modules folder cannot be read
     */
    public static boolean uninstallPlugin(String name) throws IOException {
        return uninstallPluginReporting(name).jarsDeleted();
    }

    /**
     * {@link #uninstallPlugin(String)}, also reporting the entries whose identity could not be
     * determined -- the ones a caller has to tell the operator about, because one of them may be a
     * copy of this module that loads again at the next start.
     *
     * @param name the module's runtime name
     * @return what the uninstall did and what it could not determine
     * @throws IOException exactly as {@link #uninstallPlugin(String)} documents
     */
    @ApiStatus.Internal
    public static UninstallReport uninstallPluginReporting(String name) throws IOException {
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        List<UltiToolsPlugin> matches = new ArrayList<>();
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            if (plugin.getPluginName().equals(name)) {
                matches.add(plugin);
            }
        }
        // Before the unload: afterwards the instance and its class loader may be gone, and with
        // them the only authoritative answer to "which JAR is this module's".
        Set<String> codeSourceJars = codeSourceJarsOf(matches);
        Throwable unloadFailure = unloadEvery(name, matches, pluginManager);
        UninstallReport report;
        try {
            report = deleteModuleJars(name, !matches.isEmpty(), codeSourceJars);
        } catch (IOException jarFailure) {
            // The JAR failure already carries the undetermined entries; see deleteModuleJars.
            if (unloadFailure != null) {
                throw unloadFailed(name, unloadFailure, jarFailure);
            }
            throw jarFailure;
        }
        if (unloadFailure != null) {
            throw carrying(unloadFailed(name, unloadFailure, null), report.undeterminedEntries());
        }
        return report;
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
     * The JAR half of {@link #uninstallPlugin(String)}: deletes every entry identified as this
     * module's -- not only the first one listed, since a second JAR loads the module again on
     * restart -- and reports the entries nothing could be read from.
     *
     * @param name           the module's runtime name
     * @param moduleUnloaded whether a loaded module of that name was unloaded first
     * @param codeSourceJars the JARs the loaded instances were loaded from, read before the unload
     * @return what was deleted and what could not be determined
     * @throws IOException as documented on {@link #uninstallPlugin(String)}
     */
    private static UninstallReport deleteModuleJars(String name, boolean moduleUnloaded,
                                                   Set<String> codeSourceJars) throws IOException {
        File folder = new File(UltiTools.getInstance().getDataFolder() + "/plugins");
        File[] listFiles = folder.listFiles();
        if (listFiles == null) {
            // Checked without following the link too: a modules folder that is a link to a target
            // which is away has unknown contents, and its JARs come back when the target does. Only
            // a path that is not there at all is an absent folder.
            if (folder.isDirectory()
                    || java.nio.file.Files.exists(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // State D. The folder is there and its contents are unknown, so "no JAR of this
                // module is here" would be a claim nothing supports: the module's JAR may be
                // sitting in it, ready to load again.
                LOGGER.severe("Uninstalling module " + name + ": the modules folder "
                        + folder.getAbsolutePath() + " exists but could not be listed");
                throw new java.nio.file.AccessDeniedException(folder.getAbsolutePath(), null,
                        "the modules folder exists but could not be listed, so nothing can be concluded"
                                + " about the JARs it holds");
            }
            return new UninstallReport(noJarFound(folder, name, moduleUnloaded, Collections.<String>emptyList()),
                    Collections.emptyList());
        }
        List<File> matchingJars = new ArrayList<>();
        List<File> undetermined = new ArrayList<>();
        for (File file : listFiles) {
            EntryState state = classify(file, name, codeSourceJars);
            if (state == EntryState.THIS_MODULES) {
                matchingJars.add(file);
            } else if (state == EntryState.UNDETERMINED) {
                undetermined.add(file);
            }
        }
        List<String> undeterminedPaths = absolutePathsOf(undetermined);
        if (!undetermined.isEmpty()) {
            LOGGER.warning("Uninstalling module " + name + ": " + undetermined.size() + " entr(ies) in "
                    + folder.getAbsolutePath() + " could not be read, so whether any of them is a copy of this"
                    + " module is unknown: " + String.join(", ", undeterminedPaths));
        }
        if (matchingJars.isEmpty()) {
            // State C outlives this answer: "nothing here could be identified as this module's" is
            // true, and so is "these entries said nothing", and one of them may be the module's own
            // JAR. Both facts travel together rather than the first discarding the second.
            return new UninstallReport(noJarFound(folder, name, moduleUnloaded, undeterminedPaths), undeterminedPaths);
        }
        try {
            deleteAllOrThrow(matchingJars);
        } catch (java.nio.file.FileSystemException deleteFailure) {
            throw carrying(deleteFailure, undeterminedPaths);
        }
        return new UninstallReport(true, undeterminedPaths);
    }

    /**
     * Which {@link EntryState} an entry of the modules folder is in.
     *
     * <p>Each answer is positive. An entry that is not a JAR file, a JAR that opens and carries no
     * {@code plugin.yml}, and a JAR that declares another module are all state B: they cannot load
     * this module, and that is known rather than assumed. An archive that will not open, an entry
     * that cannot be read, and a {@code plugin.yml} that is not valid YAML are state C: nothing was
     * learned, which is not the same as learning "no".
     *
     * @param file           the entry
     * @param name           the module's runtime name
     * @param codeSourceJars the JARs the loaded instances were loaded from
     * @return the state it is in
     */
    private static EntryState classify(File file, String name, Set<String> codeSourceJars) {
        if (codeSourceJars.contains(canonicalPathOf(file))) {
            // The module itself said this is where it came from. Nothing a file declares, or fails
            // to declare, outranks that.
            return EntryState.THIS_MODULES;
        }
        if (!file.getName().endsWith(".jar") || file.isDirectory()) {
            // Not an archive this module could ever load from: a file of another kind, or a
            // directory. Both are answers, not the absence of one.
            return EntryState.NOT_THIS_MODULES;
        }
        if (!file.isFile()) {
            // Named like a JAR and not resolvable right now -- a link whose target is away, for
            // instance. Nothing can be read from it, and it loads whatever it points at once that
            // returns, so it is undetermined rather than unrelated.
            return EntryState.UNDETERMINED;
        }
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry == null) {
                // Undetermined, not "not this module's": a module needs no plugin.yml -- it is
                // identified by @UltiToolsModule -- so a JAR without one may be a copy of this
                // module that loads again after the next restart. The cost of saying so is that a
                // stray sources JAR gets reported; the cost of not saying so is a module coming
                // back from a folder the operator was told is clear. Do not "optimise" this into
                // NOT_THIS_MODULES by reading it as metadata-free-means-unrelated; deciding it any
                // other way means predicting what a class loader would do with the archive, which
                // is the approach this work removed.
                return EntryState.UNDETERMINED;
            }
            try (InputStream is = jarFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                YamlConfiguration config = new YamlConfiguration();
                // load(Reader), not loadConfiguration(Reader): the latter swallows a parse error and
                // hands back an empty configuration, which reads as "declares no name" -- turning a
                // file that says nothing into one that says no.
                config.load(reader);
                return name.equals(config.getString("name"))
                        ? EntryState.THIS_MODULES : EntryState.NOT_THIS_MODULES;
            } catch (org.bukkit.configuration.InvalidConfigurationException malformed) {
                LOGGER.log(Level.FINE, "plugin.yml is not valid YAML in " + file, malformed);
                return EntryState.UNDETERMINED;
            }
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read " + file + " while uninstalling " + name, e);
            return EntryState.UNDETERMINED;
        }
    }

    /** The absolute paths of {@code files}, in the order given. */
    private static List<String> absolutePathsOf(List<File> files) {
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
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
            } catch (IOException | SecurityException e) {
                // SecurityException too: a policy that allows reading a module JAR and denies
                // deleting it throws an unchecked exception, which would otherwise escape past the
                // rest of this loop and leave the operator with no report of the JAR still there.
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
     * What "nothing here could be identified as this module's" means, which depends on whether one
     * was loaded.
     *
     * @param folder         the modules folder
     * @param name           the module's runtime name
     * @param moduleUnloaded whether a loaded module of that name was unloaded first
     * @param undetermined   the entries nothing could be read from, attached to the failure
     * @return {@code false}, the "no such module" answer, when nothing was unloaded either
     * @throws java.nio.file.NoSuchFileException when a module was unloaded and its JAR is missing,
     *     with one suppressed entry per undetermined file
     */
    private static boolean noJarFound(File folder, String name, boolean moduleUnloaded,
                                      List<String> undetermined) throws java.nio.file.NoSuchFileException {
        if (moduleUnloaded) {
            // The module was loaded from somewhere, so "check the spelling" is the wrong answer:
            // the operator needs to know the module is unloaded and its JAR was not found (#501).
            throw carrying(new java.nio.file.NoSuchFileException(folder.getAbsolutePath(), null,
                    "no module JAR named " + name), undetermined);
        }
        return false;
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


}
