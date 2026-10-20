package com.ultikits.testfixtures.firstassembly.aop;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.annotations.UltiToolsModule;

/**
 * C08 fixture: the module main class {@code @Autowired}s two services that carry AOP annotations.
 * Registering the plugin singleton autowires this class, which creates both services; they must
 * be created as AOP proxies, so AOP has to be wired before that first creation.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.firstassembly.aop")
public class AopReadyFixtureModule extends UltiToolsPlugin {

    @Autowired
    private GuardedService guardedService;

    @Autowired
    private TransactionalService transactionalService;

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** @return the injected {@code @ExceptionCatch} service, or {@code null} */
    public GuardedService getGuardedService() {
        return guardedService;
    }

    /** @return the injected {@code @Transactional} service, or {@code null} */
    public TransactionalService getTransactionalService() {
        return transactionalService;
    }

    /** A service whose guarded method throws; {@code @ExceptionCatch} turns that into -1. */
    @Service
    public static class GuardedService {

        /**
         * @return -1 through the {@code @ExceptionCatch} proxy; throws on an unproxied instance
         */
        @ExceptionCatch(silent = true, defaultValue = "-1")
        public int guarded() {
            throw new IllegalStateException("C08: an unproxied service lets this escape");
        }
    }

    /** A service with one {@code @Transactional} method. */
    @Service
    public static class TransactionalService {

        /** Does nothing; the proxy begins and commits a transaction around it. */
        @Transactional
        public void write() {
            // The transaction boundary is the behaviour under test.
        }
    }
}
