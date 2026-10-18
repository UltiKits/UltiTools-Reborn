package com.ultikits.testfixtures.beanalias;

import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.events.ModuleEvent;

/**
 * A {@code @Configuration} declaring one {@code @Bean} under two names, the shape
 * {@code ComponentScanner.processBeanMethod} binds with {@code registerSingleton} for the first
 * name and {@code addSingleton} for the alias. See this package's {@code package-info} for the
 * tests that scan it.
 */
@Configuration
public class BeanAliasFixtures {

    /** The primary name of the aliased product. */
    public static final String PRIMARY_NAME = "aliasedPrimary";

    /** The alias of the aliased product. */
    public static final String ALIAS_NAME = "aliasedSecondary";

    @Bean(name = {PRIMARY_NAME, ALIAS_NAME})
    public AliasedLifecycleBean aliasedLifecycleBean() {
        return new AliasedLifecycleBean();
    }

    /**
     * A plain product (no component stereotype, so the scan never registers it as a definition)
     * whose per-instance callbacks are counted.
     */
    public static class AliasedLifecycleBean {
        /** Number of {@code @PreDestroy} invocations since the last {@link #resetCounters()}. */
        public static int preDestroyCount;

        /** Resets every static counter; call at the start of each test. */
        public static void resetCounters() {
            preDestroyCount = 0;
        }

        @Scheduled(period = 20)
        public void tick() {
            // Scheduling is observed through the mocked TaskManager; the body never runs.
        }

        @ModuleEventHandler
        public void onFixtureEvent(FixtureModuleEvent event) {
            // Registration is observed through the mocked EventBus; the body never runs.
        }

        @PreDestroy
        public void shutdown() {
            preDestroyCount++;
        }
    }

    /** The event type {@link AliasedLifecycleBean#onFixtureEvent} handles. */
    public static class FixtureModuleEvent extends ModuleEvent {
    }
}
