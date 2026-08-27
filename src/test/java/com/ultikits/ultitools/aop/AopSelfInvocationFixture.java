package com.ultikits.ultitools.aop;

/**
 * Reusable self-invocation harness for AOP interceptor tests.
 * <p>
 * Subclass this to exercise any AOP annotation for the self-invocation question: does a call a bean
 * makes to its own method, through an <b>un-annotated, never-intercepted</b> caller method, still
 * reach the annotation this bean carries? Concrete subclasses implement {@link #annotated()} and
 * put the annotation under test directly on their own override - annotations are not inherited
 * across an override, so the annotation must live on the subclass's own method, not here.
 * <p>
 * {@link #viaSelfInvocation()} is deliberately left un-annotated on this base class, so a generated
 * proxy never overrides it: its bytecode is inherited unmodified on the proxy instance, and its
 * call to {@code this.annotated()} is a plain {@code invokevirtual} that resolves against whatever
 * class actually implements {@code annotated()} at runtime - see {@link ProxyFactory}'s class
 * javadoc ("the proxy is the bean: there is no second object") and
 * {@link ReflectiveMethodInvocation}'s constructor javadoc (reflecting on the target instead of
 * calling {@code super} "would re-enter the proxy through virtual dispatch"). Both are why this
 * codebase's self-invocation behaviour cannot be assumed from JDK-dynamic-proxy or delegating-CGLIB
 * folklore and must be measured per {@link ExceptionHandlerRethrowTest}'s Test 6 and reused by
 * later AOP phases (WIRE-13, WIRE-14, WIRE-15 in Phase 2) rather than re-derived per test class.
 * <p>
 * 可复用的自调用 AOP 测试夹具：子类实现 {@link #annotated()} 并在其自身的方法覆盖上标注被测注解。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public abstract class AopSelfInvocationFixture {

    /**
     * The method under test. Concrete subclasses override this and annotate their override with
     * the AOP annotation being exercised.
     *
     * @return whatever the concrete case needs to assert on
     * @throws Throwable whatever the concrete body (or its interceptor chain) throws
     */
    public abstract Object annotated() throws Throwable;

    /**
     * Calls {@link #annotated()} via self-invocation ({@code this.annotated()}) from a method that
     * itself carries no AOP annotation and is therefore never overridden by the proxy factory.
     *
     * @return whatever {@link #annotated()} returns
     * @throws Throwable whatever {@link #annotated()} throws
     */
    public final Object viaSelfInvocation() throws Throwable {
        return annotated();
    }
}
