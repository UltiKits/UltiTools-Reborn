package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ContainerException.
 */
@DisplayName("ContainerException Tests")
class ContainerExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with message should use BEAN_CREATION_FAILED code")
        void constructorWithmessageUsesbeancreationfailedcode() {
            ContainerException exception = new ContainerException("Test message");
            
            assertEquals(ErrorCode.BEAN_CREATION_FAILED, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use specified code")
        void constructorWitherrorcodeandmessageUsesspecifiedcode() {
            ContainerException exception = new ContainerException(ErrorCode.BEAN_NOT_FOUND, "Bean not found");
            
            assertEquals(ErrorCode.BEAN_NOT_FOUND, exception.getErrorCode());
            assertEquals("Bean not found", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause should set both")
        void constructorWithmessageandcauseSetsboth() {
            Throwable cause = new RuntimeException("Root cause");
            ContainerException exception = new ContainerException("Test message", cause);
            
            assertEquals(ErrorCode.BEAN_CREATION_FAILED, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructorWitherrorcodemessageandcauseSetsall() {
            Throwable cause = new RuntimeException("Root cause");
            ContainerException exception = new ContainerException(ErrorCode.CIRCULAR_DEPENDENCY, "Circular dep", cause);
            
            assertEquals(ErrorCode.CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertEquals("Circular dep", exception.getMessage());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("beanNotFound(Class) should create exception with correct code and message")
        void beanNotFoundByclassCreatescorrectexception() {
            ContainerException exception = ContainerException.beanNotFound(String.class);
            
            assertEquals(ErrorCode.BEAN_NOT_FOUND, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("java.lang.String"));
            assertTrue(exception.getMessage().contains("No bean of type"));
        }

        @Test
        @DisplayName("beanNotFound(String) should create exception with correct code and message")
        void beanNotFoundBynameCreatescorrectexception() {
            ContainerException exception = ContainerException.beanNotFound("myService");
            
            assertEquals(ErrorCode.BEAN_NOT_FOUND, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("myService"));
            assertTrue(exception.getMessage().contains("No bean named"));
        }

        @Test
        @DisplayName("circularDependency should create exception with correct code and message")
        void circularDependencyCreatescorrectexception() {
            ContainerException exception = ContainerException.circularDependency("serviceA");
            
            assertEquals(ErrorCode.CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("serviceA"));
            assertTrue(exception.getMessage().contains("Circular dependency"));
        }

        @Test
        @DisplayName("injectionFailed should create exception with correct code and message")
        void injectionFailedCreatescorrectexception() {
            Throwable cause = new RuntimeException("Injection error");
            ContainerException exception = ContainerException.injectionFailed(Object.class, String.class, cause);
            
            assertEquals(ErrorCode.DEPENDENCY_INJECTION_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("java.lang.String"));
            assertTrue(exception.getMessage().contains("java.lang.Object"));
            assertTrue(exception.getMessage().contains("Failed to inject"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("duplicateBean should create exception with correct code and message")
        void duplicateBeanCreatescorrectexception() {
            ContainerException exception = ContainerException.duplicateBean("myBean", String.class);
            
            assertEquals(ErrorCode.DUPLICATE_BEAN, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("myBean"));
            assertTrue(exception.getMessage().contains("java.lang.String"));
            assertTrue(exception.getMessage().contains("Duplicate bean"));
        }
    }

    @Nested
    @DisplayName("Inherited Method Tests")
    class InheritedMethodTests {

        @Test
        @DisplayName("getFormattedMessage should include error code prefix")
        void getFormattedMessageIncludeserrorcodeprefix() {
            ContainerException exception = new ContainerException("Test");
            
            String formatted = exception.getFormattedMessage();
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("Test"));
        }

        @Test
        @DisplayName("toString should include class name and formatted message")
        void toStringIncludesclassnameandformattedmessage() {
            ContainerException exception = new ContainerException("Test message");
            
            String str = exception.toString();
            assertTrue(str.contains("ContainerException"));
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("Test message"));
        }

        @Test
        @DisplayName("getErrorCode should return correct error code")
        void getErrorCodeReturnscorrecterrorcode() {
            ContainerException exception = new ContainerException(ErrorCode.DUPLICATE_BEAN, "Duplicate");
            
            assertEquals(ErrorCode.DUPLICATE_BEAN, exception.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("ContainerException should extend UltiToolsException")
        void containerExceptionExtendsultitoolsexception() {
            ContainerException exception = new ContainerException("Test");
            
            assertTrue(exception instanceof UltiToolsException);
        }

        @Test
        @DisplayName("ContainerException should extend RuntimeException")
        void containerExceptionExtendsruntimeexception() {
            ContainerException exception = new ContainerException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty message should be handled")
        void emptyMessageIshandled() {
            ContainerException exception = new ContainerException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getErrorCode());
        }

        @Test
        @DisplayName("beanNotFound with primitive type")
        void beanNotFoundWithprimitivetype() {
            ContainerException exception = ContainerException.beanNotFound(int.class);
            
            assertEquals(ErrorCode.BEAN_NOT_FOUND, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("int"));
        }

        @Test
        @DisplayName("beanNotFound with empty name")
        void beanNotFoundWithemptyname() {
            ContainerException exception = ContainerException.beanNotFound("");
            
            assertEquals(ErrorCode.BEAN_NOT_FOUND, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("circularDependency with empty bean name")
        void circularDependencyWithemptybeanname() {
            ContainerException exception = ContainerException.circularDependency("");
            
            assertEquals(ErrorCode.CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("duplicateBean with empty name")
        void duplicateBeanWithemptyname() {
            ContainerException exception = ContainerException.duplicateBean("", Object.class);
            
            assertEquals(ErrorCode.DUPLICATE_BEAN, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }
    }
}
