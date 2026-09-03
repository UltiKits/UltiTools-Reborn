package com.ultikits.ultitools.interfaces.impl.data.json;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.exceptions.DataAccessException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.manager.JsonTransactionManager;
import com.ultikits.ultitools.utils.BeanCopyUtil;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.JsonPathUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Simple Json data operator.
 *
 * @param <T> Data type inherited from BaseDataEntity
 * @author wisdomme
 * @version 1.0.0
 */
public class SimpleJsonDataOperator<T extends BaseDataEntity<String>> implements DataOperator<T>, Cached {
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
     * The per-plugin {@link JsonTransactionManager} governing this operator, or {@code null} if
     * this operator was never bound to one (e.g. constructed directly, as every pre-existing
     * caller of the two-arg constructor still does -- see {@link #transaction(Callable)}, which
     * is a separate, independent snapshot mechanism this field does not affect).
     * <p>
     * Set via {@link #bindTransactionManager(JsonTransactionManager)} by {@code JsonStore} (same
     * package) right after this operator is constructed or retrieved from its cache (02-05, D-03).
     * {@code volatile} because a write can happen on a different Bukkit worker thread than the one
     * that bound the manager.
     */
    private volatile JsonTransactionManager transactionManager;

    public SimpleJsonDataOperator(String storeLocation, Class<T> type) {
        this.storeLocation = storeLocation;
        this.type = type;
        this.columnToFieldName = buildColumnToFieldName(type);
        File file = new File(storeLocation);
        File[] files = file.listFiles();
        if (files != null) {
            Arrays.stream(files).parallel().forEach(dataFile -> {
                try (Reader reader = Files.newBufferedReader(dataFile.toPath(), StandardCharsets.UTF_8)) {
                    T entity = GSON.fromJson(reader, type);
                    cache.put(FileUtils.mainName(dataFile), entity);
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.SEVERE, ChatColor.RED + "发现一个数据损坏！位置：" + dataFile.getAbsolutePath());
                }
            });
        }
    }

