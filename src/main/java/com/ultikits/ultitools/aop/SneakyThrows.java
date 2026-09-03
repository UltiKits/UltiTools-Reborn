package com.ultikits.ultitools.aop;

/**
 * The single checked-exception-safe rethrow helper for the {@code aop} package.
 * <p>
 * Java's checked-exception check is purely a {@code javac} constraint - the JVM's bytecode
 * verifier has no concept of checked versus unchecked, and an {@code athrow} instruction can
 * propagate any {@link Throwable} through any frame regardless of that frame's declared
 * {@code throws} clause. {@link #sneakyThrow(Throwable)} exploits exactly that gap: the generic
 * bound {@code <E extends Throwable>} lets the compiler infer {@code E} as an unchecked
 * {@link RuntimeException} at the call site (satisfying the throws-clause check there) while the
 * erased bytecode throws the original, unmodified {@link Throwable} - checked or not.
 * <p>
 * This is the trade: the compiler's checked-exception analysis is defeated on purpose so a caught
 * {@link Throwable} can cross a generated proxy boundary as its original type instead of being
 * wrapped or replaced. It exists once, here, for two reverse-direction call sites that both need
 * it:
 * <ul>
 *   <li>{@link ProxyFactory}'s {@code InterceptorDispatcher.invokeTrampoline} - unwraps
 *       {@link java.lang.reflect.InvocationTargetException} so a checked exception the trampoline
 *       body throws reaches the interceptor chain as its original type, matching what a direct
 *       {@code super.method()} call would have thrown</li>
 *   <li>{@link ExceptionInterceptor}'s custom-handler branch - propagates whatever a custom
 *       {@link ExceptionHandler#handleException} deliberately re-throws, including a checked
 *       {@link Throwable} the intercepted method's own {@code throws} clause does not declare</li>
 * </ul>
 * Extracted from a private copy inside {@link ProxyFactory}'s {@code InterceptorDispatcher} so both
 * call sites share one implementation - a second, independently-maintained copy is the exact class
 * of defect this milestone exists to close (one rule, implemented twice, silently diverging).
 *
 * @author wisdomme
 * @since 6.3.0
 */
final class SneakyThrows {

    private SneakyThrows() {
    }

    /**
     * Rethrows {@code t} unmodified, bypassing the compiler's checked-exception check.
     * <p>
     * Callers write {@code throw SneakyThrows.sneakyThrow(t);} so a method whose own
     * {@code throws} clause does not (and, for an override, cannot) declare {@code t}'s checked
     * type still compiles - the declared {@link RuntimeException} return type never actually
     * returns, since the generic-cast trick always throws.
     *
     * @param t   the throwable to rethrow, exactly as given
     * @param <E> inferred as an unchecked type at the call site so {@code javac} permits the throw
     * @return never returns; declared as {@link RuntimeException} only to satisfy the compiler at
     *         call sites that want to write {@code throw sneakyThrow(t);} as a single statement
     * @throws E always - the actual runtime type is {@code t}'s own class, checked or not
     */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
