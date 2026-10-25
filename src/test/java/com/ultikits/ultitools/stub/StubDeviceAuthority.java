package com.ultikits.ultitools.stub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The stub's device authority and credential-revoke endpoint (plan 22-19), answering on the
 * internal HTTP server behind {@link StubFrontDoor}: {@code POST /v1/device/authorize},
 * {@code POST /v1/device/token} (RFC 8628 as the contract's {@code agent-rest} operations shape it)
 * and {@code POST /v1/agent/credential/revoke}. Any other path answers 404.
 *
 * <p>Every request body is checked against its vendored request schema and logged
 * {@code REST-VALID} or {@code REST-INVALID} with the schema's first issue; an invalid body answers
 * 400 {@code invalid_request}. Every answer is built from the vendored valid fixture of its status
 * (fresh codes, credential and ids substituted) and checked against its response schema before it
 * is sent.
 *
 * <p><b>Approval.</b> By default an authorization is approved after its first poll: the first
 * {@code deviceToken} poll answers {@code authorization_pending}, the next one the credential. With
 * auto-approve off, or after {@link #hold()}, it stays pending until {@link #approve()},
 * {@link #deny()}, {@link #expire()} or {@link #slowDown()}. The stub does not answer
 * {@code slow_down} on its own for polls faster than the interval, so in-process tests need not
 * wait five seconds; {@link #slowDown()} produces it on demand.
 *
 * @since 6.3.1
 */
public final class StubDeviceAuthority {

    /** The device-authorization request path. */
    public static final String AUTHORIZE_PATH = "/v1/device/authorize";
    /** The device-token poll path. */
    public static final String TOKEN_PATH = "/v1/device/token";
    /** The credential-revoke path {@code /ulticloud logout} calls. */
    public static final String REVOKE_PATH = "/v1/agent/credential/revoke";

    /** A device code's lifetime, the contract's {@code expiresIn}. */
    static final long LIFETIME_MS = 600_000L;

    private static final String USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";
    private static final String DEFAULT_SERVER_NAME = "UltiCloud Stub Server";
    private static final SecureRandom RANDOM = new SecureRandom();

    private enum State { PENDING, APPROVED, DENIED, EXPIRED, CONSUMED }

    private final UltiCloudStub.Contract contract;
    private final StubAccessLog log;
    private final UltiCloudStub.Credentials credentials;
    private final Consumer<String> onRevoked;
    private final Map<String, Authorization> byDeviceCode = new HashMap<>();
    private final Map<String, String> serverIdByLegacyUuid = new HashMap<>();
    private boolean autoApprove;
    private Authorization latest;
    private String nextAction;

    StubDeviceAuthority(UltiCloudStub.Contract contract, StubAccessLog log, UltiCloudStub.Credentials credentials,
                        boolean autoApprove, Consumer<String> onRevoked) {
        this.contract = contract;
        this.log = log;
        this.credentials = credentials;
        this.autoApprove = autoApprove;
        this.onRevoked = onRevoked;
    }

    void install(HttpServer server) {
        server.createContext("/", this::handle);
    }

    // ------------------------------------------------------------------ routes

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if (AUTHORIZE_PATH.equals(path) && "POST".equals(method)) {
                authorize(exchange);
            } else if (TOKEN_PATH.equals(path) && "POST".equals(method)) {
                token(exchange);
            } else if (REVOKE_PATH.equals(path) && "POST".equals(method)) {
                revoke(exchange);
            } else {
                JsonObject error = new JsonObject();
                JsonObject body = new JsonObject();
                body.addProperty("code", "not_found");
                body.addProperty("message", "No such route on the UltiCloud stub.");
                body.addProperty("requestId", requestId());
                error.add("error", body);
                send(exchange, 404, error, null);
            }
        } finally {
            exchange.close();
        }
    }

    private void authorize(HttpExchange exchange) throws IOException {
        JsonObject request = checkedBody(exchange, "deviceAuthorize.request");
        if (request == null) {
            send(exchange, 400, error("deviceAuthorize/error/valid/400-invalid-request.json"),
                    "deviceAuthorize.error.400");
            return;
        }
        Authorization authorization;
        synchronized (this) {
            authorization = new Authorization(UltiCloudStub.Contract.randomToken(32), userCode(),
                    text(request, "clientKind"), text(request, "suggestedName"), text(request, "legacyServerUuid"));
            byDeviceCode.put(authorization.deviceCode, authorization);
            latest = authorization;
            if (nextAction != null) {
                apply(authorization, nextAction);
                nextAction = null;
            }
        }
        JsonObject body = contract.object("fixtures/agent-rest/deviceAuthorize/response/valid/200-issued.json");
        JsonObject data = body.getAsJsonObject("data");
        data.addProperty("deviceCode", authorization.deviceCode);
        data.addProperty("userCode", authorization.userCode);
        data.addProperty("verificationUriComplete",
                data.get("verificationUri").getAsString() + "?code=" + authorization.userCode);
        send(exchange, 200, body, "deviceAuthorize.response.200");
    }

    private void token(HttpExchange exchange) throws IOException {
        JsonObject request = checkedBody(exchange, "deviceToken.request");
        if (request == null) {
            send(exchange, 400, tokenError("invalid_request"), "deviceToken.error.400");
            return;
        }
        String outcome;
        Authorization authorization;
        synchronized (this) {
            authorization = byDeviceCode.get(request.get("deviceCode").getAsString());
            outcome = poll(authorization);
        }
        if (!"issued".equals(outcome)) {
            send(exchange, 400, tokenError(outcome), "deviceToken.error.400");
            return;
        }
        send(exchange, 200, issue(authorization), "deviceToken.response.200");
    }

    /** @return the error code to answer, or {@code issued} when the credential is to be issued now */
    private String poll(Authorization authorization) {
        if (authorization == null || authorization.state == State.CONSUMED) {
            return "expired_token";
        }
        if (authorization.state == State.PENDING && System.currentTimeMillis() > authorization.expiresAt) {
            authorization.state = State.EXPIRED;
        }
        if (authorization.slowDownNext) {
            authorization.slowDownNext = false;
            return "slow_down";
        }
        switch (authorization.state) {
            case APPROVED:
                authorization.state = State.CONSUMED;
                return "issued";
            case DENIED:
                authorization.state = State.CONSUMED;
                return "access_denied";
            case EXPIRED:
                return "expired_token";
            default:
                authorization.polls++;
                if (autoApprove && !authorization.held) {
                    authorization.state = State.APPROVED;
                }
                return "authorization_pending";
        }
    }

    private JsonObject issue(Authorization authorization) {
        boolean server = !"cli".equals(authorization.clientKind);
        JsonObject body = contract.object("fixtures/agent-rest/deviceToken/response/valid/"
                + (server ? "200-server.json" : "200-cli.json"));
        JsonObject data = body.getAsJsonObject("data");
        String credential = (server ? "ucs_" : "ucc_") + UltiCloudStub.Contract.randomToken(32);
        data.addProperty("credential", credential);
        data.addProperty("credentialId", UltiCloudStub.Contract.newUlid());
        if (server) {
            String serverId;
            synchronized (this) {
                serverId = authorization.legacyUuid == null ? null : serverIdByLegacyUuid.get(authorization.legacyUuid);
                if (serverId == null) {
                    serverId = UltiCloudStub.Contract.newUlid();
                    if (authorization.legacyUuid != null) {
                        serverIdByLegacyUuid.put(authorization.legacyUuid, serverId);
                    }
                }
            }
            data.addProperty("serverId", serverId);
            data.addProperty("serverName", authorization.suggestedName == null
                    ? DEFAULT_SERVER_NAME : authorization.suggestedName);
            credentials.accept(credential, serverId, "issued by device authorization " + authorization.userCode);
        } else {
            log.log("CREDENTIAL", "issued cli credential " + credential);
        }
        return body;
    }

    private void revoke(HttpExchange exchange) throws IOException {
        String credential = bearer(exchange.getRequestHeaders().getFirst("Authorization"));
        if (credential != null && credentials.revoke(credential, "revoked by " + REVOKE_PATH)) {
            onRevoked.accept(credential);
            send(exchange, 200, contract.object("fixtures/agent-rest/credentialRevoke/response/valid/200-revoked.json"),
                    "credentialRevoke.response.200");
        } else {
            send(exchange, 401, error("credentialRevoke/error/valid/401-invalid-credential.json"),
                    "credentialRevoke.error.401");
        }
    }

    // ------------------------------------------------------------------ admin actions

    /** Approves the latest pending authorization (or the next one, when none is pending). */
    public void approve() {
        act("approve");
    }

    /** Makes the next poll of the latest pending authorization answer {@code access_denied}. */
    public void deny() {
        act("deny");
    }

    /** Makes the latest pending authorization answer {@code expired_token} from now on. */
    public void expire() {
        act("expire");
    }

    /** Makes the next poll of the latest authorization answer {@code slow_down}, once. */
    public void slowDown() {
        act("slow_down");
    }

    /** Keeps the latest and every later authorization pending until an explicit action. */
    public synchronized void hold() {
        autoApprove = false;
        if (latest != null && latest.state == State.PENDING) {
            latest.held = true;
        }
    }

    /** Restores the default: an authorization is approved after its first poll. */
    public synchronized void auto() {
        autoApprove = true;
        if (latest != null) {
            latest.held = false;
        }
    }

    private synchronized void act(String action) {
        if (latest != null && latest.state == State.PENDING) {
            apply(latest, action);
        } else {
            nextAction = action;
        }
    }

    private static void apply(Authorization authorization, String action) {
        if ("approve".equals(action)) {
            authorization.state = State.APPROVED;
        } else if ("deny".equals(action)) {
            authorization.state = State.DENIED;
        } else if ("expire".equals(action)) {
            authorization.state = State.EXPIRED;
        } else if ("slow_down".equals(action)) {
            authorization.slowDownNext = true;
        }
    }

    /** @return the user code of the latest authorization, or {@code null} when there has been none */
    public synchronized String latestUserCode() {
        return latest == null ? null : latest.userCode;
    }

    // ------------------------------------------------------------------ bodies

    private JsonObject checkedBody(HttpExchange exchange, String schema) throws IOException {
        String text = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (JsonParseException notJson) {
            log.log("REST-INVALID", schema + " $: the body is not JSON");
            return null;
        }
        String issue = contract.firstIssue(UltiCloudStub.Contract.restSchema(schema + ".schema.json"), parsed);
        if (issue != null) {
            log.log("REST-INVALID", schema + " " + issue);
            return null;
        }
        log.log("REST-VALID", schema);
        return parsed.getAsJsonObject();
    }

    private JsonObject error(String fixture) {
        JsonObject body = contract.object("fixtures/agent-rest/" + fixture);
        body.getAsJsonObject("error").addProperty("requestId", requestId());
        return body;
    }

    private JsonObject tokenError(String code) {
        JsonObject body = contract.object("fixtures/agent-rest/deviceToken/error/valid/400-authorization-pending.json");
        body.getAsJsonObject("error").addProperty("code", code);
        body.getAsJsonObject("error").addProperty("requestId", requestId());
        return body;
    }

    private void send(HttpExchange exchange, int status, JsonObject body, String schema) throws IOException {
        if (schema != null) {
            String issue = contract.firstIssue(UltiCloudStub.Contract.restSchema(schema + ".schema.json"), body);
            if (issue != null) {
                log.log("REST-SELF-INVALID", schema + " " + issue);
            }
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.flush();
    }

    static String requestId() {
        return "req_" + UltiCloudStub.Contract.newUlid();
    }

    static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : null;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String userCode() {
        StringBuilder code = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                code.append('-');
            }
            code.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    /** One device authorization and where it stands. */
    private static final class Authorization {
        final String deviceCode;
        final String userCode;
        final String clientKind;
        final String suggestedName;
        final String legacyUuid;
        final long expiresAt = System.currentTimeMillis() + LIFETIME_MS;
        State state = State.PENDING;
        boolean slowDownNext;
        boolean held;
        int polls;

        Authorization(String deviceCode, String userCode, String clientKind, String suggestedName, String legacyUuid) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.clientKind = clientKind;
            this.suggestedName = suggestedName;
            this.legacyUuid = legacyUuid;
        }
    }
}
