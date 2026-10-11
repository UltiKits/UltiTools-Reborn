package com.ultikits.ultitools.utils;

/**
 * Lets tests outside this package point {@link PluginInstallUtils} at a catalogue stub; the
 * setters themselves are package-private so production code cannot reach them.
 */
public final class PluginInstallUtilsTestAccess {

    private PluginInstallUtilsTestAccess() {
    }

    /**
     * @param url the stub catalogue's base URL
     */
    public static void setBaseUrl(String url) {
        PluginInstallUtils.setBaseUrlForTesting(url);
    }

    /** Forgets the stub's base URL. */
    public static void resetBaseUrl() {
        PluginInstallUtils.resetBaseUrl();
    }
}
