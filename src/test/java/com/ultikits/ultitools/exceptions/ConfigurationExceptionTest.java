package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ConfigurationException.
 */
@DisplayName("ConfigurationException Tests")
class ConfigurationExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with message should use CONFIG_ERROR code")
        void constructorWithmessageUsesconfigerrorcode() {
            ConfigurationException exception = new ConfigurationException("Test message");
            
            assertEquals(ErrorCode.CONFIG_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use specified code")
        void constructorWitherrorcodeandmessageUsesspecifiedcode() {
            ConfigurationException exception = new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED, "Load failed");
            
            assertEquals(ErrorCode.CONFIG_LOAD_FAILED, exception.getErrorCode());
            assertEquals("Load failed", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause should set both")
        void constructorWithmessageandcauseSetsboth() {
            Throwable cause = new RuntimeException("Root cause");
            ConfigurationException exception = new ConfigurationException("Test message", cause);
            
            assertEquals(ErrorCode.CONFIG_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructorWitherrorcodemessageandcauseSetsall() {
            Throwable cause = new RuntimeException("Root cause");
            ConfigurationException exception = new ConfigurationException(ErrorCode.CONFIG_PARSE_FAILED, "Parse error", cause);
            
            assertEquals(ErrorCode.CONFIG_PARSE_FAILED, exception.getErrorCode());
            assertEquals("Parse error", exception.getMessage());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("loadFailed should create exception with correct code and message")
        void loadFailedCreatescorrectexception() {
            Throwable cause = new RuntimeException("IO error");
            ConfigurationException exception = ConfigurationException.loadFailed("config/main.yml", cause);
            
            assertEquals(ErrorCode.CONFIG_LOAD_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("config/main.yml"));
            assertTrue(exception.getMessage().contains("Failed to load"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("saveFailed should create exception with correct code and message")
        void saveFailedCreatescorrectexception() {
            Throwable cause = new RuntimeException("Permission denied");
            ConfigurationException exception = ConfigurationException.saveFailed("config/settings.yml", cause);
            
            assertEquals(ErrorCode.CONFIG_SAVE_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("config/settings.yml"));
            assertTrue(exception.getMessage().contains("Failed to save"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("parseFailed should create exception with correct code and message")
        void parseFailedCreatescorrectexception() {
            Throwable cause = new RuntimeException("Invalid YAML");
            ConfigurationException exception = ConfigurationException.parseFailed("config/data.yml", cause);
            
            assertEquals(ErrorCode.CONFIG_PARSE_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("config/data.yml"));
            assertTrue(exception.getMessage().contains("Failed to parse"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("validationFailed should create exception with correct code and message")
        void validationFailedCreatescorrectexception() {
            ConfigurationException exception = ConfigurationException.validationFailed("maxPlayers", "must be positive");
            
            assertEquals(ErrorCode.CONFIG_VALIDATION_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("maxPlayers"));
            assertTrue(exception.getMessage().contains("must be positive"));
        }

        @Test
        @DisplayName("validationFailed should include field name and rule")
        void validationFailedIncludesfieldnameandrule() {
            ConfigurationException exception = ConfigurationException.validationFailed("timeout", "must be between 1 and 3600");
            
            String message = exception.getMessage();
            assertTrue(message.contains("timeout"));
            assertTrue(message.contains("must be between 1 and 3600"));
            assertTrue(message.contains("validation failed"));
        }
    }

    @Nested
    @DisplayName("Inherited Method Tests")
    class InheritedMethodTests {

        @Test
        @DisplayName("getFormattedMessage should include error code prefix")
        void getFormattedMessageIncludeserrorcodeprefix() {
            ConfigurationException exception = new ConfigurationException("Test");
            
            String formatted = exception.getFormattedMessage();
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("Test"));
        }

        @Test
        @DisplayName("toString should include class name and formatted message")
        void toStringIncludesclassnameandformattedmessage() {
            ConfigurationException exception = new ConfigurationException("Test message");
            
            String str = exception.toString();
            assertTrue(str.contains("ConfigurationException"));
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("Test message"));
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("ConfigurationException should extend UltiToolsException")
        void configurationExceptionExtendsultitoolsexception() {
            ConfigurationException exception = new ConfigurationException("Test");
            
            assertTrue(exception instanceof UltiToolsException);
        }

        @Test
        @DisplayName("ConfigurationException should extend RuntimeException")
        void configurationExceptionExtendsruntimeexception() {
            ConfigurationException exception = new ConfigurationException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty message should be handled")
        void emptyMessageIshandled() {
            ConfigurationException exception = new ConfigurationException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getErrorCode());
        }

        @Test
        @DisplayName("loadFailed with empty path")
        void loadFailedWithemptypath() {
            Throwable cause = new RuntimeException("Error");
            ConfigurationException exception = ConfigurationException.loadFailed("", cause);
            
            assertEquals(ErrorCode.CONFIG_LOAD_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("saveFailed with empty path")
        void saveFailedWithemptypath() {
            Throwable cause = new RuntimeException("Error");
            ConfigurationException exception = ConfigurationException.saveFailed("", cause);
            
            assertEquals(ErrorCode.CONFIG_SAVE_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("validationFailed with empty field and rule")
        void validationFailedWithemptyfieldandrule() {
            ConfigurationException exception = ConfigurationException.validationFailed("", "");
            
            assertEquals(ErrorCode.CONFIG_VALIDATION_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }
    }
}
