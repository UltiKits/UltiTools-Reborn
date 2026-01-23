package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for ErrorCode enum.
 */
@DisplayName("ErrorCode Tests")
class ErrorCodeTest {

    @Nested
    @DisplayName("General Errors (1000-1999)")
    class GeneralErrorsTests {

        @Test
        @DisplayName("UNKNOWN_ERROR should have code 1000")
        void unknownErrorHascorrectcode() {
            assertEquals(1000, ErrorCode.UNKNOWN_ERROR.getCode());
            assertEquals("Unknown error", ErrorCode.UNKNOWN_ERROR.getDefaultMessage());
            assertEquals("ULTI-1000", ErrorCode.UNKNOWN_ERROR.getFormattedCode());
        }

        @Test
        @DisplayName("INVALID_ARGUMENT should have code 1001")
        void invalidArgumentHascorrectcode() {
            assertEquals(1001, ErrorCode.INVALID_ARGUMENT.getCode());
            assertEquals("Invalid argument", ErrorCode.INVALID_ARGUMENT.getDefaultMessage());
        }

        @Test
        @DisplayName("NULL_POINTER should have code 1002")
        void nullPointerHascorrectcode() {
            assertEquals(1002, ErrorCode.NULL_POINTER.getCode());
            assertEquals("Null pointer", ErrorCode.NULL_POINTER.getDefaultMessage());
        }

