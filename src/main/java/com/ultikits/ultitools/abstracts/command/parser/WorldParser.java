package com.ultikits.ultitools.abstracts.command.parser;

import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Type parser for Bukkit World.
 * Supports parsing by world name.
 * <p>
 * Bukkit World 类型解析器。
 * 支持通过世界名称解析。
 *
 * @since 6.2.0
 */
public class WorldParser implements TypeParser<World> {

    @Override
    public Class<World> getPrimaryType() {
        return World.class;
    }

    @Override
    public List<Class<?>> getSupportedTypes() {
        return Arrays.asList(World.class);
    }

    @Override
    public World parse(String value) throws TypeParseException {
        if (value == null || value.isEmpty()) {
            throw new TypeParseException("World name cannot be empty");
        }
        
        World world = Bukkit.getWorld(value);
        if (world == null) {
            throw new TypeParseException("World not found: " + value);
        }
        return world;
    }

    @Override
    public World[] parseArray(String[] values) throws TypeParseException {
        World[] result = new World[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = parse(values[i]);
        }
        return result;
    }
}
