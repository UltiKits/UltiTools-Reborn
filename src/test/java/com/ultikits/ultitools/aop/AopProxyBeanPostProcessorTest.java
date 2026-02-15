package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for AopProxyBeanPostProcessor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AopProxyBeanPostProcessor Tests")
class AopProxyBeanPostProcessorTest {

    private AopProxyBeanPostProcessor processor;

    @Mock
    private MethodInterceptor mockInterceptor;

    @Mock
    private AopAdvisor mockAdvisor;

    // Test annotation
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface TestMarker {}

    // Test classes
    public static class SimpleBean {
        public String getValue() {
            return "value";
        }
    }

    @TestMarker
    public static class AnnotatedBean {
        public String process() {
            return "processed";
        }
    }

    public static final class FinalBean {
        public String action() {
            return "action";
        }
    }

    @BeforeEach
    void setUp() {
        processor = new AopProxyBeanPostProcessor();
    }

    @Nested
    @DisplayName("Advisor Management Tests")
    class AdvisorManagementTests {

        @Test
        @DisplayName("Should add advisor")
        void shouldAddAdvisor() {
            processor.addAdvisor(mockAdvisor);
            
            List<AopAdvisor> advisors = processor.getAdvisors();
            assertEquals(1, advisors.size());
            assertTrue(advisors.contains(mockAdvisor));
        }

        @Test
        @DisplayName("Should add multiple advisors")
        void shouldAddMultipleAdvisors() {
            AopAdvisor advisor2 = mock(AopAdvisor.class);
            
            processor.addAdvisor(mockAdvisor);
            processor.addAdvisor(advisor2);
            
            List<AopAdvisor> advisors = processor.getAdvisors();
            assertEquals(2, advisors.size());
        }

        @Test
        @DisplayName("Should remove advisor")
        void shouldRemoveAdvisor() {
            processor.addAdvisor(mockAdvisor);
            
            boolean removed = processor.removeAdvisor(mockAdvisor);
            
            assertTrue(removed);
            assertTrue(processor.getAdvisors().isEmpty());
        }

        @Test
        @DisplayName("Should return false when removing non-existent advisor")
        void shouldReturnFalseWhenRemovingNonExistentAdvisor() {
            boolean removed = processor.removeAdvisor(mockAdvisor);
            
            assertFalse(removed);
        }

        @Test
        @DisplayName("Should sort advisors by order")
        void shouldSortAdvisorsByOrder() {
            AopAdvisor advisor1 = mock(AopAdvisor.class);
            AopAdvisor advisor2 = mock(AopAdvisor.class);
            AopAdvisor advisor3 = mock(AopAdvisor.class);
            
            when(advisor1.getOrder()).thenReturn(300);
            when(advisor2.getOrder()).thenReturn(100);
            when(advisor3.getOrder()).thenReturn(200);
            
            processor.addAdvisor(advisor1);
            processor.addAdvisor(advisor2);
            processor.addAdvisor(advisor3);
            
            List<AopAdvisor> advisors = processor.getAdvisors();
            assertEquals(100, advisors.get(0).getOrder());
            assertEquals(200, advisors.get(1).getOrder());
            assertEquals(300, advisors.get(2).getOrder());
        }

        @Test
        @DisplayName("Should return copy of advisors list")
        void shouldReturnCopyOfAdvisorsList() {
            processor.addAdvisor(mockAdvisor);
            
            List<AopAdvisor> advisors1 = processor.getAdvisors();
            List<AopAdvisor> advisors2 = processor.getAdvisors();
            
            assertNotSame(advisors1, advisors2);
            assertEquals(advisors1, advisors2);
        }
    }

    @Nested
    @DisplayName("postProcessAfterInitialization Tests")
    class PostProcessTests {

        @Test
        @DisplayName("Should return null bean as is")
        void shouldReturnNullBeanAsIs() {
            Object result = processor.postProcessAfterInitialization(null, "nullBean");
            
            assertNull(result);
        }

