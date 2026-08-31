package com.ultikits.ultitools.abstracts.command.validation;

import com.ultikits.ultitools.abstracts.command.CommandContext;

import javax.annotation.Nullable;

/**
 * Interface for command validation following the Chain of Responsibility pattern.
 * Validators are executed in order and can stop the chain by returning a failed result.
 * <p>
 * 遵循责任链模式的命令验证接口。
 * 验证器按顺序执行，可以通过返回失败结果来停止链。
 *
 * @author wisdomme
 * @version 2.0.0
 * @since 6.2.0
 */
public interface CommandValidator {
    
    /**
     * Validates the command context.
     * 验证命令上下文。
     *
     * @param context the command context to validate
     * @return the validation result
     */
    ValidationResult validate(CommandContext context);
    
    /**
     * Gets the order of this validator. Lower values are executed first.
     * 获取此验证器的顺序。较低的值优先执行。
     *
     * @return the order value
     */
    default int getOrder() {
        return 0;
    }
    
    /**
     * Checks if this validator should be applied to the given context.
     * 检查此验证器是否应应用于给定的上下文。
     *
     * @param context the command context
     * @return true if this validator should be applied
     */
    default boolean shouldValidate(CommandContext context) {
        return true;
    }
    
    /**
     * Gets the name of this validator for debugging and logging purposes.
     * 获取此验证器的名称，用于调试和日志记录。
     *
     * @return the validator name
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Invoked once, after the mapped command method has returned or thrown, for each validator
     * whose {@link #validate(CommandContext)} succeeded during the chain run that gated this
     * invocation. Never invoked for a validator that did not run -- skipped via
     * {@link #shouldValidate(CommandContext)}, or never reached because an earlier validator in
     * the chain failed. Validators are invoked in the same order the chain validated them, i.e.
     * ascending {@link #getOrder()}.
     * <p>
     * "In the chain that ran" and "receives this call" are the same fact by construction: the
     * caller drives this hook from {@code ValidatorChain.ChainValidationResult#getPassedValidators()},
     * never from a hard-coded reference to a specific validator instance.
     * <p>
     * 在映射的命令方法返回或抛出异常后，对本次调用所使用的责任链中每个 {@link #validate(CommandContext)}
     * 成功的验证器调用一次。对于未运行的验证器（被 {@link #shouldValidate(CommandContext)} 跳过，
     * 或因链中更早的验证器失败而从未到达）不会调用。验证器按链验证时的相同顺序（即 {@link #getOrder()}
     * 升序）被调用。
     * <p>
     * The default implementation is a no-op, so existing validators that only gate -- and have no
     * side effect to apply afterward -- require no change.
     *
     * @param context          the command context
     * @param commandSucceeded {@code true} if the mapped method returned without throwing,
     *                         {@code false} if it threw
     * @since 6.3.0
     */
    // The empty body IS the design: it is what keeps this default method source- and
    // binary-compatible with every pre-6.3.0 CommandValidator implementation, per the javadoc
    // above ("existing validators that only gate ... require no change").
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    default void onComplete(CommandContext context, boolean commandSucceeded) {
    }

    /**
     * Result of a validation operation.
     * 验证操作的结果。
     */
    final class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final String errorKey;
        
        private ValidationResult(boolean valid, @Nullable String errorMessage, @Nullable String errorKey) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.errorKey = errorKey;
        }
        
        /**
         * Creates a successful validation result.
         * 创建成功的验证结果。
         *
         * @return a success result
         */
        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }
        
        /**
         * Creates a failed validation result with a message.
         * 创建带有消息的失败验证结果。
         *
         * @param errorMessage the error message
         * @return a failure result
         */
        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage, null);
        }
        
        /**
         * Creates a failed validation result with a message and i18n key.
         * 创建带有消息和 i18n 键的失败验证结果。
         *
         * @param errorMessage the error message
         * @param errorKey     the i18n key for the error
         * @return a failure result
         */
        public static ValidationResult failure(String errorMessage, String errorKey) {
            return new ValidationResult(false, errorMessage, errorKey);
        }
        
        /**
         * Checks if the validation was successful.
         * 检查验证是否成功。
         *
         * @return true if valid
         */
        public boolean isValid() {
            return valid;
        }
        
        /**
         * Gets the error message if validation failed.
         * 获取验证失败时的错误消息。
         *
         * @return the error message, or null if valid
         */
        @Nullable
        public String getErrorMessage() {
            return errorMessage;
        }
        
        /**
         * Gets the i18n key for the error message.
         * 获取错误消息的 i18n 键。
         *
         * @return the error key, or null if not set
         */
        @Nullable
        public String getErrorKey() {
            return errorKey;
        }
    }
}
