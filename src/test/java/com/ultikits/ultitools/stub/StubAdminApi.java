package com.ultikits.ultitools.stub;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The stub's control surface (plan 22-19): a {@code com.sun.net.httpserver} server bound to the
 * loopback address only, on its own port, through which a UAT run produces each stub-environment
 * condition deterministically. Every action ({@code POST}) writes one {@code ADMIN} line to the
 * access log, with its masked body and its status, so a verdict can quote the action it took.
 *
 * <p>Bodies are JSON objects; answers are {@code {"ok":true,...}} or
 * {@code {"ok":false,"error":"..."}} with 400 (a request the contract or the stub does not allow),
 * 409 (no plugin connected), 404 or 405. {@link #ENDPOINTS} lists every endpoint; {@code GET /_stub}
 * returns the same list.
 *
 * @since 6.3.1
 */
public final class StubAdminApi {

    /** The prefix of every admin path. */
    public static final String PREFIX = "/_stub";

    /** Every endpoint with its body, as {@code GET /_stub} and {@code --help} print them. */
    public static final List<String> ENDPOINTS = Collections.unmodifiableList(Arrays.asList(
            "POST /_stub/mode        {\"refusal\": 426|401|402|429|\"none\", \"minFramework\"?: \"X.Y.Z\", "
                    + "\"retryAfterSeconds\"?: N}  every later upgrade is refused at its step of the contract order",
            "POST /_stub/unreachable {\"on\": true|false}  close the public port and drop every connection, or reopen it",
            "POST /_stub/close       {\"code\": 1000|1001|1009|1011|4000|4400|4401|4402|4408|4426|4429, \"reason\"?: \"...\"}"
                    + "  close the live connection; 4401 also revokes its credential, 4402 also sets refusal 402",
            "POST /_stub/send        {\"type\": \"<down type>\", \"fixture\"?: \"<name>\", \"payload\"?: {...}, "
                    + "\"id\"?: \"<ULID>\", \"replyTo\"?: \"<ULID>\"}  send a frame built from its vendored fixture",
            "POST /_stub/welcome     {\"serverId\": \"<ULID>\"|null}  the next welcome carries this serverId",
            "POST /_stub/ack         {\"enabled\": true|false}  stop or resume acknowledging reliable events",
            "POST /_stub/device      {\"action\": \"approve\"|\"deny\"|\"expire\"|\"slow_down\"|\"hold\"|\"auto\"}"
                    + "  decide the latest device authorization's next poll",
            "POST /_stub/revoke      {\"state\": \"up\"|\"down\"}  down resets every credential-revoke connection",
            "POST /_stub/catalogue   {\"mode\": \"<StubCatalogue.Mode>\"}  e.g. tamper_hash, tamper_size, wrong_header",
            "POST /_stub/credential  {\"credential\": \"ucs_...\", \"serverId\": \"<ULID>\"}  accept a stored credential",
            "GET  /_stub/log         the access log as text",
            "GET  /_stub/frames      ?type=<type>&direction=in|out|all  recorded frames as JSON, masked",
            "GET  /_stub/status      connections, modes and credential fingerprints as JSON"));

    private static final String CREDENTIAL = "^ucs_[A-Za-z0-9_-]{43}$";

    private final UltiCloudStub.Handle stub;
    private final StubAccessLog log;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            UltiCloudStub.daemonThreads("ulticloud-stub-admin"));

    StubAdminApi(int port, UltiCloudStub.Handle stub, StubAccessLog log) throws IOException {
        this.stub = stub;
        this.log = log;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** @return the bound address, always the loopback address */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            if ("GET".equals(method)) {
                get(exchange, path, exchange.getRequestURI().getRawQuery());
            } else if ("POST".equals(method)) {
                post(exchange, path);
            } else {
                reply(exchange, 405, failure("only GET and POST"));
            }
        } finally {
            exchange.close();
        }
    }

    private void get(HttpExchange exchange, String path, String query) throws IOException {
        if (PREFIX.equals(path) || (PREFIX + "/").equals(path) || (PREFIX + "/help").equals(path)) {
            JsonObject help = success();
            JsonArray endpoints = new JsonArray();
            for (String endpoint : ENDPOINTS) {
                endpoints.add(endpoint);
            }
            help.add("endpoints", endpoints);
            reply(exchange, 200, help);
        } else if ((PREFIX + "/log").equals(path)) {
            send(exchange, 200, "text/plain; charset=utf-8", log.text().getBytes(StandardCharsets.UTF_8));
        } else if ((PREFIX + "/frames").equals(path)) {
            String type = parameter(query, "type");
            String direction = parameter(query, "direction");
            JsonArray frames = new JsonArray();
            for (StubGateway.Frame frame : stub.gateway().frames()) {
                String wanted = direction == null ? "in" : direction;
                boolean directionMatches = "all".equals(wanted) || wanted.equals(frame.direction());
                if (directionMatches && (type == null || type.equals(frame.type()))) {
                    JsonObject item = new JsonObject();
                    item.addProperty("at", frame.at());
                    item.addProperty("conn", frame.connection());
                    item.addProperty("direction", frame.direction());
                    item.add("frame", frame.frame());
                    frames.add(item);
                }
            }
            send(exchange, 200, "application/json; charset=utf-8", frames.toString().getBytes(StandardCharsets.UTF_8));
        } else if ((PREFIX + "/status").equals(path)) {
            reply(exchange, 200, status());
        } else {
            reply(exchange, 404, failure("no admin endpoint " + path + "; GET " + PREFIX + " lists them"));
        }
    }

    private void post(HttpExchange exchange, String path) throws IOException {
        String text = new String(StubDeviceAuthority.readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
        int status;
        JsonObject answer;
        try {
            JsonObject body = parseBody(text);
            answer = act(path, body);
            status = answer == null ? 404 : 200;
            if (answer == null) {
                answer = failure("no admin endpoint " + path + "; GET " + PREFIX + " lists them");
            }
        } catch (IllegalArgumentException refused) {
            status = 400;
            answer = failure(refused.getMessage());
        } catch (IllegalStateException conflict) {
            status = 409;
            answer = failure(conflict.getMessage());
        }
        log.log("ADMIN", "POST " + path + " " + text.trim() + " -> " + status
                + (status == 200 ? "" : " " + answer.get("error").getAsString()));
        reply(exchange, status, answer);
    }

    /** @return the answer, or {@code null} for an unknown path */
    private JsonObject act(String path, JsonObject body) {
        String action = path.startsWith(PREFIX + "/") ? path.substring(PREFIX.length() + 1) : "";
        JsonObject answer = success();
        switch (action) {
            case "mode":
                mode(body);
                break;
            case "unreachable":
                try {
                    stub.frontDoor().setUnreachable(bool(body, "on"));
                } catch (IOException cannotRebind) {
                    throw new IllegalStateException("the public port could not be opened again: " + cannotRebind);
                }
                break;
            case "close":
                stub.closeLive(integer(body, "code"), optionalString(body, "reason"));
                break;
            case "send":
                answer.add("frame", stub.gateway().send(string(body, "type"), optionalString(body, "fixture"),
                        optionalObject(body, "payload"), optionalString(body, "id"), optionalString(body, "replyTo")));
                break;
            case "welcome":
                stub.gateway().overrideNextWelcomeServerId(optionalString(body, "serverId"));
                break;
            case "ack":
                stub.gateway().setAcking(bool(body, "enabled"));
                break;
            case "device":
                device(string(body, "action"));
                break;
            case "revoke":
                String state = string(body, "state");
                if (!"up".equals(state) && !"down".equals(state)) {
                    throw new IllegalArgumentException("state must be up or down");
                }
                stub.frontDoor().setRevokeDown("down".equals(state));
                break;
            case "catalogue":
                stub.catalogue().mode(catalogueMode(string(body, "mode")));
                break;
            case "credential":
                String credential = string(body, "credential");
                String serverId = string(body, "serverId");
                if (!credential.matches(CREDENTIAL) || !UltiCloudStub.Contract.isUlid(serverId)) {
                    throw new IllegalArgumentException("credential must be a ucs_ credential and serverId a ULID");
                }
                stub.acceptCredential(credential, serverId);
                break;
            default:
                return null;
        }
        return answer;
    }

    private void mode(JsonObject body) {
        JsonElement refusal = body.get("refusal");
        if (refusal == null || refusal.isJsonNull()) {
            throw new IllegalArgumentException("refusal is required: 426, 401, 402, 429 or none");
        }
        String value = refusal.getAsString().trim().toLowerCase(Locale.ROOT);
        int status;
        if ("none".equals(value)) {
            status = 0;
        } else if (value.matches("[0-9]{3}")) {
            status = Integer.parseInt(value);
        } else {
            throw new IllegalArgumentException("refusal must be 426, 401, 402, 429 or none");
        }
        JsonElement retry = body.get("retryAfterSeconds");
        long retryAfter = retry == null || retry.isJsonNull() ? 0 : retry.getAsLong();
        if (retryAfter < 0 || retryAfter > 86_400) {
            throw new IllegalArgumentException("retryAfterSeconds must be between 1 and 86400");
        }
        stub.frontDoor().setRefusal(status, optionalString(body, "minFramework"), retryAfter);
    }

    private void device(String action) {
        StubDeviceAuthority authority = stub.deviceAuthority();
        switch (action) {
            case "approve":
                authority.approve();
                break;
            case "deny":
                authority.deny();
                break;
            case "expire":
                authority.expire();
                break;
            case "slow_down":
                authority.slowDown();
                break;
            case "hold":
                authority.hold();
                break;
            case "auto":
                authority.auto();
                break;
            default:
                throw new IllegalArgumentException("action must be approve, deny, expire, slow_down, hold or auto");
        }
    }

    private static StubCatalogue.Mode catalogueMode(String name) {
        try {
            return StubCatalogue.Mode.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("mode must be one of " + Arrays.toString(StubCatalogue.Mode.values()));
        }
    }

    private JsonObject status() {
        JsonObject status = success();
        status.addProperty("httpUrl", stub.baseUrl());
        status.addProperty("refusal", stub.frontDoor().refusal() == 0 ? "none"
                : String.valueOf(stub.frontDoor().refusal()));
        status.addProperty("unreachable", stub.frontDoor().unreachable());
        status.addProperty("revoke", stub.frontDoor().revokeDown() ? "down" : "up");
        status.addProperty("acking", stub.gateway().acking());
        status.addProperty("catalogueMode", stub.catalogue().mode().name());
        status.addProperty("latestUserCode", stub.deviceAuthority().latestUserCode());
        status.add("connections", stub.gateway().describeConnections());
        JsonArray fingerprints = new JsonArray();
        for (String fingerprint : stub.credentials().validFingerprints()) {
            fingerprints.add(fingerprint);
        }
        status.add("validCredentialFingerprints", fingerprints);
        return status;
    }

    // ------------------------------------------------------------------ body helpers

    private static JsonObject parseBody(String text) {
        if (text.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException notJson) {
            // Answered below.
        }
        throw new IllegalArgumentException("the body must be a JSON object");
    }

    private static String string(JsonObject body, String key) {
        String value = optionalString(body, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optionalString(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!(value instanceof JsonPrimitive)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static JsonObject optionalObject(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static boolean bool(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (!(value instanceof JsonPrimitive) || !((JsonPrimitive) value).isBoolean()) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject body, String key) {
        JsonElement value = body.get(key);
        if (!(value instanceof JsonPrimitive) || !((JsonPrimitive) value).isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        return value.getAsInt();
    }

    private static String parameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return null;
    }

    private static JsonObject success() {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", true);
        return answer;
    }

    private static JsonObject failure(String error) {
        JsonObject answer = new JsonObject();
        answer.addProperty("ok", false);
        answer.addProperty("error", error);
        return answer;
    }

    private static void reply(HttpExchange exchange, int status, JsonObject body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                StubAccessLog.mask(body.toString()).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.flush();
    }
}
