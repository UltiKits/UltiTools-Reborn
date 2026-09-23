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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
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
import com.ultikits.ultitools.utils.PosixAttributePreserver;
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

    /** Named logger for the D-03 per-module reload line -- see {@link #RELOAD_LOG_MESSAGE_KEY}. */
    private static final Logger LOGGER = Logger.getLogger(UltiToolsPlugin.class.getName());

    /**
     * Framework i18n key (this class's own {@code lang/en.json}/{@code lang/zh.json} catalogue,
     * not a module's) for the per-module reload line {@link #reloadSelf()} logs after its three
     * steps (D-03). Package-private so {@code UltiToolsPluginLifecycleHookTest} can assert both
     * shipped catalogues actually carry a translation for it, rather than duplicating the
     * literal string between production and test code.
     */
    static final String RELOAD_LOG_MESSAGE_KEY = "Module '%s' reloaded.";
    /**
     * Matches a {@code java.util.Formatter} conversion specifier, e.g. {@code %s} in {@code
     * "Hello, %s!"}, or {@code %1$s} for an explicit argument index. Used only by {@link
     * #placeholderArity(String)}, one of the two placeholder-loss detectors {@link
     * #applyPlaceholderArityOverride} combines (see that method's own javadoc) for the D-05
     * per-key comparison; not a change to how placeholders are substituted anywhere -- every
     * parameterised {@code i18n(...)} value in THIS FRAMEWORK's own two catalogues ({@code
     * src/main/resources/lang/en.json}/{@code zh.json}) is formatted with {@code String.format},
     * never {@code MessageFormat} (measured: 0 {@code {n}}-style placeholders in either file, and
     * 0 across any {@code i18n(...)} call site in this repository).
     * <p>
     * That measurement is scoped to THIS repository's own two catalogues -- it says nothing
     * about the module catalogues this same mechanism actually protects. Corrected 2026-09-22:
     * an earlier revision of this javadoc reported the same 0-count without that scoping
     * qualifier, which read as "no {@code {n}}-style placeholder exists anywhere this mechanism
     * looks" and was wrong for the broader population the mechanism serves. An install-wide sweep
     * of all 15 module jars' {@code lang/*.json}/{@code lang/*.yml} catalogues against their
     * on-disk copies (34 file pairs) found the opposite: 0 {@code %s}/{@code %d}-arity mismatches
     * anywhere, and every brace-delimited span in that corpus is a {@code {0}}/{@code {1}}-style
     * positional or {@code {PLAYER}}/{@code {WORLD}}-style named token -- the modules use {@code
     * String#replace}-driven substitution exclusively, never {@code String.format}. {@link
     * #BRACE_TOKEN_PATTERN} and {@link #missingBracePlaceholder} exist to cover that population;
     * this pattern and {@link #placeholderArity} continue to cover only the framework's own.
     * <p>
     * Codex round 6, P2: deliberately narrower than the full {@code Formatter} grammar --
     * {@code %s}/{@code %d} (optionally explicit-indexed) or a literal {@code %%}, with NO flags,
     * width or precision component. The earlier, more permissive pattern accepted a space as a
     * flag character, so ordinary text like {@code "Progress: 90% done"} matched {@code "% d"} as
     * a (bogus) {@code %d} conversion with a space flag -- an operator's customised value
     * containing an innocent {@code '%'} could be reported as an arity mismatch against
     * differently-worded bundled text and silently overwritten at runtime, even though the key
     * carries no real {@code String.format} placeholder at all. Since this framework never emits
     * anything outside {@code %s}/{@code %d}/{@code %%} (per the measurement above), restricting
     * the pattern to exactly those forms eliminates the false-positive class rather than patching
     * the specific "% d" instance -- no genuine placeholder this framework uses stops matching.
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("%(?:(\\d+)\\$)?([sd%])");

    /**
     * Matches a brace-style placeholder token as used by the MODULE {@code lang} catalogues this
     * mechanism protects -- {@code {0}}/{@code {1}} positional or {@code {PLAYER}}/{@code
     * {WORLD}} named, substituted with plain {@code String#replace}, never {@code
     * java.util.Formatter}. See {@link #PLACEHOLDER_PATTERN}'s javadoc for the install-wide
     * measurement establishing this is the modules' actual dialect, and #524 for the real-machine
     * report this pattern and {@link #missingBracePlaceholder} exist to fix.
     * <p>
     * The captured group requires the braces to contain ONLY a bare decimal integer or a bare
     * identifier (a letter/underscore followed by letters, digits or underscores) -- no
     * whitespace or other punctuation. This is deliberately strict, for the same reason {@link
     * #PLACEHOLDER_PATTERN} was narrowed in Codex round 6: an operator's own decorative or
     * explanatory text that happens to contain a brace must never be mistaken for a placeholder
     * token, and this pattern alone cannot make that guarantee -- {@link #missingBracePlaceholder}'s
     * one-directional comparison is what actually protects a decorative brace the disk value adds
     * on its own (see that method's javadoc). Measured against the install-wide sweep above: every
     * brace-delimited span across all 34 module file pairs matches this pattern with nothing left
     * over, so this shape is not hand-picked -- it is what the corpus actually contains.
     */
    private static final Pattern BRACE_TOKEN_PATTERN =
            Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*|\\d+)\\}");

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
     * <p>
     * 16-05 / CodeQL {@code java/zipslip} alert #11 (CWE-22): {@code code} is only lightly
     * restricted by {@link com.ultikits.ultitools.interfaces.Localized#languageCodeOf(String)}'s
     * character allowlist when it comes from a scanned jar/directory entry -- and not restricted
     * at all when it comes from a hostile {@code config.yml language:} value, since {@link
     * #resolveLanguageCode()} returns an unmatched configured code UNCHANGED whenever {@link
     * com.ultikits.ultitools.interfaces.Localized#supported()} is empty. This is therefore the
     * last line of defence: before this file is read (or, deeper in {@link
     * #resolveLanguageWithProvenance}, overwritten), its canonical path must stay inside {@code
     * <folderPath>/lang}'s own canonical path -- mirroring the guard {@link #saveResources()}
     * already applies to extracted jar entries. A violation degrades to the same {@code null}
     * (“not loadable”) outcome as a missing file, never a thrown exception.
     */
    private Language loadLanguageFromDisk(String folderPath, String code, String extension) {
        File langDir = new File(folderPath, "lang");
        File file = new File(langDir, code + extension);
        if (!isWithinDirectory(langDir, file)) {
            getLogger().warn("Module '" + getPluginName() + "' resolved a language code that would "
                    + "escape its lang/ directory ('" + langDir + "'); refusing to load or write '"
                    + file + "'.");
            return null;
        }
        if (!file.exists()) {
            return null;
        }
        String resourcePath = "lang/" + code + extension;
        return resolveLanguageWithProvenance(folderPath, file, resourcePath, extension);
    }

    /**
     * Verifies that {@code candidate}'s canonical path is contained within {@code baseDir}'s own
     * canonical path -- the same Zip Slip guard {@link #saveResources()} already applies to
     * extracted jar entries, generalized here for every other place an untrusted language code is
     * turned into a {@link File} (16-05 / CodeQL {@code java/zipslip} alert #11, CWE-22). Neither
     * {@code baseDir} nor {@code candidate} needs to exist: {@link File#getCanonicalPath()} is
     * defined for a non-existent path too, normalizing {@code ..} segments lexically wherever the
     * real filesystem does not need to be consulted (the same property {@code saveResources()}
     * already relies on for a file it is about to create).
     * <p>
     * Returns {@code false} (never throws) on any I/O failure resolving either canonical path,
     * since an uncanonicalizable path is not a basis for trusting containment either.
     *
     * @param baseDir   the directory {@code candidate} must resolve inside
     * @param candidate the file built from an untrusted language code
     * @return whether {@code candidate}'s canonical path is {@code baseDir}'s canonical path or a
     *         descendant of it
     */
    private static boolean isWithinDirectory(File baseDir, File candidate) {
        try {
            String canonicalBase = baseDir.getCanonicalPath() + File.separator;
            String canonicalCandidate = candidate.getCanonicalPath() + File.separator;
            return canonicalCandidate.startsWith(canonicalBase);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Applies the D-05/D-06 recorded-provenance decision to a single on-disk language file. Four
     * branches, mutually exclusive:
     * <ol>
     *   <li>Recorded hash present and equal to the disk file's current hash -- never touched by
     *       the operator since extraction: overwrite the disk file with the current jar's bytes,
     *       re-record the new (jar) hash as the baseline, and log one informative line.</li>
     *   <li>Recorded hash present and different from the disk file's current hash: this by
     *       ITSELF is NOT evidence of operator customisation -- see the branch-2 code comment
     *       below (Codex finding, thread {@code PRRT_kwDOIcF9Es6i2kao}, P2) for why, and for the
     *       content-comparison this branch now performs before concluding either way.</li>
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
     * <p>
     * Every branch above that concludes "customisation" derives that conclusion from an ACTUAL
     * disk-bytes-vs-jar-bytes comparison, never from the record alone -- branches 3 and 4 already
     * did (their own classification IS a content comparison); branch 2 was corrected to do the
     * same (see its own code comment). The record is provenance BOOKKEEPING, not the source of
     * truth for whether a file has been customised; it can go stale (most deterministically, since
     * the previous round, when the sidecar itself is symlinked or read-only and its own write is
     * refused) without that staleness ever being able to cause a PERMANENT misclassification.
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
        // Branch-audit (Codex finding thread PRRT_kwDOIcF9Es6i2kao, P2): neither short-circuit
        // below reaches a "customised" conclusion at all, so neither needed the branch-2-style
        // content-comparison fix. jarBytes == null has literally nothing to compare the disk
        // bytes against (there IS no bundled version of this resource for this module); the
        // UncheckedIOException fallback below never classifies provenance either -- it degrades
        // to a best-effort raw read (or an empty dictionary) precisely because it could not even
        // compute diskHash, so no comparison of any kind is possible here.
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
                // Branch 1: never touched since extraction -> overwrite from the jar. Extracted
                // into its own method (PMD NPathComplexity: this branch's own nested
                // try/writeBytes/catch shape was the dominant contributor to this method's
                // complexity once branch 2 below gained its own content-comparison check) --
                // see resolveNeverTouchedSinceExtraction's own javadoc for the full behaviour.
                return resolveNeverTouchedSinceExtraction(file, jarBytes, jarHash, diskHash,
                        resourceFolder, resourcePath, extension);
            }
            // Branch 2: recorded hash present and mismatched against the disk file's CURRENT
            // hash. Codex finding (thread PRRT_kwDOIcF9Es6i2kao, P2, on
            // UltiToolsPlugin.java:515): a mismatch against the RECORD ALONE is not evidence of
            // operator customisation -- it is also exactly what a STALE record looks like, which
            // branch 1's own re-record step (above) can leave behind whenever the language file's
            // own write succeeds but the follow-up ResourceHashSidecar.record(...) call does not
            // persist. Before this fix that state was permanent: every later boot re-entered this
            // branch and reclassified an up-to-date file as "customised" forever, silently
            // stopping the module from ever receiving another bundled update, with nothing in the
            // log to say why -- the defect class this milestone exists to remove (declared
            // provenance-driven refresh, not delivered). The previous round's own symlink/
            // read-only refusal on the SIDECAR's write path (PosixAttributePreserver#replaceInPlace)
            // makes the stale-record precondition deterministic rather than rare: a symlinked or
            // read-only sidecar now reliably refuses every re-record attempt, so this branch is
            // reached on every subsequent boot.
            //
            // Root-cause fix, not a special case for "the sidecar write failed": derive the
            // conclusion from what is ACTUALLY on disk, not from the record. If diskHash already
            // equals jarHash, the file IS up to date regardless of what the stale record says --
            // opportunistically re-record the correct hash (best-effort; record() already
            // degrades silently on its own write failure per its own contract, and that failure
            // must not change this classification or fail the boot) and proceed on the up-to-date
            // path, exactly like branch 1's own jarHash.equals(diskHash) short-circuit above. Only
            // fall through to "operator customisation" -- unchanged from before -- when the disk
            // bytes are ACTUALLY different from the bundle.
            if (diskHash.equals(jarHash)) {
                ResourceHashSidecar.record(resourceFolder, resourcePath, diskHash);
                return readLanguageFile(file, extension);
            }
            return applyPlaceholderArityOverride(file, jarBytes, extension, resourcePath);
        }

        // Branch-audit (Codex finding thread PRRT_kwDOIcF9Es6i2kao, P2): branches 3 and 4 below
        // already derived their conclusion from an actual diskHash-vs-jarHash comparison before
        // this finding was reported -- this `if` IS that comparison -- so neither needed the
        // branch-2-style fix. Branch 3 concludes "not customised" (record now, proceed) only
        // because diskHash.equals(jarHash) was just confirmed true; branch 4 concludes
        // "customised" only because that same comparison was false. A record() failure inside
        // branch 3 (e.g. a symlinked sidecar) cannot cause a permanent misclassification either:
        // recorded stays Optional.empty() (nothing was ever persisted), so EVERY later boot
        // re-enters this same "no recorded hash" path and re-derives fresh from content again --
        // self-healing by construction, not merely by coincidence.
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
     * Branch 1 of {@link #resolveLanguageWithProvenance}: the recorded hash equals the disk
     * file's current hash -- the operator has never touched this file since it was extracted,
     * so it is safe to overwrite from the current jar. Extracted into its own method (PMD
     * {@code NPathComplexity}: once branch 2 in the caller gained its own content-comparison
     * check for the Codex finding on {@code UltiToolsPlugin.java:515}, thread {@code
     * PRRT_kwDOIcF9Es6i2kao}, this branch's own nested {@code writeBytes}/try/catch shape pushed
     * the combined method over Codacy's threshold; splitting it out is a structural change only,
     * the behaviour below is unchanged from before that finding).
     * <p>
     * Codex round 1, P2: skip the write entirely when the bundled content has not actually
     * changed since it was last synced -- the common case on every restart after the first
     * successful sync, not an edge case. Rewriting identical bytes and logging "has been
     * updated" every single time is misleading, touches the file's mtime for no reason, and
     * fails needlessly on an installation that hardens module resources read-only after
     * provisioning.
     * <p>
     * Only records the new baseline and logs success once the write is CONFIRMED to have
     * landed -- re-hashes the bytes actually on disk afterward (mirroring {@code
     * saveResources()}'s own pattern) rather than trusting the precomputed {@code jarHash}, so a
     * partial/failed write can never be misreported as success (WR-01). Codex round 2, P2:
     * {@code record(...)} swallows its own {@link IOException} and returns {@code void}, so a
     * caller cannot otherwise tell a sidecar write failure from success -- the record is read
     * back to confirm it actually persisted before claiming success in the log; logging "has
     * been updated" when the file WAS refreshed but the sidecar was NOT would misrepresent
     * provenance tracking as healthy. Deliberately not reverted on failure: a second write
     * introduces its own atomicity risk for a genuinely rare failure, and the next boot's hash
     * mismatch safely falls into branch 2's own content-comparison fix instead, rather than a
     * second write attempt here.
     * <p>
     * Codex round on {@code UltiToolsPlugin.java:498}, P2: this used to be an unconditional
     * {@code readLanguageFile(...)}, skipping the per-key placeholder-arity guard on EXACTLY the
     * paths where a stale value is most likely -- {@code writeBytes(file, jarBytes)} returning
     * {@code false} (read-only file, symlink, unpreservable ownership, or an I/O failure
     * mid-write) leaves the OLD disk bytes in place while {@code jarBytes} has already moved on,
     * so a key whose bundled arity shrank (two placeholders to one) can still carry the old,
     * wider disk value. A stale disk value with MORE placeholders than the call site now
     * supplies throws {@link java.util.MissingFormatArgumentException} at format time -- extra
     * bundled arguments over a narrower stale value are silently ignored by {@link
     * String#format}, not an error. Applying the override UNCONDITIONALLY -- not only on the
     * {@code writeBytes}-failed path -- means the write's own outcome stops mattering for this
     * guarantee: when the write succeeded, disk and jar bytes are now identical, so every key's
     * arity trivially matches and this is a no-op past the extra parse; when it failed, this is
     * precisely the protection this method exists to provide.
     *
     * @param file           the on-disk language file, already confirmed to exist
     * @param jarBytes       the current bundled bytes for this resource, already confirmed
     *                       non-null by the caller
     * @param jarHash        {@code jarBytes}'s own SHA-256 digest, already computed by the caller
     * @param diskHash       {@code file}'s current SHA-256 digest, already computed by the
     *                       caller -- equal to the recorded provenance hash, which is what
     *                       routed the caller into this method in the first place
     * @param resourceFolder the module's resource folder root (the sidecar's own location)
     * @param resourcePath   the extracted resource's path relative to the resource folder
     * @param extension      the language file extension ({@code ".json"}, {@code ".yml"} or
     *                       {@code ".yaml"})
     * @return the resolved language for this file
     */
    private Language resolveNeverTouchedSinceExtraction(File file, byte[] jarBytes, String jarHash,
            String diskHash, File resourceFolder, String resourcePath, String extension) {
        if (jarHash.equals(diskHash)) {
            return readLanguageFile(file, extension);
        }
        if (writeBytes(file, jarBytes)) {
            try {
                String newDiskHash = ResourceHashSidecar.sha256(file);
                ResourceHashSidecar.record(resourceFolder, resourcePath, newDiskHash);
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
            } catch (UncheckedIOException e) {
                // Codex round 6, P1: the same gap fixed in saveResources() -- the file this
                // branch just wrote could not be reopened for hashing immediately afterward
                // (e.g. a write-only default ACL, or a transient filesystem error), and
                // sha256(File)'s UncheckedIOException is not an IOException a plain IOException
                // catch would see. Degrade rather than let it abort module construction: the
                // file itself WAS refreshed and is still used below via readLanguageFile -- only
                // its provenance record is skipped, so the next boot's hash comparison falls
                // back to "unknown provenance" (branches 3/4) instead of crashing this one.
                getLogger().error("Could not hash refreshed language file '" + resourcePath
                        + "' for module '" + getPluginName() + "' immediately after writing it; "
                        + "its provenance record was not updated.", e);
            }
        }
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
     * except a key the jar's value proves has LOST a placeholder relative to the disk value --
     * that key is overridden to the jar's value and warned about once, naming the module, the
     * file and the key but never either value (T-16-04-02). A key present only in the jar is
     * deliberately left out of the returned dictionary: {@link Language#withFallback} (unchanged)
     * already resolves a key the disk dictionary lacks entirely, and duplicating that here would
     * just be a second, redundant path to the same answer.
     * <p>
     * Two independent detectors decide "lost a placeholder", evaluated SEPARATELY rather than
     * through one shared code path (#524):
     * <ul>
     * <li>{@link #placeholderArity} for {@code %s}/{@code %d} -- SYMMETRIC equality of the
     * highest required {@code String.format} argument position. Either side moving, in either
     * direction, is a mismatch: both sides use the same dialect, so any count change at all means
     * the parameter shape changed.</li>
     * <li>{@link #missingBracePlaceholder} for {@code {0}}/{@code {PLAYER}}-style tokens --
     * ONE-DIRECTIONAL presence, not a count. It fires only when a token the JAR value contains is
     * ABSENT from the disk value; a disk value that keeps every bundled token but adds a token of
     * its own, or rewords everything around them, never fires.</li>
     * </ul>
     * The two cannot share one code path: collapsing them into a single "count differs" rule would
     * make the brace detector symmetric too, and a symmetric brace rule fires on an operator's own
     * customisation the moment its OWN brace count doesn't happen to match the jar's -- exactly the
     * false-positive class {@link #PLACEHOLDER_PATTERN}'s own javadoc already documents Codex
     * round 6 eliminating for the {@code %} dialect (a permissive pattern reading ordinary text as
     * a placeholder). One-directional token presence is the brace dialect's equivalent fix: it
     * flags a slot that disappeared under the operator and nothing else -- never an operator who
     * added something of their own.
     * <p>
     * What an operator loses in the one case this DOES fire: nothing structural -- their file is
     * never touched, never rewritten. That one message renders with the bundled wording (not
     * their customised wording) until they notice the warning and re-add the missing token
     * themselves.
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
            String jarValue = jarEntry.getValue();
            boolean arityMismatch;
            try {
                arityMismatch = placeholderArity(diskValue) != placeholderArity(jarValue);
            } catch (NumberFormatException e) {
                // Codex round 3, P2: an explicit format-argument index too large for int (e.g.
                // "%999999999999999999$s") overflows Integer.parseInt inside placeholderArity.
                // Before this fix, that exception propagated out of THIS method, out of
                // resolveLanguageWithProvenance, and aborted the whole module's construction --
                // over a single malformed, possibly never-formatted translation value, whereas
                // before D-05/D-06 existed only the affected key would have failed at format
                // time. Treat a malformed index the same conservative way a genuine arity
                // mismatch is already handled below: fall back to the bundled version's value
                // for this key, and say why (never either value) in the warning.
                resolved.put(key, jarValue);
                getLogger().warn("Language key '" + key + "' in '" + resourcePath + "' for module '"
                        + getPluginName() + "' has a malformed format-argument index and could "
                        + "not be compared; using the current bundled version's value for this key.");
                continue;
            }
            // #524: the %s/%d check above never sees this module population's actual dialect --
            // see PLACEHOLDER_PATTERN's javadoc for the install-wide measurement. Only evaluated
            // when the symmetric %-check above found no mismatch: either detector firing is
            // sufficient reason to prefer the bundled value, so there is nothing more to learn by
            // also running the brace check once arityMismatch is already true.
            boolean braceTokenLost = !arityMismatch && missingBracePlaceholder(diskValue, jarValue);
            if (arityMismatch || braceTokenLost) {
                resolved.put(key, jarValue);
                getLogger().warn("Language key '" + key + "' in '" + resourcePath + "' for module '"
                        + getPluginName() + "' " + (arityMismatch
                                ? "has a different placeholder count than the current bundled version"
                                : "is missing a placeholder that the current bundled version has")
                        + "; using the current bundled version's value for this key.");
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
     * two. {@code %%} (a literal percent) consumes no argument and is excluded -- {@link
     * #PLACEHOLDER_PATTERN} itself (Codex round 6, P2) no longer matches anything outside
     * {@code %s}/{@code %d}/{@code %%} at all, so {@code %n} and every other {@code Formatter}
     * conversion this framework never emits cannot reach this method to begin with.
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
            if (conversion == '%') {
                continue;
            }
            String explicitIndex = matcher.group(1);
            if (explicitIndex != null) {
                int position = Integer.parseInt(explicitIndex);
                if (position == 0) {
                    // Codex round 11, P2: java.util.Formatter argument indexes are 1-based --
                    // String.format throws IllegalFormatArgumentIndexException for index 0, so
                    // "%0$s" is exactly as malformed as an oversized index (round 3), not "no
                    // placeholder at all". Math.max(highestPosition, 0) would otherwise never
                    // raise highestPosition above 0, making this value compare EQUAL in arity to
                    // one with no placeholder whatsoever -- the malformed value would then
                    // survive completely unflagged. Reuse the same NumberFormatException signal
                    // applyPlaceholderArityOverride's catch clause already treats as malformed.
                    throw new NumberFormatException("Formatter argument index must be >= 1, was 0");
                }
                highestPosition = Math.max(highestPosition, position);
            } else {
                highestPosition = Math.max(highestPosition, nextImplicitPosition);
                nextImplicitPosition++;
            }
        }
        return highestPosition;
    }

    /**
     * Reports whether {@code jarValue} contains a brace-style placeholder token (see {@link
     * #BRACE_TOKEN_PATTERN}) that {@code diskValue} does not -- ONE-DIRECTIONALLY, and by TOKEN
     * PRESENCE, not by count (#524). See {@link #applyPlaceholderArityOverride}'s own javadoc for
     * why this is deliberately asymmetric, unlike {@link #placeholderArity}'s symmetric check: an
     * operator's value that keeps every bundled token -- however reworded, recoloured, or however
     * many of its OWN extra tokens it adds -- must never be flagged. Only a token that existed in
     * the bundled value and is genuinely gone from the operator's value counts as a loss.
     * <p>
     * A jar value with no brace tokens at all can never report a loss (there is nothing to lose),
     * independent of whatever the disk value happens to contain -- this is what keeps a module's
     * {@code %s}/{@code %d}-only keys, and any decorative brace an operator adds on their own,
     * outside this check entirely.
     *
     * @param diskValue the operator's on-disk value for this key
     * @param jarValue  the current bundled value for the same key
     * @return {@code true} if {@code jarValue} has at least one brace token absent from {@code
     *         diskValue}
     */
    private static boolean missingBracePlaceholder(String diskValue, String jarValue) {
        Set<String> jarTokens = bracePlaceholderTokens(jarValue);
        if (jarTokens.isEmpty()) {
            return false;
        }
        return !bracePlaceholderTokens(diskValue).containsAll(jarTokens);
    }

    /**
     * Returns the distinct brace-style placeholder tokens (see {@link #BRACE_TOKEN_PATTERN}) found
     * in {@code value} -- e.g. {@code {"0", "PLAYER"}} for {@code "Hi {PLAYER}, you are #{0}"}.
     * Iteration order is insertion order and otherwise unused: the only caller ({@link
     * #missingBracePlaceholder}) only ever calls {@code containsAll} on the result.
     *
     * @param value a language value, or {@code null}
     * @return the distinct token names found, or an empty set for {@code null} or a value with none
     */
    private static Set<String> bracePlaceholderTokens(String value) {
        if (value == null) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = BRACE_TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
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
        } catch (JsonParseException e) {
            // Codex round 7, P2: JsonParseException is the common superclass of
            // JsonSyntaxException (malformed JSON) AND JsonIOException -- Gson wraps an
            // IOException it hits reading from `reader` mid-parse (e.g. a transient filesystem
            // error on the second read of a customised catalogue) in the latter, which a catch
            // (JsonSyntaxException) alone does not see, letting it escape
            // applyPlaceholderArityOverride and abort this whole module's construction, contrary
            // to this method's own documented empty-map degradation (see its own javadoc).
            return Collections.emptyMap();
        }
    }

    /**
     * Writes {@code bytes} to {@code file}, returning whether the write actually succeeded
     * (WR-01) -- the caller must not record a new provenance baseline or log a success line for
     * a write that threw partway through.
     * <p>
     * Round following {@code PosixAttributePreserver.java:104} (symlink finding on {@code
     * ResourceHashSidecar.java:320}, thread {@code PRRT_kwDOIcF9Es6i2I49}, P2): this method's
     * entire operation -- the operator-pinned-read-only refusal, the symlink refusal, the
     * temp-file-then-atomic-move mechanism, and the POSIX attribute preservation -- now lives in
     * {@link PosixAttributePreserver#replaceInPlace}, shared with {@code
     * ResourceHashSidecar#writeAll}'s identical contract, so the two write paths cannot disagree
     * on any of these properties again the way they disagreed on attribute preservation (round 3),
     * an unreadable-attributes edge case (round 6), and symlink handling (this round) one at a
     * time. This method is now a thin {@link PosixAttributePreserver.ReplaceInPlaceListener}
     * supplying this class's own {@link #getLogger()}/{@code getPluginName()} warning text; the
     * full behavioural contract (what each refusal means, why each is conservative rather than
     * best-effort) is documented on {@link PosixAttributePreserver#replaceInPlace} and {@link
     * PosixAttributePreserver.ReplaceInPlaceListener} themselves, not duplicated here.
     * <p>
     * The symlink refusal's exact semantics, unchanged from Codex round 11, P2: a symlinked
     * language file (an operator-managed shared-translations layout, e.g. {@code lang/en.json}
     * pointing at a shared store) is left completely untouched -- REFUSE, not write-through to
     * the link's target. See {@link PosixAttributePreserver.ReplaceInPlaceListener#onSymbolicLink()}.
     */
    private boolean writeBytes(File file, byte[] bytes) {
        return PosixAttributePreserver.replaceInPlace(file, bytes, new PosixAttributePreserver.ReplaceInPlaceListener() {
            @Override
            public void onOperatorPinnedReadOnly() {
                getLogger().warn("Language file '" + file.getPath() + "' for module '" + getPluginName()
                        + "' is not writable; treating it as operator-pinned and leaving it untouched "
                        + "instead of refreshing it from the bundled version.");
            }

            @Override
            public void onSymbolicLink() {
                getLogger().warn("Language file '" + file.getPath() + "' for module '" + getPluginName()
                        + "' is a symbolic link; treating it as operator-pinned and leaving it "
                        + "untouched instead of replacing the link with a regular file.");
            }

            @Override
            public void onSourceAttributesUnreadable() {
                getLogger().warn("Could not read the current permissions and owner/group of "
                        + "language file '" + file.getPath() + "' for module '" + getPluginName()
                        + "'; treating its identity as unreplicable and skipping the refresh instead "
                        + "of silently replacing it with a process-owned copy.");
            }

            @Override
            public void onPermissionCopyFailure() {
                getLogger().warn("Could not preserve file permissions while refreshing '" + file.getPath()
                        + "' for module '" + getPluginName() + "'; the refreshed file may not match the "
                        + "original's permissions.");
            }

            @Override
            public void onOwnershipCopyFailure(String ownerName, String groupName) {
                getLogger().warn("Language file '" + file.getPath() + "' for module '"
                        + getPluginName() + "' is owned by '" + ownerName + ":" + groupName
                        + "', which this process cannot replicate onto the refreshed file; "
                        + "skipping the refresh instead of silently changing the file's ownership.");
            }

            @Override
            public void onWriteFailure(IOException cause) {
                getLogger().error("Failed to write language file " + file.getPath(), cause);
            }
        });
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
            return readEmbeddedResourceBytesFromDirectory(location, resourcePath);
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
     * The exploded-classpath (directory {@code CodeSource}) branch of {@link
     * #readEmbeddedResourceBytes(String)}, split out to keep that method's NPath complexity under
     * the Codacy/PMD threshold once the 16-05 / CodeQL {@code java/zipslip} alert #11 containment
     * guard was added -- purely a structural split, the guard's own behaviour is unchanged.
     *
     * @param location     the directory {@code CodeSource} location
     * @param resourcePath the resource path to read, e.g. {@code "lang/en.json"}
     * @return the resource's raw bytes, or {@code null} if absent, escaping, or unreadable
     */
    private byte[] readEmbeddedResourceBytesFromDirectory(File location, String resourcePath) {
        File resource = new File(location, resourcePath.replace('/', File.separatorChar));
        // 16-05 / CodeQL java/zipslip alert #11: resourcePath is built by the caller as
        // "lang/" + code + extension from the same untrusted code loadLanguageFromDisk guards --
        // mirror that guard here so this directory-CodeSource branch cannot be used to read
        // outside the module's own resource folder either.
        if (!isWithinDirectory(location, resource)) {
            getLogger().warn("Module '" + getPluginName() + "' resolved an embedded resource "
                    + "path that would escape its resource folder ('" + location + "'); "
                    + "refusing to read '" + resource + "'.");
            return null;
        }
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
            File langDir = new File(location, "lang");
            File resource = new File(langDir, code + extension);
            // 16-05 / CodeQL java/zipslip alert #11: same guard as loadLanguageFromDisk -- code
            // reaches this branch either lightly restricted (via Localized.languageCodeOf's
            // allowlist) or not restricted at all (a hostile config.yml language: value), so the
            // file boundary is still enforced here rather than trusted from upstream.
            if (!isWithinDirectory(langDir, resource)) {
                getLogger().warn("Module '" + getPluginName() + "' resolved a language code that "
                        + "would escape its lang/ directory ('" + langDir + "'); refusing to load '"
                        + resource + "'.");
                return null;
            }
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
            // CR-01 (#358 Part 1 gate-1 finding): registered as one plugin-scoped batch across
            // every scan package, not once per package with an independent rollback each -
            // otherwise a later package's refusal would leave an earlier package's already-
            // registered entries stranded. See ConfigManager#registerAll(UltiToolsPlugin,
            // String[], ClassLoader)'s own javadoc for the full reasoning.
            UltiTools.getInstance().getConfigManager().registerAll(
                    this, DependencyUtils.getPluginPackages(this), UltiTools.getJavaPluginClassLoader()
            );
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
     * Every file this method actually extracts gets its provenance recorded, in one batch via
     * {@link ResourceHashSidecar#recordAll(File, Map)} after the whole extraction pass completes
     * (Codex round 4, P2) -- across all three prefixes, D-07 -- so a later boot can tell "the
     * operator edited this" from "an old jar extracted this and nobody has touched it since"
     * (#441, D-05/D-06). A file this method skips (already present on disk) gets no record here:
     * the skip already means the file predates this mechanism, or was already decided on by
     * {@link #loadLanguageFromDisk} on a previous boot.
     */
    private void saveResources() {
        CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
        URL jar = src.getLocation();
        // Codex round 4, P2: accumulated across the whole pass and persisted ONCE via
        // ResourceHashSidecar.recordAll after the loop, instead of one record(...) call (one
        // read-modify-write cycle of the WHOLE sidecar) per extracted file -- see recordAll's own
        // javadoc for why that was quadratic for a module bundling many resources.
        //
        // Codex round 10, P2: recordAll is now called from a `finally` block (below), not as the
        // last statement inside the try -- if a LATER entry throws before the while loop reaches
        // it (e.g. File#getCanonicalPath() failing for a malformed name), the exception used to
        // propagate straight past this call to the outer catch, silently discarding every hash
        // already accumulated for entries that extracted successfully BEFORE the failure. The
        // files those entries wrote remain on disk regardless of how the loop ends, so their
        // provenance must be persisted regardless too -- recordAll is itself a no-op for an empty
        // map, so this costs nothing on the ordinary all-succeeded path.
        Map<String, String> hashesToRecord = new LinkedHashMap<>();
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
                        hashesToRecord.put(fileName, ResourceHashSidecar.sha256(outFile));
                    } catch (IOException | UncheckedIOException ex) {
                        // Codex round 5, P1: ResourceHashSidecar.sha256(File) wraps a read
                        // failure in UncheckedIOException (a RuntimeException), which an
                        // IOException-only catch here does NOT catch -- so a resource that was
                        // successfully extracted but could not be reopened for hashing (e.g. a
                        // write-only default ACL, or a filesystem error on the second open) let
                        // that exception escape saveResources() entirely and abort this whole
                        // module's construction, even though the file itself extracted fine.
                        UltiTools.getInstance().getLogger().log(Level.WARNING, "Could not save " + outFile.getName() + " to " + outFile);
                    }
                }
            }
        } catch (IOException e) {
            getLogger().error("Failed to save resources from jar", e);
        } finally {
            ResourceHashSidecar.recordAll(new File(resourceFolderPath), hashesToRecord);
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

    /**
     * Extension point for a module's own unload cleanup.
     * <p>
     * Called by {@link #unregisterSelf()} <em>before</em> the framework unregisters this
     * plugin's commands and listeners (D-02), so the module's own beans are still alive
     * while this runs. {@link #unregisterSelf()} is {@code final} and always calls this
     * hook and the framework's own unregistration afterward -- a module cannot skip either
     * by overriding {@link #unregisterSelf()} itself, because that is no longer possible
     * (D-01). The default body does nothing; override this method, not
     * {@link #unregisterSelf()}, to add cleanup work.
     * <p>
     * If this hook throws, {@link #unregisterSelf()} still unregisters this module's own
     * commands and listeners before the exception propagates (WR-01,
     * 16-REVIEW-lifecycle.md) -- a throwing hook is surfaced to the caller, not swallowed,
     * but it cannot skip the framework's own cleanup the way an unguarded {@code super}
     * call could. If a cleanup step also fails, that failure does not replace this hook's own
     * exception: it is attached to it via {@link Throwable#addSuppressed} instead, so the
     * module author's own failure is always the one that propagates and is never silently lost
     * behind a secondary framework failure (Codex review on #457, issue #484).
     */
    // The empty body IS the design: it is what keeps every existing module unaffected --
    // a module with no unload work needs no override at all (PMD.UncommentedEmptyMethodBody,
    // matching CommandValidator#onComplete's established precedent for this exact shape).
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    protected void onUnregister() {
    }

    @Override
    public final void unregisterSelf() {
        // Every step below runs regardless of an earlier step's failure (Codex review on #457:
        // "Run listener cleanup even if command cleanup throws") -- a single flat try/finally
        // would let CommandManager.unregisterAll(this) throwing skip
        // ListenerManager.unregisterAll(this) entirely. But running every step is not enough on
        // its own: plain finally-block semantics also let a LATER step's exception silently
        // replace an EARLIER one, discarding it (Codex review on #457, issue #484: "Preserve the
        // hook failure when cleanup also throws"). So failures are collected instead -- the
        // FIRST one, in source order (hook, then command cleanup, then listener cleanup), is the
        // one that propagates, and every later failure is attached to it via addSuppressed()
        // rather than overwriting it.
        Throwable failure = runUnregisterStep(null, this::onUnregister);
        failure = runUnregisterStep(failure, () -> getCommandManager().unregisterAll(this));
        failure = runUnregisterStep(failure, () -> getListenerManager().unregisterAll(this));
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        } else if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    /**
     * Runs one {@link #unregisterSelf()} cleanup step, collecting any failure onto {@code
     * previousFailure} instead of letting it replace or be replaced by another step's failure.
     * <p>
     * {@code step} can only throw an unchecked exception -- {@link #onUnregister()},
     * {@code CommandManager#unregisterAll}, and {@code ListenerManager#unregisterAll} all
     * declare no checked exceptions -- so catching {@code RuntimeException | Error} here covers
     * every real case without the width of catching {@link Throwable} (this is a deliberate
     * cleanup barrier, not a broad catch: {@code unregisterSelf()} always runs every remaining
     * step and always rethrows exactly one collected failure, per its own javadoc).
     *
     * @param previousFailure the failure already collected from an earlier step, or {@code null}
     *                        if every earlier step (if any) succeeded
     * @param step            the cleanup step to run
     * @return {@code previousFailure}, with {@code step}'s own failure (if any) attached to it
     *     via {@link Throwable#addSuppressed}; or, if {@code previousFailure} was {@code null},
     *     {@code step}'s own failure, or {@code null} if {@code step} completed normally
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // deliberate cleanup barrier -- see javadoc above
    private static Throwable runUnregisterStep(Throwable previousFailure, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error e) {
            if (previousFailure == null) {
                return e;
            }
            previousFailure.addSuppressed(e);
        }
        return previousFailure;
    }

    /**
     * Extension point for a module's own reload work.
     * <p>
     * Called by {@link #reloadSelf()} <em>after</em> the framework's own reload steps -- config
     * reload, config-bound timing refresh (#531), language-catalogue refresh,
     * {@code @ConditionalOnConfig} drift report, and the
     * framework-owned per-module reload log line (D-02, D-03) -- so a real-work override sees
     * the already-reloaded configuration rather than the stale one. {@link #reloadSelf()} is
     * {@code final} and always runs its own steps first; a module cannot skip them by
     * overriding {@link #reloadSelf()} itself, because that is no longer possible (D-01). The
     * default body does nothing; override this method, not {@link #reloadSelf()}, to add reload
     * work.
     */
    // The empty body IS the design: it is what keeps every existing module unaffected --
    // a module with no reload work needs no override at all (PMD.UncommentedEmptyMethodBody,
    // matching CommandValidator#onComplete's established precedent for this exact shape).
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    protected void onReload() {
    }

    /**
     * Reload this plugin's configuration and language files.
     * <p>
     * Also reports (but does not act on) any {@code @ConditionalOnConfig} drift: the condition
     * is evaluated once, at component-scan time during startup, so a reload can only log that a
     * watched key has changed direction since then -- it never registers, unregisters, or
     * rebuilds anything (issue #392, D-01). Right after the configuration reload, and only if it
     * succeeded, the module's config-bound {@code @Scheduled} tasks and {@code @CmdCD} cooldowns
     * pick up their reloaded values (#531; see {@code PluginManager#applyReloadedConfigBindings}).
     * {@code final} and always runs its own steps,
     * then logs one framework-owned INFO line naming this module (D-03), then calls
     * {@link #onReload()} -- a module can no longer skip any of this by overriding
     * {@code reloadSelf()} itself, because that override point no longer exists (D-01).
     */
    @Override
    public final void reloadSelf() {
        getConfigManager().reloadConfigs(this);
        // #531: apply the reloaded values to config-bound @Scheduled/@CmdCD. Only reached when
        // reloadConfigs did not throw, so a refused reload leaves the running timings alone.
        PluginManager pluginManager = UltiTools.getInstance().getPluginManager();
        if (pluginManager != null) {
            pluginManager.applyReloadedConfigBindings(this);
        }
        // Reinitialize language in case language setting changed
        language = createLanguageFromPath(resourceFolderPath);
        // @ConditionalOnConfig is evaluated once at component-scan time; a reload can only
        // report drift on a watched key, never re-register or rebuild anything (#392, D-01).
        ConditionalRegistrationEvaluator.reportDrift(this);
        LOGGER.log(Level.INFO, String.format(UltiTools.getInstance().i18n(RELOAD_LOG_MESSAGE_KEY), getPluginName()));
        onReload();
    }

    /**
     * @return plugin logger
     */
    public PluginLogger getLogger() {
        return new PluginLogger(this.pluginName, UltiTools.getInstance().getLogger());
    }
}
