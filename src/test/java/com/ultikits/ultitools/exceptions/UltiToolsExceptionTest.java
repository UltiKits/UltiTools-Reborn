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
        void constructorWitherrorcodeUsesdefaultmessage() {
            // Test using a concrete subclass
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR.getDefaultMessage());
            
            assertEquals("Command error", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use custom message")
        void constructorWitherrorcodeandmessageUsescustommessage() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Custom message");
            
            assertEquals("Custom message", exception.getMessage());
            assertEquals(ErrorCode.COMMAND_ERROR, exception.getErrorCode());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructorWitherrorcodemessageandcauseSetsall() {
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
        void getErrorCodeReturnserrorcode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_VALIDATION_FAILED, "Validation failed");
            
            assertEquals(ErrorCode.COMMAND_VALIDATION_FAILED, exception.getErrorCode());
        }

        @Test
        @DisplayName("getErrorCode should return consistent value")
        void getErrorCodeReturnsconsistentvalue() {
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
        void getFormattedMessageIncludeserrorcode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Test message");
            
            String formatted = exception.getFormattedMessage();
            
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("4000"));
            assertTrue(formatted.contains("Test message"));
        }

        @Test
        @DisplayName("getFormattedMessage should have correct format")
        void getFormattedMessageHascorrectformat() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "Test");
            
            String formatted = exception.getFormattedMessage();
            
            // Format should be: [ULTI-XXXX] message
            assertTrue(formatted.startsWith("[ULTI-"));
            assertTrue(formatted.contains("] "));
        }

        @Test
        @DisplayName("getFormattedMessage with different error codes")
        void getFormattedMessageWithdifferenterrorcodes() {
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
        void toStringIncludesclassname() {
            CommandException exception = new CommandException("Test");
            
            String str = exception.toString();
            
            assertTrue(str.contains("CommandException"));
        }

        @Test
        @DisplayName("toString should include formatted message")
        void toStringIncludesformattedmessage() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_ERROR, "My message");
            
            String str = exception.toString();
            
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("My message"));
        }

        @Test
        @DisplayName("toString format should be: ClassName: formattedMessage")
        void toStringHascorrectformat() {
            CommandException exception = new CommandException("Test");
            
            String str = exception.toString();
            
            assertTrue(str.contains("CommandException:"));
            assertTrue(str.contains("[ULTI-"));
        }

        @Test
        @DisplayName("toString for different exception types")
        void toStringFordifferentexceptiontypes() {
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
        void allSubclassesExtendultitoolsexception() {
            assertTrue(new CommandException("test") instanceof UltiToolsException);
            assertTrue(new ContainerException("test") instanceof UltiToolsException);
            assertTrue(new DataAccessException("test") instanceof UltiToolsException);
            assertTrue(new ConfigurationException("test") instanceof UltiToolsException);
            assertTrue(new PluginModuleException("test") instanceof UltiToolsException);
        }

        @Test
        @DisplayName("UltiToolsException should extend RuntimeException")
        void ultiToolsExceptionExtendsruntimeexception() {
            CommandException exception = new CommandException("test");
            
            assertTrue(exception instanceof RuntimeException);
        }

        @Test
        @DisplayName("Standard exception methods should work")
        void standardExceptionMethodsShouldwork() {
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
        void exceptionWithemptymessage() {
            CommandException exception = new CommandException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getFormattedMessage());
            assertNotNull(exception.toString());
        }

        @Test
        @DisplayName("Exception with null cause")
        void exceptionWithnullcause() {
            CommandException exception = new CommandException("Test", null);
            
            assertEquals(null, exception.getCause());
        }

        @Test
        @DisplayName("Exception with nested cause chain")
        void exceptionWithnestedcausechain() {
            Throwable rootCause = new RuntimeException("Root");
            Throwable middleCause = new RuntimeException("Middle", rootCause);
            CommandException exception = new CommandException("Top", middleCause);
            
            assertSame(middleCause, exception.getCause());
            assertSame(rootCause, exception.getCause().getCause());
        }
    }
}
