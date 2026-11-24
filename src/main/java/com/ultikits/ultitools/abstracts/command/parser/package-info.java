/**
 * Module-defined command argument parsers.
 * A module registers a {@link com.ultikits.ultitools.abstracts.command.parser.TypeParser}
 * during initialization for a Java type not covered by the command engine's built-in mapping.
 * As of 7.0.0, built-in types use Brigadier and Paper argument types, not registry entries;
 * in particular, Location uses native coordinates rather than the 6.x comma-delimited syntax.
 * Parser rejections use {@link com.ultikits.ultitools.abstracts.command.parser.TypeParseException}.
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.command.parser;
