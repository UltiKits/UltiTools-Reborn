package com.ultikits.ultitools.interfaces.impl.pasers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.junit.jupiter.api.Test;

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
    @SuppressWarnings("unchecked")
    void testParseConfigurationSection() {
        DefaultConfigParser parser = new DefaultConfigParser();
        ConfigurationSection section = mock(ConfigurationSection.class);
        
        Set<String> keys = new HashSet<>(Arrays.asList("key1", "key2"));
        when(section.getKeys(false)).thenReturn(keys);
        when(section.get("key1")).thenReturn("value1");
        when(section.get("key2")).thenReturn(100);
        
        Object result = parser.parse(section);
        
        assertTrue(result instanceof Map);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("value1", map.get("key1"));
        assertEquals(100, map.get("key2"));
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

    /**
     * Reproduces the real UAT failure (Phase 06 issue): {@code UltiTools.onDisable()} ->
     * {@code ConfigManager.saveAll()} -> {@code AbstractConfigEntity.save()} calls exactly
     * {@code ReflectionUtil.newInstance(annotation.parser()).serialize(fieldValue)} for every
     * {@code @ConfigEntry} field - which for the default parser is {@link
     * DefaultConfigParser#serialize} (inherited, final, from {@link ConfigParser}). A
     * {@code Map}-typed field value is not a basic type, {@code String}, or {@code List}, so it
     * reaches {@link DefaultConfigParser#serializeToMemorySection(Object)}. Before the fix, that
     * method reflected on the {@code LinkedHashMap} instance's own implementation fields (table/
     * head/tail/modCount/serialVersionUID) instead of walking its entries, and on this JDK 21
     * toolchain {@code field.setAccessible(true)} on a private {@code java.util} field throws
     * {@link java.lang.reflect.InaccessibleObjectException} because {@code java.base} does not
     * open {@code java.util} to an unnamed module.
     * <p>
     * Also proves the round trip stays lossless: what {@code serialize} writes, {@code parse}
     * must read back as an equal map - not just "no exception".
     */
    @Test
    void testSerializeMapWalksEntriesAndRoundTripsThroughParse() {
        DefaultConfigParser parser = new DefaultConfigParser();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("alpha", "one");
        input.put("beta", "two");

        // Same public entry point AbstractConfigEntity.save() calls: ConfigParser#serialize.
        Object serialized = parser.serialize(input);

        assertTrue(serialized instanceof MemorySection);
        MemorySection section = (MemorySection) serialized;

        // The entries themselves must be what got serialized - never LinkedHashMap's own
        // reflective bookkeeping fields (table/size/modCount/serialVersionUID/...).
        assertEquals(new HashSet<>(Arrays.asList("alpha", "beta")), section.getKeys(false));
        assertEquals("one", section.getString("alpha"));
        assertEquals("two", section.getString("beta"));

        // Round trip: parse(serialize(map)) must equal the original map.
        Object roundTripped = parser.parse(section);
        assertTrue(roundTripped instanceof Map);
        assertEquals(input, roundTripped);
    }
}
