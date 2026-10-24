package com.ultikits.ultitools.utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.handshake.Handshakedata;

/**
 * A {@link Draft_6455} that records the HTTP status of the upgrade response, so the agent socket
 * can tell a refused upgrade (401, 402, 426, 429, ...) from a lost connection (as of 6.3.1).
 * <p>
 * <b>Why an override of {@code translateHandshake}.</b> Measured with {@code javap -c} on
 * Java-WebSocket 1.6.0: for a client, {@code WebSocketImpl.decodeHandshake} calls the public,
 * non-final {@code Draft.translateHandshake(ByteBuffer)}; its private
 * {@code translateHandshakeHttpClient} throws {@code InvalidHandshakeException("Invalid status code
 * received: ...")} as soon as the status line is complete and the status is not 101, and
 * {@code WebSocketImpl} turns that into {@code flushAndClose(1002, message, false)} -- no accept
 * hook ({@code acceptHandshakeAsClient}) runs for a refused upgrade, so the status is visible only
 * here. The client's {@code WebSocketImpl} copies its draft with {@link #copyInstance()}, which is
 * why that is overridden too: the copy shares this instance's sink.
 * <p>
 * The response is read from a duplicate of the buffer, so the parent sees it untouched. Nothing is
 * recorded until the status line's CRLF has arrived; the parent then throws its
 * incomplete-handshake exception and the library calls again with more bytes. Response headers
 * are used only when the head's terminating blank line is present, and the body only when the
 * bytes after the head already hold all of it per {@code Content-Length} and that is at most
 * 8 KiB: the library does not wait for more bytes after a non-101 status, so a body that has not
 * fully arrived is simply absent and the status alone decides.
 */
final class StatusCapturingDraft extends Draft_6455 {

    /** Status recorded for an accepted upgrade. */
    static final int SWITCHING_PROTOCOLS = 101;

    private static final int MAX_BODY_BYTES = UpgradeRejection.MAX_BODY_CHARS;
    private static final int MAX_HEAD_BYTES = 16384;
    private static final String HTTP_PREFIX = "HTTP/1.";

    private final AtomicReference<UpgradeRejection> sink;

    /**
     * @param sink where the parsed response is stored; shared with every copy of this draft
     */
    StatusCapturingDraft(AtomicReference<UpgradeRejection> sink) {
        this.sink = sink;
    }

    /** @return the sink this draft writes to */
    AtomicReference<UpgradeRejection> sink() {
        return sink;
    }

    @Override
    public Handshakedata translateHandshake(ByteBuffer buf) throws InvalidHandshakeException {
        try {
            capture(buf.duplicate());
        } catch (RuntimeException ignored) {
            // Peeking must never change what the library does with the handshake: a response the
            // peek cannot read is left to the parent, and the sink keeps what it had.
        }
        return super.translateHandshake(buf);
    }

    @Override
    public Draft copyInstance() {
        return new StatusCapturingDraft(sink);
    }

    private void capture(ByteBuffer view) {
        int length = Math.min(view.remaining(), MAX_HEAD_BYTES + MAX_BODY_BYTES);
        byte[] bytes = new byte[length];
        view.get(bytes);
        int statusEnd = indexOf(bytes, 0, Math.min(length, MAX_HEAD_BYTES), new byte[]{'\r', '\n'});
        if (statusEnd < 0) {
            return;
        }
        int status = parseStatus(new String(bytes, 0, statusEnd, StandardCharsets.ISO_8859_1));
        if (status < 0) {
            return;
        }
        if (status == SWITCHING_PROTOCOLS) {
            sink.set(UpgradeRejection.statusOnly(SWITCHING_PROTOCOLS));
            return;
        }
        int headEnd = indexOf(bytes, statusEnd, Math.min(length, MAX_HEAD_BYTES),
                new byte[]{'\r', '\n', '\r', '\n'});
        if (headEnd < 0) {
            sink.set(UpgradeRejection.parse(status, null, null));
            return;
        }
        Map<String, String> headers = parseHeaders(
                new String(bytes, statusEnd + 2, headEnd - statusEnd - 2, StandardCharsets.ISO_8859_1));
        int bodyStart = headEnd + 4;
        String body = null;
        long contentLength = contentLength(headers.get("content-length"));
        if (contentLength >= 0 && contentLength <= MAX_BODY_BYTES && length - bodyStart >= contentLength) {
            body = new String(bytes, bodyStart, (int) contentLength, StandardCharsets.UTF_8);
        }
        sink.set(UpgradeRejection.parse(status, headers, body));
    }

    private static int parseStatus(String statusLine) {
        if (!statusLine.startsWith(HTTP_PREFIX)) {
            return -1;
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[1].matches("\\d{3}")) {
            return -1;
        }
        return Integer.parseInt(parts[1]);
    }

    private static Map<String, String> parseHeaders(String block) {
        Map<String, String> headers = new HashMap<>();
        for (String line : block.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!headers.containsKey(name)) {
                headers.put(name, line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static long contentLength(String raw) {
        if (raw == null || !raw.matches("\\d{1,9}")) {
            return -1;
        }
        return Long.parseLong(raw);
    }

    private static int indexOf(byte[] data, int from, int to, byte[] pattern) {
        for (int i = from; i <= to - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
