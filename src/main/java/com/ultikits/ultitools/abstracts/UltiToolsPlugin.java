package com.ultikits.ultitools.abstracts;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.nio.file.StandardCopyOption;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.EnableAutoRegister;
import com.ultikits.ultitools.context.ConditionalRegistrationEvaluator;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.exceptions.PluginModuleException;
import com.ultikits.ultitools.interfaces.Configurable;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.IPlugin;
import com.ultikits.ultitools.interfaces.Localized;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;
import com.ultikits.ultitools.manager.CommandManager;
import com.ultikits.ultitools.manager.ConfigManager;
import com.ultikits.ultitools.manager.ListenerManager;
import com.ultikits.ultitools.manager.PluginManager;
import com.ultikits.ultitools.utils.DependencyUtils;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.ResourceHashSidecar;
import com.ultikits.ultitools.utils.VersionComparatorUtil;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract class representing a plugin module.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public abstract class UltiToolsPlugin implements IPlugin, Localized, Configurable {
    /**
     * Language file extensions, in the order they are tried.
     * <p>
     * {@code .json} stays first so a module shipping both keeps exactly the behaviour it had
     * before 6.3.0. {@code .yml} and {@code .yaml} were added for #389: eight modules ship YAML,
     * and the loader silently produced an empty dictionary for every one of them.
     */
    private static final String[] LANGUAGE_EXTENSIONS = {".json", ".yml", ".yaml"};

    /**
     * Matches a {@code java.util.Formatter} conversion specifier, e.g. {@code %s} in {@code
     * "Hello, %s!"}, or {@code %1$s} for an explicit argument index. Used only by {@link
     * #placeholderArity(String)} for the D-05 per-key comparison; not a change to how
     * placeholders are substituted anywhere -- every parameterised {@code i18n(...)} value in
     * this framework is formatted with {@code String.format}, never {@code MessageFormat}
     * (measured: 0 {@code {n}}-style placeholders anywhere in {@code src/main/resources/lang}
     * or across any {@code i18n(...)} call site; the shipped catalogues use {@code %s}/{@code
     * %d} exclusively).
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");

    /**
     * A private, independent JSON reader for the D-05 placeholder-arity comparison only -- not a
     * change to {@link Language}'s own Gson usage, which stays entirely inside {@code
     * Language.java} (untouched by this plan).
     */
    private static final Gson ARITY_GSON = new Gson();
    private static final Type ARITY_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private Language language;
    @Getter
    private final String version;
    @Getter
    private final String pluginName;
    @Getter
    private final List<String> authors;
    @Getter
    private final List<String> loadAfter;
    @Getter
    private final int minUltiToolsVersion;
    @Getter
    private final String mainClass;
    @Getter
    private final String identifyString;
    @Getter
    @Setter
    private String resourceFolderPath;
    @Getter
    private SimpleContainer context;
    private com.ultikits.ultitools.manager.DataScope dataScope;


    /**
     * Constructor for UltiToolsPlugin. For module development only.
     */
    protected UltiToolsPlugin() {
        YamlConfiguration pluginConfig = loadPluginConfiguration();

        if (!pluginConfig.contains("name")) {
            // D-16: a module with no `name:` key used to silently become "unknown" and share
            // sqliteDB/unknown.db with every other name-less module (measured on-disk: 10 tables
            // from 8 modules, one of them - world_settings - holding the same logical rows as a
            // properly-named module's own .db file). Fail fast at load instead, naming the JAR, so
            // the operator sees this at startup rather than discovering it as missing data later.
            throw new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                    "Module JAR '" + resolveJarFileNameForError() + "' has no 'name:' key in its "
                            + "plugin.yml. Refusing to load rather than silently sharing "
                            + "sqliteDB/unknown.db with other unnamed modules - add a 'name:' key.");
        }

        version = pluginConfig.getString("version", "unknown");
        pluginName = pluginConfig.getString("name");
        authors = pluginConfig.getStringList("authors");
        loadAfter = pluginConfig.getStringList("loadAfter");
        minUltiToolsVersion = pluginConfig.getInt("api-version", 0);
        mainClass = pluginConfig.getString("main", "unknown");
        identifyString = pluginConfig.getString("identify-string", null);

        resourceFolderPath = UltiTools.getInstance().getDataFolder().getAbsolutePath() + File.separator + "pluginConfig" + File.separator + this.getPluginName();
        language = initializeLanguage();
        saveResources();
        try{
            initConfig();
        } catch (IOException e) {
            getLogger().error(e);
        }
    }

    /**
     * Initializes the language object
     * @return Language object
     */
    private Language initializeLanguage() {
        return createLanguageFromPath(resourceFolderPath);
    }

    /**
     * Resolves which language code to actually load, consulting {@link Localized#supported()}
     * before {@link #createLanguageFromPath(String)} picks a file (D-20/D-21/WIRE-10). Prefers
     * the configured code; if it is absent from a non-empty {@code supported()}, prefers
     * {@code "en"} when {@code supported()} contains it, otherwise the first entry in
     * {@code supported()}'s iteration order. An empty {@code supported()} is "no information" -
     * the configured code is returned unchanged and nothing is logged.
     *
     * @return the language code to actually load
     */
    private String resolveLanguageCode() {
        String configured = getLanguageCode();
        List<String> supportedCodes = this.supported();
        if (supportedCodes == null || supportedCodes.isEmpty()) {
            return configured;
        }
        if (configured != null && supportedCodes.contains(configured)) {
            return configured;
        }
        String fallback = supportedCodes.contains("en") ? "en" : supportedCodes.get(0);
        getLogger().warn("Module '" + getPluginName() + "' is configured for language '" + configured
                + "' but only ships " + supportedCodes + " - falling back to '" + fallback + "'.");
        return fallback;
    }

    /**
     * Creates a Language object from the given resource folder path
     * @param folderPath the resource folder path
     * @return Language object
     */
    private Language createLanguageFromPath(String folderPath) {
        String resolvedCode = resolveLanguageCode();
        // Round 5 (own deep review of 0ddc95f, finding 1): the disk and jar catalogues are now
        // resolved independently of each other's extension. Resolving them together, one shared
        // extension per loop iteration, meant a module that shipped an old jar's lang/en.json
        // (extracted to disk) alongside a new jar's lang/en.yml never reached the .yml iteration
        // at all -- the .json iteration's non-null onDisk short-circuited the loop before the
        // jar's .yml catalogue, under a different extension, was ever looked at.
        Language onDisk = resolveDiskLanguage(folderPath, resolvedCode);
        Language inJar = resolveJarLanguage(resolvedCode);
        if (onDisk != null && inJar != null) {
            // Real-machine finding (phase 13, PR #418): on an upgraded server the module jar
            // adds a key that the copy of this language file already extracted to disk by an
            // older jar does not have. The disk file stays authoritative for every key it does
            // contain -- server owners customise it -- but a key it lacks now falls back to
            // the jar-bundled catalogue instead of rendering as its own raw key.
            return onDisk.withFallback(inJar);
        }
        if (onDisk != null) {
            return onDisk;
        }
        if (inJar != null) {
            return inJar;
        }
        // #389: this used to return an empty dictionary without a word. Language.get then falls
        // back to the key, so every message in the module rendered as its own raw key -- which is
        // what a player sees, and what nobody sees in the log. Eight of sixteen modules were in
        // this state for a whole release because they ship lang/*.yml and only .json was looked
        // for. Whatever the cause next time, it will say so.
        getLogger().warn("Module '" + getPluginName() + "' has no loadable language file for '"
                + resolvedCode + "'. Looked for lang/" + resolvedCode + " with extensions "
                + Arrays.toString(LANGUAGE_EXTENSIONS) + ", on disk under " + folderPath
                + " and inside the module jar. Every i18n(...) call in this module will render its "
                + "own key until one is added.");
        return new Language("{}");
    }

    /**
     * Resolves the on-disk language catalogue for {@code code}, trying each of {@link
     * #LANGUAGE_EXTENSIONS} in order and returning the first that exists -- independently of
     * whichever extension {@link #resolveJarLanguage(String)} resolves for the same code
     * (13-REVIEW round 5, own deep review of {@code 0ddc95f}, finding 1).
     *
     * @return the resolved on-disk language, or {@code null} if none of the extensions exist on disk
     */
    private Language resolveDiskLanguage(String folderPath, String code) {
        for (String extension : LANGUAGE_EXTENSIONS) {
            Language onDisk = loadLanguageFromDisk(folderPath, code, extension);
            if (onDisk != null) {
                return onDisk;
            }
        }
        return null;
    }

    /**
     * Resolves the jar-bundled language catalogue for {@code code}, trying each of {@link
     * #LANGUAGE_EXTENSIONS} in order and returning the first that exists -- independently of
     * whichever extension {@link #resolveDiskLanguage(String, String)} resolves for the same code
     * (13-REVIEW round 5, own deep review of {@code 0ddc95f}, finding 1).
     *
     * @return the resolved jar-bundled language, or {@code null} if none of the extensions exist
     *         in the jar
     */
    private Language resolveJarLanguage(String code) {
        for (String extension : LANGUAGE_EXTENSIONS) {
            Language inJar = loadLanguageFromJar(code, extension);
            if (inJar != null) {
                return inJar;
            }
        }
        return null;
    }

    /**
     * Reads {@code <folderPath>/lang/<code><extension>} if it exists, else {@code null}.
     * <p>
     * D-05/D-06/D-07 (#441): before returning, applies the recorded-provenance decision -- an
     * upgraded server whose language file was never touched since extraction gets it silently
     * replaced by the current jar's copy; one an operator customised is left alone, with a
     * per-key warning for any key whose placeholder count moved out from under it. Layered in
     * front of the resolution split, not inside {@link Language}: {@link
     * #resolveLanguageWithProvenance} decides which bytes/dictionary this method returns, and
     * {@link Language#withFallback} (unchanged) still owns filling a key this dictionary lacks
     * entirely.
     */
    private Language loadLanguageFromDisk(String folderPath, String code, String extension) {
        File file = new File(folderPath + File.separator + "lang" + File.separator + code + extension);
        if (!file.exists()) {
            return null;
        }
        String resourcePath = "lang/" + code + extension;
        return resolveLanguageWithProvenance(folderPath, file, resourcePath, extension);
    }

    /**
     * Applies the D-05/D-06 recorded-provenance decision to a single on-disk language file. Four
     * branches, mutually exclusive:
     * <ol>
     *   <li>Recorded hash present and equal to the disk file's current hash -- never touched by
     *       the operator since extraction: overwrite the disk file with the current jar's bytes,
     *       re-record the new (jar) hash as the baseline, and log one informative line.</li>
     *   <li>Recorded hash present and different -- operator customisation: leave the disk file
     *       alone; apply the per-key placeholder-arity override for any key that moved.</li>
     *   <li>No recorded hash, but the disk bytes already equal the jar's -- provably unmodified,
     *       unknown provenance only because an older jar (pre-#441) extracted it: record the hash
     *       as the new baseline and enter the normal mechanism, with no overwrite this pass
     *       (D-06). Never adopt an unequal disk hash as a baseline -- that would silently
     *       overwrite a real customisation on the next jar change.</li>
     *   <li>No recorded hash and the disk bytes differ from the jar's -- unknown provenance,
     *       assume customisation: never record, never overwrite; the per-key placeholder-arity
     *       override still applies.</li>
     * </ol>
     * A jar entry absent for this exact {@code resourcePath} (D-05's stated exception) short-
     * circuits before any of the four branches: the disk file is left alone and nothing is
     * recorded, since there is nothing to compare against.
     *
     * @param folderPath   the module's on-disk resource folder root
     * @param file         the on-disk language file, already confirmed to exist
     * @param resourcePath the extracted resource's path relative to the resource folder (e.g.
     *                     {@code "lang/en.json"}), matching {@code saveResources()}'s own keys
     * @param extension    the language file extension ({@code ".json"}, {@code ".yml"} or {@code
     *                     ".yaml"})
     * @return the language that {@link #loadLanguageFromDisk} should treat as "the disk language"
     */
    private Language resolveLanguageWithProvenance(String folderPath, File file, String resourcePath,
                                                     String extension) {
        byte[] jarBytes = readEmbeddedResourceBytes(resourcePath);
        if (jarBytes == null) {
            // Jar entry absent for this exact resource path: nothing to compare against, so the
            // disk file is left alone and no record is written (D-05).
            return readLanguageFile(file, extension);
        }
        File resourceFolder = new File(folderPath);
        String diskHash;
        try {
            diskHash = ResourceHashSidecar.sha256(file);
        } catch (UncheckedIOException e) {
            // Codex round 1, P1: an unreadable disk file (or one accidentally replaced by a
            // directory) must degrade to a best-effort read, exactly like the pre-#441 code
            // path -- it must never abort this module's whole language resolution, let alone
            // its startup. readLanguageFile()/Language(File) already catch a read failure on
            // their own and return an empty dictionary, which Language.withFallback (in the
            // caller) then backstops from the jar side.
            getLogger().error("Could not hash on-disk language file " + file.getPath()
                    + " for module '" + getPluginName() + "'; leaving it untouched.", e);
            try {
                return readLanguageFile(file, extension);
            } catch (RuntimeException readFailure) {
                // The same unreadable path (e.g. a directory where a file is expected) can also
                // defeat the best-effort fallback read in a way Language's own IOException-only
                // catch does not cover -- this method must still never propagate, so fall back
                // one more step to an empty dictionary. Language.withFallback (in the caller)
                // then resolves every key from the jar side instead.
                getLogger().error("Also failed to read on-disk language file " + file.getPath()
                        + " as a best-effort fallback for module '" + getPluginName() + "'.", readFailure);
                return new Language("{}");
            }
        }
        String jarHash = ResourceHashSidecar.sha256(jarBytes);
        Optional<String> recorded = ResourceHashSidecar.readRecordedHash(resourceFolder, resourcePath);

        if (recorded.isPresent()) {
            if (recorded.get().equals(diskHash)) {
                // Branch 1: never touched since extraction -> overwrite from the jar. Codex
                // round 1, P2: skip entirely when the bundled content has not actually
                // changed since it was last synced -- the common case on every restart after
                // the first successful sync, not an edge case. Rewriting identical bytes and
                // logging "has been updated" every single time is misleading, touches the
                // file's mtime for no reason, and fails needlessly on an installation that
                // hardens module resources read-only after provisioning.
                if (jarHash.equals(diskHash)) {
                    return readLanguageFile(file, extension);
                }
                // Only record the new baseline and log success once the write is CONFIRMED to
                // have landed -- re-hash the bytes actually on disk afterward (mirroring
                // saveResources()'s own pattern) rather than trusting the precomputed jarHash,
                // so a partial/failed write can never be misreported as success (WR-01).
                if (writeBytes(file, jarBytes)) {
                    String newDiskHash = ResourceHashSidecar.sha256(file);
                    ResourceHashSidecar.record(resourceFolder, resourcePath, newDiskHash);
                    // Codex round 2, P2: record(...) swallows its own IOException and returns
                    // void, so a caller cannot otherwise tell a sidecar write failure from
                    // success. Read the record back to confirm it actually persisted before
                    // claiming success -- logging "has been updated" when the file WAS
                    // refreshed but the sidecar was NOT would misrepresent provenance
                    // tracking as healthy. Deliberately not reverted on failure: a second
                    // write introduces its own atomicity risk for a genuinely rare failure;
                    // the next boot's hash mismatch safely falls into branch 2 (treated as
                    // customised) instead, which is this mechanism's own conservative default.
                    boolean recordPersisted = ResourceHashSidecar.readRecordedHash(resourceFolder, resourcePath)
                            .filter(newDiskHash::equals).isPresent();
                    if (recordPersisted) {
                        getLogger().info("Language file '" + resourcePath + "' for module '" + getPluginName()
                                + "' was not modified since it was extracted and has been updated to the "
                                + "current bundled version.");
                    } else {
                        getLogger().error("Refreshed language file '" + resourcePath + "' for module '"
                                + getPluginName() + "' but could not persist its provenance record; it may "
                                + "be treated as customised on the next start until this is resolved.");
                    }
                }
                return readLanguageFile(file, extension);
            }
            // Branch 2: operator customisation -> leave the disk file alone.
            return applyPlaceholderArityOverride(file, jarBytes, extension, resourcePath);
        }

        if (diskHash.equals(jarHash)) {
            // Branch 3: unknown provenance, but provably unmodified -> record the baseline now;
            // no overwrite this pass (D-06).
            ResourceHashSidecar.record(resourceFolder, resourcePath, diskHash);
            return readLanguageFile(file, extension);
        }
        // Branch 4: unknown provenance and the bytes differ -> assume customisation, never record.
        return applyPlaceholderArityOverride(file, jarBytes, extension, resourcePath);
    }

    /**
     * Reads an on-disk language file exactly as the pre-#441 {@code loadLanguageFromDisk} did --
     * extracted unchanged so both provenance branches that keep the disk file's own parse
     * (branches 1 and 3 in {@link #resolveLanguageWithProvenance}) share the same reading logic
     * the jar-absent short-circuit also uses.
     */
    private Language readLanguageFile(File file, String extension) {
        if (".json".equals(extension)) {
            return new Language(file);
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return Language.fromYaml(reader);
        } catch (IOException e) {
            getLogger().error("Failed to read language file " + file.getPath(), e);
            return new Language("{}");
        }
    }

    /**
     * Builds the disk language for the "operator customisation" branches (2 and 4 in {@link
     * #resolveLanguageWithProvenance}): the disk dictionary stays authoritative for every key,
     * except a key whose {@link #placeholderArity(String)} differs from the jar's value for the
     * same key -- that key is overridden to the jar's value and warned about once, naming the
     * module, the file and the key but never either value (T-16-04-02). A key present only in the
     * jar is deliberately left out of the returned dictionary: {@link Language#withFallback}
     * (unchanged) already resolves a key the disk dictionary lacks entirely, and duplicating that
     * here would just be a second, redundant path to the same answer.
     */
    private Language applyPlaceholderArityOverride(File file, byte[] jarBytes, String extension,
                                                     String resourcePath) {
        Map<String, String> diskDictionary = readFlatDictionary(file, extension);
        Map<String, String> jarDictionary = readFlatDictionary(jarBytes, extension);
        Map<String, String> resolved = new LinkedHashMap<>(diskDictionary);
        for (Map.Entry<String, String> jarEntry : jarDictionary.entrySet()) {
            String key = jarEntry.getKey();
            String diskValue = diskDictionary.get(key);
            if (diskValue == null) {
                // Missing from disk entirely: Language.withFallback already covers this key.
                continue;
            }
            if (placeholderArity(diskValue) != placeholderArity(jarEntry.getValue())) {
                resolved.put(key, jarEntry.getValue());
                getLogger().warn("Language key '" + key + "' in '" + resourcePath + "' for module '"
                        + getPluginName() + "' has a different placeholder count than the current "
                        + "bundled version; using the current version's value for this key.");
            }
        }
        return new Language(resolved);
    }

    /**
     * Returns the highest {@code String.format} argument POSITION {@code value} requires --
     * not the count of distinct positions used (Codex round 2, P2). {@code "%2$s" alone}
     * requires an args array of length (at least) 2, since {@code String.format} demands every
     * position up to the highest one referenced be present, even if a lower position (here,
     * 1) is never itself rendered -- so its arity is 2, not 1. Counting DISTINCT positions
     * (a one-element set for both {@code "%2$s"} alone and {@code "%s"} alone) would wrongly
     * call those two equal.
     * <p>
     * A value repeating one EXPLICIT index twice (e.g. {@code "%1$s and %1$s again"}) still
     * has arity one, since both conversions consume the SAME argument and neither raises the
     * highest-position watermark past 1; a naive occurrence count would call it two and warn
     * on a rewording that changed nothing about the message's parameter shape. An unindexed
     * conversion (this framework's own catalogues use only this form) consumes the NEXT
     * sequential position, so two unindexed {@code %s} conversions in one value require arity
     * two. {@code %%} (a literal percent) and {@code %n} (a line separator) consume no
     * argument and are excluded.
     *
     * @param value a language value, or {@code null}
     * @return the highest argument position {@code value}'s {@code String.format} conversions
     *         require, or {@code 0} for {@code null} or a value with none
     */
    private static int placeholderArity(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        int highestPosition = 0;
        int nextImplicitPosition = 1;
        while (matcher.find()) {
            char conversion = matcher.group(2).charAt(0);
            if (conversion == '%' || conversion == 'n' || conversion == 'N') {
                continue;
            }
            String explicitIndex = matcher.group(1);
            if (explicitIndex != null) {
                highestPosition = Math.max(highestPosition, Integer.parseInt(explicitIndex));
            } else {
                highestPosition = Math.max(highestPosition, nextImplicitPosition);
                nextImplicitPosition++;
            }
        }
        return highestPosition;
    }

    /**
     * Reads a flat {@code key -> value} dictionary from a language file on disk, for the
     * placeholder-arity comparison only -- {@link Language} itself exposes no way to enumerate its
     * keys, so this is a small, independent read of the same file formats, not a change to {@link
     * Language}'s own parsing. Degrades to an empty map on any read/parse failure (an empty file
     * is "no keys", never an error, per D-05).
     */
    private static Map<String, String> readFlatDictionary(File file, String extension) {
        if (file == null || !file.isFile()) {
            return Collections.emptyMap();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return readFlatDictionary(reader, extension);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Reads a flat {@code key -> value} dictionary from jar-bundled bytes, mirroring the file
     * overload above for the jar side of the comparison.
     */
    private static Map<String, String> readFlatDictionary(byte[] bytes, String extension) {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return readFlatDictionary(reader, extension);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> readFlatDictionary(Reader reader, String extension) {
        try {
            if (".json".equals(extension)) {
                Map<String, String> parsed = ARITY_GSON.fromJson(reader, ARITY_MAP_TYPE);
                return parsed != null ? parsed : Collections.emptyMap();
            }
            // Mirrors Language.fromYaml's own flattening -- duplicated here (not called) because
            // Language exposes no way to get its dictionary back out, and this plan does not touch
            // Language.java at all.
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
            Map<String, String> flattened = new LinkedHashMap<>();
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    flattened.put(key, yaml.getString(key));
                }
            }
            return flattened;
        } catch (JsonSyntaxException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Writes {@code bytes} to {@code file}, returning whether the write actually succeeded
     * (WR-01) -- the caller must not record a new provenance baseline or log a success line for
     * a write that threw partway through.
     * <p>
     * Codex round 1, P2: writes to a temporary file in {@code file}'s OWN parent directory
     * first, then atomically replaces {@code file} only once the full write has succeeded --
     * a direct {@code Files.write(file.toPath(), bytes)} truncates the destination immediately
     * on open, so a write failure partway through (disk full, etc.) could otherwise leave a
     * truncated or partial file in place of the original bytes. The temp file is created in
     * the same directory specifically so the final move can be a same-filesystem atomic
     * rename, not a copy.
     */
    private boolean writeBytes(File file, byte[] bytes) {
        File parentDir = file.getParentFile();
        File tempFile = null;
        try {
            tempFile = File.createTempFile(file.getName(), ".tmp", parentDir);
            Files.write(tempFile.toPath(), bytes);
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            getLogger().error("Failed to write language file " + file.getPath(), e);
            return false;
        } finally {
            if (tempFile != null) {
                // A successful move already renamed the temp file away from tempFile's own
                // path, so this is a no-op on the success path and only cleans up a leftover
                // staging file on any failure branch above.
                // noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    /**
     * Reads the raw bytes of {@code resourcePath} (e.g. {@code "lang/en.json"}) from this module's
     * own {@link CodeSource} location, or {@code null} if it is not present there. Mirrors {@link
     * #loadLanguageFromJar(String, String)}'s directory/jar dual branch exactly (13-REVIEW CR-01,
     * issue #412 follow-up) but returns raw bytes instead of a parsed {@link Language}, so the
     * D-05/D-06 provenance decision can hash and, in the overwrite branch, write those exact bytes
     * to disk.
     */
    private byte[] readEmbeddedResourceBytes(String resourcePath) {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        if (src == null || src.getLocation() == null) {
            return null;
        }
        File location = resolveCodeSourceFile(src.getLocation());
        if (location.isDirectory()) {
            File resource = new File(location, resourcePath.replace('/', File.separatorChar));
            if (!resource.isFile()) {
                return null;
            }
            try {
                return Files.readAllBytes(resource.toPath());
            } catch (IOException e) {
                getLogger().error(e, "Failed to read embedded resource " + resource + " from " + location);
                return null;
            }
        }
        try (JarFile jarFile = new JarFile(location)) {
            JarEntry entry = jarFile.getJarEntry(resourcePath);
            if (entry == null) {
                return null;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int len;
                while ((len = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, len);
                }
                return buffer.toByteArray();
            }
        } catch (IOException e) {
            getLogger().error(e, "Failed to read embedded resource " + resourcePath + " from " + location);
            return null;
        }
    }

    /**
     * Reads {@code lang/<code><extension>} from the module's own {@link CodeSource} location if
     * present, else {@code null}. Mirrors {@link Localized#scanLangResources(URL)}'s directory/jar
     * branch (13-REVIEW CR-01, issue #412 follow-up): an exploded classpath (dev workspace, IDE
     * launch, or a module test that instantiates a real {@code UltiToolsPlugin} subclass) has a
     * directory as its {@code CodeSource}, and {@link Localized#supported()} already scans that
     * directory directly -- this method must not disagree by unconditionally trying (and failing)
     * to open the directory as a {@code JarFile} first.
     * <p>
     * The resource path is built with {@code '/'} for the jar-entry lookup and {@link
     * File#separator} for the on-disk lookup: jar entry names always use a forward slash, so the
     * separator form would silently find nothing on a Windows host, and the reverse holds for a
     * real file path.
     */
    private Language loadLanguageFromJar(String code, String extension) {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        if (src == null || src.getLocation() == null) {
            return null;
        }
        File location = resolveCodeSourceFile(src.getLocation());
        if (location.isDirectory()) {
            // Exploded classpath (dev workspace, IDE launch, test) -- Localized.scanLangResources()
            // already treats this shape as first-class; loadLanguageFromJar must not disagree.
            File resource = new File(location, "lang" + File.separator + code + extension);
            if (!resource.isFile()) {
                return null;
            }
            try (BufferedReader reader = Files.newBufferedReader(resource.toPath(), StandardCharsets.UTF_8)) {
                return parseLanguageResource(reader, extension);
            } catch (IOException e) {
                getLogger().error(e, "Failed to read language resource " + resource + " from " + location);
                return new Language("{}");
            }
        }
        String entryName = "lang/" + code + extension;
        try (JarFile jarFile = new JarFile(location)) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                return parseLanguageResource(reader, extension);
            }
        } catch (IOException e) {
            getLogger().error(e, "Failed to read language resource " + entryName + " from " + location);
            return new Language("{}");
        }
    }

    /**
     * Resolves a {@link CodeSource} location as a {@link File}, decoding any percent-escaped
     * characters (spaces, non-ASCII, etc.) that {@link URL#getPath()} does not decode on its
     * own (13-REVIEW WR-03) -- passing an undecoded {@code %20...} path straight to {@link
     * File#File(String)} or {@link JarFile#JarFile(File)} finds nothing when the module is
     * installed under a path containing a space or other URI-escaped character. Falls back to
     * the previous raw-path substring logic -- matching {@link #getInputStream()}'s own
     * try/catch({@link URISyntaxException}) shape for the same {@link CodeSource} location --
     * for the rare case the location cannot be expressed as a {@link URI} at all.
     *
     * @param location the {@code CodeSource.getLocation()} URL, never {@code null}
     * @return the resolved location as a {@link File}
     */
    private static File resolveCodeSourceFile(URL location) {
        try {
            return new File(location.toURI());
        } catch (URISyntaxException e) {
            String rawPath = location.getPath();
            return new File(rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
        }
    }

    /**
     * Parses a {@code lang/*} resource already opened as a {@link BufferedReader} -- shared by
     * both the on-disk (exploded directory) and in-jar branches of {@link
     * #loadLanguageFromJar(String, String)} so the two stay in sync.
     */
    private static Language parseLanguageResource(BufferedReader reader, String extension) throws IOException {
        if (".json".equals(extension)) {
            return new Language(reader.lines().collect(Collectors.joining("")));
        }
        // Joining with "" is fine for JSON and destroys YAML, whose structure is the line
        // breaks -- so YAML is handed the reader rather than a flattened string.
        return Language.fromYaml(reader);
    }

    /**
     * Loads the plugin configuration from plugin.yml
     * @return YamlConfiguration object with default values if loading fails
     */
    private YamlConfiguration loadPluginConfiguration() {
        try (InputStream inputStream = getInputStream()) {
            if (inputStream == null) {
                getLogger().error("Cannot find plugin.yml in the plugin jar");
                return new YamlConfiguration();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            getLogger().error("Failed to load plugin configuration", e);
            return new YamlConfiguration();
        }
    }

    /**
     * Constructor for UltiToolsPlugin. For plugin connector.
     *
     * @param pluginName          the name of the plugin
     * @param version             the version of the plugin
     * @param authors             the authors of the plugin
     * @param loadAfter           the plugins which should be loaded before this plugin
     * @param minUltiToolsVersion the minimum version of UltiTools required by this plugin
     * @param mainClass           the main class of the plugin
     * @param resourceFolderPath  the path to the resource folder
     */
    public UltiToolsPlugin(String pluginName, String version, List<String> authors, List<String> loadAfter, int minUltiToolsVersion, String mainClass, String resourceFolderPath) {
        this.pluginName = pluginName;
        this.version = version;
        this.authors = authors;
        this.loadAfter = loadAfter;
        this.minUltiToolsVersion = minUltiToolsVersion;
        this.mainClass = mainClass;
        this.identifyString = null; // Connector plugins don't have identify-string
        this.resourceFolderPath = resourceFolderPath;
        language = createLanguageFromPath(resourceFolderPath);
        saveResources();
        try {
            initConfig();
        } catch (IOException e) {
            // GATE-05 group two (08-21): routed to the typed plugin-module hierarchy -- this
            // constructor failing to complete means the whole connector plugin failed to load.
            throw PluginModuleException.loadFailed(pluginName, e);
        }
    }

    /**
     * Injects the IoC container created for this plugin.
     * <p>
     * Called by {@code PluginManager} while a module is being loaded, before
     * {@code registerSelf()} runs. It is not part of the module-facing API — a
     * module that calls it replaces the container the framework already wired up,
     * losing every bean that was injected into it.
     * <p>
     * Deliberately still public: {@code PluginManager} lives in another package, so
     * this cannot be narrowed to package-private, and deleting it outright would
     * remove a public method, which the compatibility policy forbids in a PATCH
     * release. The annotation is a signal to humans and IDEs; it enforces nothing
     * at runtime.
     *
     * @param context the container created for this plugin
     */
    @ApiStatus.Internal
    public void setContext(SimpleContainer context) {
        this.context = context;
    }

    /**
     * Injects the {@link com.ultikits.ultitools.manager.DataScope} credential {@code
     * PluginManager} minted for this module (D-17). Called by {@code PluginManager} right after
     * minting, before {@code wireAop} runs -- the same lifecycle point {@link #setContext}
     * documents.
     * <p>
     * Deliberately still public, for the same reason {@link #setContext} is: {@code
     * PluginManager} lives in another package and cannot be narrowed to package-private, and the
     * compatibility policy forbids removing a public method in a PATCH release. The annotation is
     * a signal to humans and IDEs; it enforces nothing at runtime.
     *
     * @param scope the scope minted for this plugin
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public void setDataScope(com.ultikits.ultitools.manager.DataScope scope) {
        this.dataScope = scope;
    }

    /**
     * @return the config manager
     */
    public static ConfigManager getConfigManager() {
        return UltiTools.getInstance().getConfigManager();
    }

    /**
     * @return the listener manager
     */
    public static ListenerManager getListenerManager() {
        return UltiTools.getInstance().getListenerManager();
    }

    /**
     * @return the command manager
     */
    public static CommandManager getCommandManager() {
        return UltiTools.getInstance().getCommandManager();
    }

    /**
     * @return the plugin manager
     */
    public static PluginManager getPluginManager() {
        return UltiTools.getInstance().getPluginManager();
    }

    /**
     * Initializes the configuration entity.
     */
    private void initConfig() throws IOException {
        EnableAutoRegister annotation = MergedAnnotationResolver.find(this.getClass(), EnableAutoRegister.class);
        if (annotation != null && annotation.config()) {
            for (String packageName : DependencyUtils.getPluginPackages(this)) {
                UltiTools.getInstance().getConfigManager().registerAll(
                        this, packageName, UltiTools.getJavaPluginClassLoader()
                );
            }
            // D-06: diff getAllConfigs() against what package-scan auto-registration actually
            // registered. Runs only on this branch - on the config = false branch below,
            // getAllConfigs() is the sole registration path and there is nothing to diff against.
            diffGetAllConfigsOverride();
            return;
        }
        List<AbstractConfigEntity> allConfigs = this.getAllConfigs();
        for (AbstractConfigEntity configEntity : allConfigs) {
            UltiToolsPlugin.getConfigManager().register(this, configEntity);
        }
    }

    /**
     * Diffs a {@link #getAllConfigs()} override against what auto-registration already
     * registered for this module (D-06 / SILENT-18 / #336), called once right after
     * package-scan auto-registration finishes.
     * <p>
     * An empty override (the interface default - the module never wrote {@link #getAllConfigs()})
     * has nothing to compare and nothing to log. A non-empty override whose every {@code
     * configFilePath} was already registered by the package scan is pure redundancy, logged at
     * {@link Level#FINE} only. A non-empty override naming a {@code configFilePath} the scan
     * never registered is real capability loss - #336's warning-only ask cannot tell these two
     * cases apart, so this refuses the module and names every missing entity instead of guessing.
     *
     * @throws ConfigurationException if the override names a {@code configFilePath} auto-registration
     *                                 never registered
     */
    private void diffGetAllConfigsOverride() {
        List<AbstractConfigEntity> override = this.getAllConfigs();
        if (override.isEmpty()) {
            return;
        }
        Set<String> overridePaths = new LinkedHashSet<>();
        for (AbstractConfigEntity entity : override) {
            overridePaths.add(entity.getConfigFilePath());
        }

        Map<String, AbstractConfigEntity> registered = UltiToolsPlugin.getConfigManager().getAllConfigEntities(this);
        Set<String> registeredPaths = registered != null ? registered.keySet() : Collections.<String>emptySet();

        List<String> missing = new ArrayList<>();
        for (String path : overridePaths) {
            if (!registeredPaths.contains(path)) {
                missing.add(path);
            }
        }

        if (missing.isEmpty()) {
            getLogger().debug("getAllConfigs() override registers " + overridePaths.size()
                    + " entit" + (overridePaths.size() == 1 ? "y" : "ies")
                    + " already found by package-scan auto-registration - the override adds nothing.");
            return;
        }

        List<String> violations = new ArrayList<>();
        for (String path : missing) {
            violations.add("getAllConfigs() registers '" + path
                    + "' but package-scan auto-registration never found it - the entity is lost");
        }
        throw ConfigurationException.validationFailed(getPluginName(), "getAllConfigs() override", violations);
    }

    private InputStream getInputStream() throws IOException {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        String path = jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1);
        try {
            URL url = new java.net.URI("jar:file:" + path + "!/plugin.yml").toURL();
            JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
            return jarConnection.getInputStream();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid URL format", e);
        }
    }

    /**
     * Best-effort resolution of this module's own JAR file name, for the {@code name:}-missing
     * refusal message only. Never throws -- falls back to the class name if the code source is
     * unavailable (e.g. when running from unpacked classes in a test).
     *
     * @return the JAR file name, or this class's name if it cannot be determined
     */
    private String resolveJarFileNameForError() {
        try {
            CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                String path = src.getLocation().getPath();
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Exception ignored) {
            // Best effort only - fall through to the class-name fallback below.
        }
        return this.getClass().getName();
    }

    protected final String getConfigFolder() {
        return this.resourceFolderPath;
    }

    protected final File getConfigFile(String path) {
        return new File(getConfigFolder() + File.separator + path);
    }

    public <T extends AbstractConfigEntity> T getConfig(Class<T> configType) {
        return getConfigManager().getConfigEntity(this, configType);
    }

    public <T extends AbstractConfigEntity> T getConfig(String path, Class<T> configType) {
        return getConfigManager().getConfigEntity(this, path, configType);
    }

    public <T extends AbstractConfigEntity> List<T> getConfigs(Class<T> configType) {
        return getConfigManager().getConfigEntities(this, configType);
    }

    public <T extends AbstractConfigEntity> void saveConfig(String path, Class<T> configType) throws IOException {
        getConfigManager().getConfigEntity(this, path, configType).save();
    }

    /**
     * Extracts this module's embedded {@code res}/{@code lang}/{@code config} jar resources into
     * {@link #resourceFolderPath}. Guarantees canonical-path validation before every write: each
     * extracted entry's resolved destination is checked against the resource folder's own
     * canonical path, and any entry whose path would resolve outside it (a Zip Slip attempt) is
     * skipped with a warning rather than written.
     * <p>
     * Every file this method actually extracts gets its provenance recorded via {@link
     * ResourceHashSidecar#record(File, String, String)} -- across all three prefixes, D-07 -- so a
     * later boot can tell "the operator edited this" from "an old jar extracted this and nobody has
     * touched it since" (#441, D-05/D-06). A file this method skips (already present on disk) gets
     * no record here: the skip already means the file predates this mechanism, or was already
     * decided on by {@link #loadLanguageFromDisk} on a previous boot.
     */
    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        try (JarFile jarFile = new JarFile(
                jar.getPath().startsWith("/") ? jar.getPath() : jar.getPath().substring(1)
        )) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String fileName = jarEntry.getName();
                if ((!fileName.startsWith("res") && !fileName.startsWith("lang")
                        && !fileName.startsWith("config")) || !fileName.contains(".")) {
                    continue;
                }
                try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                    if (inputStream == null) {
                        throw new IllegalArgumentException("The embedded resource '" + fileName + "' cannot be found in " + fileName);
                    }
                    File outFile = new File(resourceFolderPath, fileName);
                    // Zip Slip protection: ensure extracted file stays within resource folder
                    String canonicalDest = outFile.getCanonicalPath();
                    String canonicalBase = new File(resourceFolderPath).getCanonicalPath() + File.separator;
                    if (!canonicalDest.startsWith(canonicalBase)) {
                        getLogger().warn("Skipping jar entry with path traversal: " + fileName);
                        continue;
                    }
                    try {
                        if (outFile.exists()) {
                            continue;
                        }
                        FileUtils.touch(outFile);
                        try (OutputStream out = Files.newOutputStream(outFile.toPath())) {
                            byte[] buf = new byte[1024];
                            int len;
                            while ((len = inputStream.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                        }
                        ResourceHashSidecar.record(new File(resourceFolderPath), fileName,
                                ResourceHashSidecar.sha256(outFile));
                    } catch (IOException ex) {
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().error("Failed to save resources from jar", e);
        }
    }

    /**
     * Gets data operator. Refuses outright if {@code dataClazz} is not registered to this module
     * (D-14) -- checked against the same {@link com.ultikits.ultitools.manager.DataScope} minted
     * for this module at load, via the same refusal {@code DataStore.getOperator(DataScope,
     * Class)} builds, so the exception type, error code, and message shape are identical
     * regardless of which entry point a caller reaches.
     * <p>
     * <strong>02-13 (CR-03):</strong> before this, {@code dataScope.owns(...)} was checked here
     * inline and this method then delegated to the deprecated {@code getOperator(UltiToolsPlugin,
     * Class)} overload directly -- so {@code DataStore.getOperator(DataScope, Class)}, the method
     * D-17/02-07 built specifically as the credential-typed supported path, had zero real callers
     * anywhere in the framework. This now routes through it, so production actually uses the path
     * it was built for. {@code dataScope} is normally non-null by the time any module calls this
     * (set by {@code PluginManager} right after minting, before {@code registerSelf()} runs); the
     * {@code null} fallback below only covers a bare instance constructed outside the normal
     * {@code PluginManager} load flow (e.g. a test), where the deprecated overload's own {@code
     * checkOwnership(...)} call still refuses correctly on its own.
     *
     * @param dataClazz the class of the data entity
     * @param <T>       the type of the data entity
     * @return the data operator
     * @throws com.ultikits.ultitools.exceptions.DataAccessException if {@code dataClazz} is not
     *         registered to this module
     */
    public final <T extends BaseDataEntity<String>> DataOperator<T> getDataOperator(Class<T> dataClazz) {
        if (dataScope != null) {
            return UltiTools.getInstance().getDataStore().getOperator(dataScope, dataClazz);
        }
        return UltiTools.getInstance().getDataStore().getOperator(this, dataClazz);
    }

    /**
     * @return language code
     */
    public final String getLanguageCode() {
        return UltiTools.getInstance().getConfig().getString("language");
    }

    /**
     * @return the language
     */
    public final Language getLanguage() {
        return language;
    }

    /**
     * @param str the string to be localized
     * @return the localized string
     */
    public String i18n(String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    @Override
    public final String i18n(String code, String str) {
        return this.getLanguage().getLocalizedText(str);
    }

    /**
     * @param plugin the plugin to be checked
     * @return whether the plugin is newer than the given plugin
     */
    public boolean isNewerVersionThan(UltiToolsPlugin plugin) {
        if (plugin == null || plugin.getVersion() == null || this.getVersion() == null) {
            return false;
        }
        return VersionComparatorUtil.compare(this.getVersion(), plugin.getVersion()) > 0;
    }

    @Override
    public void unregisterSelf() {
        getCommandManager().unregisterAll(this);
        getListenerManager().unregisterAll(this);
    }

    /**
     * Reload this plugin's configuration and language files.
     * <p>
     * Also reports (but does not act on) any {@code @ConditionalOnConfig} drift: the condition
     * is evaluated once, at component-scan time during startup, so a reload can only log that a
     * watched key has changed direction since then -- it never registers, unregisters, or
     * rebuilds anything (issue #392, D-01). A module overriding {@code reloadSelf()} without
     * calling {@code super.reloadSelf()} will not get this report; that is pre-existing
     * behaviour for the two statements above too, stated here so it is not a surprise.
     */
    @Override
    public void reloadSelf() {
        getConfigManager().reloadConfigs(this);
        // Reinitialize language in case language setting changed
        language = createLanguageFromPath(resourceFolderPath);
        // @ConditionalOnConfig is evaluated once at component-scan time; a reload can only
        // report drift on a watched key, never re-register or rebuild anything (#392, D-01).
        ConditionalRegistrationEvaluator.reportDrift(this);
    }

    /**
     * @return plugin logger
     */
    public PluginLogger getLogger() {
        return new PluginLogger(this.pluginName, UltiTools.getInstance().getLogger());
    }
}
