package com.ultikits.ultitools.exceptions;

/**
 * Exception thrown when command execution or validation fails.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class CommandException extends UltiToolsException {

    /**
     * Creates a new command exception with a generic error code.
     *
     * @param message the error message
     */
    public CommandException(String message) {
        super(ErrorCode.COMMAND_ERROR, message);
    }

    /**
     * Creates a new command exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public CommandException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new command exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public CommandException(String message, Throwable cause) {
        super(ErrorCode.COMMAND_ERROR, message, cause);
    }

    /**
     * Creates a new command exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public CommandException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception for permission denied scenarios.
     *
     * @param permission the permission that was denied
     * @return a new CommandException
     */
    public static CommandException permissionDenied(String permission) {
        return new CommandException(ErrorCode.COMMAND_PERMISSION_DENIED,
                "Permission denied: " + permission);
    }

    /**
     * Creates an exception for cooldown active scenarios.
     *
     * @param remainingSeconds the remaining cooldown time in seconds
     * @return a new CommandException
     */
    public static CommandException cooldownActive(long remainingSeconds) {
        return new CommandException(ErrorCode.COMMAND_COOLDOWN_ACTIVE,
                "Command on cooldown. Please wait " + remainingSeconds + " seconds.");
    }

    /**
     * Creates an exception for validation failures.
     *
     * @param validationMessage the validation error message
     * @return a new CommandException
     */
    public static CommandException validationFailed(String validationMessage) {
        return new CommandException(ErrorCode.COMMAND_VALIDATION_FAILED, validationMessage);
    }

    /**
     * Creates an exception for parse errors.
     *
     * @param input      the input that could not be parsed
     * @param targetType the expected type
     * @return a new CommandException
     */
    public static CommandException parseError(String input, Class<?> targetType) {
        return new CommandException(ErrorCode.COMMAND_PARSE_ERROR,
                "Cannot parse '" + input + "' as " + targetType.getSimpleName());
    }
}
