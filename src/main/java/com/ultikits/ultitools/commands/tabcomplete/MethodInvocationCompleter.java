package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSuggest;
import com.ultikits.ultitools.utils.ReflectionUtil;

/**
 * Completer that invokes suggestion methods annotated with @CmdSuggest.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class MethodInvocationCompleter implements TabCompleter {
    
    private static final Logger logger = Logger.getLogger(MethodInvocationCompleter.class.getName());
    
    @Override
    public List<String> complete(TabCompletionContext context) {
        Method matchedMethod = context.getMatchedMethod();
        String parameterName = context.getParameterName();
        
        if (matchedMethod == null || parameterName == null) {
            return Collections.emptyList();
        }
        
        String suggestName = getSuggestName(matchedMethod, parameterName);
        if (suggestName == null || suggestName.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Find and invoke the suggest method
        Method[] suggestMethods = getSuggestMethodsByName(context.getExecutorInstance(), suggestName);
        if (suggestMethods == null || suggestMethods.length == 0) {
            // If no method found, return the suggestName as a hint
            UltiToolsPlugin plugin = UltiTools.getInstance().getCommandManager()
                    .getPluginByCommand(context.getCommand());
            if (plugin != null) {
                String hint = plugin.i18n(suggestName);
                if (hint != null && !hint.isEmpty()) {
                    return Collections.singletonList(hint);
                }
            }
            return Collections.emptyList();
        }
        
        return invokeSuggestMethod(context, suggestMethods[0]);
    }
    
    /**
     * Gets the suggest method name for a parameter.
     */
    private String getSuggestName(Method method, String paramName) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        for (Annotation[] annotations : parameterAnnotations) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof CmdParam) {
                    CmdParam cmdParam = (CmdParam) annotation;
                    if (paramName.equals(cmdParam.value())) {
                        return cmdParam.suggest();
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Gets suggest methods by name from the executor class or @CmdSuggest classes.
     * <p>
     * {@code public static} (not {@code private}) so {@code PluginManager}'s load-time contract
     * validation (T-05-fix Part 2) can resolve exactly the method this completer would invoke at
     * completion time -- own class first, then {@code @CmdSuggest}-referenced classes -- without
     * a second, independently-maintained lookup implementation. Neither this method nor {@link
     * #findMethodsByName(Class, String)} reads instance state, so both are {@code static}.
     *
     * @param executor    the executor instance whose class (and {@code @CmdSuggest}-referenced
     *                    classes) is searched
     * @param suggestName the {@code @CmdParam.suggest()} value, a plain method name (with or
     *                    without a trailing {@code "()"})
     * @return the matching methods, or {@code null} if none found
     */
    public static Method[] getSuggestMethodsByName(Object executor, String suggestName) {
        if (executor == null) {
            return null;
        }
        
        // Remove () suffix if present
        String methodName = suggestName;
        if (methodName.endsWith("()")) {
            methodName = methodName.substring(0, methodName.length() - 2);
        }
        
        Class<?> executorClass = executor.getClass();
        
        // First check the executor class itself
        Method[] methods = findMethodsByName(executorClass, methodName);
        if (methods != null && methods.length > 0) {
            return methods;
        }
        
        // Then check @CmdSuggest annotated classes
        CmdSuggest cmdSuggest = executorClass.getAnnotation(CmdSuggest.class);
        if (cmdSuggest != null) {
            for (Class<?> suggestClass : cmdSuggest.value()) {
                methods = findMethodsByName(suggestClass, methodName);
                if (methods != null && methods.length > 0) {
                    return methods;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Finds methods by name in a class.
     */
    private static Method[] findMethodsByName(Class<?> clazz, String methodName) {
        // Walk the hierarchy: on an AOP proxy, getDeclaredMethods() returns only the intercepted
        // overrides, so a suggest method declared elsewhere in the class would silently stop being
        // found. See issue #190.
        List<Method> found = new ArrayList<>();
        for (Method method : ReflectionUtil.getAllMethods(clazz)) {
            if (method.getName().equals(methodName)) {
                found.add(method);
            }
        }
        return found.isEmpty() ? null : found.toArray(new Method[0]);
    }
    
    /**
     * Invokes the suggest method and returns the results.
     */
    @SuppressWarnings("unchecked")
    private List<String> invokeSuggestMethod(TabCompletionContext context, Method suggestMethod) {
        try {
            suggestMethod.setAccessible(true);
            
            // Determine the target object
            Object target = context.getExecutorInstance();
            Class<?> declaringClass = suggestMethod.getDeclaringClass();
            
            if (!declaringClass.isInstance(target)) {
                // Need to get bean from container
                UltiToolsPlugin plugin = UltiTools.getInstance().getCommandManager()
                        .getPluginByCommand(context.getCommand());
                if (plugin != null) {
                    target = plugin.getContext().getBean(declaringClass);
                }
            }
            
            if (target == null) {
                return Collections.emptyList();
            }
            
            // Invoke with appropriate parameters, dispatched on the SAME classification
            // isInvocableSuggestSignature (and therefore PluginManager's load-time contract
            // check, T-05-fix Part 2) uses -- one decision point, not two independently
            // maintained lists of "which signatures are supported".
            Object result;
            Class<?>[] paramTypes = suggestMethod.getParameterTypes();
            SuggestSignatureShape shape = classifySuggestSignature(paramTypes);

            switch (shape) {
                case NO_ARGS:
                    result = suggestMethod.invoke(target);
                    break;
                case PLAYER_ONLY:
                    result = suggestMethod.invoke(target, context.getPlayer());
                    break;
                case STRING_ONLY:
                    // (String) -- the current partial token being completed. Mirrors
                    // UltiEssentials' BaseEssentialsCommand#suggestOnlinePlayers/
                    // suggestOfflinePlayers/suggestAllPlayers(String prefix) (T-05-fix Part 1).
                    result = suggestMethod.invoke(target, context.getCurrentInput());
                    break;
                case PLAYER_AND_STRING:
                    // (Player, String) -- the shape 16 of UltiWorlds' 24 downstream call sites
                    // use (suggestWorlds, suggestWorldTypes, suggestOptions, suggestBooleans,
                    // suggestDifficulties). This is the real-machine-UAT regression: every one
                    // of these previously fell into the unreachable-by-construction branch below
                    // and was invoked with zero arguments, throwing IllegalArgumentException at
                    // Tab-press time (T-05-fix Part 1).
                    result = suggestMethod.invoke(target, context.getPlayer(), context.getCurrentInput());
                    break;
                case PLAYER_COMMAND_ARGS:
                    result = suggestMethod.invoke(target, context.getPlayer(), context.getCommand(),
                            context.getArgs());
                    break;
                case UNSUPPORTED:
                default:
                    // Unreachable by construction in a module that passed load --
                    // PluginManager.validateCommandExecutorContract (T-05-fix Part 2) refuses to
                    // load any module whose @CmdParam.suggest() method-name resolves to a
                    // signature outside the five shapes above. If this IS reached anyway (e.g. a
                    // signature that changed after load), fail loudly instead of silently
                    // invoking with the wrong arity and swallowing the result -- that silent
                    // wrong-invocation behaviour is exactly the defect class this fix exists to
                    // eliminate, not a fallback to preserve.
                    logger.log(Level.SEVERE, "Refusing to invoke suggest method with an unsupported "
                            + "signature (this should have been refused at load time): "
                            + declaringClass.getName() + "#" + suggestMethod.getName() + " has "
                            + paramTypes.length + " parameter(s). Supported signatures: (), (Player), "
                            + "(String), (Player, String), (Player, Command, String[]).");
                    return Collections.emptyList();
            }
            
            // Convert result to list
            if (result == null) {
                return Collections.emptyList();
            }
            
            List<String> suggestions = new ArrayList<>();
            if (result instanceof Collection) {
                for (Object item : (Collection<?>) result) {
                    suggestions.add(item.toString());
                }
            } else if (result instanceof String[]) {
                for (String item : (String[]) result) {
                    suggestions.add(item);
                }
            }
            
            // Filter by current input
            String input = context.getCurrentInput().toLowerCase();
            if (!input.isEmpty()) {
                suggestions.removeIf(s -> !s.toLowerCase().startsWith(input));
            }
            
            return suggestions;
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to invoke suggest method: " + suggestMethod.getName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * The signature shapes {@link #invokeSuggestMethod(TabCompletionContext, Method)} knows how
     * to invoke a {@code @CmdParam.suggest()} method-name target with.
     */
    private enum SuggestSignatureShape {
        NO_ARGS,
        PLAYER_ONLY,
        STRING_ONLY,
        PLAYER_AND_STRING,
        PLAYER_COMMAND_ARGS,
        UNSUPPORTED
    }

    /**
     * Classifies {@code paramTypes} into the signature shape {@link
     * #invokeSuggestMethod(TabCompletionContext, Method)} dispatches on. This is the SINGLE
     * decision point both invocation and load-time validation (via {@link
     * #isInvocableSuggestSignature(Class[])}) consult -- there is deliberately no second,
     * independently-maintained list of "which signatures are supported" for the two to drift out
     * of sync (T-05-fix Part 1 + Part 2).
     *
     * @param paramTypes the candidate suggest method's parameter types
     * @return the matching shape, or {@link SuggestSignatureShape#UNSUPPORTED} if none match
     */
    private static SuggestSignatureShape classifySuggestSignature(Class<?>[] paramTypes) {
        // Dispatch on arity FIRST (mirrors invokeSuggestMethod's own switch on the resulting
        // shape) rather than a flat chain of five independent length-and-type guards -- PMD's
        // NPathComplexity multiplies the path counts of sequential guard clauses even though
        // they are mutually exclusive by construction, which is what pushed the flat form to
        // 360 against a threshold of 200 for a method that is, in cyclomatic terms, just five
        // straight-line comparisons. Splitting by arity turns that multiplication into a sum.
        switch (paramTypes.length) {
            case 0:
                return SuggestSignatureShape.NO_ARGS;
            case 1:
                return classifySingleParamShape(paramTypes[0]);
            case 2:
                return classifyPlayerAndStringShape(paramTypes);
            case 3:
                return classifyPlayerCommandArgsShape(paramTypes);
            default:
                return SuggestSignatureShape.UNSUPPORTED;
        }
    }

    private static SuggestSignatureShape classifySingleParamShape(Class<?> paramType) {
        if (paramType == Player.class) {
            return SuggestSignatureShape.PLAYER_ONLY;
        }
        if (paramType == String.class) {
            return SuggestSignatureShape.STRING_ONLY;
        }
        return SuggestSignatureShape.UNSUPPORTED;
    }

    private static SuggestSignatureShape classifyPlayerAndStringShape(Class<?>[] paramTypes) {
        if (paramTypes[0] == Player.class && paramTypes[1] == String.class) {
            return SuggestSignatureShape.PLAYER_AND_STRING;
        }
        return SuggestSignatureShape.UNSUPPORTED;
    }

    private static SuggestSignatureShape classifyPlayerCommandArgsShape(Class<?>[] paramTypes) {
        if (paramTypes[0] == Player.class && paramTypes[1] == Command.class
                && paramTypes[2] == String[].class) {
            return SuggestSignatureShape.PLAYER_COMMAND_ARGS;
        }
        return SuggestSignatureShape.UNSUPPORTED;
    }

    /**
     * Whether {@link #invokeSuggestMethod(TabCompletionContext, Method)} can invoke a method with
     * exactly {@code paramTypes}. Exposed so {@code PluginManager}'s load-time contract check
     * (T-05-fix Part 2) can refuse a module whose {@code @CmdParam.suggest()} method-name value
     * resolves to a signature this completer cannot call -- naming the class, the mapping method
     * and the offending signature at load time, rather than invoking it with the wrong arity the
     * first time a player presses Tab.
     *
     * @param paramTypes the candidate suggest method's parameter types
     * @return {@code true} if this completer knows how to invoke a method with this exact
     *         parameter list
     */
    public static boolean isInvocableSuggestSignature(Class<?>[] paramTypes) {
        return classifySuggestSignature(paramTypes) != SuggestSignatureShape.UNSUPPORTED;
    }
}
