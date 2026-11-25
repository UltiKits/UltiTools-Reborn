package com.ultikits.ultitools.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.util.function.Function;

import org.bukkit.command.CommandSender;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import static com.ultikits.ultitools.text.Arg.number;
import static com.ultikits.ultitools.text.Arg.string;
import static com.ultikits.ultitools.utils.ModuleFileTransactions.text;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.manager.ModuleApiLevels;
import com.ultikits.ultitools.manager.ModuleJarIndex;
import com.ultikits.ultitools.manager.PluginManager;

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
    private static final String CATALOGUE_URL_PROPERTY = "ultitools.catalogue.url";
    private static final String REASON_INTEGRITY_FAILED =
            "已拒绝安装 %s %s：文件没有通过云端目录的完整性校验（SHA-256 或大小与目录不符，或目录没有给出可用的校验值）。模块目录没有任何改动。";
    private static final String REASON_LICENCE_REQUIRED =
            "无法安装 %s %s：该版本需要许可证，不提供公开下载。模块目录没有任何改动。";

    /** How the uninstall asks a loaded module which JAR it came from; a test passes its own. */
    static final java.util.function.Function<UltiToolsPlugin, File> DEFAULT_MODULE_CODE_SOURCE =
            module -> codeSourceLocationOf(module.getClass());

    /**
     * The UltiCloud catalogue, as the update transaction asks it. Versions exist but none this
     * framework installs -- the level-filtered list is empty -- is thrown as
     * {@link ModuleFileTransactions.NoCompatibleVersionException} (plan 18-40).
     */
    static final ModuleFileTransactions.Catalogue CATALOGUE = new ModuleFileTransactions.Catalogue() {
        @Override
        public String latestVersion(String identifyString) {
            int level = ModuleApiLevels.frameworkLevel();
            if (CatalogueClient.module(identifyString) == null) {
                return null;
            }
            List<CatalogueClient.Version> versions = CatalogueClient.versions(identifyString, level);
            if (versions == null) {
                return null;
            }
            CatalogueClient.Version latest = CatalogueClient.newest(versions);
            if (latest == null) {
                throw new ModuleFileTransactions.NoCompatibleVersionException(level);
            }
            return latest.version();
        }

        @Override
        public String downloadLink(String identifyString, String version) {
            return getPluginVersionDownloadLink(identifyString, version);
        }
    };

    // Use lazy initialization to avoid static initializer dependency on UltiTools
    private static volatile String baseUrl;
    
    // Custom base URL for testing purposes
    private static String customBaseUrl;

    /** The suffix of a download not yet checked; the module loader reads only {@code *.jar}. */
    static final String PART_SUFFIX = ".part";
    
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
                    baseUrl = configuredCatalogueUrl();
                }
            }
        }
        return baseUrl;
    }
    
    /** Resolves an explicitly configured test URL early enough for its diagnostic to appear at startup. */
    @ApiStatus.Internal
    public static void initializeCatalogueUrl() {
        getBaseUrl();
    }

    private static String configuredCatalogueUrl() {
        String value = System.getProperty(CATALOGUE_URL_PROPERTY);
        if (value != null) {
            if (isLoopbackCatalogueUrl(value)) {
                LOGGER.warning("The module catalogue URL was overridden by ultitools.catalogue.url to " + value
                        + " (for testing only).");
                return value;
            }
            LOGGER.warning("Ignoring ultitools.catalogue.url: expected an HTTP(S) URL on a loopback host "
                    + "(127.0.0.1, [::1], or localhost) with no user info, query, or fragment.");
        }
        return UltiTools.getEnv().getString("api-url");
    }

    private static boolean isLoopbackCatalogueUrl(String value) {
        try {
            URI url = URI.create(value);
            String host = url.getHost();
            return ("https".equals(url.getScheme()) || "http".equals(url.getScheme()))
                    && ("127.0.0.1".equals(host) || "[::1]".equals(host) || "localhost".equalsIgnoreCase(host))
                    && url.getPort() <= 65535 && url.getPort() != 0 && url.getUserInfo() == null
                    && url.getRawQuery() == null && url.getRawFragment() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
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

    static String normalizeIdentifyString(String idString) {
        if (idString == null) {
            return null;
        }
        String normalized = idString.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
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
     * Get plugin list online.
     *
     * @param page     the page number
     * @param pageSize the number of items per page
     * @return the plugin list
     */
    public static List<PluginEntity> getPluginList(int page, int pageSize) {
        return CatalogueClient.list(page, pageSize);
    }

    /**
     * Get plugin download link.
     *
     * @param idString the plugin ID
     * @param version  the version
     * @return the plugin download link
     */
    public static String getPluginVersionDownloadLink(String idString, String version) {
        if (version == null || version.trim().isEmpty()) {
            return null;
        }
        CatalogueClient.Version found = CatalogueClient.version(idString, version.trim());
        return found == null ? null : CatalogueClient.absoluteDownloadUrl(found.downloadUrl());
    }

    /**
     * Gets all versions this framework can install, across every page of the catalogue.
     *
     * @param idString the plugin ID
     * @return the complete plugin version list, newest first as the catalogue lists them
     */
    public static List<String> getPluginVersions(String idString) {
        List<CatalogueClient.Version> versions = CatalogueClient.versions(idString,
                ModuleApiLevels.frameworkLevel());
        if (versions == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (CatalogueClient.Version version : versions) {
            names.add(version.version());
        }
        return names;
    }

    /**
     * Internal command lookup that distinguishes an unknown module from a successful empty list.
     * This bridge is not part of the supported plugin API; {@link #getPluginVersions(String)} keeps
     * its existing empty-list/null contract.
     *
     * @param idString the plugin ID
     * @param names collects version names on success
     * @return {@code FOUND} with the module identifier on success, {@code NONE} when the versions
     *         endpoint answers 404, or {@code FAILED} when the lookup establishes nothing
     */
    @ApiStatus.Internal
    public static VersionLookup lookUpPluginVersions(String idString, List<String> names) {
        List<CatalogueClient.Version> versions = new ArrayList<>();
        VersionLookup lookup = CatalogueClient.lookUpVersions(idString, ModuleApiLevels.frameworkLevel(), versions);
        for (CatalogueClient.Version version : versions) {
            names.add(version.version());
        }
        return lookup;
    }

    /**
     * Get plugin latest version.
     *
     * <p>Returns {@code null} for a blank id, a module the catalogue does not know, no version this
     * framework can install, and every failure (an error status, an unreadable body, an unreachable
     * catalogue); this contract is unchanged as of 6.3.0. The start-up update check uses
     * {@link #lookUpPluginLatestVersion(String)} instead, which tells those apart.
     *
     * @param idString the plugin ID
     * @return the plugin's latest version, or {@code null}
     */
    public static String getPluginLatestVersion(String idString) {
        return lookUpPluginLatestVersion(idString).getVersion();
    }

    /**
     * Looks up a module's latest version for this framework's API level and says whether the
     * lookup established anything (Phase 19, D19-032). {@link VersionLookup.Outcome#FOUND} carries
     * the version exactly as {@link #getPluginLatestVersion(String)} returns it.
     * {@link VersionLookup.Outcome#NONE} is a confirmed "nothing to offer": a blank id, the
     * catalogue not knowing the module ({@code plugin/get} answers 404), or knowing it with no
     * version this framework can install or no finalized version ({@code latest} answers 409 or
     * 404). Every other answer -- another non-2xx status, an error envelope, a missing
     * {@code data}, an unreadable body, an unreachable catalogue -- is
     * {@link VersionLookup.Outcome#FAILED}.
     * <p>
     * An exception thrown while asking propagates, exactly as it does from
     * {@link #getPluginLatestVersion(String)}.
     *
     * @param idString the module's identify string
     * @return the lookup's outcome, never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static VersionLookup lookUpPluginLatestVersion(String idString) {
        return lookUpPluginLatestVersion(idString, () -> false);
    }

    /**
     * {@link #lookUpPluginLatestVersion(String)}, with a stop signal read before each request,
     * including every version page (Phase 19, D19-032). When {@code stopped} is true, the lookup is
     * {@link VersionLookup.Outcome#FAILED} with the failure {@code "stopped"}. The start-up update
     * check passes its own stop flag, so a check stopped by the plugin's disable sends no further
     * request.
     *
     * @param idString the module's identify string
     * @param stopped  read before the module request and every version-page request
     * @return the lookup's outcome, never {@code null}
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public static VersionLookup lookUpPluginLatestVersion(String idString, java.util.function.BooleanSupplier stopped) {
        if (stopped.getAsBoolean()) {
            return VersionLookup.failed("stopped");
        }
        VersionLookup module = CatalogueClient.moduleKnown(idString);
        if (module.getOutcome() != VersionLookup.Outcome.FOUND) {
            return module;
        }
        return CatalogueClient.latestVersion(idString, ModuleApiLevels.frameworkLevel(), stopped);
    }

    /**
     * Get plugin latest version download link.
     *
     * @param idString Plugin identify string
     * @return Download link for latest version
     */
    public static String getPluginLatestDownloadLink(String idString) {
        List<CatalogueClient.Version> versions = CatalogueClient.versions(idString,
                ModuleApiLevels.frameworkLevel());
        if (versions == null) {
            return null;
        }
        CatalogueClient.Version latest = CatalogueClient.newest(versions);
        return latest == null ? null : CatalogueClient.absoluteDownloadUrl(latest.downloadUrl());
    }

    /**
     * Get plugin information by identify string.
     *
     * @param idString Plugin identify string
     * @return Plugin entity or null if not found
     */
    public static PluginEntity getPlugin(String idString) {
        return CatalogueClient.module(idString);
    }

    /**
     * Requests an installation of the latest compatible version.
     * @param idString the catalogue module identifier
     * @return whether a new change was recorded for the next start
     */
    public static boolean installLatestPlugin(String idString) {
        return installReporting(idString, null).queued();
    }

    /**
     * Requests an installation of the specified version.
     * @param idString the catalogue module identifier
     * @param version the catalogue version
     * @return whether a new change was recorded for the next start
     */
    public static boolean installPlugin(String idString, String version) {
        return installReporting(idString, version == null ? "" : version).queued();
    }

    /** @return the install request, with no command sender (for internal programmatic callers) */
    @ApiStatus.Internal
    public static PendingResult installReporting(String idString, String versionOrNull) {
        return installReporting(idString, versionOrNull, null);
    }

    /** Queues a verified catalogue module without changing the running modules or their files. */
    @ApiStatus.Internal
    public static PendingResult installReporting(String idString, String versionOrNull, CommandSender sender) {
        int level = ModuleApiLevels.frameworkLevel();
        int floor = ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL;
        String requested = idString == null ? "?" : idString.trim();
        PluginEntity module = CatalogueClient.module(idString);
        if (module == null) {
            return PendingResult.reply(UpmKeys.INSTALL_FAILED);
        }
        CatalogueClient.Version metadata;
        if (versionOrNull == null) {
            List<CatalogueClient.Version> versions = CatalogueClient.versions(idString, level);
            if (versions == null) {
                return PendingResult.reply(UpmKeys.INSTALL_FAILED);
            }
            metadata = CatalogueClient.newest(versions);
            if (metadata == null) {
                return PendingResult.reply(UpmKeys.INSTALL_REFUSED_NO_COMPATIBLE_VERSION, string("module", requested),
                        number("level", level), number("floor", floor), number("max", level));
            }
        } else {
            metadata = CatalogueClient.version(idString, versionOrNull.trim());
            if (metadata == null) {
                return PendingResult.reply(UpmKeys.INSTALL_FAILED);
            }
            Integer moduleLevel = metadata.apiLevel();
            if (moduleLevel != null && !ModuleApiLevels.isInstallable(moduleLevel, floor, level)) {
                return PendingResult.reply(UpmKeys.INSTALL_REFUSED_VERSION_INCOMPATIBLE, string("module", requested),
                        string("version", versionOrNull.trim()), number("level", level), number("floor", floor), number("max", level));
            }
        }
        return installVersion(metadata, module, requested, level, sender);
    }

    private static PendingResult installVersion(CatalogueClient.Version metadata, PluginEntity module, String requested,
                                                int level, CommandSender sender) {
        String version = metadata.version();
        String identify = module.getIdentifyString() == null ? requested : module.getIdentifyString();
        if (installedJarName(identify, version) == null) {
            return PendingResult.reply(UpmKeys.INSTALL_FAILED);
        }
        var admission = new PendingResult[1];
        ModuleFileTransactions.Catalogue catalogue = new ModuleFileTransactions.Catalogue() {
            @Override public String latestVersion(String id) { return version; }

            @Override public String downloadLink(String id, String desiredVersion) {
                if (!metadata.downloadable()) {
                    if (licenceRequired(metadata)) {
                        LOGGER.log(Level.WARNING, FrameworkText.format(REASON_LICENCE_REQUIRED, requested,
                                version));
                        admission[0] = PendingResult.reply(UpmKeys.INSTALL_REFUSED_LICENCE_REQUIRED,
                                string("module", requested), string("version", version));
                    } else {
                        LOGGER.log(Level.WARNING, notAvailableLine(requested, version));
                        admission[0] = PendingResult.reply(UpmKeys.INSTALL_FAILED);
                    }
                    return null;
                }
                String url = CatalogueClient.absoluteDownloadUrl(metadata.downloadUrl());
                if (url == null) {
                    LOGGER.log(Level.SEVERE, foreignDownloadLine(requested, version));
                    admission[0] = PendingResult.reply(UpmKeys.INSTALL_FAILED);
                }
                return url;
            }
        };
        UltiTools framework = UltiTools.getInstance();
        PluginManager manager = framework.getPluginManager();
        String name = module.getName() == null || module.getName().isBlank() ? requested : module.getName();
        var staged = transactions(framework).stageChange(identify, version, name, PendingChanges.Requester.from(sender),
                () -> manager == null ? List.of() : manager.getPluginList(), DEFAULT_MODULE_CODE_SOURCE, catalogue,
                (url, file, folder) -> {
                    admission[0] = downloadAndAdmit(url, metadata, file, folder, requested, version, level);
                    if (admission[0] != null) {
                        throw new IOException("the downloaded module was not admitted");
                    }
                }, recordedMainClass(manager == null ? null : manager.getModuleJarIndex()));
        var result = PendingResult.builder();
        appendPreviousFailure(result, name, staged);
        if (admission[0] != null) {
            result.replies.addAll(admission[0].replies());
            return result.build();
        }
        appendStageReply(result, name, staged, true);
        return result.build();
    }

    /** @return the running framework's journal view */
    @ApiStatus.Internal
    public static PendingChanges pendingChanges() {
        return new PendingChanges(transactions(UltiTools.getInstance()));
    }

    /** @return a catalogue identifier for a queued change, even if the module is not loaded */
    @ApiStatus.Internal
    public static String pendingUpdateIdentifier(String name) {
        UltiTools framework = UltiTools.getInstance();
        if (name == null || name.isBlank() || framework.getModuleFileTransactions() == null && framework.getDataFolder() == null) {
            return null;
        }
        return transactions(framework).pendingIdentifyString(name);
    }

    private static ModuleFileTransactions transactions(UltiTools framework) {
        ModuleFileTransactions existing = framework.getModuleFileTransactions();
        return existing != null ? existing : new ModuleFileTransactions(framework.getDataFolder());
    }

    /**
     * Verifies the download, then checks it as an archive and by its declared {@code api-version},
     * and either renames it to {@code fileName} or deletes it. No partial file survives this method,
     * whatever happens.
     */
    private static PendingResult downloadAndAdmit(String url, CatalogueClient.Version metadata, String fileName,
                                                 File folder, String requested, String version, int level) {
        File part = new File(folder, fileName + PART_SUFFIX);
        VerifiedDownload.Result download = VerifiedDownload.fetch(url, metadata.sha256(), metadata.sizeBytes(),
                part.toPath());
        if (download.kind() != VerifiedDownload.Kind.OK) {
            LOGGER.log(Level.SEVERE, failureLine(requested, version, download.kind()));
            LOGGER.log(Level.FINE, "Catalogue download for " + requested + " " + version + " was refused: "
                    + download.kind() + "; expected sha256 " + VerifiedDownload.shortHash(metadata.sha256())
                    + ", size " + metadata.sizeBytes());
            return isIntegrityFailure(download.kind())
                    ? PendingResult.reply(UpmKeys.INSTALL_REFUSED_INTEGRITY_FAILED,
                            string("module", requested), string("version", version))
                    : PendingResult.reply(UpmKeys.INSTALL_FAILED);
        }
        boolean admitted = false;
        try {
            PendingResult refusal = refusalOf(part, requested, version, level);
            if (refusal != null) {
                return refusal;
            }
            moveIntoPlace(part, new File(folder, fileName));
            admitted = true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to install " + requested + " " + version + ": " + e.getMessage());
            return PendingResult.reply(UpmKeys.INSTALL_FAILED);
        } finally {
            if (!admitted) {
                deleteQuietly(part);
            }
        }
        return null;
    }

    /**
     * The one line an operator sees when a download did not pass its checks: the module, the
     * version and the reason, and that nothing was installed. A withdrawn version, a transport
     * failure and an integrity failure cannot be mistaken for one another; every integrity failure
     * (hash, size, hash header, no usable hash or size) is the catalogue text
     * {@link UpmKeys#INSTALL_REFUSED_INTEGRITY_FAILED} in the server's language (plan 22-22), and
     * which check failed is on the FINE line beside it. Only short hash prefixes may join it in the
     * local log, never a whole URL.
     *
     * @param module the module as the operator asked for it
     * @param version the version
     * @param kind why the download was refused
     * @return the line
     */
    static String failureLine(String module, String version, VerifiedDownload.Kind kind) {
        if (isIntegrityFailure(kind)) {
            return FrameworkText.format(REASON_INTEGRITY_FAILED, module, version);
        }
        switch (kind) {
            case NOT_AVAILABLE:
                // 403/404: the contract uses 404 for every version that is not public.
                return module + " " + version
                        + " is not available for download (withdrawn, unpublished or not licensed);"
                        + " nothing was installed.";
            case IO_ERROR:
            default:
                // A timeout, a broken stream, a 5xx or a redirect: the bytes never arrived whole.
                return "Could not download " + module + " " + version
                        + " (the download did not complete); nothing was installed.";
        }
    }

    /**
     * @param kind why a download was refused
     * @return whether the bytes, the header or the catalogue entry failed the integrity check, as opposed to a
     *         version that is not offered or a download that did not complete
     */
    private static boolean isIntegrityFailure(VerifiedDownload.Kind kind) {
        return kind == VerifiedDownload.Kind.HASH_MISMATCH || kind == VerifiedDownload.Kind.SIZE_MISMATCH
                || kind == VerifiedDownload.Kind.HEADER_MISMATCH || kind == VerifiedDownload.Kind.MISSING_METADATA;
    }

    /** @return whether the catalogue offers the version only with a licence */
    private static boolean licenceRequired(CatalogueClient.Version metadata) {
        return "license_required".equals(metadata.downloadKind());
    }

    /**
     * The line for a {@code download.url} the catalogue put outside its own host: refused before
     * anything is requested, because a catalogue answer must never move an executable download.
     */
    private static String foreignDownloadLine(String module, String version) {
        return module + " " + version + " was refused: the download is not on the catalogue host;"
                + " nothing was installed.";
    }

    /**
     * The line for a version the catalogue lists but does not offer for another reason than a licence (that one is
     * {@link UpmKeys#INSTALL_REFUSED_LICENCE_REQUIRED}); it is not an integrity failure.
     */
    private static String notAvailableLine(String module, String version) {
        return module + " " + version + " is not available for download (no public download);"
                + " nothing was installed.";
    }

    /**
     * Why a downloaded JAR is not installed, or {@code null} to install it: the archive guard the
     * module loader applies ({@link SecurityPolicy#isSafeFileStructure}; the {@code .jar} suffix
     * check of {@link SecurityPolicy#isValidModuleJar} does not apply to the {@code .part} name), then
     * the declared {@code api-version}.
     */
    private static PendingResult refusalOf(File part, String requested, String version, int level) {
        int floor = ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL;
        if (!isSafeArchive(part)) {
            return PendingResult.reply(UpmKeys.INSTALL_REFUSED_JAR_INVALID, string("module", requested),
                    string("version", version));
        }
        String declared = PluginYmlReader.readFromJarFile(part).getApiVersion();
        if (declared == null || declared.trim().isEmpty()) {
            return PendingResult.reply(UpmKeys.INSTALL_REFUSED_JAR_NO_LEVEL, string("module", requested),
                    string("version", version), number("level", level), number("floor", floor), number("max", level));
        }
        Integer moduleLevel = ModuleApiLevels.parse(declared);
        if (moduleLevel == null || !ModuleApiLevels.isInstallable(moduleLevel, floor, level)) {
            return PendingResult.reply(UpmKeys.INSTALL_REFUSED_JAR_LEVEL_REFUSED, string("module", requested),
                    string("version", version), string("declared", declared.trim()),
                    number("level", level), number("floor", floor), number("max", level));
        }
        return null;
    }

    private static boolean isSafeArchive(File file) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
            return SecurityPolicy.isSafeFileStructure(file.length(), jar.size());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.FINE, "Downloaded file is not a readable JAR: " + file.getName(), e);
            return false;
        }
    }

    /** Renames the checked download to its final name in one step, replacing a file of that name. */
    private static void moveIntoPlace(File part, File target) throws IOException {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException | SecurityException e) {
            LOGGER.log(Level.WARNING, "Could not delete the unchecked download " + file.getAbsolutePath(), e);
        }
    }

    /** A command reply in the framework catalogue, with typed arguments. */
    @ApiStatus.Internal
    public record PendingReply(MessageKey key, List<Arg> args) {
        public PendingReply {
            args = List.copyOf(args);
        }
    }

    /** Result of an install, update, or uninstall request; no chat or logger dependencies. */
    @ApiStatus.Internal
    public record PendingResult(boolean queued, List<PendingReply> replies, String warningModule, ModuleContent content) {
        public PendingResult {
            replies = List.copyOf(replies);
        }

        static Builder builder() { return new Builder(); }
        static PendingResult reply(MessageKey key, Arg... args) { return builder().add(key, args).build(); }

        static final class Builder {
            private boolean queued;
            private final List<PendingReply> replies = new ArrayList<>();
            private String warningModule;
            private ModuleContent content = ModuleContent.NONE;

            Builder queued() {
                queued = true;
                return this;
            }

            Builder add(MessageKey key, Arg... args) {
                replies.add(new PendingReply(key, List.of(args)));
                return this;
            }

            Builder warning(String module, ModuleContent warning) {
                warningModule = module;
                content = warning;
                return this;
            }

            Builder undetermined(List<String> files) {
                if (!files.isEmpty()) {
                    add(UpmKeys.UNINSTALL_UNDETERMINED, number("count", files.size()),
                            string("files", String.join(", ", files)));
                }
                return this;
            }

            PendingResult build() { return new PendingResult(queued, replies, warningModule, content); }
        }
    }

    /** Converts the shared stage result into update replies in the operator's language. */
    @ApiStatus.Internal
    public static PendingResult updateResult(String requested, ModuleFileTransactions.StageResult staged) {
        var result = PendingResult.builder();
        appendPreviousFailure(result, requested, staged);
        appendStageReply(result, requested, staged, false);
        return result.build();
    }

    private static void appendPreviousFailure(PendingResult.Builder result, String requested,
                                              ModuleFileTransactions.StageResult staged) {
        if (staged.getPreviousFailure() != null) {
            result.add(UpmKeys.PENDING_PREVIOUS_FAILED, string("module", requested),
                    string("failure", staged.getPreviousFailure()));
        }
    }

    private static void appendStageReply(PendingResult.Builder result, String requested,
                                         ModuleFileTransactions.StageResult staged, boolean install) {
        Arg module = string("module", staged.getModuleName() != null ? staged.getModuleName() : requested);
        Arg from = string("from", text(staged.getOldVersion()));
        Arg to = string("to", text(staged.getNewVersion()));
        Arg version = string("version", text(staged.getNewVersion()));
        Arg old = string("old_version", text(staged.getPreviousVersion()));
        switch (staged.getOutcome()) {
            case QUEUED_INSTALL -> result.queued().add(UpmKeys.INSTALL_QUEUED, module, version);
            case REPLACED_INSTALL -> result.queued().add(UpmKeys.INSTALL_REPLACED_INSTALL, module, old, version);
            case STAGED -> {
                if (install) {
                    result.queued().add(UpmKeys.INSTALL_AS_UPDATE, module, from, version);
                } else {
                    result.queued().add(UpmKeys.UPDATE_STAGED, module, from, to);
                }
            }
            case REPLACED_UPDATE -> result.queued().add(UpmKeys.UPDATE_REPLACED, module, old, from, to);
            case CANCELLED_UNINSTALL -> result.queued().add(UpmKeys.INSTALL_CANCELLED_UNINSTALL, module, from, version);
            case CANCELLED_UNINSTALL_SAME -> result.add(UpmKeys.INSTALL_CANCELLED_UNINSTALL_SAME, module, version);
            case CANCELLED_UPDATE -> result.add(UpmKeys.INSTALL_CANCELLED_UPDATE_SAME, module, old, version);
            case ALREADY_CURRENT -> result.add(UpmKeys.INSTALL_ALREADY_CURRENT, module, version);
            case ALREADY_STAGED -> result.add(UpmKeys.PENDING_UNCHANGED, module, version);
            case BUSY -> result.add(UpmKeys.PENDING_BUSY, module);
            case UNFINISHED -> result.add(UpmKeys.PENDING_UNFINISHED, module);
            case PENDING_UNINSTALL -> result.add(UpmKeys.UPDATE_PENDING_UNINSTALL, module);
            case FAILED -> appendStageFailure(result, module, staged, install);
        }
    }

    private static void appendStageFailure(PendingResult.Builder result, Arg module,
                                           ModuleFileTransactions.StageResult staged, boolean install) {
        MessageKey reason = staged.getReasonKey();
        Arg[] args = staged.getReasonArgs();
        if (UpmKeys.REASON_NEEDS_OPERATOR.equals(reason)) {
            result.add(UpmKeys.PENDING_NEEDS_OPERATOR, module, args[0], args[1]);
        } else if (install && UpmKeys.REASON_RECORD_FAILED.equals(reason)) {
            result.add(UpmKeys.INSTALL_NOT_RECORDED, module, args[0]);
        } else if (install) {
            result.add(UpmKeys.INSTALL_FAILED);
        } else {
            result.add(UpmKeys.UPDATE_NOT_STAGED, module, Arg.text("reason", Text.of(reason, args)));
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
        // The loader's own listing, in its file-name order (#476), so the JAR found first is the
        // same on every file system.
        File[] jars = ModuleFileTransactions.moduleJars(pluginsFolder);
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
     * Stages an update of a loaded module to its latest version (#505).
     *
     * <p>Nothing in the modules folder changes: the new JAR is downloaded into the transaction
     * folder beside it and recorded. At the next start the files are swapped before any module
     * loads, and the update is kept only if the module is then seen loaded from the new JAR at the
     * new version; otherwise the old JAR is restored. See {@link ModuleFileTransactions}.
     *
     * @param identifyString the module's identify-string
     * @return what was staged, or why nothing was
     */
    @ApiStatus.Internal
    public static ModuleFileTransactions.StageResult stageUpdate(String identifyString) {
        return stageUpdate(identifyString, null);
    }

    /** Requests a restart-time update as the supplied command sender. */
    @ApiStatus.Internal
    public static ModuleFileTransactions.StageResult stageUpdate(String identifyString, CommandSender sender) {
        UltiTools ultiTools = UltiTools.getInstance();
        PluginManager pluginManager = ultiTools.getPluginManager();
        // The loaded modules are read by staging under its lock, not snapshotted here: an uninstall
        // completing between a snapshot and the lock would be missed (Codex round 16).
        StagingCatalogue staging = new StagingCatalogue();
        return transactions(ultiTools).stageChange(identifyString, null, null, PendingChanges.Requester.from(sender),
                pluginManager::getPluginList, DEFAULT_MODULE_CODE_SOURCE,
                staging, staging, recordedMainClass(pluginManager.getModuleJarIndex()));
    }

    /**
     * The catalogue and downloader of one staging call (plan 22-26 fix round).
     *
     * <p>The catalogue contract makes a {@code download.url} an opaque address: it must not be built
     * or parsed, and its shape may change inside a contract version. So the download is verified
     * against the version this staging has just read through the contract's version route (the
     * module's identifier and the version, {@link CatalogueClient#version(String, String)}), which is
     * also where its link came from, and never against a route derived from the URL. A link this
     * staging did not just obtain from the catalogue is not downloaded.
     *
     * <p>One instance serves one call of {@link ModuleFileTransactions#stageUpdate}, which asks for
     * the link and then downloads it on the same thread; it is not shared.
     */
    static final class StagingCatalogue implements ModuleFileTransactions.Catalogue,
            ModuleFileTransactions.Downloader {
        private volatile CatalogueClient.Version resolved;
        private volatile String resolvedLink;

        @Override
        public String latestVersion(String identifyString) {
            return CATALOGUE.latestVersion(identifyString);
        }

        @Override
        public String downloadLink(String identifyString, String version) {
            resolved = null;
            resolvedLink = null;
            if (version == null || version.trim().isEmpty()) {
                return null;
            }
            CatalogueClient.Version found = CatalogueClient.version(identifyString, version.trim());
            String link = found == null ? null : CatalogueClient.absoluteDownloadUrl(found.downloadUrl());
            if (link != null) {
                resolved = found;
                resolvedLink = link;
            }
            return link;
        }

        @Override
        public void download(String link, String fileName, File folder) throws IOException {
            CatalogueClient.Version metadata = resolved;
            if (metadata == null || link == null || !link.equals(resolvedLink)) {
                throw new IOException("the version's catalogue entry could not be read");
            }
            downloadVerified(link, metadata, fileName, folder);
        }
    }

    /**
     * The staging download: the same verification the install applies, because a staged update
     * becomes the module that loads at the next start. The link is the version's {@code
     * download.url}, used as written; the metadata it is checked against is the version object the
     * caller has just read through the contract's version route, so staging never trusts a hash it
     * did not just fetch.
     *
     * @param link     the absolute version download URL
     * @param metadata the version, as the catalogue's version route just answered it
     * @param fileName the file name to write
     * @param folder   the folder to write it into
     * @throws IOException when the catalogue offers no public download or the file does not verify
     */
    static void downloadVerified(String link, CatalogueClient.Version metadata, String fileName, File folder)
            throws IOException {
        if (metadata == null) {
            throw new IOException("the version's catalogue entry could not be read");
        }
        if (!metadata.downloadable()) {
            throw new IOException("the catalogue offers no public download for this version");
        }
        File part = new File(folder, fileName + PART_SUFFIX);
        VerifiedDownload.Result result = VerifiedDownload.fetch(link, metadata.sha256(), metadata.sizeBytes(),
                part.toPath());
        if (result.kind() == VerifiedDownload.Kind.OK) {
            moveIntoPlace(part, new File(folder, fileName));
            return;
        }
        LOGGER.log(Level.SEVERE, failureLine(fileName, metadata.version(), result.kind()));
        throw new IOException("the downloaded file did not pass its integrity check (" + reasonOf(result.kind()) + ")");
    }

    /** @return the reason a staged download was refused, as the update reason names it */
    static String reasonOf(VerifiedDownload.Kind kind) {
        switch (kind) {
            case SIZE_MISMATCH:
                return "size mismatch";
            case HEADER_MISMATCH:
                return "hash header mismatch";
            case MISSING_METADATA:
                return "no usable hash or size in the catalogue entry";
            case NOT_AVAILABLE:
                return "not available";
            case IO_ERROR:
                return "download did not complete";
            default:
                return "hash mismatch";
        }
    }

    /**
     * The main class the module loader recorded for a loaded module, read from its start-up index
     * (#516): what staging checks other copies of the module against (maintainer follow-up 19).
     * {@code null} for a module the index has no record of, so that staging falls back to the old
     * JAR's own {@code plugin.yml} {@code main:} rather than to a runtime class name no JAR declares.
     *
     * @param index the loader's index, or {@code null}
     * @return the resolver
     */
    static java.util.function.Function<UltiToolsPlugin, String> recordedMainClass(ModuleJarIndex index) {
        if (index == null) {
            return module -> null;
        }
        return module -> {
            String mainClass = index.mainClassOf(module);
            return index.jarsDeclaring(mainClass).isEmpty() ? null : mainClass;
        };
    }

    /**
     * Stages an update of a loaded module; see {@link #stageUpdate(String)}.
     *
     * <p>As of 6.3.0 this no longer replaces the JAR in place and no longer means "updated": it
     * returns {@code true} when the update was staged, to take effect at the next start and to be
     * kept only if the module then loads.
     *
     * @param identifyString the plugin identify string
     * @return {@code true} if the update was staged
     */
    public static boolean updatePlugin(String identifyString) {
        return stageUpdate(identifyString).getOutcome() == ModuleFileTransactions.StageResult.Outcome.STAGED;
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
     * <p>The loaded instance is the authority on which file it came from, whatever that file's
     * {@code plugin.yml} says now: the file may have been replaced or edited since the start, and a
     * module registered from code has no {@code plugin.yml} entry the loader read at all. So the
     * framework asks the instance, exactly as {@code UltiToolsPlugin} itself does when it reads its
     * own embedded resources.
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
        /** The {@code main:} its {@code plugin.yml} declares: the one entry the module loader reads (#516). */
        private final String declaredMain;

        private ArchiveIdentity(EntryState state, String declaredName) {
            this(state, declaredName, null);
        }

        private ArchiveIdentity(EntryState state, String declaredName, String declaredMain) {
            this.state = state;
            this.declaredName = declaredName;
            this.declaredMain = declaredMain;
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
        // The same `.jar` test the start-up scan applies to this folder (ModuleFileTransactions#moduleJars):
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
                // Reported as undetermined rather than passed over. This dates from before #548,
                // when a module needed no plugin.yml at all; since #548 the start-up scan refuses a
                // JAR whose plugin.yml has no main:, so such a JAR cannot load a module by itself.
                // It is still named, without being called a copy, which is the conservative side:
                // whether to stop naming it changes what the operator is told, never what is
                // deleted, and deciding it from the archive's classes is the approach this work
                // removed. (#516 identifies a copy by its plugin.yml main:, which this lacks.)
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
            // The main: entry is read with the name, from the same parse: it is what the module loader
            // loads (#548/#549), so it identifies the module whatever the name says (#516).
            String main = config.getString("main");
            String declaredMain = main == null || main.trim().isEmpty() ? null : main;
            if (declared == null || declared.trim().isEmpty()) {
                // A plugin.yml with no name: key -- or one whose name is blank -- carries exactly as
                // much about which module this is by name as no plugin.yml at all, which is nothing
                // (gate 1, WR-01, and its completion). A blank name must never become a key either:
                // every other blank-named archive would then match it. Its main:, if any, still
                // counts (#516).
                return new ArchiveIdentity(EntryState.UNDETERMINED, null, declaredMain);
            }
            return new ArchiveIdentity(EntryState.NOT_THIS_MODULES, declared, declaredMain);
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

    /** A refusal with a localisable reply and an English log message. */
    @ApiStatus.Internal
    public static class UninstallRefusedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final transient MessageKey key;
        private final transient Arg[] args;

        UninstallRefusedException(String message, MessageKey key, Arg... args) {
            super(message);
            this.key = key;
            this.args = args.clone();
        }
    }

    /** Records the JARs a module will lose at the next start; never unloads it during this run. */
    @ApiStatus.Internal
    public static PendingResult requestUninstall(String name, CommandSender requester) {
        return requestUninstall(name, requester, DEFAULT_MODULE_CODE_SOURCE);
    }

    static PendingResult requestUninstall(String name, CommandSender requester,
                                          Function<UltiToolsPlugin, File> codeSource) {
        if (name == null || name.isBlank()) {
            return PendingResult.reply(UpmKeys.UNINSTALL_NOT_FOUND);
        }
        return ModuleFileTransactions.underLock(() -> {
            try {
                return requestUninstallLocked(name.trim(), requester, codeSource);
            } catch (UninstallRefusedException refusal) {
                return PendingResult.reply(refusal.key, refusal.args);
            } catch (SecurityException denied) {
                return PendingResult.reply(UpmKeys.UNINSTALL_NOT_RECORDED, string("module", name),
                        string("error", text(denied.getMessage())));
            }
        });
    }

    private static PendingResult requestUninstallLocked(String name, CommandSender sender,
                                                        Function<UltiToolsPlugin, File> codeSource) {
        UltiTools frame = UltiTools.getInstance();
        ModuleIdentity identity = resolveIdentity(name, frame.getPluginManager(), codeSource);
        refuseIfAnotherModuleSharesTheJar(identity);
        File folder = modulesFolder();
        File[] entries;
        try {
            entries = ModuleFileTransactions.sortedByName(folder.listFiles());
        } catch (SecurityException denied) {
            return PendingResult.reply(UpmKeys.UNINSTALL_FOLDER_UNREADABLE, string("folder", folder.getAbsolutePath()));
        }
        if (entries == null && probe(folder) != Presence.ABSENT) {
            return PendingResult.reply(UpmKeys.UNINSTALL_FOLDER_UNREADABLE, string("folder", folder.getAbsolutePath()));
        }
        var files = new ArrayList<File>();
        var undetermined = new ArrayList<String>();
        if (entries != null) {
            for (File entry : entries) {
                switch (classify(entry, identity)) {
                    case THIS_MODULES -> files.add(entry);
                    case UNDETERMINED -> undetermined.add(entry.getAbsolutePath());
                    default -> { /* Leave unrelated entries alone. */ }
                }
            }
        }
        refuseToTouchAnotherModulesJar(identity, files);
        identity.noteCandidates(files);
        UltiToolsPlugin running = identity.loaded.isEmpty() ? null : identity.loaded.getFirst();
        String key = running == null ? null : running.getIdentifyString();
        String module = running == null ? null : running.getPluginName();
        if (running == null && !files.isEmpty()) {
            var metadata = PluginYmlReader.readFromJarFile(files.getFirst());
            key = readIdentifyString(files.getFirst());
            module = metadata.getName();
        }
        if (identity.recorded.bystanderIds.contains(normalizeIdentifyString(key))) {
            key = null;
        }
        return transactions(frame).queueRemoval(module == null || module.isBlank() ? name : module, key,
                running == null ? null : running.getVersion(), identity.removedModule(), files, undetermined,
                sender, running != null);
    }

    private static String readIdentifyString(File file) {
        try (var jar = new java.util.jar.JarFile(file)) {
            var descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor != null) {
                try (var reader = new InputStreamReader(jar.getInputStream(descriptor), java.nio.charset.StandardCharsets.UTF_8)) {
                    return YamlConfiguration.loadConfiguration(reader).getString("identify-string");
                }
            }
        } catch (IOException | SecurityException unreadable) {
            LOGGER.log(Level.FINE, "Could not read the module identifier from " + file.getName(), unreadable);
        }
        return null;
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
        if (chosen.isEmpty()) {
            String key = normalizeIdentifyString(requested);
            chosen = others.stream().filter(module -> key.equals(normalizeIdentifyString(module.getIdentifyString()))).toList();
            refuseIfAmbiguous(requested, chosen);
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
            String matching = String.join(", ", names);
            throw new UninstallRefusedException("Refusing to uninstall " + requested
                    + ": it was declared by more than one module (" + matching + "); nothing was changed.",
                    UpmKeys.UNINSTALL_REFUSED_AMBIGUOUS, string("module", requested), string("modules", matching));
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
        private final boolean someCodeSourceUnknown;
        /** What the module loader and the loaded instances recorded about the targets (#516, round 10). */
        private final Recorded recorded;
        /**
         * The file names of the entries the folder scan identified as this module's, collected as
         * the directory scan identifies them for the pending removal.
         */
        private final List<String> removedJarNames = new ArrayList<>();

        @SuppressWarnings("PMD.ExcessiveParameterList") // one resolution's result, built in one place
        private ModuleIdentity(String requested, List<UltiToolsPlugin> loaded, Set<String> ownJars,
                               Set<String> keys, Recorded recorded, Set<String> bystanderJars,
                               Map<String, String> bystanderOf, boolean someCodeSourceUnknown) {
            this.requested = requested;
            this.recorded = recorded;
            this.loaded = loaded;
            this.ownJars = ownJars;
            this.keys = keys;
            this.bystanderJars = bystanderJars;
            this.bystanderOf = bystanderOf;
            this.someCodeSourceUnknown = someCodeSourceUnknown;
        }

        /**
         * Notes the entries the folder scan identified as this module's, before anything is deleted.
         *
         * @param jars the entries
         */
        private void noteCandidates(List<File> jars) {
            for (File jar : jars) {
                removedJarNames.add(jar.getName());
            }
        }

        /**
         * The uninstalled module in every form an update transaction can name it by, for the
         * cancellation (round-10 review): the unloaded instances' identify-strings and runtime
         * names, the name keys, the JARs they were loaded from, the JARs the start-up scan recorded
         * as declaring their main class, and the entries the folder scan identified. Everything
         * except the last is known before the folder is touched, so it holds whatever the folder
         * then does.
         *
         * @return the removed module
         */
        private ModuleFileTransactions.RemovedModule removedModule() {
            List<String> names = new ArrayList<>(keys);
            names.addAll(recorded.runtimeNames);
            List<String> jarNames = new ArrayList<>(recorded.jarNames);
            jarNames.addAll(removedJarNames);
            List<String> ids = new ArrayList<>(recorded.identifyStrings);
            if (!recorded.bystanderIds.contains(normalizeIdentifyString(requested))) {
                ids.add(requested);
            }
            return new ModuleFileTransactions.RemovedModule(ids, names, jarNames);
        }
    }

    /**
     * What the module loader and the loaded instances say about an uninstall's targets, read before
     * anything is unloaded.
     */
    private static final class Recorded {
        /**
         * The targets' main classes as the loader recorded them, less any another loaded module also
         * has: an entry whose {@code plugin.yml} declares one of them is a JAR the loader would load
         * this module from again (#516).
         */
        private final Set<String> mainClasses;
        /** The file names of the targets' code-source JARs and of every JAR recorded as declaring their main class. */
        private final Set<String> jarNames;
        /** The targets' identify-strings, normalised, less any another loaded module also reports. */
        private final List<String> identifyStrings;
        /** The targets' runtime names, less any another loaded module also answers to. */
        private final Set<String> runtimeNames;
        private final Set<String> bystanderIds;

        private Recorded(Set<String> mainClasses, Set<String> jarNames, List<String> identifyStrings,
                         Set<String> runtimeNames, Set<String> bystanderIds) {
            this.mainClasses = mainClasses;
            this.jarNames = jarNames;
            this.identifyStrings = identifyStrings;
            this.runtimeNames = runtimeNames;
            this.bystanderIds = bystanderIds;
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
        return new ModuleIdentity(requested, loaded, ownJars, keys, recordedOf(pluginManager, loaded, others, ownJars),
                bystanderJars, bystanderOf, unknown.get());
    }

    /**
     * What the loader recorded about the targets (#516) and what they report about themselves, read
     * before they are unloaded.
     *
     * @param pluginManager the manager whose start-up scan recorded them
     * @param targets       the loaded instances this uninstall targets
     * @param others        every other loaded instance
     * @param ownJars       the canonical paths of the targets' code-source JARs
     * @return the record
     */
    private static Recorded recordedOf(PluginManager pluginManager, List<UltiToolsPlugin> targets,
                                       List<UltiToolsPlugin> others, Set<String> ownJars) {
        Set<String> mainClasses = mainClassesOf(pluginManager, targets, others);
        Set<String> jarNames = new java.util.HashSet<>();
        String modulesFolderPath = canonicalPathOf(modulesFolder());
        for (String path : ownJars) {
            // Only a JAR directly in the modules folder can be an update's old JAR, and a cancellation
            // matches JARs by file name: an external code source's name could be an unrelated module
            // JAR's in the modules folder (Codex round 16).
            File jar = new File(path);
            if (jar.getParentFile() != null && modulesFolderPath.equals(jar.getParentFile().getPath())) {
                jarNames.add(jar.getName());
            }
        }
        ModuleJarIndex index = pluginManager.getModuleJarIndex();
        if (index != null) {
            for (String mainClass : mainClasses) {
                for (File jar : index.jarsDeclaring(mainClass)) {
                    jarNames.add(jar.getName());
                }
            }
        }
        // An identify-string or runtime name another loaded module also has is not this module's to
        // match on -- the exclusion the name keys and main classes get (gate 1, BL-02). Every form a
        // RemovedModule carries is excluded this way: keys and main classes where they are built,
        // identify-strings and runtime names here; JAR names need none, since a JAR another loaded
        // module came from is refused before anything is unloaded (17-44 gate 1, reviewer A).
        Set<String> identifyStrings = new java.util.LinkedHashSet<>();
        Set<String> runtimeNames = new java.util.LinkedHashSet<>();
        for (UltiToolsPlugin plugin : targets) {
            addNormalized(identifyStrings, plugin.getIdentifyString());
            runtimeNames.add(plugin.getPluginName());
        }
        Set<String> bystanderIds = new java.util.HashSet<>();
        for (UltiToolsPlugin plugin : others) {
            addNormalized(bystanderIds, plugin.getIdentifyString());
            runtimeNames.remove(plugin.getPluginName());
        }
        identifyStrings.removeAll(bystanderIds);
        return new Recorded(mainClasses, jarNames, new ArrayList<>(identifyStrings), runtimeNames, bystanderIds);
    }

    /** Adds {@code identifyString}, normalised, unless it is absent or blank. */
    private static void addNormalized(Set<String> into, String identifyString) {
        String key = normalizeIdentifyString(identifyString);
        if (key != null) {
            into.add(key);
        }
    }

    /**
     * The main classes the module loader recorded for the targets, less every one another loaded
     * module also has (#516) -- the same exclusion the name keys get (gate 1, BL-02).
     *
     * @param pluginManager the manager whose start-up scan recorded them
     * @param targets       the loaded instances this uninstall targets
     * @param others        every other loaded instance
     * @return the main classes an entry is matched on
     */
    private static Set<String> mainClassesOf(PluginManager pluginManager, List<UltiToolsPlugin> targets,
                                             List<UltiToolsPlugin> others) {
        ModuleJarIndex index = pluginManager.getModuleJarIndex();
        Set<String> mainClasses = new java.util.HashSet<>();
        if (index == null) {
            return mainClasses;
        }
        for (UltiToolsPlugin plugin : targets) {
            mainClasses.add(index.mainClassOf(plugin));
        }
        for (UltiToolsPlugin plugin : others) {
            mainClasses.remove(index.mainClassOf(plugin));
        }
        return mainClasses;
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

    private static void refuseIfAnotherModuleSharesTheJar(ModuleIdentity identity) {
        for (String jar : identity.ownJars) {
            if (identity.bystanderJars.contains(jar)) {
                throw new UninstallRefusedException("Refusing to uninstall " + identity.requested + ": "
                        + jar + " is also the JAR module " + identity.bystanderOf.get(jar)
                        + " is loaded from; nothing was changed.", UpmKeys.UNINSTALL_REFUSED_SHARED_JAR,
                        string("module", identity.requested), string("file", jar),
                        string("other", text(identity.bystanderOf.get(jar))));
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
                        + " is loaded from; nothing was changed.", UpmKeys.UNINSTALL_REFUSED_SHARED_JAR,
                        string("module", identity.requested), string("file", file.getAbsolutePath()),
                        string("other", text(identity.bystanderOf.get(path))));
            }
        }
    }

    /**
     * The modules folder install and uninstall act on: the one {@code PluginManager#init} scans and
     * the module class loader reads, from the one method that computes it (#517). Before 6.3.0 the
     * scan read {@code System.getProperty("user.dir")} + {@code /plugins/UltiTools/plugins} while
     * this read the data folder, and the two diverged on a server started from another directory.
     *
     * @return the modules folder
     */
    private static File modulesFolder() {
        return ModuleFileTransactions.modulesFolder(UltiTools.getInstance().getDataFolder());
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
            if (archive.declaredMain != null && identity.recorded.mainClasses.contains(archive.declaredMain)) {
                return byDeclaredMainClass(identity);
            }
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

    /**
     * An entry whose {@code plugin.yml} declares one of the targets' main classes: the module loader
     * loads exactly the class {@code main:} names (#548/#549), so after a restart this file loads the
     * module the operator removed (#516). The same caution as a name match applies: while some loaded
     * module's own JAR could not be determined, the entry may be that module's, and is reported.
     *
     * @param identity the resolved identity
     * @return the entry's state
     */
    private static EntryState byDeclaredMainClass(ModuleIdentity identity) {
        return identity.someCodeSourceUnknown ? EntryState.UNDETERMINED : EntryState.THIS_MODULES;
    }

    /** The absolute paths of {@code files}, in the order given. */
}
