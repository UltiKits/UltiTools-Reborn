package com.ultikits.ultitools.utils;

import com.ultikits.ultitools.UltiTools;

/**
 * The UltiCloud base URL ({@code env.yml} {@code api-url}), read by the protocol 2.1.0 client.
 * <p>
 * The three protected protocol v1 REST calls this class kept for 6.3.0 subclasses were deprecated in
 * 6.3.1 and removed in 6.4.0.
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class HttpRequestUtils {

    /**
     * Base URL for the API server (lazy initialized).
     */
    private static volatile String baseUrl;

    /**
     * Custom base URL for testing purposes.
     */
    private static String customBaseUrl;

    /**
     * Get the base URL for API calls.
     * Uses lazy initialization to avoid requiring UltiTools at class loading time.
     *
     * @return the base URL
     */
    static String getBaseUrl() {
        if (customBaseUrl != null) {
            return customBaseUrl;
        }
        if (baseUrl == null) {
            synchronized (HttpRequestUtils.class) {
                if (baseUrl == null) {
                    baseUrl = UltiTools.getEnv().getString("api-url");
                }
            }
        }
        return baseUrl;
    }

    /**
     * Set a custom base URL for testing purposes.
     * This should only be used in unit tests.
     *
     * @param url the custom base URL, or null to reset to default
     */
    static void setBaseUrlForTesting(String url) {
        customBaseUrl = url;
    }

    /**
     * Reset the base URL to force re-initialization.
     * This should only be used in unit tests.
     */
    static void resetBaseUrl() {
        baseUrl = null;
        customBaseUrl = null;
    }
}
