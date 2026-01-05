package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for UltiToolsException (tested via concrete subclasses).
 * Since UltiToolsException is abstract, we test it through CommandException.
 */
@DisplayName("UltiToolsException Tests (via subclasses)")
class UltiToolsExceptionTest {

    @Nested
    @DisplayName("Constructor Tests (via CommandException)")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with error code should use default message")
        void constructor_withErrorCode_usesDefaultMessage() {
            // Test using a concrete subclass
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR.getDefaultMessage());
            
            assertEquals("Command error", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use custom message")
        void constructor_withErrorCodeAndMessage_usesCustomMessage() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Custom message");
            
            assertEquals("Custom message", exception.getMessage());
            assertEquals(ErrorCode.COMMAND_ERROR, exception.getErrorCode());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructor_withErrorCodeMessageAndCause_setsAll() {
            Throwable cause = new RuntimeException("Root cause");
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Message", cause);
            
            assertEquals("Message", exception.getMessage());
            assertEquals(ErrorCode.COMMAND_ERROR, exception.getErrorCode());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("getErrorCode() Tests")
    class GetErrorCodeTests {

        @Test
        @DisplayName("getErrorCode should return the error code")
        void getErrorCode_returnsErrorCode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_VALIDATION_FAILED, "Validation failed");
            
            assertEquals(ErrorCode.COMMAND_VALIDATION_FAILED, exception.getErrorCode());
        }

        @Test
        @DisplayName("getErrorCode should return consistent value")
        void getErrorCode_returnsConsistentValue() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_PARSE_ERROR, "Parse error");
            
            // Multiple calls should return the same value
            ErrorCode code1 = exception.getErrorCode();
            ErrorCode code2 = exception.getErrorCode();
            
            assertSame(code1, code2);
        }
    }

    @Nested
    @DisplayName("getFormattedMessage() Tests")
    class GetFormattedMessageTests {

        @Test
        @DisplayName("getFormattedMessage should include error code")
        void getFormattedMessage_includesErrorCode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Test message");
            
            String formatted = exception.getFormattedMessage();
            
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("4000"));
            assertTrue(formatted.contains("Test message"));
        }

        @Test
        @DisplayName("getFormattedMessage should have correct format")
        void getFormattedMessage_hasCorrectFormat() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Test");
            
            String formatted = exception.getFormattedMessage();
            
            // Format should be: [ULTI-XXXX] message
            assertTrue(formatted.startsWith("[ULTI-"));
            assertTrue(formatted.contains("] "));
        }

        @Test
        @DisplayName("getFormattedMessage with different error codes")
        void getFormattedMessage_withDifferentErrorCodes() {
            ContainerException containerEx = new ContainerException(ErrorCode.BEAN_NOT_FOUND, "Bean error");
            assertTrue(containerEx.getFormattedMessage().contains("ULTI-2001"));
            
            DataAccessException dataEx = new DataAccessException(ErrorCode.ENTITY_NOT_FOUND, "Data error");
            assertTrue(dataEx.getFormattedMessage().contains("ULTI-3001"));
            
            ConfigurationException configEx = new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED, "Config error");
            assertTrue(configEx.getFormattedMessage().contains("ULTI-5001"));
        }
    }

    @Nested
    @DisplayName("toString() Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString should include class name")
        void toString_includesClassName() {
            CommandException exception = new CommandException("Test");
            
            String str = exception.toString();
            
            assertTrue(str.contains("CommandException"));
        }

        @Test
        @DisplayName("toString should include formatted message")
        void toString_includesFormattedMessage() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "My message");
            
            String str = exception.toString();
            
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("My message"));
        }

        @Test
        @DisplayName("toString format should be: ClassName: formattedMessage")
        void toString_hasCorrectFormat() {
            CommandException exception = new CommandException("Test");
            
            String str = exception.toString();
            
            assertTrue(str.contains("CommandException:"));
            assertTrue(str.contains("[ULTI-"));
        }

        @Test
        @DisplayName("toString for different exception types")
        void toString_forDifferentExceptionTypes() {
            ContainerException containerEx = new ContainerException("Container test");
            assertTrue(containerEx.toString().contains("ContainerException"));
            
            DataAccessException dataEx = new DataAccessException("Data test");
            assertTrue(dataEx.toString().contains("DataAccessException"));
            
            ConfigurationException configEx = new ConfigurationException("Config test");
            assertTrue(configEx.toString().contains("ConfigurationException"));
            
            PluginModuleException pluginEx = new PluginModuleException("Plugin test");
            assertTrue(pluginEx.toString().contains("PluginModuleException"));
        }
    }

    @Nested
    @DisplayName("Inheritance Tests")
    class InheritanceTests {

        @Test
        @DisplayName("All exception subclasses should extend UltiToolsException")
        void allSubclasses_extendUltiToolsException() {
            assertTrue(new CommandException("test") instanceof UltiToolsException);
            assertTrue(new ContainerException("test") instanceof UltiToolsException);
            assertTrue(new DataAccessException("test") instanceof UltiToolsException);
            assertTrue(new ConfigurationException("test") instanceof UltiToolsException);
            assertTrue(new PluginModuleException("test") instanceof UltiToolsException);
        }

        @Test
        @DisplayName("UltiToolsException should extend RuntimeException")
        void ultiToolsException_extendsRuntimeException() {
            CommandException exception = new CommandException("test");
            
            assertTrue(exception instanceof RuntimeException);
        }

        @Test
        @DisplayName("Standard exception methods should work")
        void standardExceptionMethods_shouldWork() {
            Throwable cause = new RuntimeException("Cause");
            CommandException exception = new CommandException("Message", cause);
            
            // getMessage()
            assertEquals("Message", exception.getMessage());
            
            // getCause()
            assertSame(cause, exception.getCause());
            
            // getStackTrace()
            assertNotNull(exception.getStackTrace());
            assertTrue(exception.getStackTrace().length > 0);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Exception with empty message")
        void exception_withEmptyMessage() {
            CommandException exception = new CommandException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getFormattedMessage());
            assertNotNull(exception.toString());
        }

        @Test
        @DisplayName("Exception with null cause")
        void exception_withNullCause() {
            CommandException exception = new CommandException("Test", null);
            
            assertEquals(null, exception.getCause());
        }

        @Test
        @DisplayName("Exception with nested cause chain")
        void exception_withNestedCauseChain() {
            Throwable rootCause = new RuntimeException("Root");
            Throwable middleCause = new RuntimeException("Middle", rootCause);
            CommandException exception = new CommandException("Top", middleCause);
            
            assertSame(middleCause, exception.getCause());
            assertSame(rootCause, exception.getCause().getCause());
        }
    }
}