        @Test
        @DisplayName("Should return bean when no advisors")
        void shouldReturnBeanWhenNoAdvisors() {
            SimpleBean bean = new SimpleBean();
            
            Object result = processor.postProcessAfterInitialization(bean, "simpleBean");
            
            assertSame(bean, result);
        }

        @Test
        @DisplayName("Should return bean when no advisor matches")
        void shouldReturnBeanWhenNoAdvisorMatches() {
            SimpleBean bean = new SimpleBean();
            when(mockAdvisor.matches(any(Method.class), eq(SimpleBean.class))).thenReturn(false);
            
            processor.addAdvisor(mockAdvisor);
            Object result = processor.postProcessAfterInitialization(bean, "simpleBean");
            
            assertSame(bean, result);
        }

        @Test
        @DisplayName("Should return final bean without proxy")
        void shouldReturnFinalBeanWithoutProxy() {
            FinalBean bean = new FinalBean();
            when(mockAdvisor.matches(any(Method.class), eq(FinalBean.class))).thenReturn(true);
            
            processor.addAdvisor(mockAdvisor);
            Object result = processor.postProcessAfterInitialization(bean, "finalBean");
            
            assertSame(bean, result);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty advisors list gracefully")
        void shouldHandleEmptyAdvisorsListGracefully() {
            SimpleBean bean = new SimpleBean();
            
            Object result = processor.postProcessAfterInitialization(bean, "bean");
            
            assertSame(bean, result);
        }

        @Test
        @DisplayName("Should get empty advisors list initially")
        void shouldGetEmptyAdvisorsListInitially() {
            List<AopAdvisor> advisors = processor.getAdvisors();
            
            assertNotNull(advisors);
            assertTrue(advisors.isEmpty());
        }

        @Test
        @DisplayName("Should handle bean with no declared methods")
        void shouldHandleBeanWithNoDeclaredMethods() {
            Object emptyBean = new Object() {};
            
            processor.addAdvisor(mockAdvisor);
            Object result = processor.postProcessAfterInitialization(emptyBean, "emptyBean");
            
            assertSame(emptyBean, result);
        }
    }

    // Integration tests require CGLIB to work properly.
    // On Java 17+, they need --add-opens java.base/java.lang=ALL-UNNAMED JVM argument.
    @Nested
    @DisplayName("Integration Tests (requires CGLIB)")
    @Disabled("CGLIB requires --add-opens JVM args on Java 17+")
    class IntegrationTests {

        @Test
        @DisplayName("Should create proxy when advisor matches")
        void shouldCreateProxyWhenAdvisorMatches() {
            SimpleBean bean = new SimpleBean();
            when(mockAdvisor.matches(any(Method.class), eq(SimpleBean.class))).thenReturn(true);
            when(mockAdvisor.getInterceptor()).thenReturn(mockInterceptor);
            when(mockAdvisor.getOrder()).thenReturn(0);
            
            processor.addAdvisor(mockAdvisor);
            Object result = processor.postProcessAfterInitialization(bean, "simpleBean");
            
            assertNotNull(result);
            assertNotSame(bean, result);
            assertTrue(result instanceof SimpleBean);
        }

        @Test
        @DisplayName("Should intercept method calls on proxy")
        void shouldInterceptMethodCallsOnProxy() throws Throwable {
            SimpleBean bean = new SimpleBean();
            
            // Setup interceptor to return modified value
            when(mockInterceptor.invoke(any(MethodInvocation.class))).thenAnswer(invocation -> {
                MethodInvocation mi = invocation.getArgument(0);
                return "intercepted:" + mi.proceed();
            });
            
            when(mockAdvisor.matches(any(Method.class), eq(SimpleBean.class))).thenReturn(true);
            when(mockAdvisor.getInterceptor()).thenReturn(mockInterceptor);
            when(mockAdvisor.getOrder()).thenReturn(0);
            
            processor.addAdvisor(mockAdvisor);
            SimpleBean proxy = (SimpleBean) processor.postProcessAfterInitialization(bean, "bean");
            
            String result = proxy.getValue();
            
            assertEquals("intercepted:value", result);
        }
    }
}
