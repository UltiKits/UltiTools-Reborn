package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.Data;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.ModuleEventHandler;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.ModuleEvent;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.manager.PlayerCacheManager;
import com.ultikits.ultitools.manager.PluginManager;

/**
 * Guards against the failure this issue exists to fix: a complete, well-tested aop/ package that
 * was never registered with the container, leaving @ExceptionCatch an empty annotation for three
 * releases. Every assertion here is about the seam between the container and AOP, not about the
 * aop/ package in isolation.
 * <p>
 * This is deliberately separate from {@code PluginManagerTest} and
 * {@code ExternalPluginIntegrationTest}, which assert that the real {@code register(plugin)} /
 * {@code registerExternal(adapter)} entry points call {@code wireAop(...)} at all (a non-null
 * {@code getAopProxyResolver()}). This file assumes wiring happened - it calls
 * {@link PluginManager#wireAop(SimpleContainer)} directly to isolate proxy behaviour from plugin
 * bootstrap - and instead asserts that proxying does not break anything else on the resulting
 * bean: interception actually runs, injection lands on the right instance, and every scanner that
 * reads annotations off {@code getMethods()}/{@code getClass()} still finds them.
 */
@DisplayName("AOP activation assertions")
class AopActivationTest {

    public static class SampleEvent extends ModuleEvent { }

    @Service
    @EventListener
    public static class KitchenSink implements Listener {

        @Autowired
        private Collaborator collaborator;

        @PlayerCache
        public final Map<UUID, String> cache = new HashMap<>();

        public boolean moduleHandlerCalled = false;

        @ExceptionCatch(silent = true)
        public String guarded() { throw new IllegalStateException("boom"); }

        @ExceptionCatch(silent = true)
        public String guardedSelfCall() { return inner(); }

        // defaultValue makes the outcome distinguishable from guardedSelfCall()'s own default
        // (null): if self-invocation actually routes through the proxy, inner()'s own
        // interceptor returns "intercepted" and guardedSelfCall() passes that straight back. If
        // self-invocation instead bypasses the proxy (the delegating-proxy failure mode this test
        // exists to catch), inner() throws uncaught into guardedSelfCall()'s body, and it is
        // guardedSelfCall()'s own @ExceptionCatch(silent = true) - with no defaultValue - that
        // catches it and returns null instead. Two default values that differ is what makes the
        // assertion below discriminate; two methods that both defaulted to null would not.
        @ExceptionCatch(silent = true, defaultValue = "intercepted")
        public String inner() { throw new IllegalStateException("inner-boom"); }

        public String collaborate() { return collaborator.name(); }

        // The three methods below carry BOTH an AOP annotation and a scanned annotation.
        // That combination is the only one where annotation copying is load-bearing: a method
        // without an AOP annotation is never overridden, so its annotations are trivially still
        // visible on getMethods() and would assert nothing.
        @ExceptionCatch(silent = true)
        @EventHandler
        public void onQuit(PlayerQuitEvent event) { }

        @ExceptionCatch(silent = true)
        @ModuleEventHandler
        public void onModuleEvent(SampleEvent event) { moduleHandlerCalled = true; }

        @ExceptionCatch(silent = true)
        @Scheduled(delay = 20L, period = 20L)
        public void tick() { }
    }

    @Service
    public static class Collaborator {
        public String name() { return "collaborator"; }
    }

    /**
     * A class-level annotation is a default for the methods this class declares. The default
     * differs from every other fixture in this file (null and "intercepted") so the assertions
     * below can tell which annotation fired.
     */
    @Service
    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class ClassLevelOwnMethods {
        public String first() { throw new IllegalStateException("first-boom"); }
        public String second() { throw new IllegalStateException("second-boom"); }
        private String privateHelper() { return "private"; }
        public static String staticHelper() { return "static"; }
        public final String finalHelper() { return "final"; }
    }

    /** The annotation is on the ancestor, so its coverage reaches down into the subclass bean. */
    @ExceptionCatch(silent = true, defaultValue = "from-annotated-base")
    public static class AnnotatedAncestor {
        public String inheritedRisky() { throw new IllegalStateException("base-boom"); }
    }

    @Service
    public static class InheritsAnnotatedAncestor extends AnnotatedAncestor { }

    /** The ancestor carries nothing, and the subclass's annotation must not reach up to it. */
    public static class PlainAncestor {
        public String ancestorRisky() { throw new IllegalStateException("ancestor-boom"); }
    }

