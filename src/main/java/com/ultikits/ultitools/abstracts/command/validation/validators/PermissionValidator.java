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
 * <b>Which senders actually reach the class-level check (#383).</b> {@code CommandManager
 * .registerCommandDirect} calls {@code command.setPermission(permission)} with the value from
 * {@code @CmdExecutor}, so Paper filters the command out of the command tree of any <em>player</em>
 * lacking it and answers with a parse error before {@code onCommand} is invoked at all. Measured
 * across 22 permission-gated commands on a real 1.21.4 server: a deop'd player received
 * {@code Unknown or incomplete command} every time and never this validator's message, while an
 * op'd player received real output for the same commands -- the control proving they were
 * registered and working.
 * <p>
 * The class-level branch is therefore reached by:
 * <ul>
 *   <li>the <b>console</b>, which always satisfies the Bukkit-level check, and</li>
 *   <li>nothing else, for a class-level permission.</li>
 * </ul>
 * <b>Method-level</b> permissions declared on {@code @CmdMapping} are not registered with Bukkit,
 * so those branches run for players normally.
 * <p>
 * This is deliberate and is not a defect to be fixed by deleting the branch or by dropping the
 * Bukkit registration. Hiding a command a player cannot use is standard Minecraft behaviour and
 * avoids disclosing that the command exists; the branch still governs the console. What was
 * wrong before 6.3.0 was only that nothing said so, so a reader expected players to see the
 * message below. Removing {@code setPermission} to make them see it would make every
 * permission-gated command visible in every player's tab completion -- a product decision, not a
 * bug fix, and one for the maintainer rather than this class.
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
     */
    public PermissionValidator() {
        this.basePermission = null;
        this.requireOp = false;
    }
    
    /**
     * Creates a permission validator with the specified permission.
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
