/**
 * Type parsers for command argument conversion.
 * <p>
 * This package provides a flexible type parsing system:
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.parser.TypeParser} - Parser interface</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.parser.TypeParserRegistry} - Registry for parsers</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.command.parser.TypeParseException} - Parsing exceptions</li>
 * </ul>
 * 
 * <h2>Built-in Parsers:</h2>
 * <ul>
 *   <li>Primitives: Boolean, Integer, Double, Float, Long, Short, Byte</li>
 *   <li>Bukkit types: Player, OfflinePlayer, Material, UUID</li>
 *   <li>String (default)</li>
 * </ul>
 * 
 * <h2>Custom Parser Example:</h2>
 * <pre>{@code
 * public class LocationParser implements TypeParser<Location> {
 *     @Override
 *     public Class<Location> getPrimaryType() {
 *         return Location.class;
 *     }
 *     
 *     @Override
 *     public List<Class<?>> getSupportedTypes() {
 *         return Arrays.asList(Location.class);
 *     }
 *     
 *     @Override
 *     public Location parse(String value) {
 *         // Parse "world,x,y,z" format
 *         String[] parts = value.split(",");
 *         World world = Bukkit.getWorld(parts[0]);
 *         return new Location(world, 
 *             Double.parseDouble(parts[1]),
 *             Double.parseDouble(parts[2]),
 *             Double.parseDouble(parts[3]));
 *     }
 * }
 * 
 * // Register custom parser
 * TypeParserRegistry.getInstance().register(new LocationParser());
 * }</pre>
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.command.parser;
