package com.ultikits.ultitools.abstracts;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
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
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;
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
 * unaffected - each construction is a single, trivial reflective call. Since #526, an {@code init()}
 * or {@code reload()} that finds a value it cannot bind constructs one more instance, once per pass,
 * to read the field's declared default.
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
 * Operator's file (D-01): the framework touches the file as little as possible. It has two
 * sanctioned exceptions. (1) A key missing from the file is added with its declared default and its
 * {@code @ConfigEntry} comment. (2) Since 6.3.0 (#542, maintainer decision of 2026-09-29), a comment
 * that is exactly one language key ({@code comment = "{config.demo.limit}"}) is resolved from the
 * owning module's catalogue in the server's current language and written on every framework write of
 * the file - the first-boot defaults write, {@link #save()}, the shutdown save, a panel write, and a
 * load that finds the file's comment on such an entry differs (an upgraded server's first start, a
 * language switch), keys already in the file included. An operator's hand-written comment on such an
 * entry is replaced; values keep their meaning and literal comments, comments on other keys and the
 * header are kept; a load with nothing to change writes nothing, a file that could not be parsed is
 * not rewritten by it, and a comment-only difference is never a change the shutdown save writes. Like
 * every framework write, the rewrite renders the whole file through the YAML writer, which re-lays out
 * hand-formatted YAML and does not write back a comment the configuration API does not keep (one
 * beside a list item).
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
     * Path separator of the view the framework reads a file through only to find map keys that
     * contain {@code '.'} (#553), so that it can tell the operator to rename them. The configuration
     * a module sees, and every value the framework binds, keep {@code '.'} as in 6.2. NUL cannot
     * appear in a YAML key an operator writes.
     */
    private static final char MAP_KEY_SEPARATOR = '\u0000';

    /**
     * A {@code @ConfigEntry} comment that is exactly one language key (#542): after trimming, the
     * whole comment is {@code {key}} with an ASCII key of letters, digits, {@code .}, {@code _} and
     * {@code -}. Any other comment - including one that merely contains {@code {player}} in its text -
     * is literal.
     */
    private static final java.util.regex.Pattern COMMENT_TOKEN =
            java.util.regex.Pattern.compile("\\{([A-Za-z0-9._-]+)\\}");

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
     * The resolved comment lines of every entry whose {@code @ConfigEntry} comment is one language
     * key (#542), by path, as resolved by the last {@link #init}; empty before it. Resolved once per
     * load, so a key missing from the catalogue is reported once, not at every save.
     */
    @Getter(AccessLevel.NONE)
    private volatile Map<String, List<String>> tokenComments = Collections.emptyMap();

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
            applyFieldsTo(config, true);
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
     * @param write  whether this is a real write, whose refused dotted map keys are reported (#553);
     *               the shutdown comparison and the snapshot probe refuse the same keys silently
     */
    @SuppressWarnings("unchecked")
    private void applyFieldsTo(YamlConfiguration target, boolean write) {
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
            target.set(path, serializeForFile(annotation, withoutDottedKeys(annotation, path, fieldValue, write)));
        }
        // #542: every framework write carries the one-token comments in the server's language, and
        // the shutdown comparison renders the same text.
        applyTokenComments(target);
    }

    /**
     * Sets the resolved comment lines of every one-token entry (#542) that {@code target} holds and
     * whose comment differs from them. Only those entries' comment lines change: a blank line that
     * separates the entry from the one above it is kept, and nothing else in {@code target} is
     * touched. The snapshot probe has no resolved comments, so this does nothing there.
     *
     * @param target the configuration about to be written or compared
     * @return {@code true} if any comment was changed
     */
    private boolean applyTokenComments(YamlConfiguration target) {
        boolean changed = false;
        for (Map.Entry<String, List<String>> entry : tokenComments.entrySet()) {
            String path = entry.getKey();
            if (!target.contains(path)) {
                continue;
            }
            List<String> current = target.getComments(path);
            List<String> desired = new ArrayList<>();
            for (String line : current) {
                if (line != null) {
                    break;
                }
                desired.add(null);
            }
            desired.addAll(entry.getValue());
            if (!desired.equals(current)) {
                target.setComments(path, desired);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Renders the exact text {@link #save()} would write right now, without writing it and without
     * touching the live {@link #config} (which {@link #toJsonObject()} still reports to the panel).
     * The live configuration is copied through its own YAML text - comments included - and the
     * fields are applied to the copy through {@link #applyFieldsTo(YamlConfiguration, boolean)}, the same
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
        applyFieldsTo(copy, false);
        return copy.saveToString();
    }

    /**
     * Brings a configuration text to the form this entity would give it after reading it and saving
     * it again (#510): the text is parsed, a throwaway instance of this class (one per call, see the
     * construction counts in this class's own javadoc) loads every present {@code @ConfigEntry} key
     * through its parser exactly as {@link #init(UltiToolsPlugin)} does
     * (absent keys keep that instance's declared defaults), and the instance's fields are applied back
     * onto the parsed text through {@link #applyFieldsTo(YamlConfiguration, boolean)}. Two passes make the
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
                // Silent: init() already warned about any value it could not bind. The probe is a fresh
                // instance, so a value it cannot bind simply leaves the declared default (#526).
                Object value = readConfigValue(field, annotation, path, configValue, false);
                if (value != ConfigValueBinder.UNBOUND) {
                    ReflectionUtil.setFieldValue(probe, field, value);
                }
            }
        }
        probe.applyFieldsTo(parsed, false);
        // #542: a one-key entry's comment belongs to the framework and is rewritten on every write, so
        // both sides of the comparison carry the resolved text - a comment-only difference on disk
        // (an operator's edit, a reload before the rewrite) is never a change the shutdown save writes.
        applyTokenComments(parsed);
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
            String fileText = null;
            try {
                // Read once: the check for dotted map keys (#553) looks at the same text.
                fileText = readConfigText(file);
                config.loadFromString(fileText);
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
            AbstractConfigEntity declaredDefaults = null;
            YamlConfiguration keyView = lastLoadUnparseable ? null : keyPreservingView(fileText);
            tokenComments = resolveTokenComments(true);
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
                        declaredDefaults = assignFileValue(field, annotation, path, configValue, declaredDefaults);
                    } else {
                        upToDate = false;
                        config.set(path, fileFormOfDefault(annotation, path, ReflectionUtil.getFieldValue(this, field)));
                        // D-07/D-09: the key never existed in the operator's file, so writing its
                        // @ConfigEntry comment alongside the value discloses nothing of theirs - this
                        // is D-01's first sanctioned exception, widened from "silently add a value" to
                        // "silently add a value and its explanation". A literal comment is written only
                        // here; the second exception, one-token comments (#542), follows below.
                        List<String> commentLines = tokenComments.containsKey(path)
                                ? tokenComments.get(path) : splitComment(annotation.comment());
                        if (!commentLines.isEmpty()) {
                            config.setComments(path, commentLines);
                        }
                    }
                }
            }
            warnDottedMapKeys(keyView);
            // #542 (maintainer 2026-09-29, "rewrite in the current language on every save"): D-01's
            // second sanctioned exception. A one-token comment on a key already in the file is
            // rewritten when it differs from the text resolved in the server's current language - the
            // first start of an upgraded server, or after a language switch. It is folded into the one
            // write this load makes, only after a successful load, and never when nothing differs.
            boolean commentsDiffer = !lastLoadUnparseable && applyTokenComments(config);
            boolean commentRewriteFailed = false;
            if (!upToDate) {
                config.save(file);
            } else if (commentsDiffer) {
                // A comment-only rewrite is not worth failing the load for: a read-only file (a
                // container's mounted config, a store symlink) keeps its old comments this start.
                try {
                    config.save(file);
                } catch (IOException e) {
                    commentRewriteFailed = true;
                    LOGGER.warning(String.format("Config file '%s': could not rewrite its comments in the server's"
                            + " language (%s); the values were loaded, and the shutdown save will write the file"
                            + " again", configFilePath, e.getMessage()));
                }
            }
            // #510: config now holds exactly the file's content, including any first-boot defaults
            // write. The snapshot is taken from that text, so a change listener below that changes a
            // value in memory is still seen as a code change by the shutdown save.
            takeSnapshot();
            if (commentRewriteFailed) {
                // A write that failed may have left the file truncated (the writer empties the file
                // before it writes), so do not record it as in sync: the shutdown save rewrites it.
                savedSnapshot = null;
            }
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
     * <p>
     * #523: a collection or map field is then converted to its declared element, key and value
     * types by {@link ConfigValueBinder}; an element that cannot be converted is skipped, and when
     * {@code report} is set one WARNING names this entity's file, the key, the raw value and the
     * declared type. The snapshot probe passes {@code false}, so a bad value is reported once per
     * load, not again at every snapshot and shutdown comparison.
     *
     * @param field      the target {@code @ConfigEntry} field
     * @param annotation its {@code @ConfigEntry}
     * @param path       the entry's path, named in warnings
     * @param raw        the value SnakeYAML returned for the entry's path, never {@code null}
     * @param report     whether a value that cannot be bound is logged
     * @return the value to store in {@code field}, or {@link ConfigValueBinder#UNBOUND} when the file's
     *         value cannot be bound to it and the field keeps its declared default (#526)
     */
    private Object readConfigValue(Field field, ConfigEntry annotation, String path, Object raw, boolean report) {
        java.util.function.Consumer<String> reporter = report ? this::warnBinding : message -> { };
        Object parsed;
        try {
            parsed = ReflectionUtil.newInstance(annotation.parser()).parse(raw);
        } catch (RuntimeException e) {
            // A value the entry's parser cannot read (a YAML timestamp handed to the default parser, a
            // module parser's own refusal) keeps the default like any other value that cannot be bound
            // (#526), instead of leaving init() and taking the module down.
            reporter.accept(String.format("Config file '%s': key '%s' holds %s %s, which its parser %s cannot"
                            + " read (%s); the field keeps its default, the rest of the configuration loads",
                    configFilePath, path, ConfigValueBinder.kindOf(raw), ConfigValueBinder.describe(path, raw),
                    annotation.parser().getSimpleName(), e.getClass().getSimpleName()));
            return ConfigValueBinder.UNBOUND;
        }
        return new ConfigValueBinder(configFilePath, reporter).bind(field, path, parsed);
    }

    /**
     * Stores the file's value for one entry in this entity's field (#523, #526). A value that cannot
     * be bound - {@link ConfigValueBinder#UNBOUND}, already reported - sets the field to this class's
     * declared default instead, read from a fresh instance constructed the first time a load pass
     * needs one (so a pass with no such value constructs nothing extra). On {@link #init} that is the
     * value the field already holds; on {@link #reload()} it replaces the running value, so a reload
     * with a wrongly shaped value behaves like a start with it.
     *
     * @param field            the {@code @ConfigEntry} field
     * @param annotation       its {@code @ConfigEntry}
     * @param path             the entry's path
     * @param raw              the value the file holds, never {@code null}
     * @param declaredDefaults the fresh instance this pass already constructed, or {@code null}
     * @return the fresh instance, if one has now been constructed, else {@code declaredDefaults}
     */
    private AbstractConfigEntity assignFileValue(Field field, ConfigEntry annotation, String path, Object raw,
                                                 AbstractConfigEntity declaredDefaults) {
        Object value = readConfigValue(field, annotation, path, raw, true);
        if (value != ConfigValueBinder.UNBOUND) {
            ReflectionUtil.setFieldValue(this, field, value);
            return declaredDefaults;
        }
        AbstractConfigEntity defaults = declaredDefaults;
        if (defaults == null) {
            try {
                defaults = constructSibling();
            } catch (RuntimeException e) {
                // validateFields() refuses an unconstructable class right after the load; until then the
                // field keeps the value it holds.
                return null;
            }
        }
        ReflectionUtil.setFieldValue(this, field, ReflectionUtil.getFieldValue(defaults, field));
        return defaults;
    }

    /**
     * Logs one binding problem {@link ConfigValueBinder} found, prefixed with the owning module's
     * name when it is known.
     *
     * @param message the binder's description of the value it could not bind
     */
    private void warnBinding(String message) {
        String moduleName = ultiToolsPlugin != null ? ultiToolsPlugin.getPluginName() : null;
        LOGGER.warning(moduleName != null ? "[" + moduleName + "] " + message : message);
    }

    /**
     * The form in which a missing key's declared default is written into the file: a collection or
     * an enum constant goes through the entry's parser, the same form {@link #save()} writes - a
     * {@code Set} as a YAML sequence and an enum by its name (#523), which is what {@link
     * ConfigValueBinder} reads back; a map is written as it is, as in 6.2, without any key that
     * contains a dot (#553, reported); everything else is written unchanged, as before.
     *
     * @param annotation   the entry's {@code @ConfigEntry}
     * @param path         the entry's path, named in a warning
     * @param defaultValue the field's declared default, possibly {@code null}
     * @return the value to put into the configuration
     */
    private Object fileFormOfDefault(ConfigEntry annotation, String path, Object defaultValue) {
        if (defaultValue instanceof Map) {
            return withoutDottedKeys(annotation, path, defaultValue, true);
        }
        if (defaultValue instanceof java.util.Collection || defaultValue instanceof Enum) {
            return serializeForFile(annotation, defaultValue);
        }
        return defaultValue;
    }

    /**
     * Serializes a field value through its entry's parser, the form {@link #save()} writes. For the
     * default parser a collection is written as a YAML list and an enum constant by its name, at any
     * depth of a list (#523) - SnakeYAML would otherwise tag an enum with its Java class, which the
     * loader then refuses, and write a {@code Set} as a tagged mapping. A module's own parser
     * serializes exactly as it always did.
     *
     * @param annotation the entry's {@code @ConfigEntry}
     * @param value      the value, never {@code null}
     * @return the value to put into the configuration
     */
    @SuppressWarnings("unchecked")
    private static Object serializeForFile(ConfigEntry annotation, Object value) {
        com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<Object> parser =
                ReflectionUtil.newInstance(annotation.parser());
        if (parser instanceof DefaultConfigParser) {
            return ((DefaultConfigParser) parser).fileForm(value);
        }
        return parser.serialize(value);
    }

    /**
     * #553, maintainer decision of 2026-09-30 ("refuse, and say so plainly"): the configuration file
     * uses {@code '.'} as its path separator, so a map key containing a dot cannot be stored as one
     * key - Bukkit's loader reads {@code my.rule} as {@code my} -> {@code rule}, and quoting the key
     * does not help. Every framework write therefore leaves such a key out of a map it writes - at the
     * map's own level and in maps nested as its values, which become sections too; keys inside a list
     * element are plain data and are kept - and, when {@code report} is set, logs one WARNING per key
     * naming the file, the entry and the key and asking for a rename. The in-memory value is not
     * changed. Applies to the framework's own writes of a map: with the built-in parsers
     * ({@code DefaultConfigParser}, {@code StringHashMapParser}), and to a panel write whatever the
     * parser; a module's own parser writes as it always did.
     *
     * @param annotation the entry's {@code @ConfigEntry}, or {@code null} for a panel write
     * @param path       the entry's path
     * @param value      the value about to be written
     * @param report     whether a refused key is logged
     * @return {@code value} unchanged, or a copy of the map without its dotted keys
     */
    private Object withoutDottedKeys(ConfigEntry annotation, String path, Object value, boolean report) {
        if (!(value instanceof Map)) {
            return value;
        }
        if (annotation != null && annotation.parser() != DefaultConfigParser.class
                && annotation.parser() != com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser.class) {
            return value;
        }
        return mapWithoutDottedKeys((Map<?, ?>) value, path, report);
    }

    private Map<Object, Object> mapWithoutDottedKeys(Map<?, ?> map, String path, boolean report) {
        Map<Object, Object> kept = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.indexOf('.') >= 0) {
                if (report) {
                    warnBinding(String.format("Config file '%s': map key '%s' under key '%s' contains '.', which the"
                            + " configuration file reads as a path separator, so it cannot be stored as one key; the"
                            + " entry was not written - rename the key (for example with '-' or '_')",
                            configFilePath, key, path));
                }
                continue;
            }
            Object child = entry.getValue();
            kept.put(entry.getKey(), child instanceof Map
                    ? mapWithoutDottedKeys((Map<?, ?>) child, path + "." + key, report) : child);
        }
        return kept;
    }

    /**
     * Warns, once per load, about every map key in the file that contains a dot (#553): the loader
     * has read it as a nested path ({@code my.rule} as {@code my} -> {@code rule}), exactly as in 6.2,
     * and the operator is asked to rename it. Only the entries of {@code Map} fields are checked.
     *
     * @param keyView the file read with a separator no key contains, or {@code null}
     */
    private void warnDottedMapKeys(YamlConfiguration keyView) {
        if (keyView == null) {
            return;
        }
        for (Field field : configEntryFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String path = annotation.path().isEmpty() ? field.getName() : annotation.path();
            Object section;
            try {
                section = keyView.get(path.replace('.', MAP_KEY_SEPARATOR));
            } catch (RuntimeException e) {
                continue;
            }
            if (section instanceof ConfigurationSection) {
                warnDottedKeysIn((ConfigurationSection) section, path);
            }
        }
    }

    private void warnDottedKeysIn(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            if (key.indexOf('.') >= 0) {
                warnBinding(String.format("Config file '%s': map key '%s' under key '%s' contains '.', which the"
                        + " configuration file reads as a path separator, so it was loaded as '%s'; rename the key"
                        + " (for example with '-' or '_')", configFilePath, key, path, key.replace(".", "' -> '")));
                continue;
            }
            Object child = section.get(key);
            if (child instanceof ConfigurationSection) {
                warnDottedKeysIn((ConfigurationSection) child, path + "." + key);
            }
        }
    }

    /**
     * Reads a configuration file's text exactly as {@code YamlConfiguration#load(File)} does - UTF-8,
     * line by line, each line ended by {@code \n} - so that loading it with {@code loadFromString}
     * gives the same configuration, and the check for dotted map keys (#553) looks at the same text
     * instead of a second read.
     *
     * @param file the configuration file
     * @return its text
     * @throws FileNotFoundException if it does not exist or is not a regular file, as {@code load} does
     * @throws IOException           if it cannot be read
     */
    private static String readConfigText(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private static boolean hasMapField(List<Field> configFields) {
        for (Field field : configFields) {
            if (Map.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The view {@link #warnDottedMapKeys} reads a text this entity read through, or {@code null} when
     * this class has no {@code Map} field or there is no text.
     *
     * @param text the file's text as read, or {@code null}
     * @return the view, or {@code null}
     */
    private YamlConfiguration keyPreservingView(String text) {
        if (text == null || !hasMapField(configEntryFields())) {
            return null;
        }
        return parseKeyPreserving(text);
    }

    /**
     * @param text a YAML text
     * @return {@code text} parsed with {@link #MAP_KEY_SEPARATOR} as its path separator, comments
     *         kept, or {@code null} if it cannot be parsed
     */
    private static YamlConfiguration parseKeyPreserving(String text) {
        if (text == null) {
            return null;
        }
        YamlConfiguration view = new YamlConfiguration();
        view.options().parseComments(true);
        view.options().pathSeparator(MAP_KEY_SEPARATOR);
        try {
            view.loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException e) {
            // Only used to warn about dotted keys: a text this view cannot read (a key ending in NUL)
            // simply gets no such warning.
            return null;
        }
        return view;
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
     * declaration order, ready for {@link ConfigurationSection}'s
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
     * Splits a catalogue text into comment lines at every character YAML treats as a line break -
     * {@code \r\n}, {@code \r}, {@code \n}, U+0085, U+2028 and U+2029 - and drops every character
     * YAML does not allow in a file (control characters other than tab, unpaired surrogates,
     * U+FFFE/U+FFFF), so no translation can make the framework write a file its own loader then
     * refuses (#542, gate-1 review).
     *
     * @param text the catalogue text
     * @return one element per line, trailing empty lines dropped
     */
    static List<String> commentLinesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|[\n\r\u0085\u2028\u2029]")) {
            lines.add(yamlPrintable(line));
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String yamlPrintable(String line) {
        StringBuilder kept = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < line.length() && Character.isLowSurrogate(line.charAt(i + 1))) {
                kept.append(c).append(line.charAt(++i));
            } else if (c == '\t' || c >= 0x20 && c <= 0x7E || c >= 0xA0 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD) {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    /**
     * Looks {@code key} up in the owning module's language catalogue. A lookup that fails - the
     * module's language did not load - counts as a missing key, so the configuration still loads and
     * the token is written, rather than the comment taking the module down.
     *
     * @param key the catalogue key
     * @return the catalogue text, or {@code null} if there is none
     */
    private String lookUpCatalogue(String key) {
        if (ultiToolsPlugin == null) {
            return null;
        }
        try {
            return ultiToolsPlugin.i18n(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The language key a {@code @ConfigEntry} comment names, if the comment is exactly one {@code
     * {key}} token after trimming (#542).
     *
     * @param comment the {@code comment()} attribute
     * @return the key, or {@code null} for a literal comment
     */
    static String commentKey(String comment) {
        if (comment == null) {
            return null;
        }
        java.util.regex.Matcher matcher = COMMENT_TOKEN.matcher(comment.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Resolves every one-token {@code @ConfigEntry} comment of this class against the owning
     * module's language catalogue, in the server's current language (#542). The text is split into
     * one comment line per line break ({@code \r\n}, {@code \r} or {@code \n}), so a translation that
     * spans lines can never break the YAML file. A key the catalogue does not contain resolves to the
     * token itself, and - when {@code warn} is set - one WARNING names the module, the file, the
     * entry's path and the key.
     *
     * @param warn whether a missing catalogue key is logged
     * @return the resolved lines by entry path, in declaration order
     */
    private Map<String, List<String>> resolveTokenComments(boolean warn) {
        Map<String, List<String>> resolved = new java.util.LinkedHashMap<>();
        for (Field field : configEntryFields()) {
            ConfigEntry annotation = ReflectionUtil.getAnnotation(field, ConfigEntry.class);
            String key = commentKey(annotation.comment());
            if (key == null) {
                continue;
            }
            String path = annotation.path().isEmpty() ? field.getName() : annotation.path();
            String text = lookUpCatalogue(key);
            if (text == null || text.equals(key)) {
                if (warn) {
                    String moduleName = ultiToolsPlugin != null ? ultiToolsPlugin.getPluginName() : null;
                    LOGGER.warning(String.format("[%s] Config file '%s': the comment of key '%s' is the language key"
                                    + " '%s', which the module's language catalogue does not contain; the key is"
                                    + " written as the comment", moduleName, configFilePath, path, key));
                }
                resolved.put(path, Collections.singletonList(annotation.comment().trim()));
            } else {
                resolved.put(path, commentLinesOf(text));
            }
        }
        return resolved;
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
                Object value = ReflectionUtil.getFieldValue(this, field);
                // An enum or a collection is written in the form the loader reads back (#523): the
                // Gson value as it is would be a Java-class-tagged enum or a tagged set, and the next
                // start would refuse the whole file. Other values are written as before.
                config.set(path, value instanceof Enum || value instanceof java.util.Collection
                        ? serializeForFile(annotation, value) : withoutDottedKeys(null, path, value, true));
            }
            applyTokenComments(config); // #542: a panel write carries the resolved comments too
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
        validateBindingRanges(touchedFieldsOut);
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
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        addLeaves(config, "", gson, jsonObject);
        return jsonObject;
    }

    /**
     * Adds every non-section value under {@code section} to {@code out}, keyed by its dotted path
     * from the root. Walks the tree one level at a time and joins the keys itself rather than using
     * {@code getKeys(true)}: that builds each path with the separator of the section's own root, and a
     * section written by {@link #save()} comes from the entry's parser with a root of its own (a
     * map's has a separator that keeps its keys whole, #553) - so {@code getKeys(true)} returned paths
     * such as {@code rulea} and {@code .rulea.reply} instead of {@code autoreply.rules.rulea.reply}.
     *
     * @param section the section to walk
     * @param prefix  the dotted path of {@code section}, empty for the root
     * @param gson    the serializer for leaf values
     * @param out     the payload being built
     */
    private static void addLeaves(ConfigurationSection section, String prefix, Gson gson,
                                  JsonObject out) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection) {
                addLeaves((ConfigurationSection) value, path, gson, out);
            } else {
                out.add(path, gson.toJsonTree(value));
            }
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
                // #542: a one-token comment is sent as the text resolved in the server's language.
                List<String> resolved = tokenComments.get(path);
                if (resolved == null && commentKey(annotation.comment()) != null) {
                    resolved = resolveTokenComments(false).get(path);
                }
                jsonObject.addProperty(path, resolved != null ? String.join("\n", resolved) : annotation.comment());
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
    static boolean isSecretShapedFieldName(String fieldName) {
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
            String fileText = null;
            try {
                fileText = readConfigText(file);
                config.loadFromString(fileText);
            } catch (FileNotFoundException ignored) {
                // Mirrors init()'s own handling above: a missing file is the normal case, not an
                // error - config stays empty and every field below simply keeps its current value.
            } catch (InvalidConfigurationException e) {
                // #510: same as init() - never write over a file the framework could not read.
                lastLoadUnparseable = true;
                LOGGER.log(Level.SEVERE, "Cannot load " + file, e);
            }

            // Update field values
            AbstractConfigEntity declaredDefaults = null;
            YamlConfiguration keyView = lastLoadUnparseable ? null : keyPreservingView(fileText);
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
                        declaredDefaults = assignFileValue(field, annotation, path, configValue, declaredDefaults);
                    }
                }
            }
            warnDottedMapKeys(keyView);
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
