package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Security policy configuration for UltiTools class loading and reflection operations.
 * This class defines security rules and validation logic to prevent malicious code execution.
 * <br>
 * UltiTools类加载和反射操作的安全策略配置。
 * 此类定义安全规则和验证逻辑以防止恶意代码执行。
 *
 * @author UltiKits Security Team
 * @version 1.0.0
 */
public class SecurityPolicy {
    
    private static final Logger LOGGER = Logger.getLogger(SecurityPolicy.class.getName());

    // The four name-based filter lists formerly lived here. They moved to ClassloadFilterAudit
    // (same package) once addTrustedPackage/addDangerousClass became no-ops (D-12/D-14) -- see
    // that class's javadoc. isSafeClassName/isSafeParameterType read them via
    // ClassloadFilterAudit.<LIST_NAME> below, same-package access requiring no accessor method.

    /**
     * Validate if a class name is safe to load.
     * <br>
     * 验证类名是否可以安全加载。
     *
     * @param className class name to validate <br> 要验证的类名
     * @return true if safe, false otherwise <br> 如果安全则为true，否则为false
     */
    public static boolean isSafeClassName(String className) {
        if (className == null || className.trim().isEmpty()) {
            logSecurityViolation("Empty or null class name", className);
            return false;
        }

        // Delegates to ClassloadFilterAudit.classify() -- same package, same four layers, same
        // evaluation order -- rather than owning the lists directly. The lists themselves now
        // live in ClassloadFilterAudit (D-14).
        ClassloadFilterAudit.Layer refusingLayer = ClassloadFilterAudit.classify(className);
        if (refusingLayer != null) {
            logSecurityViolation("Class name " + refusingLayer.describe(), className);
            return false;
        }

        return true;
    }
    
    /**
     * Validate if a parameter type is safe for reflection operations.
     * <br>
     * 验证参数类型是否对反射操作安全。
     *
     * @param clazz parameter class <br> 参数类
     * @return true if safe, false otherwise <br> 如果安全则为true，否则为false
     */
    public static boolean isSafeParameterType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        
        String className = clazz.getName();
        
        // 允许基本数据类型和包装类
        if (clazz.isPrimitive() || 
            className.startsWith("java.lang.String") ||
            className.startsWith("java.lang.Integer") ||
            className.startsWith("java.lang.Long") ||
            className.startsWith("java.lang.Double") ||
            className.startsWith("java.lang.Float") ||
            className.startsWith("java.lang.Boolean") ||
            className.startsWith("java.lang.Character") ||
            className.startsWith("java.lang.Byte") ||
            className.startsWith("java.lang.Short")) {
            return true;
        }
        
        // 允许常用集合类型
        if (className.startsWith("java.util.List") ||
            className.startsWith("java.util.Map") ||
            className.startsWith("java.util.Set") ||
            className.startsWith("java.util.ArrayList") ||
            className.startsWith("java.util.HashMap") ||
            className.startsWith("java.util.HashSet")) {
            return true;
        }
        
        // 允许信任的包
        for (String trustedPrefix : ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES) {
            if (className.startsWith(trustedPrefix)) {
                return true;
            }
        }
        
