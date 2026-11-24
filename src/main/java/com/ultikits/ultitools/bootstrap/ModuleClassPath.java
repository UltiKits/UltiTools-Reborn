package com.ultikits.ultitools.bootstrap;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.ModuleFileTransactions;
import com.ultikits.ultitools.utils.SecurityPolicy;

/** Internal startup class-path operations; not an API for modules. */
@ApiStatus.Internal
public final class ModuleClassPath {
    private static final Logger LOGGER = Logger.getLogger("UltiTools");

    private ModuleClassPath() {
        // Utility class; all operations belong to one bootstrap result.
    }

    /**
     * Locates the server API source without requiring a live Bukkit server.
     *
     * @param logger the startup logger
     * @return the source URL, or {@code null} when unavailable
     */
    public static @Nullable URL serverJarOf(Logger logger) {
        CodeSource source = Bukkit.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            return null;
        }
        if (source.getLocation().toString().startsWith("union:")) {
            String fileUrl = source.getLocation().toString().replace("union:", "file:").split("%")[0];
            try {
                return new java.net.URI(fileUrl).toURL();
            } catch (MalformedURLException | URISyntaxException failure) {
                logger.log(Level.WARNING, "Failed to parse server JAR URL: " + fileUrl, failure);
            }
        }
        return source.getLocation();
    }

    /**
     * Applies pending file transactions before opening any module archive, then builds its class path.
     *
     * @param transactions this start's transactions
     * @param modulesFolder the one module directory
     * @param serverJar the server source, if available
     * @return the server source followed by admitted module sources
     */
    public static URL[] moduleClassPath(ModuleFileTransactions transactions, File modulesFolder,
                                        @Nullable URL serverJar) {
        return moduleClassPath(transactions, modulesFolder, serverJar, new ModuleJarAdmission());
    }

    /**
     * Builds a class path using the admission snapshot later consumed by discovery.
     *
     * @param transactions this start's transactions
     * @param modulesFolder the one module directory
     * @param serverJar the server source, if available
     * @param admission this start's metadata and decisions
     * @return the server source followed by admitted module sources
     */
    public static URL[] moduleClassPath(ModuleFileTransactions transactions, File modulesFolder,
                                        @Nullable URL serverJar, ModuleJarAdmission admission) {
        transactions.applyBeforeLoad();
        List<URL> urls = new ArrayList<>();
        if (serverJar != null) {
            urls.add(serverJar);
        }
        urls.addAll(collectModuleJarUrls(modulesFolder, admission));
        return urls.toArray(new URL[0]);
    }

    /**
     * Collects module sources in filename order, guarding archives before they enter the class path.
     *
     * @param directory the module directory, or {@code null}
     * @return accepted sources; an absent or unlistable directory yields an empty list
     */
    public static List<URL> collectModuleJarUrls(@Nullable File directory) {
        return collectModuleJarUrls(directory, new ModuleJarAdmission());
    }

    /**
     * Collects sources and retains their admission decisions for discovery. Unreadable metadata
     * keeps its existing discovery diagnostic; API refusals never supply classes.
     *
     * @param directory the module directory, or {@code null}
     * @param admission this start's metadata and decisions
     * @return accepted sources
     */
    public static List<URL> collectModuleJarUrls(@Nullable File directory, ModuleJarAdmission admission) {
        List<URL> urls = new ArrayList<>();
        if (directory == null || !directory.exists()) {
            return urls;
        }
        File[] files = ModuleFileTransactions.moduleJars(directory);
        if (files != null) {
            for (File file : files) {
                collect(file, admission, urls);
            }
        }
        return urls;
    }

    private static void collect(File file, ModuleJarAdmission admission, List<URL> urls) {
        if (!SecurityPolicy.isValidModuleJar(file)) {
            LOGGER.log(Level.WARNING, "[UltiTools-API] Skipped module JAR (failed security validation), not added "
                    + "to module classpath: " + file.getName());
            return;
        }
        ModuleJarAdmission.Entry entry = admission.inspect(file);
        String main = entry.getMetadata().getMain();
        if (main != null && !main.trim().isEmpty() && !entry.isApiAllowed()) {
            return;
        }
        try {
            urls.add(file.toURI().toURL());
        } catch (MalformedURLException failure) {
            LOGGER.log(Level.WARNING, "Failed to add module JAR to classpath: " + file.getName(), failure);
        }
    }
}
