package com.ultikits.ultitools.exceptions;

/**
 * Base exception class for all UltiTools exceptions.
 * <p>
 * All custom exceptions in UltiTools should extend this class to provide
 * consistent error handling with error codes.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public abstract class UltiToolsException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Creates a new exception with the given error code.
     *
     * @param errorCode the error code
     */
    protected UltiToolsException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * Creates a new exception with the given error code and custom message.
     *
     * @param errorCode the error code
     * @param message   the custom error message
     */
    protected UltiToolsException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new exception with the given error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the custom error message
     * @param cause     the underlying cause
     */
    protected UltiToolsException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new exception with the given error code and cause.
     *
     * @param errorCode the error code
     * @param cause     the underlying cause
     */
    protected UltiToolsException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    /**
     * Gets the error code associated with this exception.
     *
     * @return the error code
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Gets the formatted error message including the error code.
     *
     * @return the formatted message
     */
    public String getFormattedMessage() {
        return "[" + errorCode.getFormattedCode() + "] " + getMessage();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + getFormattedMessage();
    }
}
