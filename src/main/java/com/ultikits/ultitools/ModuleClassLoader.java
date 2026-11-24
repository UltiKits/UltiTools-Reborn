package com.ultikits.ultitools;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.ultikits.ultitools.manager.ModuleJarAdmission;
import com.ultikits.ultitools.utils.PluginYmlReader;

import org.jetbrains.annotations.ApiStatus;

/**
 * The single, parent-first module loader. Class definition, resources, package metadata,
 * signature verification and JAR lifetime use URLClassLoader's ordinary behavior.
 * Internal bootstrap/enable seam; not an API for modules.
 */
@ApiStatus.Internal
public final class ModuleClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final CopyOnWriteArrayList<ClassLoader> dependencyLoaders = new CopyOnWriteArrayList<>();

    ModuleClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    void addDependencyLoader(ClassLoader loader) {
        if (!loader.equals(this) && !loader.equals(getParent())) {
            dependencyLoaders.addIfAbsent(loader);
        }
    }

    /**
     * Called only during enable, when server plugin instances exist. A module can use the plugins
     * it declares in depend/softdepend; visibility of undeclared plugins is not guaranteed.
     */
    void addDeclaredDependencies(ModuleJarAdmission admission, Function<String, ClassLoader> findLoader) {
        Set<String> names = new TreeSet<>(Arrays.asList("Vault", "PlaceholderAPI"));
        for (ModuleJarAdmission.Entry entry : admission.entries().values()) {
            PluginYmlReader.PluginYmlInfo metadata = entry.getMetadata();
            String main = metadata.getMain();
            if (entry.isApiAllowed() && entry.getBootstrapFailure() == null && main != null && !main.trim().isEmpty()) {
                names.addAll(metadata.getDepend());
                names.addAll(metadata.getSoftDepend());
            }
        }
        for (String name : names) {
            ClassLoader loader = findLoader.apply(name);
            if (loader != null) {
                addDependencyLoader(loader);
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException missing) {
            for (ClassLoader loader : dependencyLoaders) {
                try {
                    return loader.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // This dependency does not supply the class; try the next declared dependency.
                }
            }
            throw new ClassNotFoundException(name + " is not available to modules. If this class belongs to another server plugin, "
                    + "declare the providing plugin in the module's plugin.yml depend or softdepend; "
                    + "visibility of undeclared plugins is not guaranteed.", missing);
        }
    }

    /**
     * Creates the loader handed from bootstrap to enable.
     *
     * @param urls this start's admitted sources
     * @param parent the framework class loader
     * @return the shared module loader
     */
    public static ModuleClassLoader create(URL[] urls, ClassLoader parent) {
        return new ModuleClassLoader(urls, parent);
    }
}