        logSecurityViolation("Unsafe parameter type", className);
        return false;
    }
    
    /**
     * Validate file size and structure for security.
     * <br>
     * 验证文件大小和结构的安全性。
     *
     * @param fileSize file size in bytes <br> 文件大小（字节）
     * @param entryCount number of entries in archive <br> 归档中的条目数
     * @return true if safe, false otherwise <br> 如果安全则为true，否则为false
     */
    public static boolean isSafeFileStructure(long fileSize, int entryCount) {
        // 文件大小限制：100MB
        long maxFileSize = 100 * 1024 * 1024;
        if (fileSize > maxFileSize) {
            logSecurityViolation("File too large", "Size: " + fileSize + " bytes");
            return false;
        }
        
        // 条目数量限制：10000
        int maxEntries = 10000;
        if (entryCount > maxEntries) {
            logSecurityViolation("Too many entries", "Count: " + entryCount);
            return false;
        }
        
        return true;
    }

    /**
     * Validate that a module JAR is safe to hand to a classloader: it must exist, be a regular
     * file, have a {@code .jar} suffix (case-insensitive), open as a readable archive, and pass
     * the size/entry-count limits enforced by {@link #isSafeFileStructure(long, int)}.
     * <br>
     * 校验一个模块 JAR 是否可以安全地交给类加载器：文件必须存在、是常规文件、
     * 文件名以 {@code .jar} 结尾（大小写不敏感）、能作为可读归档打开，并通过
     * {@link #isSafeFileStructure(long, int)} 的大小/条目数限制。
     *
     * <p>This is a static, instance-free rule with no dependency on Bukkit — it must be callable
     * before the plugin's {@code onEnable()} has built any manager, and from a plain JUnit test
     * with no server running. Callers are responsible for skipping (not adding) a JAR this method
     * rejects; this method never throws for a JAR it judges unsafe, it only returns {@code false}.</p>
     *
     * @param jarFile candidate module jar file <br> 候选模块 jar 文件
     * @return true if the jar may be handed to a classloader, false otherwise
     *         <br> 如果该 jar 可以交给类加载器则为 true，否则为 false
     * @since 6.3.0
     */
    public static boolean isValidModuleJar(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.isFile()) {
            return false;
        }

        // 检查文件扩展名
        if (!jarFile.getName().toLowerCase().endsWith(".jar")) {
            return false;
        }

        // 验证jar文件结构
        try (JarFile jar = new JarFile(jarFile)) {
            // UltiTools modules don't require plugin.yml — they're identified by @UltiToolsModule

            // 统计条目数量
            Enumeration<JarEntry> entries = jar.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                entries.nextElement();
                entryCount++;
            }

            // 使用 SecurityPolicy 验证文件结构
            return isSafeFileStructure(jarFile.length(), entryCount);
        } catch (IOException e) {
            logSecurityViolation("Failed to validate jar file", jarFile.getName());
            return false;
        }
    }

    /**
     * Add a trusted package prefix at runtime.
     * <br>
     * 在运行时添加信任的包前缀。
     *
     * @param packagePrefix package prefix to trust <br> 要信任的包前缀
     */
    public static void addTrustedPackage(String packagePrefix) {
        if (packagePrefix != null && !packagePrefix.trim().isEmpty()) {
            ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES.add(packagePrefix);
            LOGGER.log(Level.INFO, "Added trusted package: " + packagePrefix);
        }
    }
    
    /**
     * Add a dangerous class to the blacklist.
     * <br>
     * 将危险类添加到黑名单。
     *
     * @param className class name to blacklist <br> 要加入黑名单的类名
     */
    public static void addDangerousClass(String className) {
        if (className != null && !className.trim().isEmpty()) {
            ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES.add(className);
            LOGGER.log(Level.INFO, "Added dangerous class: " + className);
        }
    }
    
    /**
     * Log security violation for monitoring and audit purposes.
     * <br>
     * 记录安全违规以用于监控和审计。
     *
     * @param reason violation reason <br> 违规原因
     * @param details violation details <br> 违规详情
     */
    private static void logSecurityViolation(String reason, String details) {
        String message = String.format("[UltiTools-Security] Violation: %s - %s", reason, details);
        LOGGER.log(Level.WARNING, message);
    }
    
    /**
     * Get current security policy information.
     * <br>
     * 获取当前安全策略信息。
     *
     * @return security policy summary <br> 安全策略摘要
     */
    public static String getSecurityPolicySummary() {
        return String.format(
            "UltiTools Security Policy - Trusted packages: %d, Dangerous classes: %d, Dangerous packages: %d",
            ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES.size(),
            ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES.size(),
            ClassloadFilterAudit.DANGEROUS_PACKAGE_PREFIXES.size()
        );
    }
}
