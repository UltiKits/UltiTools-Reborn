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
 *   <li>Self-invocation (calling a @Transactional method from within the same class) bypasses the proxy</li>
 *   <li>Only public methods can be transactional</li>
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
     * Exception types that should trigger a rollback.
     * <p>
     * By default, transactions are rolled back for RuntimeException and Error.
     * Specify additional exception types here if needed.
     *
     * @return exception types to rollback for
     */
    Class<? extends Throwable>[] rollbackFor() default {};

    /**
     * Exception types that should NOT trigger a rollback.
     * <p>
     * Use this to prevent rollback for specific exceptions that would
     * normally cause a rollback.
     *
     * @return exception types to not rollback for
     */
    Class<? extends Throwable>[] noRollbackFor() default {};
}