    @Service
    @ExceptionCatch(silent = true, defaultValue = "class-level")
    public static class AnnotatedSubclass extends PlainAncestor {
        public String ownRisky() { throw new IllegalStateException("own-boom"); }
    }

    /** Lombok generates equals/hashCode/canEqual/toString onto the annotated class itself. */
    @Service
    @Data
    @ExceptionCatch(silent = true, defaultValue = "over-lombok")
    public static class ClassLevelWithLombok {
        private String label;
        public String risky() { throw new IllegalStateException("risky-boom"); }
    }

    public static class InheritedMethodLevelBase {
        @ExceptionCatch(silent = true, defaultValue = "from-base")
        public String guardedOnBase() { throw new IllegalStateException("base-boom"); }
    }

    @Service
    public static class InheritsMethodLevel extends InheritedMethodLevelBase { }

    public static class TransactionalBase {
        @Transactional
        public void transactionalOnBase() { }
    }

    @Service
    public static class InheritsTransactionalBean extends TransactionalBase { }

    private static SimpleContainer wiredContainer() {
        SimpleContainer context = new SimpleContainer();
        invokeWireAop(context);
        context.registerBean(Collaborator.class);
        context.registerBean(KitchenSink.class);
        context.refresh();
        return context;
    }

    private static SimpleContainer wiredContainer(Class<?>... beans) {
        SimpleContainer context = new SimpleContainer();
        invokeWireAop(context);
        for (Class<?> bean : beans) {
            context.registerBean(bean);
        }
        context.refresh();
        return context;
    }

