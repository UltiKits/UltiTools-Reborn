package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * Pins D-14 (full assembly, unconditional on {@code refresh()} state) for
 * {@link SimpleContainer#registerSingleton}.
 * <p>
 * Before this plan, {@code registerSingleton} was a two-line method: {@code addSingleton} plus a
 * type-mapping write. No autowiring, no {@code @PostConstruct}, no {@code BeanPostProcessor}
 * chain. Every assembly case below is run <b>both</b> before and after {@code refresh()}, because
 * "the outcome does not depend on refresh state" is the substance of D-14 and a single-state test
 * cannot show it -- a post-refresh-only case would also pass under a narrower window-guard fix
 * that D-14 explicitly rejects as insufficient (it would have left the config entities, the
 * {@code @Configuration} instance, and the {@code @Bean} products -- all registered before
 * {@code refresh()} -- exactly as uninjected as before).
 */
@DisplayName("registerSingleton full assembly and AOP refusal (D-14/D-15)")
class RegisterSingletonAssemblyTest {

    // ===== Fixtures =====

    static class Dependency {
    }

    static class WithAutowiredField {
        @Autowired
        Dependency dependency;
    }

    static class WithPostConstruct {
        int callCount = 0;

        @PostConstruct
        void init() {
            callCount++;
        }
    }

    static class Plain {
        String value = "unchanged";
    }

    /** Substitutes the bean with a different instance in postProcessAfterInitialization. */
    static class SubstitutingPostProcessor implements BeanPostProcessor {
        final Object replacement;

        SubstitutingPostProcessor(Object replacement) {
            this.replacement = replacement;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return replacement;
        }
    }

    /** Records every bean name/instance it sees, for both processor callbacks. */
    static class RecordingPostProcessor implements BeanPostProcessor {
        java.util.List<String> beforeNames = new java.util.ArrayList<>();
        java.util.List<String> afterNames = new java.util.ArrayList<>();

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            beforeNames.add(beanName);
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            afterNames.add(beanName);
            return bean;
        }
    }

    // ===== Task 1: full assembly, unconditional on refresh() state (D-14) =====

    @Nested
    @DisplayName("@Autowired field is populated regardless of refresh() state")
    class AutowiringAssembly {

        @Test
        @DisplayName("before refresh(): field is populated")
        void populatedBeforeRefresh() {
            SimpleContainer container = new SimpleContainer();
            Dependency dependency = new Dependency();
            container.registerSingleton("dependency", dependency);

            WithAutowiredField bean = new WithAutowiredField();
            container.registerSingleton("withAutowiredField", bean);

            assertSame(dependency, bean.dependency,
                    "the @Autowired field must be populated even though refresh() has not run yet");
        }

        @Test
        @DisplayName("after refresh(): field is populated too -- same outcome, not gated on refresh state")
        void populatedAfterRefresh() {
            SimpleContainer container = new SimpleContainer();
            Dependency dependency = new Dependency();
            container.registerSingleton("dependency", dependency);
            container.refresh();

            WithAutowiredField bean = new WithAutowiredField();
            container.registerSingleton("withAutowiredField", bean);

            assertSame(dependency, bean.dependency,
                    "the @Autowired field must be populated after refresh() has already run -- "
                            + "D-14 explicitly rejects gating assembly on refresh state");
        }
    }

    @Nested
    @DisplayName("@PostConstruct is invoked exactly once, regardless of refresh() state")
    class PostConstructAssembly {

        @Test
        @DisplayName("before refresh(): @PostConstruct runs once")
        void invokedBeforeRefresh() {
            SimpleContainer container = new SimpleContainer();
            WithPostConstruct bean = new WithPostConstruct();

            container.registerSingleton("withPostConstruct", bean);

            assertEquals(1, bean.callCount,
                    "@PostConstruct must run exactly once as a side effect of registerSingleton, "
                            + "not merely leave the bean retrievable afterwards");
        }

        @Test
        @DisplayName("after refresh(): @PostConstruct runs once too")
        void invokedAfterRefresh() {
            SimpleContainer container = new SimpleContainer();
            container.refresh();
            WithPostConstruct bean = new WithPostConstruct();

            container.registerSingleton("withPostConstruct", bean);

            assertEquals(1, bean.callCount, "@PostConstruct must run once after refresh() too");
        }

        @Test
        @DisplayName("registering a second bean under the same name does not re-invoke the first's @PostConstruct")
        void secondRegistrationUnderSameNameDoesNotDoubleInvokeFirst() {
            SimpleContainer container = new SimpleContainer();
            WithPostConstruct first = new WithPostConstruct();
            container.registerSingleton("shared", first);
            assertEquals(1, first.callCount);

            WithPostConstruct second = new WithPostConstruct();
            container.registerSingleton("shared", second);

            assertEquals(1, first.callCount,
                    "the first object's own @PostConstruct must not run again just because a "
                            + "second object was registered under the same name");
            assertEquals(1, second.callCount, "the second object's own @PostConstruct must run exactly once");
        }
    }

    @Nested
    @DisplayName("BeanPostProcessor chain sees the instance both before and after initialization")
    class BeanPostProcessorChain {

        @Test
        @DisplayName("both callbacks are invoked with the registered name")
        void bothCallbacksInvoked() {
            SimpleContainer container = new SimpleContainer();
            RecordingPostProcessor processor = new RecordingPostProcessor();
            container.addBeanPostProcessor(processor);

            container.registerSingleton("plain", new Plain());

            assertEquals(java.util.Collections.singletonList("plain"), processor.beforeNames);
            assertEquals(java.util.Collections.singletonList("plain"), processor.afterNames);
        }

        @Test
        @DisplayName("a substitute returned from postProcessAfterInitialization is what getBean returns")
        void substituteIsStored() {
            SimpleContainer container = new SimpleContainer();
            Plain replacement = new Plain();
            replacement.value = "substituted";
            container.addBeanPostProcessor(new SubstitutingPostProcessor(replacement));

            container.registerSingleton("plain", new Plain());

            assertSame(replacement, container.getBean("plain"),
                    "getBean must return whatever postProcessAfterInitialization returned, not the "
                            + "original argument -- otherwise every processor sees the bean and none "
                            + "of them can actually affect it");
        }
    }

    @Nested
    @DisplayName("an object with nothing to assemble behaves exactly as before")
    class NoOpAssembly {

        @Test
        @DisplayName("stored and retrievable, unchanged")
        void storedAndRetrievable() {
            SimpleContainer container = new SimpleContainer();
            Plain plain = new Plain();

            container.registerSingleton("plain", plain);

            assertSame(plain, container.getBean("plain"));
            assertEquals("unchanged", plain.value);
        }
    }
}
