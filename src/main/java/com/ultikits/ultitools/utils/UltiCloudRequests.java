package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.bukkit.Bukkit;

import com.ultikits.ultitools.entities.TokenEntity;

/**
 * Performs an authenticated UltiCloud request on a module's behalf, without ever handing the module
 * the server's UltiCloud token.
 * <p>
 * The framework deliberately never exposes the cloud credential to module code: the session that
 * holds it is package-private, and no public static member of this package accepts or returns a
 * {@link TokenEntity} (the Phase 16 token-non-exposure rule, enforced by a structural test). Some
 * module features nevertheless need UltiCloud to know that a request really comes from this server
 * -- UltiLogin's {@code /panel} link is the first. This class is the only way to get that: the
 * framework attaches the current session's bearer to one connection it opens itself, sends the
 * request, and returns only the HTTP status and response body.
 * <p>
 * <b>Allow-list (exact match per method).</b> Only these two requests are made:
 * <ul>
 *   <li>{@code POST /auth/magic-link} -- create a web login link ({@link #post(String, String)})</li>
 *   <li>{@code GET /auth/magic-link/poll} -- poll that link's outcome ({@link #get(String, Map)})</li>
 * </ul>
 * Anything else -- another path, an allowed path with the other method, or a path containing
 * {@code ..}, {@code //}, {@code \}, {@code %}, {@code ?} or {@code #} -- is answered with
 * {@link Outcome#PATH_NOT_ALLOWED} and no request is made. Query parameters travel only through the
 * {@code query} map. The list grows only by a documented contract change in a framework release; a
 * module cannot widen it.
 * <p>
 * <b>The token is never exposed.</b> It is never returned, never logged, never placed in an exception
 * message or in {@link Result#toString()}, and no public member of this class or of {@link Result}
 * is typed {@link TokenEntity}. Redirects are not followed, so the bearer is never re-sent to the
 * host a {@code Location} header names; a 3xx is returned to the caller as an ordinary result.
 * <p>
 * <b>Threading.</b> Both methods block on network I/O (connect timeout 10 s, read timeout 30 s) and
 * refuse to run on the server's primary thread with an {@link IllegalStateException}. Call them from
 * an asynchronous task and hop back to the primary thread to act on the result.
 * <p>
 * <b>Side effects.</b> Besides the single HTTP exchange this class writes nothing: no file, no
 * configuration, no log line.
 * <p>
 * The order of checks is fixed: primary thread, then path, then session ({@link Outcome#NOT_CONNECTED}
 * when this server is not logged in to UltiCloud -- for example after {@code /ulticloud logout}),
 * then the HTTP exchange ({@link Outcome#OK} for any completed exchange whatever its status,
 * {@link Outcome#IO_ERROR} when the exchange could not complete).
 *
 * @since 6.3.0
 */
public final class UltiCloudRequests {

    /** The one path {@link #post(String, String)} accepts. */
    private static final String CREATE_PATH = "/auth/magic-link";
    /** The one path {@link #get(String, Map)} accepts. */
    private static final String POLL_PATH = "/auth/magic-link/poll";
    /** Character sequences that are never part of an allowed path, checked before the exact match. */
    private static final String[] FORBIDDEN_SEQUENCES = {"..", "//", "\\", "%", "?", "#"};
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private UltiCloudRequests() {
    }

    /**
     * Sends {@code POST <api-url><path>} with {@code jsonBody} and this server's UltiCloud bearer.
     *
     * @param path     must be exactly {@code /auth/magic-link}
     * @param jsonBody the JSON request body, sent as UTF-8 with
     *                 {@code Content-Type: application/json; charset=utf-8}; {@code null} sends an
     *                 empty body
     * @return the outcome; never {@code null}
     * @throws IllegalStateException if called on the server's primary thread
     */
    public static Result post(String path, String jsonBody) {
        requireOffPrimaryThread();
        if (!isAllowed(path, CREATE_PATH)) {
            return Result.withoutExchange(Outcome.PATH_NOT_ALLOWED);
        }
        String bearer = currentBearer();
        if (bearer == null) {
            return Result.withoutExchange(Outcome.NOT_CONNECTED);
        }
        byte[] body = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        return exchange("POST", path, bearer, body);
    }

