package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for ReflectiveMethodInvocation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectiveMethodInvocation Tests")
class ReflectiveMethodInvocationTest {

    @Mock
    private MethodInterceptor mockInterceptor;

    private Method testMethod;
    private Object target;

    // Test class
    public static class TestTarget {
        public String process(String input) {
            return "processed:" + input;
        }

        public int add(int a, int b) {
            return a + b;
        }

        public void voidMethod() {
            // no-op
        }

        public String noArgs() {
            return "noArgs";
        }
    }

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        target = new TestTarget();
        testMethod = TestTarget.class.getMethod("process", String.class);
    }

    @Nested
    @DisplayName("Basic Getter Tests")
    class BasicGetterTests {

        @Test
        @DisplayName("Should return correct target")
        void shouldReturnCorrectTarget() {
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            assertSame(target, invocation.getTarget());
        }

        @Test
        @DisplayName("Should return correct method")
        void shouldReturnCorrectMethod() {
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            assertSame(testMethod, invocation.getMethod());
        }

        @Test
        @DisplayName("Should return correct arguments")
        void shouldReturnCorrectArguments() {
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object[] returnedArgs = invocation.getArguments();
            assertArrayEquals(args, returnedArgs);
        }

        @Test
        @DisplayName("Should return empty arguments array")
        void shouldReturnEmptyArgumentsArray() throws NoSuchMethodException {
            Method noArgsMethod = TestTarget.class.getMethod("noArgs");
            Object[] args = new Object[0];
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, noArgsMethod, args, interceptors
            );

            Object[] returnedArgs = invocation.getArguments();
            assertNotNull(returnedArgs);
            assertEquals(0, returnedArgs.length);
        }

        @Test
        @DisplayName("Should return multiple arguments")
        void shouldReturnMultipleArguments() throws NoSuchMethodException {
            Method addMethod = TestTarget.class.getMethod("add", int.class, int.class);
            Object[] args = {5, 10};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, addMethod, args, interceptors
            );

            Object[] returnedArgs = invocation.getArguments();
            assertEquals(2, returnedArgs.length);
            assertEquals(5, returnedArgs[0]);
            assertEquals(10, returnedArgs[1]);
        }
    }

    @Nested
    @DisplayName("proceed() without interceptors Tests")
    class ProceedWithoutInterceptorsTests {

        @Test
        @DisplayName("Should invoke target method directly")
        void shouldInvokeTargetMethodDirectly() throws Throwable {
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("processed:test", result);
        }

        @Test
        @DisplayName("Should invoke void method")
        void shouldInvokeVoidMethod() throws Throwable {
            Method voidMethod = TestTarget.class.getMethod("voidMethod");
            Object[] args = new Object[0];
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, voidMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertNull(result);
        }

        @Test
        @DisplayName("Should invoke method with multiple parameters")
        void shouldInvokeMethodWithMultipleParameters() throws Throwable {
            Method addMethod = TestTarget.class.getMethod("add", int.class, int.class);
            Object[] args = {3, 7};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, addMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals(10, result);
        }
    }

    @Nested
    @DisplayName("proceed() with interceptors Tests")
    class ProceedWithInterceptorsTests {

        @Test
        @DisplayName("Should invoke single interceptor")
        void shouldInvokeSingleInterceptor() throws Throwable {
            Object[] args = {"test"};
            
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                MethodInvocation mi = inv.getArgument(0);
                return "intercepted:" + mi.proceed();
            });

            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("intercepted:processed:test", result);
        }

        @Test
        @DisplayName("Should invoke interceptors in chain order")
        void shouldInvokeInterceptorsInChainOrder() throws Throwable {
            Object[] args = {"test"};
            
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            
            when(interceptor1.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return "[1:" + ((MethodInvocation) inv.getArgument(0)).proceed() + "]";
            });
            when(interceptor2.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return "[2:" + ((MethodInvocation) inv.getArgument(0)).proceed() + "]";
            });

            List<MethodInterceptor> interceptors = Arrays.asList(interceptor1, interceptor2);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("[1:[2:processed:test]]", result);
        }

        @Test
        @DisplayName("Should invoke three interceptors in order")
        void shouldInvokeThreeInterceptorsInOrder() throws Throwable {
            Object[] args = {"input"};
            
            MethodInterceptor i1 = mock(MethodInterceptor.class);
            MethodInterceptor i2 = mock(MethodInterceptor.class);
            MethodInterceptor i3 = mock(MethodInterceptor.class);
            
            when(i1.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return "A-" + ((MethodInvocation) inv.getArgument(0)).proceed();
            });
            when(i2.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return "B-" + ((MethodInvocation) inv.getArgument(0)).proceed();
            });
            when(i3.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return "C-" + ((MethodInvocation) inv.getArgument(0)).proceed();
            });

            List<MethodInterceptor> interceptors = Arrays.asList(i1, i2, i3);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("A-B-C-processed:input", result);
        }

        @Test
        @DisplayName("Should allow interceptor to short-circuit chain")
        void shouldAllowInterceptorToShortCircuitChain() throws Throwable {
            Object[] args = {"test"};
            
            MethodInterceptor interceptor1 = mock(MethodInterceptor.class);
            MethodInterceptor interceptor2 = mock(MethodInterceptor.class);
            
            // First interceptor short-circuits - doesn't call proceed()
            when(interceptor1.invoke(any(MethodInvocation.class))).thenReturn("short-circuited");

            List<MethodInterceptor> interceptors = Arrays.asList(interceptor1, interceptor2);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("short-circuited", result);
            verify(interceptor2, never()).invoke(any(MethodInvocation.class));
        }

        @Test
        @DisplayName("Should pass same invocation to interceptors")
        void shouldPassSameContextToInterceptors() throws Throwable {
            Object[] args = {"test"};
            
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                MethodInvocation mi = inv.getArgument(0);
                assertSame(target, mi.getTarget());
                assertSame(testMethod, mi.getMethod());
                assertArrayEquals(args, mi.getArguments());
                return mi.proceed();
            });

            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            invocation.proceed();

            verify(mockInterceptor).invoke(any(MethodInvocation.class));
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("Should propagate target exception")
        void shouldPropagateTargetException() throws NoSuchMethodException {
            TestTarget exceptionTarget = new TestTarget() {
                @Override
                public String process(String input) {
                    throw new RuntimeException("Target error");
                }
            };
            
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                exceptionTarget, testMethod, args, interceptors
            );

            // InvocationTargetException wraps the actual exception
            Exception ex = assertThrows(Exception.class, invocation::proceed);
            String errorMessage = ex.getMessage();
            Throwable cause = ex.getCause();
            String causeMessage = cause != null ? cause.getMessage() : null;
            
            assertTrue(
                (errorMessage != null && errorMessage.contains("Target error")) || 
                (causeMessage != null && causeMessage.contains("Target error")),
                "Expected exception message to contain 'Target error'"
            );
        }

        @Test
        @DisplayName("Should propagate interceptor exception")
        void shouldPropagateInterceptorException() throws Throwable {
            Object[] args = {"test"};
            
            when(mockInterceptor.invoke(any(MethodInvocation.class)))
                .thenThrow(new RuntimeException("Interceptor error"));

            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            RuntimeException ex = assertThrows(RuntimeException.class, invocation::proceed);
            assertEquals("Interceptor error", ex.getMessage());
        }

        @Test
        @DisplayName("Should allow interceptor to catch and handle exception")
        void shouldAllowInterceptorToCatchException() throws Throwable {
            TestTarget exceptionTarget = new TestTarget() {
                @Override
                public String process(String input) {
                    throw new RuntimeException("Original error");
                }
            };
            
            Object[] args = {"test"};
            
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                try {
                    return ((MethodInvocation) inv.getArgument(0)).proceed();
                } catch (Exception e) {
                    // Handle both direct exception and wrapped exception
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return "handled:" + cause.getMessage();
                }
            });

            List<MethodInterceptor> interceptors = Collections.singletonList(mockInterceptor);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                exceptionTarget, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("handled:Original error", result);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null arguments")
        void shouldHandleNullArguments() throws Throwable {
            Object[] args = {null};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result = invocation.proceed();

            assertEquals("processed:null", result);
        }

        @Test
        @DisplayName("Should work with different target instances")
        void shouldWorkWithDifferentTargetInstances() throws Throwable {
            TestTarget target1 = new TestTarget() {
                @Override
                public String process(String input) {
                    return "target1:" + input;
                }
            };
            TestTarget target2 = new TestTarget() {
                @Override
                public String process(String input) {
                    return "target2:" + input;
                }
            };
            
            Object[] args = {"test"};
            List<MethodInterceptor> interceptors = Collections.emptyList();
            
            ReflectiveMethodInvocation inv1 = new ReflectiveMethodInvocation(
                target1, testMethod, args, interceptors
            );
            ReflectiveMethodInvocation inv2 = new ReflectiveMethodInvocation(
                target2, testMethod, args, interceptors
            );

            assertEquals("target1:test", inv1.proceed());
            assertEquals("target2:test", inv2.proceed());
        }

        @Test
        @DisplayName("Should be stateful - proceed can only be called properly in sequence")
        void shouldMaintainInterceptorIndex() throws Throwable {
            Object[] args = {"test"};
            
            MethodInterceptor countingInterceptor = mock(MethodInterceptor.class);
            when(countingInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(inv -> {
                return ((MethodInvocation) inv.getArgument(0)).proceed();
            });

            List<MethodInterceptor> interceptors = Collections.singletonList(countingInterceptor);
            ReflectiveMethodInvocation invocation = new ReflectiveMethodInvocation(
                target, testMethod, args, interceptors
            );

            Object result1 = invocation.proceed();
            
            assertEquals("processed:test", result1);
            verify(countingInterceptor, times(1)).invoke(any(MethodInvocation.class));
        }
    }
}
