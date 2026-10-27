package com.ultikits.ultitools.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Derives the UltiCloud agent WebSocket endpoint from the build-time API URL (as of 6.3.1).
 * <p>
 * {@code https://} becomes {@code wss://}, {@code http://} becomes {@code ws://}, a URL without a
 * scheme is taken as {@code wss://}; a trailing {@code /} is dropped, any base path is kept, and
 * {@code /v1/agent} is appended.
 */
final class AgentEndpoint {

    /** The agent socket's path below the API base. */
    static final String AGENT_PATH = "/v1/agent";

    private static final String SCHEME_SEPARATOR = "://";

    private AgentEndpoint() {
    }

    /**
     * Maps the API URL to the agent endpoint.
     *
     * @param apiUrl the API base URL, for example {@code https://api.ultikits.com}
     * @return the agent endpoint, for example {@code wss://api.ultikits.com/v1/agent}
     * @throws IllegalArgumentException when {@code apiUrl} is blank, has another scheme, or is
     *                                  not a URL with a host; the caller reports it once and does
     *                                  not start the connection
     */
    static URI fromApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("The UltiCloud API URL is blank.");
        }
        String value = apiUrl.trim();
        String scheme;
        String rest;
        int separator = value.indexOf(SCHEME_SEPARATOR);
        if (separator < 0) {
            scheme = "wss";
            rest = value;
        } else {
            String given = value.substring(0, separator).toLowerCase(Locale.ROOT);
            if ("https".equals(given)) {
                scheme = "wss";
            } else if ("http".equals(given)) {
                scheme = "ws";
            } else {
                throw new IllegalArgumentException("The UltiCloud API URL must use http or https.");
            }
            rest = value.substring(separator + SCHEME_SEPARATOR.length());
        }
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(scheme + SCHEME_SEPARATOR + rest + AGENT_PATH);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The UltiCloud API URL is not a valid URL.", e);
        }
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("The UltiCloud API URL is not a valid URL.");
        }
        return uri;
    }
}
