package com.ultikits.ultitools.utils;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.entities.TokenEntity;
import com.ultikits.ultitools.entities.vo.ServerEntityVO;
import com.ultikits.ultitools.utils.SimpleHttpClient.Response;

/**
 * Utility class for HTTP request operations.
 * Provides methods for communicating with the UltiPanel API server.
 * This class handles authentication, server registration, and server updates.
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

    /**
     * Get server information by UUID from the API server.
     *
     * @param uuid  the unique identifier of the server
     * @param token the authentication token
     * @return HttpResponse containing the server information
     */
    protected static Response getServerByUUID(String uuid, TokenEntity token) {
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        headers.put("Content-Type", "application/json");
        return SimpleHttpClient.get(cleanBaseUrl + "/server/getByUUID?uuid=" + uuid, headers);
    }

    /**
     * Register a new server with the API server.
     *
     * @param uuid   the unique identifier for the server
     * @param name   the display name for the server
     * @param port   the port number of the server
     * @param domain the domain name of the server
     * @param ssl    whether SSL is enabled
     * @param token  the authentication token
     * @return HttpResponse containing the registration result
     */
    // GATE-05 group two (08-21): deliberately left raw, not routed to the typed exception
    // hierarchy. Both this method's caller (PluginInitiationUtils.loginWithToken) and its
    // caller in turn (UltiTools.attemptCloudLogin / CloudAuthManager's login callback) catch
    // generic Exception and log e.getMessage() -- neither distinguishes rate limiting from any
    // other registration failure by type, and the failure is internal to this HTTP helper
    // rather than part of the framework's public exception contract surface. See
    // 08-GATE05-TRIAGE.md's rate-limit decision.
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    protected static Response registerServer(String uuid, String name, int port, String domain, boolean ssl, TokenEntity token) {
        if (!ApiRateLimiter.isAllowed("registerServer", 60_000)) {
            throw new RuntimeException("Rate limited: please wait before retrying server registration");
        }
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .name(name)
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        
        // Send the request using FormData format
        Map<String, Object> formMap = new HashMap<>();

        formMap.put("id", token.getUserIdAsString());  // Use the user ID from the token (as a string to avoid float precision issues)
        formMap.put("serverData", new Gson().toJson(serverEntityVO));  // Use JSON serialization to ensure correct formatting
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        
        return SimpleHttpClient.post(cleanBaseUrl + "/editor/register", headers, formMap);
    }

    /**
     * Update an existing server on the API server.
     *
     * @param uuid   the unique identifier of the server
     * @param port   the new port number
     * @param domain the new domain name
     * @param ssl    whether SSL is enabled
     * @param token  the authentication token
     * @return HttpResponse containing the update result
     */
    // GATE-05 group two (08-21): deliberately left raw -- same reasoning as registerServer's
    // identical suppression above. See 08-GATE05-TRIAGE.md's rate-limit decision.
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes")
    protected static Response updateServer(String uuid, int port, String domain, boolean ssl, TokenEntity token) {
        if (!ApiRateLimiter.isAllowed("updateServer", 60_000)) {
            throw new RuntimeException("Rate limited: please wait before retrying server update");
        }
        String cleanBaseUrl = getBaseUrl() != null ? getBaseUrl().trim() : "";
        ServerEntityVO serverEntityVO = ServerEntityVO.builder()
                .uuid(uuid)
                .port(port)
                .ssl(ssl)
                .domain(domain)
                .build();
        
        // Send the request using FormData format
        Map<String, Object> formMap = new HashMap<>();
        formMap.put("id", token.getUserIdAsString());  // Use the user ID from the token (as a string to avoid float precision issues)
        formMap.put("serverData", new Gson().toJson(serverEntityVO));  // Use JSON serialization to ensure correct formatting
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token.getAccess_token());
        
        return SimpleHttpClient.post(cleanBaseUrl + "/editor/updateServer", headers, formMap);
    }

}
