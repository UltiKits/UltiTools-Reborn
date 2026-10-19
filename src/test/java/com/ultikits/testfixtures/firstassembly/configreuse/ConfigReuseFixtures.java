package com.ultikits.testfixtures.firstassembly.configreuse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.Service;

/**
 * Fixtures for {@code ComponentScannerConfigurationReuseTest}: a {@code @Configuration} class the
 * container creates early, because a configuration class assembled before it needs it, must not be
 * constructed a second time when the assembly phase reaches it.
 * <p>
 * Two scenarios, each a pair of configuration classes listed first-then-second in the test's JAR:
 * <ul>
 *   <li>direct: {@link DirectConfigA} {@code @Autowired}s {@link DirectConfigB};</li>
 *   <li>through a service: {@link ServiceConfigA} {@code @Autowired}s {@link BridgeService}, which
 *       {@code @Autowired}s {@link ServiceConfigB}.</li>
 * </ul>
 * Each second configuration class records every construction, {@code @PostConstruct},
 * {@code @PreDestroy} and {@code @Bean} call in its own {@link Recorder}.
 */
public final class ConfigReuseFixtures {

    private ConfigReuseFixtures() {
    }

    /** What happened to the instances of one configuration class, in order. */
    public static final class Recorder {
        private final List<Object> constructed = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> postConstructed = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> preDestroyed = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> beanMethodCalls = Collections.synchronizedList(new ArrayList<>());

        /** Forgets everything recorded so far. */
        public void reset() {
            constructed.clear();
            postConstructed.clear();
            preDestroyed.clear();
            beanMethodCalls.clear();
        }

        /** @return the instances constructed, in order */
        public List<Object> constructed() {
            return new ArrayList<>(constructed);
        }

        /** @return the instances whose {@code @PostConstruct} ran, in order */
        public List<Object> postConstructed() {
            return new ArrayList<>(postConstructed);
        }

        /** @return the instances whose {@code @PreDestroy} ran, in order */
        public List<Object> preDestroyed() {
            return new ArrayList<>(preDestroyed);
        }

        /** @return the instances whose {@code @Bean} method ran, once per call, in order */
        public List<Object> beanMethodCalls() {
            return new ArrayList<>(beanMethodCalls);
        }
    }

    /** Assembled first; needs {@link DirectConfigB} directly. */
    @Configuration
    public static class DirectConfigA {

        @Autowired
        private DirectConfigB second;

        /** @return the injected second configuration */
        public DirectConfigB getSecond() {
            return second;
        }
    }

    /** Assembled second; created early because {@link DirectConfigA} needs it. */
    @Configuration
    public static class DirectConfigB {

        /** Shared record of every {@code DirectConfigB} instance. */
        public static final Recorder RECORD = new Recorder();

        /** Records the construction. */
        public DirectConfigB() {
            RECORD.constructed.add(this);
        }

        @PostConstruct
        void started() {
            RECORD.postConstructed.add(this);
        }

        @PreDestroy
        void stopped() {
            RECORD.preDestroyed.add(this);
        }

        /** @return a new product */
        @Bean
        public DirectProduct directProduct() {
            RECORD.beanMethodCalls.add(this);
            return new DirectProduct();
        }
    }

    /** The {@code @Bean} product of {@link DirectConfigB}. */
    public static class DirectProduct {
    }

    /** Assembled first; needs {@link ServiceConfigB} through {@link BridgeService}. */
    @Configuration
    public static class ServiceConfigA {

        @Autowired
        private BridgeService bridge;

        /** @return the injected service */
        public BridgeService getBridge() {
            return bridge;
        }
    }

    /** A service between the two configuration classes. */
    @Service
    public static class BridgeService {

        @Autowired
        private ServiceConfigB second;

        /** @return the injected second configuration */
        public ServiceConfigB getSecond() {
            return second;
        }
    }

    /** Assembled second; created early because {@link BridgeService} needs it. */
    @Configuration
    public static class ServiceConfigB {

        /** Shared record of every {@code ServiceConfigB} instance. */
        public static final Recorder RECORD = new Recorder();

        /** Records the construction. */
        public ServiceConfigB() {
            RECORD.constructed.add(this);
        }

        @PostConstruct
        void started() {
            RECORD.postConstructed.add(this);
        }

        @PreDestroy
        void stopped() {
            RECORD.preDestroyed.add(this);
        }

        /** @return a new product */
        @Bean
        public ServiceProduct serviceProduct() {
            RECORD.beanMethodCalls.add(this);
            return new ServiceProduct();
        }
    }

    /** The {@code @Bean} product of {@link ServiceConfigB}. */
    public static class ServiceProduct {
    }
}
