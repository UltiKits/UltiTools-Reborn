package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * <b>Provides NO runtime constraint on class loading or reflection (GEN-07, D-12, since 6.3.0).</b>
 * {@link #isSafeClassName(String)} and {@link #isSafeParameterType(Class)} now unconditionally
 * return {@code true}; {@link #addTrustedPackage(String)} and {@link #addDangerousClass(String)}
 * are no-ops. The three name-based filter layers this class used to enforce never protected
 * anything: they evaluated already-trusted, already-loaded classes on the framework's own
 * classpath, not arbitrary untrusted input, and the trusted-package whitelist alone would have
 * refused nearly every third-party module class ({@code TRUSTED_PACKAGE_PREFIXES} held no
 * third-party package). See the package-private {@code ClassloadFilterAudit} (D-14) for what
 * these removed layers would have refused, recorded as telemetry rather than enforced.
 * <p>
 * This class still performs plugin-scan-time JAR STRUCTURE filtering only:
 * {@link #isSafeFileStructure(long, int)} and {@link #isValidModuleJar(File)} are unchanged and
 * remain the framework's one genuine defense against a zip-bomb or oversized module JAR
 * (ROADMAP criterion 4).
 * <br>
 * <b>不再对类加载或反射提供任何运行时约束（GEN-07，D-12，自 6.3.0 起）。</b>
 * {@link #isSafeClassName(String)} 与 {@link #isSafeParameterType(Class)} 现在无条件返回
 * {@code true}；{@link #addTrustedPackage(String)} 与 {@link #addDangerousClass(String)} 变为空操作。
 * 该类曾经强制执行的三个基于名称的过滤层从未真正保护过任何东西——它们评估的是框架自身 classpath 上
 * 已经信任、已经加载的类，而非任意的不可信输入。此类仍然只在插件扫描阶段执行 JAR
 * 结构性过滤：{@link #isSafeFileStructure(long, int)} 与 {@link #isValidModuleJar(File)}
 * 保持不变，仍是框架对付超大或炸弹式 JAR 的唯一真实防线。
 *
 * @author UltiKits Security Team
 * @version 1.0.0
 */
public class SecurityPolicy {

    private static final Logger LOGGER = Logger.getLogger(SecurityPolicy.class.getName());

    // The four name-based filter lists formerly lived here. They moved to ClassloadFilterAudit
    // (same package) once addTrustedPackage/addDangerousClass became no-ops below (D-12/D-14) --
    // the audit now reflects exactly the shipped lists, never a caller's runtime mutation.

    /**
     * Fires the D-13 deprecation warning below at most once per JVM. Guards against the measured
     * call volume: isSafeClassName was reached from two PluginManager scan loops, each capped at
     * 1000 classes per JAR -- up to 2000 calls per module JAR, roughly 28,000 per startup with
     * fourteen modules. A naive per-call warning would flood startup.
     */
    private static final AtomicBoolean DEPRECATION_WARNING_LOGGED = new AtomicBoolean(false);

    private static void warnDeprecatedNoOp() {
        if (DEPRECATION_WARNING_LOGGED.compareAndSet(false, true)) {
            LOGGER.log(Level.WARNING, "[UltiTools-Security] GEN-07 (6.3.0): SecurityPolicy's "
                    + "name-based classload filters were removed -- isSafeClassName/"
                    + "isSafeParameterType always return true and addTrustedPackage/"
                    + "addDangerousClass are no-ops. This class provides no runtime constraint; "
                    + "see its class javadoc. (This warning logs once per JVM.)");
        }
    }

    /**
     * Formerly validated whether a class name was safe to load; unconditionally returns
     * {@code true} now, including for a {@code null} or blank input (GEN-07 empty-input edge --
     * this differs from the pre-6.3.0 behavior, which returned {@code false} for both).
     * <br>
     * 曾用于验证类名是否可以安全加载；现在无条件返回 {@code true}，包括对 {@code null} 或空白输入
     * （GEN-07 空输入边界——此前对两者都返回 {@code false}）。
     *
     * @param className class name to validate, ignored <br> 要验证的类名，被忽略
     * @return {@code true}, unconditionally <br> 始终返回 {@code true}
     * @deprecated Since 6.3.0 (GEN-07) this method is an unconditional no-op -- the three
     * name-based classload filter layers it enforced never protected anything (they evaluated
     * already-trusted, already-loaded classes, not arbitrary untrusted input), and the
     * trusted-package whitelist alone would have refused nearly every third-party module class.
     * The signature is retained because it is {@code public static} on a published class and
     * cannot be removed without breaking a downstream caller's compile. See the package-private
     * {@code ClassloadFilterAudit} for what these removed layers would have refused, now recorded
     * as telemetry.
     * <br>
     * 自 6.3.0（GEN-07）起，此方法是无条件空操作——它曾强制执行的三个基于名称的过滤层从未真正
     * 保护过任何东西（它们评估的是已经信任、已经加载的类，而非任意不可信输入），且信任包白名单
     * 本身几乎会拒绝所有第三方模块类。保留签名是因为它是已发布类上的 {@code public static}
     * 方法，删除会破坏下游调用方的编译。
     */
    @Deprecated(since = "6.3.0")
    public static boolean isSafeClassName(String className) {
        warnDeprecatedNoOp();
        return true;
    }

    /**
     * Formerly validated whether a parameter type was safe for reflection operations;
     * unconditionally returns {@code true} now, including for a {@code null} input (GEN-07
     * empty-input edge -- this differs from the pre-6.3.0 behavior, which returned {@code false}).
     * <br>
     * 曾用于验证参数类型是否对反射操作安全；现在无条件返回 {@code true}，包括对 {@code null}
     * 输入（GEN-07 空输入边界——此前会返回 {@code false}）。
     *
     * @param clazz parameter class, ignored <br> 参数类，被忽略
     * @return {@code true}, unconditionally <br> 始终返回 {@code true}
     * @deprecated Since 6.3.0 (GEN-07), for the same reason as {@link #isSafeClassName(String)} --
     * its only in-tree caller (the seven-argument {@code PluginManager.register}/with-args
     * reflective-construction path) was deleted by GEN-04, which is what dissolved this method's
     * former "shared-field, can't safely delete the whitelist" objection.
     * <br>
     * 自 6.3.0（GEN-07）起弃用，原因同 {@link #isSafeClassName(String)}——它在框架内唯一的调用方
     * （七参数 {@code PluginManager.register}/带参反射构造路径）已被 GEN-04 删除。
     */
    @Deprecated(since = "6.3.0")
    public static boolean isSafeParameterType(Class<?> clazz) {
        warnDeprecatedNoOp();
        return true;
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
     * Formerly added a trusted package prefix at runtime; now a no-op that does not mutate
     * anything (GEN-07, D-12) -- {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES} reflects
     * exactly the shipped list, never a caller's runtime addition, so the audit measures the
     * framework as released.
     * <br>
     * 曾用于在运行时添加信任的包前缀；现在是空操作，不再修改任何状态（GEN-07，D-12）——
     * {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES} 精确反映发布时的列表，而非调用方的
     * 运行时添加，这样审计衡量的才是框架实际发布的样子。
     *
     * @param packagePrefix package prefix, ignored <br> 包前缀，被忽略
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     * <br>
     * 自 6.3.0（GEN-07）起弃用：此方法不再修改 {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES}
     * 或任何其他状态。
     */
    @Deprecated(since = "6.3.0")
    public static void addTrustedPackage(String packagePrefix) {
        warnDeprecatedNoOp();
    }

    /**
     * Formerly added a class to the dangerous-class blacklist at runtime; now a no-op that does
     * not mutate anything (GEN-07, D-12) -- {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES}
     * reflects exactly the shipped list, never a caller's runtime addition.
     * <br>
     * 曾用于在运行时将类添加到危险类黑名单；现在是空操作，不再修改任何状态（GEN-07，D-12）。
     *
     * @param className class name, ignored <br> 类名，被忽略
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     * <br>
     * 自 6.3.0（GEN-07）起弃用：此方法不再修改 {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES}
     * 或任何其他状态。
     */
    @Deprecated(since = "6.3.0")
    public static void addDangerousClass(String className) {
        warnDeprecatedNoOp();
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
