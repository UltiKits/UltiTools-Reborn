package com.ultikits.ultitools.context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.exceptions.ContainerException;
import com.ultikits.ultitools.exceptions.ErrorCode;

import org.jetbrains.annotations.ApiStatus;

/**
 * Component scanner to find and register components.
 * <br>
 * 组件扫描器，用于查找和注册组件。
 */
@ApiStatus.Internal
public class ComponentScanner {
    private static final Logger LOGGER = Logger.getLogger(ComponentScanner.class.getName());

    private final SimpleContainer container;

    public ComponentScanner(SimpleContainer container) {
        this.container = container;
    }

    /**
     * Scan packages for components.
     * <br>
     * 扫描包以查找组件。
     *
     * @param basePackages packages to scan <br> 要扫描的包
     */
    public void scanPackages(String... basePackages) {
        for (String basePackage : basePackages) {
            scanPackage(basePackage);
        }
    }

    /**
     * Scan a single package for components.
     * <br>
     * 扫描单个包以查找组件。
     *
     * @param basePackage package to scan <br> 要扫描的包
     */
    public void scanPackage(String basePackage) {
        try {
            ClassLoader classLoader = container.getClassLoader() != null ?
                container.getClassLoader() : UltiTools.getJavaPluginClassLoader();

            String path = basePackage.replace('.', '/');
            URL resource = classLoader.getResource(path);

            if (resource != null) {
                String protocol = resource.getProtocol();
                if ("file".equals(protocol)) {
                    // Classes on disk (development mode)
                    File directory = new File(resource.getFile());
                    if (directory.exists()) {
                        scanDirectory(directory, basePackage, classLoader);
                    }
                } else if ("jar".equals(protocol)) {
                    // Classes inside a JAR file (production mode)
                    scanJar(resource, basePackage, classLoader);
                }
            }
        } catch (ContainerException e) {
            // A @Final contract violation is a hard failure and must abort module loading - the
            // catch-all below would otherwise log it and let scanning continue as if nothing
            // happened. See issue #190.
            throw e;
        } catch (Exception e) {
            // Skip-and-continue: the scan moves on to the next package, so this is an
            // expected-shape event (a missing/unreadable package), not a registration
            // failure - logged at WARNING and never forwarded to the UltiPanel dashboard
            // (D-24).
            LOGGER.log(Level.WARNING, "Failed to scan package: " + basePackage, e);
        }
    }

