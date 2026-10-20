package com.ultikits.ultitools.abstracts.command.parser;

import java.util.Arrays;
import java.util.List;

import org.bukkit.GameMode;

/**
 * Type parser for Bukkit GameMode.
 * Supports parsing by name (SURVIVAL, CREATIVE, etc.) or number (0, 1, 2, 3).
 *
 * @since 6.2.0
 */
public class GameModeParser implements TypeParser<GameMode> {

    @Override
    public Class<GameMode> getPrimaryType() {
        return GameMode.class;
    }

    @Override
    public List<Class<?>> getSupportedTypes() {
        return Arrays.asList(GameMode.class);
    }

    @Override
    public GameMode parse(String value) throws TypeParseException {
        if (value == null || value.isEmpty()) {
            throw new TypeParseException("GameMode cannot be empty");
        }
        
        // Try parsing as number first
        try {
            int modeNum = Integer.parseInt(value);
            switch (modeNum) {
                case 0: return GameMode.SURVIVAL;
                case 1: return GameMode.CREATIVE;
                case 2: return GameMode.ADVENTURE;
                case 3: return GameMode.SPECTATOR;
                default:
                    throw new TypeParseException("Invalid GameMode number: " + modeNum + ". Valid: 0-3");
            }
        } catch (NumberFormatException ignored) {
            // Not a number, try as name
        }
        
        // Try parsing as name
        try {
            return GameMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TypeParseException(
                "Invalid GameMode: " + value + ". Valid: SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR or 0-3"
            );
        }
    }

    @Override
    public GameMode[] parseArray(String[] values) throws TypeParseException {
        GameMode[] result = new GameMode[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = parse(values[i]);
        }
        return result;
    }
}
