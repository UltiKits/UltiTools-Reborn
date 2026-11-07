package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.ApiStatus;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * What reading one config file found. {@link ConfigDocument#load(Path)} returns one of four states and never
 * throws, so a file that exists but cannot be read or parsed is never mistaken for a missing file - the
 * distinction that keeps the framework from overwriting an operator's broken file with defaults
 * (UltiKits/UltiTools-Reborn#511, #470).
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConfigLoadResult {

    private static final Pattern PARSER_LOCATION = Pattern.compile(
            "(?m)^ *in [^\\r\\n]*, line ([0-9]+), column ([0-9]+):? *$");

    /** The four outcomes of a load. */
    public enum State {
        /** No file exists at the path. */
        ABSENT,
        /** The file was read and parsed; {@link #document()} and {@link #fingerprint()} are set. */
        LOADED,
        /** The file exists but reading it failed; {@link #cause()} is set. */
        UNREADABLE,
        /** The file was read but is not a document this layer reads; {@link #parserMessage()} is set. */
        UNPARSEABLE
    }

    private final Path file;
    private final State state;
    private final ConfigDocument document;
    private final String fingerprint;
    private final IOException cause;
    private final String parserMessage;
    private final String parserLocation;

    private ConfigLoadResult(Path file, State state, ConfigDocument document, String fingerprint, IOException cause,
                             String parserMessage, String parserLocation) {
        this.file = file;
        this.state = state;
        this.document = document;
        this.fingerprint = fingerprint;
        this.cause = cause;
        this.parserMessage = parserMessage;
        this.parserLocation = parserLocation;
    }

    static ConfigLoadResult absent(Path file) {
        return new ConfigLoadResult(file, State.ABSENT, null, null, null, null, null);
    }

    static ConfigLoadResult loaded(Path file, ConfigDocument document, String fingerprint) {
        return new ConfigLoadResult(file, State.LOADED, document, fingerprint, null, null, null);
    }

    static ConfigLoadResult unreadable(Path file, IOException cause) {
        return new ConfigLoadResult(file, State.UNREADABLE, null, null, cause, null, null);
    }

    static ConfigLoadResult unparseable(Path file, String parserMessage) {
        return unparseable(file, parserMessage, null);
    }

    static ConfigLoadResult unparseable(Path file, String parserMessage, Throwable parserFailure) {
        return new ConfigLoadResult(file, State.UNPARSEABLE, null, null, null, parserMessage,
                safeParserLocation(parserFailure, parserMessage));
    }

    /** Extracts only numeric metadata while the typed parser failure is still available. */
    private static String safeParserLocation(Throwable failure, String message) {
        if (failure instanceof MarkedYAMLException) {
            Mark problem = ((MarkedYAMLException) failure).getProblemMark();
            if (problem != null) {
                return "invalid YAML at line " + (problem.getLine() + 1) + ", column " + (problem.getColumn() + 1);
            }
        }
        String location = "invalid YAML or UTF-8";
        if (message != null) {
            // SnakeYAML prints the context mark first and the problem mark last. Never include
            // the filename, reason, scalar snippet or any other part of the parser message.
            Matcher marks = PARSER_LOCATION.matcher(message);
            while (marks.find()) {
                location = "invalid YAML at line " + marks.group(1) + ", column " + marks.group(2);
            }
        }
        return location;
    }

    /**
     * The file that was read.
     *
     * @return the path as passed to {@code load}
     */
    public Path file() {
        return file;
    }

    /**
     * The outcome.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * The parsed document.
     *
     * @return the document when {@link State#LOADED}, otherwise {@code null}
     */
    public ConfigDocument document() {
        return document;
    }

    /**
     * The SHA-256 of the bytes read, as 64 lower-case hex digits, so a later write can tell whether the file
     * changed on disk since it was read.
     *
     * @return the fingerprint when {@link State#LOADED}, otherwise {@code null}
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Why the file could not be read.
     *
     * @return the I/O failure when {@link State#UNREADABLE}, otherwise {@code null}
     */
    public IOException cause() {
        return cause;
    }

    /**
     * Why the file could not be parsed: the parser's own message, naming the line and column.
     *
     * @return the message when {@link State#UNPARSEABLE}, otherwise {@code null}
     */
    public String parserMessage() {
        return parserMessage;
    }

    /**
     * A value-safe parse diagnostic shared by configuration loads, reloads and migration.
     * The typed parser problem mark takes precedence over text metadata; no raw exception is retained.
     *
     * @return the one-based problem line and column, or a generic YAML/UTF-8 failure category
     */
    public String safeParserLocation() {
        return parserLocation == null ? "invalid YAML or UTF-8" : parserLocation;
    }

    @Override
    public String toString() {
        return "ConfigLoadResult{" + state + ", " + file + "}";
    }
}
