package com.ultikits.ultitools.stub;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UAT stub's access log (plan 22-19): one timestamped line per HTTP request and response, per
 * upgrade decision, per WebSocket frame in either direction, per schema failure and per admin
 * action, kept in memory and, when a file is given, appended to it as it happens.
 *
 * <p>Every line has the form {@code <ISO-8601 UTC instant> <CATEGORY> <text>}. The categories are a
 * fixed vocabulary a UAT verdict can quote:
 * <ul>
 *   <li>{@code STUB} start and stop;</li>
 *   <li>{@code HTTP} a request line with its masked {@code Authorization}, {@code HTTP-RESPONSE} its
 *       status, {@code V1} a request to a v1 path (answered 410), {@code TCP-RESET} a request the
 *       stub cut off on purpose;</li>
 *   <li>{@code REST-VALID} / {@code REST-INVALID} a request body checked against its vendored
 *       schema (the invalid line carries the schema's first issue), {@code REST-SELF-INVALID} an
 *       answer of the stub's own that failed its schema (a stub defect, never expected);</li>
 *   <li>{@code UPGRADE} an upgrade request and the decision ({@code -> 101} or the refusal status);</li>
 *   <li>{@code WS-OPEN}, {@code WS-CLOSED}, {@code WS-ERROR} connection events; {@code WS-IN} and
 *       {@code WS-OUT} one frame each (frame type, envelope id, replyTo; {@code ping}, {@code pong}
 *       and {@code close} for the heartbeat and close frames); {@code WS-INVALID} an inbound frame the
 *       stub refused, with the schema's first issue; {@code WS-SELF-INVALID} an outbound frame of the
 *       stub's own that failed its schema; {@code WS-NOTE} something the stub did not do on purpose
 *       (an event left unacknowledged, a frame not sent because the connection had closed);</li>
 *   <li>{@code CREDENTIAL} a credential issued, accepted, rotated or revoked;</li>
 *   <li>{@code ADMIN} one line per admin action, with its masked body and status.</li>
 * </ul>
 *
 * <p><b>Masking.</b> No line carries a credential: every {@code ucs_}/{@code ucc_} credential and
 * every {@code Bearer} value is replaced by {@code <prefix>***#<fingerprint>}, where the
 * fingerprint is the first eight hex digits of the value's SHA-256 ({@link #fingerprint(String)}),
 * so two credentials can be told apart in a log without either being readable.
 *
 * @since 6.3.1
 */
public final class StubAccessLog implements AutoCloseable {

    /** The most lines kept in memory; older ones stay only in the file. */
    static final int MEMORY_LINES = 200_000;

    private static final Pattern CREDENTIAL = Pattern.compile("(uc[sc]_)[A-Za-z0-9_-]{43}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)([^\\s\",]+)");

    private final List<String> lines = new ArrayList<>();
    private final Path file;
    private final Writer writer;

    /**
     * @param file the file to append every line to, or {@code null} to keep the log in memory only
     * @throws IOException when the file cannot be opened for appending
     */
    StubAccessLog(Path file) throws IOException {
        this.file = file;
        if (file == null) {
            this.writer = null;
        } else {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE),
                    StandardCharsets.UTF_8));
        }
    }

    /**
     * Appends one line; the text is masked first.
     *
     * @param category one of the categories in the class description
     * @param text     the rest of the line
     */
    public void log(String category, String text) {
        String line = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(System.currentTimeMillis()))
                + " " + category + " " + mask(text).replace('\r', ' ').replace('\n', ' ');
        synchronized (lines) {
            lines.add(line);
            if (lines.size() > MEMORY_LINES) {
                lines.remove(0);
            }
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                } catch (IOException ignored) {
                    // The in-memory copy stays complete; a full disk must not stop the stub.
                }
            }
            lines.notifyAll();
        }
    }

    /** @return a copy of every line kept in memory, oldest first */
    public List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    /** @return every line kept in memory, joined by line feeds and ending with one */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (String line : lines()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** @return the file the log is appended to, or {@code null} when it is kept in memory only */
    public Path file() {
        return file;
    }

    /**
     * Waits until a line containing {@code fragment} has been logged.
     *
     * @param fragment  the text to look for
     * @param timeoutMs how long to wait
     * @return whether such a line exists
     * @throws InterruptedException when interrupted while waiting
     */
    public boolean await(String fragment, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lines) {
            while (true) {
                for (String line : lines) {
                    if (line.contains(fragment)) {
                        return true;
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                lines.wait(left);
            }
        }
    }

    /**
     * @param fragment the text to count
     * @return how many lines kept in memory contain {@code fragment}
     */
    public int count(String fragment) {
        int count = 0;
        for (String line : lines()) {
            if (line.contains(fragment)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        synchronized (lines) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Closing a log file that could not be flushed loses nothing the memory copy lacks.
                }
            }
        }
    }

    /**
     * Replaces every credential and every {@code Bearer} value in {@code text} by its masked form.
     *
     * @param text any text, or {@code null}
     * @return the text with no credential readable in it; {@code null} for {@code null}
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        Matcher credentials = CREDENTIAL.matcher(text);
        StringBuffer once = new StringBuffer();
        while (credentials.find()) {
            credentials.appendReplacement(once,
                    Matcher.quoteReplacement(credentials.group(1) + "***#" + fingerprint(credentials.group())));
        }
        credentials.appendTail(once);
        Matcher bearers = BEARER.matcher(once.toString());
        StringBuffer twice = new StringBuffer();
        while (bearers.find()) {
            String value = bearers.group(2);
            String masked = value.contains("***") ? value : "***#" + fingerprint(value);
            bearers.appendReplacement(twice, Matcher.quoteReplacement(bearers.group(1) + masked));
        }
        bearers.appendTail(twice);
        return twice.toString();
    }

    /**
     * @param secret a credential or bearer value
     * @return the first eight lower-case hex digits of its SHA-256
     */
    public static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
