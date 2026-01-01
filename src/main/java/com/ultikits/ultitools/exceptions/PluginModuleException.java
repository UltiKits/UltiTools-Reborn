package com.ultikits.ultitools.exceptions;

/**
 * Exception thrown when plugin module operations fail.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class PluginModuleException extends UltiToolsException {

    /**
     * Creates a new plugin module exception with the given message.
     *
     * @param message the error message
     */
    public PluginModuleException(String message) {
        super(ErrorCode.PLUGIN_ERROR, message);
    }

    /**
     * Creates a new plugin module exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public PluginModuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new plugin module exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public PluginModuleException(String message, Throwable cause) {
        super(ErrorCode.PLUGIN_ERROR, message, cause);
    }

    /**
     * Creates a new plugin module exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public PluginModuleException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception for plugin load failures.
     *
     * @param pluginName the name of the plugin that failed to load
     * @param cause      the underlying cause
     * @return a new PluginModuleException
     */
    public static PluginModuleException loadFailed(String pluginName, Throwable cause) {
        return new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED,
                "Failed to load plugin: " + pluginName, cause);
    }

    /**
     * Creates an exception for plugin unload failures.
     *
     * @param pluginName the name of the plugin that failed to unload
     * @param cause      the underlying cause
     * @return a new PluginModuleException
     */
    public static PluginModuleException unloadFailed(String pluginName, Throwable cause) {
        return new PluginModuleException(ErrorCode.PLUGIN_UNLOAD_FAILED,
                "Failed to unload plugin: " + pluginName, cause);
    }

    /**
     * Creates an exception for missing plugin dependencies.
     *
     * @param pluginName         the name of the plugin with missing dependencies
     * @param missingDependency  the name of the missing dependency
     * @return a new PluginModuleException
     */
    public static PluginModuleException dependencyMissing(String pluginName, String missingDependency) {
        return new PluginModuleException(ErrorCode.PLUGIN_DEPENDENCY_ERROR,
                "Plugin '" + pluginName + "' requires missing dependency: " + missingDependency);
    }

    /**
     * Creates an exception for circular plugin dependencies.
     *
     * @param pluginNames the names of plugins involved in the circular dependency
     * @return a new PluginModuleException
     */
    public static PluginModuleException circularDependency(String... pluginNames) {
        return new PluginModuleException(ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY,
                "Circular dependency detected among plugins: " + String.join(" -> ", pluginNames));
    }
}
