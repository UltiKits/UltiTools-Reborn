package com.ultikits.ultitools.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * Case C35: the control card {@code PROJECT.md} must say what the shipped proxies do with a call a
 * bean makes to its own method.
 * <p>
 * The behaviour half is a green control: with ByteBuddy subclass proxies the proxy is the bean, so
 * {@code this.inner()} reaches the interceptor and an inner {@code REQUIRES_NEW} call opens its own
 * transaction. The document half reads the AOP item of {@code PROJECT.md}'s gotcha list and asserts
 * that it states exactly that, with no claim that a call to the bean's own method skips the
 * interceptor chain.
 */
class SelfInvocationDocumentationTest {

    /** The bean under test: a REQUIRED method that calls its own REQUIRES_NEW method. */
    public static class SelfCallingService {
        @Transactional
        public String outer() {
            return "outer:" + inner();
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String inner() {
            return "inner";
        }
    }

    @Nested
    @DisplayName("Behaviour: a bean's call to its own method is intercepted")
    class BehaviourTests {

        @Test
        @DisplayName("an inner REQUIRES_NEW self-call suspends the caller and opens its own transaction")
        void innerRequiresNewSelfCallOpensItsOwnTransaction() throws Exception {
            TransactionManager manager = mock(TransactionManager.class);
            Object suspended = new Object();
            // First question: the outer call (nothing active yet). Second: the self-call, by then
            // inside the outer transaction.
            when(manager.hasActiveTransaction()).thenReturn(false, true);
            when(manager.suspend()).thenReturn(suspended);
            TransactionInterceptor interceptor = new TransactionInterceptor(manager);
            Set<Method> intercepted = new LinkedHashSet<>(Arrays.asList(
                    SelfCallingService.class.getMethod("outer"),
                    SelfCallingService.class.getMethod("inner")));

            SelfCallingService bean = new ProxyFactory(Collections.singletonList(interceptor))
                    .createProxyClass(SelfCallingService.class, intercepted)
                    .getDeclaredConstructor().newInstance();

            assertThat(bean.outer()).isEqualTo("outer:inner");

            InOrder order = inOrder(manager);
            order.verify(manager).begin();
            order.verify(manager).suspend();
            order.verify(manager).begin();
            order.verify(manager).commit();
            order.verify(manager).resume(suspended);
            order.verify(manager).commit();
            order.verifyNoMoreInteractions();
        }
    }

    @Nested
    @DisplayName("Document: PROJECT.md states the rule the proxies enforce")
    class DocumentTests {

        private final Pattern aopItem = Pattern.compile(
                "(?m)^\\d+\\. \\*\\*AOP[^\\n]*(?:\\n(?!\\d+\\. )[^\\n]*)*");

        private String aopGotcha() throws Exception {
            Path root = Paths.get(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
            Path card = root.resolve("PROJECT.md");
            assertThat(card).as("the control card must exist at the project base directory").isRegularFile();
            String text = new String(Files.readAllBytes(card), StandardCharsets.UTF_8);
            Matcher found = aopItem.matcher(text);
            assertThat(found.find()).as("PROJECT.md must keep a numbered gotcha item about AOP").isTrue();
            // Positive control for the extraction: the item is the one that names the proxy kind.
            assertThat(found.group()).contains("ByteBuddy subclass proxies");
            return found.group().replaceAll("\\s+", " ");
        }

        @Test
        @DisplayName("the AOP gotcha says self-invocation is intercepted")
        void aopGotchaSaysSelfInvocationIsIntercepted() throws Exception {
            String item = aopGotcha();

            assertThat(item).as("the AOP gotcha").containsIgnoringCase("self-invocation");
            assertThat(item).as("the AOP gotcha").containsIgnoringCase("is intercepted");
            assertThat(item).as("the AOP gotcha").contains("REQUIRES_NEW");
        }

        @Test
        @DisplayName("the AOP gotcha makes no claim that a call to the bean's own method skips the interceptor chain")
        void aopGotchaDoesNotClaimABypass() throws Exception {
            String item = aopGotcha();

            List<String> bypassWords = Arrays.asList("bypass", "skips the interceptor", "skip the interceptor",
                    "not intercepted", "never intercepted", "is not proxied");
            for (String word : bypassWords) {
                assertThat(item).as("the AOP gotcha must not claim: " + word).doesNotContainIgnoringCase(word);
            }
        }
    }
}
