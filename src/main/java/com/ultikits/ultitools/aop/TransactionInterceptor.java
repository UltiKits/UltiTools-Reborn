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
 * transaction boundaries accordingly. Each propagation is handled as follows:
 * <ul>
 *   <li>{@code REQUIRED}: begins a new transaction when none is active; otherwise joins the
 *       active one as a participant (below).</li>
 *   <li>{@code REQUIRES_NEW}: suspends whatever is active, runs in a new, independent transaction,
 *       and resumes the suspended one afterwards.</li>
 *   <li>{@code SUPPORTS}: joins an active transaction as a participant; without one, the method
 *       runs with no transaction.</li>
 *   <li>{@code MANDATORY}: joins an active transaction as a participant; without one, it throws
 *       {@link IllegalStateException} and the method does not run.</li>
 *   <li>{@code NOT_SUPPORTED}: suspends whatever is active, runs with no transaction, and resumes
 *       the suspended one afterwards.</li>
 *   <li>{@code NEVER}: throws {@link IllegalStateException} when a transaction is active;
 *       otherwise runs with no transaction.</li>
 * </ul>
 * <p>
 * A joined {@code REQUIRED}, {@code SUPPORTS} or {@code MANDATORY} method runs in a participation
 * scope: {@code begin()}, then {@code commit()} when it returns, or {@code rollback()} when it
 * throws an exception its own {@code rollbackFor}/{@code noRollbackFor} rules accept (otherwise
 * {@code commit()}); its exception is rethrown unchanged. This relies on the
 * {@link TransactionManager} nesting contract both built-in managers implement:
 * {@code begin()} on an active transaction joins it, {@code rollback()} at a nested depth marks the
 * transaction rollback-only without ending it, and the outermost {@code commit()} of a marked
 * transaction rolls back and throws
 * {@link com.ultikits.ultitools.exceptions.UnexpectedRollbackException}. So a joined method's
 * failure that its caller catches still rolls the whole transaction back, as Spring does by default
 * ({@code AbstractPlatformTransactionManager}'s {@code globalRollbackOnParticipationFailure = true};
 * the participant's rollback-or-commit choice follows {@code TransactionAspectSupport}'s
 * {@code completeTransactionAfterThrowing}). A joined method's isolation, read-only and timeout
 * settings are not applied; they belong to the transaction that began it.
 * <p>
 * For a new transaction, the commit after a normal return runs after, not inside, the body's
 * failure handler ({@code TransactionAspectSupport}'s {@code invokeWithinTransaction} shape), so the
 * transaction is completed once: a failing commit reaches the caller as it is, and when the
 * method's rules commit despite its exception and that commit fails, the body's exception is kept
 * on the commit failure as suppressed.
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
                return executeAsParticipant(invocation, tx);

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
                // Participates in an active transaction like REQUIRED; without one, runs without.
                if (existingTx) {
                    return executeAsParticipant(invocation, tx);
                }
                return invocation.proceed();

            case MANDATORY:
                if (!existingTx) {
                    throw new IllegalStateException(
                            "No existing transaction found for MANDATORY propagation on method: " +
                                    invocation.getMethod().getName());
                }
                return executeAsParticipant(invocation, tx);

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
     * <p>
     * Only the body runs inside the {@code try} whose handler completes the transaction after a
     * failure; the commit after a normal return runs after that handler, so a failing commit
     * ({@code UnexpectedRollbackException} included) reaches the caller as it is and is never
     * followed by a second completion call. When the body's exception does not call for a rollback
     * and the commit then fails, the commit failure is thrown with the body's exception attached as
     * suppressed.
     */
    // PMD.AvoidCatchingThrowable: an Error raised by the body must complete the transaction too,
    // and a failing commit-despite-exception must not lose the body's exception; both are rethrown
    // or attached, never swallowed.
    // PMD.CompareObjectsWithEquals: deliberate reference identity - addSuppressed rejects the very
    // same instance (self-suppression), and that rejection would replace the commit failure.
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CompareObjectsWithEquals"})
    private Object executeInNewTransaction(MethodInvocation invocation, Transactional tx) throws Throwable {
        String methodName = invocation.getMethod().getDeclaringClass().getSimpleName() + "." +
                invocation.getMethod().getName();

        LOGGER.fine("Starting transaction for: " + methodName);

        transactionManager.begin();

        // Settings run after begin(), because a JDBC setting needs the connection begin() opened,
        // and inside the cleanup scope of the transaction begin() started: a failing setting must
        // not strand it on this thread.
        applySettings(tx, methodName);

        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable e) {
            if (shouldRollback(e, tx)) {
                LOGGER.log(Level.FINE, "Rolling back transaction for: " + methodName + " due to: " + e.getMessage());
                transactionManager.rollback();
            } else {
                LOGGER.fine("Committing transaction despite exception for: " + methodName);
                try {
                    transactionManager.commit();
                } catch (Throwable commitFailure) {
                    if (commitFailure != e) {
                        commitFailure.addSuppressed(e);
                    }
                    throw commitFailure;
                }
            }
            throw e;
        }
        // Outside the handler above (Spring's invokeWithinTransaction shape): a commit that fails
        // has already ended the transaction, so no rollback() may follow it.
        transactionManager.commit();
        LOGGER.fine("Transaction committed for: " + methodName);
        return result;
    }

    /**
     * Executes the invocation as a participant in the transaction already active on this thread.
     * <p>
     * {@code begin()} joins the active transaction (one more nesting level). When the body returns,
     * {@code commit()} leaves that level. When the body throws an exception the method's own rules
     * accept ({@link #shouldRollback}), {@code rollback()} at the nested level marks the whole
     * transaction rollback-only without ending it; any other exception leaves it with {@code
     * commit()}. The body's exception is always rethrown unchanged, with a failure of that
     * completion call attached as suppressed. The method's isolation, read-only and timeout
     * settings are not applied: they belong to the transaction that began it.
     */
    // PMD.AvoidCatchingThrowable: an Error raised by the body must leave the participation level
    // too, and a failure of that completion call must not replace the body's failure; both are
    // rethrown or attached, never swallowed.
    // PMD.CompareObjectsWithEquals: deliberate reference identity - addSuppressed rejects the very
    // same instance (self-suppression), and that rejection would replace the body's failure.
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CompareObjectsWithEquals"})
    private Object executeAsParticipant(MethodInvocation invocation, Transactional tx) throws Throwable {
        String methodName = invocation.getMethod().getDeclaringClass().getSimpleName() + "." +
                invocation.getMethod().getName();

        LOGGER.fine("Joining the active transaction for: " + methodName);

        transactionManager.begin();

        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable e) {
            try {
                if (shouldRollback(e, tx)) {
                    LOGGER.log(Level.FINE, "Marking the joined transaction rollback-only for: " + methodName
                            + " due to: " + e.getMessage());
                    transactionManager.rollback();
                } else {
                    LOGGER.fine("Leaving the joined transaction committable despite exception for: " + methodName);
                    transactionManager.commit();
                }
            } catch (Throwable completionFailure) {
                if (completionFailure != e) {
                    e.addSuppressed(completionFailure);
                }
            }
            throw e;
        }
        transactionManager.commit();
        LOGGER.fine("Left the joined transaction for: " + methodName);
        return result;
    }

    /**
     * Applies the isolation, read-only and timeout settings of {@code tx} to the transaction this
     * invocation has just begun.
     * <p>
     * When applying a setting fails, the transaction this invocation began is rolled back once,
     * so its connection is released and no active context is left on the thread for the next
     * call to join; any failure of that rollback is attached to the settings failure with
     * {@link Throwable#addSuppressed(Throwable)}, and the settings failure itself is rethrown
     * unchanged. The method body never runs. A {@code REQUIRES_NEW} caller still resumes the
     * suspended outer transaction in its own {@code finally}. This mirrors Spring's
     * {@code DataSourceTransactionManager.doBegin}, which applies these settings inside its
     * {@code try} and releases the connection before rethrowing.
     *
     * @param tx         the transaction attributes of the invoked method
     * @param methodName the qualified method name, for logging
     */
    // PMD.AvoidCatchingThrowable: an Error raised by a setting must roll back the transaction
    // this invocation began too, and a rollback failure of any kind must not replace the settings
    // failure; both are rethrown or attached, never swallowed.
    // PMD.CompareObjectsWithEquals: deliberate reference identity - addSuppressed rejects the very
    // same instance (self-suppression), and that rejection would replace the settings failure.
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CompareObjectsWithEquals"})
    private void applySettings(Transactional tx, String methodName) {
        try {
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
        } catch (Throwable settingsFailure) {
            LOGGER.log(Level.FINE, "Rolling back transaction for: " + methodName
                    + " because a transaction setting failed: " + settingsFailure.getMessage());
            try {
                transactionManager.rollback();
            } catch (Throwable rollbackFailure) {
                if (rollbackFailure != settingsFailure) {
                    settingsFailure.addSuppressed(rollbackFailure);
                }
            }
            throw settingsFailure;
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
