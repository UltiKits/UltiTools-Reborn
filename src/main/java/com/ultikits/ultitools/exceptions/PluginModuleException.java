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

    // The three factories below belong to WIRE-16's single-owner panel responder registry
    // (Plan 06-08). They join PluginModuleException rather than ContainerException — a deliberate
    // departure from 06-RESEARCH.md's Pattern 3, whose illustrative code names ContainerException.
    // The offender here is a module and the failure is at module load/dispatch, which is exactly
    // what this class's four existing factories above already describe; ContainerException
    // describes bean-container failures in the 2000-series, a different resource entirely.

    /**
     * Creates an exception refusing to register a responder for a message type the framework
     * itself already owns (D-26). Single-owner semantics apply to the framework's own 24 message
     * types exactly as they do to another module's — without this check, a module could silently
     * take over {@code execute_command}.
     *
     * @param messageType the message type the framework already serves
     * @return a new PluginModuleException
     */
    public static PluginModuleException responderTypeOwnedByFramework(String messageType) {
        return new PluginModuleException(ErrorCode.WEBSOCKET_RESPONDER_TYPE_OWNED_BY_FRAMEWORK,
                "Cannot register a panel responder for '" + messageType + "': the framework itself "
                        + "already owns this message type.");
    }

    /**
     * Creates an exception refusing to register a responder for a message type another module has
     * already claimed (D-26). Names both the contested type and the incumbent owner, so the
     * offending module author can act on it without cross-referencing anything else.
     *
     * @param messageType   the contested message type
     * @param existingOwner the module that already owns {@code messageType}
     * @return a new PluginModuleException
     */
    public static PluginModuleException responderTypeAlreadyOwned(String messageType, String existingOwner) {
        return new PluginModuleException(ErrorCode.WEBSOCKET_RESPONDER_TYPE_ALREADY_OWNED,
                "Cannot register a panel responder for '" + messageType + "': already owned by module '"
                        + existingOwner + "'.");
    }

    /**
     * Creates an exception for a responder whose future did not complete within the registry's
     * single, one-place timeout (D-27). Used both to complete the caller's future exceptionally and
     * to name the failure in the outbound error reply sent back to the panel.
     *
     * @param messageType   the message type whose responder timed out
     * @param ownerModule   the module that owns the timed-out responder
     * @param elapsedMillis how long the registry waited before giving up
     * @return a new PluginModuleException
     */
    public static PluginModuleException responderTimedOut(String messageType, String ownerModule, long elapsedMillis) {
        return new PluginModuleException(ErrorCode.WEBSOCKET_RESPONDER_TIMEOUT,
                "Panel responder for '" + messageType + "' (owned by module '" + ownerModule
                        + "') did not complete within " + elapsedMillis + "ms.");
    }
}
