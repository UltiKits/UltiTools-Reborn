package com.ultikits.ultitools.manager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.utils.ModuleFileTransactions;

/**
 * Which JARs in the modules folder declare which module main class, as the module loader read them
 * at start-up (#516).
 *
 * <p>The loader identifies a module JAR by nothing but the {@code main:} entry of its
 * {@code plugin.yml} (#548/#549): it reads that entry and loads exactly that class. This index keeps
 * what it read, per main class, together with the JAR the class was actually loaded from. It is how
 * an uninstall knows every file on disk that belongs to a loaded module -- including a second copy
 * whose {@code plugin.yml} declares a different {@code name:} -- and how the start-up warning about
 * duplicate copies names all of them. Nothing here reads a class out of an archive.
 *
 * <p>Thread-safe: written by the start-up scan, read by {@code /upm} commands.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ModuleJarIndex {

    /** Main class name -> canonical path -> JAR, for every JAR whose {@code plugin.yml} declares it. */
    private final Map<String, Map<String, File>> jarsByMainClass = new LinkedHashMap<>();

    /** Main class name -> the JAR its class was loaded from. */
    private final Map<String, File> supplierByMainClass = new HashMap<>();

    /**
     * Records that {@code jar}'s {@code plugin.yml} declares {@code mainClass}.
     *
     * @param mainClass the declared main class
     * @param jar       the JAR declaring it
     */
    public synchronized void record(String mainClass, File jar) {
        jarsByMainClass.computeIfAbsent(mainClass, key -> new LinkedHashMap<>()).put(canonicalPath(jar), jar);
    }

    /**
     * Records the JAR the class {@code mainClass} was loaded from.
     *
     * @param mainClass the main class
     * @param jar       the JAR its class came from
     */
    public synchronized void recordSupplier(String mainClass, File jar) {
        supplierByMainClass.put(mainClass, jar);
    }

    /**
     * Every JAR recorded as declaring {@code mainClass}, in file-name order.
     *
     * @param mainClass the main class
     * @return the JARs, possibly empty
     */
    public synchronized List<File> jarsDeclaring(String mainClass) {
        Map<String, File> jars = jarsByMainClass.get(mainClass);
        if (jars == null) {
            return Collections.emptyList();
        }
        File[] sorted = ModuleFileTransactions.sortedByName(jars.values().toArray(new File[0]));
        List<File> result = new ArrayList<>();
        Collections.addAll(result, sorted);
        return result;
    }

    /**
     * The JAR the class {@code mainClass} was loaded from, or {@code null} when it is not known.
     *
     * @param mainClass the main class
     * @return the JAR, or {@code null}
     */
    public synchronized File supplierOf(String mainClass) {
        return supplierByMainClass.get(mainClass);
    }

    /**
     * The main class the loader recorded for a loaded module: the nearest class in its hierarchy
     * that some JAR declares -- the instance may be a subclass of the declared class, as a proxy
     * is. When none is recorded (a module registered from code rather than from the modules
     * folder), the instance's own class name.
     *
     * @param module a loaded module
     * @return its main class name
     */
    public synchronized String mainClassOf(UltiToolsPlugin module) {
        for (Class<?> type = module.getClass(); type != null && type != UltiToolsPlugin.class;
             type = type.getSuperclass()) {
            if (jarsByMainClass.containsKey(type.getName())) {
                return type.getName();
            }
        }
        return module.getClass().getName();
    }

    /**
     * The main classes two or more JARs declare, each with its JARs in file-name order.
     *
     * @return main class name -> its JARs; empty when there is no duplicate
     */
    public synchronized Map<String, List<File>> duplicates() {
        Map<String, List<File>> duplicates = new LinkedHashMap<>();
        for (String mainClass : jarsByMainClass.keySet()) {
            List<File> jars = jarsDeclaring(mainClass);
            if (jars.size() > 1) {
                duplicates.put(mainClass, jars);
            }
        }
        return duplicates;
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException | SecurityException e) {
            return file.getAbsolutePath();
        }
    }
}
