package com.ultikits.ultitools.abstracts;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.common.reflect.TypeToken;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.ConfigLoadResult;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.config.document.PlainData;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.config.convert.ConversionResult;
import com.ultikits.ultitools.config.convert.ConversionFailure;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Pattern;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.annotations.config.Size;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.ConfigChangeListener;
import com.ultikits.ultitools.utils.ReflectionUtil;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * A module configuration bound through the converter registry and a comment-preserving document.
 * All framework operations run under the entity monitor; module field setters remain unsynchronized.
 * Constructors must be cheap and side-effect-free: validation constructs one throwaway instance to
 * prove the existing String/no-arg construction contract. Snapshot checks never construct entities.
 * The persisted raw document and the ordered effective field baseline are separate: partial panel
 * writes must not acknowledge unrelated unsaved code edits. Missing reload keys retain live fields,
 * but their baseline remains the initial declared default.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Config binder writes/reads private @ConfigEntry fields -- see 08-GATE05-TRIAGE.md
@Getter
public abstract class AbstractConfigEntity {
    private static final Logger LOGGER = Logger.getLogger(AbstractConfigEntity.class.getName());

    /**
     * The numeric wrappers in JLS 5.1.2 widening order: every conversion from an earlier entry to a
     * later one is a widening primitive conversion, and no other conversion between them is.
     */
    private static final List<Class<?>> WIDENING_ORDER = Collections.unmodifiableList(Arrays.<Class<?>>asList(
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class));

    /** Converts a {@link Number} to the wrapper at the same index of {@link #WIDENING_ORDER}. */
    private static final List<Function<Number, Object>> WIDENERS =
            Collections.unmodifiableList(Arrays.<Function<Number, Object>>asList(
                    Number::byteValue, Number::shortValue, Number::intValue, Number::longValue,
                    Number::floatValue, Number::doubleValue));
    
    private final String configFilePath;
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private UltiToolsPlugin ultiToolsPlugin;
    @Getter(AccessLevel.NONE)
    private ConfigDocument document;
    @Getter(AccessLevel.NONE)
    private Map<String, Object> lastLoadedPresence;
    @Getter(AccessLevel.NONE)
    private final Map<Field, RawEntry> acknowledgedRaw = new LinkedHashMap<>();

    /** A detached raw entry acknowledgment; absent and explicit null are different states. */
    private static final class RawEntry {
        private final boolean present;
        private final Object value;

        private RawEntry(ConfigDocument source, List<String> path) {
            this(source.contains(path), source.get(path));
        }

        private RawEntry(boolean present, Object value) {
            this.present = present; this.value = PlainData.copy(value);
        }

        private boolean matches(ConfigDocument source, List<String> path) {
            return present == source.contains(path) && PlainData.plainEquals(value, source.get(path));
        }
    }

    @Getter(AccessLevel.NONE)
    private final Map<Field, Object> declaredDefaults = new LinkedHashMap<>();
    @Getter(AccessLevel.NONE)
    private Map<Field, Object> savedSnapshot;
    @Getter(AccessLevel.NONE)
    private String savedFileFingerprint;
    @Getter(AccessLevel.NONE)
    private boolean lastLoadUnparseable;
    @Getter(AccessLevel.NONE)
    private volatile boolean lastInitIncomplete;
    @Getter(AccessLevel.NONE)
    private boolean defaultsCaptured;
    @Getter(AccessLevel.NONE)
    private boolean pendingCommentWrite;
    @Getter(AccessLevel.NONE)
    private boolean deferInitialization;
    @Getter(AccessLevel.NONE)
    private PendingInitialization pendingInitialization;

    private static final class PendingInitialization {
        private final ConfigDocument candidate;
        private final Map<Field, Object> baseline;
        private PendingInitialization(ConfigDocument candidate, Map<Field, Object> baseline) {
            this.candidate = candidate; this.baseline = baseline;
        }
    }
    @Getter(AccessLevel.NONE)
    private final Set<String> warnedCommentKeys = ConcurrentHashMap.newKeySet();

    /**
     * The value ranges a config binding (#531) imposes on this entity's keys, by {@code @ConfigEntry}
     * path, then by rule text. A panel write ({@link #updateProperties}, {@link
     * #validateProposedProperties}) is refused when it sets a bound key outside its range, the same
     * way as a {@code @Range} violation. {@link #init} and {@link #reload()} do not consult this:
     * the binding step handles an invalid value there (it refuses the module at load and keeps the
     * running value on reload).
     */
    @Getter(AccessLevel.NONE)
    private final Map<String, Map<String, Predicate<Long>>> bindingRanges = new ConcurrentHashMap<>();

    /**
     * Constructor for AbstractConfigEntity.
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Persists all fields, preserving current operator comments and unknown keys. Semantically
     * equal data performs no writer call (bytes and modification time stay unchanged). An explicit
     * save still replaces disk values differing from the entity, even when the entity was clean.
     * A failed write leaves the previous effective baseline in place.
     * @throws IOException if persistence fails
     */
    public void save() throws IOException {
        synchronized (this) {
            if (lastLoadUnparseable) { return; }
            persist(configEntryFields());
        }
    }

    private ConverterRegistry registry() { return ConverterRegistry.forModule(ultiToolsPlugin); }

    private String fieldPath(Field field) {
        String path = field.getAnnotation(ConfigEntry.class).path();
        return path.isEmpty() ? field.getName() : path;
    }

    private List<String> keys(Field field) { return Arrays.asList(fieldPath(field).split("\\.", -1)); }

    private Type declaredType(Field field) {
        return TypeToken.of(getClass()).resolveType(field.getGenericType()).getType();
    }

    private Object plainValue(Field field) {
        field.setAccessible(true);
        try {
            return PlainData.copy(registry().toPlain(ReflectionUtil.getFieldValue(this, field), declaredType(field),
                    configFilePath, keys(field), field.getAnnotation(ConfigEntry.class)));
        } catch (ConversionException failure) {
            throw new ConfigurationException(failure.getMessage(), failure);
        }
    }

    private Map<Field, Object> currentPlain(List<Field> fields) {
        Map<Field, Object> plain = new LinkedHashMap<>();
        for (Field field : fields) { plain.put(field, plainValue(field)); }
        return plain;
    }

    private void captureDefaults() {
        if (!defaultsCaptured) {
            declaredDefaults.putAll(currentPlain(configEntryFields()));
            defaultsCaptured = true;
        }
    }

    private void persist(List<Field> fields) throws IOException {
        PreparedSave prepared = prepareSave(fields);
        if (prepared.changed) { write(prepared.candidate); }
        acknowledgeSave(prepared);
    }

    private static final class PreparedSave {
        private final ConfigDocument candidate;
        private final Map<Field, Object> values;
        private final List<String> overwritten;
        private final boolean changed;
        private Map<Field, RawEntry> raw;
        private PreparedSave(ConfigDocument candidate, Map<Field, Object> values,
                List<String> overwritten, boolean changed) {
            this.candidate = candidate; this.values = values;
            this.overwritten = overwritten; this.changed = changed;
        }
    }

    private PreparedSave prepareSave(List<Field> fields) throws IOException {
        return prepareSave(fields, Collections.emptyMap());
    }

