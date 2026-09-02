package com.ultikits.ultitools.abstracts.command.parser;

/**
 * Exception thrown when type parsing fails.
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class TypeParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String inputValue;
    private final Class<?> targetType;

    /**
     * Constructs a new TypeParseException with just a message.
     *
     * @param message the detail message
     */
    public TypeParseException(String message) {
        super(message);
        this.inputValue = null;
        this.targetType = null;
    }

    /**
     * Constructs a new TypeParseException with a message and cause.
     *
     * @param message the detail message
     * @param cause   the cause of the exception
     */
    public TypeParseException(String message, Throwable cause) {
        super(message, cause);
        this.inputValue = null;
        this.targetType = null;
    }

    /**
     * Constructs a new TypeParseException.
     *
     * @param inputValue the input value that failed to parse
     * @param targetType the target type that was expected
     * @param message    the detail message
     */
    public TypeParseException(String inputValue, Class<?> targetType, String message) {
        super(message);
        this.inputValue = inputValue;
        this.targetType = targetType;
    }

    /**
     * Constructs a new TypeParseException with a cause.
     *
     * @param inputValue the input value that failed to parse
     * @param targetType the target type that was expected
     * @param message    the detail message
     * @param cause      the cause of the exception
     */
    public TypeParseException(String inputValue, Class<?> targetType, String message, Throwable cause) {
        super(message, cause);
        this.inputValue = inputValue;
        this.targetType = targetType;
    }

    /**
     * Gets the input value that failed to parse.
     *
     * @return the input value
     */
    public String getInputValue() {
        return inputValue;
    }

    /**
     * Gets the target type that was expected.
     *
     * @return the target type
     */
    public Class<?> getTargetType() {
        return targetType;
    }

    /**
     * Creates a TypeParseException for an invalid format.
     *
     * @param value      the invalid value
     * @param targetType the expected type
     * @return a new TypeParseException
     */
    public static TypeParseException invalidFormat(String value, Class<?> targetType) {
        return new TypeParseException(value, targetType,
                String.format("Cannot parse '%s' as %s: invalid format", value, targetType.getSimpleName()));
    }

    /**
     * Creates a TypeParseException for a null value.
     *
     * @param targetType the expected type
     * @return a new TypeParseException
     */
    public static TypeParseException nullValue(Class<?> targetType) {
        return new TypeParseException(null, targetType,
                String.format("Cannot parse null as %s", targetType.getSimpleName()));
    }
}
