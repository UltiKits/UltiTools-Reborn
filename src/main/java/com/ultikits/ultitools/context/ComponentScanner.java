package com.ultikits.ultitools.context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Arrays;
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
import com.ultikits.ultitools.utils.ModuleScanDiagnostics;

import org.jetbrains.annotations.ApiStatus;

/**
 * Component scanner to find and register components.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // IoC container invokes @Bean factory methods -- see 08-GATE05-TRIAGE.md
@ApiStatus.Internal
public class ComponentScanner {
    private static final Logger LOGGER = Logger.getLogger(ComponentScanner.class.getName());

    private final SimpleContainer container;

    public ComponentScanner(SimpleContainer container) {
        this.container = container;
    }

    /**
     * Scan packages for components.
     *
     * @param basePackages packages to scan
     */
    public void scanPackages(String... basePackages) {
        for (String basePackage : basePackages) {
            scanPackage(basePackage);
        }
    }

    /**
     * Scan a single package for components.
     *
     * @param basePackage package to scan
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
                        scanDirectory(directory, basePackage, classLoader, basePackage);
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
        } finally {
            // D-19: one SEVERE summary for this basePackage's scan, if (and only if) either
            // catch block below recorded a skipped class into the accumulator. A healthy scan
            // never invokes the emitter at all -- see ModuleScanDiagnostics' own class javadoc.
            ModuleScanDiagnostics.emitSummary(basePackage);
        }
    }

    /**
     * Scan inside a JAR file for class files.
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
                        // class behaves identically on both scan modes. D-19 escalates the
                        // diagnostics without overturning D-25/D-26's skip-and-continue: the
                        // WARNING stays exactly as it was, and this class is also accumulated so
                        // scanPackage's finally block can emit one loud, named summary for the
                        // whole module once its scan finishes.
                        ModuleScanDiagnostics.recordSkippedClass(basePackage, className, e);
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
     *
     * @param directory   the directory to scan
     * @param packageName the dotted package name for {@code directory}, growing with each
     *                    recursive descent into a subdirectory
     * @param classLoader the class loader to load discovered classes with
     * @param moduleName  the D-19 diagnostic identifier for this scan -- fixed at the original
     *                    {@code basePackage} for the whole recursion, deliberately NOT the
     *                    per-recursion {@code packageName} above, so a class three subdirectories
     *                    deep is still attributed to the same module {@code scanPackage}'s
     *                    {@code finally} block will emit a summary for
     */
    private void scanDirectory(File directory, String packageName, ClassLoader classLoader, String moduleName) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file, packageName + "." + file.getName(), classLoader, moduleName);
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
                        // D-19 escalates the diagnostics without overturning D-25/D-26's
                        // skip-and-continue -- see the matching comment in scanJar above.
                        ModuleScanDiagnostics.recordSkippedClass(moduleName, className, e);
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
     *
     * @param clazz the class to check
     * @return true if the class should be registered
     */
    private boolean shouldRegister(Class<?> clazz) {
        return ConditionalRegistrationEvaluator.shouldRegister(clazz, container);
    }

    /**
     * Check if class is a component.
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
     */
    private boolean hasComponentAnnotation(Class<?> clazz) {
        if (UltiToolsPlugin.class.isAssignableFrom(clazz)) {
            return false;
        }
        return MergedAnnotationResolver.isPresent(clazz, Component.class);
    }

    /**
     * Register a component class.
     * <p>
     * For a class carrying {@code @CmdExecutor}, the {@code @CmdTarget} class-versus-method
     * composition is checked here - pure reflection, before {@code registerBeanDefinition}, and
     * inside this method's own try/catch - because this is the isolation primitive: a check that
     * lived in {@code processClass} (no try/catch, aborts the whole scan) or in the executor's
     * own constructor (runs inside {@code preInstantiateSingletons}, no per-bean isolation, would
     * fail the whole plugin) would take down every command in the module instead of just this
     * one class. See T-01-01b in this plan's threat model and D-03.
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
        } catch (ContainerException e) {
            // A malformed @Bean name/value declaration (D-02/D-06) or a hard failure inside
            // registerSingleton (an unresolvable required @Autowired dependency, D-08, or an AOP
            // annotation registerSingleton can never honour, D-15) must abort the module's scan
            // like every other ContainerException (D-25) -- not be caught here and demoted to an
            // ordinary logged-and-skipped registration failure.
            throw e;
        } catch (Exception e) {
            // A registration failure, not a skip - reported to the panel (D-24).
            LOGGER.log(Level.SEVERE, "Failed to register configuration: " + clazz.getName(), e);
        }
    }

    /**
     * Process @Bean method.
     * <p>
     * The bean name is derived from {@code @Bean}'s {@code name()}/{@code value()} (D-06) via
     * {@link #resolveBeanNames(Method)}, falling back to the method's own name exactly as before
     * this attribute took effect. The <b>first</b> resolved name is registered through
     * {@link SimpleContainer#registerSingleton(String, Object)}, which fully assembles the
     * instance (D-14); any remaining names are bound as aliases to that <em>same, already
     * assembled</em> reference via the package-visible
     * {@link SimpleContainer#addSingleton(String, Object)} -- deliberately not a second
     * {@code registerSingleton} call per name, which after D-14 would re-run autowiring and
     * {@code @PostConstruct} once per alias on what should be one bean.
     */
    private void processBeanMethod(Object configInstance, Method method) {
        try {
            method.setAccessible(true);
            Object bean = method.invoke(configInstance);
            String[] beanNames = resolveBeanNames(method);
            String primaryName = beanNames[0];
            container.registerSingleton(primaryName, bean);
            if (beanNames.length > 1) {
                Object assembled = container.getBean(primaryName);
                for (int i = 1; i < beanNames.length; i++) {
                    container.addSingleton(beanNames[i], assembled);
                }
            }
        } catch (ContainerException e) {
            // A malformed @Bean name/value declaration (D-02/D-06) or a hard failure inside
            // registerSingleton (D-08/D-15) must abort the module's scan, not be demoted to an
            // ordinary registration failure (D-25).
            throw e;
        } catch (Exception e) {
            // A registration failure, not a skip - reported to the panel (D-24).
            LOGGER.log(Level.SEVERE, "Failed to process bean method: " + method.getName(), e);
        }
    }

    /**
     * Resolve the effective bean-name array for a {@code @Bean} method (D-06): {@code name()} if
     * non-empty, else {@code value()} if non-empty, else the method's own name as a
     * single-element array. {@code name()} and {@code value()} are mutual aliases -- declaring
     * both with different content is a malformed declaration and hard-fails naming the method
     * and both declared values, reusing the same {@link ContainerException#malformedAliasFor}
     * factory (and therefore the same message shape) plan 03-01 built for malformed
     * {@code @AliasFor} declarations, even though {@code @Bean} itself deliberately does not
     * declare a real {@code @AliasFor} between its two attributes (see this method's own class's
     * scope boundary). Declaring both with identical content is legal. Every resolved element
     * must be non-blank -- a blank or whitespace-only element is also a malformed declaration,
     * because a name that cannot name anything is not a usable third state between "declared" and
     * "absent" (D-02). No element is normalized: two names differing only by Unicode
     * normalization form are two distinct declared names, decided purely by
     * {@code String.equals}.
     *
     * @param method the {@code @Bean}-annotated factory method
     * @return the resolved name array; index 0 is the registered bean name, the rest are aliases
     */
    private String[] resolveBeanNames(Method method) {
        Bean beanAnnotation = method.getAnnotation(Bean.class);
        String[] name = beanAnnotation.name();
        String[] value = beanAnnotation.value();

        if (name.length > 0 && value.length > 0 && !Arrays.equals(name, value)) {
            throw ContainerException.malformedAliasFor(Bean.class, "name",
                    "@Bean method '" + method.getName() + "' declares both name=" + Arrays.toString(name)
                            + " and value=" + Arrays.toString(value) + " with different content -- "
                            + "name() and value() are mutual aliases and must agree, or only one "
                            + "should be declared");
        }

        String[] resolved;
        if (name.length > 0) {
            resolved = name;
        } else if (value.length > 0) {
            resolved = value;
        } else {
            resolved = new String[]{method.getName()};
        }

        for (int i = 0; i < resolved.length; i++) {
            String candidate = resolved[i];
            if (candidate == null || candidate.trim().isEmpty()) {
                throw ContainerException.malformedAliasFor(Bean.class, "name",
                        "@Bean method '" + method.getName() + "' declares a blank or whitespace-only "
                                + "name element at index " + i + " of " + Arrays.toString(resolved));
            }
        }

        return resolved;
    }

    /**
     * Get bean name from class.
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
