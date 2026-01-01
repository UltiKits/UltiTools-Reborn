package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for TypeParserRegistry.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TypeParserRegistry Tests")
class TypeParserRegistryTest {

    private TypeParserRegistry registry;

    @BeforeEach
    void setUp() {
        registry = TypeParserRegistry.getInstance();
    }

    @Test
    @DisplayName("Singleton instance should be consistent")
    void singletonShouldBeConsistent() {
        TypeParserRegistry instance1 = TypeParserRegistry.getInstance();
        TypeParserRegistry instance2 = TypeParserRegistry.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    @DisplayName("Should parse String correctly")
    void shouldParseString() throws TypeParseException {
        String result = registry.parse("hello", String.class);
        assertEquals("hello", result);
    }

    @Test
    @DisplayName("Should parse Integer correctly")
    void shouldParseInteger() throws TypeParseException {
        Integer result = registry.parse("42", Integer.class);
        assertEquals(42, result);
    }

    @Test
    @DisplayName("Should throw exception for invalid Integer")
    void shouldThrowForInvalidInteger() {
        assertThrows(TypeParseException.class, () -> 
            registry.parse("not-a-number", Integer.class)
        );
    }

    @Test
    @DisplayName("Should parse Double correctly")
    void shouldParseDouble() throws TypeParseException {
        Double result = registry.parse("3.14", Double.class);
        assertEquals(3.14, result, 0.001);
    }

    @Test
    @DisplayName("Should parse Boolean correctly")
    void shouldParseBoolean() throws TypeParseException {
        assertTrue(registry.parse("true", Boolean.class));
        assertFalse(registry.parse("false", Boolean.class));
    }

    @Test
    @DisplayName("Should parse Long correctly")
    void shouldParseLong() throws TypeParseException {
        Long result = registry.parse("9999999999", Long.class);
        assertEquals(9999999999L, result);
    }

    @Test
    @DisplayName("Should parse Float correctly")
    void shouldParseFloat() throws TypeParseException {
        Float result = registry.parse("1.5", Float.class);
        assertEquals(1.5f, result, 0.001);
    }

    @Test
    @DisplayName("Should check if parser exists")
    void shouldCheckParserExists() {
        assertTrue(registry.hasParser(String.class));
        assertTrue(registry.hasParser(Integer.class));
        assertTrue(registry.hasParser(Boolean.class));
    }

    @Test
    @DisplayName("Should get all registered parsers")
    void shouldGetAllParsers() {
        List<TypeParser<?>> parsers = registry.getAllParsers();
        assertNotNull(parsers);
        assertFalse(parsers.isEmpty());
    }

    @Test
    @DisplayName("Should parse array values")
    void shouldParseArrayValues() throws TypeParseException {
        String[] values = {"1", "2", "3"};
        Integer[] result = registry.parseArray(values, Integer.class);
        
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals(2, result[1]);
        assertEquals(3, result[2]);
    }
}
