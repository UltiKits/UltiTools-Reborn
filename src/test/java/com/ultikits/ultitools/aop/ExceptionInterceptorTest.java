package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.context.ContextHolder;
import com.ultikits.ultitools.context.SimpleContainer;

/**
 * Unit tests for ExceptionInterceptor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExceptionInterceptor Tests")
@SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes") // Test code intentionally throws exceptions
class ExceptionInterceptorTest {

    private ExceptionInterceptor interceptor;

    @Mock
    private MethodInvocation mockInvocation;

    @Mock
    private SimpleContainer mockContext;

    // Test classes with annotations
    public static class ServiceWithExceptionCatch {

        @ExceptionCatch(value = {IllegalArgumentException.class})
        public String methodWithAnnotation() {
            return "result";
        }

        @ExceptionCatch(value = {RuntimeException.class}, silent = true)
        public String silentMethod() {
            return "result";
        }

        @ExceptionCatch(defaultValue = "default_value")
        public String methodWithDefault() {
            return "result";
        }

        @ExceptionCatch(defaultValue = "42")
        public int methodReturningInt() {
            return 0;
        }

        @ExceptionCatch(defaultValue = "true")
        public boolean methodReturningBoolean() {
            return false;
        }

        @ExceptionCatch(defaultValue = "null")
        public String methodReturningNull() {
            return "result";
        }

        @ExceptionCatch(defaultValue = "empty")
        public List<String> methodReturningEmptyList() {
            return Collections.singletonList("item");
        }

        @ExceptionCatch(defaultValue = "empty")
        public String[] methodReturningEmptyArray() {
            return new String[]{"item"};
        }

        @ExceptionCatch(defaultValue = "empty")
        public String methodReturningEmptyString() {
            return "not empty";
        }

        @ExceptionCatch(handler = "customHandler")
        public String methodWithCustomHandler() {
            return "result";
        }

        public String methodWithoutAnnotation() {
            return "result";
        }
    }

    @ExceptionCatch(value = {NullPointerException.class})
    public static class AnnotatedClass {
        public String method() {
            return "result";
        }
    }

    @BeforeEach
    void setUp() {
        interceptor = new ExceptionInterceptor();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create interceptor with default constructor")
        void shouldCreateInterceptorWithDefaultConstructor() {
            ExceptionInterceptor interceptor = new ExceptionInterceptor();
            assertNotNull(interceptor);
        }

        @Test
        @DisplayName("Should create interceptor with global handlers")
        void shouldCreateInterceptorWithGlobalHandlers() {
            ExceptionHandler handler = mock(ExceptionHandler.class);
            List<ExceptionHandler> handlers = Collections.singletonList(handler);

            ExceptionInterceptor interceptor = new ExceptionInterceptor(handlers);

            assertNotNull(interceptor);
        }

        @Test
        @DisplayName("Should handle null global handlers list")
        void shouldHandleNullGlobalHandlersList() {
            ExceptionInterceptor interceptor = new ExceptionInterceptor(null);
            assertNotNull(interceptor);
        }
    }

    @Nested
    @DisplayName("invoke() Tests - No Exception")
    class InvokeNoExceptionTests {

        @Test
        @DisplayName("Should proceed normally when no exception")
        void shouldProceedNormallyWhenNoException() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("result", result);
            verify(mockInvocation).proceed();
        }

        @Test
        @DisplayName("Should proceed for method without annotation")
        void shouldProceedForMethodWithoutAnnotation() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithoutAnnotation");

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenReturn("result");

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("result", result);
        }
    }

    @Nested
    @DisplayName("invoke() Tests - Exception Handling")
    class InvokeExceptionHandlingTests {

        @Test
        @DisplayName("Should catch specified exception type")
        void shouldCatchSpecifiedExceptionType() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new IllegalArgumentException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result); // Default return value for String
        }

        @Test
        @DisplayName("Should rethrow non-matching exception")
        void shouldRethrowNonMatchingException() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new NullPointerException("Test error"));

            assertThrows(NullPointerException.class, () -> interceptor.invoke(mockInvocation));
        }

        @Test
        @DisplayName("Should rethrow for method without annotation")
        void shouldRethrowForMethodWithoutAnnotation() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithoutAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            assertThrows(RuntimeException.class, () -> interceptor.invoke(mockInvocation));
        }

        @Test
        @DisplayName("Should catch exception from class-level annotation")
        void shouldCatchExceptionFromClassLevelAnnotation() throws Throwable {
            Method method = AnnotatedClass.class.getMethod("method");
            Object target = new AnnotatedClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new NullPointerException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Default Value Parsing Tests")
    class DefaultValueParsingTests {

        @Test
        @DisplayName("Should return default value when specified")
        void shouldReturnDefaultValueWhenSpecified() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithDefault");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("default_value", result);
        }

        @Test
        @DisplayName("Should parse integer default value")
        void shouldParseIntegerDefaultValue() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningInt");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should parse boolean true default value")
        void shouldParseBooleanTrueDefaultValue() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningBoolean");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should parse null default value")
        void shouldParseNullDefaultValue() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningNull");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return empty list when empty specified")
        void shouldReturnEmptyListWhenEmptySpecified() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningEmptyList");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNotNull(result);
            assertTrue(result instanceof List);
            assertTrue(((List<?>) result).isEmpty());
        }

        @Test
        @DisplayName("Should return empty array when empty specified")
        void shouldReturnEmptyArrayWhenEmptySpecified() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningEmptyArray");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNotNull(result);
            assertTrue(result instanceof String[]);
            assertEquals(0, ((String[]) result).length);
        }

        @Test
        @DisplayName("Should return empty string when empty specified")
        void shouldReturnEmptyStringWhenEmptySpecified() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("methodReturningEmptyString");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("", result);
        }
    }

    @Nested
    @DisplayName("Global Handler Tests")
    class GlobalHandlerTests {

        @Test
        @DisplayName("Should use global handler when exception not matched")
        void shouldUseGlobalHandlerWhenExceptionNotMatched() throws Throwable {
            ExceptionHandler globalHandler = mock(ExceptionHandler.class);
            when(globalHandler.supports(RuntimeException.class)).thenReturn(true);
            when(globalHandler.handleException(any(), any(), any(), any())).thenReturn("handled");

            interceptor = new ExceptionInterceptor(Collections.singletonList(globalHandler));

            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithoutAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.getArguments()).thenReturn(new Object[0]);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("handled", result);
            verify(globalHandler).handleException(any(), eq(target), eq(method), any());
        }

        @Test
        @DisplayName("Should try multiple global handlers")
        void shouldTryMultipleGlobalHandlers() throws Throwable {
            ExceptionHandler handler1 = mock(ExceptionHandler.class);
            ExceptionHandler handler2 = mock(ExceptionHandler.class);

            when(handler1.supports(RuntimeException.class)).thenReturn(false);
            when(handler2.supports(RuntimeException.class)).thenReturn(true);
            when(handler2.handleException(any(), any(), any(), any())).thenReturn("handled by 2");

            interceptor = new ExceptionInterceptor(Arrays.asList(handler1, handler2));

            Method method = ServiceWithExceptionCatch.class.getMethod("methodWithoutAnnotation");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.getArguments()).thenReturn(new Object[0]);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals("handled by 2", result);
            verify(handler1).supports(RuntimeException.class);
            verify(handler2).supports(RuntimeException.class);
            verify(handler1, never()).handleException(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Custom Handler Tests")
    class CustomHandlerTests {

        @Test
        @DisplayName("Should use custom handler when specified")
        void shouldUseCustomHandlerWhenSpecified() throws Throwable {
            try (MockedStatic<ContextHolder> contextHolderMock = mockStatic(ContextHolder.class)) {
                ExceptionHandler customHandler = mock(ExceptionHandler.class);
                when(customHandler.handleException(any(), any(), any(), any())).thenReturn("custom handled");

                contextHolderMock.when(ContextHolder::getContext).thenReturn(mockContext);
                when(mockContext.getBean("customHandler")).thenReturn(customHandler);

                Method method = ServiceWithExceptionCatch.class.getMethod("methodWithCustomHandler");
                Object target = new ServiceWithExceptionCatch();

                when(mockInvocation.getMethod()).thenReturn(method);
                when(mockInvocation.getTarget()).thenReturn(target);
                when(mockInvocation.getArguments()).thenReturn(new Object[0]);
                when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

                Object result = interceptor.invoke(mockInvocation);

                assertEquals("custom handled", result);
            }
        }

        @Test
        @DisplayName("Should fallback when custom handler not found")
        void shouldFallbackWhenCustomHandlerNotFound() throws Throwable {
            try (MockedStatic<ContextHolder> contextHolderMock = mockStatic(ContextHolder.class)) {
                contextHolderMock.when(ContextHolder::getContext).thenReturn(mockContext);
                when(mockContext.getBean("customHandler")).thenThrow(new RuntimeException("Bean not found"));

                Method method = ServiceWithExceptionCatch.class.getMethod("methodWithCustomHandler");
                Object target = new ServiceWithExceptionCatch();

                when(mockInvocation.getMethod()).thenReturn(method);
                when(mockInvocation.getTarget()).thenReturn(target);
                when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

                Object result = interceptor.invoke(mockInvocation);

                assertNull(result); // Falls back to default value
            }
        }
    }

    @Nested
    @DisplayName("Silent Mode Tests")
    class SilentModeTests {

        @Test
        @DisplayName("Should catch exception in silent mode")
        void shouldCatchExceptionInSilentMode() throws Throwable {
            Method method = ServiceWithExceptionCatch.class.getMethod("silentMethod");
            Object target = new ServiceWithExceptionCatch();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Additional Default Value Tests")
    class AdditionalDefaultValueTests {

        public class AdditionalTestClass {

            @ExceptionCatch(defaultValue = "3.14")
            public double methodReturningDouble() {
                return 0.0;
            }

            @ExceptionCatch(defaultValue = "999")
            public long methodReturningLong() {
                return 0L;
            }

            @ExceptionCatch(defaultValue = "2.5")
            public float methodReturningFloat() {
                return 0.0f;
            }

            @ExceptionCatch(defaultValue = "false")
            public boolean methodReturningFalse() {
                return true;
            }

            @ExceptionCatch(defaultValue = "empty")
            public Set<String> methodReturningEmptySet() {
                return Collections.singleton("item");
            }

            @ExceptionCatch(defaultValue = "empty")
            public Map<String, String> methodReturningEmptyMap() {
                return Collections.singletonMap("key", "value");
            }

            @ExceptionCatch(defaultValue = "empty")
            public Optional<String> methodReturningEmptyOptional() {
                return Optional.of("value");
            }

            @ExceptionCatch
            public int methodWithPrimitiveInt() {
                return 10;
            }

            @ExceptionCatch
            public boolean methodWithPrimitiveBoolean() {
                return true;
            }

            @ExceptionCatch
            public char methodWithPrimitiveChar() {
                return 'a';
            }

            @ExceptionCatch
            public byte methodWithPrimitiveByte() {
                return 1;
            }

            @ExceptionCatch
            public short methodWithPrimitiveShort() {
                return 1;
            }

            @ExceptionCatch
            public void methodReturningVoid() {
                throw new RuntimeException("error");
            }

            @ExceptionCatch(defaultValue = "invalid_number")
            public int methodWithInvalidNumber() {
                return 10;
            }

            @ExceptionCatch(value = {IllegalArgumentException.class, NullPointerException.class})
            public String methodWithMultipleExceptionTypes() {
                return "result";
            }
        }

        @Test
        @DisplayName("Should parse double default value")
        void shouldParseDoubleDefaultValue() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningDouble");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(3.14, result);
        }

        @Test
        @DisplayName("Should parse long default value")
        void shouldParseLongDefaultValue() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningLong");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(999L, result);
        }

        @Test
        @DisplayName("Should parse float default value")
        void shouldParseFloatDefaultValue() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningFloat");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(2.5f, result);
        }

        @Test
        @DisplayName("Should parse boolean false default value")
        void shouldParseBooleanFalseDefaultValue() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningFalse");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(false, result);
        }

        @Test
        @DisplayName("Should return empty set when empty specified")
        void shouldReturnEmptySetWhenEmptySpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningEmptySet");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNotNull(result);
            assertTrue(result instanceof Set);
            assertTrue(((Set<?>) result).isEmpty());
        }

        @Test
        @DisplayName("Should return empty map when empty specified")
        void shouldReturnEmptyMapWhenEmptySpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningEmptyMap");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNotNull(result);
            assertTrue(result instanceof Map);
            assertTrue(((Map<?, ?>) result).isEmpty());
        }

        @Test
        @DisplayName("Should return empty optional when empty specified")
        void shouldReturnEmptyOptionalWhenEmptySpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningEmptyOptional");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNotNull(result);
            assertTrue(result instanceof Optional);
            assertFalse(((Optional<?>) result).isPresent());
        }

        @Test
        @DisplayName("Should return default int when no default specified")
        void shouldReturnDefaultIntWhenNoDefaultSpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithPrimitiveInt");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("Should return default boolean when no default specified")
        void shouldReturnDefaultBooleanWhenNoDefaultSpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithPrimitiveBoolean");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(false, result);
        }

        @Test
        @DisplayName("Should return default char when no default specified")
        void shouldReturnDefaultCharWhenNoDefaultSpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithPrimitiveChar");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals('\0', result);
        }

        @Test
        @DisplayName("Should return default byte when no default specified")
        void shouldReturnDefaultByteWhenNoDefaultSpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithPrimitiveByte");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals((byte) 0, result);
        }

        @Test
        @DisplayName("Should return default short when no default specified")
        void shouldReturnDefaultShortWhenNoDefaultSpecified() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithPrimitiveShort");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals((short) 0, result);
        }

        @Test
        @DisplayName("Should return null for void method")
        void shouldReturnNullForVoidMethod() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodReturningVoid");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }

        @Test
        @DisplayName("Should fallback to default for invalid number")
        void shouldFallbackToDefaultForInvalidNumber() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithInvalidNumber");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new RuntimeException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertEquals(0, result); // Falls back to primitive default
        }

        @Test
        @DisplayName("Should catch first matching exception type")
        void shouldCatchFirstMatchingExceptionType() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithMultipleExceptionTypes");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new IllegalArgumentException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }

        @Test
        @DisplayName("Should catch second matching exception type")
        void shouldCatchSecondMatchingExceptionType() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithMultipleExceptionTypes");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new NullPointerException("Test error"));

            Object result = interceptor.invoke(mockInvocation);

            assertNull(result);
        }

        @Test
        @DisplayName("Should rethrow non-matching exception from multiple types")
        void shouldRethrowNonMatchingExceptionFromMultipleTypes() throws Throwable {
            Method method = AdditionalTestClass.class.getMethod("methodWithMultipleExceptionTypes");
            Object target = new AdditionalTestClass();

            when(mockInvocation.getMethod()).thenReturn(method);
            when(mockInvocation.getTarget()).thenReturn(target);
            when(mockInvocation.proceed()).thenThrow(new ArrayIndexOutOfBoundsException("Test error"));

            assertThrows(ArrayIndexOutOfBoundsException.class, () -> interceptor.invoke(mockInvocation));
        }
    }
}
