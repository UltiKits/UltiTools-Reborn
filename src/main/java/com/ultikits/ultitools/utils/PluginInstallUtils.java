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
import java.util.Map;
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

    /** How the uninstall asks a loaded module which JAR it came from; a test passes its own. */
    static final java.util.function.Function<UltiToolsPlugin, File> DEFAULT_MODULE_CODE_SOURCE =
            module -> codeSourceLocationOf(module.getClass());

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
     * What a filesystem probe answered, keeping "unknown" out of "no".
     *
     * <p>{@code File}'s predicates return {@code false} both when the answer is no and when the
     * answer could not be obtained -- an ancestor that cannot be searched, a policy that refuses.
     * Every yes/no question this uninstall asks the filesystem goes through {@link #probe(File)}
     * so the two cannot be confused; the sweep table in the pull request lists them.
     */
    private enum Presence {
        /** It is there, and it is a regular file. */
        REGULAR_FILE,
        /** It is there, and it is a directory. */
        DIRECTORY,
        /** It is there and is neither -- a link to nowhere, a device, a socket. */
        OTHER,
        /** It is definitively not there. */
        ABSENT,
        /** The question could not be answered. */
        UNKNOWN
    }

    /**
     * What is at {@code file}, without following links, distinguishing "not there" from "could not
     * be determined".
     *
     * @param file the path to probe
     * @return what is there
     */
    private static Presence probe(File file) {
        return probe(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * What is at {@code file} with links followed: the question "is this one of the files the
     * module loader would open?", which is a different question from "is anything at this path?".
     *
     * <p>{@code PluginManager#init} opens each entry through {@code new JarFile(file)}, which
     * follows links, so a link to a real JAR is a file it loads from. The access question keeps
     * {@code NOFOLLOW_LINKS}, because that is what separates absence from a denial; this one must
     * match the loader.
     *
     * @param file the path to probe
     * @return what is at the end of it
     */
    private static Presence probeFollowingLinks(File file) {
        return probe(file, new java.nio.file.LinkOption[0]);
    }

    /**
     * {@link #probe(File)} with the link options to use.
     *
     * @param file    the path to probe
     * @param options how to treat a symbolic link
     * @return what is there
     */
    private static Presence probe(File file, java.nio.file.LinkOption... options) {
        try {
            java.nio.file.attribute.BasicFileAttributes attributes = java.nio.file.Files.readAttributes(
                    file.toPath(), java.nio.file.attribute.BasicFileAttributes.class, options);
            if (attributes.isDirectory()) {
                return Presence.DIRECTORY;
            }
            return attributes.isRegularFile() ? Presence.REGULAR_FILE : Presence.OTHER;
        } catch (java.nio.file.NoSuchFileException absent) {
            return Presence.ABSENT;
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            LOGGER.log(Level.FINE, "Could not determine what is at " + file, e);
            return Presence.UNKNOWN;
        }
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
     * <p>A code source that is a directory is a development checkout rather than an installed JAR
     * and answers nothing about which file to delete. One that cannot be probed is still named:
     * the module was loaded from it, and a probe that cannot answer is not an answer of "no".
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
            return probeFollowingLinks(location) == Presence.DIRECTORY ? null : location;
        } catch (SecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.FINE, "Could not read the code source of " + moduleClass, e);
            return null;
        }
    }

    /**
     * Whether which JAR a module was loaded from could be determined at all.
     *
     * <p>Three answers through one value, because the difference matters: a file is the JAR the
     * module came from; a directory is an exploded development checkout, which is no JAR at all and
     * says so; and {@code null} is "could not be determined" -- a code source a policy refuses to
     * reveal, or a class that has none. Unknown is not "none": while it holds, any entry matched
     * only by a name might be that running module's own JAR.
     *
     * @param moduleClass the loaded module's class
     * @return the code source's location, or {@code null} when it could not be determined
     */
    static File codeSourceLocationOf(Class<?> moduleClass) {
        try {
            java.security.CodeSource source = moduleClass.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return resolveCodeSourceFile(source.getLocation());
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
     * What one archive says about which module it holds -- the single reader both identity
     * resolution and entry classification use, so the two cannot drift apart.
     */
    private static final class ArchiveIdentity {
        private final EntryState state;
        private final String declaredName;

        private ArchiveIdentity(EntryState state, String declaredName) {
            this.state = state;
            this.declaredName = declaredName;
        }

        /** Whether the archive was read and declares a module name. */
        private boolean declaresAName() {
            return declaredName != null;
        }
    }

    /**
     * Reads what an archive declares about itself.
     *
     * <p>The one place a {@code plugin.yml} is opened. Four outcomes, each a positive answer:
     * it declares a name; it opened and declares none (no {@code plugin.yml}, or one without a
     * {@code name:} key -- which say the same thing, since a module needs no metadata at all);
     * it could not be opened or parsed; or the entry is one the module loader itself would never
     * load.
     *
     * @param file the entry
     * @return what it says about itself
     */
    private static ArchiveIdentity readArchive(File file) {
        // The same `.jar` test PluginManager#init applies to this folder (PluginManager.java:167):
        // an entry it would never load can hold no module, whatever it contains. This is the one
        // decision taken from the file's own name, and it is taken to stay in step with the loader
        // rather than to identify anything -- if that filter ever changes, this must change with it.
        if (!file.getName().endsWith(".jar")) {
            return new ArchiveIdentity(EntryState.NOT_THIS_MODULES, null);
        }
        // Links followed: this asks what the loader would open, and `new JarFile(file)` follows
        // them. A link to a real JAR is a file the module loads from, so calling it unidentifiable
        // would leave a JAR that loads the module again; only a link that resolves to nothing, or
        // that cannot be resolved at all, identifies nothing.
        Presence presence = probeFollowingLinks(file);
        if (presence == Presence.DIRECTORY) {
            return new ArchiveIdentity(EntryState.NOT_THIS_MODULES, null);
        }
        if (presence != Presence.REGULAR_FILE) {
            return new ArchiveIdentity(EntryState.UNDETERMINED, null);
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
                // is the approach this work removed. The documented limit is #516.
                return new ArchiveIdentity(EntryState.UNDETERMINED, null);
            }
            return readDeclaredName(jarFile, entry, file);
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read " + file, e);
            return new ArchiveIdentity(EntryState.UNDETERMINED, null);
        }
    }

    /**
     * The {@code name} a {@code plugin.yml} declares, if it declares one.
     *
     * @param jarFile the archive
     * @param entry   its {@code plugin.yml}
     * @param file    the archive's path, for the log line
     * @return what it says about itself
     * @throws IOException when the entry cannot be read
     */
    private static ArchiveIdentity readDeclaredName(java.util.jar.JarFile jarFile, java.util.jar.JarEntry entry,
                                                    File file) throws IOException {
        try (InputStream is = jarFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            YamlConfiguration config = new YamlConfiguration();
            // load(Reader), not loadConfiguration(Reader): the latter swallows a parse error and
            // hands back an empty configuration, which reads as "declares no name" -- turning a
            // file that says nothing into one that says no.
            config.load(reader);
            String declared = config.getString("name");
            if (declared == null || declared.trim().isEmpty()) {
                // A plugin.yml with no name: key -- or one whose name is blank -- carries exactly as
                // much about which module this is as no plugin.yml at all, which is nothing (gate 1,
                // WR-01, and its completion). A blank name must never become a key either: every
                // other blank-named archive would then match it.
                return new ArchiveIdentity(EntryState.UNDETERMINED, null);
            }
            return new ArchiveIdentity(EntryState.NOT_THIS_MODULES, declared);
        } catch (org.bukkit.configuration.InvalidConfigurationException malformed) {
            LOGGER.log(Level.FINE, "plugin.yml is not valid YAML in " + file, malformed);
            return new ArchiveIdentity(EntryState.UNDETERMINED, null);
        }
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
     * entry is exactly one of these, and each is decided by a positive test on the archive -- with
     * one exception, stated where it is taken: an entry the module loader itself would never load,
     * judged by the same {@code .jar} test {@code PluginManager#init} applies to this folder, is
     * state B without being opened.
     */
    private enum EntryState {
        /**
         * It is a loaded instance's own code-source JAR, or the archive opened and its
         * {@code plugin.yml} declares this module.
         */
        THIS_MODULES,
        /**
         * The archive opened and its {@code plugin.yml} declares another module, or the entry is
         * one the module loader would never load at all.
         */
        NOT_THIS_MODULES,
        /**
         * Nothing about it identifies a module: the archive would not open, the entry could not be
         * read, the {@code plugin.yml} is not valid YAML, it carries no {@code plugin.yml}, or that
         * file declares no {@code name:}. Unknown is not "no".
         */
        UNDETERMINED
    }

    /**
     * Raised when the uninstall refuses to act because its outcome would be undefined: two possible
     * targets, or a target whose JAR another running module shares. A type the command owns, so the
     * refusal is reported as itself rather than escaping as a command error.
     */
    @ApiStatus.Internal
    public static class UninstallRefusedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private UninstallRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Raised when the name the operator typed could be more than one loaded module's, so the
     * uninstall changed nothing.
     */
    @ApiStatus.Internal
    public static final class AmbiguousModuleNameException extends UninstallRefusedException {
        private static final long serialVersionUID = 1L;

        private AmbiguousModuleNameException(String message) {
            super(message);
        }
    }

    /**
     * Raised when a module's own unload threw. The module is still removed from the loaded modules
     * and its JARs are still deleted; the JAR outcome is attached as suppressed.
     *
     * <p>A type of its own rather than a bare {@code IllegalStateException}, so the reply that says
     * "its own unload threw" is structurally true rather than true of whatever else might raise one
     * (gate 1, IN-06). It extends {@code IllegalStateException} because that is what the method's
     * javadoc has documented since this branch began.
     */
    @ApiStatus.Internal
    public static final class ModuleUnloadFailedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ModuleUnloadFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * @param message what to tell the caller
         * @param cause   what the module's unload threw
         * @return the failure
         */
        public static ModuleUnloadFailedException of(String message, Throwable cause) {
            return new ModuleUnloadFailedException(message, cause);
        }
    }

    /**
     * The entries an uninstall could not identify, carried on whatever failure leaves the method.
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
                    "could not be identified, so whether any of them is a JAR of this module is unknown");
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
     * @param entries the absolute paths of the entries nothing could identify
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
        private final List<String> deleted;

        private UninstallReport(boolean jarsDeleted, List<String> undetermined, List<String> deleted) {
            this.jarsDeleted = jarsDeleted;
            this.undetermined = Collections.unmodifiableList(new ArrayList<>(undetermined));
            this.deleted = Collections.unmodifiableList(new ArrayList<>(deleted));
        }

        /**
         * @param jarsDeleted  whether every entry identified as this module's was deleted
         * @param undetermined the absolute paths of the entries nothing could identify
         * @return the report, with no files named as deleted
         */
        public static UninstallReport of(boolean jarsDeleted, List<String> undetermined) {
            return new UninstallReport(jarsDeleted, undetermined, Collections.<String>emptyList());
        }

        /**
         * @param jarsDeleted  whether every entry identified as this module's was deleted
         * @param undetermined the absolute paths of the entries nothing could identify
         * @param deleted      the absolute paths of the entries that were deleted
         * @return the report
         */
        public static UninstallReport of(boolean jarsDeleted, List<String> undetermined, List<String> deleted) {
            return new UninstallReport(jarsDeleted, undetermined, deleted);
        }

        /** @return whether every entry identified as this module's was deleted */
        public boolean jarsDeleted() {
            return jarsDeleted;
        }

        /** @return the absolute paths of the entries whose identity could not be determined */
        public List<String> undeterminedEntries() {
            return undetermined;
        }

        /**
         * The files this uninstall deleted, by absolute path.
         *
         * <p>Named so the operator can see what was removed: every other outcome names paths, and
         * a success that named none is what made a deletion outside the operator's request
         * invisible (gate 1, IN-12 and BL-02).
         *
         * @return the deleted paths
         */
        public List<String> deletedFiles() {
            return deleted;
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
     * @param name the module's runtime name -- which need not be what its JAR's {@code plugin.yml}
     *     declares, since {@code UltiToolsPlugin(String pluginName, ...)} takes it as an argument
     *     and reads no metadata
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
        return uninstallPluginReporting(name, DEFAULT_MODULE_CODE_SOURCE);
    }

    /**
     * {@link #uninstallPluginReporting(String)} with the code-source resolver to ask, which is how
     * a test substitutes one without production code carrying a writable field (gate 1, IN-02).
     *
     * @param name       the module's runtime name, or a name a JAR of it declares
     * @param codeSource how to ask a loaded module which JAR it came from
     * @return what the uninstall did and what it could not determine
     * @throws IOException exactly as {@link #uninstallPlugin(String)} documents
     */
    @ApiStatus.Internal
    static UninstallReport uninstallPluginReporting(String name,
                                                    java.util.function.Function<UltiToolsPlugin, File> codeSource)
            throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("A module name is required to uninstall one");
        }
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        ModuleIdentity identity = resolveIdentity(name, pluginManager, codeSource);
        refuseIfAnotherModuleSharesTheJar(identity);
        Throwable unloadFailure = unloadEvery(identity, pluginManager);
        UninstallReport report;
        try {
            report = deleteModuleJars(identity);
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
     * Moves the instances the argument names from {@code others} into {@code loaded}.
     *
     * <p>The two namespaces are asked in order, never together. A runtime-name match answers the
     * question outright: if any loaded module answers to the name that was typed, that is the
     * answer and no declared-name lookup runs beside it -- asking both at once made one module's
     * runtime name and another's declared name equally good, and unloaded both.
     *
     * <p>Only when nothing answers by runtime name is the other namespace consulted, because
     * naming a module by what its JAR declares must still work (gate 1, BL-01). If more than one
     * module answers that way, the uninstall refuses and names the candidates rather than choosing:
     * this deletes files, and a destructive command with two possible targets must stop.
     *
     * @param requested the operator's argument
     * @param loaded    collects the instances it names
     * @param others    every loaded instance; the chosen ones are moved out of it
     * @param declared  what each instance's JAR declares
     */
    private static void chooseTargets(String requested, List<UltiToolsPlugin> loaded,
                                      List<UltiToolsPlugin> others, Map<UltiToolsPlugin, String> declared) {
        List<UltiToolsPlugin> byRuntimeName = new ArrayList<>();
        List<UltiToolsPlugin> byDeclaredName = new ArrayList<>();
        for (UltiToolsPlugin plugin : others) {
            if (requested.equals(plugin.getPluginName())) {
                byRuntimeName.add(plugin);
            } else if (requested.equals(declared.get(plugin))) {
                byDeclaredName.add(plugin);
            }
        }
        List<UltiToolsPlugin> chosen = byRuntimeName.isEmpty() ? byDeclaredName : byRuntimeName;
        if (byRuntimeName.isEmpty()) {
            refuseIfAmbiguous(requested, byDeclaredName);
        }
        loaded.addAll(chosen);
        others.removeAll(chosen);
    }

    /**
     * Refuses when more than one module answers to a declared name.
     *
     * @param requested the operator's argument
     * @param candidates the loaded modules whose JARs declare it
     */
    private static void refuseIfAmbiguous(String requested, List<UltiToolsPlugin> candidates) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (UltiToolsPlugin candidate : candidates) {
            names.add(candidate.getPluginName());
        }
        if (names.size() > 1) {
            throw new AmbiguousModuleNameException("Refusing to uninstall " + requested
                    + ": it is not the runtime name of any loaded module, and the JARs of "
                    + String.join(", ", names) + " all declare it. Name one of those instead;"
                    + " nothing was changed.");
        }
    }

    /**
     * Everything later decisions need about which module this is, resolved once.
     *
     * <p>Two identity namespaces meet here and are reconciled in this one place: a module's runtime
     * name ({@code getPluginName()}, which {@code UltiToolsPlugin(String pluginName, ...)} takes as
     * an argument) and the name a JAR's {@code plugin.yml} declares. Gate 1's two blockers were
     * both a comparison of a name from one space against a name from the other; after this
     * resolution no code path compares across them -- entries are matched against {@link #keys},
     * and loaded instances were already found by both.
     */
    private static final class ModuleIdentity {
        private final String requested;
        private final List<UltiToolsPlugin> loaded;
        private final Set<String> ownJars;
        private final Set<String> keys;
        private final Set<String> bystanderJars;
        private final Map<String, String> bystanderOf;
        private final boolean resolvedFromLoadedModule;
        private final boolean someCodeSourceUnknown;

        @SuppressWarnings("PMD.ExcessiveParameterList") // one resolution's result, built in one place
        private ModuleIdentity(String requested, List<UltiToolsPlugin> loaded, Set<String> ownJars,
                               Set<String> keys, Set<String> bystanderJars, Map<String, String> bystanderOf,
                               boolean resolvedFromLoadedModule, boolean someCodeSourceUnknown) {
            this.requested = requested;
            this.loaded = loaded;
            this.ownJars = ownJars;
            this.keys = keys;
            this.bystanderJars = bystanderJars;
            this.bystanderOf = bystanderOf;
            this.resolvedFromLoadedModule = resolvedFromLoadedModule;
            this.someCodeSourceUnknown = someCodeSourceUnknown;
        }
    }

    /**
     * Resolves the operator's argument into the one identity every later decision reads.
     *
     * <p>Argument to loaded instances: an instance matches when its runtime name is the argument,
     * <em>or</em> when the JAR it was loaded from declares that name -- the second half is gate 1's
     * BL-01, where naming a module by what its JAR declares deleted the JAR of a module that was
     * left running.
     *
     * <p>Instances to keys: what their own JARs declare, plus the argument. A key another loaded
     * module owns -- its runtime name, or what its own JAR declares -- is dropped, which is gate
     * 1's BL-02: a name lifted off one module's JAR is not safe as a global key. Those modules'
     * JARs are also remembered, because no decision may end in deleting one of them.
     *
     * <p>With nothing loaded, the argument is the only key there is, and that is recorded.
     *
     * @param requested     the operator's argument
     * @param pluginManager the manager that owns the plugin list
     * @param codeSource    how to ask a loaded module which JAR it came from
     * @return the resolved identity
     */
    private static ModuleIdentity resolveIdentity(String requested, PluginManager pluginManager,
                                                  java.util.function.Function<UltiToolsPlugin, File> codeSource) {
        List<UltiToolsPlugin> loaded = new ArrayList<>();
        List<UltiToolsPlugin> others = new ArrayList<>();
        Map<UltiToolsPlugin, File> jars = new java.util.LinkedHashMap<>();
        Map<UltiToolsPlugin, String> declared = new java.util.LinkedHashMap<>();
        java.util.concurrent.atomic.AtomicBoolean unknown = new java.util.concurrent.atomic.AtomicBoolean();
        readLoadedModules(pluginManager, codeSource, others, jars, declared, unknown);
        chooseTargets(requested, loaded, others, declared);
        Set<String> ownJars = new java.util.HashSet<>();
        Set<String> keys = new java.util.LinkedHashSet<>();
        keys.add(requested);
        for (UltiToolsPlugin plugin : loaded) {
            File jar = jars.get(plugin);
            if (jar != null) {
                ownJars.add(canonicalPathOf(jar));
            }
            if (declared.get(plugin) != null) {
                keys.add(declared.get(plugin));
            }
        }
        Set<String> bystanderJars = new java.util.HashSet<>();
        Map<String, String> bystanderOf = new java.util.HashMap<>();
        for (UltiToolsPlugin plugin : others) {
            File jar = jars.get(plugin);
            if (jar != null) {
                String path = canonicalPathOf(jar);
                bystanderJars.add(path);
                bystanderOf.put(path, plugin.getPluginName());
            }
            // A key another loaded module owns is not this module's to match on (gate 1, BL-02).
            keys.remove(plugin.getPluginName());
            keys.remove(declared.get(plugin));
        }
        // No re-adding what the loop above dropped: a key another loaded module owns stays dropped
        // even when it was the argument itself. The target's own JAR is still found through its
        // code source, and matching other entries on a name a running module answers to would take
        // that module's JAR (gate 1, BL-02).
        return new ModuleIdentity(requested, loaded, ownJars, keys, bystanderJars, bystanderOf,
                !loaded.isEmpty(), unknown.get());
    }

    /**
     * Reads every loaded module once: which JAR it came from, and what that JAR declares.
     *
     * <p>Read before anything is unloaded: afterwards the instance and its class loader may be
     * gone, and with them the only authoritative answer to "which JAR is this module's". Which of
     * them the argument names is decided afterwards, by {@link #chooseTargets}.
     *
     * @param pluginManager the manager that owns the plugin list
     * @param codeSource    how to ask a loaded module which JAR it came from
     * @param others        collects every loaded instance, before any is chosen as a target
     * @param jars          collects each instance's code-source JAR
     * @param declared      collects what each instance's JAR declares
     * @param someUnknown   set when some instance's code source could not be determined at all
     */
    private static void readLoadedModules(PluginManager pluginManager,
                                          java.util.function.Function<UltiToolsPlugin, File> codeSource,
                                          List<UltiToolsPlugin> others, Map<UltiToolsPlugin, File> jars,
                                          Map<UltiToolsPlugin, String> declared,
                                          java.util.concurrent.atomic.AtomicBoolean someUnknown) {
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            File location = codeSource.apply(plugin);
            if (location == null) {
                // Not "it has no JAR": the question could not be answered, and while that holds any
                // entry matched only by a name might be this running module's own JAR.
                someUnknown.set(true);
            }
            // A directory is an exploded checkout: no JAR of this module is in the folder, which is
            // an answer rather than an absence of one.
            File jar = location == null || probeFollowingLinks(location) == Presence.DIRECTORY ? null : location;
            jars.put(plugin, jar);
            String name = jar == null ? null : readArchive(jar).declaredName;
            declared.put(plugin, name);
            others.add(plugin);
        }
    }

    /**
     * Unloads every matching module through the framework's full unload path and delists it.
     *
     * <p>A module that throws while unloading is collected rather than propagated: it is already
     * unloaded and its context already closed, so the rest of the uninstall goes ahead and the
     * failure is reported together with the JAR outcome. It leaves the plugin list either way,
     * because a listed module whose context is closed would be reported as still loaded.
     *
     * @param identity      the resolved identity, whose loaded instances are unloaded
     * @param pluginManager the manager that owns the plugin list
     * @return the first unload failure with any later one attached, or {@code null}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one module's failure must not stop the uninstall
    private static Throwable unloadEvery(ModuleIdentity identity, PluginManager pluginManager) {
        Throwable unloadFailure = null;
        for (UltiToolsPlugin plugin : identity.loaded) {
            try {
                pluginManager.unregister(plugin);
            } catch (Exception | LinkageError e) {
                // LinkageError, not Error: a module's unload can reasonably meet a
                // NoClassDefFoundError from its own half-loaded classes, and that is the case worth
                // surviving. Swallowing an OutOfMemoryError and going on to delete files is not
                // (gate 1, IN-07).
                LOGGER.log(Level.SEVERE, "Module " + identity.requested + " threw while unloading for uninstall; "
                        + "it has been removed from the loaded modules", e);
                unloadFailure = firstOf(unloadFailure, e);
            } finally {
                try {
                    pluginManager.getPluginList().remove(plugin);
                } catch (RuntimeException e) {
                    // Collected rather than thrown out of a finally block, where it would replace
                    // the failure being collected and skip the rest of the uninstall (gate 1, IN-04).
                    LOGGER.log(Level.SEVERE, "Module " + identity.requested
                            + " could not be removed from the loaded modules", e);
                    unloadFailure = firstOf(unloadFailure, e);
                }
            }
        }
        return unloadFailure;
    }

    /** The first failure, with any later one attached to it. */
    private static Throwable firstOf(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * The JAR half of {@link #uninstallPlugin(String)}: deletes every entry identified as this
     * module's -- not only the first one listed, since a second JAR loads the module again on
     * restart -- and reports the entries nothing could be read from.
     *
     * @param identity the resolved identity: which instances were loaded, which JARs they came
     *                 from, which keys other entries are matched against, and which JARs belong to
     *                 other loaded modules
     * @return what was deleted and what could not be identified
     * @throws IOException as documented on {@link #uninstallPlugin(String)}
     */
    private static UninstallReport deleteModuleJars(ModuleIdentity identity) throws IOException {
        File folder = modulesFolder();
        File[] listFiles;
        try {
            listFiles = folder.listFiles();
        } catch (SecurityException denied) {
            // A policy that denies reading the folder throws rather than answering null, and the
            // module has already been unloaded by now: this is state D, not an abort.
            LOGGER.log(Level.SEVERE, "Uninstalling module " + identity.requested + ": the modules folder "
                    + folder.getAbsolutePath() + " could not be listed", denied);
            java.nio.file.AccessDeniedException failure = modulesFolderUnlistable(folder);
            failure.initCause(denied);
            throw failure;
        }
        if (listFiles == null) {
            if (probe(folder) != Presence.ABSENT) {
                // State D. Something is there, or the question could not be answered -- either way
                // "no JAR of this module is here" is a claim nothing supports, and the module's JAR
                // may be sitting in it ready to load again.
                LOGGER.severe("Uninstalling module " + identity.requested + ": the modules folder "
                        + folder.getAbsolutePath() + " could not be listed");
                throw modulesFolderUnlistable(folder);
            }
            return new UninstallReport(noJarFound(folder, identity, Collections.<String>emptyList()),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }
        List<File> matchingJars = new ArrayList<>();
        List<File> undetermined = new ArrayList<>();
        for (File file : listFiles) {
            EntryState state = classify(file, identity);
            if (state == EntryState.THIS_MODULES) {
                matchingJars.add(file);
            } else if (state == EntryState.UNDETERMINED) {
                undetermined.add(file);
            }
        }
        List<String> undeterminedPaths = absolutePathsOf(undetermined);
        if (!undetermined.isEmpty()) {
            LOGGER.warning("Uninstalling module " + identity.requested + ": " + undetermined.size() + " entr(ies) in "
                    + folder.getAbsolutePath() + " could not be identified, so whether any of them is a copy of"
                    + " this module is unknown: " + String.join(", ", undeterminedPaths));
        }
        if (matchingJars.isEmpty()) {
            // State C outlives this answer: "nothing here could be identified as this module's" is
            // true, and so is "these entries said nothing", and one of them may be the module's own
            // JAR. Both facts travel together rather than the first discarding the second.
            return new UninstallReport(noJarFound(folder, identity, undeterminedPaths),
                    undeterminedPaths, Collections.<String>emptyList());
        }
        refuseToTouchAnotherModulesJar(identity, matchingJars);
        List<String> deleted = absolutePathsOf(matchingJars);
        try {
            deleteAllOrThrow(matchingJars);
        } catch (java.nio.file.FileSystemException deleteFailure) {
            throw carrying(deleteFailure, undeterminedPaths);
        }
        return new UninstallReport(true, undeterminedPaths, deleted);
    }

    /**
     * Refuses before anything is unloaded when a JAR this uninstall would act on is also the JAR
     * another running module was loaded from.
     *
     * <p>Two module classes can be packaged in one archive and registered separately, so one
     * physical JAR can be two loaded modules' code source. Deleting it would take a module the
     * operator did not name, and unloading the target first would change the loaded state for an
     * uninstall whose outcome is already known to be undefined -- so nothing happens at all. The
     * invariant at the delete stays as well, for whatever future path reaches one by another route.
     *
     * @param identity the resolved identity
     */
    private static void refuseIfAnotherModuleSharesTheJar(ModuleIdentity identity) {
        for (String jar : identity.ownJars) {
            if (identity.bystanderJars.contains(jar)) {
                throw new UninstallRefusedException("Refusing to uninstall " + identity.requested + ": "
                        + jar + " is also the JAR module " + identity.bystanderOf.get(jar)
                        + " is loaded from, so nothing was unloaded or deleted");
            }
        }
    }

    /**
     * The invariant, checked immediately before anything is deleted: no JAR another loaded module
     * was loaded from may be removed, whatever decided to get here.
     *
     * <p>Gate 1's BL-02 was a bystander module losing its JAR because a name lifted off the
     * target's JAR became the match key. Resolution no longer produces such a key, and this is the
     * guard at the point of destruction for every other way it could happen. It fires before the
     * first delete, so nothing is half-done when it does.
     *
     * @param identity the resolved identity, carrying the other loaded modules' JARs
     * @param toDelete the entries about to be deleted
     */
    private static void refuseToTouchAnotherModulesJar(ModuleIdentity identity, List<File> toDelete) {
        for (File file : toDelete) {
            String path = canonicalPathOf(file);
            if (identity.bystanderJars.contains(path)) {
                throw new UninstallRefusedException("Refusing to uninstall " + identity.requested + ": "
                        + file.getAbsolutePath() + " is the JAR module " + identity.bystanderOf.get(path)
                        + " is loaded from, and nothing was deleted");
            }
        }
    }

    /**
     * The folder the uninstall acts on.
     *
     * <p>{@code PluginManager#init} scans {@code System.getProperty("user.dir")} +
     * {@code /plugins/UltiTools/plugins} while this reads the framework's own data folder. On a
     * server started from its own root the two are one path and can diverge when it is not; that
     * they are computed twice is gate 1's IN-05, filed as its own issue. This side uses the data
     * folder because every other file the uninstall touches is relative to it.
     *
     * @return the modules folder
     */
    private static File modulesFolder() {
        return new File(UltiTools.getInstance().getDataFolder() + "/plugins");
    }

    /** The state-D failure: the folder is there, and what it holds is unknown. */
    private static java.nio.file.AccessDeniedException modulesFolderUnlistable(File folder) {
        return new java.nio.file.AccessDeniedException(folder.getAbsolutePath(), null,
                "the modules folder exists but could not be listed, so nothing can be concluded"
                        + " about the JARs it holds");
    }

    /**
     * Which {@link EntryState} an entry of the modules folder is in.
     *
     * <p>The loaded instance's own code-source JAR is state A whatever it declares -- the module
     * itself said where it came from. Otherwise the archive is read once ({@link #readArchive}):
     * an entry the module loader would never load, and one declaring a module that is not this
     * one, are state B; one that declares a key this identity owns is state A; and one that says
     * nothing about itself -- would not open, cannot be parsed, carries no {@code plugin.yml} or
     * no {@code name:} -- is state C.
     *
     * @param file     the entry
     * @param identity the resolved identity, whose keys and own JARs decide this
     * @return the state it is in
     */
    private static EntryState classify(File file, ModuleIdentity identity) {
        try {
            if (identity.ownJars.contains(canonicalPathOf(file))) {
                // The module itself said this is where it came from. Nothing a file declares, or
                // fails to declare, outranks that.
                return EntryState.THIS_MODULES;
            }
            ArchiveIdentity archive = readArchive(file);
            if (!archive.declaresAName()) {
                return archive.state;
            }
            if (!identity.keys.contains(archive.declaredName)) {
                return EntryState.NOT_THIS_MODULES;
            }
            if (identity.someCodeSourceUnknown) {
                // A loaded module's own JAR could not be determined, so this entry may be that
                // module's rather than the target's. Deleting it on a name alone would take a
                // running module's JAR; it is reported as what it is instead.
                return EntryState.UNDETERMINED;
            }
            return EntryState.THIS_MODULES;
        } catch (SecurityException denied) {
            // Even a type probe can be refused by a policy. A refusal answers nothing about what
            // the entry is, which is state C.
            LOGGER.log(Level.FINE, "Could not examine " + file + " while uninstalling "
                    + identity.requested, denied);
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
     * @param folder       the modules folder
     * @param identity     the resolved identity
     * @param undetermined the entries nothing could identify, attached to the failure
     * @return {@code false}, the "no such module" answer, when nothing was unloaded either
     * @throws java.nio.file.NoSuchFileException when a module was unloaded and its JAR is missing,
     *     with one suppressed entry per undetermined file
     */
    private static boolean noJarFound(File folder, ModuleIdentity identity, List<String> undetermined)
            throws java.nio.file.NoSuchFileException {
        if (identity.resolvedFromLoadedModule) {
            // The module was loaded from somewhere, so "check the spelling" is the wrong answer:
            // the operator needs to know the module is unloaded and no JAR of it was identified.
            throw carrying(new java.nio.file.NoSuchFileException(folder.getAbsolutePath(), null,
                    "no module JAR identified as " + identity.requested), undetermined);
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
    private static ModuleUnloadFailedException unloadFailed(String name, Throwable unloadFailure,
                                                           IOException jarFailure) {
        ModuleUnloadFailedException failure = new ModuleUnloadFailedException(
                "Module " + name + " was removed from the loaded modules, but its unload threw", unloadFailure);
        if (jarFailure != null) {
            failure.addSuppressed(jarFailure);
        }
        return failure;
    }


}
