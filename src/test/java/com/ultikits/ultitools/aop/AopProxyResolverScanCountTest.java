package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * D-37's call-count guard: {@link AopProxyResolver#resolve(Class)} performs exactly one reflective
 * hierarchy scan per resolution, whatever branch it takes, instead of the three-to-four rescans the
 * unconsolidated version repeated for the unavailable-annotation locator, the intercepted-method
 * collector, {@code AopEligibility.findAopAnnotatedMethods}, and the diagnostic pass.
 * <p>
 * This is the guard D-37 says is not optional and gives no compile-time guarantee on its own - see
 * {@code MethodScan}'s own javadoc for the stated limitation. Wraps {@link ReflectionUtil} with
 * {@code CALLS_REAL_METHODS} so every scan still runs for real; only the invocation count is
 * intercepted.
 *
 * @author wisdomme
 * @since 6.3.0
 */
@DisplayName("AopProxyResolver scan-count guard (D-37)")
class AopProxyResolverScanCountTest {

    public static class Guarded {
        @ExceptionCatch(silent = true, defaultValue = "boom")
        public String work() {
            throw new IllegalStateException("boom");
        }
    }

    public static class Plain {
        public String work() {
            return "plain";
        }
    }

    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelOverUnproxyableOnly {
        private String privateHelper() {
            return "private";
        }
    }

    private static AopProxyResolver exceptionCatchResolver() {
        AopProxyResolver resolver = new AopProxyResolver();
        resolver.addAdvisor(AopAdvisor.forAnnotation(ExceptionCatch.class,
                new ExceptionInterceptor(Collections.emptyList(), null), 200));
        return resolver;
    }

    @Nested
    @DisplayName("resolve()")
    class Resolving {

        @Test
        @DisplayName("Should scan exactly once when the bean ends up proxied")
        void shouldScanOnceWhenProxied() {
            try (MockedStatic<ReflectionUtil> scans = mockStatic(ReflectionUtil.class, CALLS_REAL_METHODS)) {
                AopProxyResolver resolver = exceptionCatchResolver();

                Class<?> resolved = resolver.resolve(Guarded.class);

                assertNotNull(resolved);
                scans.verify(() -> ReflectionUtil.getAllMethods(any()), times(1));
            }
        }

        @Test
        @DisplayName("Should scan exactly once when no advisors are registered")
        void shouldScanOnceWithNoAdvisors() {
            try (MockedStatic<ReflectionUtil> scans = mockStatic(ReflectionUtil.class, CALLS_REAL_METHODS)) {
                AopProxyResolver resolver = new AopProxyResolver();

                Class<?> resolved = resolver.resolve(Plain.class);

                assertEquals(Plain.class, resolved);
                scans.verify(() -> ReflectionUtil.getAllMethods(any()), times(1));
            }
        }

        @Test
        @DisplayName("Should scan exactly once when nothing ends up intercepted")
        void shouldScanOnceWithNothingIntercepted() {
            try (MockedStatic<ReflectionUtil> scans = mockStatic(ReflectionUtil.class, CALLS_REAL_METHODS)) {
                AopProxyResolver resolver = exceptionCatchResolver();

                Class<?> resolved = resolver.resolve(ClassLevelOverUnproxyableOnly.class);

                assertEquals(ClassLevelOverUnproxyableOnly.class, resolved);
                scans.verify(() -> ReflectionUtil.getAllMethods(any()), times(1));
            }
        }

        @Test
        @DisplayName("Should not scan again when the same bean class is resolved twice")
        void shouldNotRescanOnMemoHit() {
            try (MockedStatic<ReflectionUtil> scans = mockStatic(ReflectionUtil.class, CALLS_REAL_METHODS)) {
                AopProxyResolver resolver = exceptionCatchResolver();

                resolver.resolve(Guarded.class);
                scans.verify(() -> ReflectionUtil.getAllMethods(any()), times(1));

                Class<?> secondResolution = resolver.resolve(Guarded.class);

                assertNotNull(secondResolution);
                // Zero further scans - the memo hit returns before MethodScan is ever built.
                scans.verify(() -> ReflectionUtil.getAllMethods(any()), times(1));
            }
        }

        @Test
        @DisplayName("A proxied bean's outcome is unchanged by reading from the shared scan")
        void proxyOutcomeUnchanged() {
            AopProxyResolver resolver = exceptionCatchResolver();

            Class<?> resolved = resolver.resolve(Guarded.class);

            assertNotNull(resolved);
            assertFalse(Guarded.class.equals(resolved),
                    "a bean with an interceptable @ExceptionCatch method must still be proxied");
        }
    }
}
