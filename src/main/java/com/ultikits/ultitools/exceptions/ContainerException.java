package com.ultikits.ultitools.exceptions;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;

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

    /**
     * Creates an exception for a malformed {@code @AliasFor} declaration -- one that fails one
     * of Spring's documented Implementation Requirements for an explicit meta-annotation alias
     * (D-02). The message always names both the declaring annotation and the offending
     * attribute, so the module author can act on it without cross-referencing anything else.
     *
     * @param declaringAnnotation the annotation type that declares the malformed {@code @AliasFor}
     * @param attribute           the offending attribute's name
     * @param reason              a specific reason clause describing which requirement failed
     * @return a new ContainerException
     */
    public static ContainerException malformedAliasFor(Class<? extends Annotation> declaringAnnotation,
            String attribute, String reason) {
        return new ContainerException(ErrorCode.MALFORMED_ANNOTATION_ALIAS,
                "Malformed @AliasFor on " + declaringAnnotation.getName() + "#" + attribute + "(): " + reason);
    }

    /**
     * Creates an exception for an unresolvable {@code @Autowired(required = true)} field (D-08).
     * <p>
     * The message names both the dependency's type and the field's declaring class plus field
     * name, and repeats the same actionable guidance the earlier warning-only diagnostic gave:
     * check the target is annotated {@code @Service}/{@code @Component}, that it is inside the
     * module's {@code scanBasePackages}, that the owning object was created by the container
     * rather than with {@code new}, and that {@code @Autowired(required = false)} exists for
     * genuinely optional dependencies.
     *
     * @param field the field whose dependency could not be resolved
     * @return a new ContainerException
     */
    public static ContainerException requiredDependencyUnresolved(Field field) {
        return new ContainerException(ErrorCode.DEPENDENCY_INJECTION_FAILED,
                "@Autowired could not resolve " + field.getType().getName() + " for field "
                        + field.getDeclaringClass().getName() + "." + field.getName()
                        + " - check that the target class is annotated with @Service/@Component, "
                        + "that it is inside the module's scanBasePackages, and that the owning "
                        + "object was created by the container rather than with 'new'. "
                        + "Use @Autowired(required = false) if the dependency really is optional.");
    }
}
