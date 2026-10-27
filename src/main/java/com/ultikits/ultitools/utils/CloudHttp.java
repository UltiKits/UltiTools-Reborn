package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;

import com.google.gson.JsonObject;

/**
 * The single JSON REST exchange the protocol v2 client uses against UltiCloud ({@code /v1/*}).
 * <p>
 * It keeps these exchange rules (the ones the 6.3.0 {@link UltiCloudRequests} exchange kept), because the
 * server credential travels through it:
 * <ul>
 *   <li><b>No redirect is followed.</b> Following one would re-send the {@code Authorization}
 *   header to whatever host the {@code Location} header names; a 3xx is returned as a status.</li>
 *   <li><b>No exception, log line or {@link Response#toString()} carries a request header.</b> The
 *   JDK puts the whole header value into some exception messages (an unchecked
 *   {@code IllegalArgumentException} for a value with CR or LF), so every failure of the exchange
 *   becomes {@link Outcome#IO_ERROR} and the exception itself is dropped.</li>
 *   <li><b>Network failure and HTTP status stay distinct.</b> {@link Outcome#OK} means an exchange
 *   completed, whatever its status; the caller reads {@link Response#status()}.</li>
 *   <li><b>The host is not caller-controlled.</b> The base URL is the jar's build-time
 *   {@code env.yml} {@code api-url} ({@link HttpRequestUtils#getBaseUrl()}); callers pass only a
 *   constant path.</li>
 * </ul>
 * Timeouts: connect 10 s, read 15 s. The response body is read from the error stream for a status of
 * 400 or more and capped at {@value #MAX_BODY_BYTES} bytes. {@code Retry-After} is exposed only in
 * its delta-seconds form ({@link Response#retryAfterSeconds()}).
 * <p>
 * {@link UltiCloudRequests} makes no HTTP request in 6.3.1 (plan 22-17 removed its exchange), so this
 * class is the one REST exchange implementation the framework uses.
 *
 * @since 6.3.1
 */
final class CloudHttp {

    /** Upper bound on the bytes read from one response body. */
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_RETRY_AFTER_SECONDS = 3600L;
    /** Longest digit string parsed as a {@code Retry-After} value; anything longer is clamped. */
    private static final int MAX_RETRY_AFTER_DIGITS = 10;

    private CloudHttp() {
    }

    /**
     * Sends {@code POST <api-url><path>}.
     *
     * @param path         a constant path beginning with {@code /}
     * @param bodyOrNull   the JSON body, sent as UTF-8 with
     *                     {@code Content-Type: application/json; charset=utf-8}; {@code null} sends
     *                     an empty POST without a JSON {@code Content-Type} (the JDK may add its
     *                     default form media type)
     * @param bearerOrNull the value after {@code Bearer } in the {@code Authorization} header, or
     *                     {@code null} for a public route
     * @return the outcome; never {@code null}
     * @throws IllegalStateException if called on the server's primary thread
     */
    static Response postJson(String path, JsonObject bodyOrNull, String bearerOrNull) {
        requireOffPrimaryThread();
        String base = baseUrl();
        if (base == null) {
            return Response.withoutExchange(Outcome.NOT_CONFIGURED);
        }
        byte[] body = bodyOrNull == null ? null : bodyOrNull.toString().getBytes(StandardCharsets.UTF_8);
        return exchange(base + path, body, bearerOrNull);
    }

    private static void requireOffPrimaryThread() {
        // No server means no primary thread to block (plain unit tests).
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "CloudHttp performs blocking network I/O and must not be called on the server's "
                    + "primary thread");
        }
    }

    /** @return the configured base URL without trailing slashes, or {@code null} when blank */
    private static String baseUrl() {
        String base = HttpRequestUtils.getBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            return null;
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // A RuntimeException is caught on purpose: the JDK validates header values and throws an
    // unchecked IllegalArgumentException whose message is the whole value ("Bearer <credential>")
    // when the value contains CR or LF. Letting it escape would break the promise that no exception
    // carries the credential, so every failure of the exchange becomes IO_ERROR.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static Response exchange(String url, byte[] body, String bearer) {
        HttpURLConnection connection = null;
        try {
            // Not caller-controlled: the base is the framework's own env.yml api-url (a jar resource
            // filled at build time) and the path is a constant chosen by this package.
            // nosemgrep: java_ssrf_rule-SSRF
            connection = (HttpURLConnection) new URL(url).openConnection();
            // Never follow a redirect: following it would re-send the bearer to whatever host the
            // Location header names. A 3xx is returned to the caller instead.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            // The JDK silently retries a non-streamed POST after a connection reset. Stream even
            // an empty POST so a credential revoke can never be sent twice by the transport.
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body == null ? 0 : body.length);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            try (OutputStream out = connection.getOutputStream()) {
                if (body != null) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            long retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"));
            return new Response(Outcome.OK, status, readBody(connection, status), retryAfter);
        } catch (IOException | RuntimeException e) {
            // The exception is deliberately dropped, not logged or wrapped: the caller learns only
            // that the exchange did not complete, and no message can carry request headers.
            return Response.withoutExchange(Outcome.IO_ERROR);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * @param header the raw {@code Retry-After} value, or {@code null}
     * @return the delay in seconds clamped to 0..3600, or {@code -1} when the header is absent or not
     *         in delta-seconds form (an HTTP date, a negative number, any other text)
     */
    static long parseRetryAfter(String header) {
        if (header == null) {
            return -1;
        }
        String value = header.trim();
        if (value.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        if (value.length() > MAX_RETRY_AFTER_DIGITS) {
            return MAX_RETRY_AFTER_SECONDS;
        }
        return Math.min(Long.parseLong(value), MAX_RETRY_AFTER_SECONDS);
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
            while (out.size() < MAX_BODY_BYTES && (read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, Math.min(read, MAX_BODY_BYTES - out.size()));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** What happened to one request. */
    enum Outcome {
        /** An HTTP exchange completed; read {@link Response#status()} (any status, 3xx included). */
        OK,
        /** The exchange could not complete (connection, timeout or read failure). */
        IO_ERROR,
        /** The base URL is blank; no request was made. */
        NOT_CONFIGURED
    }

    /** The result of one exchange. Carries no request header and no credential. */
    static final class Response {

        private final Outcome outcome;
        private final int status;
        private final String body;
        private final long retryAfterSeconds;

        private Response(Outcome outcome, int status, String body, long retryAfterSeconds) {
            this.outcome = outcome;
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private static Response withoutExchange(Outcome outcome) {
            return new Response(outcome, -1, null, -1);
        }

        /** @return what happened; never {@code null} */
        Outcome outcome() {
            return outcome;
        }

        /** @return the HTTP status, or {@code -1} when no exchange completed */
        int status() {
            return status;
        }

        /** @return the response body as UTF-8 text, or {@code null} when no exchange completed */
        String body() {
            return body;
        }

        /**
         * @return the {@code Retry-After} delay in seconds (0..3600), or {@code -1} when absent or
         *         not in delta-seconds form
         */
        long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        /** @return the outcome and status only -- never the body or a header */
        @Override
        public String toString() {
            return "CloudHttp.Response{outcome=" + outcome + ", status=" + status + "}";
        }
    }
}
