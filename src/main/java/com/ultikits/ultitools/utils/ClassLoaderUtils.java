package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;

import java.util.regex.Pattern;

/**
 * Utility class for ensuring proper class loader usage throughout the plugin.
 * All class loaders created in this plugin should use the JavaPlugin class loader as parent.
 * <br>
 * 类加载器实用工具类，确保插件中正确使用类加载器。
 * 此插件中创建的所有类加载器都应使用JavaPlugin类加载器作为父类加载器。
 */
public class ClassLoaderUtils {
    
    // 类名格式验证模式
    private static final Pattern VALID_CLASS_NAME_PATTERN = Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$");
    
    /**
     * Validate class name format.
     * <br>
     * 验证类名格式。
     *
     * <p><b>GEN-07 (D-13, since 6.3.0):</b> this method no longer calls into
     * {@link SecurityPolicy} at all -- that class's four name-based checks are now unconditional
     * no-ops (D-12), and the framework must not call a method it has just declared to do nothing.
     * {@link #VALID_CLASS_NAME_PATTERN} is the only check left here, and it is input validation,
     * not security: a {@code null} is guarded explicitly before the regex runs (a bare
     * {@code Pattern.matcher(null)} throws {@link NullPointerException} rather than failing to
     * match), so a {@code null}/blank/malformed class name is still rejected, just for a
     * different reason than before.</p>
     *
     * @param className class name to validate <br> 要验证的类名
     * @throws SecurityException if the class name is null or does not match the expected format
     *                           <br> 如果类名为 null 或不符合预期格式
     */
    private static void validateClassName(String className) throws SecurityException {
        if (className == null || !VALID_CLASS_NAME_PATTERN.matcher(className).matches()) {
            throw new SecurityException("Invalid class name format: " + className);
        }
    }
    
    /**
     * Get the JavaPlugin class loader.
     * This should be used as the parent class loader for any custom class loaders.
     * <br>
     * 获取JavaPlugin类加载器。
     * 这应该用作任何自定义类加载器的父类加载器。
     *
     * @return JavaPlugin class loader <br> JavaPlugin类加载器
     */
    public static ClassLoader getPluginClassLoader() {
        return UltiTools.getJavaPluginClassLoader();
    }
    
