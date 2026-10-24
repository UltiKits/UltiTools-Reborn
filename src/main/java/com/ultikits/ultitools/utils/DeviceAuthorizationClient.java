package com.ultikits.ultitools.utils;

import java.util.logging.Level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.UltiTools;

/**
 * The two REST calls of the RFC 8628 device authorization grant against UltiCloud protocol v2:
 * {@code POST /v1/device/authorize} and {@code POST /v1/device/token}. Both routes are public; no
 * credential travels with them.
 * <p>
 * Neither the device code nor a granted secret ever appears in a log line, an exception or a
 * {@code toString()} produced here: the device code is a bearer for the grant during its lifetime.
 *
 * @since 6.3.1
 */
class DeviceAuthorizationClient {

    static final String AUTHORIZE_PATH = "/v1/device/authorize";
    static final String TOKEN_PATH = "/v1/device/token";
    /** The protocol major version this framework speaks. */
    static final int PROTOCOL_VERSION = 2;
    /** RFC 8628 §3.2: the poll interval to use when the response names none. */
    static final long DEFAULT_INTERVAL_SECONDS = 5;
    /** The device code lifetime to assume when the response names none. */
    static final long DEFAULT_EXPIRES_IN_SECONDS = 600;
    /** The longest backend-supplied text (message, reason) this class passes on, in characters. */
    static final int MAX_BACKEND_TEXT = 200;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_TOO_MANY_REQUESTS = 429;
    private static final int STATUS_SERVER_ERROR = 500;

    /**
     * Requests a device code.
     *
     * @param frameworkVersion        the running framework version, sent verbatim
     * @param suggestedNameOrNull     the default server name for the confirmation page, or
     *                                {@code null} to omit the field
     * @param legacyServerUuidOrNull  this server's normalised install id, or {@code null} to omit the
     *                                field
     * @return the grant, or the reason it could not be obtained; never {@code null}
     */
    AuthorizeResult authorize(String frameworkVersion, String suggestedNameOrNull, String legacyServerUuidOrNull) {
        JsonObject body = new JsonObject();
        body.addProperty("clientKind", "server");
        body.addProperty("framework", frameworkVersion);
        body.addProperty("protocol", PROTOCOL_VERSION);
        if (suggestedNameOrNull != null) {
            body.addProperty("suggestedName", suggestedNameOrNull);
        }
        if (legacyServerUuidOrNull != null) {
            body.addProperty("legacyServerUuid", legacyServerUuidOrNull);
        }
        CloudHttp.Response response = CloudHttp.postJson(AUTHORIZE_PATH, body, null);
        if (response.outcome() == CloudHttp.Outcome.NOT_CONFIGURED) {
            return AuthorizeResult.failed("API URL not configured", -1);
        }
        if (response.outcome() == CloudHttp.Outcome.IO_ERROR) {
            return AuthorizeResult.failed("UltiCloud could not be reached", -1);
        }
        if (response.status() == STATUS_TOO_MANY_REQUESTS) {
            long wait = response.retryAfterSeconds();
            return AuthorizeResult.failed(wait >= 0
                ? "UltiCloud is limiting device-code requests; try again in " + wait + " seconds"
                : "UltiCloud is limiting device-code requests; try again later", wait);
        }
        if (response.status() != 200) {
            return AuthorizeResult.failed(describeFailure(response), -1);
        }
        JsonObject data = dataObject(response.body());
        if (data == null) {
            return AuthorizeResult.failed("UltiCloud sent an unreadable device-code response", -1);
        }
        String deviceCode = string(data, "deviceCode");
        String userCode = string(data, "userCode");
        String verificationUri = string(data, "verificationUri");
        if (isBlank(deviceCode) || isBlank(userCode) || isBlank(verificationUri)) {
            return AuthorizeResult.failed("UltiCloud sent an incomplete device-code response", -1);
        }
        String complete = string(data, "verificationUriComplete");
        long expiresIn = positiveLong(data, "expiresIn", DEFAULT_EXPIRES_IN_SECONDS);
        long interval = positiveLong(data, "interval", DEFAULT_INTERVAL_SECONDS);
        // The three presentable values go to the console verbatim; strip control characters so a
        // response cannot rewrite the operator's terminal.
        String uri = sanitise(verificationUri);
        return AuthorizeResult.granted(new Grant(deviceCode, sanitise(userCode), uri,
                isBlank(complete) ? uri : sanitise(complete), expiresIn, interval));
    }

