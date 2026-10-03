package com.ultikits.ultitools.handler;

import org.jetbrains.annotations.ApiStatus;

/**
 * RED stub: mirrors the server console into the panel's log stream. No behaviour yet.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConsoleMirror {

    /** The name of the appender on Log4j's root logger. */
    public static final String APPENDER_NAME = "UltiToolsConsoleMirror";

    private ConsoleMirror() {
    }

    /**
     * Installs the mirror.
     *
     * @return whether it is installed
     */
    public static boolean install() {
        return true;
    }

    /** Removes the mirror. */
    public static void uninstall() {
        // RED stub.
    }

    /**
     * Whether the mirror is installed.
     *
     * @return whether it is installed
     */
    public static boolean isInstalled() {
        return false;
    }
}
