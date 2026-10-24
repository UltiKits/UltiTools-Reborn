package com.ultikits.ultitools.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /**
     * Requests a device code.
     *
     * @param frameworkVersion        the running framework version, sent verbatim
     * @param suggestedNameOrNull     the default server name for the confirmation page, or
     *                                {@code null} to omit the field
     * @param attachToServerIdOrNull  this server's normalised install id, or {@code null} to omit the
     *                                field
     * @return the grant, or the reason it could not be obtained; never {@code null}
     */
    AuthorizeResult authorize(String frameworkVersion, String suggestedNameOrNull, String attachToServerIdOrNull) {
        JsonObject body = new JsonObject();
        body.addProperty("clientKind", "server");
        body.addProperty("framework", frameworkVersion);
        body.addProperty("protocol", PROTOCOL_VERSION);
        if (suggestedNameOrNull != null) {
            body.addProperty("suggestedName", suggestedNameOrNull);
        }
        if (attachToServerIdOrNull != null) {
            body.addProperty("attachToServerId", attachToServerIdOrNull);
        }
        CloudHttp.Response response = CloudHttp.postJson(AUTHORIZE_PATH, body, null);
        if (response.outcome() == CloudHttp.Outcome.NOT_CONFIGURED) {
            return AuthorizeResult.failed("API URL not configured", -1);
        }
        if (response.outcome() == CloudHttp.Outcome.IO_ERROR) {
            return AuthorizeResult.failed("UltiCloud could not be reached", -1);
        }
        if (response.status() != 200) {
            return AuthorizeResult.failed("UltiCloud answered HTTP " + response.status(), -1);
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
        return AuthorizeResult.granted(new Grant(deviceCode, userCode, verificationUri,
                isBlank(complete) ? verificationUri : complete, expiresIn, interval));
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
        if (response.status() == 200) {
            JsonObject data = dataObject(response.body());
            String secret = data == null ? null : string(data, "credential");
            String serverId = data == null ? null : string(data, "serverId");
            if (secret == null || isBlank(serverId)) {
                return PollResult.transientFailure(-1);
            }
            return PollResult.granted(new ServerCredential(secret, string(data, "credentialId"), serverId,
                    string(data, "userId"), string(data, "serverName"), System.currentTimeMillis()));
        }
        String code = errorCode(response.body());
        if (response.status() == 400 && "authorization_pending".equals(code)) {
            return PollResult.of(PollResult.Kind.PENDING);
        }
        return PollResult.transientFailure(-1);
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

    private static String errorCode(String body) {
        JsonObject error = errorObject(body);
        return error == null ? null : string(error, "code");
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
