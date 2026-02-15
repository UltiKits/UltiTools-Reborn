package com.ultikits.ultitools.abstracts.command.parser;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Interface for parsing command argument strings into specific types.
 * Implementations should be thread-safe and stateless.
 * <p>
 * 用于将命令参数字符串解析为特定类型的接口。
 * 实现应该是线程安全且无状态的。
 *
 * @param <T> the target type to parse into
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public interface TypeParser<T> {
    
    /**
     * Gets the primary type this parser handles.
     * 获取此解析器处理的主要类型。
     *
     * @return the primary type class
     */
    Class<T> getPrimaryType();
    
    /**
     * Gets all types this parser can handle, including array types.
     * 获取此解析器可以处理的所有类型，包括数组类型。
     *
     * @return list of supported types
     */
    List<Class<?>> getSupportedTypes();
    
    /**
     * Parses a string value into the target type.
     * 将字符串值解析为目标类型。
     *
     * @param value the string value to parse
     * @return the parsed value, or null if parsing fails
     * @throws TypeParseException if parsing fails with an error
     */
    @Nullable
    T parse(String value) throws TypeParseException;
    
    /**
     * Parses multiple string values into an array of the target type.
     * Default implementation calls parse() for each value.
     * 将多个字符串值解析为目标类型的数组。
     * 默认实现为每个值调用 parse()。
     *
     * @param values the string values to parse
     * @return the parsed array
     * @throws TypeParseException if parsing fails with an error
     */
    @SuppressWarnings("unchecked")
    default T[] parseArray(String[] values) throws TypeParseException {
        if (values == null || values.length == 0) {
            return (T[]) java.lang.reflect.Array.newInstance(getPrimaryType(), 0);
        }
        T[] result = (T[]) java.lang.reflect.Array.newInstance(getPrimaryType(), values.length);
        for (int i = 0; i < values.length; i++) {
            result[i] = parse(values[i]);
        }
        return result;
    }
    
    /**
     * Checks if this parser can handle the specified type.
     * 检查此解析器是否可以处理指定类型。
     *
     * @param type the type to check
     * @return true if this parser can handle the type
     */
    default boolean supports(Class<?> type) {
        return getSupportedTypes().stream()
                .anyMatch(supported -> supported.isAssignableFrom(type) || supported.equals(type));
    }
    
    /**
     * Gets the priority of this parser. Higher priority parsers are checked first.
     * Default priority is 0.
     * 获取此解析器的优先级。优先级较高的解析器优先检查。
     * 默认优先级为 0。
     *
     * @return the parser priority
     */
    default int getPriority() {
        return 0;
    }
}
