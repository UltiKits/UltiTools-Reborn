package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopAdvisor;
import com.ultikits.ultitools.aop.AopProxyResolver;
import com.ultikits.ultitools.aop.MethodInterceptor;
import com.ultikits.ultitools.aop.ProxyFactory;

@DisplayName("SimpleContainer AOP integration")
class SimpleContainerAopTest {

    static final List<String> LOG = new ArrayList<>();

    public static class Dependency {
        public String name() { return "dep"; }
    }

    public static class Managed {
        @Autowired
        private Dependency dependency;

        public boolean postConstructRan = false;

        @PostConstruct
        public void init() { postConstructRan = true; }

        @Transactional
        public String work() { return "work:" + dependency.name() + ":" + helper(); }

        @Transactional
        public String helper() { return "helper"; }
    }

    public static class Plain {
        public String work() { return "plain"; }
    }

    private static SimpleContainer containerWithAop() {
        LOG.clear();
        SimpleContainer container = new SimpleContainer();
        AopProxyResolver resolver = new AopProxyResolver();
        MethodInterceptor recorder = inv -> {
            LOG.add(inv.getMethod().getName());
            return inv.proceed();
        };
        resolver.addAdvisor(AopAdvisor.forAnnotation(Transactional.class, recorder, 100));
        container.setAopProxyResolver(resolver);
        return container;
    }

    @Test
    @DisplayName("Should hand out a proxy instance for a bean requesting interception")
    void shouldProxyManagedBean() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        Managed bean = container.getBean(Managed.class);

        assertNotNull(bean);
        assertTrue(ProxyFactory.isProxyClass(bean.getClass()),
                "getBean(OriginalType) must return the proxy instance");
    }

    @Test
    @DisplayName("Should leave beans without AOP annotations unproxied")
    void shouldNotProxyPlainBean() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Plain.class);
        container.refresh();

        Plain bean = container.getBean(Plain.class);

        assertSame(Plain.class, bean.getClass());
    }

    @Test
    @DisplayName("Should inject @Autowired fields into the proxy instance")
    void shouldAutowireIntoProxy() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        Managed bean = container.getBean(Managed.class);

        assertEquals("work:dep:helper", bean.work(),
                "the injected dependency must be visible from the intercepted method body");
    }

    @Test
    @DisplayName("Should run @PostConstruct on the proxy instance")
    void shouldRunPostConstructOnProxy() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        assertTrue(container.getBean(Managed.class).postConstructRan);
    }

    @Test
    @DisplayName("Should intercept self-invocation on a container-managed bean")
    void shouldInterceptSelfInvocation() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        container.getBean(Managed.class).work();

        assertEquals(Arrays.asList("work", "helper"), LOG);
    }

    @Test
    @DisplayName("Should return the same singleton instance on repeated lookups")
    void shouldReturnSameSingleton() {
        SimpleContainer container = containerWithAop();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        assertSame(container.getBean(Managed.class), container.getBean(Managed.class));
    }

    @Test
    @DisplayName("Should behave as before when no resolver is set")
    void shouldBeInertWithoutResolver() {
        SimpleContainer container = new SimpleContainer();
        container.registerBean(Dependency.class);
        container.registerBean(Managed.class);
        container.refresh();

        assertSame(Managed.class, container.getBean(Managed.class).getClass());
    }
}
