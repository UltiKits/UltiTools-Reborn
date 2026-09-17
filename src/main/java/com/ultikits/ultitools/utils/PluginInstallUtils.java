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
     * Name of the staging directory, directly under the framework's data folder and therefore
     * outside the scanned modules folder, where an update keeps its download and the old JARs it
     * sets aside.
     *
     * @since 6.3.0
     */
    static final String STAGING_DIRECTORY_NAME = ".upm-staging";

    /** Normalised identify strings of the modules whose update is running right now. */
    private static final Set<String> UPDATES_IN_PROGRESS = ConcurrentHashMap.newKeySet();

    /** File operations used by an update; replaced only by tests, to inject failures and record order. */
    static volatile UpdateFileOperations updateFileOperations = UpdateFileOperations.DEFAULT;

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
     *     download failed, the download is not a JAR of this module at the expected version, or an
     *     update of the same module was already running -- in each of those cases nothing changed
     * @throws java.io.UncheckedIOException wrapping a {@link java.nio.file.FileSystemException}
     *     when an older JAR could not be moved out of the modules folder or the new version could
     *     not be moved in; {@link java.nio.file.FileSystemException#getFile()} names that JAR or
     *     path, and the older JARs have been moved back (any that could not be are attached as
     *     suppressed {@code FileSystemException}s) (#505). This is an exception rather than
     *     {@code false} because {@code false} means nothing was changed, and a failed move can
     *     leave the modules folder changed when a set-aside JAR cannot be moved back
     */
    public static boolean updatePlugin(String identifyString) {
        UpdateOutcome outcome = updatePluginTransactionally(identifyString);
        switch (outcome.getStatus()) {
            case UPDATED:
                return true;
            case OLD_JAR_NOT_MOVED:
            case NEW_JAR_NOT_INSTALLED:
                FileSystemException failure = new FileSystemException(
                        outcome.getFiles().isEmpty() ? null : outcome.getFiles().get(0), null,
                        outcome.getStatus().name());
                if (outcome.getFailure() != null) {
                    failure.initCause(outcome.getFailure());
                }
                for (String unrestored : outcome.getUnrestoredFiles()) {
                    failure.addSuppressed(new FileSystemException(unrestored, null, "not moved back"));
                }
                throw new UncheckedIOException(failure);
            default:
                return false;
        }
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
        if (!UPDATES_IN_PROGRESS.add(moduleKey)) {
            return UpdateOutcome.of(UpdateOutcome.Status.ALREADY_IN_PROGRESS);
        }
        try {
            return runUpdateTransaction(identifyString, moduleKey);
        } finally {
            UPDATES_IN_PROGRESS.remove(moduleKey);
        }
    }

    private static UpdateOutcome runUpdateTransaction(String identifyString, String moduleKey) {
        String latestVersion = getPluginLatestVersion(identifyString);
        String downloadLink = getPluginVersionDownloadLink(identifyString, latestVersion);
        String fileName = installedJarName(identifyString, latestVersion);
        if (downloadLink == null || fileName == null) {
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
        try {
            Files.createDirectories(stagingFolder.toPath());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.SEVERE, "Could not prepare the update staging directory " + stagingFolder
                    + " for " + identifyString + "; nothing was changed", e);
            return new UpdateOutcome(UpdateOutcome.Status.STAGING_UNAVAILABLE,
                    Collections.singletonList(stagingFolder.getAbsolutePath()), Collections.<String>emptyList(),
                    Collections.<String>emptyList()).withFailure(e);
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
            return new UpdateOutcome(UpdateOutcome.Status.STAGING_UNAVAILABLE,
                    Collections.singletonList(stagingFolder.getAbsolutePath()), Collections.<String>emptyList(),
                    Collections.<String>emptyList()).withFailure(e);
        }

        // Step 1: download into the staging directory, never into the modules folder.
        try {
            operations.download(downloadLink, stagedName, stagingFolder);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            LOGGER.log(Level.SEVERE, "Failed to download update for " + identifyString + "; nothing was changed", e);
            deleteQuietly(operations, staged);
            return UpdateOutcome.of(UpdateOutcome.Status.DOWNLOAD_FAILED);
        }

        // Step 2: validate before touching anything.
        if (!isJarOfModule(staged.toFile(), moduleKey, latestVersion)) {
            LOGGER.severe("Downloaded update for " + identifyString
                    + " is not a loadable JAR of that module at version " + latestVersion
                    + "; nothing was changed");
            deleteQuietly(operations, staged);
            return UpdateOutcome.of(UpdateOutcome.Status.INVALID_DOWNLOAD);
        }

        // Step 3: select the older JARs while the new version is not in the modules folder.
        List<File> olderJars = operations.findModuleJars(pluginsFolder, identifyString);

        // Step 4: move every older JAR aside; on failure, move back what was moved.
        List<Path[]> movedAside = new ArrayList<>();
        for (File olderJar : olderJars) {
            Path original = olderJar.toPath();
            Path aside = stagingFolder.toPath().resolve(olderJar.getName() + "." + unique + ".old");
            try {
                operations.move(original, aside);
                movedAside.add(new Path[]{original, aside});
            } catch (NoSuchFileException gone) {
                // Removed since the listing: nothing left to move aside.
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.log(Level.WARNING, "Could not move " + original + " aside to " + aside
                        + " atomically; refusing the update of " + identifyString, e);
                List<Path[]> unrestored = moveBack(operations, movedAside);
                deleteQuietly(operations, staged);
                return withUnrestored(fileSystemsDiffer(stagingFolder, pluginsFolder, e), unrestored);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not move " + original + " aside to " + aside
                        + "; rolling back the update of " + identifyString, e);
                List<Path[]> unrestored = moveBack(operations, movedAside);
                deleteQuietly(operations, staged);
                return withUnrestored(new UpdateOutcome(UpdateOutcome.Status.OLD_JAR_NOT_MOVED,
                        Collections.singletonList(original.toAbsolutePath().toString()), Collections.<String>emptyList(),
                        Collections.<String>emptyList()).withFailure(e), unrestored);
            }
        }

        // Step 5: move the new version in under its final name; on failure, restore the older JARs.
        Path target = pluginsFolder.toPath().resolve(fileName);
        try {
            operations.move(staged, target);
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.log(Level.WARNING, "Could not move the new version to " + target
                    + " atomically; refusing the update of " + identifyString, e);
            List<Path[]> unrestored = moveBack(operations, movedAside);
            deleteQuietly(operations, staged);
            return withUnrestored(fileSystemsDiffer(stagingFolder, pluginsFolder, e), unrestored);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not move the new version to " + target
                    + "; rolling back the update of " + identifyString, e);
            List<Path[]> unrestored = moveBack(operations, movedAside);
            deleteQuietly(operations, staged);
            return withUnrestored(new UpdateOutcome(UpdateOutcome.Status.NEW_JAR_NOT_INSTALLED,
                    Collections.singletonList(target.toAbsolutePath().toString()), Collections.<String>emptyList(),
                    Collections.<String>emptyList()).withFailure(e), unrestored);
        }

        // Step 6: delete the set-aside JARs; any that remain are outside the modules folder and inert.
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
        return new UpdateOutcome(UpdateOutcome.Status.UPDATED, Collections.<String>emptyList(),
                Collections.<String>emptyList(), leftovers);
    }

    private static UpdateOutcome fileSystemsDiffer(File stagingFolder, File pluginsFolder, Throwable cause) {
        UpdateOutcome outcome = new UpdateOutcome(UpdateOutcome.Status.FILE_SYSTEMS_DIFFER,
                Arrays.asList(stagingFolder.getAbsolutePath(), pluginsFolder.getAbsolutePath()),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        return cause == null ? outcome : outcome.withFailure(cause);
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
            return moduleKey.equals(normalizeIdentifyString(pluginYml.get("identify-string")))
                    && version != null && expectedVersion != null
                    && VersionComparatorUtil.compare(version.trim(), expectedVersion.trim()) == 0;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Downloaded file is not a readable JAR: " + file, e);
            return false;
        }
    }

    /**
     * The {@code plugin.yml} {@code version} of the JAR at {@code file}, exactly as written, or
     * {@code null} when the JAR, its {@code plugin.yml} or the key cannot be read.
     */
    static String readModuleVersion(File file) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(file)) {
            return readPluginYmlScalars(jarFile).get("version");
        } catch (IOException e) {
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
            org.yaml.snakeyaml.nodes.Node root = new org.yaml.snakeyaml.Yaml().compose(reader);
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
