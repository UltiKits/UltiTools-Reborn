package com.ultikits.ultitools.interfaces.impl.pasers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSONObject;

class DefaultConfigParserTest {

    @Test
    void testParseList() {
        DefaultConfigParser parser = new DefaultConfigParser();
        List<Object> input = Arrays.asList("a", 1, true);
        
        Object result = parser.parse(input);
        
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals("1", list.get(1)); // Converted to string
        assertEquals("true", list.get(2)); // Converted to string
    }

    @Test
    void testParseBasicType() {
        DefaultConfigParser parser = new DefaultConfigParser();
        
        assertEquals("test", parser.parse("test"));
        assertEquals(123, parser.parse(123));
        assertEquals(true, parser.parse(true));
    }

    @Test
    void testParseConfigurationSection() {
        DefaultConfigParser parser = new DefaultConfigParser();
        ConfigurationSection section = mock(ConfigurationSection.class);
        
        Set<String> keys = new HashSet<>(Arrays.asList("key1", "key2"));
        when(section.getKeys(false)).thenReturn(keys);
        when(section.get("key1")).thenReturn("value1");
        when(section.get("key2")).thenReturn(100);
        
        Object result = parser.parse(section);
        
        assertTrue(result instanceof JSONObject);
        JSONObject json = (JSONObject) result;
        assertEquals("value1", json.get("key1"));
        assertEquals(100, json.get("key2"));
    }

    @Test
    void testSerializeToMemorySection() {
        DefaultConfigParser parser = new DefaultConfigParser();
        TestObject obj = new TestObject("test", 123);
        
        MemorySection result = parser.serializeToMemorySection(obj);
        
        assertEquals("test", result.get("name"));
        assertEquals(123, result.get("value"));
    }

    static class TestObject {
        private String name;
        private int value;

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }
}
