package com.ultikits.ultitools.interfaces;

/**
 * Generic parser interface for serialization and deserialization.
 * <p>
 * Provides bidirectional conversion between objects and their serialized form.
 * Implementations should handle type conversion for configuration files,
 * database storage, or other persistence mechanisms.
 * </p>
 * <p>
 * 通用解析器接口，用于序列化和反序列化。
 * 提供对象与其序列化形式之间的双向转换。
 * 实现类应处理配置文件、数据库存储或其他持久化机制的类型转换。
 * </p>
 *
 * <h3>Example Implementation / 实现示例:</h3>
 * <pre>{@code
 * public class LocationParser implements Parser<Location> {
 *     public Location parse(Object object) {
 *         Map<String, Object> map = (Map<String, Object>) object;
 *         return new Location(
 *             Bukkit.getWorld((String) map.get("world")),
 *             (double) map.get("x"),
 *             (double) map.get("y"),
 *             (double) map.get("z")
 *         );
 *     }
 *
 *     public Object serialize(Location location) {
 *         Map<String, Object> map = new HashMap<>();
 *         map.put("world", location.getWorld().getName());
 *         map.put("x", location.getX());
 *         map.put("y", location.getY());
 *         map.put("z", location.getZ());
 *         return map;
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type to parse/serialize
 * @author wisdomme
 * @see com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser
 * @see com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser
 * @since 6.0.0
 */
public interface Parser<T> {
    /**
     * Parses a raw object into the target type.
     * <br>
     * 将原始对象解析为目标类型。
     *
     * @param object the raw object to parse (typically from configuration)
     *               要解析的原始对象（通常来自配置）
     * @return the parsed object of type T
     *         解析后的 T 类型对象
     */
    T parse(Object object);

    /**
     * Serializes an object to a storable format.
     * <br>
     * 将对象序列化为可存储格式。
     *
     * @param object the object to serialize
     *               要序列化的对象
     * @return the serialized form (typically Map, List, or primitive types)
     *         序列化形式（通常是 Map、List 或基本类型）
     */
    Object serialize(T object);
}
