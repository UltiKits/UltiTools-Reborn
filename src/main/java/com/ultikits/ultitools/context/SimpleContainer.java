package com.ultikits.ultitools.context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.ComponentScan;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.PreDestroy;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Transactional;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.exceptions.ErrorCode;
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.jetbrains.annotations.ApiStatus;

/**
 * Simple dependency injection container to replace Spring ApplicationContext.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // IoC container invokes constructors and lifecycle methods -- see 08-GATE05-TRIAGE.md
@ApiStatus.Internal
public class SimpleContainer {
    private static final Logger LOGGER = Logger.getLogger(SimpleContainer.class.getName());
    
    // === Three-level cache for circular dependency resolution ===
    // Level 1: Complete singleton objects (fully initialized)
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
    // Level 2: Early singleton objects (instantiated but not fully initialized - exposed for circular ref)
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>();
    // Level 3: Singleton factories (ObjectFactory for creating early refs)
    private final Map<String, Supplier<Object>> singletonFactories = new ConcurrentHashMap<>();
    
    // Legacy singletons reference (for backwards compatibility - points to singletonObjects)
    private final Map<String, Object> singletons = singletonObjects;
    
    private final Map<String, Supplier<Object>> suppliers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> typeMappings = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<Object>> typeSuppliers = new ConcurrentHashMap<>();
    // By-type assignability resolution cache. Populated only by getBean(Class)'s ambiguity
    // adjudication (D-11/D-12) -- distinct from typeMappings, which holds author-declared
    // bindings (registerType/registerTypeSupplier/registerSingleton's own concrete-class
    // binding). Kept separate so a newly registered implementation can invalidate this cache
    // without dropping an explicit binding. Instance-scoped, not static: a static Class-keyed
    // map would pin module classes and block plugin ClassLoader unload (Phase 1 D-35/D-38).
    private final Map<Class<?>, Object> resolvedTypeCache = new ConcurrentHashMap<>();
    private final Map<String, BeanScope> beanScopes = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> beanTypes = new ConcurrentHashMap<>();
    private final List<BeanPostProcessor> beanPostProcessors = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentHashMap<>();
    private final Set<String> currentlyCreating = ConcurrentHashMap.newKeySet();
    // The container's own memory of a bean whose creation is DETERMINISTICALLY unresolvable for
    // the lifetime of this container's classloader (07-23) -- keyed by bean name, valued by the
    // EXACT LinkageError/TypeNotPresentException createBean already caught for that name. Once a
    // name is present here, getBean(String) fails fast and createBean is never re-invoked for it,
    // so a later, independent caller (e.g. PluginManager.validateCommandExecutorContracts's
    // second getBean() call after preInstantiateSingletons already tried and skipped the same
    // bean once) never re-attempts creation and never re-throws uncaught. Distinct from
    // currentlyCreating's transient in-progress tracking: this map records a PERMANENT, terminal
    // failure, not work still underway.
    private final Map<String, Throwable> unresolvableBeans = new ConcurrentHashMap<>();
    // Cache for supplier types to avoid instantiation in getBeanNamesForType
    private final Map<String, Class<?>> supplierTypes = new ConcurrentHashMap<>();
    private SimpleContainer parent;
    private ClassLoader classLoader;
    private boolean isStarted = false;
    // The 07-21 D-19 diagnostic identifier for this container's own bean-creation phase --
    // independently keyed from ComponentScanner's basePackage-keyed summary. Set (for real) via
    // setDisplayName, wired from PluginManager.assemblePluginContainer.
    private String displayName;
    /**
     * Resolves which class to instantiate for a bean. Null means AOP is not wired for this
     * container, in which case every bean is instantiated as its declared class.
     */
    private com.ultikits.ultitools.aop.AopProxyResolver aopProxyResolver;

    public enum BeanScope {
        SINGLETON, PROTOTYPE
    }

    public SimpleContainer() {
    }

    public SimpleContainer(SimpleContainer parent) {
        this.parent = parent;
    }

    /**
     * Get singleton from three-level cache.
     * This method implements the circular dependency resolution through early singleton exposure.
     *
     * @param beanName bean name
     * @param allowEarlyReference whether to allow early reference (level 2/3 cache)
     * @return singleton instance or null if not found
     */
    protected Object getSingleton(String beanName, boolean allowEarlyReference) {
        // Level 1: Check complete singletons
        Object singletonObject = singletonObjects.get(beanName);
        
        if (singletonObject == null && currentlyCreating.contains(beanName)) {
            // Bean is currently being created - check early caches
            if (allowEarlyReference) {
                // Level 2: Check early singleton objects
                singletonObject = earlySingletonObjects.get(beanName);
                
                if (singletonObject == null) {
                    // Level 3: Check singleton factories
                    Supplier<Object> factory = singletonFactories.get(beanName);
                    if (factory != null) {
                        // Create early singleton reference
                        singletonObject = factory.get();
                        // Promote to level 2 cache
                        earlySingletonObjects.put(beanName, singletonObject);
                        // Remove from level 3 cache
                        singletonFactories.remove(beanName);
                        LOGGER.fine("Early singleton reference created for: " + beanName);
                    }
                }
            }
        }
        
        return singletonObject;
    }
    
    /**
     * Add a singleton factory to level 3 cache for early reference support.
     *
     * @param beanName bean name
     * @param singletonFactory factory for creating early reference
     */
    protected void addSingletonFactory(String beanName, Supplier<Object> singletonFactory) {
        if (!singletonObjects.containsKey(beanName)) {
            singletonFactories.put(beanName, singletonFactory);
            earlySingletonObjects.remove(beanName);
        }
    }
    
    /**
     * Add a fully initialized singleton to level 1 cache.
     * Clears from level 2 and level 3 caches.
     *
     * @param beanName bean name
     * @param singletonObject fully initialized singleton
     */
    protected void addSingleton(String beanName, Object singletonObject) {
        singletonObjects.put(beanName, singletonObject);
        singletonFactories.remove(beanName);
        earlySingletonObjects.remove(beanName);
    }

    /**
     * Registers a singleton instance, fully assembling it before storage.
     * <p>
     * Runs the same {@code postProcessBeforeInitialization -> autowireBean -> @PostConstruct ->
     * postProcessAfterInitialization} sequence {@link #createBean} runs for a container-constructed
     * bean, in that order, before storing -- unconditionally, whether or not {@link #refresh()} has
     * been called. Before 6.3.0 this method was two lines: {@code addSingleton} plus a type-mapping
     * write, with no autowiring, no {@code @PostConstruct} invocation, and no
     * {@link BeanPostProcessor} chain -- an object handed in through this method reached the
     * container half-initialised. {@link #refresh()} only pre-instantiates non-lazy singleton
     * <em>definitions</em> and never touches a {@code registerSingleton}-path object, so gating
     * assembly on refresh state (as a narrower, window-guard fix would) would have left the config
     * entities, the {@code @Configuration} instance, and the {@code @Bean} products -- all
     * registered <em>before</em> {@code refresh()} runs -- exactly as uninjected as before (D-14).
     * <p>
     * The bean actually stored, and returned by a later {@link #getBean(String)}, is whichever
     * reference {@code postProcessAfterInitialization} last returned -- not necessarily
     * {@code instance} itself, if a registered {@link BeanPostProcessor} substitutes it.
     * <p>
     * <b>Refuses {@code instance} outright when its class carries {@code @Transactional} or
     * {@code @ExceptionCatch} -- method-level or class-level -- that it can never honour (D-15).</b>
     * A ByteBuddy proxy is the bean itself in this framework's design; there is no separate
     * delegate target, so a proxy can never be retrofitted onto an object the caller already
     * constructed. Before this refusal existed, the annotation was silently inert: the method ran
     * completely unprotected/untransacted, with no signal anything was wrong. An instance that is
     * already a generated proxy ({@link ProxyFactory#isProxyClass}) is exempt -- it already honours
     * its own annotations, copied onto the generated subclass by {@code ProxyFactory} itself.
     * <p>
     * <b>No circular-dependency support between two {@code registerSingleton}-registered objects
     * (WR-02).</b> Unlike {@link #createBean}, this method does not add an early reference to
     * {@link #singletonFactories}/{@link #earlySingletonObjects} before autowiring -- and, unlike
     * {@code createBean}, it structurally cannot benefit from doing so. {@code createBean}'s early
     * exposure works because the container itself triggers a dependency's construction lazily,
     * recursively, inside the same call stack that is still assembling the bean that depends on
     * it -- so an early, not-yet-autowired self-reference can be handed back partway through. A
     * {@code registerSingleton} caller, by contrast, hands in an object the caller already fully
     * constructed <em>outside</em> the container; the container never constructs it and has no
     * hook to trigger construction of a second, not-yet-registered object on the first one's
     * behalf. Concretely: if object A and object B are registered via two separate, sequential
     * {@code registerSingleton} calls and each has an {@code @Autowired} field pointing at the
     * other, A's autowiring runs while B does not exist in this container in any form yet (no
     * definition, no factory, nothing) -- there is nothing an early-reference mechanism could
     * expose. A's field then either throws (if {@code required = true}) or stays {@code null} (if
     * {@code required = false}); B, registered second, resolves normally since A already exists by
     * then. This is reachable by an ordinary module author writing two {@code @Bean} methods (or a
     * {@code @Configuration} class and one of its own {@code @Bean} products) that reference each
     * other, since both paths register their product via this method. Workarounds: order the two
     * registrations so the one with the {@code required = true} field is registered second, use
     * {@code @Autowired(required = false)} plus manual post-registration wiring, or -- where
     * possible -- register the pair through component scanning instead
     * ({@code @Service}/{@code @Component}), where {@link #createBean}'s three-level cache does
     * apply.
     *
     * @param name instance name
     * @param instance instance object
     * @throws com.ultikits.ultitools.exceptions.ContainerException if {@code instance}'s class
     *         carries an AOP annotation it can never honour (D-15), or if a required
     *         {@code @Autowired} dependency on it cannot be resolved (D-08)
     */
    public void registerSingleton(String name, Object instance) {
        refuseIfAopAnnotated(instance);

        // 07-fix: the memo describes ONE binding for this name. Re-binding the name makes it
        // stale, and getBean's fast-path answers from it before consulting any map, so a
        // valid replacement would stay permanently unreachable (and filtered out of
        // getBeanNamesForType). Same invariant registerBeanDefinition already honours for
        // resolvedTypeCache via invalidateResolvedTypeCache (D-12).
        unresolvableBeans.remove(name);

        Object bean = instance;
        for (BeanPostProcessor processor : beanPostProcessors) {
            bean = processor.postProcessBeforeInitialization(bean, name);
        }
        getAutowireCapableBeanFactory().autowireBean(bean);
        invokePostConstructMethods(bean);
        for (BeanPostProcessor processor : beanPostProcessors) {
            bean = processor.postProcessAfterInitialization(bean, name);
        }

        addSingleton(name, bean);
        typeMappings.put(bean.getClass(), bean);
        // A newly registered singleton may be a new candidate for some already-resolved
        // interface/superclass type -- invalidate so the next getBean(Class) reconsiders it
        // instead of returning a stale cached resolution (D-12).
        invalidateResolvedTypeCache();
    }

    /**
     * Refuses {@code instance} when its class carries an AOP annotation
     * ({@code @Transactional}/{@code @ExceptionCatch}) it can never honour (D-15).
     * <p>
     * A generated proxy ({@link ProxyFactory#isProxyClass}) is exempt -- it already honours its own
     * annotations. Otherwise, method-level annotations are read via
     * {@link AopEligibility#findAopAnnotatedMethods}, which deliberately omits class-level ones (see
     * its own javadoc), so the class-level half is checked separately here -- omitting it would
     * leave a class-level {@code @Transactional} silently inert, restating SILENT-10 rather than
     * fixing it.
     *
     * @param instance the instance about to be registered
     * @throws ContainerException naming the offending class and the annotated method (or
     *         {@code "class-level"}) when a refusal applies
     */
    private void refuseIfAopAnnotated(Object instance) {
        Class<?> clazz = instance.getClass();
        if (ProxyFactory.isProxyClass(clazz)) {
            return;
        }
        Set<Method> annotatedMethods = AopEligibility.findAopAnnotatedMethods(clazz);
        if (!annotatedMethods.isEmpty()) {
            Method offending = annotatedMethods.iterator().next();
            throw ContainerException.aopAnnotationOnPreConstructedBean(clazz,
                    offending.getDeclaringClass().getName() + "#" + offending.getName());
        }
        if (MergedAnnotationResolver.isPresent(clazz, Transactional.class)
                || MergedAnnotationResolver.isPresent(clazz, ExceptionCatch.class)) {
            throw ContainerException.aopAnnotationOnPreConstructedBean(clazz, "class-level");
        }
    }

    /**
     * Register a supplier for lazy initialization.
     *
     * @param name supplier name
     * @param supplier supplier function
     */
    public void registerSupplier(String name, Supplier<Object> supplier) {
        // 07-fix: the memo describes ONE binding for this name. Re-binding the name makes it
        // stale, and getBean's fast-path answers from it before consulting any map, so a
        // valid replacement would stay permanently unreachable (and filtered out of
        // getBeanNamesForType). Same invariant registerBeanDefinition already honours for
        // resolvedTypeCache via invalidateResolvedTypeCache (D-12).
        unresolvableBeans.remove(name);
        suppliers.put(name, supplier);
    }

    /**
     * Register a supplier for lazy initialization with type information.
     *
     * @param name supplier name
     * @param supplier supplier function
     * @param type the type of bean this supplier produces
     */
    public void registerSupplier(String name, Supplier<Object> supplier, Class<?> type) {
        suppliers.put(name, supplier);
        supplierTypes.put(name, type);
    }

    /**
     * Register a type mapping.
     *
     * @param type class type
     * @param instance instance object
     */
    public <T> void registerType(Class<T> type, T instance) {
        typeMappings.put(type, instance);
    }

    /**
     * Register a type supplier.
     *
     * @param type class type
     * @param supplier supplier function
     */
    @SuppressWarnings("unchecked")
    public <T> void registerTypeSupplier(Class<T> type, Supplier<T> supplier) {
        typeSuppliers.put(type, (Supplier<Object>) supplier);
    }

    /**
     * Get bean by name.
     *
     * @param name bean name
     * @return bean instance
     */
    public Object getBean(String name) {
        // Fast-path: a bean already proven deterministically unresolvable (07-23) is never
        // retried -- rethrow its ORIGINAL exception type, never null. A null return would flow
        // into AutowireFactory.autowireBean's `dependency == null` branch and, for any OTHER bean
        // with a required=true field of the SAME poisoned type, convert what is today a reliably
        // caught LinkageError into an uncaught ContainerException that
        // preInstantiateSingletons's narrow catch does NOT catch -- silently turning today's
        // correct per-bean skip into a NEW whole-module abort for a different bean. Only these
        // two types are ever stored (see createBean's catch below), so the two checks are
        // exhaustive.
        Throwable memoized = unresolvableBeans.get(name);
        if (memoized != null) {
            if (memoized instanceof RuntimeException) {
                throw (RuntimeException) memoized;
            }
            if (memoized instanceof Error) {
                throw (Error) memoized;
            }
        }

        // Try three-level cache for singletons
        Object bean = getSingleton(name, true);
        if (bean != null) {
            return bean;
        }

        // Check bean definition with double-checked locking for thread safety
        BeanDefinition definition = beanDefinitions.get(name);
        if (definition != null) {
            if (definition.isSingleton()) {
                // Double-check for singleton beans using three-level cache
                bean = getSingleton(name, true);
                if (bean == null) {
                    synchronized (this) {
                        bean = getSingleton(name, true);
                        if (bean == null) {
                            bean = createBean(name, definition);
                        }
                    }
                }
                return bean;
            } else {
                // Prototype: always create new instance
                // Check circular dependency for prototype
                if (currentlyCreating.contains(name)) {
                    // GATE-05 group two: routed to the typed container hierarchy (08-21). The
                    // message and the "currently creating" diagnostic are preserved verbatim --
                    // only the exception's type and carried ErrorCode changed.
                    throw new ContainerException(ErrorCode.CIRCULAR_DEPENDENCY,
                        "Circular dependency detected for prototype bean '" + name +
                        "'. Currently creating beans: " + currentlyCreating);
                }
                return createBean(name, definition);
            }
        }

        Supplier<Object> supplier = suppliers.get(name);
        if (supplier != null) {
            bean = supplier.get();
            BeanScope scope = beanScopes.getOrDefault(name, BeanScope.SINGLETON);
            if (scope == BeanScope.SINGLETON) {
                singletonObjects.put(name, bean);
            }
            return bean;
        }

        if (parent != null) {
            return parent.getBean(name);
        }

        return null;
    }

    /**
     * Get bean by type.
     *
     * @param type bean type
     * @return bean instance
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        Object bean = typeMappings.get(type);
        if (bean != null) {
            return (T) bean;
        }

        Supplier<Object> supplier = typeSuppliers.get(type);
        if (supplier != null) {
            // Double-check for thread safety
            bean = typeMappings.get(type);
            if (bean == null) {
                synchronized (this) {
                    bean = typeMappings.get(type);
                    if (bean == null) {
                        bean = supplier.get();
                        typeMappings.put(type, bean);
                    }
                }
            }
            return (T) bean;
        }

        // Check bean definitions and create bean if found -- but only take this shortcut for a
        // genuine self-match. getBeanName(type) synthesizes a decapitalized default name when
        // `type` carries no explicit @Component/@Service of its own -- for an INTERFACE (the
        // exact case this by-type lookup exists for), that default is just "decapitalize the
        // interface's own simple name". If an unrelated implementer happens to be registered
        // under that exact name (an ordinary, Spring-idiomatic naming choice: e.g.
        // @Service("notificationService") on some impl of NotificationService), returning it
        // here unconditionally would bypass the priority/ambiguity adjudication below entirely,
        // even when a strictly higher-priority implementer also exists (CR-01).
        //
        // Two cases are still trusted as a deliberate, specific request and skip adjudication,
        // matching Spring's own precedent that a by-name resolution is more specific than a
        // by-type one (DefaultListableBeanFactory#resolveNamedBean) -- but note Spring itself
        // never derives that name from the requested TYPE the way getBeanName(type) does here;
        // it only trusts a name the caller or the bean itself explicitly supplied:
        //   1. `type` IS the registered class -- querying a concrete class by itself always
        //      resolves to itself, exactly the exactNameMatchStillShortCircuits case.
        //   2. `type` itself explicitly declares @Component/@Service(value = beanName) -- i.e.
        //      beanName did NOT come from getBeanName's decapitalized-default fallback, but from
        //      a real annotation on `type`. This is `type` naming its own registration, not an
        //      unrelated class's registration happening to collide with a synthesized guess.
        // Anything else falls through to the same candidate-set/priority adjudication every
        // other by-type lookup goes through.
        String beanName = getBeanName(type);
        BeanDefinition definition = beanDefinitions.get(beanName);
        if (definition != null && type.isAssignableFrom(definition.getBeanClass())
                && (definition.getBeanClass().equals(type) || declaresOwnBeanName(type))) {
            bean = getBean(beanName); // Use getBean(name) for thread-safe creation
            return (T) bean;
        }

        // A prior assignability adjudication for this exact type, if one has already happened
        // and no registration has invalidated it since (D-12).
        bean = resolvedTypeCache.get(type);
        if (bean != null) {
            return (T) bean;
        }

        // Collect every assignable candidate in this container and order by @Service(priority)
        // descending, reusing getOrderedBeansOfType -- which had zero production callers before
        // this change (D-11) -- instead of writing a third ordering implementation. It already
        // dedups a definition-backed singleton that has already been instantiated, it
        // identity-dedups a singleton registered under two names (e.g. DependenceManagers'
        // TeleportService/NotificationService/EmailService, each aliased under a short name plus
        // the interface FQN -- regression fixed after PR #352's real-machine UAT), and it reads
        // priority off each candidate's actual resolved instance class, so a proxied bean's
        // priority is read correctly: ProxyFactory copies the target's annotations onto the
        // generated subclass, and getServicePriority relies on that.
        List<T> candidates = getOrderedBeansOfType(type);

        if (candidates.isEmpty()) {
            // Total miss in this container -- only now is the parent consulted, so a child that
            // has any candidates (even an ambiguous pair that goes on to throw) never falls
            // through to a parent's unrelated bean (D-13).
            if (parent != null) {
                return parent.getBean(type);
            }
            return null;
        }

        if (candidates.size() > 1) {
            // Only the top two matter: a tie further down the list, among beans that already
            // lose to the top candidate, is not ambiguous.
            T first = candidates.get(0);
            T second = candidates.get(1);
            if (getServicePriority(first.getClass()) == getServicePriority(second.getClass())) {
                throw ContainerException.ambiguousBeanType(type, first.getClass(), second.getClass());
            }
        }

        // Adjudicate before caching: this is reached only once exactly one winner is
        // determined -- either there was a single candidate, or priority broke the tie (D-12).
        bean = candidates.get(0);
        resolvedTypeCache.put(type, bean);
        return (T) bean;
    }

    /**
     * Get bean by name and type.
     *
     * @param name bean name
     * @param type bean type
     * @return bean instance
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name, Class<T> type) {
        Object bean = getBean(name);
        if (bean != null && type.isInstance(bean)) {
            return (T) bean;
        }
        return null;
    }

    /**
     * Get bean names for type.
     *
     * @param type bean type
     * @return bean names
     */
    public String[] getBeanNamesForType(Class<?> type) {
        Set<String> beanNames = new HashSet<>();
        
        // Check singletons
        for (Map.Entry<String, Object> entry : singletons.entrySet()) {
            if (type.isInstance(entry.getValue())) {
                beanNames.add(entry.getKey());
            }
        }
        
        // Check bean definitions by class type (no instantiation needed)
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitions.entrySet()) {
            // 07-23: never offer a name already proven deterministically unresolvable -- this is
            // the fix for PluginManager.validateCommandExecutorContracts's uncaught retry (and
            // the identically-shaped CommandManager.registerAll/ListenerManager.registerAll):
            // all three enumerate via this method, then call getBean() per returned name.
            if (unresolvableBeans.containsKey(entry.getKey())) {
                continue;
            }
            if (type.isAssignableFrom(entry.getValue().getBeanClass())) {
                beanNames.add(entry.getKey());
            }
        }
        
        // Check suppliers using cached type information (avoiding instantiation)
        for (Map.Entry<String, Class<?>> entry : supplierTypes.entrySet()) {
            if (type.isAssignableFrom(entry.getValue())) {
                beanNames.add(entry.getKey());
            }
        }
        
        // Check parent container
        if (parent != null) {
            String[] parentNames = parent.getBeanNamesForType(type);
            beanNames.addAll(Arrays.asList(parentNames));
        }
        
        return beanNames.toArray(new String[0]);
    }

    /**
     * Get autowire capable bean factory.
     *
     * @return autowire factory
     */
    public AutowireFactory getAutowireCapableBeanFactory() {
        return new AutowireFactory(this);
    }

    /**
     * Close the container.
     */
    public void close() {
        LOGGER.info("Closing container...");
        
        // Invoke @PreDestroy methods on all singleton beans
        for (Object bean : singletonObjects.values()) {
            invokePreDestroyMethods(bean);
        }
        
        // Clear three-level cache
        singletonObjects.clear();
        earlySingletonObjects.clear();
        singletonFactories.clear();
        
        suppliers.clear();
        typeMappings.clear();
        typeSuppliers.clear();
        beanScopes.clear();
        beanTypes.clear();
        beanDefinitions.clear();
        beanPostProcessors.clear();
        currentlyCreating.clear();
        supplierTypes.clear();
        // Release the by-type resolution cache too (WR-01): it holds both requested Class<?>
        // keys and resolved bean-instance values loaded by this container's own classloader,
        // exactly the kind of reference every other Class/instance-keyed collection above is
        // cleared to stop pinning after a plugin unloads.
        resolvedTypeCache.clear();
        // 07-23: release the failed-bean memo too -- instance-scoped bookkeeping, cleared exactly
        // like every other per-container structure above.
        unresolvableBeans.clear();
        isStarted = false;
        
        LOGGER.info("Container closed.");
    }

    /**
     * Check if container contains bean.
     *
     * @param name bean name
     * @return true if contains
     */
    public boolean containsBean(String name) {
        return singletons.containsKey(name) || suppliers.containsKey(name) ||
                beanDefinitions.containsKey(name) ||
                (parent != null && parent.containsBean(name));
    }

    /**
     * Register bean with constructor arguments.
     *
     * @param type bean type
     * @param constructorArgs constructor arguments
     */
    public <T> void registerBean(Class<T> type, Object... constructorArgs) {
        try {
            String beanName = getBeanName(type);
            BeanDefinition definition = new BeanDefinition(type);
            if (constructorArgs.length > 0) {
                definition.setConstructorArgValues(constructorArgs);
            }
            registerBeanDefinition(beanName, definition);
        } catch (Exception e) {
            // GATE-05 group two (08-21): routed to the typed container hierarchy. Reachable
            // today via getBeanName(type) throwing StringIndexOutOfBoundsException for a type
            // with no derivable simple name (e.g. an anonymous class), when no @Component/
            // @Service value covers it.
            throw new ContainerException("Failed to register bean: " + type.getName(), e);
        }
    }

    /**
     * Generate bean name for class.
     */
    private <T> String getBeanName(Class<T> type) {
        Component component = type.getAnnotation(Component.class);
        if (component != null && !component.value().isEmpty()) {
            return component.value();
        }
        Service service = type.getAnnotation(Service.class);
        if (service != null && !service.value().isEmpty()) {
            return service.value();
        }
        // Default to simple class name with first letter lowercase
        String className = type.getSimpleName();
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    /**
     * True iff {@code type} itself carries an explicit {@code @Component(value = ...)} or
     * {@code @Service(value = ...)} name -- the same condition {@link #getBeanName(Class)} checks
     * before falling back to a decapitalized-simple-name guess.
     * <p>
     * Used by {@link #getBean(Class)}'s by-name shortcut (CR-01) to tell a deliberate, specific
     * request ("{@code type} declares this exact name as its own") apart from an accidental
     * collision between an unrelated implementer's explicit bean name and an interface's
     * synthesized default -- only the former is a genuine self-match that should skip the
     * priority/ambiguity adjudication a few lines below.
     *
     * @param type the requested type to check
     * @return true if {@code type} declares its own non-empty {@code @Component}/{@code @Service} name
     */
    private boolean declaresOwnBeanName(Class<?> type) {
        Component component = type.getAnnotation(Component.class);
        if (component != null && !component.value().isEmpty()) {
            return true;
        }
        Service service = type.getAnnotation(Service.class);
        return service != null && !service.value().isEmpty();
    }

    /**
     * Refresh the container.
     */
    public void refresh() {
        preInstantiateSingletons();
    }

    /**
     * Get all singleton bean instances registered in this container (not including parent).
     *
     * @return unmodifiable collection of singleton beans
     */
    public java.util.Collection<Object> getSingletonValues() {
        return java.util.Collections.unmodifiableCollection(singletonObjects.values());
    }

    /**
     * Set display name.
     *
     * @param displayName display name
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Set ID.
     *
     * @param id container ID
     */
    public void setId(String id) {
        // No-op for now
    }

    /**
     * Set class loader.
     *
     * @param classLoader class loader
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Register shutdown hook.
     */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * Set parent container.
     *
     * @param parent parent container
     */
    public void setParent(SimpleContainer parent) {
        this.parent = parent;
    }

    /**
     * Get bean factory.
     *
     * @return bean factory
     */
    public BeanFactory getBeanFactory() {
        return new BeanFactory(this);
    }

    /**
     * Get class loader.
     *
     * @return class loader
     */
    public ClassLoader getClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        // Always use UltiTools JavaPlugin classloader as fallback
        return UltiTools.getJavaPluginClassLoader();
    }

    /**
     * Register bean definition.
     *
     * @param name bean name
     * @param definition bean definition
     */
    public void registerBeanDefinition(String name, BeanDefinition definition) {
        // 07-fix: the memo describes ONE binding for this name. Re-binding the name makes it
        // stale, and getBean's fast-path answers from it before consulting any map, so a
        // valid replacement would stay permanently unreachable (and filtered out of
        // getBeanNamesForType). Same invariant registerBeanDefinition already honours for
        // resolvedTypeCache via invalidateResolvedTypeCache (D-12).
        unresolvableBeans.remove(name);
        beanDefinitions.put(name, definition);
        beanTypes.put(name, definition.getBeanClass());
        // A newly registered bean definition may be a new candidate for some already-resolved
        // interface/superclass type -- invalidate so the next getBean(Class) reconsiders it
        // instead of returning a stale cached resolution (D-12).
        invalidateResolvedTypeCache();
    }

    /**
     * Invalidates the by-type assignability resolution cache ({@link #resolvedTypeCache}).
     * <p>
     * Called whenever a new bean definition or singleton is registered, so a subsequently
     * registered implementation of an already-resolved type participates in the next
     * adjudication instead of being masked by a resolution cached before it existed (D-12).
     * Never touches {@link #typeMappings} -- that map holds author-declared bindings
     * ({@code registerType}/{@code registerTypeSupplier}/{@code registerSingleton}'s own
     * concrete-class binding), which must survive registration of unrelated beans.
     */
    private void invalidateResolvedTypeCache() {
        resolvedTypeCache.clear();
    }

    /**
     * Create bean from definition.
     * Uses three-level cache to support circular dependency resolution for setter injection.
     *
     * @param name bean name
     * @param definition bean definition
     * @return created bean
     */
    private Object createBean(String name, BeanDefinition definition) {
        try {
            currentlyCreating.add(name);
            LOGGER.fine("Creating bean: " + name);

            Object bean;
            if (definition.getFactoryMethod() != null) {
                // Factory method creation
                bean = definition.getFactoryMethod().invoke(definition.getFactoryBean());
            } else {
                // Constructor creation with smart matching.
                // AOP: resolve the class to instantiate BEFORE construction. An inheritance-based
                // proxy is the bean itself, so it must be the object every later step sees --
                // @Autowired injection, @PostConstruct, and the singleton cache all act on it.
                // ByteBuddy copies every constructor of the target, so all three paths below work
                // unchanged on the generated subclass. See issue #190.
                Class<?> beanClass = definition.getBeanClass();
                if (aopProxyResolver != null) {
                    beanClass = aopProxyResolver.resolve(beanClass);
                }
                Object[] constructorArgs = definition.getConstructorArgValues();
                
                if (constructorArgs != null && constructorArgs.length > 0) {
                    Constructor<?> constructor = findMatchingConstructor(beanClass, constructorArgs);
                    constructor.setAccessible(true);
                    bean = constructor.newInstance(constructorArgs);
                } else {
                    // Try no-arg constructor first, fall back to constructor auto-wiring
                    try {
                        Constructor<?> constructor = beanClass.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        bean = constructor.newInstance();
                    } catch (NoSuchMethodException e) {
                        bean = createBeanWithConstructorInjection(beanClass);
                    }
                }
            }

            // Add to level 3 cache for early reference support (before autowiring)
            // This allows other beans being created to get a reference to this bean
            if (definition.isSingleton()) {
                final Object earlyBean = bean;
                addSingletonFactory(name, () -> earlyBean);
            }

            // Apply bean post processors before initialization
            for (BeanPostProcessor processor : beanPostProcessors) {
                bean = processor.postProcessBeforeInitialization(bean, name);
            }

            // Autowire dependencies (may trigger circular dependency resolution)
            getAutowireCapableBeanFactory().autowireBean(bean);

            // Invoke @PostConstruct methods
            invokePostConstructMethods(bean);

            // Apply bean post processors after initialization
            for (BeanPostProcessor processor : beanPostProcessors) {
                bean = processor.postProcessAfterInitialization(bean, name);
            }

            // Store singleton in level 1 cache (final location)
            if (definition.isSingleton()) {
                // Check if early reference was created and exposed
                Object earlySingletonRef = earlySingletonObjects.get(name);
                if (earlySingletonRef != null && earlySingletonRef != bean) {
                    // Bean was modified during post-processing, but early ref was already exposed
                    // This is a circular dependency issue - log warning
                    LOGGER.warning("Bean '" + name + "' was modified after early exposure. " +
                        "Circular dependency may cause issues with proxied beans.");
                }
                addSingleton(name, bean);
                typeMappings.put(definition.getBeanClass(), bean);
            }

            LOGGER.fine("Successfully created bean: " + name);
            return bean;
        } catch (ContainerException e) {
            // A ContainerException raised deeper in bean creation (e.g. an unresolvable
            // @Autowired(required = true) dependency, or a constructor-injection failure) is
            // already a deliberate, correctly-typed refusal -- rethrow it unchanged instead of
            // letting the catch-all below downgrade it to an anonymous RuntimeException. Mirrors
            // ComponentScanner.scanPackage's own catch (ContainerException e) { throw e; }
            // rethrow for the same reason: a refusal that is swallowed or re-typed upstream is
            // indistinguishable from the silent no-op it replaces (D-08, D-09).
            singletonFactories.remove(name);
            earlySingletonObjects.remove(name);
            LOGGER.log(Level.SEVERE, "Failed to create bean: " + name, e);
            throw e;
        } catch (LinkageError | TypeNotPresentException e) {
            // A symbol this bean's own method signatures reference is absent from the classpath
            // (e.g. a module JAR compiled against an older UltiTools-API, referencing a removed
            // symbol) -- AopProxyResolver.resolve (called above) triggers
            // ReflectionUtil.getAllMethods -> Class.getDeclaredMethods, which the JVM resolves
            // eagerly, throwing NoClassDefFoundError/TypeNotPresentException right there rather
            // than at bean-construction time. Mirrors FinalContractValidator's own, already-
            // reviewed catch (NoClassDefFoundError | TypeNotPresentException e) for the identical
            // hazard, one call away from this one (07-21).
            //
            // Deliberately does NOT widen to Error or Throwable: OutOfMemoryError and
            // StackOverflowError are VirtualMachineError, a sibling branch of Error and not a
            // LinkageError subtype, and must keep propagating and aborting rather than letting
            // bean creation continue in a possibly corrupted VM state.
            singletonFactories.remove(name);
            earlySingletonObjects.remove(name);
            // 07-23: remember this bean is deterministically unresolvable so getBean(String)'s
            // fast-path above short-circuits any later, independent caller instead of
            // re-invoking createBean and re-throwing. Because the fast-path means createBean can
            // now only ever run its real body once per bean name per container lifetime, this
            // single put is sufficient on its own -- no separate dedup guard is needed, and the
            // recordSkippedClass call below now fires exactly once per distinct poisoned bean
            // name rather than once per encounter.
            unresolvableBeans.put(name, e);
            ModuleScanDiagnostics.recordSkippedClass(displayName, definition.getBeanClass().getName(), e);
            LOGGER.log(Level.WARNING, "Skipping bean '" + name + "' -- its class references a "
                    + "symbol absent from the classpath", e);
            throw e;
        } catch (Exception e) {
            // Clean up caches on failure
            singletonFactories.remove(name);
            earlySingletonObjects.remove(name);
            LOGGER.log(Level.SEVERE, "Failed to create bean: " + name, e);
            // GATE-05 group two (08-21): routed to the typed container hierarchy. A
            // ContainerException raised deeper in this method is already caught and rethrown
            // unchanged by the catch (ContainerException e) clause above, so this branch only
            // ever wraps a genuinely different failure -- e.g. a no-arg constructor whose body
            // itself throws.
            throw new ContainerException("Failed to create bean: " + name, e);
        } finally {
            currentlyCreating.remove(name);
        }
    }

    /**
     * Create bean using constructor auto-wiring.
     * Resolves constructor parameters from the container.
     *
     * @param beanClass the class to instantiate
     * @return new bean instance
     */
    private Object createBeanWithConstructorInjection(Class<?> beanClass) {
        Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
        // Pick the constructor with the most parameters
        Constructor<?> bestConstructor = null;
        for (Constructor<?> c : constructors) {
            if (bestConstructor == null || c.getParameterCount() > bestConstructor.getParameterCount()) {
                bestConstructor = c;
            }
        }
        if (bestConstructor == null) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    "No constructor found for: " + beanClass.getName());
        }
        Class<?>[] paramTypes = bestConstructor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = getBean(paramTypes[i]);
            if (args[i] == null) {
                throw new ContainerException(ErrorCode.DEPENDENCY_INJECTION_FAILED,
                        "Cannot resolve constructor parameter " + paramTypes[i].getName() +
                    " for bean: " + beanClass.getName());
            }
        }
        bestConstructor.setAccessible(true);
        try {
            return bestConstructor.newInstance(args);
        } catch (Exception e) {
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED,
                    "Failed to instantiate via constructor injection: " + beanClass.getName(), e);
        }
    }

    /**
     * Find a matching constructor for the given arguments.
     * Supports interface/superclass parameter types and primitive type boxing.
     *
     * @param beanClass the class to find constructor for
     * @param args constructor arguments
     * @return matching constructor
     */
    private Constructor<?> findMatchingConstructor(Class<?> beanClass, Object[] args) throws NoSuchMethodException {
        Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
        
        for (Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length != args.length) {
                continue;
            }
            
            boolean matches = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!isAssignable(paramTypes[i], args[i])) {
                    matches = false;
                    break;
                }
            }
            
            if (matches) {
                return constructor;
            }
        }
        
        // Fall back to exact type matching
        Class<?>[] paramTypes = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
        }
        return beanClass.getDeclaredConstructor(paramTypes);
    }

    /**
     * Check if a value can be assigned to a parameter type.
     * Handles primitive types, interfaces, and inheritance.
     *
     * @param paramType the parameter type
     * @param value the value to check
     * @return true if assignable
     */
    private boolean isAssignable(Class<?> paramType, Object value) {
        if (value == null) {
            return !paramType.isPrimitive();
        }
        
        Class<?> valueType = value.getClass();
        
        // Direct assignment
        if (paramType.isAssignableFrom(valueType)) {
            return true;
        }
        
        // Primitive type handling
        if (paramType.isPrimitive()) {
            return isPrimitiveWrapperOf(paramType, valueType);
        }
        
        // Wrapper to primitive
        if (valueType.isPrimitive()) {
            return isPrimitiveWrapperOf(valueType, paramType);
        }
        
        return false;
    }

    /**
     * Check if wrapper is the wrapper class for primitive type.
     */
    private boolean isPrimitiveWrapperOf(Class<?> primitive, Class<?> wrapper) {
        if (primitive == int.class) return wrapper == Integer.class;
        if (primitive == long.class) return wrapper == Long.class;
        if (primitive == double.class) return wrapper == Double.class;
        if (primitive == float.class) return wrapper == Float.class;
        if (primitive == boolean.class) return wrapper == Boolean.class;
        if (primitive == byte.class) return wrapper == Byte.class;
        if (primitive == short.class) return wrapper == Short.class;
        if (primitive == char.class) return wrapper == Character.class;
        return false;
    }

    /**
     * Invoke methods annotated with @PostConstruct.
     *
     * @param bean the bean instance
     */
    private void invokePostConstructMethods(Object bean) {
        // Walk the hierarchy with override de-duplication. Iterating getDeclaredMethods() level by
        // level fires the callback once per level when an override repeats the annotation, and on
        // an AOP proxy that is the normal case rather than the exception. See issue #190.
        for (Method method : ReflectionUtil.getAllMethods(bean.getClass())) {
            if (method.isAnnotationPresent(PostConstruct.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(bean);
                    LOGGER.fine("Invoked @PostConstruct method: " + method.getName());
                } catch (Exception e) {
                    // GATE-05 group two (08-21): routed to the typed container hierarchy.
                    // @PostConstruct invocation is part of bean creation, so this uses the same
                    // BEAN_CREATION_FAILED code createBean's own catch-all uses -- and, once
                    // typed, this propagates through createBean's catch (ContainerException e)
                    // rethrow-unchanged clause instead of being wrapped a second time.
                    throw new ContainerException("Failed to invoke @PostConstruct method: " + method.getName(), e);
                }
            }
        }
    }

    /**
     * Invoke methods annotated with @PreDestroy on a bean.
     *
     * @param bean the bean instance
     */
    private void invokePreDestroyMethods(Object bean) {
        // Same hierarchy walk and de-dup as invokePostConstructMethods; see the comment there.
        for (Method method : ReflectionUtil.getAllMethods(bean.getClass())) {
            if (method.isAnnotationPresent(PreDestroy.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(bean);
                    LOGGER.fine("Invoked @PreDestroy method: " + method.getName());
                } catch (Throwable e) { // NOPMD - must catch Error (e.g. NoClassDefFoundError when dependency plugins unload first)
                    LOGGER.log(Level.WARNING, "Failed to invoke @PreDestroy method: " + method.getName(), e);
                }
            }
        }
    }

    /**
     * Add bean post processor.
     *
     * @param processor bean post processor
     */
    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.add(processor);
    }

    /**
     * Sets the AOP proxy resolver for this container.
     * <p>
     * Must be called before {@link #refresh()}: the resolver participates in bean instantiation,
     * not in post-processing, so beans created earlier would not be proxied.
     *
     * @param resolver the resolver, or null to disable AOP for this container
     */
    public void setAopProxyResolver(com.ultikits.ultitools.aop.AopProxyResolver resolver) {
        this.aopProxyResolver = resolver;
    }

    /**
     * Gets the AOP proxy resolver for this container.
     *
     * @return the resolver, or null if AOP is not wired
     */
    public com.ultikits.ultitools.aop.AopProxyResolver getAopProxyResolver() {
        return aopProxyResolver;
    }

    /**
     * Get all bean names.
     *
     * @return bean names
     */
    public String[] getBeanDefinitionNames() {
        Set<String> names = new HashSet<>();
        names.addAll(singletons.keySet());
        names.addAll(suppliers.keySet());
        names.addAll(beanDefinitions.keySet());
        return names.toArray(new String[0]);
    }

    /**
     * Get beans of type.
     *
     * @param type bean type
     * @return beans map
     */
    // 07-23: deliberately NOT filtered against unresolvableBeans the way getBeanNamesForType is.
    // getBeanNamesForType is what PluginManager.validateCommandExecutorContracts and its two
    // identically-shaped siblings enumerate before an ordinary per-name getBean() lookup, where
    // excluding a poisoned name only removes redundant, already-doomed work. Filtering here would
    // be different in kind, not degree: this method backs getOrderedBeansOfType, getBean(Class)'s
    // ambiguous-candidate path, and getHighestPriorityBean -- for an interface-typed
    // @Autowired(required = true) dependency whose SOLE implementor is poisoned, filtering the
    // poisoned candidate OUT of this result turns getBean(Class)'s candidates.isEmpty() branch
    // into a null return, which AutowireFactory.autowireBean then converts into an uncaught
    // ContainerException for a DIFFERENT bean -- reintroducing the exact regression getBean(String)'s
    // fast-path above exists to prevent. Left throwing exactly as it does today, this method still
    // benefits from that same fast-path (faster, deduplicated) without needing its own filter.
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        Map<String, T> result = new HashMap<>();
        
        // Check singletons
        for (Map.Entry<String, Object> entry : singletons.entrySet()) {
            if (type.isInstance(entry.getValue())) {
                result.put(entry.getKey(), (T) entry.getValue());
            }
        }
        
        // Check bean definitions
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitions.entrySet()) {
            BeanDefinition definition = entry.getValue();
            if (type.isAssignableFrom(definition.getBeanClass())) {
                if (!result.containsKey(entry.getKey())) {
                    T bean = (T) getBean(entry.getKey());
                    if (bean != null) {
                        result.put(entry.getKey(), bean);
                    }
                }
            }
        }
        
        return result;
    }

    /**
     * Get beans of type ordered by @Service priority.
     * Higher priority beans come first.
     *
     * @param type bean type
     * @return ordered list of beans
     */
    public <T> List<T> getOrderedBeansOfType(Class<T> type) {
        Map<String, T> beans = getBeansOfType(type);

        // Identity-dedup before ordering: getBeansOfType() is keyed by bean NAME, so the same
        // singleton instance registered under two names (e.g. DependenceManagers.initCoreServices()
        // deliberately registers TeleportService/NotificationService/EmailService twice each --
        // once under a short internal name, once under the interface's FQN, so both getBean(String)
        // and getBean(Class) resolve it) appears as two separate map entries whose VALUES are the
        // same object. Without this dedup, getBean(Class)'s ambiguity check below sees that one
        // object twice and throws a false "ambiguous" error naming the same instance against
        // itself (regression caught by real-machine UAT on PR #352).
        //
        // IdentityHashMap (== semantics), not a HashSet/equals()-based dedup: a bean may override
        // equals()/hashCode(), and two DISTINCT instances of the same class that happen to be
        // equal must still be counted as two separate candidates so the ambiguity check below can
        // still refuse a genuine tie (SILENT-06 / this milestone's own success criterion). Only
        // reference identity -- the same object reached through two names -- collapses to one.
        Map<T, Boolean> seenByIdentity = new IdentityHashMap<>();
        List<T> result = new ArrayList<>();
        for (T bean : beans.values()) {
            if (seenByIdentity.put(bean, Boolean.TRUE) == null) {
                result.add(bean);
            }
        }

        result.sort((a, b) -> {
            int priorityA = getServicePriority(a.getClass());
            int priorityB = getServicePriority(b.getClass());
            return Integer.compare(priorityB, priorityA); // Descending order
        });
        return result;
    }

    /**
     * Get the highest priority bean of the given type.
     *
     * @param type bean type
     * @return highest priority bean or null
     */
    public <T> T getHighestPriorityBean(Class<T> type) {
        java.util.List<T> ordered = getOrderedBeansOfType(type);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /**
     * Get service priority from @Service annotation.
     *
     * @param clazz the class to check
     * @return priority value (default 0)
     */
    private int getServicePriority(Class<?> clazz) {
        Service service = clazz.getAnnotation(Service.class);
        return service != null ? service.priority() : 0;
    }

    /**
     * Check if bean is singleton.
     *
     * @param name bean name
     * @return true if singleton
     */
    public boolean isSingleton(String name) {
        BeanDefinition definition = beanDefinitions.get(name);
        if (definition != null) {
            return definition.isSingleton();
        }
        BeanScope scope = beanScopes.get(name);
        return scope == null || scope == BeanScope.SINGLETON;
    }

    /**
     * Check if bean is prototype.
     *
     * @param name bean name
     * @return true if prototype
     */
    public boolean isPrototype(String name) {
        BeanDefinition definition = beanDefinitions.get(name);
        if (definition != null) {
            return definition.isPrototype();
        }
        BeanScope scope = beanScopes.get(name);
        return scope == BeanScope.PROTOTYPE;
    }

    /**
     * Get bean type.
     *
     * @param name bean name
     * @return bean type
     */
    public Class<?> getType(String name) {
        Class<?> type = beanTypes.get(name);
        if (type != null) {
            return type;
        }
        
        Object bean = singletons.get(name);
        if (bean != null) {
            return bean.getClass();
        }
        
        BeanDefinition definition = beanDefinitions.get(name);
        if (definition != null) {
            return definition.getBeanClass();
        }
        
        return null;
    }

    /**
     * Initialize all singletons.
     */
    public void preInstantiateSingletons() {
        try {
            String[] beanNames = getBeanDefinitionNames();
            for (String beanName : beanNames) {
                BeanDefinition definition = beanDefinitions.get(beanName);
                if (definition != null && definition.isSingleton() && !definition.isLazyInit()) {
                    try {
                        getBean(beanName);
                    } catch (LinkageError | TypeNotPresentException e) {
                        // createBean already recorded the skip via ModuleScanDiagnostics and
                        // logged a local WARNING (07-21) -- this catch only needs to move on to
                        // the next bean, so one poisoned bean no longer takes the whole module
                        // (and every other bean it declares) down with it.
                        continue;
                    }
                }
            }
        } finally {
            // D-19: one SEVERE summary for this container's bean-creation phase, independently
            // keyed from ComponentScanner's own basePackage-keyed summary and from any
            // PluginManager-keyed one -- three independent accumulator keys, matching the
            // multi-keyed-accumulator design plan 07-04 already established. A container with no
            // skipped bean never invokes the emitter at all.
            ModuleScanDiagnostics.emitSummary(displayName);
        }
    }

    /**
     * Start the container.
     */
    public void start() {
        if (!isStarted) {
            preInstantiateSingletons();
            isStarted = true;
        }
    }

    /**
     * Stop the container.
     */
    public void stop() {
        isStarted = false;
    }

    /**
     * Check if container is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return isStarted;
    }

    /**
     * Scan components in packages.
     *
     * @param basePackages packages to scan
     */
    public void scanComponents(String... basePackages) {
        ComponentScanner scanner = new ComponentScanner(this);
        scanner.scanPackages(basePackages);
    }

    /**
     * Process configuration class.
     * <p>
     * Reads {@code @ComponentScan} through {@link MergedAnnotationResolver#find} rather than a
     * bare {@code configClass.getAnnotation(ComponentScan.class)} -- a class meta-annotated with
     * {@code @UltiToolsModule} carries its {@code scanBasePackages()}/{@code
     * scanBasePackageClasses()} values onto the merged {@code @ComponentScan} view via their
     * {@code @AliasFor} declarations (D-01), which a bare lookup would miss entirely. The result
     * is additive across {@code value()}, {@code basePackages()} and the packages named by
     * {@code basePackageClasses()} -- in that declaration order, with duplicates collapsed to
     * their first occurrence (GEN-06) -- not a first-match choice among them.
     *
     * @param configClass configuration class
     */
    public void processConfigurationClass(Class<?> configClass) {
        ComponentScan merged = MergedAnnotationResolver.find(configClass, ComponentScan.class);
        if (merged == null) {
            return;
        }
        LinkedHashSet<String> basePackages = new LinkedHashSet<>();
        basePackages.addAll(Arrays.asList(merged.value()));
        basePackages.addAll(Arrays.asList(merged.basePackages()));
        for (Class<?> markerClass : merged.basePackageClasses()) {
            // Class.getPackage() is null for an array type/primitive/void -- skip rather than
            // fold a null entry into the scan set (T-03-31).
            Package markerPackage = markerClass.getPackage();
            if (markerPackage != null) {
                basePackages.add(markerPackage.getName());
            }
        }
        if (basePackages.isEmpty()) {
            // Default to the package of the configuration class, exactly as before.
            basePackages.add(configClass.getPackage().getName());
        }
        ComponentScanner scanner = new ComponentScanner(this);
        scanner.scanPackages(basePackages.toArray(new String[0]));
    }
}
