package com.ultikits.ultitools.config.document;

import org.jetbrains.annotations.ApiStatus;

/**
 * Thrown by {@link ConfigDocument#parse(String)} when a config file's text is not a document this layer
 * reads: a YAML syntax error, a global tag such as {@code !!java.util.UUID}, a top level that is not a
 * mapping, or nesting deeper than the limit. The message is the parser's own message, so it names the
 * line and column.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConfigParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the parser's message
     * @param cause   the parser's exception, or {@code null}
     */
    public ConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
