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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
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
     * Name of the staging directory, directly under the framework's data folder and therefore
     * outside the scanned modules folder, where an update keeps its download and the old JARs it
     * sets aside.
     *
     * @since 6.3.0
     */
    static final String STAGING_DIRECTORY_NAME = ".upm-staging";

    /**
     * Suffix of a transaction journal in the staging directory. A journal exists exactly while one
     * update transaction is moving JARs, which is how boot recovery tells that transaction's
     * set-aside JARs from the leftovers of a finished one (review r5 WR-01).
     */
    static final String JOURNAL_SUFFIX = ".txn";

    /** Journal format marker, so a future format can be recognised rather than misread. */
    private static final String JOURNAL_FORMAT = "1";

    /**
     * Prefix of the guard key an uninstall always holds, derived from the module's runtime name.
     * The prefix keeps it out of the identify-string namespace an update keys on, so it serialises
     * uninstalls of one name without ever refusing an unrelated update.
     */
    private static final String UNINSTALL_NAME_KEY_PREFIX = "name:";

    /** Journal key saying how far the transaction got; absent means it was still moving JARs. */
    private static final String JOURNAL_PHASE_KEY = "phase";

    /** Journal phase written once the new version is in the modules folder: never restore after this. */
    private static final String JOURNAL_PHASE_COMMITTED = "committed";

    /**
     * Normalised identify strings of the modules an update or an uninstall is changing right now.
     * One guard for both operations (review r4 WR-03): an uninstall interleaved with an update of
     * the same module otherwise replied success while the update put the module back.
     */
    private static final Set<String> MODULE_OPERATIONS_IN_PROGRESS = ConcurrentHashMap.newKeySet();

    /** File operations used by an update; replaced only by tests, to inject failures and record order. */
    static volatile UpdateFileOperations updateFileOperations = UpdateFileOperations.DEFAULT;

    /** {@code <original name>.jar.<UUID>.old}, the name a set-aside JAR carries in the staging directory. */
    private static final java.util.regex.Pattern SET_ASIDE_NAME = java.util.regex.Pattern.compile(
            "^(.+\\.jar)\\.([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.old$");
    
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
            } catch (IOException | SecurityException e) {
                // A signed JAR whose entries changed after signing throws SecurityException here
                // (review r5 IN-02); skipping it is the same answer as an unreadable JAR.
                LOGGER.log(Level.FINE, "Skipping unreadable plugin JAR: " + jar.getName(), e);
            }
        }
        return matches;
    }

    /**
     * The file operations an update performs, in one place so a test can inject a failure into any
     * step or record the order of the steps without platform-specific permission tricks.
     */
    interface UpdateFileOperations {

        /** Downloads {@code url} into {@code directory} under {@code fileName}. */
        void download(String url, String fileName, File directory) throws IOException;

        /** Lists the module JARs in {@code pluginsFolder} whose {@code plugin.yml} identifies the module. */
        List<File> findModuleJars(File pluginsFolder, String identifyString);

        /**
         * Moves {@code source} to {@code target} atomically; never replaces an existing {@code target},
         * and never falls back to a copy.
         *
         * @throws AtomicMoveNotSupportedException if the two paths are not on the same file store
         */
        void move(Path source, Path target) throws IOException;

        /** Deletes {@code path} if it exists. */
        void delete(Path path) throws IOException;

        /**
         * Whether {@code first} and {@code second} are on the same file store, so a file can be
         * renamed atomically between them.
         */
        default boolean isSameFileStore(Path first, Path second) throws IOException {
            return Files.getFileStore(first).equals(Files.getFileStore(second));
        }

        UpdateFileOperations DEFAULT = new UpdateFileOperations() {
            @Override
            public void download(String url, String fileName, File directory) throws IOException {
                HttpDownloadUtils.download(url, fileName, directory.getPath());
            }

            @Override
            public List<File> findModuleJars(File pluginsFolder, String identifyString) {
                return findPluginJars(pluginsFolder, identifyString);
            }

            @Override
            public void move(Path source, Path target) throws IOException {
                // An atomic rename replaces an existing target on POSIX, so refuse it explicitly.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                // Never fall back to a copy (review r4 WR-01): a copy killed part-way leaves a
                // truncated file, and into the modules folder it would carry the final .jar name.
                // AtomicMoveNotSupportedException propagates and the update is refused.
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void delete(Path path) throws IOException {
                Files.deleteIfExists(path);
            }
        };
    }

    /**
     * The outcome of {@link #updatePluginTransactionally(String)}.
     * <p>
     * <b>Framework-internal.</b> This is the {@code /upm update} command's outcome channel, not
     * part of the module API, and may change without notice.
     *
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static final class UpdateOutcome {

        /** What happened to the update. */
        public enum Status {
            /** The new version is in the modules folder and every older JAR of the module is out of it. */
            UPDATED,
            /** An update of the same module was already running; nothing was changed. */
            ALREADY_IN_PROGRESS,
            /** The catalogue lookup or the download failed; nothing was changed. */
            DOWNLOAD_FAILED,
            /** The downloaded file is not a loadable JAR of this module at the expected version; nothing was changed. */
            INVALID_DOWNLOAD,
            /** An older JAR could not be moved out of the modules folder; everything moved so far was moved back. */
            OLD_JAR_NOT_MOVED,
            /** The new version could not be moved into the modules folder; the older JARs were moved back. */
            NEW_JAR_NOT_INSTALLED,
            /**
             * The staging directory and the modules folder are not on the same file system, so a JAR
             * cannot be renamed atomically between them; nothing was changed.
             */
            FILE_SYSTEMS_DIFFER,
            /**
             * Both directories are on one file system, but it cannot rename a JAR atomically, which
             * is the only way this update moves one. Reported apart from {@link #FILE_SYSTEMS_DIFFER}
             * (Codex review r6) because the remedy is a different one.
             */
            ATOMIC_MOVE_UNSUPPORTED,
            /**
             * The transaction's journal could not be written, so no JAR was moved: without a
             * journal a crash mid-move would leave JARs nothing ever moves back (review r6 IN-04).
             */
            JOURNAL_NOT_WRITTEN,
            /** The staging directory could not be prepared; nothing was changed. */
            STAGING_UNAVAILABLE,
            /** A JAR of the module newer than the catalogue's latest version is already in the modules folder; nothing was changed. */
            NEWER_VERSION_PRESENT
        }

        private final Status status;
        private final List<String> files;
        private final List<String> unrestoredFiles;
        private final List<String> unrestoredTargets;
        private final List<String> leftoverFiles;
        private final Throwable failure;
        private final String foundVersion;
        private final String expectedVersion;

        UpdateOutcome(Status status, List<String> files, List<String> unrestoredFiles, List<String> leftoverFiles) {
            this(status, files, unrestoredFiles, Collections.<String>emptyList(), leftoverFiles, null, null, null);
        }

        private UpdateOutcome(Status status, List<String> files, List<String> unrestoredFiles,
                              List<String> unrestoredTargets, List<String> leftoverFiles, Throwable failure,
                              String foundVersion, String expectedVersion) {
            this.status = status;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.unrestoredFiles = Collections.unmodifiableList(new ArrayList<>(unrestoredFiles));
            this.unrestoredTargets = Collections.unmodifiableList(new ArrayList<>(unrestoredTargets));
            this.leftoverFiles = Collections.unmodifiableList(new ArrayList<>(leftoverFiles));
            this.failure = failure;
            this.foundVersion = foundVersion;
            this.expectedVersion = expectedVersion;
        }

        UpdateOutcome withFailure(Throwable cause) {
            return new UpdateOutcome(status, files, unrestoredFiles, unrestoredTargets, leftoverFiles, cause,
                    foundVersion, expectedVersion);
        }

        UpdateOutcome withUnrestoredFiles(List<String> unrestored) {
            return new UpdateOutcome(status, files, unrestored, unrestoredTargets, leftoverFiles, failure,
                    foundVersion, expectedVersion);
        }

        UpdateOutcome withUnrestoredTargets(List<String> targets) {
            return new UpdateOutcome(status, files, unrestoredFiles, targets, leftoverFiles, failure,
                    foundVersion, expectedVersion);
        }

        UpdateOutcome withVersions(String found, String expected) {
            return new UpdateOutcome(status, files, unrestoredFiles, unrestoredTargets, leftoverFiles, failure,
                    found, expected);
        }

        static UpdateOutcome of(Status status) {
            return new UpdateOutcome(status, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), Collections.<String>emptyList());
        }

        /** @return what happened */
        public Status getStatus() {
            return status;
        }

        /**
         * @return for {@link Status#OLD_JAR_NOT_MOVED}, the older JAR that could not be moved; for
         *     {@link Status#NEW_JAR_NOT_INSTALLED}, the path the new version could not be moved to;
         *     otherwise empty
         */
        public List<String> getFiles() {
            return files;
        }

        /**
         * @return older JARs that were moved to the staging directory and could not be moved back
         *     after a failure; each must be moved back into the modules folder by hand. Empty when
         *     the rollback succeeded.
         */
        public List<String> getUnrestoredFiles() {
            return unrestoredFiles;
        }

        /**
         * @return for each entry of {@link #getUnrestoredFiles()}, at the same index, the original
         *     path in the modules folder -- including its original file name -- that the set-aside
         *     file must be moved back to
         */
        public List<String> getUnrestoredTargets() {
            return unrestoredTargets;
        }

        /**
         * @return after {@link Status#UPDATED}, set-aside older JARs in the staging directory that
         *     could not be deleted. They are outside the modules folder and never load.
         */
        public List<String> getLeftoverFiles() {
            return leftoverFiles;
        }

        /**
         * @return the cause of a failed file operation as {@code <exception class>: <message>}, or
         *     {@code null} when the outcome has no such cause
         */
        public String getFailureReason() {
            return failure == null ? null : failure.getClass().getName() + ": " + failure.getMessage();
        }

        /** @return the failed file operation's exception, or {@code null} */
        Throwable getFailure() {
            return failure;
        }

        /** @return for {@link Status#NEWER_VERSION_PRESENT}, the newer JAR's version; otherwise {@code null} */
        public String getFoundVersion() {
            return foundVersion;
        }

        /** @return for {@link Status#NEWER_VERSION_PRESENT}, the catalogue's latest version; otherwise {@code null} */
        public String getExpectedVersion() {
            return expectedVersion;
        }
    }

    /**
     * Update a plugin module to its latest version.
     * <p>
     * Kept for callers that only need a boolean; {@link #updatePluginTransactionally(String)}
     * reports every outcome.
     *
     * @param identifyString the plugin identify string
     * @return {@code true} if the module was updated; {@code false} if the catalogue lookup or
     *     download failed, the staging directory could not be prepared or is not on the modules
     *     folder's file system, the download is not a JAR of this module at the expected version, a
     *     JAR of the module newer than that version is already present, or an update or uninstall of
     *     the same module was already running -- in each of those cases nothing changed
     * @throws java.io.UncheckedIOException wrapping a {@link java.nio.file.FileSystemException}
     *     when an older JAR could not be moved out of the modules folder or the new version could
     *     not be moved in, and whenever a set-aside JAR could not be moved back; the move's
     *     exception is the cause; {@link java.nio.file.FileSystemException#getFile()} names that JAR or
     *     path, and the older JARs have been moved back (any that could not be are attached as
     *     suppressed {@code FileSystemException}s) (#505). This is an exception rather than
     *     {@code false} because {@code false} means nothing was changed, and a failed move can
     *     leave the modules folder changed when a set-aside JAR cannot be moved back
     */
    public static boolean updatePlugin(String identifyString) {
        UpdateOutcome outcome = updatePluginTransactionally(identifyString);
        if (outcome.getStatus() == UpdateOutcome.Status.UPDATED) {
            return true;
        }
        // false promises that nothing was changed, so any outcome that moved a JAR and could not put
        // it back -- whatever refused the move -- is thrown, as is every failed move.
        if (outcome.getStatus() == UpdateOutcome.Status.OLD_JAR_NOT_MOVED
                || outcome.getStatus() == UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED
                || !outcome.getUnrestoredFiles().isEmpty()) {
            String file = !outcome.getFiles().isEmpty() ? outcome.getFiles().get(0)
                    : outcome.getUnrestoredFiles().isEmpty() ? null : outcome.getUnrestoredFiles().get(0);
            FileSystemException failure = new FileSystemException(file, null, outcome.getStatus().name());
            if (outcome.getFailure() != null) {
                failure.initCause(outcome.getFailure());
            }
            for (String unrestored : outcome.getUnrestoredFiles()) {
                failure.addSuppressed(new FileSystemException(unrestored, null, "not moved back"));
            }
            throw new UncheckedIOException(failure);
        }
        return false;
    }

    /**
     * Update a plugin module to its latest version as a staged transaction (#505).
     * <p>
     * The new version is downloaded into {@value #STAGING_DIRECTORY_NAME} under a unique name that
     * does not end in {@code .jar}, and validated there as a JAR of this module at the expected
     * version. Only then are the module's older JARs selected from the modules folder -- before the
     * new version exists anywhere in it, so no name or file-identity comparison is needed to tell
     * the new JAR from an old one -- and moved aside into the staging directory. The new version is
     * moved in last; if any move fails, everything moved so far is moved back and nothing is
     * reported as updated. Set-aside JARs are deleted after the new version is in place; any that
     * cannot be deleted are inert and reported as leftovers. Only one update per module runs at a
     * time.
     * <p>
     * <b>Framework-internal.</b> This is the {@code /upm update} command's outcome channel, not
     * part of the module API, and may change without notice.
     *
     * @param identifyString the plugin identify string
     * @return the outcome; never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static UpdateOutcome updatePluginTransactionally(String identifyString) {
        String moduleKey = normalizeIdentifyString(identifyString);
        if (moduleKey == null) {
            return UpdateOutcome.of(UpdateOutcome.Status.DOWNLOAD_FAILED);
        }
        synchronized (MODULE_OPERATIONS_IN_PROGRESS) {
            if (MODULE_OPERATIONS_IN_PROGRESS.contains(moduleKey)) {
                return UpdateOutcome.of(UpdateOutcome.Status.ALREADY_IN_PROGRESS);
            }
            MODULE_OPERATIONS_IN_PROGRESS.add(moduleKey);
        }
        try {
            return runUpdateTransaction(identifyString, moduleKey);
        } finally {
            MODULE_OPERATIONS_IN_PROGRESS.remove(moduleKey);
        }
    }

    private static UpdateOutcome runUpdateTransaction(String identifyString, String moduleKey) {
        String latestVersion = getPluginLatestVersion(identifyString);
        String downloadLink = getPluginVersionDownloadLink(identifyString, latestVersion);
        String fileName = installedJarName(identifyString, latestVersion);
        if (catalogueLookupFailed(downloadLink, fileName)) {
            return UpdateOutcome.of(UpdateOutcome.Status.DOWNLOAD_FAILED);
        }
        UpdateFileOperations operations = updateFileOperations;
        File dataFolder = UltiTools.getInstance().getDataFolder();
        File pluginsFolder = new File(dataFolder, "plugins");
        File stagingFolder = new File(dataFolder, STAGING_DIRECTORY_NAME);
        String unique = UUID.randomUUID().toString();
        String stagedName = fileName.substring(0, fileName.length() - ".jar".length()) + "-" + unique + ".part";
        Path staged = stagingFolder.toPath().resolve(stagedName);

        // Step 0: prepare the staging directory, and refuse unless a JAR can be renamed atomically
        // between it and the modules folder -- a copy is what can leave a truncated JAR behind.
        UpdateOutcome unusableStaging = prepareStaging(operations, stagingFolder, pluginsFolder, identifyString);
        if (unusableStaging != null) {
            return unusableStaging;
        }

        // Steps 1 and 2: download into the staging directory, never into the modules folder, and
        // validate it there before anything in the modules folder is touched.
        UpdateOutcome unusableDownload = downloadAndValidate(operations, downloadLink, stagedName, stagingFolder,
                staged, moduleKey, latestVersion, identifyString);
        if (unusableDownload != null) {
            return unusableDownload;
        }

        // Step 3: select the older JARs while the new version is not in the modules folder.
        List<File> olderJars = operations.findModuleJars(pluginsFolder, identifyString);

        // Step 3b: never replace a JAR of the module that is newer than the catalogue's latest
        // version (review r4 WR-05), for example a pre-release the operator placed since boot.
        UpdateOutcome newerPresent = refuseIfNewerJarPresent(olderJars, identifyString, latestVersion);
        if (newerPresent != null) {
            deleteQuietly(operations, staged);
            return newerPresent;
        }

        // Step 3c: record the transaction before moving anything (review r5 WR-01). Boot recovery
        // restores only what a surviving journal names; a set-aside JAR without one is a leftover
        // of a finished transaction and is never moved back.
        List<Path[]> planned = plannedMoves(olderJars, stagingFolder, unique);
        Path journal = stagingFolder.toPath().resolve(unique + JOURNAL_SUFFIX);
        UpdateOutcome journalFailed = openJournal(journal, moduleKey, readModuleName(staged.toFile()), fileName,
                planned, identifyString);
        if (journalFailed != null) {
            deleteQuietly(operations, staged);
            return journalFailed;
        }

        // Step 4: move every older JAR aside; on failure, move back what was moved.
        List<Path[]> movedAside = new ArrayList<>();
        UpdateOutcome asideFailed = moveOlderJarsAside(operations, planned, movedAside, identifyString);
        if (asideFailed != null) {
            return rollBack(operations, asideFailed, movedAside, staged, journal, stagingFolder, pluginsFolder);
        }

        // Step 5: move the new version in under its final name; on failure, restore the older JARs.
        Path target = pluginsFolder.toPath().resolve(fileName);
        UpdateOutcome moveInFailed = installNewVersion(operations, staged, target, identifyString);
        if (moveInFailed != null) {
            return rollBack(operations, moveInFailed, movedAside, staged, journal, stagingFolder, pluginsFolder);
        }

        // Step 5b: the new version is in the modules folder, so the transaction is committed. A
        // journal that survives from here on must never move an old JAR back, whatever the modules
        // folder looks like later -- an uninstall may have removed the new version since (Codex
        // review r6). Recording that is what tells the two apart.
        markJournalCommitted(journal, identifyString);

        // Step 6: delete the set-aside JARs; any that remain are outside the modules folder and inert.
        List<String> leftovers = deleteSetAsideJars(operations, movedAside, identifyString);
        // The transaction has reached its final state: the journal must go, so the next boot treats
        // any set-aside JAR still here as a leftover rather than restoring it (review r5 WR-01).
        deleteJournal(journal);
        return new UpdateOutcome(UpdateOutcome.Status.UPDATED, Collections.<String>emptyList(),
                Collections.<String>emptyList(), leftovers);
    }

    /**
     * Prepares the staging directory and refuses a pair of directories a JAR cannot be renamed
     * between atomically.
     *
     * @return the outcome to report, or {@code null} when the staging directory is usable
     */
    private static UpdateOutcome prepareStaging(UpdateFileOperations operations, File stagingFolder,
                                                File pluginsFolder, String identifyString) {
        try {
            Files.createDirectories(stagingFolder.toPath());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.SEVERE, "Could not prepare the update staging directory " + stagingFolder
                    + " for " + identifyString + "; nothing was changed", e);
            return stagingUnavailable(stagingFolder, e);
        }
        try {
            if (!operations.isSameFileStore(stagingFolder.toPath(), pluginsFolder.toPath())) {
                LOGGER.severe("Refusing to update " + identifyString + ": the staging directory " + stagingFolder
                        + " and the modules folder " + pluginsFolder + " are not on the same file system");
                return fileSystemsDiffer(stagingFolder, pluginsFolder, null);
            }
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.SEVERE, "Could not compare the file systems of " + stagingFolder + " and "
                    + pluginsFolder + " for " + identifyString + "; nothing was changed", e);
            return stagingUnavailable(stagingFolder, e);
        }
        return null;
    }

    /** Whether the catalogue gave neither a download link nor a file name to install under. */
    private static boolean catalogueLookupFailed(String downloadLink, String fileName) {
        return downloadLink == null || fileName == null;
    }

    /**
     * Downloads the new version into the staging directory and validates it there.
     *
     * @return the outcome to report, or {@code null} when a valid JAR of that module is staged
     */
    private static UpdateOutcome downloadAndValidate(UpdateFileOperations operations, String downloadLink,
                                                     String stagedName, File stagingFolder, Path staged,
                                                     String moduleKey, String latestVersion, String identifyString) {
        try {
            operations.download(downloadLink, stagedName, stagingFolder);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, "Failed to download update for " + identifyString + "; nothing was changed", e);
            deleteQuietly(operations, staged);
            return UpdateOutcome.of(UpdateOutcome.Status.DOWNLOAD_FAILED);
        }
        if (!isJarOfModule(staged.toFile(), moduleKey, latestVersion)) {
            LOGGER.severe("Downloaded update for " + identifyString
                    + " is not a loadable JAR of that module at version " + latestVersion
                    + "; nothing was changed");
            deleteQuietly(operations, staged);
            return UpdateOutcome.of(UpdateOutcome.Status.INVALID_DOWNLOAD);
        }
        return null;
    }

    /** The {@code {original, set-aside}} pairs the transaction is about to move, one per older JAR. */
    private static List<Path[]> plannedMoves(List<File> olderJars, File stagingFolder, String unique) {
        List<Path[]> planned = new ArrayList<>();
        for (File olderJar : olderJars) {
            planned.add(new Path[]{olderJar.toPath(),
                    stagingFolder.toPath().resolve(olderJar.getName() + "." + unique + ".old")});
        }
        return planned;
    }

    /**
     * Records the transaction before it moves anything (review r5 WR-01). Boot recovery moves back
     * only what a surviving journal names, so a transaction that cannot write one must not start.
     *
     * @return the outcome to report, or {@code null} when the journal is on disk
     */
    private static UpdateOutcome openJournal(Path journal, String moduleKey, String moduleName, String targetName,
                                             List<Path[]> planned, String identifyString) {
        try {
            writeTransactionJournal(journal, moduleKey, moduleName, targetName, planned);
            return null;
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.SEVERE, "Could not write the update journal " + journal + " for " + identifyString
                    + "; nothing was changed", e);
            return new UpdateOutcome(UpdateOutcome.Status.JOURNAL_NOT_WRITTEN,
                    Collections.singletonList(journal.toAbsolutePath().toString()), Collections.<String>emptyList(),
                    Collections.<String>emptyList()).withFailure(e);
        }
    }

    /** The {@code STAGING_UNAVAILABLE} outcome naming {@code stagingFolder} and its cause. */
    private static UpdateOutcome stagingUnavailable(File stagingFolder, Throwable cause) {
        return new UpdateOutcome(UpdateOutcome.Status.STAGING_UNAVAILABLE,
                Collections.singletonList(stagingFolder.getAbsolutePath()), Collections.<String>emptyList(),
                Collections.<String>emptyList()).withFailure(cause);
    }

    /**
     * Refuses the update when a JAR of the module in the modules folder is newer than the
     * catalogue's latest version (review r4 WR-05), for example a pre-release placed since boot.
     *
     * @return the outcome to report, or {@code null} when no JAR is newer
     */
    private static UpdateOutcome refuseIfNewerJarPresent(List<File> olderJars, String identifyString,
                                                         String latestVersion) {
        for (File olderJar : olderJars) {
            String version = readModuleVersion(olderJar);
            if (version != null && VersionComparatorUtil.compare(version.trim(), latestVersion.trim()) > 0) {
                LOGGER.warning("Refusing to update " + identifyString + ": " + olderJar + " is version " + version
                        + ", newer than the catalogue's latest version " + latestVersion + "; nothing was changed");
                return new UpdateOutcome(UpdateOutcome.Status.NEWER_VERSION_PRESENT,
                        Collections.singletonList(olderJar.getAbsolutePath()), Collections.<String>emptyList(),
                        Collections.<String>emptyList()).withVersions(version, latestVersion);
            }
        }
        return null;
    }

    /**
     * Moves every planned JAR aside, recording what was moved in {@code movedAside} so the caller
     * can roll back. A JAR removed since the listing is not a failure: there is nothing to move.
     *
     * @return the outcome to report, or {@code null} when every JAR was moved aside
     */
    private static UpdateOutcome moveOlderJarsAside(UpdateFileOperations operations, List<Path[]> planned,
                                                    List<Path[]> movedAside, String identifyString) {
        for (Path[] move : planned) {
            Path original = move[0];
            Path aside = move[1];
            try {
                operations.move(original, aside);
                movedAside.add(move);
            } catch (NoSuchFileException gone) {
                // Removed since the listing: nothing left to move aside.
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.log(Level.WARNING, "Could not move " + original + " aside to " + aside
                        + " atomically; refusing the update of " + identifyString, e);
                return UpdateOutcome.of(UpdateOutcome.Status.ATOMIC_MOVE_UNSUPPORTED).withFailure(e);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not move " + original + " aside to " + aside
                        + "; rolling back the update of " + identifyString, e);
                return new UpdateOutcome(UpdateOutcome.Status.OLD_JAR_NOT_MOVED,
                        Collections.singletonList(original.toAbsolutePath().toString()), Collections.<String>emptyList(),
                        Collections.<String>emptyList()).withFailure(e);
            }
        }
        return null;
    }

    /**
     * Moves the validated download in under its final name.
     *
     * @return the outcome to report, or {@code null} when the new version is in place
     */
    private static UpdateOutcome installNewVersion(UpdateFileOperations operations, Path staged, Path target,
                                                   String identifyString) {
        try {
            operations.move(staged, target);
            return null;
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.log(Level.WARNING, "Could not move the new version to " + target
                    + " atomically; refusing the update of " + identifyString, e);
            return UpdateOutcome.of(UpdateOutcome.Status.ATOMIC_MOVE_UNSUPPORTED).withFailure(e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not move the new version to " + target
                    + "; rolling back the update of " + identifyString, e);
            return new UpdateOutcome(UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                    Collections.singletonList(target.toAbsolutePath().toString()), Collections.<String>emptyList(),
                    Collections.<String>emptyList()).withFailure(e);
        }
    }

    /**
     * Undoes a failed transaction: moves back every JAR it had set aside, deletes the download, and
     * deletes the journal, because the transaction has reached a final state. A cross-file-system
     * failure is re-stated with both directories named, which is what the operator has to act on.
     *
     * @return {@code failure}, with the JARs that could not be moved back attached
     */
    private static UpdateOutcome rollBack(UpdateFileOperations operations, UpdateOutcome failure,
                                          List<Path[]> movedAside, Path staged, Path journal,
                                          File stagingFolder, File pluginsFolder) {
        List<Path[]> unrestored = moveBack(operations, movedAside);
        deleteQuietly(operations, staged);
        if (unrestored.isEmpty()) {
            deleteJournal(journal);
        } else {
            // The journal is what lets the next start move these JARs back; deleting it would make
            // them leftovers nothing ever restores (Codex review r7).
            LOGGER.warning("Update journal " + journal + " was kept: " + unrestored.size()
                    + " JAR(s) could not be moved back, and the next start will try again");
        }
        UpdateOutcome outcome = failure.getStatus() == UpdateOutcome.Status.ATOMIC_MOVE_UNSUPPORTED
                ? bothFoldersNamed(failure.getStatus(), stagingFolder, pluginsFolder, failure.getFailure())
                : failure;
        return withUnrestored(outcome, unrestored);
    }

    /**
     * Deletes the JARs the transaction set aside.
     *
     * @return the absolute paths of those that could not be deleted; they are outside the modules
     *         folder and never load
     */
    private static List<String> deleteSetAsideJars(UpdateFileOperations operations, List<Path[]> movedAside,
                                                   String identifyString) {
        List<String> leftovers = new ArrayList<>();
        for (Path[] move : movedAside) {
            try {
                operations.delete(move[1]);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not delete the set-aside JAR " + move[1]
                        + " after updating " + identifyString + "; it is outside the modules folder and never loads", e);
                leftovers.add(move[1].toAbsolutePath().toString());
            }
        }
        return leftovers;
    }

    /**
     * Writes {@code journal} atomically: a temporary file first, then an atomic rename, so boot
     * recovery never reads a half-written journal.
     * <p>
     * Format (a properties file, UTF-8): {@code format}, {@code process} (the writing JVM, so a
     * transaction of this process is never recovered beside itself), {@code module} (the normalised
     * identify-string), {@code target} (the new version's file name in the modules folder) and, per
     * set-aside JAR, {@code aside.<n>.original} and {@code aside.<n>.aside} -- file names, resolved
     * against the modules folder and the staging directory.
     *
     * @param journal the journal path in the staging directory
     * @param moduleKey the module's normalised identify-string
     * @param targetName the new version's file name
     * @param planned the {@code {original, aside}} pairs the transaction is about to move
     */
    private static void writeTransactionJournal(Path journal, String moduleKey, String moduleName, String targetName,
                                                List<Path[]> planned) throws IOException {
        java.util.Properties entries = new java.util.Properties();
        entries.setProperty("format", JOURNAL_FORMAT);
        entries.setProperty("process", currentProcessIdentity());
        entries.setProperty("module", moduleKey);
        entries.setProperty("target", targetName);
        if (moduleName != null) {
            // The runtime name an uninstall goes by. Without it a transaction that set no JAR aside
            // names its module nowhere, and an uninstall in its move window runs unguarded (r6 IN-05).
            entries.setProperty("name", moduleName);
        }
        for (int i = 0; i < planned.size(); i++) {
            entries.setProperty("aside." + i + ".original", planned.get(i)[0].getFileName().toString());
            entries.setProperty("aside." + i + ".aside", planned.get(i)[1].getFileName().toString());
        }
        writeProperties(journal, entries);
    }

    /**
     * Writes {@code entries} to {@code journal} through a temporary file and an atomic rename, so
     * recovery never reads a half-written journal. The temporary file is removed if anything fails
     * (review r6 IN-04); boot recovery sweeps it as a second line of defence.
     */
    private static void writeProperties(Path journal, java.util.Properties entries) throws IOException {
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        boolean renamed = false;
        try {
            try (java.io.Writer writer = Files.newBufferedWriter(temporary, java.nio.charset.StandardCharsets.UTF_8)) {
                entries.store(writer, "UltiTools module update journal");
            }
            Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE);
            renamed = true;
        } finally {
            if (!renamed) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException e) {
                    LOGGER.log(Level.FINE, "Could not delete the half-written journal " + temporary, e);
                }
            }
        }
    }

    /**
     * Appends the committed marker to a journal whose new version is installed. A failure to write
     * it is not a failed update -- the transaction is complete, and recovery still has the target
     * check -- but it is logged, because the marker is what keeps a later uninstall of the module
     * from being undone by recovery.
     */
    private static void markJournalCommitted(Path journal, String identifyString) {
        try {
            java.util.Properties entries = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(journal, java.nio.charset.StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
            entries.setProperty(JOURNAL_PHASE_KEY, JOURNAL_PHASE_COMMITTED);
            writeProperties(journal, entries);
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.WARNING, "Could not record that the update of " + identifyString
                    + " is committed in " + journal + "; the update itself is complete", e);
            deleteJournal(journal);
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                LOGGER.severe("The update of " + identifyString + " is complete, but its journal " + journal
                        + " could neither be marked committed nor deleted. Delete that file: while it is there, a"
                        + " restart after this module is uninstalled can move its old JAR back.");
            }
        }
    }

    /**
     * This run's identity, as recorded in a journal and compared by boot recovery.
     * <p>
     * The JVM name alone ({@code <pid>@<host>}) is not enough: a container that runs Java as PID 1
     * under a fixed hostname produces the same name on every start -- measured byte-identical across
     * three restarts of one container -- so after a crash the new server would read its own identity
     * in the crashed run's journal, skip it forever, and leave the module's JAR in the staging
     * directory (review r6 BL-01). The JVM's start time cannot repeat across restarts and does not
     * change when a plugin is re-enabled inside one JVM, which is exactly the pair of properties
     * this comparison needs. A value without the start-time marker was written by an older build and
     * is therefore another run's.
     */
    static String currentProcessIdentity() {
        java.lang.management.RuntimeMXBean jvm = java.lang.management.ManagementFactory.getRuntimeMXBean();
        return jvm.getName() + "#" + jvm.getStartTime();
    }

    private static void deleteJournal(Path journal) {
        try {
            Files.deleteIfExists(journal);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete the module update journal " + journal
                    + "; the next start will report its set-aside JARs", e);
        }
    }

    private static UpdateOutcome fileSystemsDiffer(File stagingFolder, File pluginsFolder, Throwable cause) {
        return bothFoldersNamed(UpdateOutcome.Status.FILE_SYSTEMS_DIFFER, stagingFolder, pluginsFolder, cause);
    }

    /** An outcome naming the staging directory and the modules folder, in that order. */
    private static UpdateOutcome bothFoldersNamed(UpdateOutcome.Status status, File stagingFolder,
                                                  File pluginsFolder, Throwable cause) {
        UpdateOutcome outcome = new UpdateOutcome(status,
                Arrays.asList(stagingFolder.getAbsolutePath(), pluginsFolder.getAbsolutePath()),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        return cause == null ? outcome : outcome.withFailure(cause);
    }

    /**
     * Recovers module updates a crash or kill interrupted (reviews r4/r5 WR-01). Must run before the
     * modules folder is scanned.
     * <p>
     * Only a surviving transaction journal moves anything back, and only what that journal records.
     * A journal exists exactly while its transaction is running, so a set-aside JAR without one
     * belongs to a transaction that finished: it is a leftover, never restored, and only reported
     * once. A journal written by this JVM belongs to an update running right now and is left alone.
     * Every journal and every file is handled in isolation: a malformed or unreadable journal, or a
     * JAR that cannot be moved, is logged by name and recovery continues with the next one.
     * <p>
     * <b>Framework-internal.</b> Called by the framework at boot; not part of the module API.
     *
     * @param dataFolder the framework data folder holding {@code plugins/} and the staging directory
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static void recoverInterruptedUpdates(File dataFolder) {
        if (dataFolder == null) {
            return;
        }
        File stagingFolder = new File(dataFolder, STAGING_DIRECTORY_NAME);
        File[] entries = stagingFolder.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        File pluginsFolder = new File(dataFolder, "plugins");
        Set<String> reported = new java.util.HashSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".part") || name.endsWith(JOURNAL_SUFFIX + ".tmp")) {
                deleteStaleDownload(entry);
            } else if (name.endsWith(JOURNAL_SUFFIX)) {
                recoverJournalIsolated(entry, pluginsFolder, reported);
            }
        }
        reportLeftovers(entries, reported, stagingFolder);
    }

    /** Deletes one partial download or half-written journal, logging either way. */
    private static void deleteStaleDownload(File entry) {
        try {
            Files.deleteIfExists(entry.toPath());
            LOGGER.info("Deleted a stale partial module update download: " + entry.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete a stale partial module update download: "
                    + entry.getAbsolutePath(), e);
        }
    }

    /** Recovers one journal; an unusable one is logged and left in place, never failing the sweep. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // deliberate barrier: one bad journal must not stop the others
    private static void recoverJournalIsolated(File entry, File pluginsFolder, Set<String> reported) {
        try {
            recoverJournal(entry, pluginsFolder, reported);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Module update journal " + entry.getAbsolutePath()
                    + " could not be read and was left in place", e);
        }
    }

    /**
     * Names every set-aside JAR recovery did not act on.
     * <p>
     * A JAR whose transaction left no journal belongs to a transaction that finished: it is never
     * moved back (review r5 WR-01) and the operator can delete it. A JAR whose journal is still in
     * the staging directory -- because that journal could not be read, could not be parsed, or
     * belongs to an update running right now -- is the opposite: something still needs it, and
     * telling the operator it can be deleted would cost them the module's only copy (review r6
     * WR-03). The two are told apart by the transaction id both file names carry.
     */
    private static void reportLeftovers(File[] entries, Set<String> reported, File stagingFolder) {
        for (File entry : entries) {
            java.util.regex.Matcher name = SET_ASIDE_NAME.matcher(entry.getName());
            if (!name.matches() || reported.contains(entry.getName())
                    || !Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            File journal = new File(stagingFolder, name.group(2) + JOURNAL_SUFFIX);
            if (Files.exists(journal.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                LOGGER.warning("Set-aside module JAR " + entry.getAbsolutePath() + " belongs to update journal "
                        + journal.getAbsolutePath() + ", which was kept because it could not be processed; "
                        + "do not delete either until that is resolved");
            } else {
                LOGGER.warning("Update leftover " + entry.getAbsolutePath()
                        + ": no interrupted update refers to it, so it is never restored and can be deleted");
            }
        }
    }

    /**
     * Recovers one transaction from its journal.
     *
     * @param journalFile   the journal in the staging directory
     * @param pluginsFolder the modules folder
     * @param reported      set-aside file names this recovery has already restored or reported
     */
    private static void recoverJournal(File journalFile, File pluginsFolder, Set<String> reported) throws IOException {
        java.util.Properties entries = new java.util.Properties();
        try (java.io.Reader reader = Files.newBufferedReader(journalFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            entries.load(reader);
        }
        String module = entries.getProperty("module");
        String target = entries.getProperty("target");
        String process = entries.getProperty("process");
        if (!JOURNAL_FORMAT.equals(entries.getProperty("format")) || process == null
                || module == null || !isPlainFileName(target)) {
            LOGGER.warning("Module update journal " + journalFile.getAbsolutePath()
                    + " is malformed and was left in place");
            return;
        }
        if (process.equals(currentProcessIdentity())) {
            LOGGER.info("Module update journal " + journalFile.getAbsolutePath()
                    + " belongs to an update running in this server process and was left in place");
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean skippedAPair = new java.util.concurrent.atomic.AtomicBoolean();
        List<String[]> pairs = journalPairs(entries, journalFile, skippedAPair);
        File stagingFolder = journalFile.getParentFile();
        Path targetPath = pluginsFolder.toPath().resolve(target);
        boolean alreadyInstalled = JOURNAL_PHASE_COMMITTED.equals(entries.getProperty(JOURNAL_PHASE_KEY))
                || newVersionWasInstalled(targetPath, module, journalFile);
        boolean settled = !skippedAPair.get();
        for (String[] pair : pairs) {
            File setAside = new File(stagingFolder, pair[1]);
            boolean handled = true;
            if (alreadyInstalled) {
                reportAsLeftoverOfAnInstalledUpdate(setAside, module, targetPath);
            } else {
                handled = restoreSetAsideJar(setAside, pluginsFolder.toPath().resolve(pair[0]), pluginsFolder, module);
            }
            // Only a JAR this recovery finished with is "reported": one still waiting is left to
            // the leftover pass, which names it as belonging to the journal that was kept rather
            // than saying nothing about it at all (Codex review r8).
            if (handled) {
                reported.add(pair[1]);
            }
            settled &= handled;
        }
        // Deleting the journal turns every JAR it names into a leftover nothing will ever move back,
        // so it goes only once none of them still needs moving (Codex review r6).
        if (settled) {
            deleteJournal(journalFile.toPath());
        } else {
            LOGGER.warning("Module update journal " + journalFile.getAbsolutePath()
                    + " was kept: a set-aside JAR of module " + module
                    + " could not be restored, and the next start will try again");
        }
    }

    /**
     * Whether the interrupted update had already moved its new version in. The path existing is not
     * enough (Codex review r6): the update refuses to overwrite an occupied target, so a file there
     * can be an unrelated one that was in the way -- and treating it as the new version would leave
     * every old JAR in the staging directory and delete the journal, losing the module. The file
     * counts only when it really is a JAR of the module the journal names.
     */
    private static boolean newVersionWasInstalled(Path targetPath, String module, File journalFile) {
        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (isJarOfModule(targetPath.toFile(), module)) {
            return true;
        }
        LOGGER.warning("The interrupted update recorded in " + journalFile.getAbsolutePath() + " never installed "
                + targetPath.toAbsolutePath() + ": that file is not a JAR of module " + module
                + ", so the module's own JARs are moved back");
        return false;
    }

    /**
     * The {@code {original, set-aside}} file-name pairs a journal records.
     * <p>
     * A pair naming a file that cannot be used -- a path separator, a directory hop -- is skipped
     * with a warning rather than abandoning the transaction's other JARs (review r6 IN-02): those
     * are ordinary names and moving them back is what keeps their module loadable.
     */
    private static List<String[]> journalPairs(java.util.Properties entries, File journalFile,
                                               java.util.concurrent.atomic.AtomicBoolean skippedAPair) {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; ; i++) {
            String original = entries.getProperty("aside." + i + ".original");
            String aside = entries.getProperty("aside." + i + ".aside");
            if (original == null && aside == null) {
                return pairs;
            }
            if (isPlainFileName(original) && isPlainFileName(aside)) {
                pairs.add(new String[]{original, aside});
            } else {
                // The journal is kept: its JAR is unresolved, and a journal on disk is what stops
                // recovery from advertising that JAR as deletable (Codex review r6).
                skippedAPair.set(true);
                LOGGER.warning("Module update journal " + journalFile.getAbsolutePath()
                        + " names an unusable file, which was skipped: " + original + " <- " + aside);
            }
        }
    }

    /**
     * Names a set-aside JAR of a transaction that had already installed its new version. Moving it
     * back would reinstall the version the update replaced, so it is only reported.
     */
    private static void reportAsLeftoverOfAnInstalledUpdate(File setAside, String module, Path target) {
        if (Files.exists(setAside.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warning("Update leftover " + setAside.getAbsolutePath() + ": the interrupted update of module "
                    + module + " had already installed " + target.toAbsolutePath()
                    + ", so it is never restored and can be deleted");
        }
    }

    /**
     * Moves one set-aside JAR back to the path it came from. A JAR that is gone, a path that is
     * occupied again, and a failed move are each reported and skipped, so one of them never stops
     * the rest of the journal from being restored.
     *
     * @return whether this JAR needs nothing further: it was restored, it is already gone, or its
     *         original path is occupied again. {@code false} means the move failed and is worth
     *         retrying at the next start, which is only possible while the journal survives.
     */
    private static boolean restoreSetAsideJar(File setAside, Path original, File pluginsFolder, String module) {
        try {
            if (!Files.exists(setAside.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                // Not settled (Codex review r8): the set-aside JAR is still the module's copy and
                // still needs a decision. Keeping the journal is what stops the next start from
                // calling that JAR disposable, and lets recovery retry once the path is free.
                LOGGER.warning("Set-aside module JAR " + setAside.getAbsolutePath()
                        + " from an interrupted update of module " + module + " was not restored: "
                        + original.toAbsolutePath() + " already exists");
                return false;
            }
            Files.createDirectories(pluginsFolder.toPath());
            Files.move(setAside.toPath(), original, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.warning("Restored " + original.toAbsolutePath() + " from an interrupted module update (it was "
                    + setAside.getAbsolutePath() + ")");
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.WARNING, "Could not restore the set-aside module JAR " + setAside.getAbsolutePath()
                    + " to " + original.toAbsolutePath(), e);
            return false;
        }
    }

    /** Whether {@code name} is a plain file name: not empty, no path separator, not a directory hop. */
    private static boolean isPlainFileName(String name) {
        return name != null && !name.isEmpty() && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && !".".equals(name) && !"..".equals(name);
    }

    /**
     * Moves every set-aside JAR back to where it came from, newest move first.
     *
     * @return the {@code {original, set-aside}} pairs that could not be moved back
     */
    private static List<Path[]> moveBack(UpdateFileOperations operations, List<Path[]> movedAside) {
        List<Path[]> unrestored = new ArrayList<>();
        for (int i = movedAside.size() - 1; i >= 0; i--) {
            Path[] move = movedAside.get(i);
            try {
                operations.move(move[1], move[0]);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Could not move " + move[1] + " back to " + move[0], e);
                unrestored.add(move);
            }
        }
        return unrestored;
    }

    /**
     * Records the pairs {@link #moveBack} could not restore on {@code outcome}: the set-aside path
     * and, at the same index, the original path -- file name included -- it must be moved back to
     * (review r4 WR-02; moving the set-aside file back under its staging name leaves it unloadable).
     */
    private static UpdateOutcome withUnrestored(UpdateOutcome outcome, List<Path[]> unrestored) {
        List<String> asides = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        for (Path[] move : unrestored) {
            asides.add(move[1].toAbsolutePath().toString());
            originals.add(move[0].toAbsolutePath().toString());
        }
        return outcome.withUnrestoredFiles(asides).withUnrestoredTargets(originals);
    }

    private static void deleteQuietly(UpdateFileOperations operations, Path path) {
        try {
            operations.delete(path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete staged file " + path, e);
        }
    }

    /**
     * Whether {@code file} is a JAR the loader would accept, whose {@code plugin.yml} names the
     * module {@code moduleKey} at {@code expectedVersion}.
     */
    static boolean isJarOfModule(File file, String moduleKey, String expectedVersion) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            if (!SecurityPolicy.isSafeFileStructure(file.length(), jarFile.size())) {
                return false;
            }
            Map<String, String> pluginYml = readPluginYmlScalars(jarFile);
            String version = pluginYml.get("version");
            // The loader refuses a module JAR with no name: (UltiToolsPlugin's constructor, D-16),
            // so a download without one would replace a working module with something that cannot
            // load (Codex review r6). Refuse it here, while nothing has moved.
            return moduleKey.equals(normalizeIdentifyString(pluginYml.get("identify-string")))
                    && declaresAName(pluginYml, file)
                    && PluginManager.carriesLoadableModuleMainClass(file)
                    && version != null && expectedVersion != null
                    && VersionComparatorUtil.compare(version.trim(), expectedVersion.trim()) == 0;
        } catch (IOException | SecurityException e) {
            // SecurityException: a signed JAR whose plugin.yml changed after signing (Codex review r6).
            LOGGER.log(Level.FINE, "Downloaded file is not a readable JAR: " + file, e);
            return false;
        }
    }

    /** Whether a {@code plugin.yml} carries the {@code name:} the module loader requires. */
    private static boolean declaresAName(Map<String, String> pluginYml, File file) {
        String name = pluginYml.get("name");
        if (name == null || name.trim().isEmpty()) {
            LOGGER.warning("JAR " + file + " declares no plugin.yml name:, which the module loader requires");
            return false;
        }
        return true;
    }

    /**
     * Whether {@code file} is a loadable JAR of the module {@code moduleKey}, at any version. Used
     * where the version is not known, such as recovery asking whether a file is the module's JAR.
     */
    static boolean isJarOfModule(File file, String moduleKey) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            if (!SecurityPolicy.isSafeFileStructure(file.length(), jarFile.size())) {
                return false;
            }
            Map<String, String> pluginYml = readPluginYmlScalars(jarFile);
            return moduleKey.equals(normalizeIdentifyString(pluginYml.get("identify-string")))
                    && declaresAName(pluginYml, file);
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "File is not a readable JAR of module " + moduleKey + ": " + file, e);
            return false;
        }
    }

    /**
     * The {@code plugin.yml} {@code name} of the JAR at {@code file} -- the runtime name an
     * uninstall goes by -- or {@code null} when the JAR, its {@code plugin.yml} or the key cannot
     * be read.
     */
    static String readModuleName(File file) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            return readPluginYmlScalars(jarFile).get("name");
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read the plugin.yml name of " + file, e);
            return null;
        }
    }

    /**
     * The {@code plugin.yml} {@code version} of the JAR at {@code file}, exactly as written, or
     * {@code null} when the JAR, its {@code plugin.yml} or the key cannot be read.
     */
    static String readModuleVersion(File file) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            return readPluginYmlScalars(jarFile).get("version");
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Could not read the plugin.yml version of " + file, e);
            return null;
        }
    }

    /**
     * The top-level scalar entries of {@code jarFile}'s {@code plugin.yml}, each value exactly as
     * written (review r4 IN-04). Reading through YAML typing would turn an unquoted
     * {@code version: 2.10} into the number {@code 2.1}; composing the node tree keeps the text.
     *
     * @return the entries; empty when there is no readable {@code plugin.yml}
     */
    private static Map<String, String> readPluginYmlScalars(java.util.jar.JarFile jarFile) throws IOException {
        java.util.jar.JarEntry entry = jarFile.getJarEntry("plugin.yml");
        if (entry == null) {
            return Collections.emptyMap();
        }
        try (InputStream is = jarFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
            // The safe constructor cannot instantiate an arbitrary class named by a YAML tag. Composing
            // never constructs an object at all, but a JAR's plugin.yml is not this framework's file, so
            // the parser is configured as if it were loading one. SafeConstructor(LoaderOptions) exists in
            // every SnakeYAML a supported Paper ships (measured: 1.26 through 2.2).
            org.yaml.snakeyaml.Yaml parser = new org.yaml.snakeyaml.Yaml(
                    new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            org.yaml.snakeyaml.nodes.Node root = parser.compose(reader);
            if (!(root instanceof org.yaml.snakeyaml.nodes.MappingNode)) {
                return Collections.emptyMap();
            }
            Map<String, String> scalars = new java.util.HashMap<>();
            for (org.yaml.snakeyaml.nodes.NodeTuple tuple : ((org.yaml.snakeyaml.nodes.MappingNode) root).getValue()) {
                if (tuple.getKeyNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode
                        && tuple.getValueNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode) {
                    scalars.put(((org.yaml.snakeyaml.nodes.ScalarNode) tuple.getKeyNode()).getValue(),
                            ((org.yaml.snakeyaml.nodes.ScalarNode) tuple.getValueNode()).getValue());
                }
            }
            return scalars;
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            LOGGER.log(Level.FINE, "plugin.yml is not valid YAML in " + jarFile.getName(), e);
            return Collections.emptyMap();
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
     * @throws com.ultikits.ultitools.exceptions.PluginModuleException with error code {@link
     *     com.ultikits.ultitools.exceptions.ErrorCode#PLUGIN_OPERATION_IN_PROGRESS} if an update or
     *     another uninstall of the same module is running; nothing was changed
     * @throws IOException if another I/O error occurs
     */
    public static boolean uninstallPlugin(String name) throws IOException {
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        // Take the module's guard before changing anything (review r4 WR-03). /upm uninstall names a
        // module by its runtime name, while an update keys on its identify-string; both come from the
        // same plugin.yml, so the uninstall resolves every identify-string that name stands for --
        // from the loaded modules and from the JARs in the modules folder -- and holds all of them.
        // Every key is taken or none is (review r5 IN-03): checking and taking them one at a time
        // could leave this uninstall holding the first key after refusing on the second, which would
        // block the module's next operation for good.
        Set<String> held = moduleKeysForName(name, pluginManager);
        synchronized (MODULE_OPERATIONS_IN_PROGRESS) {
            for (String key : held) {
                if (MODULE_OPERATIONS_IN_PROGRESS.contains(key)) {
                    throw new PluginModuleException(ErrorCode.PLUGIN_OPERATION_IN_PROGRESS, "An update or uninstall of module "
                            + name + " is already running; nothing was changed");
                }
            }
            MODULE_OPERATIONS_IN_PROGRESS.addAll(held);
        }
        try {
            return uninstallHoldingGuard(name, pluginManager);
        } finally {
            MODULE_OPERATIONS_IN_PROGRESS.removeAll(held);
        }
    }

    /**
     * The normalised identify strings the runtime module name {@code name} stands for: those of the
     * loaded modules with that name, those declared by JARs in the modules folder whose
     * {@code plugin.yml} {@code name} matches, and those named by the journal of an update that is
     * moving JARs right now. A module without an identify-string contributes none; such a module is
     * never offered an update, so it has nothing to be guarded against.
     */
    private static Set<String> moduleKeysForName(String name, PluginManager pluginManager) {
        Set<String> keys = new java.util.TreeSet<>();
        for (UltiToolsPlugin plugin : pluginManager.getPluginList()) {
            if (name.equals(plugin.getPluginName())) {
                String key = normalizeIdentifyString(plugin.getIdentifyString());
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        File[] jars = new File(UltiTools.getInstance().getDataFolder(), "plugins")
                .listFiles((f) -> f.getName().endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
                    Map<String, String> pluginYml = readPluginYmlScalars(jarFile);
                    String key = normalizeIdentifyString(pluginYml.get("identify-string"));
                    if (name.equals(pluginYml.get("name")) && key != null) {
                        keys.add(key);
                    }
                } catch (IOException | SecurityException e) {
                    LOGGER.log(Level.FINE, "Skipping unreadable JAR while resolving module " + name + ": " + jar, e);
                }
            }
        }
        keys.addAll(keysFromUpdateJournals(name));
        // A module may omit the optional identify-string, and then nothing above produces a key --
        // two callers of this public API would both enter the guard, unload the same instance
        // twice and race to delete its JAR (Codex review r8). The runtime name always serialises
        // the uninstall; it cannot collide with an update's key, which is an identify-string.
        keys.add(UNINSTALL_NAME_KEY_PREFIX + name.toLowerCase(Locale.ROOT));
        return keys;
    }

    /**
     * The normalised identify strings that the journals of currently running update transactions
     * record for the runtime module name {@code name}. Between moving the old JAR aside and moving
     * the new one in, the module has no JAR in the modules folder, so the journal -- and the
     * set-aside JAR it names -- is the only thing that still names the module (review r5 IN-03).
     * A journal that cannot be read contributes nothing rather than failing the uninstall.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // one bad journal must not fail the uninstall
    private static Set<String> keysFromUpdateJournals(String name) {
        Set<String> keys = new java.util.TreeSet<>();
        File stagingFolder = new File(UltiTools.getInstance().getDataFolder(), STAGING_DIRECTORY_NAME);
        File[] journals = stagingFolder.listFiles((f) -> f.getName().endsWith(JOURNAL_SUFFIX));
        if (journals == null) {
            return keys;
        }
        for (File journalFile : journals) {
            try {
                java.util.Properties entries = new java.util.Properties();
                try (java.io.Reader reader = Files.newBufferedReader(journalFile.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    entries.load(reader);
                }
                String key = normalizeIdentifyString(entries.getProperty("module"));
                if (key == null || !JOURNAL_FORMAT.equals(entries.getProperty("format"))) {
                    continue;
                }
                if (name.equals(entries.getProperty("name")) || journalNamesModule(entries, stagingFolder, name)) {
                    keys.add(key);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable update journal while resolving module "
                        + name + ": " + journalFile, e);
            }
        }
        return keys;
    }

    /**
     * Whether any set-aside JAR named by {@code entries} declares the runtime module name
     * {@code name}. The set-aside JAR is the module's own JAR, moved out of the modules folder by
     * the transaction that wrote the journal, so its {@code plugin.yml} answers the question.
     */
    private static boolean journalNamesModule(java.util.Properties entries, File stagingFolder, String name) {
        for (int i = 0; ; i++) {
            String aside = entries.getProperty("aside." + i + ".aside");
            if (aside == null) {
                return false;
            }
            if (!isPlainFileName(aside)) {
                continue;
            }
            File asideFile = new File(stagingFolder, aside);
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(asideFile)) {
                if (name.equals(readPluginYmlScalars(jarFile).get("name"))) {
                    return true;
                }
            } catch (IOException | SecurityException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable set-aside JAR while resolving module "
                        + name + ": " + asideFile, e);
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // deliberate barrier: a module's unload failure is collected and reported, see the loop
    private static boolean uninstallHoldingGuard(String name, PluginManager pluginManager) throws IOException {
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
            // Anything that is not a readable module JAR is skipped, not fatal (#504, Codex review
            // r6): the folder holds directories, notes and half-written downloads, and since this
            // loop looks at every entry rather than stopping at the first match, one of them used
            // to abort the uninstall after the module had already been unloaded.
            if (name.equals(moduleNameOf(file))) {
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
        // Nothing in the staging directory may bring this module back after an uninstall (Codex
        // review r6): boot recovery moves back whatever a surviving journal names, so a journal of
        // this module from an interrupted or half-cleaned update goes with it.
        clearStagingOf(name);
        return true;
    }

    /**
     * Deletes every update journal of the module named {@code name}, and the set-aside JARs those
     * journals record. Best effort: what cannot be deleted is logged, because the uninstall's own
     * outcome is about the module's JARs in the modules folder.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // an unreadable journal must not hide the ones that matter
    private static void clearStagingOf(String name) throws IOException {
        File stagingFolder = new File(UltiTools.getInstance().getDataFolder(), STAGING_DIRECTORY_NAME);
        File[] journals = stagingFolder.listFiles((f) -> f.getName().endsWith(JOURNAL_SUFFIX));
        if (journals == null) {
            return;
        }
        FileSystemException failure = null;
        for (File journalFile : journals) {
            try {
                java.util.Properties entries = new java.util.Properties();
                try (java.io.Reader reader = Files.newBufferedReader(journalFile.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    entries.load(reader);
                }
                if (!name.equals(entries.getProperty("name"))
                        && !journalNamesModule(entries, stagingFolder, name)) {
                    continue;
                }
                for (String[] pair : journalPairs(entries, journalFile, new java.util.concurrent.atomic.AtomicBoolean())) {
                    failure = firstOrSuppressed(failure, deleteStagedFile(new File(stagingFolder, pair[1])));
                }
                failure = firstOrSuppressed(failure, deleteStagedFile(journalFile));
            } catch (IOException | RuntimeException e) {
                // The journal cannot be read, so it cannot be shown to name this module either; it
                // is reported rather than deleted, and boot recovery will leave it in place.
                LOGGER.log(Level.WARNING, "Could not read the update journal " + journalFile
                        + " while uninstalling " + name, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Deletes one file left in the staging directory by an update of the module being uninstalled.
     * A file that survives can move the module's old JAR back at the next start, which would undo
     * the uninstall (Codex review r7), so the failure is reported rather than logged alone.
     *
     * @return the failure, or {@code null} when the file is gone
     */
    private static FileSystemException deleteStagedFile(File file) {
        try {
            if (Files.deleteIfExists(file.toPath())) {
                LOGGER.info("Deleted " + file.getAbsolutePath() + ", left by an update of a module being uninstalled");
            }
            return null;
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.SEVERE, "Could not delete " + file + " while uninstalling its module; "
                    + "delete it by hand, or the next start may move that module's old JAR back", e);
            FileSystemException failure = new FileSystemException(file.getAbsolutePath(), null,
                    "left by an update of this module; while it is there, the next start can move the module's old JAR back");
            failure.initCause(e);
            return failure;
        }
    }

    /** Keeps the first failure and attaches any later one to it. */
    private static FileSystemException firstOrSuppressed(FileSystemException first, FileSystemException next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * The {@code plugin.yml} {@code name} declared by the JAR at {@code file}.
     *
     * @return the name, or {@code null} when the file is not a readable JAR with a {@code plugin.yml}
     */
    private static String moduleNameOf(File file) {
        if (!file.isFile() || !file.getName().endsWith(".jar")) {
            return null;
        }
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            return readPluginYmlScalars(jarFile).get("name");
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Skipping unreadable JAR while looking for a module's JARs: " + file, e);
            return null;
        }
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
     * Deletes every file in {@code files}, attempting each even after an earlier one fails. A file
     * that no longer exists counts as deleted.
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
                // A file already gone -- for example removed by an overlapping @RunAsync update
                // that listed the same old jar (Codex P2 on #508) -- is the outcome asked for, not
                // a failure: reporting it would name a jar that is no longer on disk.
                Files.deleteIfExists(file.toPath());
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
