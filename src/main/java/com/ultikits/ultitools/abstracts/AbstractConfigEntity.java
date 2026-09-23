package com.ultikits.ultitools.abstracts;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.ApiStatus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
 * Abstract class representing a configuration entity.
 * <p>
 * Precondition for subclasses (#363, counts re-measured for #510): the constructor must be cheap
 * and free of side effects. For any class declaring at least one {@code @ConfigEntry} field, the
 * framework constructs and discards throwaway instances of this class on the paths below, and a
 * constructor that opens a file, registers a listener, or otherwise does real work pays that cost
 * again on every one of those events, purely to be thrown away:
 * <ul>
 * <li>{@link #validateFields()} - reached from {@link #init(UltiToolsPlugin)}, {@link #reload()},
 * {@link #updateProperties(com.google.gson.JsonObject)} and {@link
 * #validateProposedProperties(com.google.gson.JsonObject)} - constructs ONE instance via {@link
 * #ensureConstructable()} to prove the class still supports one of the framework's two documented
 * construction idioms. The construction happens before the accept/refuse decision, not only when a
 * panel write is ultimately accepted.</li>
 * <li>{@link #canonicalize(String)} constructs ONE instance, which it uses to read the text being
 * canonicalized (see that method). It runs once in {@code takeSnapshot()} - that is, on every
 * {@link #init(UltiToolsPlugin)}, {@link #reload()}, successful {@link #save()} and successful
 * {@link #updateProperties(JsonObject)} - and once in {@link #isModifiedSinceSnapshot()}, the
 * shutdown check.</li>
 * </ul>
 * Measured totals per operation: {@code init()} 2, {@code reload()} 2, a panel write 2, {@link
 * #save()} 1, and the shutdown check 1 per configuration (2 when it goes on to save). {@link
 * #save()} and the shutdown check construct nothing before 6.3.0 and are the two paths a module
 * author is most likely to consider exempt. A class with zero {@code @ConfigEntry} fields
 * constructs nothing on any of them. The documented {@code super(configFilePath)}-only idiom is
 * unaffected - each construction is a single, trivial reflective call.
 * <p>
 * Saved-state snapshot (#510, since 6.3.0): every entity remembers what its file on disk holds as
 * of the last time the framework read or wrote it - after {@link #init(UltiToolsPlugin)} (including
 * a first-boot defaults write), after {@link #reload()}, after every successful {@link #save()},
 * and after every successful {@link #updateProperties(JsonObject)} - together with a SHA-256
 * fingerprint of the file at that same point. The snapshot is derived from the file's text, plus
 * this class's declared defaults for keys the text does not contain, and never from this instance's
 * fields, so no unwritten in-memory change can ever be recorded as saved. The
 * shutdown save ({@code ConfigManager#saveAll()}) writes only the entities for which {@link
 * #isModifiedSinceSnapshot()} is {@code true}, so an operator's edit to a file whose configuration
 * no module code changed survives a restart. An explicit {@link #save()} call still writes
 * unconditionally.
 * <p>
 * Thread safety (#510): the framework's own read, write, snapshot and comparison paths - {@link
 * #init(UltiToolsPlugin)}'s and {@link #reload()}'s load, {@link #save()}, {@link
 * #updateProperties(JsonObject)}, {@link #validateProposedProperties(JsonObject)} and the two
 * snapshot checks - run under this entity's own monitor, which {@code ConfigManager#saveAll()} also
 * holds across its check-then-save of this entity. A panel write arriving on the WebSocket thread
 * and the shutdown save therefore each see the other's whole effect or none of it. Field setters in
 * module code are not synchronized by the framework.
 * <p>
 * That monitor is held across file I/O and across the constructions listed above, so it is also a
 * new direction of blocking: a panel write on the WebSocket thread holds it across validation, the
 * write and the snapshot, and a module calling {@link #save()} on the server thread waits for that
 * span. Both are bounded by small configuration files and by the cheap-constructor precondition.
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
    private YamlConfiguration config;
    /**
     * The canonical form (see {@link #canonicalize(String)}) of the text on disk as of the last
     * snapshot point (#510), or {@code null} before the first successful snapshot - which {@link
     * #isModifiedSinceSnapshot()} treats as modified, so shutdown keeps the pre-#510 "save it"
     * behaviour for an entity that was never in sync with its file. A serialized string, never a
     * reference to or shallow copy of the field values: a module that mutates a collection field in
     * place (UltiChat's auto-reply {@code rules} map) must still be detected as changed.
     */
    @Getter(AccessLevel.NONE)
    private volatile String savedSnapshot;
    /**
     * Fingerprint of the file on disk as of the last snapshot point (#510), see {@link
     * #fingerprintOf(File)}; {@code null} before the first snapshot.
     */
    @Getter(AccessLevel.NONE)
    private volatile String savedFileFingerprint;
    /**
     * Whether the last attempt to read this configuration's file failed to parse (#510). While set,
     * the framework does not know what the file holds, so {@link #isModifiedSinceSnapshot()} reports
     * {@code false} and the shutdown save leaves the file alone rather than overwriting an operator's
     * broken file with the in-memory state. Cleared by the next successful load or save.
     */
    @Getter(AccessLevel.NONE)
    private volatile boolean lastLoadUnparseable;
    /**
     * Whether the last {@link #init} did not run to the end of its validation -- set when it starts,
     * cleared only after {@link #validateFields()} succeeds. A caller that catches the
     * {@code IOException} of a failed write-back (as {@code ConfigManager} does) is otherwise left
     * with an entity whose fields hold the file's new values unvalidated (#533). Kept on the entity
     * so that it lives and dies with it; nothing else has to remember to clear it (Codex round 3 on
     * #536).
     */
    @Getter(AccessLevel.NONE)
    private volatile boolean lastInitIncomplete;

    /**
     * Constructor for AbstractConfigEntity.
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Saves the configuration to the file.
     * <p>
     * An explicit call always writes, whether or not anything changed since the last snapshot. Only
     * the shutdown save ({@code ConfigManager#saveAll()}) is conditional on {@link
     * #isModifiedSinceSnapshot()} (#510). A successful write refreshes the snapshot; a failed write
     * leaves the previous snapshot in place, so the entity stays modified and the shutdown save
     * retries it.
     *
     * @throws IOException if an I/O error occurs
     */
    public void save() throws IOException {
        synchronized (this) {
            applyFieldsTo(config);
            config.save(new File(ultiToolsPlugin.getConfigFolder() + File.separator + configFilePath));
            // #510: an explicit save is a caller's deliberate act and always writes, so the file now
            // holds what the framework just wrote - whatever state it was in before.
            lastLoadUnparseable = false;
            takeSnapshot();
        }
    }

    /**
     * Copies every non-null {@code @ConfigEntry} field, serialized through its declared parser, onto
     * {@code target}. The one serialization path shared by {@link #save()}, {@link
     * #renderSaveText()} and {@link #canonicalizeOnce(String, AbstractConfigEntity, java.util.List)}, so the shutdown comparison renders
     * exactly what {@code save()} would write (#510).
     *
     * @param target the configuration to write the serialized field values into
     */
    @SuppressWarnings("unchecked")
    private void applyFieldsTo(YamlConfiguration target) {
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (!field.isAnnotationPresent(ConfigEntry.class)) {
                continue;
            }
            field.setAccessible(true);
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            Object fieldValue = ReflectionUtil.getFieldValue(this, field);
            if (fieldValue == null) {
                continue;
            }
            Object serialized = ReflectionUtil.newInstance(annotation.parser()).serialize(fieldValue);
            target.set(path, serialized);
        }
    }

    /**
     * Renders the exact text {@link #save()} would write right now, without writing it and without
     * touching the live {@link #config} (which {@link #toJsonObject()} still reports to the panel).
     * The live configuration is copied through its own YAML text - comments included - and the
     * fields are applied to the copy through {@link #applyFieldsTo(YamlConfiguration)}, the same
     * path {@code save()} uses.
     *
     * @return the rendered text, or {@code null} if the live configuration's own text cannot be
     *         parsed back
     */
    private String renderSaveText() {
        YamlConfiguration copy = new YamlConfiguration();
        copy.options().parseComments(true);
        try {
            copy.loadFromString(config.saveToString());
        } catch (InvalidConfigurationException e) {
            return null;
        }
        applyFieldsTo(copy);
        return copy.saveToString();
    }

    /**
     * Brings a configuration text to the form this entity would give it after reading it and saving
     * it again (#510): the text is parsed, a throwaway instance of this class (one per call, see the
     * construction counts in this class's own javadoc) loads every present {@code @ConfigEntry} key
     * through its parser exactly as {@link #init(UltiToolsPlugin)} does
     * (absent keys keep that instance's declared defaults), and the instance's fields are applied back
     * onto the parsed text through {@link #applyFieldsTo(YamlConfiguration)}. Two passes make the
     * result stable, because a default filled in for an absent key by the first pass is re-read
     * through the parser by the second.
     * <p>
     * Both sides of the shutdown comparison go through this: the snapshot canonicalizes the text on
     * disk, {@link #isModifiedSinceSnapshot()} canonicalizes what {@link #save()} would write now.
     * Parser quirks therefore cancel out (for example {@code DefaultConfigParser} reading the
     * integers of a YAML list back as strings), while any in-memory value the file does not hold -
     * a field a partial panel write did not touch, a key missing from a reloaded file - still
     * differs.
     *
     * @param text a YAML text, possibly {@code null}
     * @return the canonical text, or {@code null} if {@code text} is {@code null} or cannot be parsed
     */
    private String canonicalize(String text) {
        if (text == null) {
            return null;
        }
        List<Field> configFields = configEntryFields();
        if (configFields.isEmpty()) {
            // Nothing to read into an instance, so do not construct one. A class with no
            // @ConfigEntry field never reaches validateFields()' constructability check either, so
            // it may legitimately have no constructor this class can resolve; constructing one here
            // would fail, clear the snapshot, and make the shutdown save rewrite an untouched file.
            return renderParsed(text);
        }
        // One throwaway instance for both passes, not one each: pass one writes a value for every
        // @ConfigEntry key, so pass two overwrites every field it reads and cannot see anything pass
        // one left behind. Reusing it ACROSS calls would not be safe - the declared defaults it
        // carries for absent keys are exactly what the comparison relies on.
        AbstractConfigEntity probe = constructSibling();
        return canonicalizeOnce(canonicalizeOnce(text, probe, configFields), probe, configFields);
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
     * Parses {@code text} and renders it back, with no field applied - the whole of {@link
     * #canonicalize(String)} for a class that declares no {@code @ConfigEntry} field.
     *
     * @param text a YAML text
     * @return the re-rendered text, or {@code null} if it cannot be parsed
     */
    private String renderParsed(String text) {
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.options().parseComments(true);
        try {
            parsed.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        return parsed.saveToString();
    }

    /**
     * One pass of {@link #canonicalize(String)}.
     *
     * @param text         a YAML text, possibly {@code null}
     * @param probe        the throwaway instance this pass reads {@code text} into
     * @param configFields this class's {@code @ConfigEntry} fields, never empty
     * @return the text after one read-and-render pass, or {@code null} if it cannot be parsed
     */
    private String canonicalizeOnce(String text, AbstractConfigEntity probe, List<Field> configFields) {
        if (text == null) {
            return null;
        }
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.options().parseComments(true);
        try {
            parsed.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        for (Field field : configFields) {
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path();
            if (path.isEmpty()) {
                path = field.getName();
            }
            Object configValue = parsed.get(path);
            if (configValue != null) {
                ReflectionUtil.setFieldValue(probe, field, readConfigValue(field, annotation, configValue));
            }
        }
        probe.applyFieldsTo(parsed);
        return parsed.saveToString();
    }

    /**
     * Records the snapshot and the on-disk file fingerprint (#510). Called, under this entity's
     * monitor, right after {@link #init(UltiToolsPlugin)} or {@link #reload()} has read the file and
     * right after {@link #save()} or {@link #updateProperties(JsonObject)} has written it - at each of
     * those points the live {@link #config} holds exactly the file's content, and the snapshot is
     * derived from that text alone.
     * <p>
     * Never throws. If the snapshot cannot be computed, it is cleared rather than left stale, so the
     * entity counts as modified and shutdown saves it exactly as it did before #510, and a WARNING
     * says so; a fingerprint that cannot be computed is recorded as {@code "unreadable"}.
     */
    private void takeSnapshot() {
        synchronized (this) {
            try {
                savedFileFingerprint = fingerprintOf(ultiToolsPlugin.getConfigFile(configFilePath));
            } catch (RuntimeException e) {
                savedFileFingerprint = "unreadable";
            }
            String snapshot = null;
            RuntimeException failure = null;
            try {
                snapshot = canonicalize(config.saveToString());
            } catch (RuntimeException e) {
                failure = e;
            }
            savedSnapshot = snapshot;
            if (snapshot == null) {
                LOGGER.log(Level.WARNING, "Cannot snapshot the saved state of " + configFilePath
                        + "; it will be saved at shutdown whether or not it changed", failure);
            }
        }
    }

    /**
     * Whether this entity's current state differs from what its file held when the framework last
     * read or wrote it (#510): the canonical form of the text {@link #save()} would write now is
     * compared with the snapshot. This is what the shutdown save uses to decide whether to write this
     * configuration at all.
     * <p>
     * Framework-internal: this method is called only by {@code ConfigManager#saveAll()} and is
     * {@code public} solely because {@code ConfigManager} lives in another package. Module code
     * should not call it.
     * <p>
     * An entity that was never initialized has nothing to save and reports {@code false}. An entity
     * with no snapshot yet (its first-boot defaults write failed, or its snapshot could not be
     * computed) reports {@code true}, preserving the pre-#510 behaviour of saving it at shutdown. A
     * map field whose entries were only reordered also reports {@code true}, because serialization
     * follows the map's iteration order; saving it is harmless and matches the pre-#510 behaviour.
     * <p>
     * Two cases deliberately report {@code false}. An entity whose file failed to parse the last
     * time it was read ({@link #isLastLoadUnparseable()}) is never written by the shutdown save: the
     * framework does not know what that file holds, so overwriting it with the in-memory state would
     * destroy an operator's broken-but-recoverable file. And a key the file does not contain whose
     * in-memory value equals this class's declared default is indistinguishable from the file's own
     * implied state; it is not written at shutdown, and the next load produces the same value anyway.
     *
     * @return {@code true} if the shutdown save should write this configuration
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean isModifiedSinceSnapshot() {
        synchronized (this) {
            if (config == null || ultiToolsPlugin == null || lastLoadUnparseable) {
                return false;
            }
            String snapshot = savedSnapshot;
            if (snapshot == null) {
                return true;
            }
            try {
                return !snapshot.equals(canonicalize(renderSaveText()));
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cannot compare the state of " + configFilePath
                        + " with its snapshot; treating it as changed", e);
                return true;
            }
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
     *         successful load or save has happened since
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
        lastInitIncomplete = true;
        synchronized (this) {
            this.ultiToolsPlugin = ultiToolsPlugin;
            File file = ultiToolsPlugin.getConfigFile(configFilePath);
            config = new YamlConfiguration();
            // D-08: options().parseComments(true) must be set on THIS instance before load() runs -
            // load() reads the option itself (verified via javap against paper-api), so setting it
            // afterward only affects a later save(), not this read. Under
            // -DPaper.parseYamlCommentsByDefault=false an operator's existing comments would
            // otherwise be dropped right here at parse time, and the missing-key branch below would
            // then write them out of their own file - the exact D-01 violation this lane exists to
            // prevent. Explicit, not inherited from the system-property default.
            config.options().parseComments(true);
            lastLoadUnparseable = false;
            try {
                config.load(file);
            } catch (FileNotFoundException ignored) {
                // Mirrors the bare static factory's own behaviour for a missing file: a missing
                // file is the normal "first run" case, not an error - config stays empty and every
                // field below takes the missing-key branch.
            } catch (InvalidConfigurationException e) {
                // #510: the framework does not know what this file holds, so the shutdown save must
                // not write over it. Cleared by the next successful load or save.
                lastLoadUnparseable = true;
                LOGGER.log(Level.SEVERE, "Cannot load " + file, e);
            }
            boolean upToDate = true;
            for (Field field : ReflectionUtil.getFields(this.getClass())) {
                if (field.isAnnotationPresent(ConfigEntry.class)) {
                    field.setAccessible(true);
                    ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                    String path = annotation.path();
                    if (path.isEmpty()) {
                        path = field.getName();
                    }
                    Object configValue = config.get(path);
                    if (configValue != null) {
                        ReflectionUtil.setFieldValue(this, field, readConfigValue(field, annotation, configValue));
                    } else {
                        upToDate = false;
                        config.set(path, ReflectionUtil.getFieldValue(this, field));
                        // D-07/D-09: the key never existed in the operator's file, so writing its
                        // @ConfigEntry comment alongside the value discloses nothing of theirs - this
                        // is D-01's sole sanctioned exception, widened from "silently add a value" to
                        // "silently add a value and its explanation". Never reached on the
                        // already-has-the-key path above, and this is the only comment write in the
                        // whole class.
                        List<String> commentLines = splitComment(annotation.comment());
                        if (!commentLines.isEmpty()) {
                            config.setComments(path, commentLines);
                        }
                    }
                }
            }
            if (!upToDate) {
                config.save(file);
            }
            // #510: config now holds exactly the file's content, including any first-boot defaults
            // write. The snapshot is taken from that text, so a change listener below that changes a
            // value in memory is still seen as a code change by the shutdown save.
            takeSnapshot();
        }

        // Validate fields and reset invalid values to defaults
        validateFields();
        lastInitIncomplete = false;

        // Notify listeners after initialization
        notifyChangeListeners();
    }

    /**
     * The one conversion from a raw YAML value to the value stored in a {@code @ConfigEntry} field:
     * the entry's parser, then {@link #widenToFieldType}. Every place that reads the file into a
     * field goes through here -- {@link #init}, {@link #reload()} and the #510 snapshot probe in
     * {@code canonicalizeOnce} -- so the three cannot drift apart again. Round 2 of #531 gate-1 CR-01
     * found the snapshot probe still unwidened: a {@code Long} field made every snapshot fail, and
     * the shutdown save then overwrote operator edits (the #510 defect, reinstated).
     *
     * @param field      the target {@code @ConfigEntry} field
     * @param annotation its {@code @ConfigEntry}
     * @param raw        the value SnakeYAML returned for the entry's path, never {@code null}
     * @return the value to store in {@code field}
     */
    private static Object readConfigValue(Field field, ConfigEntry annotation, Object raw) {
        Object parsed = ReflectionUtil.newInstance(annotation.parser()).parse(raw);
        return widenToFieldType(field.getType(), parsed);
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
     * field was silently skipped while {@code config.save} still ran and the caller still
     * received success.
     * <p>
     * Since 6.3.0 (SILENT-14, closing CR-01) this method validates the full post-update field
     * state - the same {@link #validateFields()} {@link #init(UltiToolsPlugin)}/{@link
     * #reload()} already use - before either {@code config.set(...)} or {@code config.save(...)}
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
            // Phase one/two: apply touched fields then validate the full post-update state -
            // extracted into applyAndValidate() so #358 Part 2's validateProposedProperties(JsonObject)
            // can share the exact same apply-then-validate contract without persisting.
            List<Field> touchedFields = new ArrayList<>();
            List<Object> previousValues = new ArrayList<>();
            try {
                applyAndValidate(jsonObject, touchedFields, previousValues);
            } catch (RuntimeException e) {
                // The original exception is rethrown unchanged - never wrapped, never converted to
                // IOException, never swallowed.
                for (int i = 0; i < touchedFields.size(); i++) {
                    ReflectionUtil.setFieldValue(this, touchedFields.get(i), previousValues.get(i));
                }
                throw e;
            }

            // Phase three: persist. Only reached once validation has passed. Writes the same
            // Gson-deserialized value the method has always written - not the @ConfigEntry.parser()
            // serialized form save() uses; that asymmetry is pre-existing and out of scope here.
            for (Field field : touchedFields) {
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                config.set(path, ReflectionUtil.getFieldValue(this, field));
            }
            config.save(ultiToolsPlugin.getConfigFile(configFilePath));
            lastLoadUnparseable = false;
            // #510: config holds exactly what was written. Fields this payload did not touch are not in
            // it; the snapshot comes from this text, so an unsaved code change to them stays modified.
            takeSnapshot();
        }
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
        // #510: under the entity monitor, so a concurrent shutdown save never writes a proposed
        // value that this call is about to restore.
        synchronized (this) {
            List<Field> touchedFields = new ArrayList<>();
            List<Object> previousValues = new ArrayList<>();
            try {
                applyAndValidate(jsonObject, touchedFields, previousValues);
            } finally {
                for (int i = 0; i < touchedFields.size(); i++) {
                    ReflectionUtil.setFieldValue(this, touchedFields.get(i), previousValues.get(i));
                }
            }
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
    private void applyAndValidate(JsonObject jsonObject, List<Field> touchedFieldsOut, List<Object> previousValuesOut) {
        Gson gson = new Gson();
        for (Field field : ReflectionUtil.getFields(this.getClass())) {
            if (field.isAnnotationPresent(ConfigEntry.class)) {
                field.setAccessible(true);
                ConfigEntry annotation = field.getAnnotation(ConfigEntry.class);
                String path = annotation.path();
                if (path.isEmpty()) {
                    path = field.getName();
                }
                if (jsonObject.has(path)) {
                    Object configValue = gson.fromJson(jsonObject.get(path), field.getType());
                    if (configValue != null) {
                        touchedFieldsOut.add(field);
                        previousValuesOut.add(ReflectionUtil.getFieldValue(this, field));
                        ReflectionUtil.setFieldValue(this, field, configValue);
                    }
                }
            }
        }
        // Must run before any field write above is persisted - otherwise a refusal would still
        // leave the in-memory YamlConfiguration holding rejected values for a later, unrelated
        // save() to flush.
        validateFields();
    }

    /**
     * Converts the configuration entity to a JSON object.
     *
     * @return the JSON object representation of the configuration entity
     */
    public JsonObject toJsonObject() {
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        Set<String> keys = config.getKeys(true);
        for (String key : keys) {
            if (!config.isConfigurationSection(key)) {
                Object value = config.get(key);
                jsonObject.add(key, gson.toJsonTree(value));
            }
        }
        return jsonObject;
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
                jsonObject.addProperty(path, annotation.comment());
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
     * for {@link #ensureConstructable()} and for the throwaway reader {@link
     * #canonicalizeOnce(String, AbstractConfigEntity, java.util.List)} uses (#510).
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
        if (ultiToolsPlugin == null) {
            throw new IllegalStateException("Config not initialized. Call init() first.");
        }

        synchronized (this) {
            // #357: build the parser and enable comment parsing before load() runs, in the same
            // construct -> parseComments(true) -> load order init() uses above. The bare static
            // factory this used to call parses the file inside itself before returning, so
            // parseComments(true) could never reach that read - a save() or updateProperties() call
            // right after this reload() would then write back a comment-stripped view over the
            // operator's file (D-01).
            File file = ultiToolsPlugin.getConfigFile(configFilePath);
            config = new YamlConfiguration();
            config.options().parseComments(true);
            lastLoadUnparseable = false;
            try {
                config.load(file);
            } catch (FileNotFoundException ignored) {
                // Mirrors init()'s own handling above: a missing file is the normal case, not an
                // error - config stays empty and every field below simply keeps its current value.
            } catch (InvalidConfigurationException e) {
                // #510: same as init() - never write over a file the framework could not read.
                lastLoadUnparseable = true;
                LOGGER.log(Level.SEVERE, "Cannot load " + file, e);
            }

            // Update field values
            for (Field field : ReflectionUtil.getFields(this.getClass())) {
                if (field.isAnnotationPresent(ConfigEntry.class)) {
                    field.setAccessible(true);
                    ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
                    String path = annotation.path();
                    if (path.isEmpty()) {
                        path = field.getName();
                    }
                    Object configValue = config.get(path);
                    if (configValue != null) {
                        ReflectionUtil.setFieldValue(this, field, readConfigValue(field, annotation, configValue));
                    }
                }
            }
            // #510: same snapshot point as init(). A field whose key is absent from the file keeps its
            // in-memory value above, but the snapshot is taken from the file's text, so that value is
            // still seen as unsaved if it differs from what the file implies.
            takeSnapshot();
        }

        // Validate fields and reset invalid values to defaults
        validateFields();

        // Notify listeners
        notifyChangeListeners();
    }
}
