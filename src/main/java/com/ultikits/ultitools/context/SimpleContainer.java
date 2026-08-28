package com.ultikits.ultitools.context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.jetbrains.annotations.ApiStatus;

/**
 * Simple dependency injection container to replace Spring ApplicationContext.
 * <br>
 * 简单的依赖注入容器，用于替换Spring ApplicationContext。
 */
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
    // Cache for supplier types to avoid instantiation in getBeanNamesForType
    private final Map<String, Class<?>> supplierTypes = new ConcurrentHashMap<>();
    private SimpleContainer parent;
    private ClassLoader classLoader;
    private boolean isStarted = false;
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
     * <br>
     * 从三级缓存获取单例。此方法通过提前暴露单例来实现循环依赖解析。
     *
     * @param beanName bean name <br> Bean名称
     * @param allowEarlyReference whether to allow early reference (level 2/3 cache) <br> 是否允许早期引用（二/三级缓存）
     * @return singleton instance or null if not found <br> 单例实例，如果未找到则返回null
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
     * <br>
     * 向三级缓存添加单例工厂以支持早期引用。
     *
     * @param beanName bean name <br> Bean名称
     * @param singletonFactory factory for creating early reference <br> 创建早期引用的工厂
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
     * <br>
     * 向一级缓存添加完全初始化的单例。从二级和三级缓存清除。
     *
     * @param beanName bean name <br> Bean名称
     * @param singletonObject fully initialized singleton <br> 完全初始化的单例
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
     * 注册单例实例，在存储前完成完整装配。运行与 {@link #createBean} 为容器自行构造的 Bean
     * 所执行的同一套顺序——postProcessBeforeInitialization -> autowireBean -> @PostConstruct ->
     * postProcessAfterInitialization——且无条件执行，与 {@link #refresh()} 是否已调用无关。
     * 当 {@code instance} 的类携带其永远无法生效的 {@code @Transactional} 或
     * {@code @ExceptionCatch}（方法级或类级）时直接拒绝注册（D-15）。
     *
     * @param name instance name <br> 实例名称
     * @param instance instance object <br> 实例对象
     * @throws com.ultikits.ultitools.exceptions.ContainerException if {@code instance}'s class
     *         carries an AOP annotation it can never honour (D-15), or if a required
     *         {@code @Autowired} dependency on it cannot be resolved (D-08)
     */
    public void registerSingleton(String name, Object instance) {
        refuseIfAopAnnotated(instance);

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
     * <br>
     * 注册供应商用于延迟初始化。
     *
     * @param name supplier name <br> 供应商名称
     * @param supplier supplier function <br> 供应商函数
     */
    public void registerSupplier(String name, Supplier<Object> supplier) {
        suppliers.put(name, supplier);
    }

    /**
     * Register a supplier for lazy initialization with type information.
     * <br>
     * 注册带类型信息的供应商用于延迟初始化。
     *
     * @param name supplier name <br> 供应商名称
     * @param supplier supplier function <br> 供应商函数
     * @param type the type of bean this supplier produces <br> 供应商生成的Bean类型
     */
    public void registerSupplier(String name, Supplier<Object> supplier, Class<?> type) {
        suppliers.put(name, supplier);
        supplierTypes.put(name, type);
    }

    /**
     * Register a type mapping.
     * <br>
     * 注册类型映射。
     *
     * @param type class type <br> 类类型
     * @param instance instance object <br> 实例对象
     */
    public <T> void registerType(Class<T> type, T instance) {
        typeMappings.put(type, instance);
    }

    /**
     * Register a type supplier.
     * <br>
     * 注册类型供应商。
     *
     * @param type class type <br> 类类型
     * @param supplier supplier function <br> 供应商函数
     */
    @SuppressWarnings("unchecked")
    public <T> void registerTypeSupplier(Class<T> type, Supplier<T> supplier) {
        typeSuppliers.put(type, (Supplier<Object>) supplier);
    }

    /**
     * Get bean by name.
     * <br>
     * 通过名称获取Bean。
     *
     * @param name bean name <br> Bean名称
     * @return bean instance <br> Bean实例
     */
    public Object getBean(String name) {
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
                    throw new RuntimeException("Circular dependency detected for prototype bean '" + name + 
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
     * <br>
     * 通过类型获取Bean。
     *
     * @param type bean type <br> Bean类型
     * @return bean instance <br> Bean实例
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

        // Check bean definitions and create bean if found
        String beanName = getBeanName(type);
        BeanDefinition definition = beanDefinitions.get(beanName);
        if (definition != null) {
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
        // dedups a definition-backed singleton that has already been instantiated, and it reads
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
     * <br>
     * 通过名称和类型获取Bean。
     *
     * @param name bean name <br> Bean名称
     * @param type bean type <br> Bean类型
     * @return bean instance <br> Bean实例
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
     * <br>
     * 获取指定类型的Bean名称。
     *
     * @param type bean type <br> Bean类型
     * @return bean names <br> Bean名称数组
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
     * <br>
     * 获取自动装配Bean工厂。
     *
     * @return autowire factory <br> 自动装配工厂
     */
    public AutowireFactory getAutowireCapableBeanFactory() {
        return new AutowireFactory(this);
    }

    /**
     * Close the container.
     * <br>
     * 关闭容器。
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
        isStarted = false;
        
        LOGGER.info("Container closed.");
    }

    /**
     * Check if container contains bean.
     * <br>
     * 检查容器是否包含Bean。
     *
     * @param name bean name <br> Bean名称
     * @return true if contains <br> 如果包含则返回true
     */
    public boolean containsBean(String name) {
        return singletons.containsKey(name) || suppliers.containsKey(name) ||
                beanDefinitions.containsKey(name) ||
                (parent != null && parent.containsBean(name));
    }

    /**
     * Register bean with constructor arguments.
     * <br>
     * 使用构造器参数注册Bean。
     *
     * @param type bean type <br> Bean类型
     * @param constructorArgs constructor arguments <br> 构造器参数
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
            throw new RuntimeException("Failed to register bean: " + type.getName(), e);
        }
    }

    /**
     * Generate bean name for class.
     * <br>
     * 为类生成Bean名称。
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
     * Refresh the container.
     * <br>
     * 刷新容器。
     */
    public void refresh() {
        preInstantiateSingletons();
    }

    /**
     * Get all singleton bean instances registered in this container (not including parent).
     * <p>
     * 获取此容器中注册的所有单例 Bean 实例（不包含父容器）。
     *
     * @return unmodifiable collection of singleton beans
     */
    public java.util.Collection<Object> getSingletonValues() {
        return java.util.Collections.unmodifiableCollection(singletonObjects.values());
    }

    /**
     * Set display name.
     * <br>
     * 设置显示名称。
     *
     * @param displayName display name <br> 显示名称
     */
    public void setDisplayName(String displayName) {
        // No-op for now
    }

    /**
     * Set ID.
     * <br>
     * 设置ID。
     *
     * @param id container ID <br> 容器ID
     */
    public void setId(String id) {
        // No-op for now
    }

    /**
     * Set class loader.
     * <br>
     * 设置类加载器。
     *
     * @param classLoader class loader <br> 类加载器
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Register shutdown hook.
     * <br>
     * 注册关闭钩子。
     */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * Set parent container.
     * <br>
     * 设置父容器。
     *
     * @param parent parent container <br> 父容器
     */
    public void setParent(SimpleContainer parent) {
        this.parent = parent;
    }

    /**
     * Get bean factory.
     * <br>
     * 获取Bean工厂。
     *
     * @return bean factory <br> Bean工厂
     */
    public BeanFactory getBeanFactory() {
        return new BeanFactory(this);
    }

    /**
     * Get class loader.
     * <br>
     * 获取类加载器。
     *
     * @return class loader <br> 类加载器
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
     * <br>
     * 注册Bean定义。
     *
     * @param name bean name <br> Bean名称
     * @param definition bean definition <br> Bean定义
     */
    public void registerBeanDefinition(String name, BeanDefinition definition) {
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
     * <br>
     * 从定义创建Bean。使用三级缓存支持 setter 注入的循环依赖解析。
     *
     * @param name bean name <br> Bean名称
     * @param definition bean definition <br> Bean定义
     * @return created bean <br> 创建的Bean
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
        } catch (Exception e) {
            // Clean up caches on failure
            singletonFactories.remove(name);
            earlySingletonObjects.remove(name);
            LOGGER.log(Level.SEVERE, "Failed to create bean: " + name, e);
            throw new RuntimeException("Failed to create bean: " + name, e);
        } finally {
            currentlyCreating.remove(name);
        }
    }

    /**
     * Create bean using constructor auto-wiring.
     * Resolves constructor parameters from the container.
     * <br>
     * 使用构造器自动装配创建Bean。从容器中解析构造器参数。
     *
     * @param beanClass the class to instantiate <br> 要实例化的类
     * @return new bean instance <br> 新的Bean实例
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
     * <br>
     * 为给定参数查找匹配的构造器。支持接口/父类参数类型和基本类型装箱。
     *
     * @param beanClass the class to find constructor for <br> 要查找构造器的类
     * @param args constructor arguments <br> 构造器参数
     * @return matching constructor <br> 匹配的构造器
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
     * <br>
     * 检查值是否可以分配给参数类型。处理基本类型、接口和继承。
     *
     * @param paramType the parameter type <br> 参数类型
     * @param value the value to check <br> 要检查的值
     * @return true if assignable <br> 如果可分配则返回true
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
     * <br>
     * 检查wrapper是否是primitive类型的包装类。
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
     * <br>
     * 调用带有@PostConstruct注解的方法。
     *
     * @param bean the bean instance <br> Bean实例
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
                    throw new RuntimeException("Failed to invoke @PostConstruct method: " + method.getName(), e);
                }
            }
        }
    }

    /**
     * Invoke methods annotated with @PreDestroy on a bean.
     * <br>
     * 调用Bean上带有@PreDestroy注解的方法。
     *
     * @param bean the bean instance <br> Bean实例
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
     * <br>
     * 添加Bean后处理器。
     *
     * @param processor bean post processor <br> Bean后处理器
     */
    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.add(processor);
    }

    /**
     * Sets the AOP proxy resolver for this container.
     * <p>
     * Must be called before {@link #refresh()}: the resolver participates in bean instantiation,
     * not in post-processing, so beans created earlier would not be proxied.
     * <p>
     * 必须在 refresh() 之前调用。解析器参与的是 bean 实例化而非后置处理，
     * 更早创建的 bean 不会被代理。
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
     * <br>
     * 获取所有Bean名称。
     *
     * @return bean names <br> Bean名称数组
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
     * <br>
     * 获取指定类型的Bean。
     *
     * @param type bean type <br> Bean类型
     * @return beans map <br> Bean映射
     */
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
     * <br>
     * 按 @Service 优先级排序获取指定类型的Bean。
     * 优先级高的Bean排在前面。
     *
     * @param type bean type <br> Bean类型
     * @return ordered list of beans <br> 排序后的Bean列表
     */
    public <T> List<T> getOrderedBeansOfType(Class<T> type) {
        Map<String, T> beans = getBeansOfType(type);
        List<T> result = new ArrayList<>(beans.values());
        result.sort((a, b) -> {
            int priorityA = getServicePriority(a.getClass());
            int priorityB = getServicePriority(b.getClass());
            return Integer.compare(priorityB, priorityA); // Descending order
        });
        return result;
    }

    /**
     * Get the highest priority bean of the given type.
     * <br>
     * 获取指定类型中优先级最高的Bean。
     *
     * @param type bean type <br> Bean类型
     * @return highest priority bean or null <br> 优先级最高的Bean或null
     */
    public <T> T getHighestPriorityBean(Class<T> type) {
        java.util.List<T> ordered = getOrderedBeansOfType(type);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /**
     * Get service priority from @Service annotation.
     * <br>
     * 从 @Service 注解获取服务优先级。
     *
     * @param clazz the class to check <br> 要检查的类
     * @return priority value (default 0) <br> 优先级值（默认0）
     */
    private int getServicePriority(Class<?> clazz) {
        Service service = clazz.getAnnotation(Service.class);
        return service != null ? service.priority() : 0;
    }

    /**
     * Check if bean is singleton.
     * <br>
     * 检查Bean是否是单例。
     *
     * @param name bean name <br> Bean名称
     * @return true if singleton <br> 如果是单例则返回true
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
     * <br>
     * 检查Bean是否是原型。
     *
     * @param name bean name <br> Bean名称
     * @return true if prototype <br> 如果是原型则返回true
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
     * <br>
     * 获取Bean类型。
     *
     * @param name bean name <br> Bean名称
     * @return bean type <br> Bean类型
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
     * <br>
     * 初始化所有单例。
     */
    public void preInstantiateSingletons() {
        String[] beanNames = getBeanDefinitionNames();
        for (String beanName : beanNames) {
            BeanDefinition definition = beanDefinitions.get(beanName);
            if (definition != null && definition.isSingleton() && !definition.isLazyInit()) {
                getBean(beanName);
            }
        }
    }

    /**
     * Start the container.
     * <br>
     * 启动容器。
     */
    public void start() {
        if (!isStarted) {
            preInstantiateSingletons();
            isStarted = true;
        }
    }

    /**
     * Stop the container.
     * <br>
     * 停止容器。
     */
    public void stop() {
        isStarted = false;
    }

    /**
     * Check if container is running.
     * <br>
     * 检查容器是否正在运行。
     *
     * @return true if running <br> 如果正在运行则返回true
     */
    public boolean isRunning() {
        return isStarted;
    }

    /**
     * Scan components in packages.
     * <br>
     * 扫描包中的组件。
     *
     * @param basePackages packages to scan <br> 要扫描的包
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
     * <br>
     * 处理配置类。
     * <p>
     * 通过 {@link MergedAnnotationResolver#find} 而非裸的
     * {@code configClass.getAnnotation(ComponentScan.class)} 读取 {@code @ComponentScan}——一个
     * 元注解了 {@code @UltiToolsModule} 的类，会通过其 {@code @AliasFor} 声明把
     * {@code scanBasePackages()}/{@code scanBasePackageClasses()} 的值带入合并后的
     * {@code @ComponentScan} 视图（D-01），裸查找会完全错过这一点。结果是在 {@code value()}、
     * {@code basePackages()} 以及 {@code basePackageClasses()} 指名的包之间做累加——按此声明顺序，
     * 重复项折叠为首次出现（GEN-06）——而不是在它们之间做首个匹配的选择。
     *
     * @param configClass configuration class <br> 配置类
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
