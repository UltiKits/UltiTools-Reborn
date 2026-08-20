package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;

@DisplayName("SimpleContainer lifecycle callback de-duplication")
class SimpleContainerLifecycleDedupTest {

    public static class BaseBean {
        public static int initCount;
        public static int destroyCount;

        @PostConstruct
        public void init() { initCount++; }

        @PreDestroy
        public void shutdown() { destroyCount++; }
    }

    /** Repeats the annotations on the override — the shape that fires the callback twice. */
    public static class ChildBean extends BaseBean {
        @Override
        @PostConstruct
        public void init() { super.init(); }

        @Override
        @PreDestroy
        public void shutdown() { super.shutdown(); }
    }

    /**
     * A parent and a child each declaring their own private {@code @PostConstruct} method named
     * {@code init}. Private methods cannot be overridden (invokespecial dispatch, not vtable), so
     * these are two distinct methods that happen to share a name and signature - not one method
     * repeated on two hierarchy levels. Both must fire.
     */
    public static class PrivateInitBase {
        public static int privateInitCount;

        @PostConstruct
        private void init() { privateInitCount++; }
    }

    public static class PrivateInitChild extends PrivateInitBase {
        @PostConstruct
        private void init() { privateInitCount++; }
    }

    @Test
    @DisplayName("Should invoke both private @PostConstruct methods when parent and child each declare one")
    void shouldInvokeBothPrivatePostConstructMethods() {
        PrivateInitBase.privateInitCount = 0;
        SimpleContainer container = new SimpleContainer();
        container.registerBean(PrivateInitChild.class);
        container.refresh();
        container.getBean(PrivateInitChild.class);

        assertEquals(2, PrivateInitBase.privateInitCount,
                "private methods cannot override one another, so the parent's and the child's "
                        + "init() are distinct methods and must both fire, not be merged into one");
    }

    @Test
    @DisplayName("Should invoke @PostConstruct once when an override repeats the annotation")
    void shouldInvokePostConstructOnce() {
        BaseBean.initCount = 0;
        SimpleContainer container = new SimpleContainer();
        container.registerBean(ChildBean.class);
        container.refresh();
        container.getBean(ChildBean.class);

        assertEquals(1, BaseBean.initCount,
                "the callback must fire once per bean, not once per hierarchy level");
    }

    @Test
    @DisplayName("Should invoke @PreDestroy once when an override repeats the annotation")
    void shouldInvokePreDestroyOnce() {
        BaseBean.destroyCount = 0;
        SimpleContainer container = new SimpleContainer();
        container.registerBean(ChildBean.class);
        container.refresh();
        container.getBean(ChildBean.class);
        container.close();

        assertEquals(1, BaseBean.destroyCount);
    }
}