    private static boolean declares(Class<?> clazz, String name, Class<?>... params) {
        try {
            clazz.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * {@link PluginManager#wireAop(SimpleContainer)} is package-private in
     * {@code com.ultikits.ultitools.manager} - a different package than this test - by deliberate
     * choice of the task that introduced it. Reflection reaches the exact production method
     * instead of duplicating its logic here, so this test still exercises the real wiring code and
     * still goes red under the negative control in the task brief (commenting out
     * {@code context.setAopProxyResolver(resolver)} inside {@code wireAop}), rather than a
     * stand-in that would pass regardless of whether wireAop itself is broken.
     */
    private static void invokeWireAop(SimpleContainer context) {
        try {
            Method wireAop = PluginManager.class.getDeclaredMethod("wireAop", SimpleContainer.class);
            wireAop.setAccessible(true);
            wireAop.invoke(null, context);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke PluginManager.wireAop via reflection", e);
        }
    }

    @Test
    @DisplayName("A bean requesting interception must come out of the container proxied")
    void shouldBeProxied() {
        KitchenSink bean = wiredContainer().getBean(KitchenSink.class);
        assertTrue(ProxyFactory.isProxyClass(bean.getClass()),
                "if this fails, AOP is not wired and every annotation below is inert");
    }

    @Test
    @DisplayName("The interception must actually happen, not merely be configured")
    void shouldActuallyIntercept() {
        assertEquals(null, wiredContainer().getBean(KitchenSink.class).guarded());
    }

    @Test
    @DisplayName("Self-invocation must be intercepted")
    void shouldInterceptSelfInvocation() {
        assertEquals("intercepted", wiredContainer().getBean(KitchenSink.class).guardedSelfCall(),
                "inner() is reached through this.inner(); a delegating proxy would miss it and "
                        + "the outer @ExceptionCatch would return its own default (null) instead");
    }

    @Test
    @DisplayName("@Autowired must be injected into the instance the caller receives")
    void shouldInjectIntoTheReturnedInstance() {
        assertEquals("collaborator", wiredContainer().getBean(KitchenSink.class).collaborate());
    }

    @Test
    @DisplayName("Type-level annotations must survive proxying")
    void shouldKeepTypeAnnotations() {
        Class<?> beanClass = wiredContainer().getBean(KitchenSink.class).getClass();
        assertNotNull(beanClass.getAnnotation(Service.class),
                "@Service is not @Inherited; ComponentScanner itself is shielded because it "
                        + "scans the class before any proxy exists, but this still guards the "
                        + "annotateType contract that @ContextEntry and @CmdExecutor/@CmdTarget "
                        + "rely on when they read getClass() directly with no such fallback");
        assertNotNull(beanClass.getAnnotation(EventListener.class),
                "@EventListener is not @Inherited; ListenerManager itself is shielded because "
                        + "AnnotationUtils.findAnnotation walks the superclass chain on its own, "
                        + "but this still guards the same annotateType contract");
    }

    @Test
    @DisplayName("Bukkit's @EventHandler scan must still find the handler")
    void shouldKeepBukkitEventHandler() throws Exception {
        Class<?> beanClass = wiredContainer().getBean(KitchenSink.class).getClass();
        Method onQuit = beanClass.getMethod("onQuit", PlayerQuitEvent.class);
        assertTrue(ProxyFactory.isProxyClass(onQuit.getDeclaringClass()),
                "precondition: onQuit must actually be overridden, or this test asserts nothing");
        assertNotNull(onQuit.getAnnotation(EventHandler.class),
                "Bukkit scans getMethods() for @EventHandler; overriding methods do not inherit it");
    }

    @Test
    @DisplayName("@ModuleEventHandler scan must still find the handler")
    void shouldKeepModuleEventHandler() throws Exception {
        Class<?> beanClass = wiredContainer().getBean(KitchenSink.class).getClass();
        Method handler = beanClass.getMethod("onModuleEvent", SampleEvent.class);
        assertTrue(ProxyFactory.isProxyClass(handler.getDeclaringClass()),
                "precondition: onModuleEvent must actually be overridden");
        assertNotNull(handler.getAnnotation(ModuleEventHandler.class),
                "PluginManager.registerModuleEventHandlers reads it off getMethods()");
    }

    @Test
    @DisplayName("@Scheduled scan must still find the method")
    void shouldKeepScheduled() throws Exception {
        Class<?> beanClass = wiredContainer().getBean(KitchenSink.class).getClass();
        Method tick = beanClass.getMethod("tick");
        assertTrue(ProxyFactory.isProxyClass(tick.getDeclaringClass()),
                "precondition: tick must actually be overridden");
        assertNotNull(tick.getAnnotation(Scheduled.class),
                "no current production consumer actually reads this specific copy - "
                        + "TaskManager.getTargetClass unwraps back to the original class first - "
                        + "but this still guards the general method-annotation-copying contract "
                        + "that @EventHandler and @ModuleEventHandler above depend on directly");
    }

    @Test
    @DisplayName("@PlayerCache cleanup must act on the instance the container handed out")
    void shouldCleanTheRightMap() {
        KitchenSink bean = wiredContainer().getBean(KitchenSink.class);
        UUID player = UUID.randomUUID();
        bean.cache.put(player, "value");

        PlayerCacheManager manager = new PlayerCacheManager();
        manager.registerBean(bean);
        manager.onPlayerQuit(player);

        assertTrue(bean.cache.isEmpty(),
                "@PlayerCache is declared on KitchenSink, not on the proxy subclass; if "
                        + "PlayerCacheManager only scanned bean.getClass().getDeclaredFields() it "
                        + "would never find the field on this proxied instance and this map would "
                        + "stay populated");
    }

    @Test
    @DisplayName("The container must return the same singleton instance on every lookup")
    void shouldReturnSameInstanceOnEveryLookup() {
        SimpleContainer context = wiredContainer();
        assertSame(context.getBean(KitchenSink.class), context.getBean(KitchenSink.class));
    }
    @Test
    @DisplayName("A class-level @ExceptionCatch must actually intercept, returning its own default")
    void shouldInterceptClassLevel() {
        assertEquals("class-level",
                wiredContainer(ClassLevelOwnMethods.class)
                        .getBean(ClassLevelOwnMethods.class).first(),
                "assert the returned value, not merely that nothing escaped: 'class-level' is "
                        + "produced by no other path in this file");
    }

    @Test
    @DisplayName("A class-level annotation must cover every method, not one by accident")
    void shouldInterceptEveryMethodUnderClassLevel() {
        assertEquals("class-level",
                wiredContainer(ClassLevelOwnMethods.class)
                        .getBean(ClassLevelOwnMethods.class).second());
    }

    @Test
    @DisplayName("The class-level annotation must survive onto the generated proxy type")
    void shouldKeepClassLevelAnnotationOnProxy() {
        Class<?> proxyClass =
                wiredContainer(ClassLevelOwnMethods.class)
                        .getBean(ClassLevelOwnMethods.class).getClass();
        assertTrue(proxyClass.isAnnotationPresent(ExceptionCatch.class),
                "type annotations are not @Inherited, so this depends on ProxyFactory's "
                        + "annotateType copy; a regression there breaks every class-level scan "
                        + "silently");
    }

    // The scope rule, stated positively. A class-level annotation is a default for the class that
    // declares it and for its subclasses - it does not reach up into ancestors. Without this
    // assertion nothing stops the coverage from creeping back over every framework base class a
    // module bean happens to extend, where a swallowed exception becomes a null that resurfaces
    // as an unrelated NPE. Matches Spring's documented rule for a class-level @Transactional.
    @Test
    @DisplayName("A class-level annotation must not reach up into ancestor-declared methods")
    void shouldNotCoverAncestorDeclaredMethods() {
        AnnotatedSubclass bean =
                wiredContainer(AnnotatedSubclass.class).getBean(AnnotatedSubclass.class);

        assertEquals("class-level", bean.ownRisky(),
                "the class's own method is covered");
        assertThrows(IllegalStateException.class, bean::ancestorRisky,
                "the ancestor declared it, so the subclass's annotation must not swallow it");
    }

    // The other direction of the same rule: coverage extends down from the annotated class.
    @Test
    @DisplayName("A class-level annotation must reach down into subclasses")
    void shouldCoverSubclassesOfTheAnnotatedClass() {
        assertEquals("from-annotated-base",
                wiredContainer(InheritsAnnotatedAncestor.class)
                        .getBean(InheritsAnnotatedAncestor.class).inheritedRisky(),
                "the annotation is on AnnotatedAncestor, which declares the method");
    }

    @Test
    @DisplayName("Unproxyable methods must be skipped, not fail the container")
    void shouldSkipUnproxyableWithoutFailing() {
        Class<?> proxyClass =
                wiredContainer(ClassLevelOwnMethods.class)
                        .getBean(ClassLevelOwnMethods.class).getClass();
        assertFalse(declares(proxyClass, "privateHelper"));
        assertFalse(declares(proxyClass, "staticHelper"));
        assertFalse(declares(proxyClass, "finalHelper"));
    }

    @Test
    @DisplayName("A class-level annotation must not cover equals, hashCode or canEqual")
    void shouldExcludeSilentWrongAnswerSignatures() {
        Class<?> proxyClass =
                wiredContainer(ClassLevelWithLombok.class)
                        .getBean(ClassLevelWithLombok.class).getClass();
        assertFalse(declares(proxyClass, "equals", Object.class));
        assertFalse(declares(proxyClass, "hashCode"));
        assertFalse(declares(proxyClass, "canEqual", Object.class));

        // The other half: without it, an implementation that excluded every Lombok-generated
        // method - or every method at all - would pass the three assertions above. Lombok emits
        // all four onto this class itself, so the scope rule is not what keeps them out.
        assertTrue(declares(proxyClass, "toString"),
                "toString is deliberately not on the exclusion list");
        assertEquals("over-lombok",
                wiredContainer(ClassLevelWithLombok.class)
                        .getBean(ClassLevelWithLombok.class).risky());
    }

    @Test
    @DisplayName("A method-level annotation declared on a superclass must be intercepted")
    void shouldInterceptInheritedMethodLevel() {
        assertEquals("from-base",
                wiredContainer(InheritsMethodLevel.class)
                        .getBean(InheritsMethodLevel.class).guardedOnBase(),
                "neither @ExceptionCatch nor @Transactional is @Inherited, so this only works "
                        + "if the scan walks the hierarchy itself");
    }

    @Test
    @DisplayName("@Transactional on a superclass method must trigger the load-time refusal")
    void shouldRefuseInheritedTransactional() {
        SimpleContainer context = new SimpleContainer();
        invokeWireAop(context);
        context.registerBean(InheritsTransactionalBean.class);

        // SimpleContainer.createBean catches Exception and rewraps it, and ContainerException is
        // itself a RuntimeException, so the refusal arrives wrapped and its text is no longer the
        // top-level message. The chain is searched rather than the assertion being loosened to
        // RuntimeException, which any unrelated container failure would also satisfy.
        RuntimeException thrown = assertThrows(RuntimeException.class, context::refresh);
        ContainerException refusal = null;
        Throwable cursor = thrown;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof ContainerException) {
                refusal = (ContainerException) cursor;
                break;
            }
            cursor = cursor.getCause();
        }

        assertNotNull(refusal, "the cause chain must contain the refusal, but was: " + thrown);
        assertTrue(refusal.getMessage().contains("Transactional"),
                "the refusal must name the annotation: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("transactionalOnBase"),
                "the refusal must name the inherited method, not just the bean: "
                        + refusal.getMessage());
    }
}
