package com.ultikits.ultitools;

import java.net.URL;
import java.net.URLClassLoader;

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

    ModuleClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
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