    /**
     * Sends {@code GET <api-url><path>?<query>} with this server's UltiCloud bearer.
     *
     * @param path  must be exactly {@code /auth/magic-link/poll}
     * @param query query parameters, each name and value URL-encoded as UTF-8; {@code null} or empty
     *              sends no query string; an entry with a {@code null} name is skipped and a
     *              {@code null} value is sent as an empty value
     * @return the outcome; never {@code null}
     * @throws IllegalStateException if called on the server's primary thread
     */
    public static Result get(String path, Map<String, String> query) {
        requireOffPrimaryThread();
        if (!isAllowed(path, POLL_PATH)) {
            return Result.withoutExchange(Outcome.PATH_NOT_ALLOWED);
        }
        String bearer = currentBearer();
        if (bearer == null) {
            return Result.withoutExchange(Outcome.NOT_CONNECTED);
        }
        String target = path + encodeQuery(query);
        return exchange("GET", target, bearer, null);
    }

    private static void requireOffPrimaryThread() {
        // No server means no primary thread to block (plain unit tests of a calling module).
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "UltiCloudRequests performs blocking network I/O and must not be called on the "
                    + "server's primary thread; call it from an asynchronous task");
        }
    }

    private static boolean isAllowed(String path, String allowedPath) {
        if (path == null) {
            return false;
        }
        for (String forbidden : FORBIDDEN_SEQUENCES) {
            if (path.contains(forbidden)) {
                return false;
            }
        }
        return allowedPath.equals(path);
    }

    /** @return the current session's access token value, or {@code null} when not connected */
    private static String currentBearer() {
        CloudSession session = CloudSession.current();
        if (!session.hasValidToken()) {
            return null;
        }
        TokenEntity token = session.getToken();
        return token == null ? null : token.getAccess_token();
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            builder.append(builder.length() == 0 ? '?' : '&');
            builder.append(urlEncode(entry.getKey()));
            builder.append('=');
            builder.append(urlEncode(entry.getValue() == null ? "" : entry.getValue()));
        }
        return builder.toString();
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is a charset every JVM must support.
            throw new IllegalStateException(e);
        }
    }

    private static Result exchange(String method, String pathAndQuery, String bearer, byte[] body) {
        String base = HttpRequestUtils.getBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            return Result.withoutExchange(Outcome.IO_ERROR);
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + pathAndQuery).openConnection();
            // Never follow a redirect: following it would re-send the bearer to whatever host the
            // Location header names. A 3xx is returned to the caller instead.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            return new Result(Outcome.OK, status, readBody(connection, status));
        } catch (IOException e) {
            // The exception is deliberately dropped, not logged or wrapped: the caller learns only
            // that the exchange did not complete, and no message can carry request headers.
            return Result.withoutExchange(Outcome.IO_ERROR);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * What happened to a request.
     *
     * @since 6.3.0
     */
    public enum Outcome {
        /** An HTTP exchange completed; read {@link Result#getStatusCode()} (any 2xx-5xx, including 3xx). */
        OK,
        /** This server is not logged in to UltiCloud; no request was made. */
        NOT_CONNECTED,
        /** The method and path are not on the allow-list; no request was made. */
        PATH_NOT_ALLOWED,
        /** The exchange could not complete (connection, timeout or read failure). */
        IO_ERROR
    }

    /**
     * The result of one call: an {@link Outcome}, and for a completed exchange its status and body.
     * Carries no request header and no credential.
     *
     * @since 6.3.0
     */
    public static final class Result {

        private final Outcome outcome;
        private final int statusCode;
        private final String body;

        private Result(Outcome outcome, int statusCode, String body) {
            this.outcome = outcome;
            this.statusCode = statusCode;
            this.body = body;
        }

        private static Result withoutExchange(Outcome outcome) {
            return new Result(outcome, -1, null);
        }

        /** @return what happened; never {@code null} */
        public Outcome getOutcome() {
            return outcome;
        }

        /** @return the HTTP status code, or {@code -1} when no HTTP exchange completed */
        public int getStatusCode() {
            return statusCode;
        }

        /** @return the response body as UTF-8 text, or {@code null} when no HTTP exchange completed */
        public String getBody() {
            return body;
        }

        /** @return the outcome and status only -- never the body, a header or a credential */
        @Override
        public String toString() {
            return "UltiCloudRequests.Result{outcome=" + outcome + ", statusCode=" + statusCode + "}";
        }
    }
}
