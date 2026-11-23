package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Validates module JAR structure before a classloader consumes it.
 * The archive must be a readable regular JAR file within the 100 MB and 10,000-entry limits.
 * This is not a sandbox and does not constrain what loaded module code can do.
 */
public class SecurityPolicy {

    private static final Logger LOGGER = Logger.getLogger(SecurityPolicy.class.getName());

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
            // Corrected (plan 17-30): this comment used to read "UltiTools modules don't require
            // plugin.yml — they're identified by @UltiToolsModule". That was already superseded by
            // #341/D-16, which refuses a module whose plugin.yml has no name: key, and is now also
            // superseded by PluginManager#loadPluginMainClass requiring a plugin.yml main: entry to
            // discover the module's main class at all -- see that method's own javadoc. A module
            // jar with no plugin.yml is refused elsewhere in the load path, not here; this method's
            // own job stays limited to the size/entry-count structural check below.

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
     * Log security violation for monitoring and audit purposes.
     *
     * @param reason violation reason
     * @param details violation details
     */
    private static void logSecurityViolation(String reason, String details) {
        String message = String.format("[UltiTools-Security] Violation: %s - %s", reason, details);
        LOGGER.log(Level.WARNING, message);
    }

}
