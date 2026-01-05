package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ExceptionHandler interface and its default methods.
 */
@DisplayName("ExceptionHandler Tests")
class ExceptionHandlerTest {

    /**
     * Simple implementation of ExceptionHandler for testing purposes.
     */
    private static class TestExceptionHandler implements ExceptionHandler {
        private Object returnValue;
        private Throwable exceptionToThrow;
        
        public TestExceptionHandler() {
            this.returnValue = null;
            this.exceptionToThrow = null;
        }
        
        public TestExceptionHandler withReturnValue(Object value) {
            this.returnValue = value;
            return this;
        }
        
        public TestExceptionHandler withExceptionToThrow(Throwable ex) {
            this.exceptionToThrow = ex;
            return this;
        }
        
        @Override
        public Object handleException(Throwable exception, Object target, Method method, Object[] args) throws Throwable {
            if (exceptionToThrow != null) {
                throw exceptionToThrow;
            }
            return returnValue;
        }
    }

    /**
     * Custom implementation that overrides default methods.
     */
    private static class CustomExceptionHandler implements ExceptionHandler {
        private final int order;
        private final Class<? extends Throwable> supportedType;
        
        public CustomExceptionHandler(int order, Class<? extends Throwable> supportedType) {
            this.order = order;
            this.supportedType = supportedType;
        }
        
        @Override
        public Object handleException(Throwable exception, Object target, Method method, Object[] args) throws Throwable {
            return "handled: " + exception.getMessage();
        }
        
        @Override
        public boolean supports(Class<? extends Throwable> exceptionType) {
            return supportedType == null || supportedType.isAssignableFrom(exceptionType);
        }
        
        @Override
        public int getOrder() {
            return order;
        }
    }

    @Nested
    @DisplayName("Default Method Tests")
    class DefaultMethodTests {

        private ExceptionHandler handler;

        @BeforeEach
        void setUp() {
            handler = new TestExceptionHandler();
        }

        @Test
        @DisplayName("supports() should return true for any exception type by default")
        void supports_returnsTrue_byDefault() {
            assertTrue(handler.supports(RuntimeException.class));
            assertTrue(handler.supports(NullPointerException.class));
            assertTrue(handler.supports(IllegalArgumentException.class));
            assertTrue(handler.supports(Exception.class));
            assertTrue(handler.supports(Throwable.class));
            assertTrue(handler.supports(Error.class));
        }

        @Test
        @DisplayName("getOrder() should return 0 by default")
        void getOrder_returnsZero_byDefault() {
            assertEquals(0, handler.getOrder());
        }
    }

    @Nested
    @DisplayName("Custom Implementation Tests")
    class CustomImplementationTests {

        @Test
        @DisplayName("Custom supports() should filter exception types correctly")
        void customSupports_filtersCorrectly() {
            ExceptionHandler handler = new CustomExceptionHandler(0, IllegalArgumentException.class);
            
            // Should support IllegalArgumentException and subclasses
            assertTrue(handler.supports(IllegalArgumentException.class));
            
            // Should not support unrelated types
            assertFalse(handler.supports(NullPointerException.class));
            assertFalse(handler.supports(RuntimeException.class));
        }

        @Test
        @DisplayName("Custom supports() with null type should accept all exceptions")
        void customSupports_nullType_acceptsAll() {
            ExceptionHandler handler = new CustomExceptionHandler(0, null);
            
            assertTrue(handler.supports(RuntimeException.class));
            assertTrue(handler.supports(Exception.class));
            assertTrue(handler.supports(Error.class));
        }

        @Test
        @DisplayName("Custom getOrder() should return specified order")
        void customGetOrder_returnsSpecifiedOrder() {
            assertEquals(10, new CustomExceptionHandler(10, null).getOrder());
            assertEquals(-5, new CustomExceptionHandler(-5, null).getOrder());
            assertEquals(100, new CustomExceptionHandler(100, null).getOrder());
        }
    }

    @Nested
    @DisplayName("handleException() Tests")
    class HandleExceptionTests {

        @Test
        @DisplayName("handleException() should return configured value")
        void handleException_returnsConfiguredValue() throws Throwable {
            TestExceptionHandler handler = new TestExceptionHandler().withReturnValue("test result");
            
            Object result = handler.handleException(
                new RuntimeException("test"),
                this,
                null,
                new Object[0]
            );
            
            assertEquals("test result", result);
        }

        @Test
        @DisplayName("handleException() should return null by default")
        void handleException_returnsNull_byDefault() throws Throwable {
            TestExceptionHandler handler = new TestExceptionHandler();
            
            Object result = handler.handleException(
                new RuntimeException("test"),
                this,
                null,
                new Object[0]
            );
            
            assertNull(result);
        }