    /**
     * Polls for the outcome of {@code deviceCode} once.
     *
     * @param deviceCode the device code from {@link #authorize}
     * @return the classified outcome; never {@code null}
     */
    PollResult pollToken(String deviceCode) {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        CloudHttp.Response response = CloudHttp.postJson(TOKEN_PATH, body, null);
        if (response.outcome() != CloudHttp.Outcome.OK) {
            return PollResult.transientFailure(-1);
        }
        int status = response.status();
        if (status == 200) {
            return readGrant(response.body());
        }
        if (status == STATUS_TOO_MANY_REQUESTS) {
            return PollResult.transientFailure(response.retryAfterSeconds());
        }
        if (status >= STATUS_SERVER_ERROR) {
            return PollResult.transientFailure(-1);
        }
        JsonObject error = errorObject(response.body());
        String code = error == null ? null : string(error, "code");
        if (code == null) {
            // A 400 without a readable error body (a proxy page, a truncated answer) says nothing
            // about the grant; another status without one is not the device endpoint answering.
            return status == STATUS_BAD_REQUEST
                ? PollResult.transientFailure(-1)
                : PollResult.rejected("http_" + status, "UltiCloud answered HTTP " + status);
        }
        if (status == STATUS_BAD_REQUEST) {
            switch (code) {
                case "authorization_pending":
                    return PollResult.of(PollResult.Kind.PENDING);
                case "slow_down":
                    return PollResult.of(PollResult.Kind.SLOW_DOWN);
                case "expired_token":
                    return PollResult.of(PollResult.Kind.EXPIRED);
                case "access_denied":
                    return PollResult.of(PollResult.Kind.DENIED);
                default:
                    break;
            }
        }
        String safeCode = sanitise(code);
        return PollResult.rejected(safeCode == null ? "unknown" : safeCode, sanitise(string(error, "message")));
    }

    /** Maps a 200 to {@code GRANTED}, or to {@code TRANSIENT} with a WARNING naming what is missing. */
    private static PollResult readGrant(String responseBody) {
        JsonObject data = dataObject(responseBody);
        if (data == null) {
            warnUnusableGrant("the response is not a {data: {...}} JSON object");
            return PollResult.transientFailure(-1);
        }
        String secret = string(data, ServerCredential.FIELD_CREDENTIAL);
        if (!ServerCredential.isWellFormedSecret(secret)) {
            warnUnusableGrant("the credential field is missing or lacks the ucs_ prefix");
            return PollResult.transientFailure(-1);
        }
        String serverId = string(data, ServerCredential.FIELD_SERVER_ID);
        if (isBlank(serverId)) {
            warnUnusableGrant("the serverId field is missing");
            return PollResult.transientFailure(-1);
        }
        return PollResult.granted(new ServerCredential(secret, string(data, ServerCredential.FIELD_CREDENTIAL_ID),
            serverId, string(data, ServerCredential.FIELD_USER_ID), string(data, ServerCredential.FIELD_SERVER_NAME),
            System.currentTimeMillis()));
    }

    /** Names the defect only -- never a value from the response, which may hold the secret. */
    private static void warnUnusableGrant(String defect) {
        UltiTools.getInstance().getLogger().log(Level.WARNING,
            "UltiCloud: ignored an unusable device-login grant (" + defect + "); polling continues.");
    }

    /** @return {@code UltiCloud answered HTTP <status>}, plus the backend error code and message */
    private static String describeFailure(CloudHttp.Response response) {
        StringBuilder reason = new StringBuilder("UltiCloud answered HTTP ").append(response.status());
        JsonObject error = errorObject(response.body());
        if (error != null) {
            String code = sanitise(string(error, "code"));
            String message = sanitise(string(error, "message"));
            if (code != null) {
                reason.append(" (").append(code).append(')');
            }
            if (message != null) {
                reason.append(": ").append(message);
            }
        }
        return reason.toString();
    }

