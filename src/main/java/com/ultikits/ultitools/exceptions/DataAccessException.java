package com.ultikits.ultitools.exceptions;

/**
 * Exception thrown when data access operations fail.
 * <p>
 * This includes database connection errors, query failures, entity not found, etc.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class DataAccessException extends UltiToolsException {

    /**
     * Creates a new data access exception with a generic error code.
     *
     * @param message the error message
     */
    public DataAccessException(String message) {
        super(ErrorCode.DATA_ACCESS_ERROR, message);
    }

    /**
     * Creates a new data access exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public DataAccessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new data access exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public DataAccessException(String message, Throwable cause) {
        super(ErrorCode.DATA_ACCESS_ERROR, message, cause);
    }

    /**
     * Creates a new data access exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public DataAccessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception for entity not found scenarios.
     *
     * @param entityType the type of entity that was not found
     * @param identifier the identifier used to look up the entity
     * @return a new DataAccessException
     */
    public static DataAccessException entityNotFound(Class<?> entityType, Object identifier) {
        return new DataAccessException(ErrorCode.ENTITY_NOT_FOUND,
                "Entity of type " + entityType.getSimpleName() + " not found with identifier: " + identifier);
    }

    /**
     * Creates an exception for database connection failures.
     *
     * @param cause the underlying cause
     * @return a new DataAccessException
     */
    public static DataAccessException connectionFailed(Throwable cause) {
        return new DataAccessException(ErrorCode.CONNECTION_FAILED, "Failed to connect to database", cause);
    }

    /**
     * Creates an exception for query failures.
     *
     * @param query the query that failed
     * @param cause the underlying cause
     * @return a new DataAccessException
     */
    public static DataAccessException queryFailed(String query, Throwable cause) {
        return new DataAccessException(ErrorCode.DATA_QUERY_FAILED, "Query failed: " + query, cause);
    }
}
