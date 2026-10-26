package com.ultikits.ultitools.utils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

import com.ultikits.ultitools.websocket.CloudPlayerLinks;

/**
 * The removed UltiCloud v1 request helper; every call is answered {@link Outcome#PATH_NOT_ALLOWED}.
 *
 * <p>Protocol 2.0.0 has no v1 REST cloud, so the two magic-link requests this class used to make
 * ({@code POST /auth/magic-link} and {@code GET /auth/magic-link/poll}) no longer reach anything and
 * are refused without opening a connection. The first such call in a run writes one INFO line that
 * names the replacement.</p>
 *
 * <p><b>Replacement.</b> UltiLogin 2.1.0 uses {@link CloudPlayerLinks}: the framework asks the
 * gateway for a one-time player link and the gateway reports how the web user proved the game
 * identity, so no module talks to a cloud endpoint itself and no module ever holds the server
 * credential.</p>
 *
 * <p><b>Compatibility.</b> Every public type and member of 6.3.0 is still present with the same
 * signatures; only behaviour and this documentation changed. Deprecation and removal follow in a
 * later release. UltiLogin 2.0.0 maps {@link Outcome#PATH_NOT_ALLOWED} to a link error and never
 * retries, so its {@code /panel} fails in the way it already handles.</p>
 *
 * <p><b>Threading.</b> The methods no longer perform network I/O, but they keep the 6.3.0 contract
 * and still refuse the server's primary thread with {@link IllegalStateException}; callers that
 * already hop to an asynchronous task keep working unchanged.</p>
 *
 * @since 6.3.0
 */
public final class UltiCloudRequests {

    private static final Logger LOGGER = Logger.getLogger(UltiCloudRequests.class.getName());

    /** The request path UltiLogin 2.0.0 used to create a link. */
    private static final String CREATE_PATH = "/auth/magic-link";
    /** The request path UltiLogin 2.0.0 used to poll that link. */
    private static final String POLL_PATH = "/auth/magic-link/poll";

    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    private UltiCloudRequests() {
    }

    /**
     * Answers {@link Outcome#PATH_NOT_ALLOWED} without opening a connection; no request is made.
     *
     * @param path the request path; only the two removed magic-link paths write a diagnostic
     * @param jsonBody the JSON request body, ignored because nothing is sent
     * @return {@link Outcome#PATH_NOT_ALLOWED} with no status code and no body
     * @throws IllegalStateException if called on the server's primary thread
     * @deprecated Use {@link CloudPlayerLinks#create} to link a player; protocol 2.0.0 has no v1
     *     REST cloud, so this call is always refused. Removed in 6.4.0.
     * @removeIn 6.4.0
     */
    @Deprecated(since = "6.3.1", forRemoval = true)
    public static Result post(String path, String jsonBody) {
        requireOffPrimaryThread();
        announceOnce(CREATE_PATH.equals(path) || POLL_PATH.equals(path));
        return Result.withoutExchange(Outcome.PATH_NOT_ALLOWED);
    }

    /**
     * Answers {@link Outcome#PATH_NOT_ALLOWED} without opening a connection; no request is made.
     *
     * @param path the request path; only the two removed magic-link paths write a diagnostic
     * @param query query parameters, ignored because nothing is sent
     * @return {@link Outcome#PATH_NOT_ALLOWED} with no status code and no body
     * @throws IllegalStateException if called on the server's primary thread
     */
    public static Result get(String path, Map<String, String> query) {
        requireOffPrimaryThread();
        announceOnce(POLL_PATH.equals(path) || CREATE_PATH.equals(path));
        return Result.withoutExchange(Outcome.PATH_NOT_ALLOWED);
    }

    private static void requireOffPrimaryThread() {
        // No server means no primary thread to block (plain unit tests of a calling module).
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                "UltiCloudRequests performs blocking network I/O and must not be called on the "
                    + "server's primary thread; call it from an asynchronous task");
        }
    }

    private static void announceOnce(boolean removedCall) {
        if (removedCall && ANNOUNCED.compareAndSet(false, true)) {
            LOGGER.info("UltiCloud: protocol 2.0.0 removed the v1 magic-link endpoint, so this call "
                    + "cannot reach UltiCloud and returns PATH_NOT_ALLOWED; UltiLogin 2.1.0 uses "
                    + "CloudPlayerLinks instead.");
        }
    }

    /** Drops the once-per-run announcement so a test can observe it again. */
    static void resetAnnouncementForTesting() {
        ANNOUNCED.set(false);
    }

    /**
     * What happened to a request.
     *
     * @since 6.3.0
     */
    public enum Outcome {
        /**
         * An HTTP exchange completed; read {@link Result#getStatusCode()}. Since 6.3.1 no call makes
         * an exchange, so this value is only kept for source compatibility.
         */
        OK,
        /**
         * This server holds no valid UltiCloud session. Since 6.3.1 no call reads a session, so this
         * value is only kept for source compatibility.
         */
        NOT_CONNECTED,
        /** The call is not on the allow-list, which since 6.3.1 is every call; no request was made. */
        PATH_NOT_ALLOWED,
        /**
         * The exchange could not complete. Since 6.3.1 no call makes an exchange, so this value is
         * only kept for source compatibility.
         */
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
