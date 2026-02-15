package com.ultikits.ultitools.interfaces.impl.pasers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.Test;

class StringHashMapParserTest {

    @Test
    void testParse() {
        StringHashMapParser parser = new StringHashMapParser();
        ConfigurationSection section = mock(ConfigurationSection.class);
        
        Set<String> keys = new HashSet<>(Arrays.asList("k1", "k2"));
        when(section.getKeys(false)).thenReturn(keys);
        when(section.getString("k1")).thenReturn("v1");
        when(section.getString("k2")).thenReturn("v2");
        
        HashMap<String, String> result = parser.parse(section);
        
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("v1", result.get("k1"));
        assertEquals("v2", result.get("k2"));
    }

    @Test
    void testParseInvalidInput() {
        StringHashMapParser parser = new StringHashMapParser();
        assertNull(parser.parse("not a section"));
    }

    @Test
    void testSerializeToMemorySection() {
        StringHashMapParser parser = new StringHashMapParser();
        HashMap<String, String> map = new HashMap<>();
        map.put("k1", "v1");
        map.put("k2", "v2");
        
        MemorySection result = parser.serializeToMemorySection(map);
        
        assertEquals("v1", result.get("k1"));
        assertEquals("v2", result.get("k2"));
    }
}
