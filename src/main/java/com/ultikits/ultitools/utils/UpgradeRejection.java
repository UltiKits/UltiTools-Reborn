package com.ultikits.ultitools.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * What the gateway said when it refused the agent socket's upgrade request (as of 6.3.1).
 * <p>
 * The refused responses (HTTP 426, 401, 429 and the plan-limit 402) carry
 * {@code {error:{code, message, requestId, details}}}. Every field ends up on the server console,
 * so every field is untrusted and is kept only when it passes its own check: {@code code},
 * {@code reason} and {@code requestId} keep {@code [0-9A-Za-z._:-]{1,64}}; {@code minFramework}
 * keeps {@code [0-9A-Za-z.+-]{1,32}}; {@code message} loses control and format characters and
 * colour codes and is cut to 200 characters; {@code graceEndedAt} keeps an ISO-8601 timestamp of
 * at most 40 characters; {@code manageUrl} keeps an {@code https://} URL on {@code ultikits.com}
 * or one of its subdomains, at most 200 characters, without whitespace, control characters or
 * user information. Anything else becomes {@code null}.
 * <p>
 * {@link #parse} never throws: a missing, partial, malformed or oversized body yields a value that
 * carries only the status (and the header-derived fields), and the status alone then decides what
 * the connection does. As in the frozen contract 2.0.0 (PROTOCOL.md section 10, plan 22-20, S-63),
 * every field is read from inside {@code error} only: a body without that wrapper is status-only,
 * and the plan-limit fields of a 402 ({@code reason}, {@code graceEndedAt}, {@code manageUrl}) are
 * read from {@code error.details} only, like the 426 details.
 */
final class UpgradeRejection {

    /** Largest body that is parsed; the draft never hands over a larger one. */
    static final int MAX_BODY_CHARS = 8192;

    private static final Pattern TOKEN = Pattern.compile("[0-9A-Za-z._:-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+-]{1,32}");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final Pattern ANSI_SEQUENCE = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final int MAX_TIMESTAMP_CHARS = 40;
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_URL_CHARS = 200;
    private static final int MAX_PROTOCOLS = 16;
    private static final int MAX_PROTOCOL_VALUE = 1000;
    private static final String MANAGE_DOMAIN = "ultikits.com";
    private static final String HEADER_RETRY_AFTER = "retry-after";
    private static final String HEADER_PROTOCOL_SUPPORTED = "x-ulticloud-protocol-supported";

    private final int status;
    private final String code;
    private final String message;
    private final String requestId;
    private final String minFramework;
    private final List<Integer> supportedProtocols;
    private final long retryAfterSeconds;
    private final String planReason;
    private final String graceEndedAt;
    private final String manageUrl;

    private UpgradeRejection(Builder b) {
        this.status = b.status;
        this.code = b.code;
        this.message = b.message;
        this.requestId = b.requestId;
        this.minFramework = b.minFramework;
        this.supportedProtocols = Collections.unmodifiableList(new ArrayList<>(b.supportedProtocols));
        this.retryAfterSeconds = b.retryAfterSeconds;
        this.planReason = b.planReason;
        this.graceEndedAt = b.graceEndedAt;
        this.manageUrl = b.manageUrl;
    }

    /**
     * A value carrying only the status.
     *
     * @param status the HTTP status of the upgrade response
     * @return the value
     */
    static UpgradeRejection statusOnly(int status) {
        Builder b = new Builder();
        b.status = status;
        return new UpgradeRejection(b);
    }

    /**
     * Parses a refused upgrade response. Never throws.
     *
     * @param status           the HTTP status
     * @param headersLowerCase the response headers, names lower-cased; may be {@code null}
     * @param bodyOrNull       the complete body, or {@code null} when it was absent, incomplete or
     *                         too large
     * @return the sanitised value
     */
    static UpgradeRejection parse(int status, Map<String, String> headersLowerCase, String bodyOrNull) {
        Builder b = new Builder();
        b.status = status;
        try {
            readBody(b, bodyOrNull);
        } catch (RuntimeException e) {
            // Any malformed body leaves the decision to the status: drop every body field.
            Builder statusOnly = new Builder();
            statusOnly.status = status;
            b = statusOnly;
        }
        try {
            readHeaders(b, headersLowerCase);
        } catch (RuntimeException e) {
            // A header map that cannot be read adds nothing; the body fields stay.
            b.retryAfterSeconds = -1;
        }
        return new UpgradeRejection(b);
    }

    private static void readBody(Builder b, String body) {
        if (body == null || body.isEmpty() || body.length() > MAX_BODY_CHARS) {
            return;
        }
        JsonElement root = JsonParser.parseString(body);
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonObject error = object(root.getAsJsonObject(), "error");
        if (error == null) {
            // Every refusal body of the contract is {error: {...}}; anything else says nothing.
            return;
        }
        b.code = token(string(error, "code"));
        b.message = message(string(error, "message"));
        b.requestId = token(string(error, "requestId"));
        JsonObject details = object(error, "details");
        if (details != null) {
            b.minFramework = version(string(details, "minFramework"));
            b.supportedProtocols.addAll(protocols(details.get("supportedProtocols")));
            b.planReason = token(string(details, "reason"));
            b.graceEndedAt = timestamp(string(details, "graceEndedAt"));
            b.manageUrl = manageUrl(string(details, "manageUrl"));
        }
    }

    private static void readHeaders(Builder b, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        if (b.minFramework == null) {
            b.minFramework = version(headers.get(
                    AgentConnection.MIN_FRAMEWORK_HEADER.toLowerCase(Locale.ROOT)));
        }
        if (b.supportedProtocols.isEmpty()) {
            String supported = headers.get(HEADER_PROTOCOL_SUPPORTED);
            if (supported != null) {
                for (String part : supported.split(",", MAX_PROTOCOLS + 1)) {
                    Integer value = protocolValue(part.trim());
                    if (value != null && b.supportedProtocols.size() < MAX_PROTOCOLS) {
                        b.supportedProtocols.add(value);
                    }
                }
            }
        }
        b.retryAfterSeconds = retryAfter(headers.get(HEADER_RETRY_AFTER));
    }

    private static long retryAfter(String raw) {
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long max = AgentConnection.RETRY_AFTER_MAX_SECONDS;
        if (!DIGITS.matcher(value).matches()) {
            return max;
        }
        return Math.min(max, Long.parseLong(value));
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement e = parent.get(name);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement e = parent.get(name);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isString() ? p.getAsString() : null;
    }

    private static List<Integer> protocols(JsonElement e) {
        List<Integer> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) {
            return out;
        }
        JsonArray array = e.getAsJsonArray();
        for (JsonElement item : array) {
            if (out.size() >= MAX_PROTOCOLS) {
                break;
            }
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber()) {
                Integer value = protocolValue(item.getAsJsonPrimitive().getAsString());
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static Integer protocolValue(String raw) {
        if (raw == null || !raw.matches("\\d{1,4}")) {
            return null;
        }
        int value = Integer.parseInt(raw);
        return value >= 1 && value <= MAX_PROTOCOL_VALUE ? value : null;
    }

    private static String token(String raw) {
        return raw != null && TOKEN.matcher(raw).matches() ? raw : null;
    }

    private static String version(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return VERSION.matcher(value).matches() ? value : null;
    }

    private static String timestamp(String raw) {
        return raw != null && raw.length() <= MAX_TIMESTAMP_CHARS && TIMESTAMP.matcher(raw).matches()
                ? raw : null;
    }

    private static String message(String raw) {
        if (raw == null) {
            return null;
        }
        String withoutAnsi = ANSI_SEQUENCE.matcher(raw).replaceAll("");
        StringBuilder sb = new StringBuilder(Math.min(withoutAnsi.length(), MAX_MESSAGE_CHARS + 1));
        for (int i = 0; i < withoutAnsi.length() && sb.length() < MAX_MESSAGE_CHARS; i++) {
            char c = withoutAnsi.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '§') {
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0 && Character.isHighSurrogate(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        String value = sb.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static String manageUrl(String raw) {
        if (raw == null || raw.length() > MAX_URL_CHARS || !raw.startsWith("https://")) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c)
                    || Character.getType(c) == Character.FORMAT) {
                return null;
            }
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean ours = lower.equals(MANAGE_DOMAIN) || lower.endsWith("." + MANAGE_DOMAIN);
        return ours ? raw : null;
    }

    /** @return the HTTP status of the refused upgrade */
    int status() {
        return status;
    }

    /** @return the error code, or {@code null} */
    String code() {
        return code;
    }

    /** @return the sanitised message, or {@code null} */
    String message() {
        return message;
    }

    /** @return the request id, or {@code null} */
    String requestId() {
        return requestId;
    }

    /** @return the minimum framework version the gateway accepts, or {@code null} */
    String minFramework() {
        return minFramework;
    }

    /** @return the protocol versions the gateway supports; empty when absent */
    List<Integer> supportedProtocols() {
        return supportedProtocols;
    }

    /** @return the {@code Retry-After} delta in seconds, clamped, or -1 when absent or not delta-seconds */
    long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** @return the plan-limit reason, or {@code null} */
    String planReason() {
        return planReason;
    }

    /** @return when the plan-limit grace period ended, or {@code null} */
    String graceEndedAt() {
        return graceEndedAt;
    }

    /** @return the panel page where the owner manages the plan, or {@code null} */
    String manageUrl() {
        return manageUrl;
    }

    @Override
    public String toString() {
        return "UpgradeRejection{status=" + status + ", code=" + code + ", requestId=" + requestId + '}';
    }

    private static final class Builder {
        private int status;
        private String code;
        private String message;
        private String requestId;
        private String minFramework;
        private final List<Integer> supportedProtocols = new ArrayList<>();
        private long retryAfterSeconds = -1;
        private String planReason;
        private String graceEndedAt;
        private String manageUrl;
    }
}
