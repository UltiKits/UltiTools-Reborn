package com.ultikits.ultitools.exceptions;

import java.util.List;

/**
 * Exception thrown when configuration operations fail.
 * <p>
 * This includes configuration loading, parsing, saving, and validation errors.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class ConfigurationException extends UltiToolsException {

    /**
     * Creates a new configuration exception with the given message.
     *
     * @param message the error message
     */
    public ConfigurationException(String message) {
        super(ErrorCode.CONFIG_ERROR, message);
    }

    /**
     * Creates a new configuration exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public ConfigurationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new configuration exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(ErrorCode.CONFIG_ERROR, message, cause);
    }

    /**
     * Creates a new configuration exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public ConfigurationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception for configuration load failures.
     *
     * @param configPath the path to the configuration file
     * @param cause      the underlying cause
     * @return a new ConfigurationException
     */
    public static ConfigurationException loadFailed(String configPath, Throwable cause) {
        return new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED,
                "Failed to load configuration from: " + configPath, cause);
    }

    /**
     * Creates an exception for configuration save failures.
     *
     * @param configPath the path to the configuration file
     * @param cause      the underlying cause
     * @return a new ConfigurationException
     */
    public static ConfigurationException saveFailed(String configPath, Throwable cause) {
        return new ConfigurationException(ErrorCode.CONFIG_SAVE_FAILED,
                "Failed to save configuration to: " + configPath, cause);
    }

    /**
     * Creates an exception for configuration parse failures.
     *
     * @param configPath the path to the configuration file
     * @param cause      the underlying cause
     * @return a new ConfigurationException
     */
    public static ConfigurationException parseFailed(String configPath, Throwable cause) {
        return new ConfigurationException(ErrorCode.CONFIG_PARSE_FAILED,
                "Failed to parse configuration: " + configPath, cause);
    }

    /**
     * Creates an exception for configuration validation failures.
     *
     * @param fieldName      the name of the invalid field
     * @param validationRule the validation rule that was violated
     * @return a new ConfigurationException
     */
    public static ConfigurationException validationFailed(String fieldName, String validationRule) {
        return new ConfigurationException(ErrorCode.CONFIG_VALIDATION_FAILED,
                "Configuration validation failed for field '" + fieldName + "': " + validationRule);
    }

    /**
     * Creates an exception for a module refusing to load because one or more of its config
     * fields violate their validation annotations ({@code @Range}, {@code @NotEmpty},
     * {@code @Size}, {@code @Pattern}).
     * <p>
     * Per D-01, the operator's file is never rewritten - the module refuses to load instead. The
     * message names the module, the file, and every violating field with its actual value and
     * the constraint it broke, so the operator can fix it without guessing.
     * <p>
     * 因为一个或多个配置字段违反了校验注解（{@code @Range}、{@code @NotEmpty}、{@code @Size}、
     * {@code @Pattern}）而拒绝加载模块时创建的异常。<br>
     * 根据 D-01，操作员的文件绝不会被改写——模块会拒绝加载。消息会指出模块、文件，以及每一个
     * 违规字段的实际值和被违反的约束，让操作员不用猜就能修好。
     *
     * @param moduleName     the module refusing to load, from {@code UltiToolsPlugin.getPluginName()}
     * @param configFilePath the path of the violating configuration file
     * @param violations     one description per violating field - field name, actual value, and
     *                       the constraint it broke
     * @return a new ConfigurationException
     * @since 6.3.0
     */
    public static ConfigurationException validationFailed(String moduleName, String configFilePath,
                                                            List<String> violations) {
        StringBuilder message = new StringBuilder();
        message.append("Module '").append(moduleName)
                .append("' refused to load: configuration file '").append(configFilePath)
                .append("' violates its validation constraints (").append(violations.size())
                .append("): ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append(violations.get(i));
        }
        message.append(". The file was not modified - fix the value(s) and restart.");
        return new ConfigurationException(ErrorCode.CONFIG_VALIDATION_FAILED, message.toString());
    }

    /**
     * Creates an exception for a config class the framework cannot construct through either of
     * its two supported idioms - a {@code (String)} constructor or an accessible no-arg
     * constructor that hardcodes its path via {@code super(path)}.
     * <p>
     * 当框架无法通过两种受支持写法之一——{@code (String)} 构造函数，或通过
     * {@code super(path)} 硬编码路径的无参构造函数——构造某个配置类时创建的异常。
     *
     * @param className the fully-qualified name of the config class
     * @param cause     the reflective failure that surfaced the missing constructor
     * @return a new ConfigurationException
     * @since 6.3.0
     */
    public static ConfigurationException unconstructable(String className, Throwable cause) {
        return new ConfigurationException(ErrorCode.CONFIG_VALIDATION_FAILED,
                "Config class '" + className + "' cannot be constructed: it exposes neither a "
                        + "(String) constructor nor an accessible no-arg constructor. Add one of "
                        + "these constructors so the framework can build and validate it.", cause);
    }
}
