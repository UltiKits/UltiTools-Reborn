package com.ultikits.ultitools.uat;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Enumerates a module's compiled classes from a {@code --classes} argument (a directory of
 * {@code .class} files, or a jar — UltiBot's {@code ultibot-dist} is a shade aggregator with no
 * {@code src/}, so its jar is the only thing that ever exists for that module) and resolves each
 * one with {@link Class#forName(String, boolean, ClassLoader)} passing {@code initialize=false},
 * so no static initializer ever runs (Phase 10, D-10-01, T-10-01). A class that fails to resolve
 * becomes a named {@link ExtractorException} — never a silently skipped row.
 * <p>
 * {@code classesRoot} itself is always added to the classpath used to resolve its own classes
 * (Phase 10 plan 10-02, Task 3): a caller's own {@code -cp} typically carries the module's
 * <em>dependencies</em> (framework jar, {@code paper-api}, ...), built once via
 * {@code mvn dependency:build-classpath} against the module's own POM, but never the module's own
 * output artifact — for a directory target that output already sits inside the working directory
 * and may incidentally be reachable anyway, but for a jar target (UltiBot's dist jar is never on
 * that dependency classpath; it is the artifact being inspected, not a dependency of itself) the
 * jar's own classes would otherwise be unresolvable. Wrapping {@code classesRoot} in its own
 * {@link URLClassLoader}, parented to the constructor-supplied loader, makes both input forms
 * self-sufficient the same way and removes the dependency on the caller assembling a perfectly
 * complete {@code -cp} by hand.
 * <p>
 * <b>Deliberately does not reuse {@link com.ultikits.ultitools.utils.SecurityPolicy#isValidModuleJar}.</b>
 * That guard enforces a 100&nbsp;MB / 10,000-entry ceiling appropriate to an untrusted jar dropped
 * into a running server's {@code plugins/} directory at load time (ROADMAP criterion 4's zip-bomb
 * defense). This tool runs in CI, over a jar this very monorepo's own build just produced, as an
 * argument the CI step itself controls — the threat model that ceiling defends against does not
 * apply here (Phase 10, T-10-10, disposition {@code accept}), and imposing it would only risk a
 * false rejection of a legitimately large module jar with no compensating security benefit.
 *
 * @since 6.3.0
 */
public final class ModuleClassIndex {

    private static final String CLASS_SUFFIX = ".class";
    private static final String MULTI_RELEASE_PREFIX = "META-INF/versions/";

    private final ClassLoader parentLoader;

    public ModuleClassIndex(ClassLoader loader) {
        this.parentLoader = loader;
    }

    /**
     * Enumerates and resolves every class under {@code classesRoot}, in deterministic
     * (lexicographic) binary-name order.
     *
     * @param classesRoot an existing directory of {@code .class} files, or an existing jar
     * @return every resolved, uninitialized {@link Class}
     * @throws ExtractorException if {@code classesRoot} is neither, or a class fails to resolve
     */
    public List<Class<?>> load(Path classesRoot) throws ExtractorException {
        Path resolved = resolveClasspathRoot(classesRoot);
        List<String> binaryNames = enumerateBinaryNames(resolved);
        ClassLoader loader = buildLoader(resolved);
        List<Class<?>> classes = new ArrayList<>(binaryNames.size());
        for (String binaryName : binaryNames) {
            try {
                // binaryName is not attacker-controllable: it comes from enumerating the
                // .class file names actually present under a CI-controlled classesRoot
                // directory or jar (see enumerateBinaryNames above), never from network or
                // user-supplied input. The suppression must sit on the line immediately
                // above the finding to take effect.
                // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
                classes.add(Class.forName(binaryName, false, loader));
            } catch (Throwable t) {
                throw ExtractorException.classLoadFailure(binaryName, t);
            }
        }
        return classes;
    }

    private Path resolveClasspathRoot(Path classesRoot) throws ExtractorException {
        Path candidate = classesRoot.toAbsolutePath().normalize();
        boolean isDirectory = Files.isDirectory(candidate);
        boolean isJar = Files.isRegularFile(candidate) && candidate.toString().endsWith(".jar");
        if (!isDirectory && !isJar) {
            throw new ExtractorException(
                    "--classes must be an existing directory or an existing .jar file: " + classesRoot);
        }
        try {
            return candidate.toRealPath();
        } catch (IOException e) {
            throw new ExtractorException("Failed to resolve canonical path for " + classesRoot, e);
        }
    }

    private ClassLoader buildLoader(Path classesRoot) throws ExtractorException {
        try {
            return new URLClassLoader(new URL[] {classesRoot.toUri().toURL()}, parentLoader);
        } catch (MalformedURLException e) {
            throw new ExtractorException("Cannot form classpath URL for " + classesRoot, e);
        }
    }

    private List<String> enumerateBinaryNames(Path classesRoot) throws ExtractorException {
        if (Files.isDirectory(classesRoot)) {
            return enumerateFromDirectory(classesRoot);
        }
        return enumerateFromJar(classesRoot);
    }

    private List<String> enumerateFromDirectory(Path root) throws ExtractorException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(CLASS_SUFFIX))
                    .forEach(path -> addIfNotInfoClass(names, toBinaryName(root, path)));
        } catch (IOException e) {
            throw new ExtractorException("Failed to enumerate classes under " + root, e);
        }
        Collections.sort(names);
        return names;
    }

    private static String toBinaryName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        return relative.substring(0, relative.length() - CLASS_SUFFIX.length());
    }

    private List<String> enumerateFromJar(Path jarPath) throws ExtractorException {
        List<String> names = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory() || !entryName.endsWith(CLASS_SUFFIX)) {
                    continue;
                }
                if (entryName.startsWith(MULTI_RELEASE_PREFIX)) {
                    // A multi-release jar's versioned overlay (e.g.
                    // META-INF/versions/11/com/acme/Foo.class, common for a shaded
                    // artifact) is not a real class package path -- converting it
                    // verbatim produces an invalid binary name ("META-INF.versions.11...")
                    // that Class.forName can never resolve, which would otherwise fail
                    // the whole extraction closed rather than merely skip this overlay.
                    // The base (non-versioned) entry for the same class is enumerated
                    // separately and is what supplies this row; skipping the overlay
                    // loses no row a JVM below the override version would ever load.
                    continue;
                }
                String binaryName = entryName
                        .substring(0, entryName.length() - CLASS_SUFFIX.length())
                        .replace('/', '.');
                addIfNotInfoClass(names, binaryName);
            }
        } catch (IOException e) {
            throw new ExtractorException("Failed to enumerate classes in jar " + jarPath, e);
        }
        Collections.sort(names);
        return names;
    }

    private static void addIfNotInfoClass(List<String> names, String binaryName) {
        if (isInfoClass(binaryName, "package-info") || isInfoClass(binaryName, "module-info")) {
            return;
        }
        names.add(binaryName);
    }

    private static boolean isInfoClass(String binaryName, String simpleName) {
        return binaryName.equals(simpleName) || binaryName.endsWith("." + simpleName);
    }
}
