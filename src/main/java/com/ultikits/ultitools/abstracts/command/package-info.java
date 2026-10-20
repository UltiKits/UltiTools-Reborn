/**
 * New command handling framework with improved architecture.
 * <p>
 * This package provides a refactored command system using:
 * <ul>
 *   <li><b>Chain of Responsibility Pattern</b> - for command validation pipeline</li>
 *   <li><b>Strategy Pattern</b> - for type parsing</li>
 *   <li><b>Context Object Pattern</b> - for command execution context</li>
 * </ul>
 * 
 * <h2>Key Components:</h2>
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.CommandContext} - Immutable command execution context</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.BaseCommandExecutor} - New base command executor</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.parser.TypeParserRegistry} - Type parser registry</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.validation.ValidatorChain} - Validation pipeline</li>
 * </ul>
 * 
 * <h2>Migration from the removed AbstractCommandExecutor:</h2>
 * <p>
 * {@code AbstractCommandExecutor} (and its misspelled shim, {@code AbstractCommendExecutor}) was
 * removed in 6.3.0 after its 6.2.1 deprecation window closed. A module still compiled against it
 * needs the same one-line base-class swap shown below; see {@code COMPATIBILITY.md}'s "Migrating
 * off AbstractCommandExecutor" section for the full guide.
 * <pre>{@code
 * // Old way (removed in 6.3.0)
 * public class MyCommand extends AbstractCommandExecutor {
 *     @Override
 *     protected void handleHelp(CommandSender sender) { }
 * }
 *
 * // New way
 * public class MyCommand extends BaseCommandExecutor {
 *     @Override
 *     protected void handleHelp(CommandSender sender) { }
 * }
 * }</pre>
 *
 * <h2>Benefits of new architecture:</h2>
 * <ul>
 *   <li>Better separation of concerns</li>
 *   <li>Extensible validation pipeline</li>
 *   <li>Type-safe parameter parsing</li>
 *   <li>Easier unit testing</li>
 *   <li>Reduced code duplication</li>
 * </ul>
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.command;