        @Test
        @DisplayName("ILLEGAL_STATE should have code 1003")
        void illegalStateHascorrectcode() {
            assertEquals(1003, ErrorCode.ILLEGAL_STATE.getCode());
            assertEquals("Illegal state", ErrorCode.ILLEGAL_STATE.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Container/IoC Errors (2000-2999)")
    class ContainerErrorsTests {

        @Test
        @DisplayName("BEAN_CREATION_FAILED should have code 2000")
        void beanCreationFailedHascorrectcode() {
            assertEquals(2000, ErrorCode.BEAN_CREATION_FAILED.getCode());
            assertEquals("Bean creation failed", ErrorCode.BEAN_CREATION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("BEAN_NOT_FOUND should have code 2001")
        void beanNotFoundHascorrectcode() {
            assertEquals(2001, ErrorCode.BEAN_NOT_FOUND.getCode());
            assertEquals("Bean not found", ErrorCode.BEAN_NOT_FOUND.getDefaultMessage());
        }

        @Test
        @DisplayName("CIRCULAR_DEPENDENCY should have code 2002")
        void circularDependencyHascorrectcode() {
            assertEquals(2002, ErrorCode.CIRCULAR_DEPENDENCY.getCode());
            assertEquals("Circular dependency detected", ErrorCode.CIRCULAR_DEPENDENCY.getDefaultMessage());
        }

        @Test
        @DisplayName("DEPENDENCY_INJECTION_FAILED should have code 2003")
        void dependencyInjectionFailedHascorrectcode() {
            assertEquals(2003, ErrorCode.DEPENDENCY_INJECTION_FAILED.getCode());
            assertEquals("Dependency injection failed", ErrorCode.DEPENDENCY_INJECTION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("DUPLICATE_BEAN should have code 2004")
        void duplicateBeanHascorrectcode() {
            assertEquals(2004, ErrorCode.DUPLICATE_BEAN.getCode());
            assertEquals("Duplicate bean definition", ErrorCode.DUPLICATE_BEAN.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Data Access Errors (3000-3999)")
    class DataAccessErrorsTests {

        @Test
        @DisplayName("DATA_ACCESS_ERROR should have code 3000")
        void dataAccessErrorHascorrectcode() {
            assertEquals(3000, ErrorCode.DATA_ACCESS_ERROR.getCode());
            assertEquals("Data access error", ErrorCode.DATA_ACCESS_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("ENTITY_NOT_FOUND should have code 3001")
        void entityNotFoundHascorrectcode() {
            assertEquals(3001, ErrorCode.ENTITY_NOT_FOUND.getCode());
            assertEquals("Entity not found", ErrorCode.ENTITY_NOT_FOUND.getDefaultMessage());
        }

        @Test
        @DisplayName("DATA_PERSISTENCE_FAILED should have code 3002")
        void dataPersistenceFailedHascorrectcode() {
            assertEquals(3002, ErrorCode.DATA_PERSISTENCE_FAILED.getCode());
            assertEquals("Data persistence failed", ErrorCode.DATA_PERSISTENCE_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("DATA_QUERY_FAILED should have code 3003")
        void dataQueryFailedHascorrectcode() {
            assertEquals(3003, ErrorCode.DATA_QUERY_FAILED.getCode());
            assertEquals("Data query failed", ErrorCode.DATA_QUERY_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("DATA_INTEGRITY_VIOLATION should have code 3004")
        void dataIntegrityViolationHascorrectcode() {
            assertEquals(3004, ErrorCode.DATA_INTEGRITY_VIOLATION.getCode());
            assertEquals("Data integrity violation", ErrorCode.DATA_INTEGRITY_VIOLATION.getDefaultMessage());
        }

        @Test
        @DisplayName("CONNECTION_FAILED should have code 3005")
        void connectionFailedHascorrectcode() {
            assertEquals(3005, ErrorCode.CONNECTION_FAILED.getCode());
            assertEquals("Database connection failed", ErrorCode.CONNECTION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("TRANSACTION_FAILED should have code 3006")
        void transactionFailedHascorrectcode() {
            assertEquals(3006, ErrorCode.TRANSACTION_FAILED.getCode());
            assertEquals("Transaction failed", ErrorCode.TRANSACTION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("DATA_ENTITY_INVALID should have code 3007")
        void dataEntityInvalidHascorrectcode() {
            assertEquals(3007, ErrorCode.DATA_ENTITY_INVALID.getCode());
            assertEquals("Data entity invalid", ErrorCode.DATA_ENTITY_INVALID.getDefaultMessage());
        }

        @Test
        @DisplayName("DATA_OPERATION_FAILED should have code 3008")
        void dataOperationFailedHascorrectcode() {
            assertEquals(3008, ErrorCode.DATA_OPERATION_FAILED.getCode());
            assertEquals("Data operation failed", ErrorCode.DATA_OPERATION_FAILED.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Command Errors (4000-4999)")
    class CommandErrorsTests {

        @Test
        @DisplayName("COMMAND_ERROR should have code 4000")
        void commandErrorHascorrectcode() {
            assertEquals(4000, ErrorCode.COMMAND_ERROR.getCode());
            assertEquals("Command error", ErrorCode.COMMAND_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("COMMAND_VALIDATION_FAILED should have code 4001")
        void commandValidationFailedHascorrectcode() {
            assertEquals(4001, ErrorCode.COMMAND_VALIDATION_FAILED.getCode());
            assertEquals("Command validation failed", ErrorCode.COMMAND_VALIDATION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("COMMAND_PERMISSION_DENIED should have code 4002")
        void commandPermissionDeniedHascorrectcode() {
            assertEquals(4002, ErrorCode.COMMAND_PERMISSION_DENIED.getCode());
            assertEquals("Permission denied", ErrorCode.COMMAND_PERMISSION_DENIED.getDefaultMessage());
        }

        @Test
        @DisplayName("COMMAND_COOLDOWN_ACTIVE should have code 4003")
        void commandCooldownActiveHascorrectcode() {
            assertEquals(4003, ErrorCode.COMMAND_COOLDOWN_ACTIVE.getCode());
            assertEquals("Command on cooldown", ErrorCode.COMMAND_COOLDOWN_ACTIVE.getDefaultMessage());
        }

        @Test
        @DisplayName("COMMAND_PARSE_ERROR should have code 4004")
        void commandParseErrorHascorrectcode() {
            assertEquals(4004, ErrorCode.COMMAND_PARSE_ERROR.getCode());
            assertEquals("Command parse error", ErrorCode.COMMAND_PARSE_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("COMMAND_EXECUTION_FAILED should have code 4005")
        void commandExecutionFailedHascorrectcode() {
            assertEquals(4005, ErrorCode.COMMAND_EXECUTION_FAILED.getCode());
            assertEquals("Command execution failed", ErrorCode.COMMAND_EXECUTION_FAILED.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Configuration Errors (5000-5999)")
    class ConfigurationErrorsTests {

        @Test
        @DisplayName("CONFIG_ERROR should have code 5000")
        void configErrorHascorrectcode() {
            assertEquals(5000, ErrorCode.CONFIG_ERROR.getCode());
            assertEquals("Configuration error", ErrorCode.CONFIG_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("CONFIG_LOAD_FAILED should have code 5001")
        void configLoadFailedHascorrectcode() {
            assertEquals(5001, ErrorCode.CONFIG_LOAD_FAILED.getCode());
            assertEquals("Configuration load failed", ErrorCode.CONFIG_LOAD_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("CONFIG_SAVE_FAILED should have code 5002")
        void configSaveFailedHascorrectcode() {
            assertEquals(5002, ErrorCode.CONFIG_SAVE_FAILED.getCode());
            assertEquals("Configuration save failed", ErrorCode.CONFIG_SAVE_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("CONFIG_PARSE_FAILED should have code 5003")
        void configParseFailedHascorrectcode() {
            assertEquals(5003, ErrorCode.CONFIG_PARSE_FAILED.getCode());
            assertEquals("Configuration parse failed", ErrorCode.CONFIG_PARSE_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("CONFIG_VALIDATION_FAILED should have code 5004")
        void configValidationFailedHascorrectcode() {
            assertEquals(5004, ErrorCode.CONFIG_VALIDATION_FAILED.getCode());
            assertEquals("Configuration validation failed", ErrorCode.CONFIG_VALIDATION_FAILED.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Plugin Errors (6000-6999)")
    class PluginErrorsTests {

        @Test
        @DisplayName("PLUGIN_ERROR should have code 6000")
        void pluginErrorHascorrectcode() {
            assertEquals(6000, ErrorCode.PLUGIN_ERROR.getCode());
            assertEquals("Plugin error", ErrorCode.PLUGIN_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("PLUGIN_LOAD_FAILED should have code 6001")
        void pluginLoadFailedHascorrectcode() {
            assertEquals(6001, ErrorCode.PLUGIN_LOAD_FAILED.getCode());
            assertEquals("Plugin load failed", ErrorCode.PLUGIN_LOAD_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("PLUGIN_UNLOAD_FAILED should have code 6002")
        void pluginUnloadFailedHascorrectcode() {
            assertEquals(6002, ErrorCode.PLUGIN_UNLOAD_FAILED.getCode());
            assertEquals("Plugin unload failed", ErrorCode.PLUGIN_UNLOAD_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("PLUGIN_DEPENDENCY_ERROR should have code 6003")
        void pluginDependencyErrorHascorrectcode() {
            assertEquals(6003, ErrorCode.PLUGIN_DEPENDENCY_ERROR.getCode());
            assertEquals("Plugin dependency error", ErrorCode.PLUGIN_DEPENDENCY_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("PLUGIN_CIRCULAR_DEPENDENCY should have code 6004")
        void pluginCircularDependencyHascorrectcode() {
            assertEquals(6004, ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY.getCode());
            assertEquals("Plugin circular dependency", ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("WebSocket Errors (7000-7999)")
    class WebSocketErrorsTests {

        @Test
        @DisplayName("WEBSOCKET_ERROR should have code 7000")
        void websocketErrorHascorrectcode() {
            assertEquals(7000, ErrorCode.WEBSOCKET_ERROR.getCode());
            assertEquals("WebSocket error", ErrorCode.WEBSOCKET_ERROR.getDefaultMessage());
        }

        @Test
        @DisplayName("WEBSOCKET_CONNECTION_FAILED should have code 7001")
        void websocketConnectionFailedHascorrectcode() {
            assertEquals(7001, ErrorCode.WEBSOCKET_CONNECTION_FAILED.getCode());
            assertEquals("WebSocket connection failed", ErrorCode.WEBSOCKET_CONNECTION_FAILED.getDefaultMessage());
        }

        @Test
        @DisplayName("WEBSOCKET_MESSAGE_FAILED should have code 7002")
        void websocketMessageFailedHascorrectcode() {
            assertEquals(7002, ErrorCode.WEBSOCKET_MESSAGE_FAILED.getCode());
            assertEquals("WebSocket message failed", ErrorCode.WEBSOCKET_MESSAGE_FAILED.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Method Tests")
    class MethodTests {

        @ParameterizedTest
        @EnumSource(ErrorCode.class)
        @DisplayName("All error codes should have positive code values")
        void allErrorCodes_havePositiveCodes(ErrorCode errorCode) {
            assertTrue(errorCode.getCode() > 0);
        }

        @ParameterizedTest
        @EnumSource(ErrorCode.class)
        @DisplayName("All error codes should have non-null default messages")
        void allErrorCodes_haveNonNullMessages(ErrorCode errorCode) {
            assertNotNull(errorCode.getDefaultMessage());
            assertTrue(errorCode.getDefaultMessage().length() > 0);
        }

        @ParameterizedTest
        @EnumSource(ErrorCode.class)
        @DisplayName("getFormattedCode should return ULTI-prefix format")
        void getFormattedCode_returnsCorrectFormat(ErrorCode errorCode) {
            String formatted = errorCode.getFormattedCode();
            assertTrue(formatted.startsWith("ULTI-"));
            assertTrue(formatted.contains(String.valueOf(errorCode.getCode())));
        }

        @Test
        @DisplayName("getCode should return the numeric code")
        void getCodeReturnsnumericcode() {
            assertEquals(1000, ErrorCode.UNKNOWN_ERROR.getCode());
            assertEquals(2001, ErrorCode.BEAN_NOT_FOUND.getCode());
            assertEquals(3001, ErrorCode.ENTITY_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("getDefaultMessage should return the message")
        void getDefaultMessageReturnsmessage() {
            assertEquals("Unknown error", ErrorCode.UNKNOWN_ERROR.getDefaultMessage());
            assertEquals("Bean not found", ErrorCode.BEAN_NOT_FOUND.getDefaultMessage());
        }
    }

    @Nested
    @DisplayName("Error Code Range Tests")
    class ErrorCodeRangeTests {

        @Test
        @DisplayName("General errors should be in 1000-1999 range")
        void generalErrorsIncorrectrange() {
            assertTrue(ErrorCode.UNKNOWN_ERROR.getCode() >= 1000 && ErrorCode.UNKNOWN_ERROR.getCode() < 2000);
            assertTrue(ErrorCode.INVALID_ARGUMENT.getCode() >= 1000 && ErrorCode.INVALID_ARGUMENT.getCode() < 2000);
            assertTrue(ErrorCode.NULL_POINTER.getCode() >= 1000 && ErrorCode.NULL_POINTER.getCode() < 2000);
            assertTrue(ErrorCode.ILLEGAL_STATE.getCode() >= 1000 && ErrorCode.ILLEGAL_STATE.getCode() < 2000);
        }

        @Test
        @DisplayName("Container errors should be in 2000-2999 range")
        void containerErrorsIncorrectrange() {
            assertTrue(ErrorCode.BEAN_CREATION_FAILED.getCode() >= 2000 && ErrorCode.BEAN_CREATION_FAILED.getCode() < 3000);
            assertTrue(ErrorCode.BEAN_NOT_FOUND.getCode() >= 2000 && ErrorCode.BEAN_NOT_FOUND.getCode() < 3000);
            assertTrue(ErrorCode.CIRCULAR_DEPENDENCY.getCode() >= 2000 && ErrorCode.CIRCULAR_DEPENDENCY.getCode() < 3000);
        }

        @Test
        @DisplayName("Data access errors should be in 3000-3999 range")
        void dataAccessErrorsIncorrectrange() {
            assertTrue(ErrorCode.DATA_ACCESS_ERROR.getCode() >= 3000 && ErrorCode.DATA_ACCESS_ERROR.getCode() < 4000);
            assertTrue(ErrorCode.ENTITY_NOT_FOUND.getCode() >= 3000 && ErrorCode.ENTITY_NOT_FOUND.getCode() < 4000);
            assertTrue(ErrorCode.CONNECTION_FAILED.getCode() >= 3000 && ErrorCode.CONNECTION_FAILED.getCode() < 4000);
        }

        @Test
        @DisplayName("Command errors should be in 4000-4999 range")
        void commandErrorsIncorrectrange() {
            assertTrue(ErrorCode.COMMAND_ERROR.getCode() >= 4000 && ErrorCode.COMMAND_ERROR.getCode() < 5000);
            assertTrue(ErrorCode.COMMAND_VALIDATION_FAILED.getCode() >= 4000 && ErrorCode.COMMAND_VALIDATION_FAILED.getCode() < 5000);
            assertTrue(ErrorCode.COMMAND_PERMISSION_DENIED.getCode() >= 4000 && ErrorCode.COMMAND_PERMISSION_DENIED.getCode() < 5000);
        }

        @Test
        @DisplayName("Configuration errors should be in 5000-5999 range")
        void configurationErrorsIncorrectrange() {
            assertTrue(ErrorCode.CONFIG_ERROR.getCode() >= 5000 && ErrorCode.CONFIG_ERROR.getCode() < 6000);
            assertTrue(ErrorCode.CONFIG_LOAD_FAILED.getCode() >= 5000 && ErrorCode.CONFIG_LOAD_FAILED.getCode() < 6000);
            assertTrue(ErrorCode.CONFIG_SAVE_FAILED.getCode() >= 5000 && ErrorCode.CONFIG_SAVE_FAILED.getCode() < 6000);
        }

        @Test
        @DisplayName("Plugin errors should be in 6000-6999 range")
        void pluginErrorsIncorrectrange() {
            assertTrue(ErrorCode.PLUGIN_ERROR.getCode() >= 6000 && ErrorCode.PLUGIN_ERROR.getCode() < 7000);
            assertTrue(ErrorCode.PLUGIN_LOAD_FAILED.getCode() >= 6000 && ErrorCode.PLUGIN_LOAD_FAILED.getCode() < 7000);
            assertTrue(ErrorCode.PLUGIN_UNLOAD_FAILED.getCode() >= 6000 && ErrorCode.PLUGIN_UNLOAD_FAILED.getCode() < 7000);
        }

        @Test
        @DisplayName("WebSocket errors should be in 7000-7999 range")
        void websocketErrorsIncorrectrange() {
            assertTrue(ErrorCode.WEBSOCKET_ERROR.getCode() >= 7000 && ErrorCode.WEBSOCKET_ERROR.getCode() < 8000);
            assertTrue(ErrorCode.WEBSOCKET_CONNECTION_FAILED.getCode() >= 7000 && ErrorCode.WEBSOCKET_CONNECTION_FAILED.getCode() < 8000);
            assertTrue(ErrorCode.WEBSOCKET_MESSAGE_FAILED.getCode() >= 7000 && ErrorCode.WEBSOCKET_MESSAGE_FAILED.getCode() < 8000);
        }
    }
}
