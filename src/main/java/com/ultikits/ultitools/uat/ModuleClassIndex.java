package com.ultikits.ultitools.uat;

import java.io.File;
import java.io.IOException;
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
 * {@code .class} files, or a jar — UltiBot ships only a final jar plus a multi-module
 * {@code target/}) and resolves each one with {@link Class#forName(String, boolean, ClassLoader)}
 * passing {@code initialize=false}, so no static initializer ever runs (Phase 10, D-10-01,
 * T-10-01). A class that fails to resolve becomes a named {@link ExtractorException} — never a
 * silently skipped row.
 *
 * @since 6.3.0
 */
public final class ModuleClassIndex {

    private static final String CLASS_SUFFIX = ".class";

    private final ClassLoader loader;

    public ModuleClassIndex(ClassLoader loader) {
        this.loader = loader;
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
        List<String> binaryNames = enumerateBinaryNames(classesRoot);
        List<Class<?>> classes = new ArrayList<>(binaryNames.size());
        for (String binaryName : binaryNames) {
            try {
                classes.add(Class.forName(binaryName, false, loader));
            } catch (Throwable t) {
                throw ExtractorException.classLoadFailure(binaryName, t);
            }
        }
        return classes;
    }

    private List<String> enumerateBinaryNames(Path classesRoot) throws ExtractorException {
        if (Files.isDirectory(classesRoot)) {
            return enumerateFromDirectory(classesRoot);
        }
        if (Files.isRegularFile(classesRoot) && classesRoot.toString().endsWith(".jar")) {
            return enumerateFromJar(classesRoot);
        }
        throw new ExtractorException(
                "--classes must be an existing directory or an existing .jar file: " + classesRoot);
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
                if (entry.isDirectory() || !entry.getName().endsWith(CLASS_SUFFIX)) {
                    continue;
                }
                String binaryName = entry.getName()
                        .substring(0, entry.getName().length() - CLASS_SUFFIX.length())
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