    /**
     * Load a class using the plugin class loader with security validation.
     * <br>
     * 使用插件类加载器安全地加载类。
     *
     * @param className class name <br> 类名
     * @return loaded class <br> 加载的类
     * @throws ClassNotFoundException if class not found <br> 如果找不到类
     * @throws SecurityException if class is dangerous <br> 如果类是危险的
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException, SecurityException {
        validateClassName(className);
        try {
            return getPluginClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to load class: " + className, e);
        }
    }
    
    /**
     * Load a class using the plugin class loader with initialization control and security validation.
     * <br>
     * 使用插件类加载器安全地加载类，并控制初始化。
     *
     * @param className class name <br> 类名
     * @param initialize whether to initialize the class <br> 是否初始化类
     * @return loaded class <br> 加载的类
     * @throws ClassNotFoundException if class not found <br> 如果找不到类
     * @throws SecurityException if class is dangerous <br> 如果类是危险的
     */
    // codacy:ignore - Reflection is required for plugin framework class loading, className is validated
    public static Class<?> loadClass(String className, boolean initialize) throws ClassNotFoundException, SecurityException {
        validateClassName(className);
        try {
            return Class.forName(className, initialize, getPluginClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to load class: " + className, e);
        }
    }
    
    /**
     * Safely load a class that extends UltiToolsPlugin.
     * This method provides additional validation for plugin classes.
     * <br>
     * 安全地加载继承自UltiToolsPlugin的类。
     * 此方法为插件类提供额外的验证。
     *
     * @param className class name <br> 类名
     * @return loaded plugin class <br> 加载的插件类
     * @throws ClassNotFoundException if class not found <br> 如果找不到类
     * @throws SecurityException if class is dangerous or invalid <br> 如果类是危险的或无效的
     */
    public static Class<?> loadPluginClass(String className) throws ClassNotFoundException, SecurityException {
        // 额外验证插件类必须在ultikits包下
        if (!className.startsWith("com.ultikits.")) {
            throw new SecurityException("Plugin class must be in com.ultikits package: " + className);
        }
        
        Class<?> clazz = loadClass(className);
        
        // 验证类是否是UltiToolsPlugin的子类
        try {
            Class<?> pluginBaseClass = getPluginClassLoader().loadClass("com.ultikits.ultitools.abstracts.UltiToolsPlugin");
            if (!pluginBaseClass.isAssignableFrom(clazz)) {
                throw new SecurityException("Class is not a valid UltiToolsPlugin: " + className);
            }
        } catch (ClassNotFoundException e) {
            throw new SecurityException("Cannot validate plugin class hierarchy: " + className, e);
        }
        
        return clazz;
    }
    
    /**
     * GEN-07 (D-14) package-boundary bridge into the package-private {@code ClassloadFilterAudit}
     * evaluator, for {@code PluginManager} (a different package, {@code manager}) to record what
     * the removed classload filter layers would have refused for {@code className} during
     * {@code moduleName}'s scan. {@code ClassloadFilterAudit} stays package-private per D-14 (its
     * four relocated lists must not become part of the public surface); this method is the one
     * public seam a caller outside {@code utils} needs, mirroring the position of the
     * {@link #loadClass(String)} call site it is always paired with. Purely observational: never
     * throws, never refuses, and does not affect whether {@code className} loads.
     * <br>
     * GEN-07（D-14）跨包桥接：让 {@code PluginManager}（不同包 {@code manager}）能够记录被移除的
     * 类加载过滤层原本会对 {@code moduleName} 扫描中的 {@code className} 做出什么裁决。
     * {@code ClassloadFilterAudit} 依 D-14 保持包私有；这是包外调用方唯一需要的公开入口，
     * 总是与其配对的 {@link #loadClass(String)} 调用点放在一起。纯观察性：从不抛出异常，
     * 从不拒绝，也不影响 {@code className} 是否被加载。
     *
     * @param moduleName the module (or scan unit) currently being evaluated <br> 正在评估的模块
     * @param className  the class name being evaluated <br> 正在评估的类名
     * @since 6.3.0
     */
    public static void recordClassloadFilterAudit(String moduleName, String className) {
        ClassloadFilterAudit.record(moduleName, className);
    }

    /**
     * GEN-07 (D-14) package-boundary bridge: emits the package-private
     * {@code ClassloadFilterAudit}'s one-INFO-per-module summary for {@code moduleName}, always --
     * even when nothing was recorded (a module the removed layers would have refused nothing from
     * is itself the measurement, not silence). Call once, at the same point
     * {@code ModuleScanDiagnostics.emitSummary} is called for the same module, so the two
     * diagnostics land together.
     * <br>
     * GEN-07（D-14）跨包桥接：为 {@code moduleName} 触发包私有 {@code ClassloadFilterAudit} 的
     * 单条 INFO 汇总——始终触发，即便没有记录到任何内容（被移除的层原本不会拒绝任何东西，
     * 这个「零」本身就是度量结果，而非沉默）。应在与该模块的 {@code ModuleScanDiagnostics.emitSummary}
     * 同一位置调用一次，使两种诊断一并落地。
     *
     * @param moduleName the module whose scan just finished <br> 刚完成扫描的模块
     * @since 6.3.0
     */
    public static void emitClassloadFilterAuditSummary(String moduleName) {
        ClassloadFilterAudit.emitSummary(moduleName);
    }

    /**
     * Validate that a class loader has the correct parent hierarchy.
     * <br>
     * 验证类加载器具有正确的父类层次结构。
     *
     * @param classLoader class loader to validate <br> 要验证的类加载器
     * @return true if valid, false otherwise <br> 如果有效则为true，否则为false
     */
    public static boolean validateClassLoaderHierarchy(ClassLoader classLoader) {
        ClassLoader pluginClassLoader = getPluginClassLoader();
        ClassLoader current = classLoader;
        
        // Walk up the parent chain to find the plugin class loader
        while (current != null) {
            if (current == pluginClassLoader) {
                return true;
            }
            current = current.getParent();
        }
        
        return false;
    }
}
