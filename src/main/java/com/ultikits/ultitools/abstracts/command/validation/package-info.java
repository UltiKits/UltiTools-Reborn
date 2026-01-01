/**
 * Command validation framework using Chain of Responsibility pattern.
 * <p>
 * This package provides an extensible validation system:
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.CommandValidator} - Validator interface</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} - Chain manager</li>
 * </ul>
 * 
 * <h2>Built-in Validators:</h2>
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator} - Player/Console validation</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator} - Permission checks</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator} - Command cooldowns</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator} - Concurrent execution locks</li>
 * </ul>
 * 
 * <h2>Custom Validator Example:</h2>
 * <pre>{@code
 * public class VIPValidator implements CommandValidator {
 *     @Override
 *     public ValidationResult validate(CommandContext context) {
 *         if (!context.isPlayer()) {
 *             return ValidationResult.success();
 *         }
 *         Player player = context.getPlayer();
 *         if (isVIP(player)) {
 *             return ValidationResult.success();
 *         }
 *         return ValidationResult.failure("This command requires VIP status!");
 *     }
 *     
 *     @Override
 *     public int getOrder() {
 *         return 150; // After sender type, before permission
 *     }
 * }
 * 
 * // Add to command executor
 * myCommand.addValidator(new VIPValidator());
 * }</pre>
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.command.validation;
