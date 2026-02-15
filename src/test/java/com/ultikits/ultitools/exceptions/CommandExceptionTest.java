package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CommandException.
 */
@DisplayName("CommandException Tests")
class CommandExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with message should use COMMAND_ERROR code")
        void constructorWithmessageUsescommanderrorcode() {
            CommandException exception = new CommandException("Test message");
            
            assertEquals(ErrorCode.COMMAND_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use specified code")
        void constructorWitherrorcodeandmessageUsesspecifiedcode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_VALIDATION_FAILED, "Validation failed");
            
            assertEquals(ErrorCode.COMMAND_VALIDATION_FAILED, exception.getErrorCode());
            assertEquals("Validation failed", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause should set both")
        void constructorWithmessageandcauseSetsboth() {
            Throwable cause = new RuntimeException("Root cause");
            CommandException exception = new CommandException("Test message", cause);
            
            assertEquals(ErrorCode.COMMAND_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructorWitherrorcodemessageandcauseSetsall() {
            Throwable cause = new RuntimeException("Root cause");
            CommandException exception = new CommandException(ErrorCode.COMMAND_PARSE_ERROR, "Parse error", cause);
            
            assertEquals(ErrorCode.COMMAND_PARSE_ERROR, exception.getErrorCode());
            assertEquals("Parse error", exception.getMessage());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("permissionDenied should create exception with correct code and message")
        void permissionDeniedCreatescorrectexception() {
            CommandException exception = CommandException.permissionDenied("admin.command");
            
            assertEquals(ErrorCode.COMMAND_PERMISSION_DENIED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("admin.command"));
            assertTrue(exception.getMessage().contains("Permission denied"));
        }

        @Test
        @DisplayName("cooldownActive should create exception with correct code and message")
        void cooldownActiveCreatescorrectexception() {
            CommandException exception = CommandException.cooldownActive(30);
            
            assertEquals(ErrorCode.COMMAND_COOLDOWN_ACTIVE, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("30"));
            assertTrue(exception.getMessage().contains("cooldown"));
        }

        @Test
        @DisplayName("cooldownActive should show remaining seconds")
        void cooldownActiveShowsremainingseconds() {
            CommandException exception = CommandException.cooldownActive(60);
            
            assertTrue(exception.getMessage().contains("60"));
            assertTrue(exception.getMessage().contains("seconds"));
        }

        @Test
        @DisplayName("validationFailed should create exception with correct code and message")
        void validationFailedCreatescorrectexception() {
            CommandException exception = CommandException.validationFailed("Invalid player name");
            
            assertEquals(ErrorCode.COMMAND_VALIDATION_FAILED, exception.getErrorCode());
            assertEquals("Invalid player name", exception.getMessage());
        }

        @Test
        @DisplayName("parseError should create exception with correct code and message")
        void parseErrorCreatescorrectexception() {
            CommandException exception = CommandException.parseError("abc", Integer.class);
            
            assertEquals(ErrorCode.COMMAND_PARSE_ERROR, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("abc"));
            assertTrue(exception.getMessage().contains("Integer"));
        }

        @Test
        @DisplayName("parseError should include input and target type")
        void parseErrorIncludesinputandtargettype() {
            CommandException exception = CommandException.parseError("invalid", Double.class);
            
            assertTrue(exception.getMessage().contains("invalid"));
            assertTrue(exception.getMessage().contains("Double"));
        }
    }

    @Nested
    @DisplayName("Inherited Method Tests")
    class InheritedMethodTests {

        @Test
        @DisplayName("getFormattedMessage should include error code prefix")
        void getFormattedMessageIncludeserrorcodeprefix() {
            CommandException exception = new CommandException("Test");
            
            String formatted = exception.getFormattedMessage();
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("Test"));
        }

        @Test
        @DisplayName("toString should include class name and formatted message")
        void toStringIncludesclassnameandformattedmessage() {
            CommandException exception = new CommandException("Test message");
            
            String str = exception.toString();
            assertTrue(str.contains("CommandException"));
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("Test message"));
        }

        @Test
        @DisplayName("getErrorCode should return correct error code")
        void getErrorCodeReturnscorrecterrorcode() {
            CommandException exception = new CommandException(ErrorCode.COMMAND_EXECUTION_FAILED, "Failed");
            
            assertEquals(ErrorCode.COMMAND_EXECUTION_FAILED, exception.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("CommandException should extend UltiToolsException")
        void commandExceptionExtendsultitoolsexception() {
            CommandException exception = new CommandException("Test");
            
            assertTrue(exception instanceof UltiToolsException);
        }

        @Test
        @DisplayName("CommandException should extend RuntimeException")
        void commandExceptionExtendsruntimeexception() {
            CommandException exception = new CommandException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty message should be handled")
        void emptyMessageIshandled() {
            CommandException exception = new CommandException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getErrorCode());
        }

        @Test
        @DisplayName("Null cause should be handled")
        void nullCauseIshandled() {
            CommandException exception = new CommandException("Test", null);
            
            assertEquals("Test", exception.getMessage());
            assertEquals(null, exception.getCause());
        }

        @Test
        @DisplayName("permissionDenied with empty permission")
        void permissionDeniedWithemptypermission() {
            CommandException exception = CommandException.permissionDenied("");
            
            assertEquals(ErrorCode.COMMAND_PERMISSION_DENIED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("Permission denied"));
        }

        @Test
        @DisplayName("cooldownActive with zero seconds")
        void cooldownActiveWithzeroseconds() {
            CommandException exception = CommandException.cooldownActive(0);
            
            assertEquals(ErrorCode.COMMAND_COOLDOWN_ACTIVE, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("0"));
        }
    }
}
