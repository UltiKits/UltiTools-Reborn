package com.ultikits.ultitools.interfaces;

/**
 * Generic parser interface for serialization and deserialization.
 * <p>
 * Provides bidirectional conversion between objects and their serialized form.
 * Implementations should handle type conversion for configuration files,
 * database storage, or other persistence mechanisms.
 * </p>
 *
 * <p><strong>Example Implementation:</strong></p>
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
     *
     * @param object the raw object to parse (typically from configuration)
     * @return the parsed object of type T
     */
    T parse(Object object);

    /**
     * Serializes an object to a storable format.
     *
     * @param object the object to serialize
     * @return the serialized form (typically Map, List, or primitive types)
     */
    Object serialize(T object);
}