    /**
     * @param text backend-supplied text, or {@code null}
     * @return the text with control characters replaced by spaces, trimmed and cut to
     *         {@value #MAX_BACKEND_TEXT} characters; {@code null} when nothing remains
     */
    static String sanitise(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(text.length(), MAX_BACKEND_TEXT));
        for (int i = 0; i < text.length() && builder.length() < MAX_BACKEND_TEXT; i++) {
            char c = text.charAt(i);
            builder.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** @return the {@code data} object of a {@code {data: {...}}} body, or {@code null} */
    static JsonObject dataObject(String body) {
        JsonObject root = parseObject(body);
        if (root == null) {
            return null;
        }
        JsonElement data = root.get("data");
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
    }

    /** @return the {@code error} object of a {@code {error: {...}}} body, or {@code null} */
    static JsonObject errorObject(String body) {
        JsonObject root = parseObject(body);
        if (root == null) {
            return null;
        }
        JsonElement error = root.get("error");
        return error != null && error.isJsonObject() ? error.getAsJsonObject() : null;
    }

    // Gson's parser throws an unchecked JsonParseException (and IllegalStateException for a
    // top-level non-object) on untrusted input; any of those simply means "not a JSON object".
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static JsonObject parseObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return the member as a string when it is a JSON string, otherwise {@code null} */
    static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    private static long positiveLong(JsonObject object, String member, long fallback) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        long number = value.getAsLong();
        return number > 0 ? number : fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** A device code and how to present it. {@link #toString()} omits the device code. */
    static final class Grant {

        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final String verificationUriComplete;
        private final long expiresInSeconds;
        private final long intervalSeconds;

        Grant(String deviceCode, String userCode, String verificationUri, String verificationUriComplete,
                long expiresInSeconds, long intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.verificationUriComplete = verificationUriComplete;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = intervalSeconds;
        }

        /** @return the device code -- a bearer for the grant; never log it */
        String deviceCode() {
            return deviceCode;
        }

        /** @return the {@code XXXX-XXXX} code the owner enters */
        String userCode() {
            return userCode;
        }

        /** @return the page where the owner enters the code */
        String verificationUri() {
            return verificationUri;
        }

        /** @return the page with the code already filled in */
        String verificationUriComplete() {
            return verificationUriComplete;
        }

        /** @return the device code lifetime in seconds */
        long expiresInSeconds() {
            return expiresInSeconds;
        }

        /** @return the poll interval in seconds */
        long intervalSeconds() {
            return intervalSeconds;
        }

        /** @return the presentable fields only -- never the device code */
        @Override
        public String toString() {
            return "Grant{userCode=" + userCode + ", verificationUri=" + verificationUri
                    + ", expiresInSeconds=" + expiresInSeconds + ", intervalSeconds=" + intervalSeconds + "}";
        }
    }

    /** Either a {@link Grant} or the operator-facing reason the request failed. */
    static final class AuthorizeResult {

        private final Grant grant;
        private final String failureReason;
        private final long retryAfterSeconds;

        private AuthorizeResult(Grant grant, String failureReason, long retryAfterSeconds) {
            this.grant = grant;
            this.failureReason = failureReason;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static AuthorizeResult granted(Grant grant) {
            return new AuthorizeResult(grant, null, -1);
        }

        static AuthorizeResult failed(String reason, long retryAfterSeconds) {
            return new AuthorizeResult(null, reason, retryAfterSeconds);
        }

        /** @return {@code true} when a grant was obtained */
        boolean isGranted() {
            return grant != null;
        }

        /** @return the grant, or {@code null} on failure */
        Grant grant() {
            return grant;
        }

        /** @return the operator-facing reason, or {@code null} when granted */
        String failureReason() {
            return failureReason;
        }

        /** @return the {@code Retry-After} of a 429 in seconds, or {@code -1} */
        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** The outcome of one token poll. {@link #toString()} omits the credential. */
    static final class PollResult {

        /** RFC 8628 §3.5 outcomes, plus the two local ones. */
        enum Kind {
            /** A credential was issued. */
            GRANTED,
            /** {@code authorization_pending}: poll again after the interval. */
            PENDING,
            /** {@code slow_down}: add 5 s to the interval, then poll again. */
            SLOW_DOWN,
            /** {@code expired_token}: the device code expired. */
            EXPIRED,
            /** {@code access_denied}: the owner refused on the confirmation page. */
            DENIED,
            /** {@code invalid_request} or another error code: stop. */
            REJECTED,
            /** A network error, 5xx, 429 or unreadable answer: poll again. */
            TRANSIENT
        }

        private final Kind kind;
        private final ServerCredential credential;
        private final String code;
        private final String message;
        private final long retryAfterSeconds;

        private PollResult(Kind kind, ServerCredential credential, String code, String message,
                long retryAfterSeconds) {
            this.kind = kind;
            this.credential = credential;
            this.code = code;
            this.message = message;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static PollResult of(Kind kind) {
            return new PollResult(kind, null, null, null, -1);
        }

        static PollResult granted(ServerCredential credential) {
            return new PollResult(Kind.GRANTED, credential, null, null, -1);
        }

        static PollResult rejected(String code, String message) {
            return new PollResult(Kind.REJECTED, null, code, message, -1);
        }

        static PollResult transientFailure(long retryAfterSeconds) {
            return new PollResult(Kind.TRANSIENT, null, null, null, retryAfterSeconds);
        }

        /** @return the outcome; never {@code null} */
        Kind kind() {
            return kind;
        }

        /** @return the issued credential when {@link Kind#GRANTED}, otherwise {@code null} */
        ServerCredential credential() {
            return credential;
        }

        /** @return the backend error code when {@link Kind#REJECTED}, otherwise {@code null} */
        String code() {
            return code;
        }

        /** @return the sanitised backend message when {@link Kind#REJECTED}, otherwise {@code null} */
        String message() {
            return message;
        }

        /** @return the {@code Retry-After} of a 429 in seconds, or {@code -1} */
        long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        /** @return the kind and error code only -- never the credential */
        @Override
        public String toString() {
            return "PollResult{kind=" + kind + ", code=" + code + ", retryAfterSeconds=" + retryAfterSeconds + "}";
        }
    }
}
