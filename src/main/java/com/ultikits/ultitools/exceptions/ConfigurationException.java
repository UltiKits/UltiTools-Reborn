package com.ultikits.ultitools.exceptions;

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
}