    private PreparedSave prepareSave(List<Field> fields, Map<Field, List<List<String>>> leaves) throws IOException {
        // Convert every candidate before reading or mutating the presentation document.
        Map<Field, Object> values = currentPlain(fields);
        ConfigLoadResult loaded = ConfigDocument.load(ultiToolsPlugin.getConfigFile(configFilePath).toPath());
        if (protectFailedLoad(loaded)) {
            throw new IOException("Cannot save " + configFilePath + ": current file is " + loaded.state());
        }
        ConfigDocument candidate = loaded.state() == ConfigLoadResult.State.LOADED
                ? loaded.document() : ConfigDocument.empty();
        boolean changed = false;
        List<String> overwritten = new ArrayList<>();
        Map<Field, RawEntry> raw = new LinkedHashMap<>();
        for (Map.Entry<Field, Object> entry : values.entrySet()) {
            Field field = entry.getKey();
            List<String> path = keys(field);
            if (leaves.containsKey(field)) {
                Object baseline = savedSnapshot == null ? declaredDefaults.get(field) : savedSnapshot.get(field);
                RawEntry previous = acknowledgedRaw.get(field);
                Object rawBaseline = previous == null ? null : previous.value;
                for (List<String> leaf : leaves.get(field)) {
                    List<String> diskPath = new ArrayList<>(path); diskPath.addAll(leaf);
                    Object next = mapLeaf(entry.getValue(), leaf);
                    boolean existed = candidate.contains(diskPath);
                    if (!existed || !PlainData.plainEquals(candidate.get(diskPath), next)) {
                        if (previous != null && (mapContains(previous.value, leaf) != existed
                                || !PlainData.plainEquals(mapLeaf(previous.value, leaf), candidate.get(diskPath)))) {
                            overwritten.add("'" + String.join(".", diskPath) + "'");
                        }
                        candidate.set(diskPath, next); changed = true;
                    }
                    baseline = patchedMap(baseline, leaf, next);
                    rawBaseline = patchedMap(rawBaseline, leaf, next);
                }
                entry.setValue(baseline);
                raw.put(field, new RawEntry(true, rawBaseline));
                continue;
            }
            boolean missing = !candidate.contains(path);
            if (missing || !PlainData.plainEquals(candidate.get(path), entry.getValue())) {
                RawEntry previous = acknowledgedRaw.get(entry.getKey());
                if (previous != null && !previous.matches(candidate, path)) {
                    overwritten.add("'" + fieldPath(entry.getKey()) + "'");
                }
                candidate.set(path, entry.getValue()); changed = true;
            }
            if (missing && !isTokenComment(entry.getKey())) { changed |= addEntryComment(candidate, entry.getKey()); }
        }
        changed |= updateTokenComments(candidate);
        PreparedSave prepared = new PreparedSave(candidate, values, overwritten, changed);
        prepared.raw = raw;
        return prepared;
    }

    private void acknowledgeSave(PreparedSave prepared) {
        if (prepared.changed && !prepared.overwritten.isEmpty()) { warnOverwritten(prepared.overwritten); }
        pendingCommentWrite = false;
        document = prepared.candidate;
        if (savedSnapshot == null) { savedSnapshot = new LinkedHashMap<>(declaredDefaults); }
        savedSnapshot.putAll(prepared.values);
        for (Field field : prepared.values.keySet()) {
            RawEntry raw = prepared.raw == null ? null : prepared.raw.get(field);
            acknowledgedRaw.put(field, raw == null ? new RawEntry(prepared.candidate, keys(field)) : raw);
        }
        savedFileFingerprint = fingerprintOf(ultiToolsPlugin.getConfigFile(configFilePath));
    }

    private void acknowledgeRaw(ConfigDocument source, List<Field> fields) {
        for (Field field : fields) { acknowledgedRaw.put(field, new RawEntry(source, keys(field))); }
    }

    private void warnOverwritten(List<String> paths) {
        // Values are deliberately omitted: even an ordinary field may hold a credential.
        Logger logger = UltiTools.getInstance() == null ? LOGGER : UltiTools.getInstance().getLogger();
        logger.log(Level.WARNING, "Configuration file " + ultiToolsPlugin.getConfigFile(configFilePath).getAbsolutePath()
                + " had operator-edited keys overwritten: " + String.join(", ", paths));
    }

    private void write(ConfigDocument candidate) throws IOException {
        File file = ultiToolsPlugin.getConfigFile(configFilePath);
        Files.createDirectories(file.toPath().toAbsolutePath().getParent());
        AtomicConfigWriter.write(file.toPath(), candidate.render());
    }

    private boolean isTokenComment(Field field) {
        return field.getAnnotation(ConfigEntry.class).comment().trim().matches("\\{[^{}]+\\}");
    }

    private String resolvedComment(Field field) {
        String literal = field.getAnnotation(ConfigEntry.class).comment();
        if (!isTokenComment(field)) { return literal; }
        String token = literal.trim();
        String key = token.substring(1, token.length() - 1);
        String resolved = null;
        try { resolved = ultiToolsPlugin.i18n(key); }
        catch (RuntimeException unavailable) {
            // Catalogue failure must not prevent configuration values from loading.
        }
        if (resolved != null && !resolved.equals(key)) { return resolved; }
        if (warnedCommentKeys.add(fieldPath(field))) {
            LOGGER.warning("Module " + ultiToolsPlugin.getPluginName() + ", file " + configFilePath
                    + ", path '" + fieldPath(field) + "': missing comment catalogue key '" + key + "'");
        }
        return token;
    }

    private boolean addEntryComment(ConfigDocument target, Field field) {
        String comment = resolvedComment(field);
        if (comment.isEmpty()) { return false; }
        List<String> before = target.blockComment(keys(field));
        // Merge-inherited entries exist in the plain view but need their own explicit comment owner.
        target.set(keys(field), target.get(keys(field)));
        target.setFrameworkComment(keys(field), Collections.singletonList(comment));
        return !before.equals(target.blockComment(keys(field)));
    }

    private boolean updateTokenComments(ConfigDocument target) {
        boolean changed = false;
        for (Field field : configEntryFields()) {
            if (isTokenComment(field) && target.contains(keys(field))) {
                changed |= addEntryComment(target, field);
            }
        }
        return changed;
    }

    private String panelComment(Field field) {
        // Reuse the storage boundary's sole sanitation policy, including every YAML line break.
        ConfigDocument presentation = ConfigDocument.empty();
        presentation.set(keys(field), null);
        presentation.setFrameworkComment(keys(field), Collections.singletonList(resolvedComment(field)));
        return String.join("\n", presentation.blockComment(keys(field)));
    }

    private boolean protectFailedLoad(ConfigLoadResult loaded) {
        if (loaded.state() != ConfigLoadResult.State.UNREADABLE
                && loaded.state() != ConfigLoadResult.State.UNPARSEABLE) { return false; }
        lastLoadUnparseable = true;
        String cause = loaded.state() == ConfigLoadResult.State.UNREADABLE
                ? loaded.cause().getClass().getSimpleName() : safeParserLocation(loaded.parserMessage());
        LOGGER.severe("Cannot load " + configFilePath + ": " + cause + "; file will not be overwritten");
        return true;
    }

    private static String safeParserLocation(String message) {
        if (message != null) {
            // The storage result discards the typed parser cause. Extract numeric metadata only,
            // never the filename, parser reason, scalar snippet or throwable message.
            java.util.regex.Matcher location = java.util.regex.Pattern.compile(
                    "(?m)^ *in [^\\r\\n]*, line ([0-9]+), column ([0-9]+):? *$").matcher(message);
            if (location.find()) {
                return "invalid YAML at line " + location.group(1) + ", column " + location.group(2);
            }
        }
        return "invalid YAML or UTF-8";
    }