        @Test
        @DisplayName("handleException() can re-throw exception")
        void handleException_canRethrow() {
            RuntimeException expected = new RuntimeException("re-thrown");
            TestExceptionHandler handler = new TestExceptionHandler().withExceptionToThrow(expected);
            
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> 
                handler.handleException(
                    new RuntimeException("original"),
                    this,
                    null,
                    new Object[0]
                )
            );
            
            assertSame(expected, thrown);
        }

        @Test
        @DisplayName("handleException() receives method information")
        void handleException_receivesMethodInfo() throws Throwable, NoSuchMethodException {
            final Object[] capturedArgs = new Object[4];
            
            ExceptionHandler handler = new ExceptionHandler() {
                @Override
                public Object handleException(Throwable exception, Object target, Method method, Object[] args) {
                    capturedArgs[0] = exception;
                    capturedArgs[1] = target;
                    capturedArgs[2] = method;
                    capturedArgs[3] = args;
                    return null;
                }
            };
            
            RuntimeException exception = new RuntimeException("test error");
            Object targetObj = "target object";
            Method method = String.class.getMethod("toString");
            Object[] methodArgs = new Object[]{"arg1", 42};
            
            handler.handleException(exception, targetObj, method, methodArgs);
            
            assertSame(exception, capturedArgs[0]);
            assertSame(targetObj, capturedArgs[1]);
            assertSame(method, capturedArgs[2]);
            assertSame(methodArgs, capturedArgs[3]);
        }

        @Test
        @DisplayName("Custom handler should format exception message")
        void customHandler_formatsMessage() throws Throwable {
            ExceptionHandler handler = new CustomExceptionHandler(0, null);
            
            Object result = handler.handleException(
                new RuntimeException("error details"),
                this,
                null,
                new Object[0]
            );
            
            assertEquals("handled: error details", result);
        }
    }

    @Nested
    @DisplayName("Handler Ordering Tests")
    class OrderingTests {

        @Test
        @DisplayName("Handlers can be sorted by order")
        void handlers_canBeSortedByOrder() {
            ExceptionHandler handler1 = new CustomExceptionHandler(10, null);
            ExceptionHandler handler2 = new CustomExceptionHandler(-5, null);
            ExceptionHandler handler3 = new CustomExceptionHandler(0, null);
            ExceptionHandler handler4 = new TestExceptionHandler(); // default order 0
            
            java.util.List<ExceptionHandler> handlers = new java.util.ArrayList<>();
            handlers.add(handler1);
            handlers.add(handler2);
            handlers.add(handler3);
            handlers.add(handler4);
            
            handlers.sort(java.util.Comparator.comparingInt(ExceptionHandler::getOrder));
            
            assertEquals(-5, handlers.get(0).getOrder());
            assertEquals(0, handlers.get(1).getOrder());
            assertEquals(0, handlers.get(2).getOrder());
            assertEquals(10, handlers.get(3).getOrder());
        }

        @Test
        @DisplayName("Negative order has higher priority than positive")
        void negativeOrder_hasHigherPriority() {
            ExceptionHandler highPriority = new CustomExceptionHandler(-100, null);
            ExceptionHandler lowPriority = new CustomExceptionHandler(100, null);
            
            assertTrue(highPriority.getOrder() < lowPriority.getOrder());
        }
    }

    @Nested
    @DisplayName("Exception Type Matching Tests")
    class ExceptionTypeMatchingTests {

        @Test
        @DisplayName("Handler for RuntimeException should support subclasses")
        void handlerForRuntimeException_supportsSubclasses() {
            ExceptionHandler handler = new CustomExceptionHandler(0, RuntimeException.class) {
                @Override
                public boolean supports(Class<? extends Throwable> exceptionType) {
                    return RuntimeException.class.isAssignableFrom(exceptionType);
                }
            };
            
            assertTrue(handler.supports(RuntimeException.class));
            assertTrue(handler.supports(IllegalArgumentException.class));
            assertTrue(handler.supports(NullPointerException.class));
            assertFalse(handler.supports(Exception.class)); // parent, not child
            assertFalse(handler.supports(Error.class));
        }

        @Test
        @DisplayName("Handler for specific exception type")
        void handlerForSpecificType() {
            ExceptionHandler handler = new CustomExceptionHandler(0, NullPointerException.class) {
                @Override
                public boolean supports(Class<? extends Throwable> exceptionType) {
                    return exceptionType == NullPointerException.class;
                }
            };
            
            assertTrue(handler.supports(NullPointerException.class));
            assertFalse(handler.supports(RuntimeException.class));
            assertFalse(handler.supports(IllegalArgumentException.class));
        }
    }
}
