package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.file.Path;

import org.jetbrains.annotations.ApiStatus;

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

    private ConfigLoadResult(Path file, State state, ConfigDocument document, String fingerprint, IOException cause,
                             String parserMessage) {
        this.file = file;
        this.state = state;
        this.document = document;
        this.fingerprint = fingerprint;
        this.cause = cause;
        this.parserMessage = parserMessage;
    }

    static ConfigLoadResult absent(Path file) {
        return new ConfigLoadResult(file, State.ABSENT, null, null, null, null);
    }

    static ConfigLoadResult loaded(Path file, ConfigDocument document, String fingerprint) {
        return new ConfigLoadResult(file, State.LOADED, document, fingerprint, null, null);
    }

    static ConfigLoadResult unreadable(Path file, IOException cause) {
        return new ConfigLoadResult(file, State.UNREADABLE, null, null, cause, null);
    }

    static ConfigLoadResult unparseable(Path file, String parserMessage) {
        return new ConfigLoadResult(file, State.UNPARSEABLE, null, null, null, parserMessage);
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

    @Override
    public String toString() {
        return "ConfigLoadResult{" + state + ", " + file + "}";
    }
}
