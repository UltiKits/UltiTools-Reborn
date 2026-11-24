package com.ultikits.ultitools.bootstrap;

import org.jetbrains.annotations.ApiStatus;

/**
 * An optional early-start hook, named by {@code bootstrapper:} in a module's own {@code plugin.yml}.
 * Called once on the server startup thread before any world loads, for an admitted module only.
 * The implementation must have a public no-argument constructor and live in that module's JAR.
 *
 * <p>No Bukkit server, plugin instances, IoC services or worlds are available here. Use only the
 * module's own classes, the framework and the server API; do not reference other plugins' classes.
 * A thrown exception or error prevents only this module from loading at this start, except that
 * virtual machine failures may propagate. Enable observes updated modules and may restore an old
 * copy; check that phase's log before changing module JARs. An overall framework preparation failure
 * is retained and reported during enable, which disables the framework.
 * No order is promised between different modules' bootstrappers.
 *
 * @since 7.0.0
 */
@ApiStatus.Experimental
public interface ModuleBootstrap {
    /**
     * Performs the module's early startup work.
     *
     * @param context the framework-supplied identity and logger of this module
     * @throws Exception when the module cannot finish its early startup
     */
    void bootstrap(ModuleBootstrapContext context) throws Exception;
}
