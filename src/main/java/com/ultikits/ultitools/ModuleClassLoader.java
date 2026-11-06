package com.ultikits.ultitools;

import com.ultikits.ultitools.context.ComponentScanner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The single, parent-first module loader. Only classes physically supplied by explicit module JAR
 * URLs enter the ordered rewrite chain. Server JARs, directories and manifest Class-Path additions
 * retain URLClassLoader's own definition path; resources and closing are inherited unchanged.
 */
final class ModuleClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final Set<URL> moduleJars;
    private final List<ClassRewriteRule> rules;
    private final BiConsumer<String, byte[]> definitionObserver;
    private final ConcurrentMap<String, Object> packageLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Package> ownPackages = new ConcurrentHashMap<>();
    private final Set<URL> auditedModules = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<URL, LinkageAudit.Audit> moduleAudits = new ConcurrentHashMap<>();
    private final Set<URL> reportedModules = ConcurrentHashMap.newKeySet();

    /** Production foundation: deliberately no compatibility rules or additional logging. */
    ModuleClassLoader(URL[] urls, ClassLoader parent, URL serverJar) {
        this(urls, parent, serverJar, Collections.emptyList(), null);
    }

    /** Internal test seam observes a copy of exactly the bytes about to be defined. */
    ModuleClassLoader(URL[] urls, ClassLoader parent, URL serverJar, List<ClassRewriteRule> rules,
            BiConsumer<String, byte[]> definitionObserver) {
        super(urls, parent);
        moduleJars = new HashSet<>(Arrays.asList(urls));
        moduleJars.remove(serverJar);
        List<ClassRewriteRule> snapshot = new ArrayList<>(rules);
        for (ClassRewriteRule rule : snapshot) {
            Objects.requireNonNull(rule, "rule");
        }
        this.rules = Collections.unmodifiableList(snapshot);
        this.definitionObserver = definitionObserver;
    }

    /** Uses exactly the rule snapshot that class definition uses, also for the offline entry point. */
    List<LinkageAudit.Finding> audit(File module) throws IOException {
        return LinkageAudit.scan(module, getParent(), rules);
    }

    LinkageAudit.Audit inspect(File module) throws IOException {
        return LinkageAudit.inspect(module, getParent(), rules);
    }

    /** Emits a cached audit only when normal assembly has obtained the module's real scan roots. */
    void reportModule(Class<?> mainClass, String[] roots, java.util.logging.Logger logger) {
        CodeSource codeSource = mainClass.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return;
        }
        URL source = codeSource.getLocation();
        LinkageAudit.Audit audit = moduleAudits.get(source);
        if (audit != null && reportedModules.add(source)) {
            audit.report(audit.moduleName, caller -> Arrays.stream(roots).anyMatch(root ->
                    ComponentScanner.matchesPackage(caller, root)),
                    logger::warning, logger::info);
        }
    }

    /** Pre-scans each explicit module once; classification waits for normal container assembly. */
    void auditModules(java.util.logging.Logger logger) {
        for (URL source : getURLs()) {
            if (!moduleJars.contains(source) || !auditedModules.add(source)) {
                continue;
            }
            try {
                File module = new File(source.toURI());
                if (module.isDirectory()) {
                    continue;
                }
                moduleAudits.put(source, inspect(module));
            } catch (IOException | URISyntaxException | RuntimeException failure) {
                // Audit is diagnostic only: the original loader still decides whether a module loads.
                logger.log(java.util.logging.Level.WARNING,
                        "Could not audit module '" + source + "'; continuing module loading", failure);
            }
        }
    }

    @Override
    protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
        URL resource = findResource(binaryName.replace('.', '/') + ".class");
        if (resource == null) {
            throw new ClassNotFoundException(binaryName);
        }
        try {
            URLConnection connection = resource.openConnection();
            if (!(connection instanceof JarURLConnection)) {
                return super.findClass(binaryName);
            }
            JarURLConnection jarConnection = (JarURLConnection) connection;
            URL source = jarConnection.getJarFileURL();
            if (!moduleJars.contains(source)) {
                return super.findClass(binaryName);
            }
            // Direct URL connections are not registered in URLClassLoader's closeables. Never
            // leave one in the global JarURLConnection cache: its handle belongs to this read.
            jarConnection.setUseCaches(false);
            byte[] bytes;
            Manifest manifest;
            CodeSigner[] signers;
            try (JarFile jar = jarConnection.getJarFile(); InputStream input = jarConnection.getInputStream()) {
                JarEntry entry = jarConnection.getJarEntry();
                bytes = readFully(input);
                // Signature verification populates signers only after the entire entry is read.
                signers = entry.getCodeSigners();
                manifest = jar.getManifest();
            }
            byte[] rewritten = rewrite(binaryName, bytes);
            ensurePackage(binaryName, manifest, source);
            if (definitionObserver != null) {
                definitionObserver.accept(binaryName, rewritten.clone());
            }
            return defineClass(binaryName, rewritten, 0, rewritten.length, new CodeSource(source, signers));
        } catch (IOException e) {
            throw new ClassNotFoundException("Could not read module class " + binaryName, e);
        }
    }

    private byte[] rewrite(String binaryName, byte[] bytes) {
        byte[] current = bytes;
        for (ClassRewriteRule rule : rules) {
            try {
                if (rule.wouldChange(binaryName, current)) {
                    current = Objects.requireNonNull(rule.rewrite(binaryName, current), "rewritten bytes");
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // A broken rule must never silently cause original bytes to be defined instead.
                throw new ModuleCompatibilityException(binaryName, rule, failure);
            }
        }
        return current;
    }

    private void ensurePackage(String binaryName, Manifest manifest, URL source) {
        int separator = binaryName.lastIndexOf('.');
        if (separator < 0) {
            return;
        }
        String name = binaryName.substring(0, separator);
        Object lock = packageLocks.computeIfAbsent(name, ignored -> new Object());
        synchronized (lock) {
            Package pkg = ownPackages.get(name);
            if (pkg == null) {
                try {
                    pkg = manifest == null
                            ? definePackage(name, null, null, null, null, null, null, null)
                            : definePackage(name, manifest, source);
                } catch (IllegalArgumentException alreadyDefined) {
                    // A superclass-path class (or its parallel definition) may have defined it.
                    // Trying definePackage first is essential: Java8 getPackage also sees parents.
                    pkg = getPackage(name);
                    if (pkg == null) {
                        throw alreadyDefined;
                    }
                }
                ownPackages.put(name, pkg);
            }
            if (pkg.isSealed()) {
                if (!pkg.isSealed(source)) {
                    throw new SecurityException("Sealing violation: package " + name + " is sealed");
                }
            } else if (isSealed(name, manifest)) {
                throw new SecurityException("Sealing violation: cannot seal already loaded package " + name);
            }
        }
    }

    private static boolean isSealed(String name, Manifest manifest) {
        if (manifest == null) {
            return false;
        }
        Attributes section = manifest.getAttributes(name.replace('.', '/') + "/");
        String sealed = section == null ? null : section.getValue(Attributes.Name.SEALED);
        if (sealed == null) {
            sealed = manifest.getMainAttributes().getValue(Attributes.Name.SEALED);
        }
        return "true".equalsIgnoreCase(sealed);
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }
}
