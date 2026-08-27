package com.ultikits.ultitools.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods or classes for declarative transaction management.
 * <p>
 * When a method annotated with @Transactional is executed, it will automatically
 * be wrapped in a database transaction. The transaction will be committed if the
 * method completes normally, or rolled back if an exception is thrown.
 * <p>
 * Usage examples:
 * <pre>{@code
 * // Basic usage - starts a new transaction or joins existing one
 * @Transactional
 * public void saveUser(User user) {
 *     userRepository.save(user);
 *     auditRepository.log("User saved: " + user.getName());
 * }
 *
 * // Always create a new transaction
 * @Transactional(propagation = Propagation.REQUIRES_NEW)
 * public void logAudit(String message) {
 *     auditRepository.log(message);
 * }
 *
 * // Read-only transaction (may allow optimizations)
 * @Transactional(readOnly = true)
 * public List<User> findAllUsers() {
 *     return userRepository.findAll();
 * }
 *
 * // Custom rollback rules
 * @Transactional(rollbackFor = {BusinessException.class})
 * public void processOrder(Order order) throws BusinessException {
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>Important:</b>
 * <ul>
 *   <li>Self-invocation (calling a {@code @Transactional} method on {@code this} from within the
 *       same class) <b>is</b> intercepted: the framework's generated proxy is a subclass of the
 *       bean itself, not a delegate wrapping a separate target, so a call to
 *       {@code this.method()} dispatches virtually onto the proxy's override. Re-verified against
 *       three pre-existing {@code shouldInterceptSelfInvocation} test classes (2026-08-27)</li>
 *   <li>Private, static, and final methods cannot be transactional - each is dispatched in a way
 *       that bypasses the proxy (respectively: {@code invokespecial}, {@code invokestatic}, and
 *       no override is possible); a package-private method is also ineligible when it is declared
 *       in a different package than the bean class</li>
 *   <li>The class must not be final (subclass proxy limitation)</li>
 * </ul>
 *
 * @author wisdomme
 * @since 6.2.0
 * @see Propagation
 * @see Isolation
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Transactional {

    /**
     * The transaction propagation behavior.
     * <p>
     * Defaults to {@link Propagation#REQUIRED}, which joins an existing
     * transaction or creates a new one if none exists.
     *
     * @return the propagation behavior
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * The transaction isolation level.
     * <p>
     * Defaults to {@link Isolation#DEFAULT}, which uses the database's
     * default isolation level.
     *
     * @return the isolation level
     */
    Isolation isolation() default Isolation.DEFAULT;

    /**
     * The transaction timeout in seconds.
     * <p>
     * Defaults to -1 (no timeout / use database default).
     *
     * @return the timeout in seconds
     */
    int timeout() default -1;

    /**
     * Whether the transaction is read-only.
     * <p>
     * A read-only transaction may allow database optimizations and is
     * useful for methods that only query data.
     *
     * @return true if read-only
     */
    boolean readOnly() default false;

    /**
     * Exception types that should trigger a rollback, in addition to the {@code RuntimeException}/
     * {@code Error} default.
     * <p>
     * This is additive, not a replacement: an exception that matches neither {@code rollbackFor}
     * nor {@code noRollbackFor} still falls through to the unchecked default, exactly as if
     * neither attribute were set. When an exception matches both this and {@link #noRollbackFor()},
     * the rule whose listed class is the <b>shallower</b> inheritance-depth match to the thrown
     * exception wins (Spring's {@code RuleBasedTransactionAttribute} tiebreak); on an exact-depth
     * tie - including the same class appearing in both arrays - the transaction rolls back.
     *
     * @return exception types to additionally rollback for
     * @since 6.3.0 additive combination with {@link #noRollbackFor()} and the depth tiebreak
     *        (D-06, D-07); before 6.3.0 a non-empty, unmatched {@code rollbackFor} silently
     *        committed instead of falling through to the default.
     */
    Class<? extends Throwable>[] rollbackFor() default {};

    /**
     * Exception types that should NOT trigger a rollback, overriding the {@code RuntimeException}/
     * {@code Error} default for those types.
     * <p>
     * Combines with {@link #rollbackFor()} by shallowest-inheritance-depth match, described there;
     * on an exact-depth tie the transaction still rolls back.
     *
     * @return exception types to not rollback for
     */
    Class<? extends Throwable>[] noRollbackFor() default {};
}
