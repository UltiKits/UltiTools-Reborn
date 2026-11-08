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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;

import com.ultikits.ultitools.utils.PluginYmlReader;

/**
 * The single, parent-first module loader. Only classes physically supplied by explicit module JAR
 * URLs enter the ordered rewrite chain. Server JARs, directories and manifest Class-Path additions
 * retain URLClassLoader's own definition path. Resource discovery remains inherited; module JAR
 * handles are retained until this loader closes, just like URLClassLoader's own JAR handles.
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
    // This monitor guards handle creation and closing, never class rewriting or definition.
    private final Map<URL, JarFile> moduleJarFiles = new HashMap<>();
    private boolean closed;

    private final ConcurrentMap<URL, ConcurrentMap<String, Integer>> rewrittenClasses = new ConcurrentHashMap<>();
    private final AtomicBoolean startupReported = new AtomicBoolean();

    /** Production rules use this start's real parent, never the module loader itself. */
    ModuleClassLoader(URL[] urls, ClassLoader parent, URL serverJar) {
        this(urls, parent, serverJar, Collections.<ClassRewriteRule>singletonList(
                new InvokeKindAlignmentRule(new RuntimeServerApiProbe(parent))), null);
    }

    /** Internal test seam observes a copy of exactly the bytes about to be defined. */
    ModuleClassLoader(URL[] urls, ClassLoader parent, URL serverJar, List<ClassRewriteRule> rules,
            BiConsumer<String, byte[]> definitionObserver) {
        super(urls, parent);
        moduleJars = new LinkedHashSet<>(Arrays.asList(urls));
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
    void reportModule(Class<?> mainClass, String[] roots, Logger logger) {
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
    void auditModules(Logger logger) {
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
            JarFile jar = moduleJar(source, jarConnection);
            // findResource has already selected the entry, including a multi-release version.
            JarEntry entry = jar.getJarEntry(jarConnection.getEntryName());
            if (entry == null) {
                throw new ClassNotFoundException(binaryName);
            }
            byte[] bytes;
            Manifest manifest;
            CodeSigner[] signers;
            try (InputStream input = jar.getInputStream(entry)) {
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
            Class<?> defined = defineClass(binaryName, rewritten, 0, rewritten.length, new CodeSource(source, signers));
            recordRewrite(source, binaryName);
            return defined;
        } catch (IOException e) {
            throw new ClassNotFoundException("Could not read module class " + binaryName, e);
        }
    }

    private JarFile moduleJar(URL source, JarURLConnection connection) throws IOException {
        synchronized (moduleJarFiles) {
            if (closed) {
                throw new IOException("Module class loader is closed");
            }
            JarFile jar = moduleJarFiles.get(source);
            if (jar == null) {
                // This verifying handle belongs to the loader, not the global connection cache.
                connection.setUseCaches(false);
                jar = connection.getJarFile();
                moduleJarFiles.put(source, jar);
            }
            return jar;
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (moduleJarFiles) {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                super.close();
            } catch (IOException e) {
                failure = e;
            } finally {
                for (JarFile jar : moduleJarFiles.values()) {
                    try {
                        jar.close();
                    } catch (IOException e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }
                moduleJarFiles.clear();
            }
            if (failure != null) {
                throw failure;
            }
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

    private void recordRewrite(URL source, String binaryName) {
        int sites = 0;
        for (ClassRewriteRule rule : rules) {
            if (rule instanceof InvokeKindAlignmentRule) {
                sites += ((InvokeKindAlignmentRule) rule).rewrittenSites(binaryName);
            }
        }
        if (sites != 0) {
            rewrittenClasses.computeIfAbsent(source, ignored -> new ConcurrentHashMap<>()).put(binaryName, sites);
        }
    }

    /**
     * One observed startup snapshot, after module initialization. Later lazy loads are not
     * predictions in this count and do not print additional startup lines.
     */
    void logRewriteCounts(Logger logger) {
        if (!startupReported.compareAndSet(false, true)) {
            return;
        }
        for (URL source : moduleJars) {
            if (!"file".equals(source.getProtocol()) || !source.getPath().endsWith(".jar")) {
                continue;
            }
            String name = source.getPath().substring(source.getPath().lastIndexOf('/') + 1);
            try {
                File jar = new File(source.toURI());
                String declared = PluginYmlReader.readFromJarFile(jar).getName();
                if (declared != null) {
                    name = declared;
                }
            } catch (java.net.URISyntaxException invalidSource) {
                // The source URL still identifies this module when it is not a file URI.
            }
            ConcurrentMap<String, Integer> classes = rewrittenClasses.get(source);
            List<Integer> counts = classes == null ? Collections.emptyList() : new ArrayList<>(classes.values());
            long sites = 0;
            for (int count : counts) {
                sites += count;
            }
            logger.info("[UltiTools-API] " + name + " startup compatibility rewriting: " + counts.size()
                    + " classes, " + sites + " call sites (R1: " + sites + ")");
        }
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