    /**
     * Binds the {@link JsonTransactionManager} this operator participates in (02-05, D-03).
     * <p>
     * Package-private: called only by {@code JsonStore}, right after it constructs or retrieves
     * this operator from its cache, with the manager it resolves for the same identity. Not part
     * of this class's public API.
     *
     * @param transactionManager the manager governing the plugin (or external caller) this
     *                           operator belongs to
     */
    void bindTransactionManager(JsonTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * Called as the first statement of every cache-mutating method ({@link #insert}, {@link #del},
     * {@link #delById}, the two {@code update} overloads). A no-op unless a {@link
     * #bindTransactionManager bound} {@link JsonTransactionManager} has an active transaction on
     * this thread and has not already captured this operator this transaction (D-03: lazy
     * snapshot on first touch, so an operator never written to during the transaction is neither
     * snapshotted nor restored).
     * <p>
     * The actual deep-copy capture ({@link #snapshotCache()}) and restore ({@link
     * #restoreCache(Map)}) stay entirely inside this class; {@link JsonTransactionManager} only
     * ever holds the {@code Supplier}/{@code Consumer} lambdas below, never this operator's cache
     * directly.
     */
    private void beforeMutate() {
        JsonTransactionManager manager = this.transactionManager;
        if (manager != null) {
            manager.captureIfAbsent(this, this::snapshotCache, snapshot -> restoreCache(uncheckedCast(snapshot)));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Object, T> uncheckedCast(Object snapshot) {
        return (Map<Object, T>) snapshot;
    }

    /**
     * Deep-copies this operator's current cache: serialize/deserialize to break references, since
     * {@code update(T)} mutates cached entities in-place via {@link
     * BeanCopyUtil#copyProperties}. Package-private hook shared by {@link #transaction(Callable)}
     * (the pre-existing per-operator mechanism, unchanged) and {@link #beforeMutate()} (the new
     * per-plugin {@link JsonTransactionManager} hook, 02-05) -- one copy of the deep-copy logic,
     * two callers.
     */
    synchronized Map<Object, T> snapshotCache() {
        Map<Object, T> snapshot = new HashMap<>();
        for (Map.Entry<Object, T> entry : cache.entrySet()) {
            snapshot.put(entry.getKey(), GSON.fromJson(GSON.toJson(entry.getValue()), type));
        }
        return snapshot;
    }

    /**
     * Restores this operator's cache from a snapshot previously captured by {@link
     * #snapshotCache()}.
     */
    synchronized void restoreCache(Map<Object, T> snapshot) {
        cache.clear();
        cache.putAll(snapshot);
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

    @Override
    public boolean exist(T object) {
        return cache.containsValue(object);
    }

    @Override
    public boolean exist(WhereCondition... whereConditions) {
        // getAllRaw, not getAll: an existence check is not a read that returns entities to the
        // caller, so it must not fire onLoad() as a side effect of computing a boolean.
        return !getAllRaw(whereConditions).isEmpty();
    }

    @Override
    public T getById(Object id) {
        T entity = cache.get(id);
        if (entity != null) {
            entity.onLoad();
        }
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
        List<T> results = getAllRaw(whereConditions);
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
            return whereConditions.length > 0 ? new ArrayList<>(cache.values()) : new ArrayList<>();
        }
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
            for (T each : cache.values()) {
                Map<String, Object> map = GSON.fromJson(GSON.toJson(each), mapType);
                Object byPath = JsonPathUtil.getByPath(map, resolveColumn(condition.getColumn()));
                if (byPath == null) {
                    continue;
                }
                String data = GSON.toJson(byPath);
                String value = GSON.toJson(condition.getValue());
                if (conditionCal(data, value, condition)) collection.add(each);
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
        for (T each : cache.values()) {
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
        for (T entity : res) {
            entity.onLoad();
        }
        return res;
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
        List<T> slice = all.subList(start, end);
        for (T entity : slice) {
            entity.onLoad();
        }
        return slice;
    }

    @Override
    public synchronized void insert(T obj) {
        beforeMutate();
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
        // what ends up cached (and, on flush(), persisted) -- obj is the same instance stored
        // below, not a copy.
        obj.onCreate();
        cache.putIfAbsent(obj.getId(), obj);
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
     * promises (CR-02, 02-13). The refusal runs before {@link #beforeMutate()}, so a refused call
     * does not capture a transaction snapshot as a side effect.
     */
    @Override
    public synchronized void del(WhereCondition... whereConditions) {
        // Deliberately does NOT mirror getAll's empty-condition handling: on the query side,
        // "empty conditions = no filter = return everything" is a reasonable convention; carried
        // over to the delete side it becomes "delete the entire table", which is precisely the
        // silent-full-wipe defect class this milestone exists to remove -- refuse both shapes
        // before beforeMutate() runs (a zero-length array previously made the loop body below
        // never run, so the call returned normally having deleted nothing; a null array threw a
        // raw NullPointerException from the enhanced-for loop, not the DataAccessException the
        // interface promises).
        if (whereConditions == null || whereConditions.length == 0) {
            throw new DataAccessException(ErrorCode.DATA_ENTITY_INVALID,
                    "Refusing to delete every entry of JSON store '" + type.getSimpleName() + "' with no "
                            + "WhereCondition. Pass an explicit condition, or use a dedicated full-table "
                            + "operation if one genuinely exists.");
        }
        beforeMutate();
        Collection<Map.Entry<Object, T>> results = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        boolean firstCondition = true;
        for (WhereCondition condition : whereConditions) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- this
                // is a query-condition validation failure, same as getAll's identical check.
                throw new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query value is not serializable");
            }
            Collection<Map.Entry<Object, T>> collection = new ArrayList<>();
            Set<Map.Entry<Object, T>> values = cache.entrySet();
            for (Map.Entry<Object, T> next : values) {
                Map<String, Object> map = GSON.fromJson(GSON.toJson(next.getValue()), mapType);
                Object byPath = JsonPathUtil.getByPath(map, resolveColumn(condition.getColumn()));
                if (byPath == null) {
                    continue;
                }
                String data = GSON.toJson(byPath);
                String value = GSON.toJson(condition.getValue());
                if (conditionCal(data, value, condition)) collection.add(next);
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
        for (Map.Entry<Object, T> each : results) {
            cache.remove(each.getKey(), each.getValue());
        }
    }

    /**
     * Deletes by id, fetching the entity first so {@code onDelete()} can fire on it -- unlike
     * {@link #del(WhereCondition...)}, which deletes by predicate without materializing entries
     * and therefore does not fire the hook. If no entry matches {@code id}, nothing fires. Reads
     * the cache directly (not {@link #getById}) so this does not also fire {@code onLoad()} --
     * deleting is not loading.
     */
    @Override
    public synchronized void delById(Object id) {
        beforeMutate();
        T entity = cache.get(id);
        if (entity != null) {
            entity.onDelete();
        }
        cache.remove(id);
    }

    @Override
    public synchronized void update(String column, Object value, Object id) {
        beforeMutate();
        if (!Serializable.class.isAssignableFrom(value.getClass())) {
            // GATE-05 group two (08-21): routed to the typed data-access hierarchy. Unlike the
            // two WhereCondition checks above, this validates the value being written by
            // update(column, value, id) -- a persistence failure, not a query-condition one.
            throw new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED, "Query value is not serializable");
        }
        T obj = cache.get(id);
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> map = GSON.fromJson(GSON.toJson(obj), mapType);
        // Without resolving the column name first, putByPath would write into a brand-new key
        // that Gson deserialization silently discards -- a silent lost write.
        JsonPathUtil.putByPath(map, resolveColumn(column), value);
        T newObj = GSON.fromJson(GSON.toJson(map), type);
        cache.put(id, newObj);
    }

    @Override
    public synchronized void update(T obj) {
        beforeMutate();
        Object id = obj.getId();
        T old = cache.get(id);
        if (old == null) {
            old = cache.get(id.toString());
        }
        BeanCopyUtil.copyProperties(obj, old, "id");
        // Fires on old (the instance actually cached below), after the incoming obj's fields have
        // been copied onto it -- callers may pass either the same cached instance (the common
        // get-mutate-update pattern) or a fresh detached instance with the same id; either way,
        // whatever onUpdate() writes on the entity that ends up cached is what persists. Mirrors
        // AbstractRelationalDataOperator.update(T)'s "fires before the row is written" ordering.
        old.onUpdate();
        cache.put(old.getId(), old);
    }

    @Override
    public synchronized void flush() {
        cache.forEach((key, value) -> {
            try {
                File file = new File(storeLocation + File.separator + key + ".json");
                FileUtils.touch(file);
                try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    writer.write(GSON.toJson(value));
                }
            } catch (IOException e) {
                // GATE-05 group two (08-21): routed to the typed data-access hierarchy -- a
                // failed write to the JSON store is a persistence failure.
                throw new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED,
                        "Failed to flush data to disk for key: " + key, e);
            }
        });
    }

    @Override
    public void gc() {
        File folder = new File(storeLocation);
        File[] files = folder.listFiles((file) -> file.getName().endsWith(".json"));
        if (files == null) {
            return;
        }
        List<File> rubbishBin = new ArrayList<>();
        for (File file : files) {
            String id = FileUtils.mainName(file);
            boolean recycle = true;
            for (Object key : cache.keySet()) {
                if (key.toString().equals(id)) {
                    recycle = false;
                }
            }
            if (recycle) {
                rubbishBin.add(file);
            }
        }
        for (File file : rubbishBin) {
            FileUtils.del(file);
        }
    }

    // ===== Transaction support (snapshot-based) =====

    @Override
    public synchronized <R> R transaction(Callable<R> action) throws Exception {
        // Deep copy: serialize/deserialize to break references
        // (update(T) mutates entities in-place via BeanCopyUtil.copyProperties)
        Map<Object, T> snapshot = snapshotCache();
        try {
            return action.call();
        } catch (Exception e) {
            // Rollback: restore cache from deep copy
            restoreCache(snapshot);
            throw e;
        }
    }

    @Override
    public synchronized void transaction(Runnable action) {
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

    @Override
    public synchronized void insertAll(List<T> entities) {
        transaction(() -> {
            for (T entity : entities) {
                insert(entity);
            }
        });
    }

    @Override
    public synchronized void updateAll(List<T> entities) throws IllegalAccessException {
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
