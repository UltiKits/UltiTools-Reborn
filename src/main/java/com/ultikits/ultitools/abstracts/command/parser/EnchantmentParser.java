package com.ultikits.ultitools.abstracts.command.parser;

import org.bukkit.enchantments.Enchantment;

import java.util.Arrays;
import java.util.List;

/**
 * Type parser for Bukkit Enchantment.
 * Supports parsing by name (e.g., "SHARPNESS", "PROTECTION").
 *
 * @since 6.2.0
 */
public class EnchantmentParser implements TypeParser<Enchantment> {

    @Override
    public Class<Enchantment> getPrimaryType() {
        return Enchantment.class;
    }

    @Override
    public List<Class<?>> getSupportedTypes() {
        return Arrays.asList(Enchantment.class);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Enchantment parse(String value) throws TypeParseException {
        if (value == null || value.isEmpty()) {
            throw new TypeParseException("Enchantment name cannot be empty");
        }
        
        // Try by name (works in most versions)
        Enchantment enchantment = Enchantment.getByName(value.toUpperCase());
        if (enchantment != null) {
            return enchantment;
        }
        
        // Try to find by partial match
        String upperValue = value.toUpperCase();
        for (Enchantment enc : Enchantment.values()) {
            if (enc.getName() != null && enc.getName().toUpperCase().contains(upperValue)) {
                return enc;
            }
        }
        
        throw new TypeParseException("Enchantment not found: " + value);
    }

    @Override
    public Enchantment[] parseArray(String[] values) throws TypeParseException {
        Enchantment[] result = new Enchantment[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = parse(values[i]);
        }
        return result;
    }
}
