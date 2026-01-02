package com.ultikits.ultitools.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 路径工具类 - 替代 hutool-json 的 putByPath/getByPath 功能
 * <p>
 * 提供通过点分隔的路径访问和设置嵌套 JSON Map 中的值。
 * </p>
 *
 * <pre>
 * 使用示例:
 * Map&lt;String, Object&gt; json = new LinkedHashMap&lt;&gt;();
 * JsonPathUtil.putByPath(json, "a.b.c", "value");
 * Object value = JsonPathUtil.getByPath(json, "a.b.c"); // "value"
 * </pre>
 *
 * @author UltiKits Team
 * @since 7.0.0
 */
public final class JsonPathUtil {

    private JsonPathUtil() {
        // 工具类禁止实例化
    }

    /**
     * 通过路径获取嵌套 Map 中的值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径，如 "a.b.c"
     * @return 路径对应的值，如果路径不存在或中间节点非 Map 则返回 null
     */
    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> json, String path) {
        if (json == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] keys = path.split("\\.");
        Object current = json;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 通过路径获取嵌套 Map 中的 String 值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return String 值，如果值不存在或不是 String 则返回 null
     */
    public static String getStr(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        return value != null ? value.toString() : null;
    }

    /**
     * 通过路径获取嵌套 Map 中的 Integer 值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return Integer 值，如果值不存在或无法转换则返回 null
     */
    public static Integer getInt(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 通过路径获取嵌套 Map 中的 Long 值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return Long 值，如果值不存在或无法转换则返回 null
     */
    public static Long getLong(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 通过路径获取嵌套 Map 中的 Boolean 值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return Boolean 值，如果值不存在则返回 null
     */
    public static Boolean getBool(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(value.toString());
    }

    /**
     * 通过路径获取嵌套 Map 中的 Double 值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return Double 值，如果值不存在或无法转换则返回 null
     */
    public static Double getDouble(Map<String, Object> json, String path) {
        Object value = getByPath(json, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 通过路径设置嵌套 Map 中的值，自动创建中间层级
     *
     * @param json  JSON Map 对象
     * @param path  点分隔的路径，如 "a.b.c"
     * @param value 要设置的值
     */
    @SuppressWarnings("unchecked")
    public static void putByPath(Map<String, Object> json, String path, Object value) {
        if (json == null || path == null || path.isEmpty()) {
            return;
        }
        String[] keys = path.split("\\.");
        Map<String, Object> current = json;
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(key, next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(keys[keys.length - 1], value);
    }

    /**
     * 通过路径删除嵌套 Map 中的值
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return 被删除的值，如果路径不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public static Object removeByPath(Map<String, Object> json, String path) {
        if (json == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] keys = path.split("\\.");
        Map<String, Object> current = json;
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object next = current.get(key);
            if (!(next instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) next;
        }
        return current.remove(keys[keys.length - 1]);
    }

    /**
     * 检查路径是否存在
     *
     * @param json JSON Map 对象
     * @param path 点分隔的路径
     * @return 如果路径存在且值不为 null 返回 true
     */
    public static boolean containsPath(Map<String, Object> json, String path) {
        return getByPath(json, path) != null;
    }
}
