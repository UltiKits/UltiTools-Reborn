package com.ultikits.ultitools.exceptions;

/**
 * Exception thrown when IoC container operations fail.
 * <p>
 * This includes bean creation, dependency injection, and lifecycle management errors.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class ContainerException extends UltiToolsException {

    /**
     * Creates a new container exception with the given message.
     *
     * @param message the error message
     */
    public ContainerException(String message) {
        super(ErrorCode.BEAN_CREATION_FAILED, message);
    }

    /**
     * Creates a new container exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public ContainerException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new container exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public ContainerException(String message, Throwable cause) {
        super(ErrorCode.BEAN_CREATION_FAILED, message, cause);
    }

    /**
     * Creates a new container exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public ContainerException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception for bean not found scenarios.
     *
     * @param beanType the type of bean that was not found
     * @return a new ContainerException
     */
    public static ContainerException beanNotFound(Class<?> beanType) {
        return new ContainerException(ErrorCode.BEAN_NOT_FOUND,
                "No bean of type " + beanType.getName() + " found in container");
    }

    /**
     * Creates an exception for bean not found by name scenarios.
     *
     * @param beanName the name of the bean that was not found
     * @return a new ContainerException
     */
    public static ContainerException beanNotFound(String beanName) {
        return new ContainerException(ErrorCode.BEAN_NOT_FOUND,
                "No bean named '" + beanName + "' found in container");
    }

    /**
     * Creates an exception for circular dependency scenarios.
     *
     * @param beanName the bean involved in the circular dependency
     * @return a new ContainerException
     */
    public static ContainerException circularDependency(String beanName) {
        return new ContainerException(ErrorCode.CIRCULAR_DEPENDENCY,
                "Circular dependency detected while creating bean: " + beanName);
    }

    /**
     * Creates an exception for dependency injection failures.
     *
     * @param targetType    the type being injected into
     * @param dependencyType the type of the dependency that could not be injected
     * @param cause         the underlying cause
     * @return a new ContainerException
     */
    public static ContainerException injectionFailed(Class<?> targetType, Class<?> dependencyType, Throwable cause) {
        return new ContainerException(ErrorCode.DEPENDENCY_INJECTION_FAILED,
                "Failed to inject " + dependencyType.getName() + " into " + targetType.getName(), cause);
    }

    /**
     * Creates an exception for duplicate bean definitions.
     *
     * @param beanName the name of the duplicate bean
     * @param beanType the type of the bean
     * @return a new ContainerException
     */
    public static ContainerException duplicateBean(String beanName, Class<?> beanType) {
        return new ContainerException(ErrorCode.DUPLICATE_BEAN,
                "Duplicate bean definition: " + beanName + " of type " + beanType.getName());
    }
}
