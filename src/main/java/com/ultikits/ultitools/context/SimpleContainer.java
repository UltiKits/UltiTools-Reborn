package com.ultikits.ultitools.context;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.*;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Simple dependency injection container to replace Spring ApplicationContext.
 * <br>
 * 简单的依赖注入容器，用于替换Spring ApplicationContext。
 */
public class SimpleContainer {
    private final Map<String, Object> singletons = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Object>> suppliers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> typeMappings = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<Object>> typeSuppliers = new ConcurrentHashMap<>();
    private final Map<String, BeanScope> beanScopes = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> beanTypes = new ConcurrentHashMap<>();
    private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();
    private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentHashMap<>();
    private final Set<String> currentlyCreating = new HashSet<>();
    private SimpleContainer parent;
    private ClassLoader classLoader;
    private boolean isStarted = false;

    public enum BeanScope {
        SINGLETON, PROTOTYPE
    }

    public SimpleContainer() {
    }

    public SimpleContainer(SimpleContainer parent) {
        this.parent = parent;
    }

    /**
     * Register a singleton instance.
     * <br>
     * 注册单例实例。
     *
     * @param name instance name <br> 实例名称
     * @param instance instance object <br> 实例对象
     */
    public void registerSingleton(String name, Object instance) {
        singletons.put(name, instance);
        typeMappings.put(instance.getClass(), instance);
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
    public <T> void registerTypeSupplier(Class<T> type, Supplier<T> supplier) {
        typeSuppliers.put(type, () -> supplier.get());
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
        // Check if currently creating (circular dependency detection)
        if (currentlyCreating.contains(name)) {
            throw new RuntimeException("Circular dependency detected for bean: " + name);
        }

        Object bean = singletons.get(name);
        if (bean != null) {
            return bean;
        }

        // Check bean definition
        BeanDefinition definition = beanDefinitions.get(name);
        if (definition != null) {
            return createBean(name, definition);
        }

        Supplier<Object> supplier = suppliers.get(name);
        if (supplier != null) {
            bean = supplier.get();
            BeanScope scope = beanScopes.getOrDefault(name, BeanScope.SINGLETON);
            if (scope == BeanScope.SINGLETON) {
                singletons.put(name, bean);
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
            bean = supplier.get();
            typeMappings.put(type, bean);
            return (T) bean;
        }

        // Check bean definitions and create bean if found
        String beanName = getBeanName(type);
        BeanDefinition definition = beanDefinitions.get(beanName);
        if (definition != null) {
            bean = createBean(beanName, definition);
            return (T) bean;
        }

        if (parent != null) {
            return parent.getBean(type);
        }

        return null;
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
        
        // Check suppliers by creating instances (this might be expensive)
        for (Map.Entry<String, Supplier<Object>> entry : suppliers.entrySet()) {
            try {
                Object bean = entry.getValue().get();
                if (type.isInstance(bean)) {
                    beanNames.add(entry.getKey());
                }
            } catch (Exception e) {
                // Ignore failed instantiation
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
        singletons.clear();
        suppliers.clear();
        typeMappings.clear();
        typeSuppliers.clear();
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
        // No-op for now - in Spring this would trigger initialization
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
        // No-op for now
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
    }

    /**
     * Create bean from definition.
     * <br>
     * 从定义创建Bean。
     *
     * @param name bean name <br> Bean名称
     * @param definition bean definition <br> Bean定义
     * @return created bean <br> 创建的Bean
     */
    private Object createBean(String name, BeanDefinition definition) {
        try {
            currentlyCreating.add(name);

            Object bean;
            if (definition.getFactoryMethod() != null) {
                // Factory method creation
                bean = definition.getFactoryMethod().invoke(definition.getFactoryBean());
            } else {
                // Constructor creation
                Class<?> beanClass = definition.getBeanClass();
                Object[] constructorArgs = definition.getConstructorArgValues();
                
                if (constructorArgs != null && constructorArgs.length > 0) {
                    // Find matching constructor
                    Class<?>[] paramTypes = new Class[constructorArgs.length];
                    for (int i = 0; i < constructorArgs.length; i++) {
                        paramTypes[i] = constructorArgs[i].getClass();
                    }
                    Constructor<?> constructor = beanClass.getDeclaredConstructor(paramTypes);
                    constructor.setAccessible(true);
                    bean = constructor.newInstance(constructorArgs);
                } else {
                    Constructor<?> constructor = beanClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    bean = constructor.newInstance();
                }
            }

            // Apply bean post processors before initialization
            for (BeanPostProcessor processor : beanPostProcessors) {
                bean = processor.postProcessBeforeInitialization(bean, name);
            }

            // Autowire dependencies
            getAutowireCapableBeanFactory().autowireBean(bean);

            // Apply bean post processors after initialization
            for (BeanPostProcessor processor : beanPostProcessors) {
                bean = processor.postProcessAfterInitialization(bean, name);
            }

            // Store singleton
            if (definition.isSingleton()) {
                singletons.put(name, bean);
                typeMappings.put(definition.getBeanClass(), bean);
            }

            return bean;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create bean: " + name, e);
        } finally {
            currentlyCreating.remove(name);
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
     * <br>
     * 处理配置类。
     *
     * @param configClass configuration class <br> 配置类
     */
    public void processConfigurationClass(Class<?> configClass) {
        ComponentScanner scanner = new ComponentScanner(this);
        if (configClass.isAnnotationPresent(ComponentScan.class)) {
            ComponentScan componentScan = configClass.getAnnotation(ComponentScan.class);
            String[] basePackages = componentScan.value();
            if (basePackages.length == 0) {
                basePackages = componentScan.basePackages();
            }
            if (basePackages.length == 0) {
                // Default to the package of the configuration class
                basePackages = new String[]{configClass.getPackage().getName()};
            }
            scanner.scanPackages(basePackages);
        }
    }
}
