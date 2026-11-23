package com.ultikits.ultitools;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * The single, parent-first module loader. Class definition, resources, package metadata,
 * signature verification and JAR lifetime use URLClassLoader's ordinary behavior.
 */
final class ModuleClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    ModuleClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }
}
