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
     *
     * @param className class name to validate, ignored
     * @return {@code true}, unconditionally
     * @deprecated Since 6.3.0 (GEN-07) this method is an unconditional no-op -- the three
     * name-based classload filter layers it enforced never protected anything (they evaluated
     * already-trusted, already-loaded classes, not arbitrary untrusted input), and the
     * trusted-package whitelist alone would have refused nearly every third-party module class.
     * The signature is retained because it is {@code public static} on a published class and
     * cannot be removed without breaking a downstream caller's compile. See the package-private
     * {@code ClassloadFilterAudit} for what these removed layers would have refused, now recorded
     * as telemetry.
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
     *
     * @param clazz parameter class, ignored
     * @return {@code true}, unconditionally
     * @deprecated Since 6.3.0 (GEN-07), for the same reason as {@link #isSafeClassName(String)} --
     * its only in-tree caller (the seven-argument {@code PluginManager.register}/with-args
     * reflective-construction path) was deleted by GEN-04, which is what dissolved this method's
     * former "shared-field, can't safely delete the whitelist" objection.
     */
    @Deprecated(since = "6.3.0")
    public static boolean isSafeParameterType(Class<?> clazz) {
        warnDeprecatedNoOp();
        return true;
    }

    /**
     * Validate file size and structure for security.
     *
     * @param fileSize file size in bytes
     * @param entryCount number of entries in archive
     * @return true if safe, false otherwise
     */
    public static boolean isSafeFileStructure(long fileSize, int entryCount) {
        // File size limit: 100MB
        long maxFileSize = 100 * 1024 * 1024;
        if (fileSize > maxFileSize) {
            logSecurityViolation("File too large", "Size: " + fileSize + " bytes");
            return false;
        }

        // Entry count limit: 10000
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
     *
     * <p>This is a static, instance-free rule with no dependency on Bukkit — it must be callable
     * before the plugin's {@code onEnable()} has built any manager, and from a plain JUnit test
     * with no server running. Callers are responsible for skipping (not adding) a JAR this method
     * rejects; this method never throws for a JAR it judges unsafe, it only returns {@code false}.</p>
     *
     * @param jarFile candidate module jar file
     * @return true if the jar may be handed to a classloader, false otherwise
     * @since 6.3.0
     */
    public static boolean isValidModuleJar(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.isFile()) {
            return false;
        }

        // Check the file extension
        if (!jarFile.getName().toLowerCase().endsWith(".jar")) {
            return false;
        }

        // Validate the jar file structure
        try (JarFile jar = new JarFile(jarFile)) {
            // UltiTools modules don't require plugin.yml — they're identified by @UltiToolsModule

            // Count the entries
            Enumeration<JarEntry> entries = jar.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                entries.nextElement();
                entryCount++;
            }

            // Use SecurityPolicy to validate the file structure
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
     *
     * @param packagePrefix package prefix, ignored
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.TRUSTED_PACKAGE_PREFIXES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     */
    @Deprecated(since = "6.3.0")
    public static void addTrustedPackage(String packagePrefix) {
        warnDeprecatedNoOp();
    }

    /**
     * Formerly added a class to the dangerous-class blacklist at runtime; now a no-op that does
     * not mutate anything (GEN-07, D-12) -- {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES}
     * reflects exactly the shipped list, never a caller's runtime addition.
     *
     * @param className class name, ignored
     * @deprecated Since 6.3.0 (GEN-07): this method no longer mutates
     * {@code ClassloadFilterAudit.SYSTEM_DANGEROUS_CLASSES} or anything else. The signature is
     * retained because it is {@code public static} on a published class.
     */
    @Deprecated(since = "6.3.0")
    public static void addDangerousClass(String className) {
        warnDeprecatedNoOp();
    }

    /**
     * Log security violation for monitoring and audit purposes.
     *
     * @param reason violation reason
     * @param details violation details
     */
    private static void logSecurityViolation(String reason, String details) {
        String message = String.format("[UltiTools-Security] Violation: %s - %s", reason, details);
        LOGGER.log(Level.WARNING, message);
    }

    /**
     * Get current security policy information.
     *
     * @return security policy summary
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
