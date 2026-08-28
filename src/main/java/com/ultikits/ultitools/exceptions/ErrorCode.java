package com.ultikits.ultitools.exceptions;

/**
 * Error codes for UltiTools exceptions.
 * <p>
 * Each error code belongs to a category and has a unique numeric code.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public enum ErrorCode {

    // ===== General Errors (1000-1999) =====
    UNKNOWN_ERROR(1000, "Unknown error"),
    INVALID_ARGUMENT(1001, "Invalid argument"),
    NULL_POINTER(1002, "Null pointer"),
    ILLEGAL_STATE(1003, "Illegal state"),

    // ===== Container/IoC Errors (2000-2999) =====
    BEAN_CREATION_FAILED(2000, "Bean creation failed"),
    BEAN_NOT_FOUND(2001, "Bean not found"),
    CIRCULAR_DEPENDENCY(2002, "Circular dependency detected"),
    DEPENDENCY_INJECTION_FAILED(2003, "Dependency injection failed"),
    DUPLICATE_BEAN(2004, "Duplicate bean definition"),
    MALFORMED_ANNOTATION_ALIAS(2005, "Malformed @AliasFor declaration"),
    AMBIGUOUS_BEAN_TYPE(2006, "Ambiguous bean type resolution"),

    // ===== Data Access Errors (3000-3999) =====
    DATA_ACCESS_ERROR(3000, "Data access error"),
    ENTITY_NOT_FOUND(3001, "Entity not found"),
    DATA_PERSISTENCE_FAILED(3002, "Data persistence failed"),
    DATA_QUERY_FAILED(3003, "Data query failed"),
    DATA_INTEGRITY_VIOLATION(3004, "Data integrity violation"),
    CONNECTION_FAILED(3005, "Database connection failed"),
    TRANSACTION_FAILED(3006, "Transaction failed"),
    DATA_ENTITY_INVALID(3007, "Data entity invalid"),
    DATA_OPERATION_FAILED(3008, "Data operation failed"),
    TRANSACTION_ROLLBACK_ONLY(3009, "Transaction was marked rollback-only by a nested scope"),
    ENTITY_NOT_OWNED(3010, "Entity is not owned by the requesting plugin"),

    // ===== Command Errors (4000-4999) =====
    COMMAND_ERROR(4000, "Command error"),
    COMMAND_VALIDATION_FAILED(4001, "Command validation failed"),
    COMMAND_PERMISSION_DENIED(4002, "Permission denied"),
    COMMAND_COOLDOWN_ACTIVE(4003, "Command on cooldown"),
    COMMAND_PARSE_ERROR(4004, "Command parse error"),
    COMMAND_EXECUTION_FAILED(4005, "Command execution failed"),

    // ===== Configuration Errors (5000-5999) =====
    CONFIG_ERROR(5000, "Configuration error"),
    CONFIG_LOAD_FAILED(5001, "Configuration load failed"),
    CONFIG_SAVE_FAILED(5002, "Configuration save failed"),
    CONFIG_PARSE_FAILED(5003, "Configuration parse failed"),
    CONFIG_VALIDATION_FAILED(5004, "Configuration validation failed"),

    // ===== Plugin Errors (6000-6999) =====
    PLUGIN_ERROR(6000, "Plugin error"),
    PLUGIN_LOAD_FAILED(6001, "Plugin load failed"),
    PLUGIN_UNLOAD_FAILED(6002, "Plugin unload failed"),
    PLUGIN_DEPENDENCY_ERROR(6003, "Plugin dependency error"),
    PLUGIN_CIRCULAR_DEPENDENCY(6004, "Plugin circular dependency"),

    // ===== WebSocket Errors (7000-7999) =====
    WEBSOCKET_ERROR(7000, "WebSocket error"),
    WEBSOCKET_CONNECTION_FAILED(7001, "WebSocket connection failed"),
    WEBSOCKET_MESSAGE_FAILED(7002, "WebSocket message failed");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /**
     * Gets the numeric error code.
     *
     * @return the error code
     */
    public int getCode() {
        return code;
    }

    /**
     * Gets the default error message.
     *
     * @return the default message
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * Gets the full error code string (e.g., "ULTI-3001").
     *
     * @return the formatted error code
     */
    public String getFormattedCode() {
        return "ULTI-" + code;
    }
}