    /**
     * Scan inside a JAR file for class files.
     * <br>
     * 扫描JAR文件中的类文件。
     */
    private void scanJar(URL resource, String basePackage, ClassLoader classLoader) {
        try {
            JarURLConnection jarConnection = (JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConnection.getJarFile();
            String packagePath = basePackage.replace('.', '/');

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith(packagePath) && entryName.endsWith(".class") && !entry.isDirectory()) {
                    String className = entryName
                            .replace('/', '.')
                            .replace(".class", "");
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        processClass(clazz);
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Skip-and-continue: the rest of the package still registers (D-25,
                        // D-26). LinkageError covers NoClassDefFoundError,
                        // UnsupportedClassVersionError and ExceptionInInitializerError with one
                        // clause - the same union scanDirectory uses below, so the same missing
                        // class behaves identically on both scan modes.
                        LOGGER.log(Level.WARNING, "Failed to load scanned class: " + className, e);
                    }
                }
            }
        } catch (IOException e) {
            // Skip-and-continue: the scan moves on to the next package (D-24).
            LOGGER.log(Level.WARNING, "Failed to scan JAR for package: " + basePackage, e);
        }
    }

    /**
     * Scan directory for class files.
     * <br>
     * 扫描目录以查找类文件。
     */
    private void scanDirectory(File directory, String packageName, ClassLoader classLoader) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file, packageName + "." + file.getName(), classLoader);
                } else if (file.getName().endsWith(".class")) {
                    String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        processClass(clazz);
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Skip-and-continue: the rest of the package still registers (D-25,
                        // D-26). Same union as scanJar's per-class catch above - the same
                        // missing/unlinkable class must behave identically on both scan modes,
                        // rather than one skipping a class and the other killing the package.
                        LOGGER.log(Level.WARNING, "Failed to load scanned class: " + className, e);
                    }
                }
            }
        }
    }

    /**
     * Process a class for component annotations.
     * <p>
     * Propagation rule (D-25), stated once here rather than duplicated at each per-class call
     * site in {@code scanJar}/{@code scanDirectory}: {@link ContainerException} is the single
     * type that propagates unconditionally out of this method and aborts the module's scan --
     * every other exception thrown while registering an individual class is logged and that one
     * class is skipped, and the rest of the package still registers. One type name is the whole
     * rule, so no allowlist has to be kept in sync. The two deliberate {@code ContainerException}
     * throws today are the {@code @Final} contract violation below and a malformed
     * {@code @AliasFor} declaration surfaced by {@link MergedAnnotationResolver} during the same
     * scan; neither may ever be caught by a blanket handler.
     * <br>
     * 处理类的组件注解。
     * <p>
     * 传播规则（D-25），在此统一声明一次，而不是在 {@code scanJar}/{@code scanDirectory}
     * 各自的逐类调用点重复：{@link ContainerException} 是唯一会无条件穿透本方法、中止模块扫描的
     * 类型；注册单个类时抛出的其他任何异常都会被记录，仅跳过该类，包内其余类照常注册。规则就是
     * 这一个类型名，因此无需维护任何白名单。当前两处刻意抛出 {@code ContainerException}
     * 的地方分别是下方的 {@code @Final} 契约违规检查，以及同一次扫描中由
     * {@link MergedAnnotationResolver} 发现的畸形 {@code @AliasFor} 声明；两者都绝不能被任何
     * 万能捕获吞掉。
     */
    private void processClass(Class<?> clazz) {
        // The @Final contract is checked before anything else, including @ConditionalOnConfig:
        // extending a sealed type is a violation whether or not this particular class ends up
        // registered. See issue #190.
        List<String> finalViolations = FinalContractValidator.validate(clazz);
        if (!finalViolations.isEmpty()) {
            StringBuilder message = new StringBuilder("@Final contract violation:");
            for (String violation : finalViolations) {
                message.append("\n  - ").append(violation);
            }
            throw new ContainerException(ErrorCode.BEAN_CREATION_FAILED, message.toString());
        }

        // Check @ConditionalOnConfig before registering anything
        if (!shouldRegister(clazz)) {
            return;
        }

        // Check for component annotations
        if (isComponent(clazz)) {
            registerComponent(clazz);
        }

        // Check for configuration annotations
        if (clazz.isAnnotationPresent(Configuration.class)) {
            registerConfiguration(clazz);
        }
    }

    /**
     * Check if a class should be registered based on @ConditionalOnConfig.
     * <p>
     * Delegates to {@link ConditionalRegistrationEvaluator}, the single shared implementation
     * of this decision (D-17) -- also consulted by {@code ListenerManager}'s package-scan
     * overload, so the annotation is honoured identically on both reflection paths.
     * <br>
     * 根据 @ConditionalOnConfig 检查类是否应被注册。
     * <p>
     * 委托给 {@link ConditionalRegistrationEvaluator}——该判定逻辑唯一的共享实现（D-17），
     * 同时也被 {@code ListenerManager} 的包扫描重载调用，从而使该注解在两条反射路径上表现一致。
     *
     * @param clazz the class to check
     * @return true if the class should be registered
     */
    private boolean shouldRegister(Class<?> clazz) {
        return ConditionalRegistrationEvaluator.shouldRegister(clazz, container);
    }

    /**
     * Check if class is a component.
     * <br>
     * 检查类是否是组件。
     */
    private boolean isComponent(Class<?> clazz) {
        return clazz.isAnnotationPresent(Component.class) ||
               clazz.isAnnotationPresent(Service.class) ||
               clazz.isAnnotationPresent(EventListener.class) ||
               hasComponentAnnotation(clazz);
    }

    /**
     * Check if class has a meta-component annotation, anywhere on its whole annotation tree.
     * <p>
     * Widened from the previous hand-written implementation (D-03, 03-02), which inspected only
     * the annotations declared directly on the class and only one meta-annotation level deep, so
     * a stereotype annotation composed two levels above {@code @Component} was not recognised.
     * {@link MergedAnnotationResolver#isPresent} walks the full annotation tree, so it now is.
     * This is intentional -- a composed stereotype annotation exists precisely to be recognised --
     * and is recorded against {@code COMPATIBILITY.md}'s behaviour-change criterion by 03-10.
     * <p>
     * <b>Exception: a {@link UltiToolsPlugin} subclass is never treated as a component here</b>,
     * regardless of what its annotation tree resolves to. {@code @UltiToolsModule} composes
     * {@code @Configuration}, which is itself meta-annotated {@code @Component} -- so an
     * unqualified whole-tree walk would also match every module's own main class. That class is
     * already constructed and registered as a singleton by {@code PluginManager} *before* this
     * scan ever runs (its `plugin.yml` metadata has to be read first), under its raw
     * {@code Class.getSimpleName()} as the bean name. {@code ComponentScanner}'s own bean-naming
     * convention decapitalizes that same name, so the two registrations never collide, and
     * treating the module main class as a scannable {@code @Component} would register a *second*
     * bean definition for it under the decapitalized name -- which {@code preInstantiateSingletons}
     * would then construct via reflection, re-running the module's entire no-arg constructor
     * (`plugin.yml` re-parse, resource re-copy, a second config-entity registration under an
     * orphaned plugin instance) a second time. Excluded here rather than in {@link #isComponent},
     * whose own four-way disjunction stays untouched (D-03 scope).
     * <br>
     * 检查类是否在其整棵注解树上的任意位置携带元组件注解。
     * <p>
     * 相较此前手写实现的扩展（D-03，03-02）：旧实现只检查类上直接声明的注解，且只向上遍历一层
     * 元注解，因此在 {@code @Component} 之上组合了两层的原型注解无法被识别。
     * {@link MergedAnnotationResolver#isPresent} 会遍历整棵注解树，因此现在可以被识别。这是刻意
     * 的——组合原型注解存在的意义正是要被识别——并由 03-10 记录进
     * {@code COMPATIBILITY.md} 的行为变更判定标准。
     * <p>
     * <b>例外：{@link UltiToolsPlugin} 子类在此处永远不会被视为组件</b>，无论其注解树解析结果如何。
     * {@code @UltiToolsModule} 组合了 {@code @Configuration}，而后者本身元注解了
     * {@code @Component}——因此不加限定的整树遍历也会匹配到每个模块自身的主类。而该类在本次扫描
     * 运行之前就已经被 {@code PluginManager} 构造并以单例注册（必须先读取其 `plugin.yml`
     * 元数据），使用它原始的 {@code Class.getSimpleName()} 作为 bean 名称。{@code ComponentScanner}
     * 自身的 bean 命名约定会把同一个名字首字母小写，因此这两次注册永远不会碰撞；若把模块主类也视为
     * 可扫描的 {@code @Component}，就会在首字母小写的名字下为它注册*第二个* bean 定义——之后
     * {@code preInstantiateSingletons} 会通过反射构造它，重新执行一遍整个无参构造函数
     * （重新解析 `plugin.yml`、重新复制资源、在一个孤儿插件实例下再注册一遍配置实体）。在此处
     * 排除，而非改动 {@link #isComponent} 自身的四路析取（保持 D-03 的范围不变）。
     */
    private boolean hasComponentAnnotation(Class<?> clazz) {
        if (UltiToolsPlugin.class.isAssignableFrom(clazz)) {
            return false;
        }
        return MergedAnnotationResolver.isPresent(clazz, Component.class);
    }

    /**
     * Register a component class.
     * <br>
     * 注册组件类。
     * <p>
     * For a class carrying {@code @CmdExecutor}, the {@code @CmdTarget} class-versus-method
     * composition is checked here - pure reflection, before {@code registerBeanDefinition}, and
     * inside this method's own try/catch - because this is the isolation primitive: a check that
     * lived in {@code processClass} (no try/catch, aborts the whole scan) or in the executor's
     * own constructor (runs inside {@code preInstantiateSingletons}, no per-bean isolation, would
     * fail the whole plugin) would take down every command in the module instead of just this
     * one class. See T-01-01b in this plan's threat model and D-03.
     * <br>
     * 对携带 @CmdExecutor 的类，在此处——纯反射、先于 registerBeanDefinition、且在本方法
     * 自身的 try/catch 内——检查 @CmdTarget 的类/方法组合，因为这里才是隔离原语：
     * 放在 processClass（无 try/catch，整次扫描中止）或执行器自身构造函数
     * （运行于 preInstantiateSingletons，无逐 bean 隔离，会拖垮整个插件）都会
     * 把爆炸半径从一个指令类扩大到整个模块。
     */
    private void registerComponent(Class<?> clazz) {
        try {
            if (clazz.isAnnotationPresent(CmdExecutor.class)) {
                List<String> violations = CmdTargetComposition.check(clazz);
                if (!violations.isEmpty()) {
                    for (String violation : violations) {
                        // A refusal is a registration failure (SEVERE), even though there is no
                        // exception object to attach - the class was refused, not merely skipped.
                        LOGGER.log(Level.SEVERE, "Refused to register command class due to "
                                + "ambiguous @CmdTarget composition: " + violation);
                    }
                    return;
                }
            }
            String beanName = getBeanName(clazz);
            BeanDefinition definition = new BeanDefinition(clazz, beanName);
            container.registerBeanDefinition(beanName, definition);
        } catch (Exception e) {
            // A registration failure, not a skip - reported to the panel (D-24).
            LOGGER.log(Level.SEVERE, "Failed to register component: " + clazz.getName(), e);
        }
    }

    /**
     * Register a configuration class.
     * <br>
     * 注册配置类。
     */
    private void registerConfiguration(Class<?> clazz) {
        try {
            String beanName = getBeanName(clazz);
            Object configInstance = clazz.getDeclaredConstructor().newInstance();
            container.registerSingleton(beanName, configInstance);

            // Process @Bean methods
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class)) {
                    processBeanMethod(configInstance, method);
                }
            }
        } catch (Exception e) {
            // A registration failure, not a skip - reported to the panel (D-24).
            LOGGER.log(Level.SEVERE, "Failed to register configuration: " + clazz.getName(), e);
        }
    }

    /**
     * Process @Bean method.
     * <br>
     * 处理@Bean方法。
     */
    private void processBeanMethod(Object configInstance, Method method) {
        try {
            method.setAccessible(true);
            Object bean = method.invoke(configInstance);
            String beanName = method.getName();
            container.registerSingleton(beanName, bean);
        } catch (Exception e) {
            // A registration failure, not a skip - reported to the panel (D-24).
            LOGGER.log(Level.SEVERE, "Failed to process bean method: " + method.getName(), e);
        }
    }

    /**
     * Get bean name from class.
     * <br>
     * 从类获取Bean名称。
     */
    private String getBeanName(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Component.class)) {
            Component component = clazz.getAnnotation(Component.class);
            if (!component.value().isEmpty()) {
                return component.value();
            }
        }
        if (clazz.isAnnotationPresent(Service.class)) {
            Service service = clazz.getAnnotation(Service.class);
            if (!service.value().isEmpty()) {
                return service.value();
            }
        }
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
