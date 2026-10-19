package com.ultikits.ultitools.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.testfixtures.beanalias.BeanAliasFixtures;
import com.ultikits.testfixtures.beanalias.BeanAliasFixtures.AliasedLifecycleBean;
import com.ultikits.testfixtures.crosspackagededup.pkga.PkgPrivateInitBase;
import com.ultikits.testfixtures.crosspackagededup.pkgb.PkgPrivateInitChild;
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

    /**
     * A package-private {@code @PostConstruct} method widened to {@code public} by a subclass in
     * the <b>same</b> package - both classes are nested in this test, so both live in
     * {@code com.ultikits.ultitools.context}. Per JLS 8.4.8.1 that is a genuine override (nothing
     * there constrains the overriding method's own access), so the callback must fire exactly once.
     * <p>
     * 同包内把包私有的 {@code @PostConstruct} 方法放宽为 {@code public}。根据 JLS 8.4.8.1 这是真正的
     * 覆盖，回调必须只触发一次。
     */
    public static class PkgPrivateWideningBase {
        public static int wideningInitCount;

        @PostConstruct
        void init() { wideningInitCount++; }
    }

    public static class PkgPrivateWideningChild extends PkgPrivateWideningBase {
        @Override
        @PostConstruct
        public void init() { super.init(); }
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
    @DisplayName("Should invoke both package-private @PostConstruct methods when parent and child "
            + "are in different packages")
    void shouldInvokeBothPackagePrivatePostConstructMethodsAcrossPackages() {
        // See com.ultikits.testfixtures.crosspackagededup.pkga.PkgPrivateInitBase's javadoc: per
        // JLS 8.4.8.1 a package-private method is overridden only by a subclass in the SAME
        // package. PkgPrivateInitChild lives in a different package than PkgPrivateInitBase, so
        // their same-signature package-private init() methods are distinct - both must fire, not
        // collapse into one the way a same-package override correctly does.
        PkgPrivateInitBase.initCount = 0;
        SimpleContainer container = new SimpleContainer();
        container.registerBean(PkgPrivateInitChild.class);
        container.refresh();
        container.getBean(PkgPrivateInitChild.class);

        assertEquals(2, PkgPrivateInitBase.initCount,
                "a package-private method is overridden only by a subclass in the SAME package "
                        + "(JLS 8.4.8.1); the parent's and the child's init() live in different "
                        + "packages, so they are distinct methods and must both fire");
    }

    @Test
    @DisplayName("Should invoke @PostConstruct once when a same-package subclass widens a "
            + "package-private callback to public")
    void shouldInvokePostConstructOnceForSamePackageWidening() {
        // The manifestation-1 shape of issue #190: the parent's callback is package-private and the
        // child's override is public, so a symmetric name-based de-dup key gives them different
        // keys and BOTH methods are invoked on the same bean. Per JLS 8.4.8.1 the child's
        // declaration overrides the parent's - one method, one invocation.
        PkgPrivateWideningBase.wideningInitCount = 0;
        SimpleContainer container = new SimpleContainer();
        container.registerBean(PkgPrivateWideningChild.class);
        container.refresh();
        container.getBean(PkgPrivateWideningChild.class);

        assertEquals(1, PkgPrivateWideningBase.wideningInitCount,
                "widening a package-private method to public in the same package is still an "
                        + "override (JLS 8.4.8.1), so the callback must fire exactly once");
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

    /**
     * C50: a {@code @Bean(name = {primary, alias})} product is one object bound under two names.
     * Both names must keep resolving to it, but every per-instance consumer -- the singleton view
     * {@code PluginManager} iterates for scheduling, player caches and module event handlers, and
     * {@code close()}'s {@code @PreDestroy} pass -- must see it once.
     */
    @Nested
    @DisplayName("C50: an aliased @Bean is one instance to every per-instance consumer")
    class AliasIdentityTests {

        @BeforeEach
        void resetCounters() {
            AliasedLifecycleBean.resetCounters();
        }

        private SimpleContainer scanAliasFixtures() {
            SimpleContainer container = new SimpleContainer();
            container.setClassLoader(SimpleContainerLifecycleDedupTest.class.getClassLoader());
            new ComponentScanner(container).scanPackage("com.ultikits.testfixtures.beanalias");
            return container;
        }

        private int countByIdentity(Collection<Object> values, Object target) {
            int count = 0;
            for (Object value : values) {
                if (value == target) {
                    count++;
                }
            }
            return count;
        }

        @Test
        @DisplayName("both names resolve to the one instance, which getSingletonValues holds once")
        void aliasedBeanAppearsOnceInSingletonValues() {
            SimpleContainer container = scanAliasFixtures();

            Object primary = container.getBean(BeanAliasFixtures.PRIMARY_NAME);
            Object alias = container.getBean(BeanAliasFixtures.ALIAS_NAME);

            assertSame(primary, alias, "both declared names must keep resolving to one instance");
            assertEquals(1, countByIdentity(container.getSingletonValues(), primary),
                    "an alias is another name for the same object, so the per-instance view must "
                            + "hold that object once, not once per name");
        }

        @Test
        @DisplayName("close() runs the aliased bean's @PreDestroy once")
        void closeRunsPreDestroyOnceForAliasedBean() {
            SimpleContainer container = scanAliasFixtures();

            container.close();

            assertEquals(1, AliasedLifecycleBean.preDestroyCount,
                    "@PreDestroy must run once per object, however many names bind it");
        }

        @Test
        @DisplayName("two distinct instances that are equal() are both kept (identity, not equality)")
        void distinctEqualInstancesUnderTwoNamesAreBothKept() {
            SimpleContainer container = new SimpleContainer();
            AlwaysEqual first = new AlwaysEqual();
            AlwaysEqual second = new AlwaysEqual();
            container.registerSingleton("first", first);
            container.registerSingleton("second", second);

            Collection<Object> values = container.getSingletonValues();

            assertEquals(1, countByIdentity(values, first));
            assertEquals(1, countByIdentity(values, second),
                    "two distinct objects are two instances even when equals() says otherwise");
        }
    }

    /** Equal to every other instance of itself, so only reference identity tells two apart. */
    public static class AlwaysEqual {
        @Override
        public boolean equals(Object other) {
            return other instanceof AlwaysEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
