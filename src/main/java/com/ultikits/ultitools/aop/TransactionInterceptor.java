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
        Transactional tx = lookupCache.methodLevel(method);
        if (tx == null) {
            tx = lookupCache.classLevel(method);
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

            case REQUIRES_NEW:
                // Always create a new transaction
                // Note: In a full implementation, we would suspend the existing transaction
                return executeInNewTransaction(invocation, tx);

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

            case NOT_SUPPORTED:
                // Execute without transaction (in a full impl, would suspend existing)
                return invocation.proceed();

            case NEVER:
                if (existingTx) {
                    throw new IllegalStateException(
                            "Existing transaction found for NEVER propagation on method: " +
                                    invocation.getMethod().getName());
                }
                return invocation.proceed();

            case NESTED:
                // For NESTED, we would create a savepoint in a full implementation
                // For now, treat it like REQUIRED
                if (!existingTx) {
                    return executeInNewTransaction(invocation, tx);
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
     */
    private boolean shouldRollback(Throwable e, Transactional tx) {
        // Check noRollbackFor first
        for (Class<? extends Throwable> noRollback : tx.noRollbackFor()) {
            if (noRollback.isInstance(e)) {
                return false;
            }
        }

        // Check rollbackFor
        Class<? extends Throwable>[] rollbackFor = tx.rollbackFor();
        if (rollbackFor.length > 0) {
            for (Class<? extends Throwable> rollback : rollbackFor) {
                if (rollback.isInstance(e)) {
                    return true;
                }
            }
            // rollbackFor specified but exception doesn't match - don't rollback
            return false;
        }

        // Default: rollback for RuntimeException and Error
        return e instanceof RuntimeException || e instanceof Error;
    }
}
