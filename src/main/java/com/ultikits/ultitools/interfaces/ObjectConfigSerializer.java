package com.ultikits.ultitools.interfaces;

import org.bukkit.configuration.MemorySection;

/**
 * Interface for serializing objects to Bukkit configuration MemorySection.
 * <p>
 * Used for complex object serialization in YAML configuration files.
 * Implementors should convert their objects to a MemorySection structure
 * that can be saved to and loaded from configuration files.
 * </p>
 *
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * public class MyDataSerializer implements ObjectConfigSerializer<MyData> {
 *     public MemorySection serializeToMemorySection(MyData data) {
 *         // Convert MyData to MemorySection
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of object to serialize
 * @author wisdomme
 * @since 6.0.0
 * @deprecated Use {@link com.ultikits.ultitools.config.convert.ConfigConverter} and
 * {@link com.ultikits.ultitools.config.convert.ConfigConverterFor}; removed in the next version.
 * @removeIn 6.4.0
 */
@Deprecated(since = "6.3.0", forRemoval = true)
public interface ObjectConfigSerializer<T> {
    /**
     * Serializes an object to a Bukkit MemorySection.
     *
     * @param object the object to serialize
     * @return the MemorySection containing the serialized data
     */
    MemorySection serializeToMemorySection(T object);
}
