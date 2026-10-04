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
import com.ultikits.ultitools.config.ConfigWriteRefusedException;
import com.ultikits.ultitools.config.document.ConfigDocument;
import com.ultikits.ultitools.config.document.ConfigLoadResult;
import com.ultikits.ultitools.config.document.AtomicConfigWriter;
import com.ultikits.ultitools.config.document.OperatorFileWriter;
import com.ultikits.ultitools.config.document.OwnedPaths;
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
    private static final Object ABSENT_RELOAD_VALUE = new Object();
    private static final Logger LOGGER = Logger.getLogger(AbstractConfigEntity.class.getName());

    private final String configFilePath;
    private final List<ConfigChangeListener> changeListeners = new CopyOnWriteArrayList<>();
    private UltiToolsPlugin ultiToolsPlugin;
    @Getter(AccessLevel.NONE)
    private ConfigDocument document;
    @Getter(AccessLevel.NONE)
    private Map<String, Object> lastLoadedPresence;
    @Getter(AccessLevel.NONE)
    private final Map<Field, RawEntry> acknowledgedRaw = new LinkedHashMap<>();

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
    private boolean deferInitialization;
    @Getter(AccessLevel.NONE)
    private PendingInitialization pendingInitialization;
    /** Whether the last load's comment-only write was refused or failed (it has logged its one warning). */
    @Getter(AccessLevel.NONE)
    private boolean commentWriteNotAppliedAtLoad;

    @Getter(AccessLevel.NONE)
    private final Set<String> warnedCommentKeys = ConcurrentHashMap.newKeySet();

    /**
     * The composite values a panel edit is about to rewrite whole, as last read, per setting and unit; set by
     * {@code applyAndValidate} and consumed by {@code panelChanges} under the entity monitor (route change, R3-01).
     */
    @Getter(AccessLevel.NONE)
    private final Map<Field, Map<List<String>, RawEntry>> compositeReads = new LinkedHashMap<>();

    /**
     * The whole composite values a panel edit writes, per setting and unit: the value as last read with only the edited
     * fields changed, so every untouched field keeps the bytes the operator wrote (#609, 17-65 round 4 R4-I2); set by
     * {@code applyAndValidate} and consumed by {@code panelChanges} under the entity monitor, like {@link #compositeReads}.
     */
    @Getter(AccessLevel.NONE)
    private final Map<Field, Map<List<String>, Object>> compositeWrites = new LinkedHashMap<>();

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

    /** A batch initialization write: what to insert, and the bytes it was read from (#602). */
    private static final class PendingInitialization {
        private final ConfigDocument read;
        private final Map<Field, Object> baseline;
        private final Map<Field, Object> inserted;
        private final String expected;
        private PendingInitialization(ConfigDocument read, Map<Field, Object> baseline, Map<Field, Object> inserted,
                String expected) {
            this.read = read; this.baseline = baseline; this.inserted = inserted; this.expected = expected;
        }
    }

    /**
     * Constructor for AbstractConfigEntity.
     *
     * @param configFilePath the path to the configuration file, for example: config/config.yml
     */
    public AbstractConfigEntity(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /**
     * Writes the settings this module changed in memory since the last load or save - and nothing else - through the
     * config write gate.
     * <p>
     * A setting is written only when all three hold: the module changed it since the last load or save; the file
     * still holds at that path exactly the value the framework last read or wrote there; and that value is the one
     * the module started from (it converts to the setting's value as last loaded or saved). A setting declared as a
     * {@link Map} is compared and written entry by entry, following the declared map types into nested maps: only the
     * entries the module added, changed or removed are set or removed. Any other value - a list, a
     * {@code ConfigurationSerializable} such as a Bukkit {@code Location}, the value of a typed map entry that is not
     * itself a map - is one value, written whole or not at all, so the file never holds a value mixed from the
     * module's and the operator's. Everything else in the file stays as it is: a save never inserts a key the file lacks, never
     * rewrites a comment and never removes a map entry the module did not remove. When nothing needs writing, the file
     * is not touched (bytes and modification time stay).
     * <p>
     * <b>Why it cannot overwrite operator content.</b> A value the operator edited on disk since it was read, a key
     * the operator deleted, a value the framework could not use ({@code interval: 3O0}, a list element {@code abc})
     * and a map entry left in the 6.2 split form ({@code o: {O: x}}) are never the value the module started from at
     * that path, so they are never written - whether or not the module changed that setting. The config write gate
     * ({@link OperatorFileWriter}) then verifies, after rendering, that every line outside the written settings is
     * byte-identical to the file as it is at write time, or writes nothing. A change that is not written stays in
     * memory, and one WARNING names the file and those keys, never a value (maintainer decision 2026-10-04, "what
     * code may write, by file type", which supersedes the earlier "a save warns and overwrites").
     * <p>
     * Use it for a change the operator asked for through the module, or for shipped text the module re-renders after
     * a language switch. A failed write leaves the effective baseline as it was, so the change is still unsaved.
     *
     * @throws IOException if publishing the verified file fails
     */
    public void save() throws IOException {
        synchronized (this) {
            if (lastLoadUnparseable) { return; }
            saveModuleChanges();
        }
    }

    /**
     * One setting, or one entry of a map setting, the module changed since the last load or save: where it is in the
     * file, what the module holds there now, and what the framework last read or wrote there.
     */
    private static final class ModuleChange {
        private final Field field;
        private final List<String> leaf;
        private final List<String> path;
        private boolean present;
        private Object value;
        /**
         * The module's value at {@link #path} as plain data, which becomes the setting's baseline once written. It differs
         * from {@link #value} only after {@link #writeWhole}: the file then holds the read composite with one field changed
         * ({@code y: 64} kept as written), while the module holds its own conversion of it ({@code 64.0}); recording the
         * written text as the baseline would leave the setting "unsaved" and refuse its next module change (PR #611 local
         * Codex run 1).
         */
        private final Object effective;
        private final boolean readPresent;
        private final Object readValue;
        /**
         * For a panel edit inside a composite value: the whole value the entity last read there, which the file must still
         * hold at write time (17-65 route change, R3-01); {@code null} when the file's value is not a precondition.
         */
        private RawEntry required;

        private ModuleChange(Field field, List<String> leaf, List<String> path, Object mine, RawEntry read) {
            this.field = field; this.leaf = leaf; this.path = path;
            this.present = leaf.isEmpty() || mapContains(mine, leaf);
            this.value = leaf.isEmpty() ? PlainData.copy(mine) : PlainData.copy(mapLeaf(mine, leaf));
            this.effective = PlainData.copy(this.value);
            this.readPresent = read != null && read.present && (leaf.isEmpty() || mapContains(read.value, leaf));
            this.readValue = read == null ? null : leaf.isEmpty() ? PlainData.copy(read.value) : PlainData.copy(mapLeaf(read.value, leaf));
        }

        private boolean holds(boolean isPresent, Object at, boolean wantPresent, Object want) {
            return isPresent == wantPresent && (!isPresent || PlainData.plainEquals(at, want));
        }

        /**
         * Writes {@code unit} - the whole composite value as last read with only the panel's fields changed - instead of
         * the module's serialization of it, so the untouched fields keep their bytes (#609, R4-I2).
         */
        private void writeWhole(Object unit) {
            this.present = true;
            this.value = PlainData.copy(unit);
        }
    }

    /** Sets the module's value, or removes the map entry the module removed, at each change's path. */
    private static void apply(ConfigDocument target, List<ModuleChange> changes) {
        for (ModuleChange change : changes) {
            if (change.present) { target.set(change.path, change.value); } else { target.remove(change.path); }
        }
    }

    /**
     * What a save owns at the config write gate: each change's whole key, except that a map which is empty in the file
     * as read ({@code {}}), or which the changes empty, is owned as a whole - its key line must change with its first
     * or last entry, and an empty map holds nothing of the operator's that could be lost.
     */
    private static OwnedPaths saveOwnership(List<ModuleChange> changes, ConfigDocument read) {
        Map<List<String>, Map<String, Object>> after = new LinkedHashMap<>();
        Set<List<String>> emptied = new java.util.LinkedHashSet<>();
        for (ModuleChange change : changes) {
            if (change.path.size() < 2) { continue; }
            List<String> parent = change.path.subList(0, change.path.size() - 1);
            Object now = read.get(parent);
            if (!(now instanceof Map)) { continue; }
            if (((Map<?, ?>) now).isEmpty()) { emptied.add(parent); }
            Map<String, Object> state = after.get(parent);
            if (state == null) {
                state = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) now).entrySet()) { state.put(String.valueOf(entry.getKey()), entry.getValue()); }
                after.put(parent, state);
            }
            String last = change.path.get(change.path.size() - 1);
            if (change.present) { state.put(last, change.value); } else { state.remove(last); }
        }
        for (Map.Entry<List<String>, Map<String, Object>> entry : after.entrySet()) {
            if (entry.getValue().isEmpty()) { emptied.add(entry.getKey()); }
        }
        OwnedPaths.Builder owned = OwnedPaths.builder();
        for (ModuleChange change : changes) {
            List<String> parent = change.path.size() < 2 ? null : change.path.subList(0, change.path.size() - 1);
            owned.value(parent != null && emptied.contains(parent) ? parent : change.path);
        }
        return owned.build();
    }

    /**
     * The save rule of {@link #save()}: collects the module's changes against the effective baseline; keeps those whose
     * last-read file value is the value the module started from; checks each on one read of the file, which must still
     * hold there what the framework last read (a file already holding the module's value needs nothing); writes the
     * rest through the config write gate against exactly that read; advances the baseline and the last-read entry only
     * for what the file now holds; and names every change that was not written in one warning.
     */
    private void saveModuleChanges() throws IOException {
        java.nio.file.Path target = ultiToolsPlugin.getConfigFile(configFilePath).toPath();
        ConfigLoadResult loaded = ConfigDocument.load(target);
        if (protectFailedLoad(loaded)) {
            throw new IOException("Cannot save " + configFilePath + ": current file is " + loaded.state());
        }
        ConfigDocument read = loaded.state() == ConfigLoadResult.State.LOADED ? loaded.document() : ConfigDocument.empty();
        List<Field> fields = configEntryFields();
        Map<Field, Object> current = currentPlain(fields, true);
        if (savedSnapshot == null) { savedSnapshot = new LinkedHashMap<>(declaredDefaults); }
        List<ModuleChange> writable = new ArrayList<>();
        List<String> unwritten = new ArrayList<>();
        for (Field field : fields) {
            Object base = savedSnapshot.get(field);
            Object mine = current.get(field);
            List<List<String>> leaves = new ArrayList<>();
            changedLeaves(base, mine, declaredType(field), new ArrayList<>(), leaves);
            if (leaves.isEmpty()) {
                // Equal content (at most a different map order): nothing a file write could carry.
                savedSnapshot.put(field, mine);
                continue;
            }
            for (List<String> leaf : leaves) {
                List<String> path = new ArrayList<>(keys(field)); path.addAll(leaf);
                ModuleChange change = new ModuleChange(field, leaf, path, mine, acknowledgedRaw.get(field));
                if (startedFromFile(field, leaf, base)) { writable.add(change); } else { unwritten.add(describeKey(field, leaf)); }
            }
        }
        List<ModuleChange> onDisk = new ArrayList<>();
        List<ModuleChange> toWrite = new ArrayList<>();
        for (ModuleChange change : writable) {
            boolean present = read.contains(change.path);
            Object now = read.get(change.path);
            boolean parentIsMapping = change.path.size() == 1
                    || read.get(change.path.subList(0, change.path.size() - 1)) instanceof Map;
            if (change.holds(present, now, change.present, change.value)) { onDisk.add(change); }
            else if (parentIsMapping && change.holds(present, now, change.readPresent, change.readValue)) { toWrite.add(change); }
            else { unwritten.add(describeKey(change.field, change.leaf)); }
        }
        if (!toWrite.isEmpty()) {
            // Against exactly the bytes checked above: a file that changed since is abandoned by the gate, named once.
            OperatorFileWriter.Result result = OperatorFileWriter.write(target, saveOwnership(toWrite, read),
                    expectedBase(loaded), candidate -> apply(candidate, toWrite));
            if (result.applied()) {
                onDisk.addAll(toWrite);
                acknowledgeWritten(result.readFingerprint(), result.fingerprint(), result.document(), onDisk);
            }
        } else if (!onDisk.isEmpty()) {
            acknowledgeWritten(loaded.fingerprint(), loaded.fingerprint(), read, onDisk);
        }
        if (!unwritten.isEmpty()) { warnNotWritten(unwritten); }
    }

    /**
     * Advances the effective baseline and the last-read entry of exactly the changes the file now holds. The entity
     * records the bytes the gate wrote as last read only when the gate's read held exactly the bytes this entity last
     * bound; otherwise the operator's newer edit stays a change on disk (never a fresh read: 17-63 review WR-01).
     *
     * @param readFingerprint the bytes the write was checked against (storage-layer SHA-256 hex)
     * @param fingerprint     the bytes the file holds now (the gate's written bytes, or the bytes read when nothing was written)
     * @param onDisk          the document the file holds now
     * @param applied         the changes the file now holds
     */
    private void acknowledgeWritten(String readFingerprint, String fingerprint, ConfigDocument onDisk, List<ModuleChange> applied) {
        boolean bound = savedFileFingerprint != null && readFingerprint != null
                && entityFingerprint(readFingerprint).equals(savedFileFingerprint);
        for (ModuleChange change : applied) {
            Field field = change.field;
            RawEntry read = acknowledgedRaw.get(field);
            // The baseline takes the module's value; the last-read entry and the document take the bytes now on disk.
            if (change.leaf.isEmpty()) {
                savedSnapshot.put(field, PlainData.copy(change.effective));
                acknowledgedRaw.put(field, new RawEntry(true, change.value));
            } else {
                Object base = savedSnapshot.get(field);
                savedSnapshot.put(field, change.present ? patchedMap(base, change.leaf, change.effective) : withoutLeaf(base, change.leaf));
                Object raw = read == null ? null : read.value;
                acknowledgedRaw.put(field, new RawEntry(true, change.present ? patchedMap(raw, change.leaf, change.value)
                        : withoutLeaf(raw, change.leaf)));
            }
            if (!bound && document != null) {
                if (change.present) { document.set(change.path, change.value); } else { document.remove(change.path); }
            }
        }
        if (bound) {
            document = onDisk;
            savedFileFingerprint = entityFingerprint(fingerprint);
        }
    }

    /**
     * Whether the value the framework last read for {@code field} at {@code leaf} is the value the module started from
     * there: it is present, and the setting's last loaded or saved value with that entry taken from the file converts,
     * without a conversion failure, back to exactly that value. An unconvertible value or element, a key the operator
     * deleted and a 6.2 split map entry are therefore never "started from".
     */
    private boolean startedFromFile(Field field, List<String> leaf, Object base) {
        RawEntry read = acknowledgedRaw.get(field);
        if (read == null || !read.present) { return false; }
        Object probe;
        if (leaf.isEmpty()) {
            probe = read.value;
        } else {
            if (!(read.value instanceof Map) || !(base instanceof Map)) { return false; }
            probe = mapContains(read.value, leaf) ? patchedMap(base, leaf, mapLeaf(read.value, leaf)) : withoutLeaf(base, leaf);
        }
        ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
        try {
            ConversionResult<Object> converted = registry().fromPlainResult(PlainData.copy(probe), declaredType(field),
                    configFilePath, keys(field), entry);
            if (!converted.failures().isEmpty()) { return false; }
            Object plain = registry().toPlainResult(converted.value(), declaredType(field), configFilePath, keys(field), entry).value();
            return PlainData.plainEquals(PlainData.copy(plain), base);
        } catch (ConversionException | RuntimeException unusable) {
            return false;
        }
    }

    /**
     * Collects the paths, relative to a setting, at which {@code current} differs from {@code base}. Only a value
     * declared as a {@link Map} is compared key by key (an added or removed key is one path), following the declared
     * value type into nested maps; anything else - a list, a {@code ConfigurationSerializable} such as a Bukkit
     * {@code Location} or {@code Vector}, a typed map's value of a non-map type - is one value, written whole or not at
     * all, so a save never leaves on disk a value mixed from the module's and the operator's (save rule revision 1,
     * 17-65 review round 1 R65-01).
     */
    private static void changedLeaves(Object base, Object current, Type declared, List<String> prefix, List<List<String>> out) {
        if (splitsByEntry(declared, base, current)) {
            Map<?, ?> before = (Map<?, ?>) base;
            Map<?, ?> after = (Map<?, ?>) current;
            Type valueType = mapValueType(declared);
            Set<Object> names = new java.util.LinkedHashSet<>(after.keySet()); names.addAll(before.keySet());
            for (Object name : names) {
                List<String> path = new ArrayList<>(prefix); path.add(String.valueOf(name));
                if (!before.containsKey(name) || !after.containsKey(name)) { out.add(path); }
                else { changedLeaves(before.get(name), after.get(name), valueType, path, out); }
            }
            return;
        }
        if (!PlainData.plainEquals(base, current)) { out.add(prefix); }
    }

    /** The declared value type of a map type ({@code Object} when it is raw). */
    private static Type mapValueType(Type declared) {
        return TypeToken.of(declared).resolveType(Map.class.getTypeParameters()[1]).getType();
    }

    /**
     * How many of {@code keys}, from the start, address entries of declared maps: the keys after them would reach inside a
     * value that is one value (17-65 review round 2 R2-01).
     */
    private static int splitDepth(Type declared, List<String> keys) {
        Type type = declared;
        int depth = 0;
        for (int i = 0; i < keys.size() && Map.class.isAssignableFrom(TypeToken.of(type).getRawType()); i++) {
            depth++;
            type = mapValueType(type);
        }
        return depth;
    }

    /**
     * Whether a value is compared entry by entry: it is declared as a {@link Map} and both plain forms are maps that are
     * not a serialized object (Bukkit's {@code ==} type key).
     */
    private static boolean splitsByEntry(Type declared, Object base, Object current) {
        return base instanceof Map && current instanceof Map
                && Map.class.isAssignableFrom(TypeToken.of(declared).getRawType())
                && !((Map<?, ?>) base).containsKey("==") && !((Map<?, ?>) current).containsKey("==");
    }

    /** The key a warning names for a change: the setting's path, then the map keys, any key below a secret-shaped one redacted. */
    private String describeKey(Field field, List<String> leaf) {
        StringBuilder text = new StringBuilder(fieldPath(field));
        boolean secret = isSecretShapedFieldName(field.getName());
        for (String key : keys(field)) { secret |= isSecretShapedFieldName(key); }
        for (String key : leaf) {
            text.append('.').append(secret ? "<redacted>" : key);
            secret |= isSecretShapedFieldName(key);
        }
        return "'" + text + "'";
    }

    /**
     * Writes exactly the named settings, as the module holds them now, because the operator explicitly asked for that
     * change - a command such as {@code /setspawn}, which names the six {@code spawn.location.*} entries.
     * <p>
     * The operator's request is their consent to replace what the file holds at those settings: the module's value is
     * written there even when the operator also edited that key by hand since it was read (the command wins at the key
     * it names), and a named setting the file lacks is inserted with its comment. Nothing else is written - not another
     * setting, not even one the module changed and did not name - and no other key, value, comment or byte of the file
     * moves (maintainer decision of 2026-10-04, "what code may write, by file type": write exactly the item the
     * operator explicitly asked to change).
     * <p>
     * Naming a setting declared as a {@link Map} writes the whole map as the module holds it: every entry the operator
     * added or edited by hand in that map since it was read is replaced or dropped. For a command that changes one
     * entry - one rule, one warp - use {@link #saveOperatorMapEntry(String, String...)}, which writes only that entry.
     * <p>
     * <b>Why it cannot overwrite other operator content.</b> The write goes through the framework's configuration write
     * gate owning only the named settings' keys: after rendering, every line outside them must be byte-identical to the
     * file as it is at write time, or nothing is written. When the gate refuses - the file cannot be read or parsed,
     * uses anchors, aliases or merge keys, has a layout the write could not keep byte for byte outside the named keys,
     * or changed while the write was being prepared - this throws {@link ConfigWriteRefusedException} naming the reason;
     * the file keeps its bytes and the in-memory values stay as the module set them, still unsaved.
     *
     * @param entryPaths the {@link ConfigEntry#path()} of each setting to write (the field name for an entry declared
     *                   without a path), at least one
     * @throws IllegalArgumentException     if no path is given, or a path is not a declared entry of this configuration;
     *                                      nothing is written
     * @throws IllegalStateException        if called before {@code init}, or off the server thread while a server runs
     * @throws ConfigWriteRefusedException  if the configuration write gate refused the write; nothing is written
     * @throws IOException                  if publishing the verified file fails
     * @since 6.3.0
     */
    public final void saveOperatorChange(String... entryPaths) throws IOException {
        requireOperatorWriteThread("saveOperatorChange");
        synchronized (this) {
            if (entryPaths == null || entryPaths.length == 0) {
                throw new IllegalArgumentException("Name at least one configuration entry of " + configFilePath + " to write");
            }
            List<Field> named = new ArrayList<>();
            for (String entryPath : entryPaths) {
                Field field = declaredEntry(entryPath);
                if (!named.contains(field)) { named.add(field); }
            }
            List<ModuleChange> changes = new ArrayList<>();
            for (Field field : named) {
                changes.add(new ModuleChange(field, Collections.<String>emptyList(), keys(field), plainValue(field, true),
                        acknowledgedRaw.get(field)));
            }
            writeOperatorChanges(changes);
        }
    }

    /**
     * Writes exactly one entry of a map setting, as the module holds it now, because the operator explicitly asked for
     * that change - a command such as {@code /autoreply add}, which names one rule of {@code autoreply.rules}.
     * <p>
     * The entry is set to the module's value, inserted when the file lacks it; when the module's map no longer holds the
     * entry, the entry is removed from the file. The operator's request is their consent to replace what the file holds
     * at that entry. Every other entry - one the operator added or edited by hand since the file was read, a 6.2 split
     * entry - and every other key, value, comment and byte of the file stay (maintainer decision of 2026-10-04: write
     * exactly the item the operator explicitly asked to change). Each map key is one whole key: a rule named
     * {@code play.example} is the single key {@code play.example}.
     * <p>
     * <b>Why it cannot overwrite other operator content.</b> As {@link #saveOperatorChange(String...)}: the write owns
     * only that entry's key at the configuration write gate, which refuses any change to another byte of the file and
     * then throws {@link ConfigWriteRefusedException} naming the reason, leaving the file and the in-memory value as
     * they are. A map that is empty in the file ({@code {}}), or that the change empties, is owned as a whole, since its
     * key line changes with its first or last entry.
     *
     * @param entryPath the {@link ConfigEntry#path()} of a setting declared as a {@link Map}
     * @param mapKeys   the keys from that map down to the entry, one whole key each; usually just the entry's key
     * @throws IllegalArgumentException     if {@code entryPath} is not a declared entry of this configuration, the
     *                                      setting is not declared as a map, no key is given, or the keys reach inside
     *                                      an entry that is not itself a declared map (a serializable such as a
     *                                      {@code Location}, or a list, is one value: name the entry itself); nothing
     *                                      is written
     * @throws IllegalStateException        if called before {@code init}, or off the server thread while a server runs
     * @throws ConfigWriteRefusedException  if the configuration write gate refused the write; nothing is written
     * @throws IOException                  if publishing the verified file fails
     * @since 6.3.0
     */
    public final void saveOperatorMapEntry(String entryPath, String... mapKeys) throws IOException {
        requireOperatorWriteThread("saveOperatorMapEntry");
        synchronized (this) {
            Field field = declaredEntry(entryPath);
            if (!Map.class.isAssignableFrom(TypeToken.of(declaredType(field)).getRawType())) {
                throw new IllegalArgumentException("Configuration entry '" + entryPath + "' of " + configFilePath
                        + " is not a map setting");
            }
            if (mapKeys == null || mapKeys.length == 0) {
                throw new IllegalArgumentException("Name the map entry of '" + entryPath + "' in " + configFilePath + " to write");
            }
            List<String> leaf = new ArrayList<>();
            for (String key : mapKeys) {
                if (key == null) { throw new IllegalArgumentException("A map key of '" + entryPath + "' cannot be null"); }
                leaf.add(key);
            }
            if (splitDepth(declaredType(field), leaf) < leaf.size()) {
                // The entry is one value - a serializable or a list - and is written whole (17-65 review round 2 R2-01).
                throw new IllegalArgumentException("The map keys " + leaf + " of '" + entryPath + "' in " + configFilePath
                        + " reach inside an entry that is not a map; name the entry itself");
            }
            List<String> path = new ArrayList<>(keys(field)); path.addAll(leaf);
            writeOperatorChanges(Collections.singletonList(
                    new ModuleChange(field, leaf, path, plainValue(field, true), acknowledgedRaw.get(field))));
        }
    }

    private void requireOperatorWriteThread(String operation) {
        if (ultiToolsPlugin == null) { throw new IllegalStateException("Config not initialized. Call init() first."); }
        if (!com.ultikits.ultitools.manager.ConfigManager.permitsConfigThread(ultiToolsPlugin, operation + " " + configFilePath)) {
            throw new IllegalStateException("Configuration " + operation + " of " + configFilePath + " requires the server thread");
        }
    }

    /** The field declared at {@code entryPath} ({@link ConfigEntry#path()}, or the field name for an empty path). */
    private Field declaredEntry(String entryPath) {
        for (Field field : configEntryFields()) {
            if (fieldPath(field).equals(entryPath)) { return field; }
        }
        throw new IllegalArgumentException("'" + entryPath + "' is not a declared configuration entry of " + configFilePath);
    }

    /**
     * Writes an operator's explicit changes through the configuration write gate - the module's value at each change's
     * path, whatever the file holds there now (the operator's consent), each owned as a whole key - against one read
     * of the file, and advances the baseline and the last-read entry of exactly those paths. A refusal throws
     * {@link ConfigWriteRefusedException}; nothing is acknowledged then.
     */
    private void writeOperatorChanges(List<ModuleChange> changes) throws IOException {
        if (changes.isEmpty()) { return; }
        OperatorFileWriter.Result result = stageOperatorChanges(changes).commit();
        if (!result.applied()) { throw refused(result.reason()); }
        acknowledgeWritten(result.readFingerprint(), result.fingerprint(), result.document(), changes);
    }

    /**
     * Prepares an operator's explicit changes for the configuration write gate and stages the verified file (see
     * {@link #writeOperatorChanges}); a write the gate settles as refused, or a file that cannot be read or parsed,
     * throws {@link ConfigWriteRefusedException} here, before anything is staged.
     */
    private OperatorFileWriter.Staged stageOperatorChanges(List<ModuleChange> changes) throws IOException {
        java.nio.file.Path target = ultiToolsPlugin.getConfigFile(configFilePath).toPath();
        if (lastLoadUnparseable) {
            throw refused("the file could not be read or parsed when it was last loaded; reload a valid file first");
        }
        ConfigLoadResult loaded = ConfigDocument.load(target);
        if (protectFailedLoad(loaded)) { throw refused("the file cannot be read or parsed"); }
        ConfigDocument read = loaded.state() == ConfigLoadResult.State.LOADED ? loaded.document() : ConfigDocument.empty();
        for (ModuleChange change : changes) {
            // A field edited inside a composite value is written as the whole value built from what was last read: only
            // while the file still holds exactly that value - observed on the read the gate verifies against - never
            // rebuilt over an edit made since (17-65 route change after review round 3, R3-01).
            if (change.required != null && !change.holds(read.contains(change.path), read.get(change.path),
                    change.required.present, change.required.value)) {
                throw refused(describeKey(change.field, change.leaf) + ": the file changed since it was read; reload first");
            }
        }
        List<Field> inserted = new ArrayList<>();
        for (ModuleChange change : changes) {
            if (change.leaf.isEmpty() && !read.contains(change.path)) { inserted.add(change.field); }
        }
        OperatorFileWriter.Staged staged = OperatorFileWriter.stage(target, saveOwnership(changes, read), expectedBase(loaded),
                candidate -> {
                    apply(candidate, changes);
                    for (Field field : inserted) { addEntryComment(candidate, field); }
                });
        if (!staged.isPending() && !staged.settledResult().applied()) { throw refused(staged.settledResult().reason()); }
        return staged;
    }

    private ConfigWriteRefusedException refused(String reason) {
        return new ConfigWriteRefusedException(ultiToolsPlugin.getConfigFile(configFilePath).getAbsolutePath(), reason);
    }

    /**
     * A panel edit's changes: each touched setting as a whole, or - for a map setting edited leaf by leaf - each touched
     * leaf, at the value the edit applied. The operator named exactly these in the panel (maintainer decision of
     * 2026-10-04: write exactly the item the operator explicitly asked to change).
     */
    private List<ModuleChange> panelChanges(List<Field> touched, Map<Field, List<List<String>>> leaves) {
        List<ModuleChange> changes = new ArrayList<>();
        for (Field field : touched) {
            Object mine = plainValue(field, true);
            RawEntry read = acknowledgedRaw.get(field);
            Map<List<String>, RawEntry> required = compositeReads.get(field);
            Map<List<String>, Object> wholes = compositeWrites.get(field);
            List<List<String>> fieldLeaves = leaves.get(field);
            if (fieldLeaves == null) {
                ModuleChange change = new ModuleChange(field, Collections.<String>emptyList(), keys(field), mine, read);
                change.required = required == null ? null : required.get(Collections.<String>emptyList());
                if (change.required != null && wholes != null && wholes.containsKey(Collections.<String>emptyList())) {
                    change.writeWhole(wholes.get(Collections.<String>emptyList()));
                }
                changes.add(change);
                continue;
            }
            for (List<String> leaf : fieldLeaves) {
                List<String> path = new ArrayList<>(keys(field)); path.addAll(leaf);
                ModuleChange change = new ModuleChange(field, leaf, path, mine, read);
                change.required = required == null ? null : required.get(leaf);
                if (change.required != null && wholes != null && wholes.containsKey(leaf)) {
                    change.writeWhole(wholes.get(leaf));
                }
                changes.add(change);
            }
        }
        compositeReads.clear();
        compositeWrites.clear();
        return changes;
    }

    private void warnNotWritten(List<String> keys) {
        // Values are deliberately omitted: any key may hold a credential.
        Logger logger = UltiTools.getInstance() == null ? LOGGER : UltiTools.getInstance().getLogger();
        logger.log(Level.WARNING, "Configuration file " + ultiToolsPlugin.getConfigFile(configFilePath).getAbsolutePath()
                + ": the module's changes to " + String.join(", ", keys) + " were not written, because the file does"
                + " not hold the value they were made from there (edited, deleted or unusable since it was read)."
                + " The file keeps its text; the module's values stay in memory.");
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

    private Object plainValue(Field field) { return plainValue(field, false); }

    private Object plainValue(Field field, boolean writing) {
        field.setAccessible(true);
        try {
            ConversionResult<Object> result = registry().toPlainResult(ReflectionUtil.getFieldValue(this, field),
                    declaredType(field), configFilePath, keys(field), field.getAnnotation(ConfigEntry.class));
            if (writing && !result.failures().isEmpty()) {
                List<String> locations = new ArrayList<>();
                for (ConversionFailure failure : result.failures()) {
                    List<String> path = failure.path();
                    List<String> relative = path.subList(keys(field).size(), path.size());
                    boolean secret = isSecretShapedFieldName(field.getName());
                    for (String key : path) { secret |= isSecretShapedFieldName(key); }
                    locations.add(secret ? "<redacted>" : relative.toString());
                }
                LOGGER.warning("File " + configFilePath + ", key '" + fieldPath(field)
                        + "': omitted null collection/array elements at " + String.join(", ", locations));
            }
            return PlainData.copy(result.value());
        } catch (ConversionException failure) {
            throw new ConfigurationException(failure.getMessage(), failure);
        }
    }

    private Map<Field, Object> currentPlain(List<Field> fields) { return currentPlain(fields, false); }

    private Map<Field, Object> currentPlain(List<Field> fields, boolean writing) {
        Map<Field, Object> plain = new LinkedHashMap<>();
        for (Field field : fields) { plain.put(field, plainValue(field, writing)); }
        return plain;
    }

    private void captureDefaults() {
        if (!defaultsCaptured) {
            declaredDefaults.putAll(currentPlain(configEntryFields()));
            defaultsCaptured = true;
        }
    }

    private void acknowledgeRaw(ConfigDocument source, List<Field> fields) {
        for (Field field : fields) { acknowledgedRaw.put(field, new RawEntry(source, keys(field))); }
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

    /**
     * Writes the framework's comment above {@code field}'s key in {@code target}: for a token comment, only the
     * run of comment lines {@link #frameworkCommentRun} identifies as the framework's is replaced, and a comment
     * that is the operator's is left as it is; a literal comment is written only for a key just inserted, which
     * has no comment yet.
     *
     * @return whether a comment line changed
     */
    private boolean addEntryComment(ConfigDocument target, Field field) {
        String comment = resolvedComment(field);
        if (comment.isEmpty()) { return false; }
        int run = isTokenComment(field) ? frameworkCommentRun(target, field) : 0;
        // The operator's comment (#604): kept byte for byte, permanently (maintainer decision 2026-10-04).
        if (run < 0) { return false; }
        List<String> before = target.blockComment(keys(field));
        // Merge-inherited entries exist in the plain view but need their own explicit comment owner.
        target.set(keys(field), target.get(keys(field)));
        target.replaceFrameworkComment(keys(field), run, Collections.singletonList(comment));
        return !before.equals(target.blockComment(keys(field)));
    }

    /**
     * How many of the last comment lines above {@code field}'s key in {@code target} the framework wrote (#604,
     * maintainer decision 2026-10-04: "only the framework's own comments are rewritten"). The key's comment -
     * in the byte form it is written in ({@link ConfigDocument#blockCommentAsWritten(List)}), without the blank
     * lines above it - is the framework's when it equals, as a whole or as its trailing run of lines, the exact
     * form the framework writes (the key's column, {@code "# "} and the text; identification revision 1, 17-64
     * review round 1 R1-02) of the token's text in a catalogue the module's jar ships, of a text an earlier module
     * version shipped for the entry ({@link ConfigEntry#previousComments()}), of the text the module resolves now,
     * or of the bare {@code {key}} token. Equality is the only test: no prefix, similarity, spacing
     * or language tolerance, so a note the operator wrote above the framework's lines, a framework comment the
     * operator edited, or the framework's text written at another column or without the space after {@code #}
     * is never taken in. Of several matching texts the longest run counts, so a whole-comment match comes first.
     *
     * @return the run's length; 0 when the key has no comment (the framework's comment may be inserted); -1 when the
     *         comment is the operator's and must not be touched
     */
    private int frameworkCommentRun(ConfigDocument target, Field field) {
        List<String> comment = new ArrayList<>(target.blockCommentAsWritten(keys(field)));
        while (!comment.isEmpty() && comment.get(0) == null) { comment.remove(0); }
        if (comment.isEmpty()) { return 0; }
        int run = -1;
        for (List<String> known : frameworkRenderings(field)) {
            int size = known.size();
            if (size > run && size > 0 && size <= comment.size()
                    && comment.subList(comment.size() - size, comment.size()).equals(known)) {
                run = size;
            }
        }
        return run;
    }

    /**
     * Every rendering of a token comment the framework may have written above {@code field}'s key: the token's text
     * in each catalogue the module's jar ships (read without any language-file side effect), the texts earlier module
     * versions shipped for the entry ({@link ConfigEntry#previousComments()}), the text the module resolves now, and
     * the bare token - each in the byte form {@link ConfigDocument#setFrameworkComment} writes
     * it, as {@link ConfigDocument#blockCommentAsWritten(List)} reports it. An empty text is not a rendering: the
     * framework writes no comment for it, so it can never identify an operator's bare {@code #} line.
     */
    private List<List<String>> frameworkRenderings(Field field) {
        String token = field.getAnnotation(ConfigEntry.class).comment().trim();
        String key = token.substring(1, token.length() - 1);
        Set<List<String>> known = new java.util.LinkedHashSet<>();
        List<String> shipped = null;
        try { shipped = ultiToolsPlugin.shippedCatalogueTexts(key); }
        catch (RuntimeException unavailable) {
            // Without the shipped catalogues only the current text and the bare token are recognised.
        }
        List<String> texts = new ArrayList<>();
        if (shipped != null) { texts.addAll(shipped); }
        // Texts earlier module versions shipped for this entry (maintainer decision 2026-10-04: registered as the framework's).
        texts.addAll(Arrays.asList(field.getAnnotation(ConfigEntry.class).previousComments()));
        texts.add(resolvedComment(field));
        texts.add(token);
        for (String text : texts) {
            // The framework never writes an empty comment (addEntryComment), so an empty text renders nothing of its.
            if (text != null && !text.isEmpty()) { known.add(renderedComment(text)); }
        }
        return new ArrayList<>(known);
    }

    /** The comment lines {@code text} becomes when the framework writes it, in their written byte form. */
    private static List<String> renderedComment(String text) {
        ConfigDocument presentation = ConfigDocument.empty();
        List<String> key = Collections.singletonList("key");
        presentation.set(key, null);
        presentation.setFrameworkComment(key, Collections.singletonList(text));
        return presentation.blockCommentAsWritten(key);
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
     * Whether serialized fields differ from their last bound/persisted effective values - module changes not yet saved.
     * Map iteration order is significant here; the storage equality used for no-op saves is not.
     * Nothing writes them at server stop: they are reported, by key, and dropped (maintainer decision 2026-10-04).
     * Protected files and uninitialized entities report {@code false}.
     * @return whether the entity holds module changes that were never saved
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final boolean isModifiedSinceSnapshot() {
        synchronized (this) {
            if (document == null || ultiToolsPlugin == null || lastLoadUnparseable) { return false; }
            if (savedSnapshot == null) { return true; }
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
     * in practice, whether someone edited, replaced or removed it while the server was running. The
     * snapshot is the bytes this entity last bound or wrote itself, never a fresh read, so an
     * operator's edit stays visible here until the next load.
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
     * practice, whether the file on disk holds invalid YAML. Nothing writes configuration at server
     * stop as of 6.3.0; the stop report names such a configuration once as left alone, instead of
     * listing unsaved keys, and no write path ever replaces a file the framework could not read.
     * <p>
     * Framework-internal: this method is called only by {@code ConfigManager}'s stop and unload report and is
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
     * restore it). Configuration files are small and this runs only at load, reload and write time.
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

    @SuppressWarnings("PMD.NPathComplexity") // Keep protected load, binding, three-way merge and initialization persistence under one monitor.
    private void load(boolean initialize) throws IOException {
        warnedCommentKeys.clear();
        commentWriteNotAppliedAtLoad = false;
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
            if (!next.contains(keys(field))) {
                missing.add(field);
                // #596 item 2: a key the operator deleted, for a setting the module did not change, resets to its
                // declared default; a setting the module changed keeps the module's value (#511 three-way rule).
                if (!initialize && savedSnapshot != null && savedSnapshot.containsKey(field)
                        && orderedEquals(savedSnapshot.get(field), mine.get(field))) {
                    bindDeclaredDefault(field, presentIn(lastLoadedPresence, keys(field)));
                }
                continue;
            }
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
                Object merged = mergeReload(savedSnapshot.get(field), mine.get(field), theirs, declaredType(field),
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
        Map<Field, Object> inserted = new LinkedHashMap<>();
        if (initialize) {
            for (Field field : missing) {
                Object value = plainValue(field, true);
                inserted.put(field, value); baseline.put(field, value);
            }
        }
        String bound = expectedBase(loaded);
        if (!inserted.isEmpty() && !deferInitialization) {
            // On a refusal the declared defaults run in memory and the raw acknowledgement keeps the keys absent.
            OperatorFileWriter.Result result = null;
            try { result = writeInitialization(inserted, bound, next); }
            catch (RuntimeException failure) { warnGateFailure("insert the missing keys into", failure); }
            if (result != null && result.applied()) { document = result.document(); bound = result.fingerprint(); }
        } else if (deferInitialization) {
            if (!inserted.isEmpty() || tokenCommentsDiffer(next)) {
                // The flush writes through the gate against the bytes read here, never over a later edit (#602).
                pendingInitialization = new PendingInitialization(next, baseline, inserted, expectedBase(loaded));
                lastLoadedPresence = loadedPresence;
                return;
            }
        } else if (tokenCommentsDiffer(next)) {
            // On a refusal or a failure the file keeps its comments and no save state changes (#603).
            OperatorFileWriter.Result result = rewriteTokenComments(loaded);
            if (result != null && result.applied()) { document = result.document(); bound = result.fingerprint(); }
            else { commentWriteNotAppliedAtLoad = true; }
        }
        lastLoadedPresence = loadedPresence;
        savedSnapshot = baseline;
        acknowledgeRaw(document, configEntryFields());
        // The bytes this entity bound or the gate wrote, never a fresh read: a later edit stays a change on disk.
        savedFileFingerprint = entityFingerprint(bound);
        for (String conflict : conflicts) { LOGGER.warning("Configuration " + configFilePath + ": " + conflict); }
    }

    /**
     * Binds {@code field}'s initially declared default because a reload found its key missing from the file and the
     * module had not changed the setting since the last load or save (#596 item 2): deleting a key resets that setting,
     * as an unusable value already does (#523). Memory only - the file is not changed, and a later save does not re-add
     * the key. When the key was in the file at the previous load - the operator deleted it - one warning names the file
     * and the key, never a value; a key the file already lacked (one a refused insert could not add, already named
     * when that write was refused) is not named again at every reload.
     *
     * @param field   the setting whose key is missing
     * @param deleted whether the previous load found the key in the file
     */
    private void bindDeclaredDefault(Field field, boolean deleted) {
        try {
            Object value = registry().fromPlainResult(declaredDefaults.get(field), declaredType(field), configFilePath,
                    keys(field), field.getAnnotation(ConfigEntry.class)).value();
            ReflectionUtil.setFieldValue(this, field, value);
        } catch (ConversionException invalidDefault) {
            throw new ConfigurationException(invalidDefault.getMessage(), invalidDefault);
        }
        if (deleted) {
            LOGGER.warning("File " + configFilePath + ", key '" + fieldPath(field) + "': missing from the file; using the"
                    + " declared default (the file is not changed)");
        }
    }

    /** Whether {@code presence} (a load's plain tree) holds {@code path}, an explicit null included. */
    private static boolean presentIn(Map<String, Object> presence, List<String> path) {
        Object current = presence;
        for (String key : path) {
            if (!(current instanceof Map) || !((Map<?, ?>) current).containsKey(key)) { return false; }
            current = ((Map<?, ?>) current).get(key);
        }
        return true;
    }

    /**
     * An unchecked failure inside the configuration write gate during start-up never refuses the module: one warning
     * names the file and the failure's class (never a message, which may quote file content), the declared defaults run
     * in memory, and the file is not changed (17-65 review round 2 R2-02).
     */
    private void warnGateFailure(String action, RuntimeException failure) {
        LOGGER.warning("Cannot " + action + " " + configFilePath + ": " + failure.getClass().getSimpleName()
                + "; the declared defaults are used in memory and the file is unchanged");
    }

    private static String expectedBase(ConfigLoadResult loaded) {
        return loaded.state() == ConfigLoadResult.State.LOADED ? loaded.fingerprint() : OperatorFileWriter.ABSENT;
    }

    /**
     * Whether a framework token comment in {@code target} differs from what the current language renders,
     * without changing {@code target}: the framework's own run ({@link #frameworkCommentRun}) compared, in its
     * written byte form, with the current rendering; a comment that is the operator's never differs.
     *
     * @param target the document as read
     * @return whether a comment-only write would change a comment
     */
    private boolean tokenCommentsDiffer(ConfigDocument target) {
        for (Field field : configEntryFields()) {
            if (!isTokenComment(field) || !target.contains(keys(field))) { continue; }
            int run = frameworkCommentRun(target, field);
            if (run < 0 || resolvedComment(field).isEmpty()) { continue; }
            List<String> current = target.blockCommentAsWritten(keys(field));
            if (!current.subList(current.size() - run, current.size()).equals(renderedComment(resolvedComment(field)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The token comments of {@code read} the framework may rewrite, each owning only the run of lines
     * {@link #frameworkCommentRun} identified as the framework's in the file as read (#604): the config write gate
     * then refuses any write that changes a comment line above that run or of a comment that is the operator's.
     *
     * @param owned the ownership being built
     * @param read  the document as read, holding the bytes the write is checked against
     */
    private void ownFrameworkComments(OwnedPaths.Builder owned, ConfigDocument read) {
        for (Field field : configEntryFields()) {
            if (!isTokenComment(field) || !read.contains(keys(field))) { continue; }
            int run = frameworkCommentRun(read, field);
            if (run >= 0) { owned.frameworkComment(keys(field), run); }
        }
    }

    /**
     * Rewrites the framework's token comments in the current language through the config write gate
     * ({@link OperatorFileWriter}), at start-up and on reload. This cannot overwrite operator content: the write
     * owns only the comment lines the framework identified as its own above token-commented keys (#604; an
     * operator's comment, or the lines above the framework's run, are not owned), the gate verifies that every
     * other byte of the file is unchanged after rendering and writes nothing when the file no longer holds the
     * bytes {@code loaded} read, and it refuses a file using anchors. A refusal or an I/O failure logs one warning
     * and changes no save state, so no later save follows from it (#603); nothing is written at stop.
     *
     * @param loaded the load being bound (LOADED)
     * @return the gate's result, or {@code null} after an I/O failure
     */
    private OperatorFileWriter.Result rewriteTokenComments(ConfigLoadResult loaded) {
        OwnedPaths.Builder owned = OwnedPaths.builder();
        ownFrameworkComments(owned, loaded.document());
        try {
            OperatorFileWriter.Result result = OperatorFileWriter.write(ultiToolsPlugin.getConfigFile(configFilePath).toPath(),
                    owned.build(), expectedBase(loaded), this::updateTokenComments);
            return result;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warning("Cannot rewrite comments in " + configFilePath + ": "
                    + failure.getClass().getSimpleName() + "; the file keeps its comments");
            return null;
        }
    }

    /**
     * Rewrites this configuration's framework comment lines in the owning module's language as it is now (#594).
     * <p>
     * A reload reads the module's configurations before it rebuilds the module's language, so the comment pass of
     * that read resolved the tokens with the catalogue of the language the module ran with until then.
     * {@code UltiToolsPlugin}'s reload calls this, through {@code ConfigManager}, right after the rebuild and before
     * the module's own reload hook, so a {@code language} switch applied by {@code /ul reload} reaches the comments
     * too (maintainer decision 2026-10-04, "the framework only refreshes comments on reload").
     * <p>
     * <b>Why it cannot overwrite operator content.</b> It reads the file afresh and hands the config write gate
     * ({@link OperatorFileWriter}) a comment-only write that owns just the comment lines the framework identifies
     * as its own above token-commented keys ({@link #frameworkCommentRun}, #604), checked against the bytes of that
     * fresh read: no value, no key and no other comment line can change, so an operator's invalid value, a key
     * deleted to reset it, a hand-written note and an edit saved after the reload read the file all stay as typed,
     * and a file that changed again before publishing, uses anchors or has a layout the renderer would normalize is
     * refused with the gate's one warning. Nothing is attempted when the last load refused the file as unreadable
     * or unparseable (it is never written), while a first-start write is still pending, or when no framework
     * comment differs from the current language, nor when this reload's own load already attempted a comment write
     * that was refused or failed: that attempt logged the file's one warning for this reload, and the next reload
     * tries again (17-64 review round 1 R1-03). A refusal or a failure logs one warning and changes no save
     * state, so no later save follows from it (#603, #597 review F2); nothing is written at stop. Only when the fresh read
     * still held exactly the bytes this entity bound does the entity record what the gate wrote as its last read;
     * otherwise the operator's newer file stays a change on disk.
     * <p>
     * Framework-internal: {@code public} solely because {@code ConfigManager} lives in another package. Module
     * code should not call it.
     *
     * @since 6.3.0
     */
    @ApiStatus.Internal
    public final void refreshFrameworkComments() {
        if (!com.ultikits.ultitools.manager.ConfigManager.permitsConfigThread(ultiToolsPlugin,
                "refresh comments " + configFilePath)) { return; }
        synchronized (this) {
            if (document == null || ultiToolsPlugin == null || lastLoadUnparseable || pendingInitialization != null
                    || commentWriteNotAppliedAtLoad) {
                return;
            }
            ConfigLoadResult fresh = ConfigDocument.load(ultiToolsPlugin.getConfigFile(configFilePath).toPath());
            if (fresh.state() != ConfigLoadResult.State.LOADED || !tokenCommentsDiffer(fresh.document())) { return; }
            OperatorFileWriter.Result result = rewriteTokenComments(fresh);
            if (result != null && result.applied() && entityFingerprint(fresh.fingerprint()).equals(savedFileFingerprint)) {
                // The file held exactly the bytes this entity bound; it now holds them with the refreshed comments.
                document = result.document();
                savedFileFingerprint = entityFingerprint(result.fingerprint());
            }
        }
    }

    /**
     * Writes an initialization - the declared keys {@code init} found missing, each with its comment, and the
     * framework's token comments - through the config write gate ({@link OperatorFileWriter}), at once or from
     * the batch flush. This cannot overwrite operator content: the write owns only the inserted keys and the
     * token comment lines the framework identified as its own (#604) (or the whole file when it was absent,
     * created exclusively so a file that appeared
     * meanwhile is never replaced), the gate verifies that every other byte of the file is unchanged after
     * rendering (layout included), and it writes nothing when the file no longer holds the bytes read at
     * {@code expected}. When it does not write, the gate has logged one warning naming the file and the keys,
     * the fields keep their declared defaults in memory, and the file keeps its bytes.
     *
     * @param inserted the missing fields and their declared default values, as plain data
     * @param expected the fingerprint of the bytes the init read, or {@link OperatorFileWriter#ABSENT}
     * @param read     the document the init read from those bytes
     * @return the gate's result; when it applied the edit, its document and fingerprint are what the file holds
     * @throws IOException if publishing the verified text fails
     */
    private OperatorFileWriter.Result writeInitialization(Map<Field, Object> inserted, String expected, ConfigDocument read)
            throws IOException {
        boolean absent = OperatorFileWriter.ABSENT.equals(expected);
        OwnedPaths owned = OwnedPaths.wholeFile();
        if (!absent) {
            OwnedPaths.Builder builder = OwnedPaths.builder();
            for (Field field : inserted.keySet()) { builder.value(keys(field)); }
            ownFrameworkComments(builder, read);
            owned = builder.build();
        }
        OperatorFileWriter.Result result = OperatorFileWriter.write(ultiToolsPlugin.getConfigFile(configFilePath).toPath(),
                owned, expected, candidate -> {
                    for (Map.Entry<Field, Object> entry : inserted.entrySet()) {
                        candidate.set(keys(entry.getKey()), entry.getValue());
                        addEntryComment(candidate, entry.getKey());
                    }
                    updateTokenComments(candidate);
                });
        return result;
    }

    /**
     * The fingerprint form {@link #fingerprintOf} uses, for a storage-layer SHA-256 in lower-case hex (or
     * {@link OperatorFileWriter#ABSENT}): what the entity records as the bytes it last bound or wrote (#602).
     */
    private static String entityFingerprint(String sha256Hex) {
        if (OperatorFileWriter.ABSENT.equals(sha256Hex)) { return "absent"; }
        byte[] digest = new byte[sha256Hex.length() / 2];
        for (int i = 0; i < digest.length; i++) {
            digest[i] = (byte) Integer.parseInt(sha256Hex.substring(2 * i, 2 * i + 2), 16);
        }
        return Base64.getEncoder().encodeToString(digest);
    }

    /**
     * The #511 three-way reload merge of one setting. Only a value declared as a {@link Map} is merged key by key,
     * following the declared value types into nested maps; any other value - a list, a {@code ConfigurationSerializable}
     * such as a Bukkit {@code Location} or {@code Vector}, a typed map's value that is not a map - is one value: the
     * module's, the file's, or on a conflict the file's whole, never a value mixed from both (17-65 review round 2 R2-01,
     * the same rule as the save's {@link #changedLeaves}). A conflict over a composite value or a list names the key only.
     */
    @SuppressWarnings("PMD.NPathComplexity") // The recursive three-way merge explicitly distinguishes absence, order and secret-valued conflicts.
    private Object mergeReload(Object base, Object mine, Object theirs, Type declared, String path, boolean secret,
            List<String> conflicts) {
        if (orderedEquals(mine, base)) { return theirs; }
        if (orderedEquals(theirs, base) || orderedEquals(mine, theirs)) { return mine; }
        if (theirs instanceof Map && !((Map<?, ?>) theirs).containsKey("==") && splitsByEntry(declared, base, mine)) {
            Type valueType = mapValueType(declared);
            Map<?, ?> b = (Map<?, ?>) base; Map<?, ?> m = (Map<?, ?>) mine; Map<?, ?> t = (Map<?, ?>) theirs;
            Set<Object> keys = new java.util.LinkedHashSet<>(); keys.addAll(t.keySet()); keys.addAll(m.keySet()); keys.addAll(b.keySet());
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object key : keys) {
                Object value = mergeReload(b.containsKey(key) ? b.get(key) : ABSENT_RELOAD_VALUE,
                        m.containsKey(key) ? m.get(key) : ABSENT_RELOAD_VALUE,
                        t.containsKey(key) ? t.get(key) : ABSENT_RELOAD_VALUE, valueType,
                        path + "." + key, secret || isSecretShapedFieldName(String.valueOf(key)), conflicts);
                if (value != ABSENT_RELOAD_VALUE) { merged.put(String.valueOf(key), value); }
            }
            return merged;
        }
        if (mine instanceof Map || mine instanceof List) {
            // A composite value or a list is replaced whole; its contents are not listed (they may hold anything;
            // 17-65 review round 3 R3-I3).
            conflicts.add("reload conflict at '" + path + "': discarded the in-memory value; file wins");
            return theirs;
        }
        String value = secret || isSecretShapedFieldName(path) || containsSecret(mine) ? "<redacted>"
                : mine == ABSENT_RELOAD_VALUE ? "<absent>" : String.valueOf(mine);
        conflicts.add("reload conflict at '" + path + "': discarded in-memory value " + value + "; file wins");
        return theirs;
    }

    /** Flushes only a validated manager batch's initialization write, through the config write gate and only
     * while the file still holds the bytes {@code initForBatch} read (#602); see {@link #writeInitialization}.
     * @throws IOException when replacement fails; the entity is then protected until reload
     */
    @ApiStatus.Internal
    public final void flushInitializationWrite() throws IOException {
        synchronized (this) {
            PendingInitialization pending = pendingInitialization;
            pendingInitialization = null;
            if (pending == null || lastLoadUnparseable) { return; }
            OperatorFileWriter.Result result;
            try { result = writeInitialization(pending.inserted, pending.expected, pending.read); }
            catch (IOException failure) { lastLoadUnparseable = true; throw failure; }
            catch (RuntimeException failure) {
                warnGateFailure("insert the missing keys into", failure);
                document = pending.read;
                savedSnapshot = pending.baseline;
                acknowledgeRaw(document, configEntryFields());
                savedFileFingerprint = entityFingerprint(pending.expected);
                return;
            }
            // Not written (file changed since initForBatch, or refused): the defaults run in memory, the file stays,
            // and the entity records the bytes it bound - never the operator's newer file - as last read (#602).
            document = result.applied() ? result.document() : pending.read;
            savedSnapshot = pending.baseline;
            acknowledgeRaw(document, configEntryFields());
            savedFileFingerprint = entityFingerprint(result.applied() ? result.fingerprint() : pending.expected);
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

    @SuppressWarnings("PMD.NPathComplexity") // Diagnostic traversal distinguishes list positions, whole keys and inherited secret boundaries.
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
     * Since 6.3.0 a panel edit writes exactly the settings it touches - or, inside a map setting, exactly the touched
     * entries - through the framework's configuration write gate: the operator named them, so their values replace
     * what the file holds there, and every other key, value, comment and byte of the file stays as it is (maintainer
     * decision of 2026-10-04, "what code may write, by file type"). It cannot overwrite other operator content: the
     * gate owns only the touched keys and writes nothing unless every other line comes out byte-identical. When the
     * gate refuses (anchors, a layout it cannot keep, a file that cannot be read or parsed, or one that changed while
     * the write was prepared), this throws {@link ConfigWriteRefusedException} naming the reason and the entity is left
     * exactly as before the call.
     * <p>
     * Since 6.3.0 (SILENT-14, closing CR-01) this method validates the full post-update field
     * state - the same {@link #validateFields()} {@link #init(UltiToolsPlugin)}/{@link
     * #reload()} already use - before either document mutation or file persistence
     * runs. A violating value refuses with {@link ConfigurationException} instead of being
     * written: the operator's file is left byte-identical, and every field this call touched is
     * restored to the value it held before the call, so memory never disagrees with disk (D-01,
     * D-04). The entity keeps running after a refusal, so its in-memory state must not be left
     * holding a rejected value; {@link #reload()} follows the same all-or-nothing rule. The same
     * holds when the file replacement itself fails: every field and every piece of save
     * tracking is restored to its state before the call, and the {@code IOException} is rethrown.
     *
     * @param jsonObject the JSON object containing the new properties
     * @throws IOException            if an I/O error occurs, or the write gate refused the write
     *                                 ({@link ConfigWriteRefusedException}); the entity is then left as it was
     * @throws ConfigurationException with {@link com.ultikits.ultitools.exceptions.ErrorCode#CONFIG_VALIDATION_FAILED}
     *                                 if the post-update field state violates a {@code @Range}/
     *                                 {@code @NotEmpty}/{@code @Size}/{@code @Pattern} constraint
     *                                 - the file is not written and touched fields are restored
     */
    public void updateProperties(JsonObject jsonObject) throws IOException {
        synchronized (this) {
            PanelCheckpoint before = new PanelCheckpoint();
            boolean saved = false;
            try {
                List<Field> touchedFields = new ArrayList<>();
                Map<Field, List<List<String>>> leaves = applyAndValidate(jsonObject, touchedFields, new ArrayList<>());
                writeOperatorChanges(panelChanges(touchedFields, leaves));
                saved = true;
            } finally {
                // A refusal or a failed file replacement leaves the entity exactly as before.
                if (!saved) { before.restore(); }
            }
        }
    }

    /** Prepares one panel entity without acknowledging or replacing its file: the touched settings or leaves are
     * verified and staged through the configuration write gate (see {@link #updateProperties}); the commit re-checks
     * the file's bytes immediately before its move.
     * @param properties proposed panel values
     * @return manager-owned write
     * @throws IOException if reading or staging fails, or the gate refused the write ({@link ConfigWriteRefusedException})
     */
    @ApiStatus.Internal
    public final PanelWrite preparePanelWrite(JsonObject properties) throws IOException {
        synchronized (this) {
            PanelCheckpoint before = new PanelCheckpoint();
            try {
                List<Field> touched = new ArrayList<>();
                Map<Field, List<List<String>>> leaves = applyAndValidate(properties, touched, new ArrayList<>());
                List<ModuleChange> changes = panelChanges(touched, leaves);
                java.nio.file.Path target = ultiToolsPlugin.getConfigFile(configFilePath).toPath();
                OperatorFileWriter.Staged staged = changes.isEmpty() ? null : stageOperatorChanges(changes);
                Map<Field, Object> bound = new LinkedHashMap<>();
                for (Field field : touched) { bound.put(field, ReflectionUtil.getFieldValue(this, field)); }
                before.restore();
                return new PanelWrite(before, changes, target, staged, bound);
            } catch (IOException | RuntimeException failure) {
                before.restore(); throw failure;
            }
        }
    }

    /** Complete entity state before a panel candidate or a reload attempt, including raw and effective acknowledgments. */
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
            deferInitialization = deferred; pendingInitialization = initialization;
            warnedCommentKeys.clear(); warnedCommentKeys.addAll(warningKeys);
        }
    }

    /** Narrow manager coordination for one staged entity; not a module transaction API. */
    @ApiStatus.Internal
    public final class PanelWrite {
        private final PanelCheckpoint before;
        private final List<ModuleChange> changes;
        private final java.nio.file.Path target;
        private final OperatorFileWriter.Staged staged;
        private OperatorFileWriter.Result committed;
        private final Map<Field, Object> bound;
        private PanelWrite(PanelCheckpoint before, List<ModuleChange> changes, java.nio.file.Path target,
                OperatorFileWriter.Staged staged, Map<Field, Object> bound) {
            this.before = before; this.changes = changes; this.target = target;
            this.staged = staged; this.bound = bound;
        }
        /**
         * Publishes this file through the configuration write gate only while it still holds exactly the bytes the
         * write was verified against; a file the operator saved after staging is kept and refused.
         * @throws IOException if the replacement fails, or {@link ConfigWriteRefusedException} when the file changed
         *                     since it was staged (nothing was moved)
         */
        public void commit() throws IOException {
            if (staged == null) { return; }
            OperatorFileWriter.Result result = staged.commit();
            if (!result.applied()) { throw refused(result.reason()); }
            committed = result;
        }
        /** Acknowledges only after every manager-owned replacement succeeds. */
        public void acknowledge() {
            synchronized (AbstractConfigEntity.this) {
                for (Map.Entry<Field, Object> entry : bound.entrySet()) {
                    ReflectionUtil.setFieldValue(AbstractConfigEntity.this, entry.getKey(), entry.getValue());
                }
                if (committed != null) {
                    acknowledgeWritten(committed.readFingerprint(), committed.fingerprint(), committed.document(), changes);
                }
            }
        }
        /** Restores a target this write replaced to exactly the bytes it was verified against - through the gate's
         * last-moment check, so a file the operator saved after this write replaced it is kept and named once
         * ({@link OperatorFileWriter.Staged#restore()}, 17-65 review round 1 R65-I3) - and all entity state; always
         * discards its staged file.
         * @throws IOException if physical recovery fails
         */
        public void rollback() throws IOException {
            synchronized (AbstractConfigEntity.this) {
                try {
                    if (staged != null) { staged.restore(); }
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
    @SuppressWarnings("PMD.NPathComplexity") // Resolve every panel owner and validate every candidate before publishing any field.
    private Map<Field, List<List<String>>> applyAndValidate(JsonObject jsonObject, List<Field> touchedFieldsOut, List<Object> previousValuesOut) {
        if (lastLoadUnparseable) {
            throw new ConfigurationException("Protected configuration file " + configFilePath
                    + ": its last load was unreadable or unparseable; reload a valid file before editing");
        }
        Map<Field, Object> proposed = new LinkedHashMap<>();
        Map<Field, List<List<String>>> leaves = new LinkedHashMap<>();
        compositeReads.clear();
        compositeWrites.clear();
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
            RawEntry lastRead = acknowledgedRaw.get(owner);
            List<List<String>> matches = new ArrayList<>();
            matchMapPaths(source, path.substring(fieldPath(owner).length() + 1), new ArrayList<>(), matches);
            if (matches.size() != 1) {
                refused.add("'" + path + "': " + (matches.isEmpty() ? "not found" : "ambiguous " + matches));
                continue;
            }
            List<String> match = matches.get(0);
            int depth = splitDepth(declaredType(owner), match);
            if (depth < match.size()) {
                // An edit inside a value that is one value (a serializable, a list): the unit edited is that whole value, as
                // the panel shows it with this field changed, so a module's unsaved change of the same value is not mixed
                // in (17-65 review round 2 R2-01).
                List<String> unit = new ArrayList<>(match.subList(0, depth));
                List<String> inside = match.subList(depth, match.size());
                // The unit is built from the whole value as last read, and that value becomes a precondition checked on
                // the file at write time (route change, R3-01): an edit made on disk since is refused, never rebuilt over.
                boolean readHolds = lastRead != null && lastRead.present && (unit.isEmpty() || mapContains(lastRead.value, unit));
                if (!readHolds) { refused.add("'" + path + "': the file changed since it was read; reload first"); continue; }
                Object readUnit = unit.isEmpty() ? lastRead.value : mapLeaf(lastRead.value, unit);
                compositeReads.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(unit, new RawEntry(true, readUnit));
                // The edited field takes the number type the module's own value holds there (a whole number sent for a
                // Vector coordinate becomes 7.0), so the value written is one the module reads back (#609).
                Object edited = isSerializedMap(readUnit) ? widenLike(raw, mapLeaf(mapLeaf(plainValue(owner), unit), inside))
                        : raw;
                if (unit.isEmpty()) {
                    Object shown = whole.contains(owner) && proposed.containsKey(owner) ? proposed.get(owner) : readUnit;
                    proposed.put(owner, patchedMap(shown, inside, edited)); leaves.remove(owner); whole.add(owner);
                    continue;
                }
                Object tree = proposed.containsKey(owner) ? proposed.get(owner) : plainValue(owner);
                if (!(tree instanceof Map)) { refused.add("'" + path + "': not a map entry"); continue; }
                if (!parentInMemory(tree, unit)) { refused.add("'" + path + "': not found in memory"); continue; }
                List<List<String>> touched = leaves.get(owner);
                Object shown = whole.contains(owner) || touched != null && touched.contains(unit) ? mapLeaf(tree, unit)
                        : readUnit;
                replaceMapLeaf(tree, unit, patchedMap(shown, inside, edited));
                proposed.put(owner, tree);
                if (!whole.contains(owner) && (touched == null || !touched.contains(unit))) {
                    leaves.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(unit);
                }
                continue;
            }
            Object tree = proposed.containsKey(owner) ? proposed.get(owner) : plainValue(owner);
            if (!(tree instanceof Map)) { refused.add("'" + path + "': not a map entry"); continue; }
            // A group the module removed in memory (unsaved) is not there to edit: refused, never a runtime error (R4-I3).
            if (!parentInMemory(tree, match)) { refused.add("'" + path + "': not found in memory"); continue; }
            replaceMapLeaf(tree, match, raw);
            proposed.put(owner, tree);
            if (!whole.contains(owner)) { leaves.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(match); }
        }
        for (Map.Entry<Field, Map<List<String>, RawEntry>> reads : compositeReads.entrySet()) {
            Object proposal = proposed.get(reads.getKey());
            Map<List<String>, Object> wholes = new LinkedHashMap<>();
            for (List<String> unit : reads.getValue().keySet()) {
                // The whole value as last read with only the edited fields changed: what the file gets (R4-I2).
                wholes.put(unit, PlainData.copy(mapLeaf(proposal, unit)));
            }
            compositeWrites.put(reads.getKey(), wholes);
        }
        Map<Field, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<Field, Object> proposal : proposed.entrySet()) {
            Field field = proposal.getKey();
            try {
                // Whole numbers inside a serialized composite are widened where the module's own value holds a
                // floating-point number there, for the conversion only (#609, R4-I1): Bukkit's Vector reads its
                // coordinates as Double without widening; the bytes the operator wrote are not changed by this.
                Object candidate = widenLike(proposal.getValue(), plainValue(field), false);
                ConversionResult<Object> result = registry().fromPlainResult(candidate, declaredType(field),
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

    /** Whether the map holding the last key of {@code path} exists in {@code tree} (the module's value in memory). */
    private static boolean parentInMemory(Object tree, List<String> path) {
        return (path.size() == 1 ? tree : mapLeaf(tree, path.subList(0, path.size() - 1))) instanceof Map;
    }

    /** Whether {@code value} is a serialized Bukkit value: a map carrying the {@code ==} type key. */
    private static boolean isSerializedMap(Object value) {
        return value instanceof Map && ((Map<?, ?>) value).containsKey("==");
    }

    /**
     * {@code value} with each whole number inside a serialized Bukkit value ({@link #isSerializedMap}) widened to a
     * {@code Double} where {@code guide} - the module's own value, as plain data - holds a floating-point number at the
     * same place; everything else as it is. A whole number given directly ({@code guide} a floating-point number) is
     * widened too. Used for a panel edit's conversion and for the edited field it writes (#609, R4-I1).
     */
    private static Object widenLike(Object value, Object guide) {
        return widenLike(value, guide, true);
    }

    private static Object widenLike(Object value, Object guide, boolean inSerialized) {
        if (inSerialized && (guide instanceof Double || guide instanceof Float) && (value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte)) {
            return ((Number) value).doubleValue();
        }
        if (!(value instanceof Map) || !(guide instanceof Map)) { return value; }
        boolean serialized = isSerializedMap(value);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            Object nested = ((Map<?, ?>) guide).get(entry.getKey());
            result.put(String.valueOf(entry.getKey()), widenLike(entry.getValue(), nested, serialized));
        }
        return result;
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

    /** A copy of {@code tree} without the entry at {@code path} (a copy of {@code tree} when it has none). */
    private static Object withoutLeaf(Object tree, List<String> path) {
        if (!(tree instanceof Map)) { return PlainData.copy(tree); }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) tree).entrySet()) {
            copy.put(String.valueOf(entry.getKey()), PlainData.copy(entry.getValue()));
        }
        String key = path.get(0);
        if (path.size() == 1) { copy.remove(key); }
        else if (copy.containsKey(key)) { copy.put(key, withoutLeaf(copy.get(key), path.subList(1, path.size()))); }
        return copy;
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

    // Recursive JSON shape dispatch preserves exact integral overflow before typed conversion.
    @SuppressWarnings("PMD.NPathComplexity")
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
        try {
            java.math.BigInteger integer = primitive.getAsBigDecimal().toBigIntegerExact();
            try { return integer.longValueExact(); }
            catch (ArithmeticException overflow) { return integer; }
        } catch (ArithmeticException fractional) { return primitive.getAsDouble(); }
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
     * <p>
     * Since 6.3.0 a reload is all-or-nothing: if it fails for any reason (a validation
     * violation, a conversion or I/O failure), every field value and every piece of load
     * tracking is restored to what it was before the attempt, and the failure is rethrown
     * without notifying listeners. A rejected file value therefore never becomes an unsaved
     * in-memory edit that the next reload's three-way merge would keep over a corrected file.
     *
     * @throws IOException if an I/O error occurs
     */
    public void reload() throws IOException {
        if (!com.ultikits.ultitools.manager.ConfigManager.permitsConfigThread(ultiToolsPlugin, "reload " + configFilePath)) { return; }
        if (ultiToolsPlugin == null) { throw new IllegalStateException("Config not initialized. Call init() first."); }
        synchronized (this) {
            registry().checkEntityFields(getClass(), ultiToolsPlugin.getPluginName(), configFilePath);
            PanelCheckpoint before = new PanelCheckpoint();
            boolean loaded = false;
            try {
                load(false);
                loaded = true;
            } finally {
                // Restore on every failure, unchecked errors included, then let it propagate.
                if (!loaded) { before.restore(); }
            }
        }
        notifyChangeListeners();
    }
}