    /**
     * Every {@code @ConfigEntry} field this class declares or inherits, in {@link
     * ReflectionUtil#getFields(Class)} order.
     *
     * @return the annotated fields, possibly empty
     */
    private List<Field> configEntryFields() {
        List<Field> configFields = new ArrayList<>();
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                configFields.add(field);
            }
        }
        return configFields;
    }

    /**
     * Whether serialized fields differ from their last bound/persisted effective values.
     * Map iteration order is significant here; the storage equality used for no-op saves is not.
     * Protected files and uninitialized entities are never saved by shutdown.
     * @return whether shutdown should persist this entity
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean isModifiedSinceSnapshot() {
        synchronized (this) {
            if (document == null || ultiToolsPlugin == null || lastLoadUnparseable) { return false; }
            if (savedSnapshot == null || pendingCommentWrite) { return true; }
            try { return !orderedEquals(savedSnapshot, currentPlain(configEntryFields())); }
            catch (RuntimeException failure) {
                LOGGER.warning("Cannot compare the state of " + configFilePath + "; treating it as changed");
                return true;
            }
        }
    }

    /** Names unsaved declared entries without exposing their values, for superseded-copy reporting.
     * @return changed entry paths
     */
    @ApiStatus.Internal
    public final List<String> unsavedEntryPaths() {
        synchronized (this) {
            List<String> paths = new ArrayList<>();
            for (Field field : configEntryFields()) {
                if (savedSnapshot == null || !orderedEquals(savedSnapshot.get(field), plainValue(field))) {
                    paths.add(fieldPath(field));
                }
            }
            return paths;
        }
    }

    private static boolean orderedEquals(Object left, Object right) {
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> a = (Map<?, ?>) left; Map<?, ?> b = (Map<?, ?>) right;
            if (a.size() != b.size()) { return false; }
            Iterator<? extends Map.Entry<?, ?>> ai = a.entrySet().iterator();
            Iterator<? extends Map.Entry<?, ?>> bi = b.entrySet().iterator();
            while (ai.hasNext()) {
                Map.Entry<?, ?> x = ai.next(); Map.Entry<?, ?> y = bi.next();
                if (!x.getKey().equals(y.getKey()) || !orderedEquals(x.getValue(), y.getValue())) { return false; }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> a = (List<?>) left; List<?> b = (List<?>) right;
            if (a.size() != b.size()) { return false; }
            for (int i = 0; i < a.size(); i++) {
                if (!orderedEquals(a.get(i), b.get(i))) { return false; }
            }
            return true;
        }
        return PlainData.plainEquals(left, right);
    }

    /**
     * Reports presence in the last successfully loaded document, including explicit nulls and
     * undeclared keys. The path splits at every dot like ConfigEntry paths; a key itself containing
     * a dot cannot be addressed through this method. Failed loads report no presence.
     * @param path dotted configuration path
     * @return whether the last load contained the key
     * @since 6.3.0
     */
    public final boolean isPresentInFile(String path) {
        synchronized (this) {
            if (lastLoadedPresence == null || lastLoadUnparseable) { return false; }
            Object current = lastLoadedPresence;
            for (String key : path.split("\\.", -1)) {
                if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(key)) { return false; }
                current = ((Map<?, ?>) current).get(key);
            }
            return true;
        }
    }

    /**
     * Whether the file on disk differs from the file as it was at the last snapshot point (#510) -
     * in practice, whether someone edited, replaced or removed it while the server was running. Used
     * by the shutdown save to warn that an in-memory change overwrote that file.
     * <p>
     * Framework-internal: this method is called only by {@code ConfigManager#saveAll()} and is
     * {@code public} solely because {@code ConfigManager} lives in another package. Module code
     * should not call it.
     *
     * @return {@code true} if the file's fingerprint changed since the last snapshot; {@code false}
     *         if it did not, or if no snapshot has been taken yet
     * @since 6.3.0
     */
    /**
     * Whether the last attempt to read this configuration's file failed to parse (#510) - in
     * practice, whether the file on disk holds invalid YAML. The shutdown save skips such a
     * configuration and says so, instead of overwriting a file the framework could not read.
     * <p>
     * Framework-internal: this method is called only by {@code ConfigManager#saveAll()} and is
     * {@code public} solely because {@code ConfigManager} lives in another package. Module code
     * should not call it.
     *
     * @return {@code true} if the last load of this configuration's file failed to parse, and no
     *         successful load has happened since
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean isLastLoadUnparseable() {
        synchronized (this) {
            return lastLoadUnparseable;
        }
    }

    /**
     * Whether the last {@link #init} failed before its field validation completed -- for example
     * because writing back a missing key threw an {@code IOException}, which {@code ConfigManager}
     * logs and continues past. The fields may then hold values that their validation annotations
     * never checked. The config-bound {@code @Scheduled}/{@code @CmdCD} step (#531) does not apply
     * values from such an entity: it refuses the module at load and keeps the running value on
     * reload.
     * <p>
     * Thread contract: the marker is set by {@code init} and read by the binding step on the main
     * server thread, where the framework and the first-party modules run both. An {@code init} or
     * {@link #reload()} run off the main thread is not synchronized with the binding step; confining
     * them to the main thread is tracked in UltiKits/UltiTools-Reborn#538.
     * <p>
     * Framework-internal: {@code public} solely because the binding step lives in another package.
     * Module code should not call it.
     *
     * @return {@code true} if the last {@code init} did not complete its validation
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean isLastInitIncomplete() {
        return lastInitIncomplete;
    }

    /**
     * Records that {@code key} is bound by a config-bound {@code @Scheduled} or {@code @CmdCD}
     * (#531), so that a panel write setting it to a value {@code isValid} rejects is refused, naming
     * the key, the value and {@code rule}. The binding step calls this when it resolves the binding
     * at load. Recording the same rule twice for a key keeps one.
     * <p>
     * Framework-internal: {@code public} solely because the binding step lives in another package.
     * Module code should not call it.
     *
     * @param key     the {@code @ConfigEntry} path, or the field name when the path is empty
     * @param rule    the range, as a sentence, for the refusal message
     * @param isValid whether a value, in seconds ({@code null} for a {@code null} boxed field), is
     *                inside the range
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final void addBindingRange(String key, String rule, Predicate<Long> isValid) {
        bindingRanges.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(rule, isValid);
    }

    @ApiStatus.Internal
    public final boolean isFileModifiedSinceSnapshot() {
        synchronized (this) {
            String fingerprint = savedFileFingerprint;
            if (fingerprint == null || ultiToolsPlugin == null) {
                return false;
            }
            String current;
            try {
                current = fingerprintOf(ultiToolsPlugin.getConfigFile(configFilePath));
            } catch (RuntimeException e) {
                current = "unreadable";
            }
            return !fingerprint.equals(current);
        }
    }

    /**
     * Fingerprints a configuration file by the SHA-256 digest of its bytes (#510). A content hash
     * rather than size plus modification time: a typical operator edit changes one value to another
     * of the same length ({@code 60} to {@code 90}, {@code true} to {@code TRUE}), which size alone
     * cannot see, and modification time is coarse or preserved on common paths (two-second
     * resolution on FAT and many network shares, {@code cp -p}, {@code rsync -t}, editors that
     * restore it). Configuration files are small and this runs only at load, save and shutdown.
     *
     * @param file the configuration file, possibly {@code null} or missing
     * @return {@code "absent"} for a missing file, {@code "unreadable"} if it cannot be read, or the
     *         Base64 SHA-256 digest of its contents
     */
    private static String fingerprintOf(File file) {
        if (file == null || !file.isFile()) {
            return "absent";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            return Base64.getEncoder().encodeToString(digest);
        } catch (IOException e) {
            return "unreadable";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    /**
     * Initializes the configuration entity.
     *
     * @param ultiToolsPlugin the plugin instance
     * @throws IOException if an I/O error occurs
     */
    public final void init(UltiToolsPlugin ultiToolsPlugin) throws IOException {
        initialize(ultiToolsPlugin, false);
    }

    /** Framework manager bridge; binds without flushing initialization writes.
     * @param plugin owning module
     * @throws IOException on load failure
     */
    @ApiStatus.Internal
    public final void initForBatch(UltiToolsPlugin plugin) throws IOException {
        initialize(plugin, true);
    }

    private void initialize(UltiToolsPlugin ultiToolsPlugin, boolean deferred) throws IOException {
        if (!com.ultikits.ultitools.manager.ConfigManager.permitsConfigThread(ultiToolsPlugin, "init " + configFilePath)) { return; }
        synchronized (this) {
            deferInitialization = deferred;
            lastInitIncomplete = true;
            this.ultiToolsPlugin = ultiToolsPlugin;
            registry().checkEntityFields(getClass(), ultiToolsPlugin.getPluginName(), configFilePath);
            captureDefaults();
            try { load(true); }
            finally { deferInitialization = false; }
            lastInitIncomplete = false;
        }
        notifyChangeListeners();
    }

    private void load(boolean initialize) throws IOException {
        warnedCommentKeys.clear();
        ConfigLoadResult loaded = ConfigDocument.load(ultiToolsPlugin.getConfigFile(configFilePath).toPath());
        if (protectFailedLoad(loaded)) {
            if (document == null) { document = ConfigDocument.empty(); }
            validateFields();
            return;
        }
        lastLoadUnparseable = false;
        ConfigDocument next = loaded.state() == ConfigLoadResult.State.LOADED
                ? loaded.document() : ConfigDocument.empty();
        // Presence describes the load input, never defaults/comment writes or a later save read.
        Map<String, Object> loadedPresence = next.toPlain();
        Map<Field, Object> baseline = new LinkedHashMap<>(declaredDefaults);
        Map<Field, Object> mine = initialize ? Collections.emptyMap() : currentPlain(configEntryFields());
        List<String> conflicts = new ArrayList<>();
        List<Field> missing = new ArrayList<>();
        for (Field field : configEntryFields()) {
            field.setAccessible(true);
            if (!next.contains(keys(field))) { missing.add(field); continue; }
            Object raw = next.get(keys(field));
            try {
                ConversionResult<Object> converted = registry().fromPlainResult(raw, declaredType(field),
                        configFilePath, keys(field), field.getAnnotation(ConfigEntry.class));
                ReflectionUtil.setFieldValue(this, field, converted.value());
                for (ConversionFailure failure : converted.failures()) {
                    warnConversion(field, failure.path(), failure.declaredType(), failure.raw(), raw);
                }
            } catch (ConversionException failure) {
                warnConversion(field, failure.path(), failure.declaredType(), raw, raw);
                // Restore the initially declared default, not a live unsaved value or the last load.
                try {
                    Object value = registry().fromPlainResult(declaredDefaults.get(field), declaredType(field),
                            configFilePath, keys(field), field.getAnnotation(ConfigEntry.class)).value();
                    ReflectionUtil.setFieldValue(this, field, value);
                } catch (ConversionException invalidDefault) {
                    throw new ConfigurationException(invalidDefault.getMessage(), invalidDefault);
                }
            }
            Object theirs = plainValue(field);
            baseline.put(field, theirs);
            if (!initialize && savedSnapshot != null && savedSnapshot.containsKey(field)) {
                Object merged = mergeReload(savedSnapshot.get(field), mine.get(field), theirs,
                        fieldPath(field), isSecretShapedFieldName(field.getName()), conflicts);
                try {
                    Object value = registry().fromPlainResult(merged, declaredType(field), configFilePath,
                            keys(field), field.getAnnotation(ConfigEntry.class)).value();
                    ReflectionUtil.setFieldValue(this, field, value);
                } catch (ConversionException failure) { throw new ConfigurationException(failure.getMessage(), failure); }
            }
        }
        validateFields();
        document = next;
        boolean changed = false;
        if (initialize) {
            for (Field field : missing) {
                Object value = plainValue(field);
                next.set(keys(field), value); addEntryComment(next, field);
                baseline.put(field, value); changed = true;
            }
        }
        boolean commentsChanged = updateTokenComments(next);
        pendingCommentWrite = false;
        if (deferInitialization && (changed || commentsChanged)) {
            pendingInitialization = new PendingInitialization(next, baseline);
            lastLoadedPresence = loadedPresence;
            return;
        }
        if (changed) { write(next); }
        else if (commentsChanged) {
            try { write(next); }
            catch (IOException failure) {
                pendingCommentWrite = true;
                LOGGER.warning("Cannot rewrite comments in " + configFilePath + ": "
                        + failure.getClass().getSimpleName() + "; pending for retry");
            }
        }
        lastLoadedPresence = loadedPresence;
        savedSnapshot = baseline;
        acknowledgeRaw(next, configEntryFields());
        savedFileFingerprint = fingerprintOf(ultiToolsPlugin.getConfigFile(configFilePath));
        for (String conflict : conflicts) { LOGGER.warning("Configuration " + configFilePath + ": " + conflict); }
    }

    private static final Object ABSENT_RELOAD_VALUE = new Object();

    private Object mergeReload(Object base, Object mine, Object theirs, String path, boolean secret, List<String> conflicts) {
        if (orderedEquals(mine, base)) { return theirs; }
        if (orderedEquals(theirs, base) || orderedEquals(mine, theirs)) { return mine; }
        if (base instanceof Map && mine instanceof Map && theirs instanceof Map) {
            Map<?, ?> b = (Map<?, ?>) base; Map<?, ?> m = (Map<?, ?>) mine; Map<?, ?> t = (Map<?, ?>) theirs;
            Set<Object> keys = new java.util.LinkedHashSet<>(); keys.addAll(t.keySet()); keys.addAll(m.keySet()); keys.addAll(b.keySet());
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object key : keys) {
                Object value = mergeReload(b.containsKey(key) ? b.get(key) : ABSENT_RELOAD_VALUE,
                        m.containsKey(key) ? m.get(key) : ABSENT_RELOAD_VALUE,
                        t.containsKey(key) ? t.get(key) : ABSENT_RELOAD_VALUE,
                        path + "." + key, secret || isSecretShapedFieldName(String.valueOf(key)), conflicts);
                if (value != ABSENT_RELOAD_VALUE) { merged.put(String.valueOf(key), value); }
            }
            return merged;
        }
        String value = secret || isSecretShapedFieldName(path) || containsSecret(mine) ? "<redacted>"
                : mine == ABSENT_RELOAD_VALUE ? "<absent>" : String.valueOf(mine);
        conflicts.add("reload conflict at '" + path + "': discarded in-memory value " + value + "; file wins");
        return theirs;
    }

    /** Flushes only a validated manager batch's initialization candidate.
     * @throws IOException when replacement fails; the entity is then protected until reload
     */
    @ApiStatus.Internal
    public final void flushInitializationWrite() throws IOException {
        synchronized (this) {
            PendingInitialization pending = pendingInitialization;
            pendingInitialization = null;
            if (pending == null || lastLoadUnparseable) { return; }
            try { write(pending.candidate); }
            catch (IOException failure) { lastLoadUnparseable = true; throw failure; }
            document = pending.candidate;
            savedSnapshot = pending.baseline;
            acknowledgeRaw(document, configEntryFields());
            savedFileFingerprint = fingerprintOf(ultiToolsPlugin.getConfigFile(configFilePath));
        }
    }

    /** Clears an uncommitted initialization belonging to a refused manager batch. */
    @ApiStatus.Internal
    public final void discardInitializationWrite() {
        synchronized (this) {
            if (pendingInitialization == null) { return; }
            pendingInitialization = null;
            document = null;
            savedSnapshot = null;
            acknowledgedRaw.clear();
            savedFileFingerprint = null;
            lastLoadedPresence = new LinkedHashMap<>();
        }
    }

    private void warnConversion(Field field, List<String> path, Type type, Object raw, Object fieldRaw) {
        StringBuilder located = new StringBuilder(fieldPath(field));
        Object cursor = fieldRaw;
        boolean parentSecret = isSecretShapedFieldName(field.getName());
        for (String key : keys(field)) { parentSecret |= isSecretShapedFieldName(key); }
        for (int i = keys(field).size(); i < path.size(); i++) {
            String part = path.get(i);
            if (cursor instanceof List) {
                located.append('[').append(part).append(']');
                cursor = ((List<?>) cursor).get(Integer.parseInt(part));
            } else {
                located.append('.').append(parentSecret ? "<redacted>" : part);
                cursor = cursor instanceof Map ? ((Map<?, ?>) cursor).get(part) : null;
            }
            parentSecret |= isSecretShapedFieldName(part);
        }
        String key = located.toString();
        boolean secret = isSecretShapedFieldName(field.getName());
        for (String part : path) { secret |= isSecretShapedFieldName(part); }
        String found = raw instanceof Map ? "a map" : raw instanceof List ? "a list"
                : raw instanceof String ? "text" : raw == null ? "null" : raw.getClass().getSimpleName();
        // Container values may hold nested credentials; redact the entire failed specimen.
        String value = secret || containsSecret(raw) ? "<redacted>" : String.valueOf(raw);
        LOGGER.warning("File " + configFilePath + ", key '" + key + "', declared as " + typeName(type)
                + ": found " + found + " " + value + "; skipped or using the declared default");
    }

    private boolean containsSecret(Object value) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (isSecretShapedFieldName(String.valueOf(entry.getKey())) || containsSecret(entry.getValue())) { return true; }
            }
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) { if (containsSecret(child)) { return true; } }
        }
        return false;
    }

    private static String typeName(Type type) {
        if (type instanceof Class) { return ((Class<?>) type).getSimpleName(); }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            List<String> arguments = new ArrayList<>();
            for (Type argument : parameterized.getActualTypeArguments()) { arguments.add(typeName(argument)); }
            return typeName(parameterized.getRawType()) + "<" + String.join(", ", arguments) + ">";
        }
        return type.getTypeName();
    }

    /**
     * Gives a boxed numeric field exactly the widening conversions its primitive already gets.
     * <p>
     * SnakeYAML hands back an {@code Integer} for a whole number such as {@code 1800}.
     * {@code Field.set} widens that into a {@code long} or {@code double} field, but it refuses the
     * same value for a {@code Long}, {@code Double} or {@code Float} field (measured:
     * {@code IllegalArgumentException: Can not set java.lang.Long field ... to java.lang.Integer}).
     * A boxed field therefore loaded on the first boot, when the key was missing and the field
     * default was written, and threw on every later boot and on every reload (#531, gate-1 CR-01).
     * <p>
     * Only the JLS 5.1.2 widening primitive conversions are applied, so a boxed field accepts
     * exactly what its primitive accepts: {@code Short} from {@code Byte}; {@code Integer} from
     * {@code Byte}/{@code Short}; {@code Long} from {@code Byte}/{@code Short}/{@code Integer};
     * {@code Float} from any integral value; {@code Double} from any integral value or a
     * {@code Float}. Anything else -- a narrowing conversion, a non-numeric value, a field that
     * is not a numeric wrapper -- is returned unchanged, so {@code Field.set} refuses it exactly as
     * before.
     *
     * @param fieldType the declared type of the target field
     * @param value     the parsed value
     * @return {@code value} widened to {@code fieldType}, or {@code value} itself
     */
    static Object widenToFieldType(Class<?> fieldType, Object value) {
        if (!(value instanceof Number) || fieldType.isInstance(value)) {
            return value;
        }
        int from = WIDENING_ORDER.indexOf(value.getClass());
        int to = WIDENING_ORDER.indexOf(fieldType);
        // Not a numeric wrapper pair, or a narrowing conversion: unchanged, so Field.set refuses it.
        if (from < 0 || to <= from) {
            return value;
        }
        return WIDENERS.get(to).apply((Number) value);
    }


    /**
     * Splits a {@code @ConfigEntry.comment()} value into one {@link List} element per line, in
     * declaration order, ready for {@link org.bukkit.configuration.ConfigurationSection}'s
     * comment-writing API. No blank leading element is added (Claude's Discretion, D-07) - it
     * would produce a diff on every regenerated file for a purely cosmetic gain, contrary to
     * D-01's touch-as-little-as-possible posture.
     *
     * @param comment the raw {@code comment()} attribute value, possibly empty
     * @return one element per line, or an empty list if {@code comment} is blank
     */
    private static List<String> splitComment(String comment) {
        if (comment == null || comment.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(comment.split("\n"));
    }

    /**
     * Updates the properties of the configuration entity.
     * <p>
     * The field traversal and path-derivation must match {@code init()} / {@code save()} /
     * {@code reload()} exactly, or a write can silently no-op: all four methods walk
     * {@code @ConfigEntry} fields, and this method previously diverged from them in two ways --
     * using {@code getDeclaredFields()} instead of {@link ReflectionUtil#getFields(Class)}
     * (missing inherited fields), and not normalizing an empty {@code path} to the field name.
     * The second divergence was the more hidden one: omitting {@code path} on
     * {@code @ConfigEntry} is a supported style, {@code init}/{@code save} read and write it by
     * field name, and {@link #toJsonObject()} also sends it to the panel by field name -- but
     * this method was looking for an empty-string key in the JSON, which never exists, so the
     * field was silently skipped while persistence still ran and the caller still
     * received success.
     * <p>
     * Since 6.3.0 (SILENT-14, closing CR-01) this method validates the full post-update field
     * state - the same {@link #validateFields()} {@link #init(UltiToolsPlugin)}/{@link
     * #reload()} already use - before either document mutation or file persistence
     * runs. A violating value refuses with {@link ConfigurationException} instead of being
     * written: the operator's file is left byte-identical, and every field this call touched is
     * restored to the value it held before the call, so memory never disagrees with disk (D-01,
     * D-04). Unlike {@link #reload()}, this entity keeps running after a refusal, so its
     * in-memory state must not be left holding a rejected value.
     *
     * @param jsonObject the JSON object containing the new properties
     * @throws IOException            if an I/O error occurs
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if the post-update field state violates a {@code @Range}/
     *                                 {@code @NotEmpty}/{@code @Size}/{@code @Pattern} constraint
     *                                 - the file is not written and touched fields are restored
     */
    public void updateProperties(JsonObject jsonObject) throws IOException {
        synchronized (this) {
            if (lastLoadUnparseable) { return; }
            List<Field> touchedFields = new ArrayList<>();
            List<Object> previousValues = new ArrayList<>();
            try {
                Map<Field, List<List<String>>> leaves = applyAndValidate(jsonObject, touchedFields, previousValues);
                PreparedSave prepared = prepareSave(touchedFields, leaves);
                if (prepared.changed) { write(prepared.candidate); }
                acknowledgeSave(prepared);
            } catch (RuntimeException failure) {
                restoreFields(touchedFields, previousValues); throw failure;
            }
        }
    }

    /** Prepares one panel entity without acknowledging or replacing its file.
     * @param properties proposed panel values
     * @return manager-owned write, or null for a protected entity
     * @throws IOException if reading or staging fails
     */
    @ApiStatus.Internal
    public final PanelWrite preparePanelWrite(JsonObject properties) throws IOException {
        synchronized (this) {
            if (lastLoadUnparseable) { return null; }
            PanelCheckpoint before = new PanelCheckpoint();
            try {
                List<Field> touched = new ArrayList<>();
                Map<Field, List<List<String>>> leaves = applyAndValidate(properties, touched, new ArrayList<>());
                PreparedSave prepared = prepareSave(touched, leaves);
                java.nio.file.Path target = ultiToolsPlugin.getConfigFile(configFilePath).toPath();
                byte[] original = Files.exists(target) ? Files.readAllBytes(target) : null;
                AtomicConfigWriter.StagedWrite staged = null;
                if (prepared.changed) {
                    Files.createDirectories(target.toAbsolutePath().getParent());
                    staged = AtomicConfigWriter.stage(target, prepared.candidate.render());
                }
                Map<Field, Object> bound = new LinkedHashMap<>();
                for (Field field : touched) { bound.put(field, ReflectionUtil.getFieldValue(this, field)); }
                before.restore();
                return new PanelWrite(before, prepared, target, original, staged, bound);
            } catch (IOException | RuntimeException failure) {
                before.restore(); throw failure;
            }
        }
    }

    /** Complete entity state before a panel candidate, including raw and effective acknowledgments. */
    private final class PanelCheckpoint {
        private final List<Field> fields = configEntryFields();
        private final List<Object> values = new ArrayList<>();
        private final ConfigDocument oldDocument = document;
        private final Map<Field, Object> baseline = savedSnapshot == null ? null : new LinkedHashMap<>(savedSnapshot);
        private final Map<String, Object> presence = lastLoadedPresence;
        private final Map<Field, RawEntry> raw = new LinkedHashMap<>(acknowledgedRaw);
        private final String fingerprint = savedFileFingerprint;
        private final boolean protectedFile = lastLoadUnparseable;
        private final boolean incomplete = lastInitIncomplete;
        private final boolean comments = pendingCommentWrite;
        private final boolean deferred = deferInitialization;
        private final PendingInitialization initialization = pendingInitialization;
        private final Set<String> warningKeys = new java.util.LinkedHashSet<>(warnedCommentKeys);
        private PanelCheckpoint() {
            for (Field field : fields) { values.add(ReflectionUtil.getFieldValue(AbstractConfigEntity.this, field)); }
        }
        private void restore() {
            restoreFields(fields, values);
            document = oldDocument; savedSnapshot = baseline; lastLoadedPresence = presence;
            acknowledgedRaw.clear(); acknowledgedRaw.putAll(raw); savedFileFingerprint = fingerprint;
            lastLoadUnparseable = protectedFile; lastInitIncomplete = incomplete;
            pendingCommentWrite = comments; deferInitialization = deferred; pendingInitialization = initialization;
            warnedCommentKeys.clear(); warnedCommentKeys.addAll(warningKeys);
        }
    }

    /** Narrow manager coordination for one staged entity; not a module transaction API. */
    @ApiStatus.Internal
    public final class PanelWrite {
        private final PanelCheckpoint before;
        private final PreparedSave prepared;
        private final java.nio.file.Path target;
        private final byte[] original;
        private final AtomicConfigWriter.StagedWrite staged;
        private boolean attempted;
        private final Map<Field, Object> bound;
        private PanelWrite(PanelCheckpoint before, PreparedSave prepared, java.nio.file.Path target,
                byte[] original, AtomicConfigWriter.StagedWrite staged, Map<Field, Object> bound) {
            this.before = before; this.prepared = prepared; this.target = target;
            this.original = original; this.staged = staged; this.bound = bound;
        }
        /** @throws IOException if the existing atomic writer cannot replace this file */
        public void commit() throws IOException {
            if (staged != null) { attempted = true; staged.commit(); }
        }
        /** Acknowledges only after every manager-owned replacement succeeds. */
        public void acknowledge() {
            synchronized (AbstractConfigEntity.this) {
                for (Map.Entry<Field, Object> entry : bound.entrySet()) {
                    ReflectionUtil.setFieldValue(AbstractConfigEntity.this, entry.getKey(), entry.getValue());
                }
                acknowledgeSave(prepared);
            }
        }
        /** Restores an attempted target and all entity state; always discards its staged file.
         * @throws IOException if physical recovery fails
         */
        public void rollback() throws IOException {
            synchronized (AbstractConfigEntity.this) {
                try {
                    if (attempted) {
                        if (original == null) { Files.deleteIfExists(target); }
                        else { AtomicConfigWriter.write(target, new String(original, java.nio.charset.StandardCharsets.UTF_8)); }
                    }
                } finally {
                    before.restore(); discard();
                }
            }
        }
        /** Releases a staged file that has not been committed. */
        public void discard() {
            if (staged != null && !staged.discard()) {
                LOGGER.warning("Could not remove staged configuration file for " + configFilePath);
            }
        }
    }

    private void restoreFields(List<Field> fields, List<Object> values) {
        for (int i = 0; i < fields.size(); i++) { ReflectionUtil.setFieldValue(this, fields.get(i), values.get(i)); }
    }

    /**
     * Validates that applying {@code jsonObject}'s touched fields would NOT violate any
     * {@code @Range}/{@code @NotEmpty}/{@code @Size}/{@code @Pattern} constraint, without
     * persisting anything to disk or leaving any field changed (#358 Part 2).
     * <p>
     * Used by {@code ConfigManager.loadFromJson(String)} to validate every entity touched by a
     * multi-file panel-pushed batch BEFORE persisting any of them - a refusal on entity N must
     * not leave entities 1..N-1 already written to disk. This method always restores every
     * field it touched, whether validation passes or fails; the caller is expected to call
     * {@link #updateProperties(JsonObject)} itself afterward (which re-validates - cheap on the
     * documented idiom - and persists) once every entity in its own batch has passed this check.
     *
     * @param jsonObject the JSON object containing the candidate new properties
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if the candidate post-update field state would violate a
     *                                 validation constraint
     */
    public void validateProposedProperties(JsonObject jsonObject) {
        synchronized (this) {
            PanelCheckpoint before = new PanelCheckpoint();
            try { applyAndValidate(jsonObject, new ArrayList<>(), new ArrayList<>()); }
            finally { before.restore(); }
        }
    }

    /**
     * Applies every {@code jsonObject} field this class declares via {@code @ConfigEntry} to
     * this instance, then validates the resulting state via {@link #validateFields()}. Never
     * itself persists or restores anything - callers decide what happens next. {@code
     * touchedFieldsOut}/{@code previousValuesOut} are populated even when {@link
     * #validateFields()} throws, so a caller can still restore exactly what this call touched.
     *
     * @param jsonObject        the JSON object containing the candidate new properties
     * @param touchedFieldsOut  populated, in application order, with every field this call applied
     * @param previousValuesOut populated in the same order with each field's pre-call value
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if the post-update field state violates a constraint
     */
    private Map<Field, List<List<String>>> applyAndValidate(JsonObject jsonObject, List<Field> touchedFieldsOut, List<Object> previousValuesOut) {
        Map<Field, Object> proposed = new LinkedHashMap<>();
        Map<Field, List<List<String>>> leaves = new LinkedHashMap<>();
        Set<Field> whole = new java.util.LinkedHashSet<>();
        List<String> refused = new ArrayList<>();
        JsonObject displayed = toJsonObject();
        for (Map.Entry<String, JsonElement> edit : jsonObject.entrySet()) {
            String path = edit.getKey();
            Object raw = jsonToPlain(edit.getValue());
            if (displayed.has(path) && PlainData.plainEquals(jsonToPlain(displayed.get(path)), raw)) { continue; }
            Field owner = null;
            for (Field field : configEntryFields()) {
                String entry = fieldPath(field);
                if (path.equals(entry)) { owner = field; break; }
                if (path.startsWith(entry + ".") && (owner == null || entry.length() > fieldPath(owner).length())) {
                    owner = field;
                }
            }
            if (owner == null) { refused.add("'" + path + "': no declared entry"); continue; }
            if (path.equals(fieldPath(owner))) {
                proposed.put(owner, raw); leaves.remove(owner); whole.add(owner); continue;
            }
            Object source = document == null ? null : document.get(keys(owner));
            List<List<String>> matches = new ArrayList<>();
            matchMapPaths(source, path.substring(fieldPath(owner).length() + 1), new ArrayList<>(), matches);
            if (matches.size() != 1) {
                refused.add("'" + path + "': " + (matches.isEmpty() ? "not found" : "ambiguous " + matches));
                continue;
            }
            Object tree = proposed.containsKey(owner) ? proposed.get(owner) : plainValue(owner);
            if (!(tree instanceof Map)) { refused.add("'" + path + "': not a map entry"); continue; }
            replaceMapLeaf(tree, matches.get(0), raw);
            proposed.put(owner, tree);
            if (!whole.contains(owner)) { leaves.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(matches.get(0)); }
        }
        Map<Field, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<Field, Object> proposal : proposed.entrySet()) {
            Field field = proposal.getKey();
            try {
                ConversionResult<Object> result = registry().fromPlainResult(proposal.getValue(), declaredType(field),
                        configFilePath, keys(field), field.getAnnotation(ConfigEntry.class));
                if (!result.failures().isEmpty()) {
                    for (ConversionFailure failure : result.failures()) {
                        refused.add("'" + String.join(".", failure.path()) + "': invalid panel value for "
                                + typeName(failure.declaredType()));
                    }
                } else {
                    converted.put(field, result.value());
                }
            } catch (ConversionException failure) {
                refused.add("'" + String.join(".", failure.path()) + "': invalid panel value for "
                        + typeName(failure.declaredType()));
            }
        }
        if (!refused.isEmpty()) { throw new ConfigurationException("File " + configFilePath + ": " + String.join("; ", refused)); }
        for (Map.Entry<Field, Object> entry : converted.entrySet()) {
            Field field = entry.getKey(); field.setAccessible(true);
            touchedFieldsOut.add(field); previousValuesOut.add(ReflectionUtil.getFieldValue(this, field));
            ReflectionUtil.setFieldValue(this, field, entry.getValue());
        }
        validateFields();
        validateBindingRanges(touchedFieldsOut);
        return leaves;
    }

    private static Object mapLeaf(Object tree, List<String> path) {
        Object value = tree;
        for (String key : path) {
            if (!(value instanceof Map)) { return null; }
            value = ((Map<?, ?>) value).get(key);
        }
        return value;
    }

    private static boolean mapContains(Object tree, List<String> path) {
        Object value = tree;
        for (String key : path) {
            if (!(value instanceof Map) || !((Map<?, ?>) value).containsKey(key)) { return false; }
            value = ((Map<?, ?>) value).get(key);
        }
        return true;
    }

    private static Object patchedMap(Object tree, List<String> path, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (tree instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) tree).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        String key = path.get(0);
        copy.put(key, path.size() == 1 ? value : patchedMap(copy.get(key), path.subList(1, path.size()), value));
        return copy;
    }

    private static void matchMapPaths(Object node, String remaining, List<String> prefix, List<List<String>> matches) {
        if (!(node instanceof Map)) { return; }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
            String key = String.valueOf(entry.getKey());
            List<String> path = new ArrayList<>(prefix); path.add(key);
            if (remaining.equals(key)) { matches.add(path); }
            else if (remaining.startsWith(key + ".")) {
                matchMapPaths(entry.getValue(), remaining.substring(key.length() + 1), path, matches);
            }
        }
    }

    @SuppressWarnings("unchecked") // Plain-data maps have String keys, enforced by the converter boundary.
    private static void replaceMapLeaf(Object tree, List<String> path, Object value) {
        Map<String, Object> parent = (Map<String, Object>) tree;
        for (int i = 0; i < path.size() - 1; i++) { parent = (Map<String, Object>) parent.get(path.get(i)); }
        parent.put(path.get(path.size() - 1), PlainData.copy(value));
    }

    private static Object jsonToPlain(JsonElement element) {
        if (element.isJsonNull()) { return null; }
        if (element.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                result.put(entry.getKey(), jsonToPlain(entry.getValue()));
            }
            return result;
        }
        if (element.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) { result.add(jsonToPlain(child)); }
            return result;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) { return primitive.getAsBoolean(); }
        if (primitive.isString()) { return primitive.getAsString(); }
        try { return primitive.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException failure) { return primitive.getAsDouble(); }
    }

    /**
     * Refuses a panel write that sets a key bound by a config binding (#531) outside that binding's
     * range, in the same shape as a {@code @Range} violation. Only the fields this write touched are
     * checked: a bound field left as it was may hold a value a refused reload kept out of use, and
     * editing another key must not fail because of it.
     *
     * @param touchedFields the fields this write applied
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 naming each bound key, its value and its range
     */
    private void validateBindingRanges(List<Field> touchedFields) {
        if (bindingRanges.isEmpty()) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (Field field : touchedFields) {
            ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
            String path = annotation.path().isEmpty() ? field.getName() : annotation.path();
            Map<String, Predicate<Long>> ranges = bindingRanges.get(path);
            if (ranges == null) {
                continue;
            }
            Object value = ReflectionUtil.getFieldValue(this, field);
            Long seconds = value instanceof Number ? ((Number) value).longValue() : null;
            for (Map.Entry<String, Predicate<Long>> range : ranges.entrySet()) {
                if (!range.getValue().test(seconds)) {
                    violations.add(String.format("key '%s' value %s is out of its binding's range: %s",
                            path, value, range.getKey()));
                }
            }
        }
        if (!violations.isEmpty()) {
            String moduleName = ultiToolsPlugin != null ? ultiToolsPlugin.getPluginName() : this.getClass().getSimpleName();
            throw ConfigurationException.validationFailed(moduleName, configFilePath, violations);
        }
    }

    /**
     * Converts the configuration entity to a JSON object.
     *
     * @return the JSON object representation of the configuration entity
     */
    public JsonObject toJsonObject() {
        synchronized (this) {
            JsonObject json = new JsonObject();
            if (document != null) { addLeaves(json, "", document.toPlain(), new Gson()); }
            return json;
        }
    }

    private static void addLeaves(JsonObject result, String prefix, Map<?, ?> values, Gson gson) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) { addLeaves(result, path, (Map<?, ?>) entry.getValue(), gson); }
            else { result.add(path, gson.toJsonTree(entry.getValue())); }
        }
    }

    /**
     * Gets the comments of the configuration entity.
     *
     * @return a JSON object containing the comments
     */
    public JsonObject getComments() {
        JsonObject jsonObject = new JsonObject();
        // Same two alignments as updateProperties: walk the full field tree, normalize an empty
        // path to the field name. Without normalizing, a field with no path would have its
        // comment filed under the "" key, while toJsonObject() sends values by field name - the
        // panel's two sides would never match up and that comment would never display.
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                jsonObject.addProperty(path, panelComment(field));
            }
        }
        return jsonObject;
    }
    
    // ==================== Configuration Validation ====================

    /**
     * Validates all fields annotated with validation annotations (@Range, @NotEmpty, @Size, @Pattern).
     * A violation refuses this config's module instead of rewriting the value - the operator's
     * file is never modified (D-01). Every violating field is collected and named in a single
     * refusal; the module author must fix the value(s) on disk and restart.
     *
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if any field violates its validation constraint, or if this
     *                                 config class cannot be constructed through either of the
     *                                 two framework-supported idioms (D-03)
     */
    protected void validateFields() {
        List<Field> configFields = configEntryFields();
        if (configFields.isEmpty()) {
            return;
        }

        // Proves this class still supports one of the two framework idioms (D-02/D-03) - the
        // constructed instance itself is discarded, only its existence matters here.
        ensureConstructable();

        List<String> violations = new ArrayList<>();
        for (Field field : configFields) {
            field.setAccessible(true);
            try {
                String violation = validateSingleField(field);
                if (violation != null) {
                    violations.add(violation);
                }
            } catch (IllegalAccessException e) {
                LOGGER.log(Level.WARNING, "Failed to validate field: " + field.getName(), e);
            }
        }

        if (!violations.isEmpty()) {
            String moduleName = ultiToolsPlugin != null ? ultiToolsPlugin.getPluginName() : this.getClass().getSimpleName();
            throw ConfigurationException.validationFailed(moduleName, configFilePath, violations);
        }
    }

    /**
     * Constructs and discards an instance of this config class through the same two-step
     * fallback {@code ConfigManager.registerAll} uses at registration time - a {@code (String)}
     * constructor first, then an accessible no-arg constructor. Existence, not the constructed
     * value, is what this proves: the framework needs every registered config class to still be
     * buildable through one of its two documented idioms (D-02). Neither resolving is a genuine
     * config-class error (D-03).
     *
     * @throws ConfigurationException if neither constructor resolves
     */
    private void ensureConstructable() {
        constructSibling();
    }

    /**
     * Constructs a fresh instance of this config class through the {@code (String)} constructor, or
     * failing that the no-arg constructor - the two idioms {@link #ensureConstructable()} proves -
     * for the existing validation constructability precondition only.
     *
     * @return a new, uninitialized instance of this entity's class
     * @throws ConfigurationException if neither constructor resolves
     */
    private AbstractConfigEntity constructSibling() {
        try {
            try {
                return this.getClass().getDeclaredConstructor(String.class).newInstance(configFilePath);
            } catch (NoSuchMethodException e) {
                // Try no-arg constructor (class may hardcode path via super() call)
                return this.getClass().getDeclaredConstructor().newInstance();
            }
        } catch (InstantiationException | InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            throw ConfigurationException.unconstructable(this.getClass().getName(), e);
        }
    }

    /**
     * Describes the single validation constraint {@code field} violates, if any.
     *
     * @param field the field to check, already made accessible
     * @return a violation description naming the field, its actual value (redacted for
     *         {@code @Pattern} on a secret-shaped field name, T-04-04) and the broken constraint,
     *         or {@code null} if the field's value satisfies its annotations
     */
    private String validateSingleField(Field field) throws IllegalAccessException {
        Object value = field.get(this);

        if (isRangeViolation(field, value)) {
            Range range = field.getAnnotation(Range.class);
            return String.format("field '%s' value %s is out of range [%s, %s]",
                    field.getName(), value, range.min(), range.max());
        }
        if (isNotEmptyViolation(field, value)) {
            return String.format("field '%s' must not be empty", field.getName());
        }
        if (isSizeViolation(field, value)) {
            Size size = field.getAnnotation(Size.class);
            int len = getValueLength(value);
            return String.format("field '%s' size %d is out of bounds [%d, %d]",
                    field.getName(), len, size.min(), size.max());
        }
        if (isPatternViolation(field, value)) {
            Pattern pattern = field.getAnnotation(Pattern.class);
            String displayValue = isSecretShapedFieldName(field.getName()) ? "<redacted>" : "'" + value + "'";
            return String.format("field '%s' value %s does not match pattern '%s'",
                    field.getName(), displayValue, pattern.regex());
        }
        return null;
    }

    /**
     * Whether a field name looks like it stores a secret. Only {@code @Pattern} violations echo
     * an arbitrary string value; {@code @Range}/{@code @Size} violations always echo a number or
     * a length, and {@code @NotEmpty} violations are empty by definition, so neither can leak a
     * secret verbatim (T-04-04).
     * <p>
     * Widened in 6.3.0 (04-REVIEW.md WR-03) to also cover {@code key}/{@code auth}/
     * {@code private}/{@code cert}, accepting the resulting false positives (a field merely
     * named e.g. {@code publicKey} is redacted too) as the safer default, per Phase 2 D-15's
     * fail-closed preference. The reason for the widening is new, not cosmetic: before the
     * write-path refusal added by this same 6.3.0 change, a {@code @Pattern} refusal message
     * went only to the local console; now both remote config-write handlers forward it
     * verbatim to UltiPanel over the WebSocket (T-04-56), so a name-heuristic miss here leaks
     * the server, not just the console.
     */
    private boolean isSecretShapedFieldName(String fieldName) {
        String lower = fieldName.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.contains("credential")
                || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("key") || lower.contains("auth")
                || lower.contains("private") || lower.contains("cert");
    }

    private boolean isRangeViolation(Field field, Object value) {
        Range range = field.getAnnotation(Range.class);
        if (range == null || !(value instanceof Number)) return false;
        double num = ((Number) value).doubleValue();
        return num < range.min() || num > range.max();
    }

    private boolean isNotEmptyViolation(Field field, Object value) {
        return field.getAnnotation(NotEmpty.class) != null
                && (value == null || value.toString().trim().isEmpty());
    }

    private boolean isSizeViolation(Field field, Object value) {
        Size size = field.getAnnotation(Size.class);
        if (size == null || value == null) return false;
        int len = getValueLength(value);
        return len >= 0 && (len < size.min() || len > size.max());
    }

    private boolean isPatternViolation(Field field, Object value) {
        Pattern pattern = field.getAnnotation(Pattern.class);
        return pattern != null && value instanceof String && !((String) value).matches(pattern.regex());
    }

    private int getValueLength(Object value) {
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
        if (value instanceof String) return ((String) value).length();
        return -1;
    }

    // ==================== Configuration Change Listener Support ====================
    
    /**
     * Adds a configuration change listener.
     * The listener will be notified when the configuration is reloaded.
     *
     * @param listener the listener to add
     */
    public void addChangeListener(ConfigChangeListener listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }
    
    /**
     * Removes a configuration change listener.
     *
     * @param listener the listener to remove
     */
    public void removeChangeListener(ConfigChangeListener listener) {
        changeListeners.remove(listener);
    }
    
    /**
     * Removes all configuration change listeners.
     */
    public void clearChangeListeners() {
        changeListeners.clear();
    }
    
    /**
     * Gets the number of registered change listeners.
     *
     * @return the number of listeners
     */
    public int getChangeListenerCount() {
        return changeListeners.size();
    }
    
    /**
     * Notifies all registered listeners about the configuration change.
     * Individual listener exceptions do not affect other listeners.
     */
    protected void notifyChangeListeners() {
        for (ConfigChangeListener listener : changeListeners) {
            try {
                listener.onConfigReload(this);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, 
                    "Config change listener failed for " + this.getClass().getSimpleName(), e);
            }
        }
    }
    
    /**
     * Reloads the configuration from file and notifies all listeners.
     *
     * @throws IOException if an I/O error occurs
     */
    public void reload() throws IOException {
        if (!com.ultikits.ultitools.manager.ConfigManager.permitsConfigThread(ultiToolsPlugin, "reload " + configFilePath)) { return; }
        if (ultiToolsPlugin == null) { throw new IllegalStateException("Config not initialized. Call init() first."); }
        synchronized (this) {
            registry().checkEntityFields(getClass(), ultiToolsPlugin.getPluginName(), configFilePath);
            load(false);
        }
        notifyChangeListeners();
    }
}
