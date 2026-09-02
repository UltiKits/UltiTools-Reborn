package com.ultikits.ultitools.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as non-extendable, or a method as non-overridable, without using the {@code final}
 * keyword.
 * <p>
 * AOP is implemented by generating a subclass whose intercepted methods reach the original body
 * through {@code super}. A {@code final} class cannot be subclassed and a {@code final} method
 * cannot be overridden, so the keyword and AOP are mutually exclusive. This annotation expresses
 * the same intent while leaving the class proxyable.
 * <p>
 * The contract is enforced when the framework scans a module's classes: a class extending a
 * {@code @Final} type, or overriding a {@code @Final} method, is rejected at load time. Enforcement
 * therefore covers every class loaded through the framework, across module boundaries. It does
 * <b>not</b> happen at compile time, so an IDE will not flag a violation.
 * <p>
 * Usage example:
 * <pre>{@code
 * @Final
 * @Service
 * public class PaymentService {
 *
 *     @Transactional
 *     public void transfer(UUID from, UUID to, double amount) { }
 * }
 * }</pre>
 *
 * @author wisdomme
 * @since 6.3.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Final {
}
