package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.File;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.Comparison;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.RowCountingDelete;
import com.ultikits.ultitools.manager.JsonTransactionManager;
import com.ultikits.ultitools.utils.BeanCopyUtil;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.FrameworkText;
import com.ultikits.ultitools.utils.JsonPathUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Simple Json data operator.
 *
 * @param <T> Data type inherited from BaseDataEntity
 * @author wisdomme
 * @version 1.0.0
 */
public class SimpleJsonDataOperator<T extends BaseDataEntity<String>>
        implements DataOperator<T>, Cached, RowCountingDelete {
    private static final Logger LOGGER = Logger.getLogger(SimpleJsonDataOperator.class.getName());
    /**
     * Default Gson has no bundled adapter for {@code java.time.LocalDateTime}: its reflective
     * fallback tries to reach {@code LocalDateTime}'s private fields, which JDK 9+'s module
     * system refuses without {@code --add-opens java.base/java.time}, throwing
     * {@code JsonIOException} the first time this operator serializes an entity carrying a
     * non-null {@code LocalDateTime}-typed {@code @Column} (02-08 --
     * {@code AuditableDataEntity#createdAt}/{@code updatedAt} were always {@code null} before
     * this plan, so the whole-entity {@code GSON.toJson(...)} calls below never reached this type
     * until the lifecycle hooks started populating it). Mirrors
     * {@code AbstractRelationalDataOperator}'s identical adapter (02-08), serializing to/from
     * {@link LocalDateTime#toString()}'s ISO-8601 form.
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(LocalDateTime.class, (JsonSerializer<LocalDateTime>) (src, type, context) ->
                    src == null ? JsonNull.INSTANCE : new JsonPrimitive(src.toString()))
            .registerTypeAdapter(LocalDateTime.class, (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                    json.isJsonNull() ? null : LocalDateTime.parse(json.getAsString()))
            .create();

    private final String storeLocation;
    private final Class<T> type;
    private final Map<Object, T> cache = new ConcurrentHashMap<>();
    /**
     * SQL column name (lowercase) -&gt; Java field name.
     * <p>
     * Callers always query using the SQL column name declared on {@code @Column} (52 call sites
     * across 17 Modules), but the key Gson serializes into the map is the Java field name -- the
     * two don't line up on snake_case vs. camelCase, and the query would silently return zero
     * hits. This map translates the former into the latter, matching the read path
     * {@code AbstractRelationalDataOperator#getListHandler()} already uses. See issue #176.
     */
    private final Map<String, String> columnToFieldName;

    /**
     * The {@link JsonTransactionManager} every read and write of this operator goes through: the
     * identity's manager, which {@code JsonStore} passes in, or, for an operator constructed
     * directly, the manager of a standalone identity of its own (see
     * {@link #SimpleJsonDataOperator(String, Class)}). Fixed at construction, so this operator's
     * readers and committers always use the same transaction contexts, the same visibility lock
     * and the same journal.
     */
    private final JsonTransactionManager transactionManager;

    /**
     * The table write locks this operator's writes take: {@code JsonStore}'s shared lock table,
     * or, for an operator constructed directly, a lock table of its own.
     */
    private final JsonLockTable lockTable;

    /** This table's name in journal records: its directory's path relative to the identity root. */
    private final String journalTable;

    /**
     * Keys whose committed value (or absence) has changed since the last checkpoint that captured
     * them, each with the generation of its latest change. Written under the commit gate's read
     * side and the visibility write side; a checkpoint captures it under the gate's write side.
     */
    private final Map<Object, Long> dirty = new ConcurrentHashMap<>();

    /**
     * An operator over the table directory {@code storeLocation}, with an identity of its own: its
     * transactions journal in {@code <storeLocation>.ultitools-journal}, a sibling of the table
     * directory, and it has its own table locks. Before it reads the table directory it recovers
     * that journal, so the entries it loads include every transaction committed before a crash.
     * Servers reach JSON data through {@code JsonStore}, whose operators share their plugin's
     * identity; this constructor serves code that builds an operator directly.
     *
     * @param storeLocation the table directory, one {@code <key>.json} file per entity
     * @param type          the entity type
     * @throws DataAccessException naming the file if a committed journal record is damaged; nothing
     *         is changed, and constructing the operator again succeeds once the file has been moved
     *         out of the journal directory
     */
    public SimpleJsonDataOperator(String storeLocation, Class<T> type) {
        this(storeLocation, type,
                new JsonTransactionManager(JsonIdentity.forStandaloneTable(storeLocation)), new JsonLockTable());
    }

    /**
     * An operator of an identity {@code JsonStore} manages: it uses that identity's manager (and so
     * its journal, commit gate and visibility lock) and the store's lock table, and registers with
     * the identity so the identity's checkpoints capture its changes. Recovers the identity's
     * journal before reading the table directory (for {@code JsonStore} it already has, and this is
     * a no-op), so the table files read here include every committed record.
     *
     * @param storeLocation      the table directory, a direct child of the identity root
     * @param type               the entity type
     * @param transactionManager the identity's manager
     * @param lockTable          the store's lock table
     */
    SimpleJsonDataOperator(String storeLocation, Class<T> type, JsonTransactionManager transactionManager,
            JsonLockTable lockTable) {
        this.storeLocation = storeLocation;
        this.type = type;
        this.columnToFieldName = buildColumnToFieldName(type);
        this.transactionManager = transactionManager;
        this.lockTable = lockTable;
        this.journalTable = transactionManager.journalIdentity().tableName(storeLocation);
        transactionManager.journalIdentity().recoverOnce();
        File file = new File(storeLocation);
        File[] files = file.listFiles();
        if (files != null) {
            Arrays.stream(files).parallel().forEach(dataFile -> {
                try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
                    T entity = GSON.fromJson(reader, type);
                    cache.put(FileUtils.mainName(dataFile), entity);
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.SEVERE, ChatColor.RED + FrameworkText.format("发现一个数据损坏！位置：%s", dataFile.getAbsolutePath()));
                }
            });
        }
        transactionManager.journalIdentity().register(this);
    }

    /**
     * The manager this operator uses, fixed at construction.
     *
     * @return this operator's transaction manager, never {@code null}
     */
    JsonTransactionManager transactionManager() {
        return transactionManager;
    }

    /** The lock table this operator's writes lock in. */
    JsonLockTable lockTable() {
        return lockTable;
    }

    /** The name of this operator's table for messages: its {@code @Table} value, else the entity's simple name. */
    String tableName() {
        Table table = type.getAnnotation(Table.class);
        return table != null ? table.value() : type.getSimpleName();
    }

    /**
     * Runs one write while holding this table's write lock. Inside a transaction the transaction
     * first rejects the write if it is read-only or past its deadline, then takes the lock, before
     * {@code body} evaluates or stages anything, and holds it until the transaction ends; outside
     * one the lock is held for the duration of the call. Every visibility-lock step {@code body}
     * takes comes after the table lock, so no thread waits for a table lock while holding the
     * visibility lock.
     *
     * @param body the write, given the calling thread's transaction or {@code null} outside one
     * @param <R>  the write's result
     * @return what {@code body} returned
     * @throws DataAccessException if the transaction is read-only or past its deadline, or the lock
     *                             wait fails (deadlock or lock-wait timeout)
     */
    private <R> R write(Function<JsonTransaction, R> body) {
        JsonTransaction transaction = transactionManager().currentTransaction();
        if (transaction != null) {
            transaction.beforeWrite(this);
            return body.apply(transaction);
        }
        JsonLockTable table = lockTable;
        table.acquire(this, table.defaultDeadlineNanos());
        try {
            return body.apply(null);
        } finally {
            table.release(this);
        }
    }

    /**
     * Runs {@code read} with the calling thread's transaction (or {@code null} outside one) under
     * the visibility read side. {@code read} only collects references; conditions, detaching and
     * hooks run after this returns. Inside a transaction the transaction first checks its deadline
     * and, at {@code REPEATABLE_READ} or {@code SERIALIZABLE}, takes this table's lock -- before
     * the read side is taken, never while holding it.
     */
    private <R> R readVisible(Function<JsonTransaction, R> read) {
        JsonTransactionManager manager = transactionManager();
        JsonTransaction transaction = manager.currentTransaction();
        if (transaction != null) {
            transaction.beforeRead(this);
        }
        return manager.readUnderVisibilityLock(() -> read.apply(transaction));
    }

    /**
     * The entry for {@code key} as {@code transaction} sees it: its staged write when it has one
     * ({@code null} for a staged delete), otherwise the committed entry. Outside a transaction,
     * the committed entry. Called under the visibility read side.
     */
    private T mergedGet(JsonTransaction transaction, Object key) {
        JsonTransaction.Overlay<T> overlay = transaction == null ? null : transaction.overlay(this);
        if (overlay != null) {
            JsonTransaction.Staged<T> staged = overlay.get(key);
            if (staged != null) {
                return staged.value;
            }
        }
        return cache.get(key);
    }

    /** {@link #mergedGet} by an entity id, trying its string form when the key type differs. */
    private T mergedFind(JsonTransaction transaction, Object id) {
        T stored = mergedGet(transaction, id);
        return stored != null ? stored : mergedGet(transaction, id.toString());
    }

    /**
     * Every entry {@code transaction} sees (every committed entry when it is {@code null}): the
     * committed entries whose key has no staged write, then the staged puts. Called under the
     * visibility read side; collects references only. Committed values are never changed in
     * place, so the references stay valid after the lock is released.
     */
    private List<Map.Entry<Object, T>> mergedEntries(JsonTransaction transaction) {
        JsonTransaction.Overlay<T> overlay = transaction == null ? null : transaction.overlay(this);
        List<Map.Entry<Object, T>> entries = new ArrayList<>(cache.size());
        for (Map.Entry<Object, T> entry : cache.entrySet()) {
            if (overlay == null || !overlay.contains(entry.getKey())) {
                entries.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
            }
        }
        if (overlay != null) {
            overlay.forEach((key, value) -> {
                if (value != null) {
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(key, value));
                }
            });
        }
        return entries;
    }

    /** The values the calling thread sees, collected under the visibility read side. */
    private List<T> visibleValues() {
        List<Map.Entry<Object, T>> entries = readVisible(this::mergedEntries);
        List<T> values = new ArrayList<>(entries.size());
        for (Map.Entry<Object, T> entry : entries) {
            values.add(entry.getValue());
        }
        return values;
    }

    /**
     * Stores {@code detachedValue} under {@code key}: staged in the calling thread's transaction,
     * or, outside one, put into the committed entries in one step (see
     * {@link #publishPlainWrite(Supplier)}).
     */
    private void store(Object key, T detachedValue) {
        JsonTransaction transaction = transactionManager().currentTransaction();
        if (transaction != null) {
            transaction.overlayForWrite(this).put(key, detachedValue);
            return;
        }
        publishPlainWrite(() -> {
            cache.put(key, detachedValue);
            markDirty(key);
            return null;
        });
    }

    /**
     * Applies a write made outside any transaction: with the identity's commit gate held (read
     * side), then the visibility write side, so a checkpoint's capture sees it whole or not at all
     * and every reader sees it at one instant. The caller holds this table's write lock. Plain
     * writes are not journaled: they reach disk at the next checkpoint, as before 6.3.0.
     * {@code apply} updates in-memory maps only and marks every key it changes dirty.
     */
    private <R> R publishPlainWrite(Supplier<R> apply) {
        ReentrantReadWriteLock.ReadLock gate = transactionManager.journalIdentity().commitGate().readLock();
        gate.lock();
        try {
            return transactionManager.publishUnderVisibilityLock(apply);
        } finally {
            gate.unlock();
        }
    }

    /** Marks {@code key} changed since the last checkpoint, with a fresh generation. */
    private void markDirty(Object key) {
        dirty.put(key, transactionManager.journalIdentity().nextGeneration());
    }

    /**
     * Applies one transaction's staged writes for this operator to the committed entries and marks
     * each key dirty. Called only by {@link JsonTransaction#commit()}, after the transaction's
     * journal record is committed, holding this table's write lock, the commit gate's read side and
     * the visibility write side. Takes no lock of its own, so a commit holding the write side never
     * waits for anything.
     *
     * @param overlay the staged writes; a {@code null} value is a staged delete
     */
    void applyStaged(JsonTransaction.Overlay<T> overlay) {
        overlay.forEach((key, value) -> {
            if (value == null) {
                cache.remove(key);
            } else {
                cache.put(key, value);
            }
            markDirty(key);
        });
    }

    /**
     * Adds the journal entries of one transaction's staged writes for this operator to
     * {@code record}, in staging order: the entity's JSON for a put (the form a checkpoint writes to
     * its file), a delete for a staged delete.
     *
     * @throws IllegalArgumentException if a key is not one path segment
     */
    void journalEntries(JsonTransaction.Overlay<T> overlay, List<JsonJournal.Entry> record) {
        overlay.forEach((key, value) -> record.add(checkpointEntry(key, value)));
    }

    /**
     * The journal entry that brings {@code key}'s file to {@code value}: the entity's JSON (the
     * form this operator has always written to its files) for a value, a delete for {@code null}.
     *
     * @throws IllegalArgumentException if {@code key} is not one path segment
     */
    JsonJournal.Entry checkpointEntry(Object key, Object value) {
        return value == null
                ? JsonJournal.Entry.delete(journalTable, key)
                : JsonJournal.Entry.put(journalTable, key, GSON.toJson(value));
    }

    /**
     * Adds every dirty key of this operator, with its generation and its committed value (or
     * {@code null} if the entry is gone), to {@code captured}. Called by
     * {@link JsonIdentity#checkpoint()} under the commit gate's write side, when no commit or
     * plain write is applying. Collects references only; committed values are never changed in
     * place.
     */
    void captureDirty(List<JsonIdentity.Captured> captured) {
        for (Map.Entry<Object, Long> mark : dirty.entrySet()) {
            Object key = mark.getKey();
            captured.add(new JsonIdentity.Captured(this, key, mark.getValue(), cache.get(key)));
        }
    }

    /**
     * Clears {@code key}'s dirty mark if it still carries {@code generation}: a key written again
     * after a checkpoint's capture keeps its newer mark for the next checkpoint.
     */
    void clearDirty(Object key, long generation) {
        dirty.remove(key, generation);
    }

    /**
     * Refuses an id that cannot name this table's entity file {@code <id>.json}: an empty id, or one
     * containing a slash, a backslash or NUL. Such an entry could never be journaled or written by a
     * checkpoint; before 6.3.0 it was flushed into a sub-path and read back as a corrupt entry.
     */
    private void requireStorableKey(Object key) {
        try {
            JsonJournal.Entry.delete(journalTable, key);
        } catch (IllegalArgumentException invalid) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to insert into JSON table '" + tableName() + "' an entity whose id cannot name"
                            + " its file: " + invalid.getMessage(), invalid);
        }
    }

    /**
     * Refuses a new id whose file {@code <id>.json} the insert must not create (question 12 A of
     * the Phase 20.4 decisions): a Windows device name with or without an extension, a trailing
     * dot or space, {@code .} or {@code ..}, a control character, a path separator, a file name
     * longer than 255 UTF-8 bytes, or a name this server's file system rejects or does not resolve
     * to {@code <table directory>/<id>.json} (on Windows, {@code < > : " | ? *}, including a leading
     * drive prefix such as {@code c:}). See {@link JsonFileNames}. Called only for a key the insert
     * creates, so an existing file whose name these rules would refuse keeps working.
     */
    private void requireCreatableKey(Object key) {
        String id = String.valueOf(key);
        String refusal = JsonFileNames.creationRefusal(Paths.get(storeLocation), id);
        if (refusal != null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to insert into JSON table '" + tableName() + "' an entity whose id cannot name its file:"
                            + " id '" + JsonFileNames.shown(id) + "': " + refusal + ". A UUID string always works;"
                            + " otherwise use lowercase letters, digits, '-' and '_', and not a Windows device name"
                            + " (con, nul, aux, prn, com1-com9, lpt1-lpt9). Do not use player names as ids: names"
                            + " such as 'Con' and 'Nul' are valid player names.");
        }
    }

    /**
     * Builds the "SQL column name -&gt; Java field name" mapping from the entity class's
     * {@code @Column} annotations. Collected along the inheritance chain, so the
     * {@code @Column("id")} inherited from {@code BaseDataEntity} is included too.
     *
     * @param type the entity type
     * @return an unmodifiable map keyed by the lowercased SQL column name
     */
    private static Map<String, String> buildColumnToFieldName(Class<?> type) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Field field : ReflectionUtil.getFields(type)) {
            if (field.isAnnotationPresent(Column.class)) {
                // Must use Locale.ROOT: under a Turkish/Azerbaijani default locale, 'I' folds to
                // dotless 'ı' -- if either the table-build side or the query side instead follows
                // the system locale, column names stop matching.
                mapping.put(field.getAnnotation(Column.class).value().toLowerCase(Locale.ROOT), field.getName());
            }
        }
        return Collections.unmodifiableMap(mapping);
    }

    /**
     * Resolves the caller-supplied column name into the key used in the Gson-serialized map.
     *
     * @param column the caller-supplied column name, which may be a SQL column name, a Java
     *               field name, or a nested path
     * @return a key or path that can be passed directly to {@link JsonPathUtil}
     */
    private String resolveColumn(String column) {
        if (column == null) {
            return null;
        }
        String fieldName = columnToFieldName.get(column.toLowerCase(Locale.ROOT));
        return fieldName != null ? fieldName : column;
    }

    /**
     * Returns a detached copy of {@code entity}, produced by the same Gson form this operator
     * persists to disk (#522).
     * <p>
     * Every read path hands out one of these rather than the instance held in {@link #cache},
     * and {@link #insert} stores one rather than the caller's instance. Before 6.3.0 the cached
     * instances themselves were returned, so changing a loaded entity without calling
     * {@code update(...)} changed the store and was written at the next flush -- on
     * {@code datasource.type: json} only; the relational backends materialise every row afresh,
     * so there a change without {@code update(...)} never persisted. The copy is what makes the
     * same module code behave the same on every backend.
     *
     * @param entity the cached (or caller-supplied) instance
     * @return a new instance equal in every persisted field
     */
    private T detach(T entity) {
        // Through a JSON tree rather than text: the same Gson form, without pretty-printing a
        // string only to parse it again.
        return GSON.fromJson(GSON.toJsonTree(entity), type);
    }

    /**
     * Refuses an update or delete addressed by a {@code null} id (#546), which no entry can have
     * as its key; the cache is a {@code ConcurrentHashMap}, so it used to surface as a raw
     * {@code NullPointerException}. Mirrors {@code AbstractRelationalDataOperator}'s refusal.
     */
    private void requireId(Object id, String operation) {
        if (id == null) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to " + operation + " an entry of JSON store '" + type.getName()
                            + "' by a null id: no entry can be addressed by it.");
        }
    }

    private List<T> detachAll(List<T> entities) {
        List<T> copies = new ArrayList<>(entities.size());
        for (T entity : entities) {
            copies.add(detach(entity));
        }
        return copies;
    }

    /**
     * Looks the entry up by the entity's id, as the relational operators do (#522). Comparing the
     * caller's instance with the cached one through {@code equals()} stopped matching once reads
     * and inserts were detached: an entity whose {@code equals()} covers a field it changed without
     * calling {@code update(...)} no longer equals the stored copy.
     */
    @Override
    public boolean exist(T object) {
        Object id = object == null ? null : object.getId();
        return id != null && readVisible(transaction -> mergedFind(transaction, id)) != null;
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        // getAllRaw, not getAll: an existence check is not a read that returns entities to the
        // caller, so it must not fire onLoad() as a side effect of computing a boolean.
        return !getAllRaw(whereConditions).isEmpty();
    }

    @Override
    public T getById(Object id) {
        T visible = readVisible(transaction -> mergedGet(transaction, id));
        if (visible == null) {
            return null;
        }
        T entity = detach(visible);
        entity.onLoad();
        return entity;
    }

    @Override
    public List<T> getAll() {
        return getAll(WhereCondition.empty());
    }

    /**
     * Fires {@code onLoad()} once per entity actually returned to the caller. Delegates the
     * matching logic to {@link #getAllRaw}, which {@link #exist(WhereCondition...)} and
     * {@link #page} also call directly -- {@code exist} because a boolean check should not fire
     * a load hook, and {@code page} because firing on every matched row (rather than only the
     * page's slice) would fire {@code onLoad()} for entities the caller never actually receives.
     */
    @Override
    public List<T> getAll(WhereCondition... whereConditions) {
        List<T> results = detachAll(getAllRaw(whereConditions));
        for (T entity : results) {
            entity.onLoad();
        }
        return results;
    }

    private List<T> getAllRaw(WhereCondition... whereConditions) {
        // An empty condition means "apply no filter" -- strip it out up front so it can't cause
        // a mid-loop return of the full set if it shows up in a later position.
        List<WhereCondition> effective = new ArrayList<>();
        for (WhereCondition condition : whereConditions) {
            if (!condition.isEmpty()) {
                effective.add(condition);
            }
        }
        if (effective.isEmpty()) {
            // Conditions were passed but all are empty (including the single empty condition
            // getAll() itself passes): return the full set. Nothing passed at all: keep the
            // historical behavior of returning an empty set -- page() and exist() both rely on it.
            return whereConditions.length > 0 ? visibleValues() : new ArrayList<>();
        }
        List<T> candidates = visibleValues();
        List<T> results = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        boolean firstCondition = true;
        for (WhereCondition condition : effective) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- this
                // is a query-condition validation failure.
                throw new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query value is not serializable");
            }
            List<T> collection = new ArrayList<>();
            String valueJson = GSON.toJson(condition.getValue());
            for (T each : candidates) {
                Map<String, Object> map = GSON.fromJson(GSON.toJson(each), mapType);
                if (matches(map, condition, valueJson)) collection.add(each);
            }
            // Multiple conditions are ANDed: the first condition establishes the initial set,
            // every condition after it intersects, and an empty set stays empty. The original
            // code read "addAll if results is empty", so when the first condition matched zero
            // rows the whole result silently fell back to the second condition's hit set, AND
            // degenerating into OR. See issue #192.
            if (firstCondition) {
                results.addAll(collection);
                firstCondition = false;
            } else {
                results.removeIf(a -> !collection.contains(a));
            }
        }
        return results;
    }

    @Override
    public List<T> getLike(String column, String value, LikeType likeType) {
        List<T> res = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        for (T each : visibleValues()) {
            Map<String, Object> map = GSON.fromJson(GSON.toJson(each), mapType);
            String byPath = JsonPathUtil.getStr(map, resolveColumn(column));
            if (byPath == null) {
                continue;
            }
            switch (likeType) {
                case END:
                    if (byPath.endsWith(value)) {
                        res.add(each);
                    }
                    break;
                case START:
                    if (byPath.startsWith(value)) {
                        res.add(each);
                    }
                    break;
                case CONTAINS:
                    if (byPath.contains(value)) {
                        res.add(each);
                    }
                    break;
                default:
                    break;
            }
        }
        List<T> copies = detachAll(res);
        for (T entity : copies) {
            entity.onLoad();
        }
        return copies;
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        // getAllRaw, not getAll: firing onLoad() on every matched row here (rather than only
        // the slice actually returned below) would fire the hook for entities outside the
        // requested page -- mirrors AbstractRelationalDataOperator's LIMIT/OFFSET behavior,
        // which only ever materializes the page it returns.
        List<T> all = new ArrayList<>(getAllRaw(whereConditions));
        int start = (page - 1) * size;
        int end = page * size;
        if (start > all.size()) {
            return new ArrayList<>();
        }
        if (end > all.size()) {
            end = all.size();
        }
        List<T> slice = detachAll(all.subList(start, end));
        for (T entity : slice) {
            entity.onLoad();
        }
        return slice;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The entity is stored as {@code <id>.json}. An insert of an id no entry has yet refuses, with
     * a {@link DataAccessException} ({@code DATA_ENTITY_INVALID}) naming the id, an id that cannot
     * name a portable file: a Windows device name with or without an extension, a trailing dot or
     * space, {@code .} or {@code ..}, a control character, a path separator, a file name longer than
     * 255 UTF-8 bytes, or a name this server's file system rejects or does not resolve to
     * {@code <table directory>/<id>.json} (on Windows, {@code < > : " | ? *}, including a leading
     * drive prefix such as {@code c:}); see {@code JsonFileNames}. An insert of an id that already has an
     * entry changes nothing and is never refused, so a file whose name these rules would refuse
     * keeps working. A refusal does not mark the transaction rollback-only.
     */
    @Override
    public void insert(T obj) {
        write(transaction -> {
            insertLocked(transaction, obj);
            return null;
        });
    }

    /** {@link #insert}'s body, run holding this table's write lock. */
    private void insertLocked(JsonTransaction transaction, T obj) {
        // The relational backend auto-fills a UUID when id is empty
        // (AbstractRelationalDataOperator.insert), so modules never set the id themselves --
        // across every Module combined, setId is called 0 times. The JSON side used to skip
        // this, letting null go straight into the ConcurrentHashMap as a key, so the exact same
        // module code would NPE the moment datasource.type switched to json. Filling it in here
        // restores "who generates the id" as a framework guarantee rather than something that
        // depends on the backend. See issue #275.
        if (obj.getId() == null) {
            obj.setId(UUID.randomUUID().toString());
        }
        // Fires before the cache is touched, mirroring AbstractRelationalDataOperator.insert:
        // whatever onCreate() writes (e.g. AuditableDataEntity's createdAt/createdBy) is exactly
        // what ends up cached (and, on flush(), persisted). A detached copy is cached, not obj
        // itself, so a later change to obj does not reach the store without update() -- the
        // same as on the relational backends (#522).
        obj.onCreate();
        T stored = detach(obj);
        Object key = obj.getId();
        requireStorableKey(key);
        // Put-if-absent against what the caller sees (inside a transaction, a key it has staged a
        // delete for is free again, so a delete followed by an insert of the same id stages the
        // new entry). This table's write lock is held, so no other writer can create or remove
        // the key between this check and the put below. Only a key this insert creates must meet
        // the file name rules; an existing one keeps its file.
        boolean exists = readVisible(current -> mergedGet(current, key)) != null;
        if (!exists) {
            requireCreatableKey(key);
        }
        if (transaction == null) {
            publishPlainWrite(() -> {
                if (cache.putIfAbsent(key, stored) == null) {
                    markDirty(key);
                }
                return null;
            });
            return;
        }
        if (!exists) {
            transaction.overlayForWrite(this).put(key, stored);
        }
    }

    /**
     * Deletes by predicate, without loading matched entries into entities first. Consequently
     * this overload does <strong>not</strong> fire {@code onDelete()} -- there is no entity to
     * fire it on without fabricating one, and this class does not fabricate entities to satisfy
     * a hook. {@link #delById(Object)} fetches the entity first and does fire {@code onDelete()}
     * on it (02-08-PLAN.md's delete-hook-path split, mirrored from
     * {@code AbstractRelationalDataOperator}).
     * <p>
     * A {@code null} or zero-length {@code whereConditions} is refused with a {@link
     * DataAccessException}, matching {@code AbstractRelationalDataOperator.del()}'s guard and the
     * contract {@link com.ultikits.ultitools.interfaces.DataOperator#del}'s interface javadoc
     * promises (CR-02, 02-13). The refusal runs before anything is read or staged, so a refused
     * call inside a transaction stages nothing.
     * <p>
     * Inside a transaction, every entry the transaction sees that matches is staged as a delete;
     * outside one, the matches are removed in one step under the visibility write side. Either
     * way the matches are found while holding this table's write lock.
     */
    @Override
    public void del(WhereCondition... whereConditions) {
        // Deliberately does NOT mirror getAll's empty-condition handling: on the query side,
        // "empty conditions = no filter = return everything" is a reasonable convention; carried
        // over to the delete side it becomes "delete the entire table", which is precisely the
        // silent-full-wipe defect class this milestone exists to remove -- refuse both shapes
        // before anything is read or staged (a zero-length array previously made the loop body below
        // never run, so the call returned normally having deleted nothing; a null array threw a
        // raw NullPointerException from the enhanced-for loop, not the DataAccessException the
        // interface promises).
        if (whereConditions == null || whereConditions.length == 0) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to delete every entry of JSON store '" + type.getSimpleName() + "' with no "
                            + "WhereCondition. Pass an explicit condition, or use a dedicated full-table "
                            + "operation if one genuinely exists.");
        }
        write(transaction -> {
            delLocked(transaction, whereConditions);
            return null;
        });
    }

    /** {@link #del(WhereCondition...)}'s body after the refusal, run holding this table's write lock. */
    private void delLocked(JsonTransaction transaction, WhereCondition... whereConditions) {
        List<Map.Entry<Object, T>> visible = readVisible(this::mergedEntries);
        List<Map.Entry<Object, T>> results = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        boolean firstCondition = true;
        for (WhereCondition condition : whereConditions) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- this
                // is a query-condition validation failure, same as getAll's identical check.
                throw new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query value is not serializable");
            }
            List<Map.Entry<Object, T>> collection = new ArrayList<>();
            String valueJson = GSON.toJson(condition.getValue());
            for (Map.Entry<Object, T> next : visible) {
                Map<String, Object> map = GSON.fromJson(GSON.toJson(next.getValue()), mapType);
                if (matches(map, condition, valueJson)) collection.add(next);
            }
            // A copy of the same defect getAll had; likewise changed to a genuine intersection.
            // On the delete path, the original code would delete every row the second condition
            // matched whenever the first condition matched zero rows. See issue #192.
            if (firstCondition) {
                results.addAll(collection);
                firstCondition = false;
            } else {
                results.removeIf(a -> !collection.contains(a));
            }
        }
        if (results.isEmpty()) {
            return;
        }
        if (transaction != null) {
            JsonTransaction.Overlay<T> overlay = transaction.overlayForWrite(this);
            for (Map.Entry<Object, T> each : results) {
                overlay.delete(each.getKey());
            }
            return;
        }
        publishPlainWrite(() -> {
            for (Map.Entry<Object, T> each : results) {
                if (cache.remove(each.getKey(), each.getValue())) {
                    markDirty(each.getKey());
                }
            }
            return null;
        });
    }

    /**
     * Deletes by id, fetching the entity first so {@code onDelete()} can fire on it -- unlike
     * {@link #del(WhereCondition...)}, which deletes by predicate without materializing entries
     * and therefore does not fire the hook. If no entry matches {@code id}, nothing fires. Reads
     * the stored entry directly (not {@link #getById}) so this does not also fire
     * {@code onLoad()} -- deleting is not loading.
     * <p>
     * {@code onDelete()} fires on a detached copy of the entry being deleted (inside a
     * transaction, of the entry as that transaction sees it), so the hook sees every persisted
     * field and nothing it changes on the entity reaches a committed value -- not even through a
     * delete that is then rolled back.
     */
    @Override
    public void delById(Object id) {
        deleteByIdCounted(id);
    }

    /**
     * {@link #delById(Object)}, returning how many cache entries it removed (#521), so
     * {@code Query#delete()} can return rows removed instead of rows matched.
     *
     * @param id the entry id
     * @return {@code 1} if an entry with this id was removed, {@code 0} if there was none
     */
    @Override
    public int deleteByIdCounted(Object id) {
        requireId(id, "delete");
        return write(transaction -> {
            T entity = readVisible(current -> mergedGet(current, id));
            if (entity != null) {
                detach(entity).onDelete();
            }
            if (transaction == null) {
                return publishPlainWrite(() -> {
                    if (cache.remove(id) == null) {
                        return 0;
                    }
                    markDirty(id);
                    return 1;
                });
            }
            if (entity == null) {
                return 0;
            }
            transaction.overlayForWrite(this).delete(id);
            return 1;
        });
    }

    @Override
    public void update(String column, Object value, Object id) {
        requireId(id, "update");
        if (!Serializable.class.isAssignableFrom(value.getClass())) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy. Unlike the
            // two WhereCondition checks above, this validates the value being written by
            // update(column, value, id) -- a persistence failure, not a query-condition one.
            throw new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED, "Query value is not serializable");
        }
        write(transaction -> {
            T obj = readVisible(current -> mergedGet(current, id));
            if (obj == null) {
                warnNoEntry(id);
                return null;
            }
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> map = GSON.fromJson(GSON.toJson(obj), mapType);
            // Without resolving the column name first, putByPath would write into a brand-new key
            // that Gson deserialization silently discards -- a silent lost write.
            JsonPathUtil.putByPath(map, resolveColumn(column), value);
            T newObj = GSON.fromJson(GSON.toJson(map), type);
            store(id, newObj);
            return null;
        });
    }

    @Override
    public void update(T obj) {
        updateEntry(obj);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the number of entries replaced, decided while holding this table's write lock.
     */
    @Override
    public int updateCounted(T entity) {
        return updateEntry(entity);
    }

    /**
     * {@link #update(BaseDataEntity)}, returning how many entries it changed: an id no entry has
     * writes nothing and logs one WARNING, where it used to throw a raw
     * {@code NullPointerException} (#558).
     */
    private int updateEntry(T obj) {
        Object id = obj.getId();
        requireId(id, "update");
        // onUpdate() fires on the caller's obj before its fields are copied, exactly as
        // AbstractRelationalDataOperator.update(T) fires it before the statement that writes
        // them: whatever the hook writes (e.g. AuditableDataEntity's updatedAt/updatedBy) is both
        // what persists and what the caller's instance shows afterwards, on every backend. It
        // runs before the table lock is requested, as the hook runs before the statement waits
        // for a row lock there.
        obj.onUpdate();
        return write(transaction -> {
            T old = readVisible(current -> mergedFind(current, id));
            // Copied onto a detached copy of the visible entry, which then replaces it in one
            // step: committed values are never changed in place, because readers collect
            // references under the visibility read side and detach them after releasing it.
            // Callers always pass a detached instance since #522 (no read hands out the stored
            // one), and BeanCopyUtil copies every primitive field since #520.
            if (old == null) {
                warnNoEntry(id);
                return 0;
            }
            replaceEntry(old, obj);
            return 1;
        });
    }

    /**
     * Logs one WARNING for an update by a non-null id that matches no entry (#558, maintainer
     * decision 2026-09-29), naming the store by its {@code @Table} value -- the directory it is
     * kept in -- as the relational backends name the table.
     */
    private void warnNoEntry(Object id) {
        LOGGER.warning("Update of table '" + tableName() + "' matched no entry with id '" + id
                + "'; nothing was written.");
    }

    /**
     * Replaces {@code stored} with a copy carrying every field of {@code source} except
     * {@code id}, in one step -- staged in the calling thread's transaction, or put into the
     * committed entries under the visibility write side -- so a concurrent read never sees a
     * half-copied entry. Shared by {@link #update(BaseDataEntity)} and {@link #updateIf}.
     * <p>
     * {@link BeanCopyUtil} copies field references, so the merged copy still shares the caller's
     * List, Map and other mutable field values; a detached copy of it is what gets stored, so no
     * object reachable from the caller's entity is reachable from a committed or staged value
     * (C11). A change the caller makes to its entity afterwards changes nothing stored.
     */
    private void replaceEntry(T stored, T source) {
        T merged = detach(stored);
        BeanCopyUtil.copyProperties(source, merged, "id");
        T updated = detach(merged);
        store(updated.getId(), updated);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The entry the caller sees -- inside a transaction, the transaction's staged version of it --
     * is checked against {@code expected} with the same matching {@link #getAll(WhereCondition...)}
     * uses, and replaced, while this table's write lock is held, so no other write -- a plain write
     * or another transaction's -- can come between the check and the write. Inside a transaction
     * the lock is held until the transaction ends, so a second transaction's {@code updateIf} on
     * the same table is checked only after the first has committed or rolled back: of two calls
     * expecting the same value, at most one succeeds. A JSON store belongs to one server; nothing
     * here coordinates two servers.
     * <p>
     * A {@code null} expected value under the default {@link Comparison#EQUAL} is satisfied by an
     * entry whose field is absent or JSON null, and by no present value (#640). This meaning belongs
     * to this method alone: {@link #getAll(WhereCondition...)} and {@link #del(WhereCondition...)}
     * are unchanged.
     */
    @Override
    public boolean updateIf(T entity, WhereCondition... expected) {
        Object id = entity.getId();
        requireId(id, "update");
        List<WhereCondition> conditions = evaluableConditions(expected);
        return write(transaction -> updateIfLocked(entity, id, conditions));
    }

    /** {@link #updateIf}'s check and write, run holding this table's write lock. */
    private boolean updateIfLocked(T entity, Object id, List<WhereCondition> conditions) {
        T stored = readVisible(current -> mergedFind(current, id));
        entity.onUpdate();
        if (stored == null) {
            return false;
        }
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> map = GSON.fromJson(GSON.toJson(stored), mapType);
        for (WhereCondition condition : conditions) {
            if (condition.getValue() == null) {
                // IS NULL (#640): evaluableConditions admitted a null value under EQUAL only. Null
                // fields are not serialised, so a never-set field is absent from the map.
                if (JsonPathUtil.getByPath(map, resolveColumn(condition.getColumn())) != null) {
                    return false;
                }
            } else if (!matches(map, condition, GSON.toJson(condition.getValue()))) {
                return false;
            }
        }
        replaceEntry(stored, entity);
        return true;
    }

    /**
     * The non-empty conditions of an {@link #updateIf} call, each checked before anything is
     * read: a {@code null} condition, a column the entity does not map with {@code @Column}, a
     * {@code null} value under any comparison other than {@link Comparison#EQUAL} (under EQUAL it
     * means "absent or JSON null", #640), and a non-serialisable value are refused with a
     * {@link DataAccessException}, as the relational operators refuse them. {@link #getAll(WhereCondition...)} would simply match
     * nothing for an unknown column, but a conditional write that can never apply would make the
     * caller's re-read-and-retry loop spin forever.
     */
    private List<WhereCondition> evaluableConditions(WhereCondition... expected) {
        List<WhereCondition> conditions = new ArrayList<>();
        if (expected == null) {
            return conditions;
        }
        for (WhereCondition condition : expected) {
            if (condition == null) {
                throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                        "updateIf was given a null condition for JSON store '" + type.getName() + "'.");
            }
            if (condition.isEmpty()) {
                continue;
            }
            String column = condition.getColumn();
            if (column == null || !columnToFieldName.containsKey(column.toLowerCase(Locale.ROOT))) {
                throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                        "Unknown column '" + column + "' for entity " + type.getName()
                                + " -- it is not among the entity's @Column mappings.");
            }
            if (condition.getValue() == null) {
                if (condition.getComparison() != Comparison.EQUAL) {
                    throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                            "updateIf can compare column '" + column + "' with a null value only under "
                                    + "EQUAL (absent or JSON null); " + condition.getComparison()
                                    + " has no null meaning.");
                }
                conditions.add(condition);
                continue;
            }
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                throw new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query value is not serializable");
            }
            conditions.add(condition);
        }
        return conditions;
    }

    /**
     * Whether one serialised entry satisfies one condition -- the single matching rule shared by
     * {@link #getAll(WhereCondition...)}, {@link #del(WhereCondition...)} and {@link #updateIf}.
     * An entry that lacks the condition's column never matches. {@code valueJson} is the
     * condition's value already serialised, once per condition rather than once per entry; each
     * caller has already checked that the value is serialisable.
     */
    private boolean matches(Map<String, Object> map, WhereCondition condition, String valueJson) {
        Object byPath = JsonPathUtil.getByPath(map, resolveColumn(condition.getColumn()));
        if (byPath == null) {
            return false;
        }
        return conditionCal(GSON.toJson(byPath), valueJson, condition);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Runs a checkpoint of this operator's identity ({@link JsonIdentity#checkpoint()}): every
     * entry changed since the last checkpoint, of every table of the plugin, is written through one
     * checkpoint record, so a crash during the flush leaves either the state before it or the
     * state it captured, never a half-written table and never an older value. Entries that did not
     * change are not rewritten. Waits for a checkpoint of the same plugin already running, and for
     * a commit that is between its record and its apply; takes no table lock. Never writes staged
     * data of an open transaction.
     *
     * @throws DataAccessException ({@code DATA_PERSISTENCE_FAILED}) if the data could not be written;
     *         the changed entries stay pending for the next flush
     */
    @Override
    public void flush() {
        transactionManager.journalIdentity().checkpoint();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Deletes every {@code *.json} file of this table that has no committed entry, except the file
     * of a key with a pending dirty mark: a deletion that has not been checkpointed yet reaches disk
     * only through the next checkpoint's record, which is what keeps plain writes crash atomic per
     * flush. Holds the identity's checkpoint mutex, so no checkpoint writes files meanwhile.
     */
    @Override
    public void gc() {
        ReentrantLock checkpointMutex = transactionManager.journalIdentity().checkpointMutex();
        checkpointMutex.lock();
        try {
            File[] files = new File(storeLocation).listFiles(file -> file.getName().endsWith(".json"));
            if (files == null) {
                return;
            }
            Set<String> live = new HashSet<>();
            for (Object key : cache.keySet()) {
                live.add(key.toString());
            }
            for (Object key : dirty.keySet()) {
                live.add(key.toString());
            }
            for (File file : files) {
                if (!live.contains(FileUtils.mainName(file))) {
                    FileUtils.del(file);
                }
            }
        } finally {
            checkpointMutex.unlock();
        }
    }

    // ===== Transaction support (staged writes) =====

    /**
     * {@inheritDoc}
     * <p>
     * Runs {@code action} in a transaction of this operator's {@link JsonTransactionManager}:
     * begins one, or joins the one already active on this thread, runs the action, and commits.
     * Writes the action makes through any operator of the same plugin are staged until the
     * outermost commit, invisible to other threads until then, and applied together at that
     * commit, so other threads see all of them at once. Any {@link Throwable} the action throws --
     * an {@link Error} included (#634) -- rolls the transaction back, which discards the staged
     * writes and never touches committed data, and is rethrown as the same instance; a rollback
     * failure is attached to it as suppressed. A commit that throws (a record that cannot be
     * written, or {@code UnexpectedRollbackException} for a transaction marked rollback-only) has
     * already ended the transaction and is not followed by a rollback. Mirrors
     * {@code AbstractRelationalDataOperator#transaction(Callable)}.
     * <p>
     * <b>Isolation (question 11 A of the Phase 20.4 decisions).</b> A transaction this method
     * begins locks each table at its first read or write, the {@code REPEATABLE_READ} behaviour of
     * {@link JsonTransactionManager}: once the action has read a table, no other transaction or
     * plain write can change it until this transaction ends, so a read-modify-write inside the
     * action cannot lose an update. This keeps the guarantee 6.2.5 gave by running each
     * {@code transaction(...)} callback alone per operator. The cost is the same as there: a read
     * inside the action can wait for another transaction that holds the table, at most 5 seconds,
     * after which it fails with a {@link DataAccessException} and the transaction rolls back; a
     * wait that would close a cycle of transactions waiting for each other fails at once. The
     * table lock is requested before the read takes the visibility lock, never while holding it.
     * A call that joins a transaction already active on this thread (a {@code @Transactional}
     * method, or an outer {@code transaction(...)}) keeps that transaction's isolation, as a
     * participating scope does in Spring and as a JDBC transaction keeps the isolation it began
     * with: inside a default {@code @Transactional} method, reads stay at read committed and take
     * no table lock.
     */
    // PMD.AvoidCatchingThrowable: an Error must roll the transaction back too (#634); it is
    // rethrown unchanged, never swallowed.
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @Override
    public <R> R transaction(Callable<R> action) throws Exception {
        JsonTransactionManager manager = transactionManager();
        boolean outermost = !manager.hasActiveTransaction();
        manager.begin();
        if (outermost) {
            manager.currentTransaction().setLockAtFirstRead(true);
        }
        R result;
        try {
            result = action.call();
        } catch (Throwable failure) {
            try {
                manager.rollback();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        // Outside the catch, as in TransactionInterceptor: a commit that throws has already ended
        // the transaction (the manager discards it whether or not the commit succeeds), so there
        // is nothing left to roll back, and a second rollback() would only log that no
        // transaction is active.
        manager.commit();
        return result;
    }

    @Override
    public void transaction(Runnable action) {
        try {
            transaction((Callable<Void>) () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy. Structurally
            // unreachable via this Runnable overload today -- Runnable.run() declares no checked
            // exception, so nothing this method's own Callable<Void> wrapper can throw reaches
            // here except a RuntimeException, already caught above. This catch exists only to
            // satisfy transaction(Callable<R>)'s declared "throws Exception"; kept typed for
            // defense in depth against a future caller that does route a checked exception
            // through it.
            throw new DataAccessException(ErrorCode.TRANSACTION_FAILED, "Transaction failed", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Atomic: runs as one transaction (joining the one active on this thread, if any), so a
     * failure part-way stores none of the entities. The batch goes through
     * {@link #transaction(Runnable)}, so a batch transaction it begins locks a table at its first
     * read; every insert takes the table's lock before its own read, so this changes nothing here.
     */
    @Override
    public void insertAll(List<T> entities) {
        transaction(() -> {
            for (T entity : entities) {
                insert(entity);
            }
        });
    }

    /**
     * {@inheritDoc}
     * <p>
     * Atomic: runs as one transaction (joining the one active on this thread, if any), so a
     * failure part-way changes none of the entries. The batch goes through
     * {@link #transaction(Callable)}, so a batch transaction it begins locks a table at its first
     * read; every update takes the table's lock before its own read, so this changes nothing here.
     */
    @Override
    public void updateAll(List<T> entities) throws IllegalAccessException {
        // Checked before the batch starts, so a refused batch changes nothing.
        for (T entity : entities) {
            requireId(entity.getId(), "update");
        }
        try {
            transaction((Callable<Void>) () -> {
                for (T entity : entities) {
                    update(entity);
                }
                return null;
            });
        } catch (IllegalAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy. Structurally
            // unreachable today for the same reason as transaction(Runnable)'s identical catch
            // above -- update(T) declares no checked exception the loop body could propagate
            // here -- kept typed for defense in depth.
            throw new DataAccessException(ErrorCode.DATA_OPERATION_FAILED, "Batch update failed", e);
        }
    }

    private boolean conditionCal(String data, String value, WhereCondition condition) {
        // Remove JSON quotes from strings for proper string comparison
        String cleanData = stripJsonQuotes(data);
        String cleanValue = stripJsonQuotes(value);
        
        switch (condition.getComparison()) {
            case EQUAL:
                // First try string equality
                if (cleanData.equals(cleanValue)) {
                    return true;
                }
                // Then try numeric equality for cases like "20.0" vs "20"
                try {
                    double dataNum = Double.parseDouble(cleanData);
                    double valueNum = Double.parseDouble(cleanValue);
                    return dataNum == valueNum;
                } catch (NumberFormatException e) {
                    return false;
                }
            case INCLUDE:
                return cleanData.contains(cleanValue);
            case STARTSWITH:
                return cleanData.startsWith(cleanValue);
            case ENDSWITH:
                return cleanData.endsWith(cleanValue);
            case LESS:
                try {
                    double dataDig = Double.parseDouble(cleanData);
                    double valueDig = Double.parseDouble(cleanValue);
                    return (dataDig < valueDig);
                } catch (Exception e) {
                    return (cleanData.compareTo(cleanValue) < 0);
                }
            case GREATER:
                try {
                    double dataDig = Double.parseDouble(cleanData);
                    double valueDig = Double.parseDouble(cleanValue);
                    return (dataDig > valueDig);
                } catch (Exception e) {
                    return (cleanData.compareTo(cleanValue) > 0);
                }
            default:
                return false;
        }
    }
    
    /**
     * Remove JSON quotes from a serialized string value.
     * e.g., "\"hello\"" becomes "hello"
     */
    private String stripJsonQuotes(String jsonValue) {
        if (jsonValue != null && jsonValue.length() >= 2 
            && jsonValue.startsWith("\"") && jsonValue.endsWith("\"")) {
            return jsonValue.substring(1, jsonValue.length() - 1);
        }
        return jsonValue;
    }
}
