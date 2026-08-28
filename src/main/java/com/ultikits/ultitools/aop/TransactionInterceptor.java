package com.ultikits.ultitools.aop;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.annotations.Isolation;
import com.ultikits.ultitools.annotations.Propagation;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.interfaces.TransactionManager;

/**
 * AOP interceptor that implements declarative transaction management.
 * <p>
 * This interceptor processes @Transactional annotations and manages
 * transaction boundaries accordingly.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class TransactionInterceptor implements MethodInterceptor {

    /** Instance-scoped on purpose - see AnnotationLookupCache's class javadoc. */
    private final AnnotationLookupCache<Transactional> lookupCache =
            new AnnotationLookupCache<>(Transactional.class);

    private static final Logger LOGGER = Logger.getLogger(TransactionInterceptor.class.getName());

    private final TransactionManager transactionManager;

    /**
     * Creates a new transaction interceptor.
     *
     * @param transactionManager the transaction manager to use
     */
    public TransactionInterceptor(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // Get @Transactional annotation from method or class
        // The same lookup the advisor used to decide this method should be intercepted. Reading
        // method.getAnnotation plus declaringClass.getAnnotation here disagreed with it in two
        // shapes the advisor now honours - an annotation on a declaration this method overrides,
        // and a class-level annotation on an ancestor - so the method was proxied while this found
        // nothing and called proceed(): the body ran with no transaction and no diagnostic. That is
        // the "proxied, annotated and inert" failure the ExceptionInterceptor half already fixed.
        // See issue #309.
        Transactional tx = lookupCache.ownMethod(method);
        if (tx == null) {
            tx = lookupCache.classLevel(method);
        }
        if (tx == null) {
            // Spring's precedence - see ExceptionInterceptor for the ordering rationale.
            tx = lookupCache.inheritedMethod(method);
        }

        // No @Transactional - proceed without transaction management
        if (tx == null) {
            return invocation.proceed();
        }

        return executeWithTransaction(invocation, tx);
    }

    /**
     * Executes the method invocation within a transaction context.
     */
    private Object executeWithTransaction(MethodInvocation invocation, Transactional tx) throws Throwable {
        Propagation propagation = tx.propagation();
        boolean existingTx = transactionManager.hasActiveTransaction();

        switch (propagation) {
            case REQUIRED:
                if (!existingTx) {
                    return executeInNewTransaction(invocation, tx);
                }
                return invocation.proceed();

            case REQUIRES_NEW: {
                // D-09: always suspend whatever is active (a no-op returning null if nothing is),
                // begin a genuinely new and independent transaction, and resume the suspended
                // frame in a finally so a failure on the inner path cannot strand it.
                Object suspended = transactionManager.suspend();
                try {
                    return executeInNewTransaction(invocation, tx);
                } finally {
                    transactionManager.resume(suspended);
                }
            }

            case SUPPORTS:
                // Execute with or without transaction
                return invocation.proceed();

            case MANDATORY:
                if (!existingTx) {
                    throw new IllegalStateException(
                            "No existing transaction found for MANDATORY propagation on method: " +
                                    invocation.getMethod().getName());
                }
                return invocation.proceed();

            case NOT_SUPPORTED: {
                // D-09: same suspend/resume shape as REQUIRES_NEW, minus the inner begin() - the
                // body runs with no active transaction at all.
                Object suspended = transactionManager.suspend();
                try {
                    return invocation.proceed();
                } finally {
                    transactionManager.resume(suspended);
                }
            }

            case NEVER:
                if (existingTx) {
                    throw new IllegalStateException(
                            "Existing transaction found for NEVER propagation on method: " +
                                    invocation.getMethod().getName());
                }
                return invocation.proceed();

            default:
                return invocation.proceed();
        }
    }

    /**
     * Executes the invocation in a new transaction.
     */
    private Object executeInNewTransaction(MethodInvocation invocation, Transactional tx) throws Throwable {
        String methodName = invocation.getMethod().getDeclaringClass().getSimpleName() + "." +
                invocation.getMethod().getName();

        LOGGER.fine("Starting transaction for: " + methodName);

        transactionManager.begin();

        // Apply transaction settings
        Isolation isolation = tx.isolation();
        if (isolation != Isolation.DEFAULT) {
            transactionManager.setIsolationLevel(isolation.getLevel());
        }

        if (tx.readOnly()) {
            transactionManager.setReadOnly(true);
        }

        if (tx.timeout() > 0) {
            transactionManager.setTimeout(tx.timeout());
        }

        try {
            Object result = invocation.proceed();
            transactionManager.commit();
            LOGGER.fine("Transaction committed for: " + methodName);
            return result;
        } catch (Throwable e) {
            if (shouldRollback(e, tx)) {
                LOGGER.log(Level.FINE, "Rolling back transaction for: " + methodName + " due to: " + e.getMessage());
                transactionManager.rollback();
            } else {
                LOGGER.fine("Committing transaction despite exception for: " + methodName);
                transactionManager.commit();
            }
            throw e;
        }
    }

    /**
     * Determines if the transaction should be rolled back for the given exception.
     * <p>
     * Additive, depth-ordered combination of {@code rollbackFor} and {@code noRollbackFor}
     * (D-06/D-07 - confirmed by the maintainer during 02-06's checkpoint, matching Spring's
     * {@code RuleBasedTransactionAttribute}): each array is checked for its shallowest matching
     * inheritance depth via {@link #shallowestMatchDepth(Throwable, Class[])}. When both arrays
     * match, the shallower depth wins; an exact-depth tie - including the same exception class
     * listed in both arrays - favours rollback. When only one array matches, that one decides.
     * When neither matches (including when both arrays are empty), this falls through to the
     * unchanged {@code RuntimeException}/{@code Error} default - a non-empty {@code rollbackFor}
     * with no match no longer short-circuits to "commit" the way it did before this fix.
     */
    private boolean shouldRollback(Throwable e, Transactional tx) {
        Integer rollbackDepth = shallowestMatchDepth(e, tx.rollbackFor());
        Integer noRollbackDepth = shallowestMatchDepth(e, tx.noRollbackFor());

        if (rollbackDepth != null && noRollbackDepth != null) {
            // Tie favours rollback: this is the "should have rolled back" failure direction,
            // chosen deliberately over silently committing.
            return rollbackDepth <= noRollbackDepth;
        }
        if (rollbackDepth != null) {
            return true;
        }
        if (noRollbackDepth != null) {
            return false;
        }

        // Neither rule matched (or both arrays are empty) - fall through to the default.
        return e instanceof RuntimeException || e instanceof Error;
    }

    /**
     * Returns the shallowest inheritance depth at which {@code thrown}'s runtime class matches any
     * rule in {@code rules}, or {@code null} if none match. Depth 0 is an exact class match; each
     * step up {@code thrown}'s superclass chain toward a matching rule adds 1.
     */
    private Integer shallowestMatchDepth(Throwable thrown, Class<? extends Throwable>[] rules) {
        Integer shallowest = null;
        for (Class<? extends Throwable> rule : rules) {
            if (rule.isInstance(thrown)) {
                int depth = inheritanceDepth(thrown.getClass(), rule);
                if (shallowest == null || depth < shallowest) {
                    shallowest = depth;
                }
            }
        }
        return shallowest;
    }

    /**
     * Walks {@code thrownClass}'s superclass chain to find the depth at which it matches {@code
     * ruleClass}. Callers only reach this after confirming {@code ruleClass.isInstance(...)} of an
     * instance of {@code thrownClass} already holds, so the walk is guaranteed to terminate at
     * {@code ruleClass} rather than at {@code null}.
     */
    private int inheritanceDepth(Class<?> thrownClass, Class<?> ruleClass) {
        int depth = 0;
        Class<?> current = thrownClass;
        while (current != null && !current.equals(ruleClass)) {
            current = current.getSuperclass();
            depth++;
        }
        return depth;
    }
}
