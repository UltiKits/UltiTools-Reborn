package com.ultikits.ultitools.context;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Component;
import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.annotations.EventListener;
import com.ultikits.ultitools.annotations.Service;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Component scanner to find and register components.
 * <br>
 * 组件扫描器，用于查找和注册组件。
 */
public class ComponentScanner {
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
        } catch (Exception e) {
            // Log warning and continue
            System.err.println("Failed to scan package: " + basePackage + ", " + e.getMessage());
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
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        // Ignore and continue
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to scan JAR for package: " + basePackage + ", " + e.getMessage());
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
                    } catch (ClassNotFoundException e) {
                        // Ignore and continue
                    }
                }
            }
        }
    }

    /**
     * Process a class for component annotations.
     * <br>
     * 处理类的组件注解。
     */
    private void processClass(Class<?> clazz) {
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
     * <br>
     * 根据 @ConditionalOnConfig 检查类是否应被注册。
     *
     * @param clazz the class to check
     * @return true if the class should be registered
     */
    private boolean shouldRegister(Class<?> clazz) {
        ConditionalOnConfig condition = clazz.getAnnotation(ConditionalOnConfig.class);
        if (condition == null) {
            return true;
        }

        // Retrieve the plugin from the container
        UltiToolsPlugin plugin = null;
        try {
            plugin = container.getBean(UltiToolsPlugin.class);
        } catch (Exception e) {
            // No plugin in container — skip conditional (register by default)
            return true;
        }

        if (plugin == null || plugin.getResourceFolderPath() == null) {
            return true;
        }

        // Load the referenced config file from the plugin's config folder
        File configFile = new File(plugin.getResourceFolderPath(), condition.value());
        if (!configFile.exists()) {
            // Config file doesn't exist yet — feature disabled
            return condition.negate();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        boolean value = yaml.getBoolean(condition.path(), false);
        return condition.negate() ? !value : value;
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
     * Check if class has meta-component annotation.
     * <br>
     * 检查类是否有元组件注解。
     */
    private boolean hasComponentAnnotation(Class<?> clazz) {
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Component.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register a component class.
     * <br>
     * 注册组件类。
     */
    private void registerComponent(Class<?> clazz) {
        try {
            String beanName = getBeanName(clazz);
            BeanDefinition definition = new BeanDefinition(clazz, beanName);
            container.registerBeanDefinition(beanName, definition);
        } catch (Exception e) {
            System.err.println("Failed to register component: " + clazz.getName() + ", " + e.getMessage());
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
            System.err.println("Failed to register configuration: " + clazz.getName() + ", " + e.getMessage());
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
            System.err.println("Failed to process bean method: " + method.getName() + ", " + e.getMessage());
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
