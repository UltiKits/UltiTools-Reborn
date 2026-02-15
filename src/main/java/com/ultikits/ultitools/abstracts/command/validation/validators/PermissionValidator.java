package com.ultikits.ultitools.abstracts.command.validation.validators;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.CommandContext;
import com.ultikits.ultitools.abstracts.command.validation.CommandValidator;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import org.bukkit.ChatColor;

import java.lang.reflect.Method;

/**
 * Validates that the command sender has the required permission.
 * Supports both class-level and method-level permission requirements.
 * <p>
 * 验证命令发送者是否具有所需权限。
 * 支持类级别和方法级别的权限要求。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public class PermissionValidator implements CommandValidator {
    
    private static final int ORDER = 200;
    
    private final String basePermission;
    private final boolean requireOp;
    
    /**
     * Creates a permission validator with no base permission requirement.
     * 创建一个没有基本权限要求的权限验证器。
     */
    public PermissionValidator() {
        this.basePermission = null;
        this.requireOp = false;
    }
    
    /**
     * Creates a permission validator with the specified permission.
     * 创建具有指定权限的权限验证器。
     *
     * @param permission the required permission
     * @param requireOp  whether OP is required
     */
    public PermissionValidator(String permission, boolean requireOp) {
        this.basePermission = (permission != null && !permission.isEmpty()) ? permission : null;
        this.requireOp = requireOp;
    }
    
    @Override
    public ValidationResult validate(CommandContext context) {
        // Check OP requirement
        if (requireOp && !context.getSender().isOp()) {
            return ValidationResult.failure(
                    ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"),
                    "command.error.op_required"
            );
        }
        
        // Check method-level OP requirement
        Method method = context.getMatchedMethod();
        if (method != null && method.isAnnotationPresent(CmdMapping.class)) {
            CmdMapping mapping = method.getAnnotation(CmdMapping.class);
            if (mapping.requireOp() && !context.getSender().isOp()) {
                return ValidationResult.failure(
                        ChatColor.RED + UltiTools.getInstance().i18n("你没有权限执行这个指令！"),
                        "command.error.op_required"
                );
            }
        }
        
        // Check base permission
        if (basePermission != null && !context.getSender().hasPermission(basePermission)) {
            return ValidationResult.failure(
                    String.format(UltiTools.getInstance().i18n("需要权限"), basePermission),
                    "command.error.no_permission"
            );
        }
        
        // Check method-level permission
        if (method != null && method.isAnnotationPresent(CmdMapping.class)) {
            CmdMapping mapping = method.getAnnotation(CmdMapping.class);
            String methodPermission = mapping.permission();
            if (!methodPermission.isEmpty() && !context.getSender().hasPermission(methodPermission)) {
                return ValidationResult.failure(
                        String.format(UltiTools.getInstance().i18n("需要权限"), methodPermission),
                        "command.error.no_permission"
                );
            }
        }
        
        return ValidationResult.success();
    }
    
    @Override
    public int getOrder() {
        return ORDER;
    }
    
    @Override
    public String getName() {
        return "PermissionValidator";
    }
    
    /**
     * Creates a validator from a CmdExecutor annotation.
     * 从 CmdExecutor 注解创建验证器。
     *
     * @param annotation the annotation
     * @return a new validator
     */
    public static PermissionValidator fromAnnotation(CmdExecutor annotation) {
        if (annotation == null) {
            return new PermissionValidator();
        }
        return new PermissionValidator(annotation.permission(), annotation.requireOp());
    }
}
