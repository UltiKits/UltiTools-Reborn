package com.ultikits.ultitools.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;

/**
 * The UltiCloud base URL, plus the three protected protocol v1 REST calls kept so 6.3.0 subclasses
 * still link.
 * <p>
 * As of 6.3.1 the base URL ({@code env.yml} {@code api-url}) is read by the protocol 2.0.0 client,
 * and the v1 calls ({@code /server/getByUUID}, {@code /editor/register}, {@code /editor/updateServer})
 * send nothing: protocol 2.0.0 has no v1 REST cloud. Each returns a failure response with status
 * {@code -1} (no HTTP exchange happened) and writes one INFO line per run; all three are deprecated
 * for removal in 6.4.0.
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

    /** Status of the failure response the removed v1 calls return: no HTTP exchange happened. */
    private static final int NO_EXCHANGE = -1;

    /** Body of that failure response. */
    private static final String REFUSED_BODY = "UltiCloud protocol v1 was removed; no request was sent.";

    private static final Logger NOTICE_LOGGER = Logger.getLogger(HttpRequestUtils.class.getName());

    private static final AtomicBoolean GET_SERVER_NOTICE = new AtomicBoolean();
    private static final AtomicBoolean REGISTER_NOTICE = new AtomicBoolean();
    private static final AtomicBoolean UPDATE_NOTICE = new AtomicBoolean();

    /**
     * Looked up a server by its installation UUID in the protocol v1 cloud. Sends nothing as of 6.3.1.
     *
     * @param uuid  ignored, nothing is sent
     * @param token ignored, nothing is sent
     * @return a failure response with status {@code -1} and a body naming the removal
     * @deprecated There is no replacement: a protocol 2.0.0 server is linked with
     *     {@code /ulticloud login} and identified by its server credential. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    @SuppressWarnings("removal") // the v1 signature names the deprecated TokenEntity and SimpleHttpClient
    protected static SimpleHttpClient.Response getServerByUUID(String uuid,
            com.ultikits.ultitools.entities.TokenEntity token) {
        return refused(GET_SERVER_NOTICE, "getServerByUUID");
    }

    /**
     * Registered a server with the protocol v1 cloud. Sends nothing as of 6.3.1.
     *
     * @param uuid   ignored, nothing is sent
     * @param name   ignored, nothing is sent
     * @param port   ignored, nothing is sent
     * @param domain ignored, nothing is sent
     * @param ssl    ignored, nothing is sent
     * @param token  ignored, nothing is sent
     * @return a failure response with status {@code -1} and a body naming the removal
     * @deprecated There is no replacement: {@code /ulticloud login} links a protocol 2.0.0 server
     *     through the device authorization. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    @SuppressWarnings("removal") // the v1 signature names the deprecated TokenEntity and SimpleHttpClient
    protected static SimpleHttpClient.Response registerServer(String uuid, String name, int port, String domain,
            boolean ssl, com.ultikits.ultitools.entities.TokenEntity token) {
        return refused(REGISTER_NOTICE, "registerServer");
    }

    /**
     * Updated a server's address in the protocol v1 cloud. Sends nothing as of 6.3.1.
     *
     * @param uuid   ignored, nothing is sent
     * @param port   ignored, nothing is sent
     * @param domain ignored, nothing is sent
     * @param ssl    ignored, nothing is sent
     * @param token  ignored, nothing is sent
     * @return a failure response with status {@code -1} and a body naming the removal
     * @deprecated There is no replacement: a protocol 2.0.0 server reports its own state over its
     *     connection. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    @SuppressWarnings("removal") // the v1 signature names the deprecated TokenEntity and SimpleHttpClient
    protected static SimpleHttpClient.Response updateServer(String uuid, int port, String domain, boolean ssl,
            com.ultikits.ultitools.entities.TokenEntity token) {
        return refused(UPDATE_NOTICE, "updateServer");
    }

    /** The removed calls' failure response; the first call of each method in a run logs one INFO line. */
    @SuppressWarnings("removal") // builds the deprecated SimpleHttpClient.Response the v1 signatures return
    private static SimpleHttpClient.Response refused(AtomicBoolean notice, String method) {
        if (notice.compareAndSet(false, true)) {
            NOTICE_LOGGER.info("UltiCloud: HttpRequestUtils#" + method + " called the protocol v1 REST cloud, "
                    + "which UltiCloud no longer serves, so no request was sent and a failure response was "
                    + "returned; it is deprecated and removed in 6.4.0.");
        }
        return new SimpleHttpClient.Response(NO_EXCHANGE, REFUSED_BODY);
    }

    /** Test-only: forgets that this run already logged each removed call's INFO line. */
    static void resetV1NoticeForTesting() {
        GET_SERVER_NOTICE.set(false);
        REGISTER_NOTICE.set(false);
        UPDATE_NOTICE.set(false);
    }

}
