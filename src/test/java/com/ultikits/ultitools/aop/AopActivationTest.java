package com.ultikits.ultitools.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.events.ModuleEvent;
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

    private static SimpleContainer wiredContainer() {
        SimpleContainer context = new SimpleContainer();
        invokeWireAop(context);
        context.registerBean(Collaborator.class);
        context.registerBean(KitchenSink.class);
        context.refresh();
        return context;
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
}
