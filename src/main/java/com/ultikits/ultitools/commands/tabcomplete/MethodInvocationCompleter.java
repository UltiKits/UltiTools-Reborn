package com.ultikits.ultitools.commands.tabcomplete;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSuggest;

/**
 * Completer that invokes suggestion methods annotated with @CmdSuggest.
 * <p>
 * 调用 @CmdSuggest 注解标注的建议方法的补全器。
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
     * 获取参数的建议方法名称。
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
     * 从执行器类或 @CmdSuggest 类中按名称获取建议方法。
     */
    private Method[] getSuggestMethodsByName(Object executor, String suggestName) {
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
     * 在类中按名称查找方法。
     */
    private Method[] findMethodsByName(Class<?> clazz, String methodName) {
        List<Method> found = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                found.add(method);
            }
        }
        return found.isEmpty() ? null : found.toArray(new Method[0]);
    }
    
    /**
     * Invokes the suggest method and returns the results.
     * 调用建议方法并返回结果。
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
            
            // Invoke with appropriate parameters
            Object result;
            Class<?>[] paramTypes = suggestMethod.getParameterTypes();
            
            if (paramTypes.length == 0) {
                result = suggestMethod.invoke(target);
            } else if (paramTypes.length == 3) {
                // (Player, Command, String[])
                result = suggestMethod.invoke(target, context.getPlayer(), context.getCommand(), context.getArgs());
            } else if (paramTypes.length == 1 && paramTypes[0] == Player.class) {
                result = suggestMethod.invoke(target, context.getPlayer());
            } else {
                result = suggestMethod.invoke(target);
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
}
