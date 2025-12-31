package com.ultikits.ultitools.interfaces;

import org.bukkit.configuration.MemorySection;

/**
 * Interface for serializing objects to Bukkit configuration MemorySection.
 * <p>
 * Used for complex object serialization in YAML configuration files.
 * Implementors should convert their objects to a MemorySection structure
 * that can be saved to and loaded from configuration files.
 * </p>
 * <p>
 * 将对象序列化到 Bukkit 配置 MemorySection 的接口。
 * 用于 YAML 配置文件中的复杂对象序列化。
 * 实现类应将对象转换为可保存到配置文件并从中加载的 MemorySection 结构。
 * </p>
 *
 * <h3>Example Usage / 使用示例:</h3>
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
 */
public interface ObjectConfigSerializer<T> {
    /**
     * Serializes an object to a Bukkit MemorySection.
     * <br>
     * 将对象序列化为 Bukkit MemorySection。
     *
     * @param object the object to serialize / 要序列化的对象
     * @return the MemorySection containing the serialized data
     *         包含序列化数据的 MemorySection
     */
    MemorySection serializeToMemorySection(T object);
}
