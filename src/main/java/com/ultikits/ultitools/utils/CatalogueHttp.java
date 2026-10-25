package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The HTTP the module catalogue is asked with (plan 22-18, S-27, S-61).
 *
 * <p>The catalogue is public, so no request carries an {@code Authorization} header, and no
 * redirect is followed: a 3xx is reported as such, so a compromised catalogue cannot send the
 * client to another host. A network failure and an HTTP status stay distinct: {@link Kind#OK}
 * means an exchange completed, whatever its status.
 *
 * <p>{@link #download} streams into a file while hashing what it wrote and keeps the stream open
 * only while the body is at most the caller's limit; a body beyond it is cut at the limit, which
 * is how a download larger than its declared {@code sizeBytes} is aborted while streaming rather
 * than after. Timeouts: connect 10 s, read 15 s.
 *
 * @since 6.3.1
 */
final class CatalogueHttp {

    /** The response header carrying the SHA-256 of the body the catalogue served. */
    static final String SHA256_HEADER = "X-Content-SHA256";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int CHUNK = 8192;

    private CatalogueHttp() {
    }

    /** What happened to one request. */
    enum Kind {
        /** An HTTP exchange completed; read the status (any status, 3xx included). */
        OK,
        /** No base URL is configured; no request was made. */
        NOT_CONFIGURED,
        /** The exchange could not complete (connection, timeout or read failure). */
        IO_ERROR,
        /** A 3xx answer; it was not followed and nothing else was requested. */
        REDIRECT,
        /** The body was longer than the caller's limit and was cut at it. */
        OVERSIZE
    }

    /** A JSON answer. Carries no request header. */
    static final class Json {
        private final Kind kind;
        private final int status;
        private final String body;

        private Json(Kind kind, int status, String body) {
            this.kind = kind;
            this.status = status;
            this.body = body;
        }

        /** @return what happened */
        Kind kind() {
            return kind;
        }

        /** @return the HTTP status, or {@code -1} when no exchange completed */
        int status() {
            return status;
        }

        /** @return the body as UTF-8 text, or {@code null} when no exchange completed */
        String body() {
            return body;
        }
    }

    /** A finished download. Carries no request header. */
    static final class Download {
        private final Kind kind;
        private final int status;
        private final long bytes;
        private final String sha256Hex;
        private final String headerSha256;

        private Download(Kind kind, int status, long bytes, String sha256Hex, String headerSha256) {
            this.kind = kind;
            this.status = status;
            this.bytes = bytes;
            this.sha256Hex = sha256Hex;
            this.headerSha256 = headerSha256;
        }

        /** @return what happened */
        Kind kind() {
            return kind;
        }

        /** @return the HTTP status, or {@code -1} when no exchange completed */
        int status() {
            return status;
        }

        /** @return the number of body bytes written */
        long bytes() {
            return bytes;
        }

        /** @return the lower-case hex SHA-256 of the bytes written */
        String sha256Hex() {
            return sha256Hex;
        }

        /** @return the {@code X-Content-SHA256} header, or {@code null} when absent */
        String headerSha256() {
            return headerSha256;
        }
    }

    /**
     * Sends {@code GET <api-url><pathAndQuery>}.
     *
     * @param pathAndQuery a path beginning with {@code /}, with its query when it has one
     * @return the answer; never {@code null}
     */
    static Json getJson(String pathAndQuery) {
        String base = baseUrl();
        if (base == null) {
            return new Json(Kind.NOT_CONFIGURED, -1, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = open(base + pathAndQuery);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (isRedirect(status)) {
                return new Json(Kind.REDIRECT, status, null);
            }
            return new Json(Kind.OK, status, readBody(connection, status));
        } catch (IOException | RuntimeException e) {
            // Deliberately dropped: no exception message can carry a request header.
            return new Json(Kind.IO_ERROR, -1, null);
        } finally {
            disconnect(connection);
        }
    }

    /**
     * Streams {@code url} into {@code target}, keeping at most {@code maxBytes} bytes of it.
     *
     * @param url      the absolute URL to read
     * @param target   the file to write; created or replaced
     * @param maxBytes the most bytes to keep
     * @return the outcome; never {@code null}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // every failure of the exchange is IO_ERROR
    static Download download(String url, Path target, long maxBytes) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            String sha256Header = connection.getHeaderField(SHA256_HEADER);
            if (isRedirect(status)) {
                return new Download(Kind.REDIRECT, status, 0, null, sha256Header);
            }
            if (status < 200 || status > 299) {
                // The body of a refusal is not kept: the status says what happened.
                drain(connection, status);
                return new Download(Kind.OK, status, 0, null, sha256Header);
            }
            return copy(connection, target, maxBytes, sha256Header);
        } catch (IOException | RuntimeException e) {
            // Deliberately dropped: no exception message can carry a request header.
            return new Download(Kind.IO_ERROR, -1, 0, null, null);
        } finally {
            disconnect(connection);
        }
    }

    /**
     * @param bytes any bytes
     * @return the lower-case hex SHA-256 of them
     */
    static String sha256Hex(byte[] bytes) {
        MessageDigest digest = digest();
        return hex(digest.digest(bytes));
    }

    private static Download copy(HttpURLConnection connection, Path target, long maxBytes, String sha256Header)
            throws IOException {
        MessageDigest digest = digest();
        long total = 0;
        boolean oversize = false;
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[CHUNK];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (total + read > maxBytes) {
                    int keep = (int) (maxBytes - total);
                    if (keep > 0) {
                        out.write(buffer, 0, keep);
                        digest.update(buffer, 0, keep);
                        total += keep;
                    }
                    oversize = true;
                    break;
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        String hex = hex(digest.digest());
        return new Download(oversize ? Kind.OVERSIZE : Kind.OK, 200, total, hex, sha256Header);
    }

    private static void drain(HttpURLConnection connection, int status) {
        InputStream in = status >= 400 ? connection.getErrorStream() : null;
        if (in == null) {
            return;
        }
        try (InputStream stream = in) {
            byte[] buffer = new byte[CHUNK];
            while (stream.read(buffer) != -1) {
                // Discarded: only the status is used.
            }
        } catch (IOException ignored) {
            // The connection is closed anyway.
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        // The host is the framework's own build-time api-url; a catalogue-supplied path is joined to
        // it by CatalogueClient only when it stays on that same origin.
        // nosemgrep: java_ssrf_rule-SSRF
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        return connection;
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            connection.disconnect();
        }
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status <= 399;
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[CHUNK];
            int read;
            while (out.size() < MAX_JSON_BYTES && (read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, Math.min(read, MAX_JSON_BYTES - out.size()));
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** @return the framework's catalogue base URL without trailing slashes, or {@code null} */
    static String baseUrl() {
        String base = PluginInstallUtils.getBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            return null;
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}
