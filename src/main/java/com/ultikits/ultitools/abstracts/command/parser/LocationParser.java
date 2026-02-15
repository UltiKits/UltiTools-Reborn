package com.ultikits.ultitools.abstracts.command.parser;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Arrays;
import java.util.List;

/**
 * Type parser for Bukkit Location.
 * Supports formats:
 * <ul>
 *   <li>world,x,y,z</li>
 *   <li>world,x,y,z,yaw,pitch</li>
 *   <li>x,y,z (uses sender's world if available)</li>
 * </ul>
 * <p>
 * Bukkit Location 类型解析器。
 * 支持格式：
 * <ul>
 *   <li>世界名,x,y,z</li>
 *   <li>世界名,x,y,z,yaw,pitch</li>
 *   <li>x,y,z（使用发送者的世界）</li>
 * </ul>
 *
 * @since 6.2.0
 */
public class LocationParser implements TypeParser<Location> {

    @Override
    public Class<Location> getPrimaryType() {
        return Location.class;
    }

    @Override
    public List<Class<?>> getSupportedTypes() {
        return Arrays.asList(Location.class);
    }

    @Override
    public Location parse(String value) throws TypeParseException {
        if (value == null || value.isEmpty()) {
            throw new TypeParseException("Location cannot be empty");
        }
        
        String[] parts = value.split(",");
        
        try {
            if (parts.length == 3) {
                // x,y,z format - use default world
                World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                if (defaultWorld == null) {
                    throw new TypeParseException("No default world available for location: " + value);
                }
                return new Location(
                    defaultWorld,
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim())
                );
            } else if (parts.length == 4) {
                // world,x,y,z format
                World world = Bukkit.getWorld(parts[0].trim());
                if (world == null) {
                    throw new TypeParseException("World not found: " + parts[0]);
                }
                return new Location(
                    world,
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim())
                );
            } else if (parts.length == 6) {
                // world,x,y,z,yaw,pitch format
                World world = Bukkit.getWorld(parts[0].trim());
                if (world == null) {
                    throw new TypeParseException("World not found: " + parts[0]);
                }
                return new Location(
                    world,
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    Float.parseFloat(parts[4].trim()),
                    Float.parseFloat(parts[5].trim())
                );
            } else {
                throw new TypeParseException(
                    "Invalid location format: " + value + 
                    ". Expected: x,y,z or world,x,y,z or world,x,y,z,yaw,pitch"
                );
            }
        } catch (NumberFormatException e) {
            throw new TypeParseException("Invalid number in location: " + value, e);
        }
    }

    @Override
    public Location[] parseArray(String[] values) throws TypeParseException {
        Location[] result = new Location[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = parse(values[i]);
        }
        return result;
    }
}
