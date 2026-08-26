package com.ultikits.ultitools.aop;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.manager.ErrorReportCollector;
import com.ultikits.ultitools.manager.TriggerContext;

/**
 * AOP interceptor that handles @ExceptionCatch annotated methods.
 * <p>
 * When a method throws an exception that matches the @ExceptionCatch configuration,
 * this interceptor catches it and returns a default value instead of propagating.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public class ExceptionInterceptor implements MethodInterceptor {

    private static final Logger LOGGER = Logger.getLogger(ExceptionInterceptor.class.getName());

    private final List<ExceptionHandler> globalHandlers;
    /** Instance-scoped on purpose - see AnnotationLookupCache's class javadoc. */
    private final AnnotationLookupCache<ExceptionCatch> lookupCache =
            new AnnotationLookupCache<>(ExceptionCatch.class);

    /**
     * Container used to resolve {@code @ExceptionCatch(handler = "...")} beans.
     * <p>
     * Injected per plugin rather than read from the global {@code ContextHolder}: each plugin has
     * its own container, and a single static holder would make the last plugin to initialise win,
     * sending every earlier plugin's handler lookup to the wrong container. See issue #190.
     */
    private final SimpleContainer context;

    /**
     * Creates an exception interceptor with no global handlers and no container.
     */
    public ExceptionInterceptor() {
        this(Collections.emptyList(), null);
    }

    /**
     * Creates an exception interceptor with the given global handlers and no container.
     *
     * @param globalHandlers handlers to try for any exception
     */
    public ExceptionInterceptor(List<ExceptionHandler> globalHandlers) {
        this(globalHandlers, null);
    }

    /**
     * Creates an exception interceptor bound to a plugin's container.
     *
     * @param globalHandlers handlers to try for any exception
     * @param context        the container used to resolve named handlers, may be null
     */
    public ExceptionInterceptor(List<ExceptionHandler> globalHandlers, SimpleContainer context) {
        this.globalHandlers = globalHandlers != null ? new ArrayList<>(globalHandlers) : new ArrayList<>();
        this.context = context;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // Method level first, then class level through the shared lookup. Resolving the class
        // level here independently is what previously let the advisor decide a method was covered
        // while this interceptor found no annotation and quietly re-threw - proxied, annotated and
        // inert. AopAdvisor.findClassLevelAnnotation is the single definition of which class-level
        // annotation governs a method, so the two cannot disagree. See issue #309.
        // Spring's precedence: the method itself, then the target class, then the
        // declaration this method overrides. A subclass writing its own class-level
        // annotation outranks a method-level one it inherited - the subclass author is
        // closer to the bean than whoever wrote the superclass method.
        ExceptionCatch annotation = lookupCache.ownMethod(method);
        if (annotation == null) {
            annotation = lookupCache.classLevel(method);
        }
        if (annotation == null) {
            annotation = lookupCache.inheritedMethod(method);
        }

        try {
            return invocation.proceed();
        } catch (Throwable e) {
            // Check if this exception should be caught
            if (annotation != null && shouldCatch(e, annotation.value())) {
                return handleCaughtException(e, invocation, annotation);
            }

            // Try global handlers
            for (ExceptionHandler handler : globalHandlers) {
                if (handler.supports(e.getClass())) {
                    return handler.handleException(e, invocation.getTarget(), method, invocation.getArguments());
                }
            }

            // Re-throw if not handled
            throw e;
        }
    }

    /**
     * Checks if the given exception should be caught based on the configured types.
     */
    private boolean shouldCatch(Throwable e, Class<? extends Throwable>[] types) {
        for (Class<? extends Throwable> type : types) {
            if (type.isInstance(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The class the bean actually is, with any proxy layers unwrapped.
     * <p>
     * Used for <b>attribution only</b>. Which class-level annotation governs a method is decided
     * by {@link AopAdvisor#findClassLevelAnnotation(Method, Class)}, which anchors on the declaring
     * class and never consults the target. Attribution wants the opposite: for an inherited method
     * the declaring class is often a framework base, which tells an operator nothing about which
     * concrete bean failed and makes ErrorReportCollector's fingerprint dedup collapse two
     * different beans into one report. The declaring class remains the fallback for a null target -
     * that branch is not proxy identity and is out of scope for this method's unwrap. See issue
     * #309.
     * <p>
     * Unwrapping itself delegates to {@link ProxyFactory#unwrap(Class)}: a proxy of a proxy already
     * names the original target, so no hierarchy walk is needed here either.
     *
     * @param invocation the invocation in progress
     * @param method     the method being intercepted
     * @return the bean class, never null
     */
    private static Class<?> beanClassOf(MethodInvocation invocation, Method method) {
        Object target = invocation.getTarget();
        if (target == null) {
            return method.getDeclaringClass();
        }
        return ProxyFactory.unwrap(target.getClass());
    }

    /**
     * Handles a caught exception according to the annotation configuration.
     */
    private Object handleCaughtException(Throwable e, MethodInvocation invocation, ExceptionCatch annotation)
            throws Throwable {

        Method method = invocation.getMethod();
        // The bean, not the declaring class. For an inherited method the declaring class is a
        // superclass - often a framework base - which tells an operator nothing about which of the
        // several concrete beans failed, and makes two different beans that inherit one method
        // collapse into a single deduplicated error report.
        String beanName = beanClassOf(invocation, method).getSimpleName();

        // Log unless silent
        if (!annotation.silent()) {
            LOGGER.log(Level.WARNING, "Exception caught in " + beanName
                    + "." + method.getName() + "(): " + e.getMessage(), e);
        }

        // Report to error collector
        try {
            UltiTools instance = UltiTools.getInstance();
            if (instance != null) {
                ErrorReportCollector erc = instance.getErrorReportCollector();
                if (erc != null) {
                    erc.reportError(e, beanName,
                            TriggerContext.aop(beanName, method.getName()));
                }
            }
        } catch (Exception ignored) {
            // Never re-enter logging from error reporting
        }

        // Try custom handler first
        if (!annotation.handler().isEmpty()) {
            if (context == null) {
                // The author named a handler explicitly; saying nothing here would make a
                // configuration mistake indistinguishable from a handler that ran and returned
                // the default value.
                LOGGER.warning("@ExceptionCatch(handler = \"" + annotation.handler() + "\") on "
                        + beanClassOf(invocation, method).getName() + "#" + method.getName()
                        + " cannot be resolved: this interceptor has no container. "
                        + "Falling back to the default value.");
            } else {
                // Bean resolution is the only thing this catch covers - a lookup failure is a
                // configuration mistake and still falls back gracefully. What handleException()
                // itself throws (below, outside this try) must not be caught here: a handler that
                // deliberately re-throws is indistinguishable from one that failed unless its
                // throw is allowed to reach the caller (D-06/D-07).
                Object handlerBean;
                try {
                    handlerBean = context.getBean(annotation.handler());
                } catch (Exception resolutionFailure) {
                    LOGGER.log(Level.WARNING, "@ExceptionCatch(handler = \"" + annotation.handler()
                            + "\") on " + beanClassOf(invocation, method).getName() + "#"
                            + method.getName() + " failed to resolve. Falling back to the "
                            + "default value.", resolutionFailure);
                    handlerBean = null;
                }
                if (handlerBean instanceof ExceptionHandler) {
                    // No catch here: whatever this throws - including a checked Throwable the
                    // intercepted method's own throws clause does not declare - must propagate to
                    // the caller unconditionally. See SneakyThrows for why a checked type still
                    // compiles through this call site's uncaught path when it needs to.
                    return ((ExceptionHandler) handlerBean).handleException(
                            e, invocation.getTarget(), method, invocation.getArguments());
                }
                LOGGER.warning("@ExceptionCatch(handler = \"" + annotation.handler() + "\") on "
                        + beanClassOf(invocation, method).getName() + "#" + method.getName()
                        + " resolved to a bean of type "
                        + (handlerBean == null ? "null" : handlerBean.getClass().getName())
                        + ", which does not implement ExceptionHandler. "
                        + "Falling back to the default value.");
            }
        }

        // Parse default value expression
        String defaultValueExpr = annotation.defaultValue();
        if (!defaultValueExpr.isEmpty()) {
            return parseDefaultValue(defaultValueExpr, method.getReturnType());
        }

        // Return type-appropriate default value
        return getDefaultValue(method.getReturnType());
    }

    /**
     * Parses a default value expression into an actual value.
     */
    private Object parseDefaultValue(String expression, Class<?> returnType) {
        if (expression == null || expression.isEmpty()) {
            return getDefaultValue(returnType);
        }

        String expr = expression.trim().toLowerCase();

        switch (expr) {
            case "null":
                return null;
            case "true":
                return true;
            case "false":
                return false;
            case "empty":
                return getEmptyValue(returnType);
            default:
                // Try to parse as number
                try {
                    if (returnType == int.class || returnType == Integer.class) {
                        return Integer.parseInt(expression);
                    } else if (returnType == long.class || returnType == Long.class) {
                        return Long.parseLong(expression);
                    } else if (returnType == double.class || returnType == Double.class) {
                        return Double.parseDouble(expression);
                    } else if (returnType == float.class || returnType == Float.class) {
                        return Float.parseFloat(expression);
                    }
                } catch (NumberFormatException ignored) {
                }
                // Return the expression as string if return type is String
                if (returnType == String.class) {
                    return expression;
                }
                return getDefaultValue(returnType);
        }
    }

    /**
     * Gets the default value for a type.
     */
    private Object getDefaultValue(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    /**
     * Gets an empty value for collection/array/string types.
     */
    private Object getEmptyValue(Class<?> type) {
        if (type == String.class) {
            return "";
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (List.class.isAssignableFrom(type)) {
            return new ArrayList<>();
        }
        if (Set.class.isAssignableFrom(type)) {
            return new HashSet<>();
        }
        if (Map.class.isAssignableFrom(type)) {
            return new HashMap<>();
        }
        if (Collection.class.isAssignableFrom(type)) {
            return new ArrayList<>();
        }
        if (Optional.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        return getDefaultValue(type);
    }
}
