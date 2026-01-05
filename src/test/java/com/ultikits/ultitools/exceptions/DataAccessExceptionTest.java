package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DataAccessException.
 */
@DisplayName("DataAccessException Tests")
class DataAccessExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with message should use DATA_ACCESS_ERROR code")
        void constructor_withMessage_usesDataAccessErrorCode() {
            DataAccessException exception = new DataAccessException("Test message");
            
            assertEquals(ErrorCode.DATA_ACCESS_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use specified code")
        void constructor_withErrorCodeAndMessage_usesSpecifiedCode() {
            DataAccessException exception = new DataAccessException(ErrorCode.ENTITY_NOT_FOUND, "Entity not found");
            
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, exception.getErrorCode());
            assertEquals("Entity not found", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause should set both")
        void constructor_withMessageAndCause_setsBoth() {
            Throwable cause = new RuntimeException("Root cause");
            DataAccessException exception = new DataAccessException("Test message", cause);
            
            assertEquals(ErrorCode.DATA_ACCESS_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructor_withErrorCodeMessageAndCause_setsAll() {
            Throwable cause = new RuntimeException("Root cause");
            DataAccessException exception = new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query failed", cause);
            
            assertEquals(ErrorCode.DATA_QUERY_FAILED, exception.getErrorCode());
            assertEquals("Query failed", exception.getMessage());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("entityNotFound should create exception with correct code and message")
        void entityNotFound_createsCorrectException() {
            DataAccessException exception = DataAccessException.entityNotFound(String.class, "abc-123");
            
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("String"));
            assertTrue(exception.getMessage().contains("abc-123"));
            assertTrue(exception.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("entityNotFound with different identifier types")
        void entityNotFound_withDifferentIdentifiers() {
            DataAccessException intException = DataAccessException.entityNotFound(Object.class, 123);
            assertTrue(intException.getMessage().contains("123"));
            
            DataAccessException uuidException = DataAccessException.entityNotFound(Object.class, "uuid-value");
            assertTrue(uuidException.getMessage().contains("uuid-value"));
        }

        @Test
        @DisplayName("connectionFailed should create exception with correct code and message")
        void connectionFailed_createsCorrectException() {
            Throwable cause = new RuntimeException("Connection refused");
            DataAccessException exception = DataAccessException.connectionFailed(cause);
            
            assertEquals(ErrorCode.CONNECTION_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("Failed to connect"));
            assertTrue(exception.getMessage().contains("database"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("queryFailed should create exception with correct code and message")
        void queryFailed_createsCorrectException() {
            Throwable cause = new RuntimeException("Syntax error");
            DataAccessException exception = DataAccessException.queryFailed("SELECT * FROM users", cause);
            
            assertEquals(ErrorCode.DATA_QUERY_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("SELECT * FROM users"));
            assertTrue(exception.getMessage().contains("Query failed"));
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Inherited Method Tests")
    class InheritedMethodTests {

        @Test
        @DisplayName("getFormattedMessage should include error code prefix")
        void getFormattedMessage_includesErrorCodePrefix() {
            DataAccessException exception = new DataAccessException("Test");
            
            String formatted = exception.getFormattedMessage();
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("Test"));
        }

        @Test
        @DisplayName("toString should include class name and formatted message")
        void toString_includesClassNameAndFormattedMessage() {
            DataAccessException exception = new DataAccessException("Test message");
            
            String str = exception.toString();
            assertTrue(str.contains("DataAccessException"));
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("Test message"));
        }

        @Test
        @DisplayName("getErrorCode should return correct error code")
        void getErrorCode_returnsCorrectErrorCode() {
            DataAccessException exception = new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED, "Failed");
            
            assertEquals(ErrorCode.DATA_PERSISTENCE_FAILED, exception.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("DataAccessException should extend UltiToolsException")
        void dataAccessException_extendsUltiToolsException() {
            DataAccessException exception = new DataAccessException("Test");
            
            assertTrue(exception instanceof UltiToolsException);
        }

        @Test
        @DisplayName("DataAccessException should extend RuntimeException")
        void dataAccessException_extendsRuntimeException() {
            DataAccessException exception = new DataAccessException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty message should be handled")
        void emptyMessage_isHandled() {
            DataAccessException exception = new DataAccessException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getErrorCode());
        }

        @Test
        @DisplayName("entityNotFound with null identifier")
        void entityNotFound_withNullIdentifier() {
            DataAccessException exception = DataAccessException.entityNotFound(Object.class, null);
            
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("null"));
        }

        @Test
        @DisplayName("queryFailed with empty query")
        void queryFailed_withEmptyQuery() {
            Throwable cause = new RuntimeException("Error");
            DataAccessException exception = DataAccessException.queryFailed("", cause);
            
            assertEquals(ErrorCode.DATA_QUERY_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("All data access error codes should work")
        void allDataAccessErrorCodes_shouldWork() {
            // Test all data access related error codes
            DataAccessException e1 = new DataAccessException(ErrorCode.DATA_ACCESS_ERROR, "test");
            assertEquals(ErrorCode.DATA_ACCESS_ERROR, e1.getErrorCode());
            
            DataAccessException e2 = new DataAccessException(ErrorCode.DATA_PERSISTENCE_FAILED, "test");
            assertEquals(ErrorCode.DATA_PERSISTENCE_FAILED, e2.getErrorCode());
            
            DataAccessException e3 = new DataAccessException(ErrorCode.DATA_INTEGRITY_VIOLATION, "test");
            assertEquals(ErrorCode.DATA_INTEGRITY_VIOLATION, e3.getErrorCode());
            
            DataAccessException e4 = new DataAccessException(ErrorCode.TRANSACTION_FAILED, "test");
            assertEquals(ErrorCode.TRANSACTION_FAILED, e4.getErrorCode());
            
            DataAccessException e5 = new DataAccessException(ErrorCode.DATA_ENTITY_INVALID, "test");
            assertEquals(ErrorCode.DATA_ENTITY_INVALID, e5.getErrorCode());
            
            DataAccessException e6 = new DataAccessException(ErrorCode.DATA_OPERATION_FAILED, "test");
            assertEquals(ErrorCode.DATA_OPERATION_FAILED, e6.getErrorCode());
        }
    }
}
