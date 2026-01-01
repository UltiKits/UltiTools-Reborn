package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdTarget;

import java.lang.reflect.Method;

/**
 * Validates that the command sender matches the expected target type.
 * <p>
 * 验证命令发送者是否匹配预期的目标类型。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class SenderTypeValidator implements CommandValidator {
    
    private static final int ORDER = 100;
    
    private final CmdTarget.CmdTargetType expectedType;
    
    /**
     * Creates a validator that accepts any sender type.
     * 创建一个接受任何发送者类型的验证器。
     */
    public SenderTypeValidator() {
        this.expectedType = CmdTarget.CmdTargetType.BOTH;
    }
    
    /**
     * Creates a validator for the specified sender type.
     * 创建指定发送者类型的验证器。
     *
     * @param expectedType the expected sender type
     */
    public SenderTypeValidator(CmdTarget.CmdTargetType expectedType) {
        this.expectedType = expectedType;
    }
    
    @Override
    public ValidationResult validate(CommandContext context) {
        CmdTarget.CmdTargetType effectiveType = determineTargetType(context);
        
        if (effectiveType == CmdTarget.CmdTargetType.BOTH) {
            return ValidationResult.success();
        }
        
        if (effectiveType == CmdTarget.CmdTargetType.PLAYER && !context.isPlayer()) {
            return ValidationResult.failure(
                    UltiTools.getInstance().i18n("只有游戏内可以执行这个指令！"),
                    "command.error.player_only"
            );
        }
        
        if (effectiveType == CmdTarget.CmdTargetType.CONSOLE && context.isPlayer()) {
            return ValidationResult.failure(
                    UltiTools.getInstance().i18n("只可以在后台执行这个指令！"),
                    "command.error.console_only"
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Determines the effective target type from context or method annotation.
     * 从上下文或方法注解确定有效的目标类型。
     *
     * @param context the command context
     * @return the effective target type
     */
    private CmdTarget.CmdTargetType determineTargetType(CommandContext context) {
        // First check method-level annotation
        Method method = context.getMatchedMethod();
        if (method != null && method.isAnnotationPresent(CmdTarget.class)) {
            return method.getAnnotation(CmdTarget.class).value();
        }
        
        // Use the configured type
        return expectedType;
    }
    
    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public String getName() {
        return "SenderTypeValidator";
    }
    
    /**
     * Creates a validator from a CmdTarget annotation.
     * 从 CmdTarget 注解创建验证器。
     *
     * @param annotation the annotation
     * @return a new validator
     */
    public static SenderTypeValidator fromAnnotation(CmdTarget annotation) {
        if (annotation == null) {
            return new SenderTypeValidator();
        }
        return new SenderTypeValidator(annotation.value());
    }
}
