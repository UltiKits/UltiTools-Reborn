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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.utils.BeanCopyUtil;
import com.ultikits.ultitools.utils.FileUtils;
import com.ultikits.ultitools.utils.JsonPathUtil;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Simple Json data operator.
 * <br>
 * 简单Json存储操作类
 *
 * @param <T> Data type inherited from BaseDataEntity (数据类型，继承自BaseDataEntity)
 * @author wisdomme
 * @version 1.0.0
 */
public class SimpleJsonDataOperator<T extends BaseDataEntity<String>> implements DataOperator<T>, Cached {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String storeLocation;
    private final Class<T> type;
    private final Map<Object, T> cache = new ConcurrentHashMap<>();
    /**
     * SQL 列名（小写）→ Java 字段名。
     * <p>
     * 调用方一律用 {@code @Column} 里的 SQL 列名做查询（跨 17 个 Modules 共 52 处），
     * 而 Gson 序列化出的 map 键是 Java 字段名，两者在 snake_case / camelCase 上对不上，
     * 查询会静默零命中。这份映射把前者翻译成后者，做法与
     * {@code AbstractRelationalDataOperator#getListHandler()} 的读路径一致。见 issue #176。
     */
    private final Map<String, String> columnToFieldName;

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
     * 从实体类上的 {@code @Column} 标注建立「SQL 列名 → Java 字段名」映射。
     * 沿继承链收集，因此 {@code BaseDataEntity} 继承来的 {@code @Column("id")} 也在内。
     *
     * @param type 实体类型
     * @return 不可变映射，键为小写化的 SQL 列名
     */
    private static Map<String, String> buildColumnToFieldName(Class<?> type) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Field field : ReflectionUtil.getFields(type)) {
            if (field.isAnnotationPresent(Column.class)) {
                // 必须用 Locale.ROOT：默认 locale 是土耳其语/阿塞拜疆语时，'I' 会折成无点的 'ı'，
                // 建表侧与查询侧只要有一边跟着系统 locale 走，列名就对不上了。
                mapping.put(field.getAnnotation(Column.class).value().toLowerCase(Locale.ROOT), field.getName());
            }
        }
        return Collections.unmodifiableMap(mapping);
    }

    /**
     * 把调用方给的列名解析成 Gson 序列化后 map 里的键。
     * <p>
     * 命中 {@code @Column} 就换成对应的 Java 字段名；否则原样返回 —— 这样一来，
     * 直接写 Java 字段名的旧调用、以及 {@link JsonPathUtil} 的点分隔嵌套路径（如
     * {@code "a.b.c"}）都不受影响。
     *
     * @param column 调用方给的列名，可能是 SQL 列名、Java 字段名或嵌套路径
     * @return 可直接交给 {@link JsonPathUtil} 的键或路径
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
        return !getAll(whereConditions).isEmpty();
    }

    @Override
    public T getById(Object id) {
        return cache.get(id);
    }

    @Override
    public List<T> getAll() {
        return getAll(WhereCondition.empty());
    }

    @Override
    public List<T> getAll(WhereCondition... whereConditions) {
        // 空条件表示「不施加过滤」，先摘出去，免得它出现在第二个位置时中途返回全量。
        List<WhereCondition> effective = new ArrayList<>();
        for (WhereCondition condition : whereConditions) {
            if (!condition.isEmpty()) {
                effective.add(condition);
            }
        }
        if (effective.isEmpty()) {
            // 传了条件但全为空（含 getAll() 走的那条单空条件）返回全量；
            // 一条都没传时保持历史行为返回空集 —— page() 与 exist() 都依赖它。
            return whereConditions.length > 0 ? new ArrayList<>(cache.values()) : new ArrayList<>();
        }
        List<T> results = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        boolean firstCondition = true;
        for (WhereCondition condition : effective) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                throw new RuntimeException("Query value is not serializable");
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
            // 多条件是 AND：首个条件建立初始集合，其后一律求交，空集保持空集。
            // 原先写的是「results 为空就 addAll」，于是首个条件零命中时会整体采信第二个
            // 条件的命中集，AND 退化成 OR。见 issue #192。
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
        return res;
    }

    @Override
    public List<T> page(int page, int size, WhereCondition... whereConditions) {
        List<T> all = new ArrayList<>(getAll(whereConditions));
        int start = (page - 1) * size;
        int end = page * size;
        if (start > all.size()) {
            return new ArrayList<>();
        }
        if (end > all.size()) {
            end = all.size();
        }
        return all.subList(start, end);
    }

    @Override
    public synchronized void insert(T obj) {
        cache.putIfAbsent(obj.getId(), obj);
    }

    @Override
    public synchronized void del(WhereCondition... whereConditions) {
        // 这里刻意不照搬 getAll 对空条件的处理：在查询侧「空条件 = 不过滤 = 返回全量」是合理的，
        // 搬到删除侧就成了「删光全表」。空条件目前会在下面取 getValue() 时抛 NPE，难看但失败方向安全。
        Collection<Map.Entry<Object, T>> results = new ArrayList<>();
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        boolean firstCondition = true;
        for (WhereCondition condition : whereConditions) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                throw new RuntimeException("Query value is not serializable");
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
            // 与 getAll 同一处缺陷的复制品，同样改成真正的交集。在删除路径上，
            // 原先的写法会在首个条件零命中时删掉第二个条件的全部命中行。见 issue #192。
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

    @Override
    public synchronized void delById(Object id) {
        cache.remove(id);
    }

    @Override
    public synchronized void update(String column, Object value, Object id) {
        if (!Serializable.class.isAssignableFrom(value.getClass())) {
            throw new RuntimeException("Query value is not serializable");
        }
        T obj = cache.get(id);
        Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
        Map<String, Object> map = GSON.fromJson(GSON.toJson(obj), mapType);
        // 不解析列名的话，putByPath 会写进一个 Gson 反序列化时直接丢弃的新键 —— 静默丢写。
        JsonPathUtil.putByPath(map, resolveColumn(column), value);
        T newObj = GSON.fromJson(GSON.toJson(map), type);
        cache.put(id, newObj);
    }

    @Override
    public synchronized void update(T obj) {
        Object id = obj.getId();
        T old = cache.get(id);
        if (old == null) {
            old = cache.get(id.toString());
        }
        BeanCopyUtil.copyProperties(obj, old, "id");
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
                throw new RuntimeException(e);
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
        Map<Object, T> snapshot = new HashMap<>();
        for (Map.Entry<Object, T> entry : cache.entrySet()) {
            snapshot.put(entry.getKey(), GSON.fromJson(GSON.toJson(entry.getValue()), type));
        }
        try {
            return action.call();
        } catch (Exception e) {
            // Rollback: restore cache from deep copy
            cache.clear();
            cache.putAll(snapshot);
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
            throw new RuntimeException("Transaction failed", e);
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
            throw new RuntimeException("Batch update failed", e);
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
