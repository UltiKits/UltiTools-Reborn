/**
 * Built-in command validators.
 * <p>
 * Available validators:
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.SenderTypeValidator} 
 *       - Validates sender is Player or Console (order: 100)</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.PermissionValidator} 
 *       - Validates permissions and OP status (order: 200)</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.UsageLockValidator} 
 *       - Prevents concurrent command execution (order: 250)</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.validators.CooldownValidator} 
 *       - Manages command cooldowns (order: 300)</li>
 * </ul>
 * 
 * <h2>Validator Order:</h2>
 * <p>Validators are executed in order from lowest to highest. Default orders:</p>
 * <ol>
 *   <li>SenderTypeValidator (100) - Fail fast if wrong sender type</li>
 *   <li>PermissionValidator (200) - Check permissions before processing</li>
 *   <li>UsageLockValidator (250) - Prevent duplicate execution</li>
 *   <li>CooldownValidator (300) - Rate limiting</li>
 * </ol>
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.command.validation.validators;
