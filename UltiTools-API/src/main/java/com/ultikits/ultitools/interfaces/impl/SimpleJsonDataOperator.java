package com.ultikits.ultitools.interfaces.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.db.sql.Condition;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.Cached;
import com.ultikits.ultitools.interfaces.DataOperator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Simple Json data operator.
 * <br>
 * 简单Json存储操作类
 *
 * @param <T> Date type inherited from AbstractDataEntity (数据类型，继承自AbstractDataEntity)
 * @author wisdomme
 * @version 1.0.0
 */
public class SimpleJsonDataOperator<T extends AbstractDataEntity> implements DataOperator<T>, Cached {
    private final String storeLocation;
    private final Class<T> type;
    private final Map<Object, T> cache = new ConcurrentHashMap<>();

    public SimpleJsonDataOperator(String storeLocation, Class<T> type) {
        this.storeLocation = storeLocation;
        this.type = type;
        File file = new File(storeLocation);
        File[] files = file.listFiles();
        if (files != null) {
            Arrays.stream(files).parallel().forEach(dataFile -> {
                try {
                    JSON json = JSONUtil.readJSON(dataFile, Charset.defaultCharset());
                    cache.put(FileNameUtil.mainName(dataFile), json.toBean(type));
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.SEVERE, ChatColor.RED + "发现一个数据损坏！位置：" + dataFile.getAbsolutePath());
                }
            });
        }
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
        List<T> results = new ArrayList<>();
        for (WhereCondition condition : whereConditions) {
            if (condition.isEmpty()) {
                return new ArrayList<>(cache.values());
            }
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                throw new RuntimeException("Query value is not serializable");
            }
            List<T> collection = new ArrayList<>();
            for (T each : cache.values()) {
                JSON parse = JSONUtil.parse(each);
                Object byPath = parse.getByPath(condition.getColumn());
                if (byPath == null) {
                    continue;
                }
                String data = JSONObject.toJSONString(byPath);
                String value = JSONObject.toJSONString(condition.getValue());
                if (conditionCal(data, value, condition)) collection.add(each);
            }
            if (results.size() == 0) {
                results.addAll(collection);
            } else {
                results.removeIf(a -> !collection.contains(a));
            }
        }
        return results;
    }

    @Override
    public List<T> getLike(String column, String value, Condition.LikeType likeType) {
        List<T> res = new ArrayList<>();
        for (T each : cache.values()) {
            JSON parse = JSONUtil.parse(each);
            String byPath = parse.getByPath(column, String.class);
            switch (likeType) {
                case EndWith:
                    if (byPath.endsWith(value)) {
                        res.add(each);
                    }
                    break;
                case StartWith:
                    if (byPath.startsWith(value)) {
                        res.add(each);
                    }
                    break;
                case Contains:
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
        Collection<Map.Entry<Object, T>> results = new ArrayList<>();
        for (WhereCondition condition : whereConditions) {
            if (!Serializable.class.isAssignableFrom(condition.getValue().getClass())) {
                throw new RuntimeException("Query value is not serializable");
            }
            Collection<Map.Entry<Object, T>> collection = new ArrayList<>();
            Set<Map.Entry<Object, T>> values = cache.entrySet();
            for (Map.Entry<Object, T> next : values) {
                JSON parse = JSONUtil.parse(next.getValue());
                Object byPath = parse.getByPath(condition.getColumn());
                if (byPath == null) {
                    continue;
                }
                String data = JSONObject.toJSONString(byPath);
                String value = JSONObject.toJSONString(condition.getValue());
                if (conditionCal(data, value, condition)) collection.add(next);
            }
            if (results.size() == 0) {
                results.addAll(collection);
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
        JSON parse = JSONUtil.parse(obj);
        parse.putByPath(column, value);
        T newObj = parse.toBean(type);
        cache.put(id, newObj);
    }

    @Override
    public synchronized void update(T obj) {
        Object id = obj.getId();
        T old = cache.get(id);
        if (old == null) {
            old = cache.get(id.toString());
        }
        BeanUtil.copyProperties(obj, old, "id");
        cache.put(old.getId(), old);
    }

    @Override
    public synchronized void flush() {
        cache.forEach((key, value) -> {
            try {
                File file = new File(storeLocation + File.separator + key + ".json");
                FileUtil.touch(file);
                FileWriter writer = new FileWriter(file);
                writer.write(com.alibaba.fastjson.JSON.toJSONString(value));
                writer.close();
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
            String id = FileNameUtil.mainName(file);
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
            FileUtil.del(file);
        }
    }

    private boolean conditionCal(String data, String value, WhereCondition condition) {
        switch (condition.getComparison()) {
            case EQUAL:
                return data.equals(value);
            case INCLUDE:
                return data.contains(value);
            case STARTSWITH:
                return (data.startsWith(value));
            case ENDSWITH:
                return (data.endsWith(value));
            case LESS:
                try {
                    double dataDig = Double.parseDouble(data);
                    double valueDig = Double.parseDouble(value);
                    return (dataDig < valueDig);
                } catch (Exception e) {
                    return (data.compareTo(value) < 0);
                }
            case GREATER:
                try {
                    double dataDig = Double.parseDouble(data);
                    double valueDig = Double.parseDouble(value);
                    return (dataDig > valueDig);
                } catch (Exception e) {
                    return (data.compareTo(value) > 0);
                }
            default:
                return false;
        }
    }
}
