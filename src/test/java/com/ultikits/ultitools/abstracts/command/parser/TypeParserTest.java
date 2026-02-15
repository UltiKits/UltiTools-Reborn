package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TypeParser Interface Tests")
class TypeParserTest {

    // Test implementation
    private static class TestParser implements TypeParser<Integer> {
        @Override
        public Class<Integer> getPrimaryType() {
            return Integer.class;
        }

        @Override
        public List<Class<?>> getSupportedTypes() {
            return Arrays.asList(Integer.class, int.class);
        }

        @Override
        public Integer parse(String value) throws TypeParseException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new TypeParseException(value, Integer.class, "Invalid integer", e);
            }
        }
    }

    private final TypeParser<Integer> parser = new TestParser();

    @Test
    @DisplayName("Should parse array correctly")
    void shouldParseArray() {
        String[] input = {"1", "2", "3"};
        Integer[] result = parser.parseArray(input);
        
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(1, result[0]);
        assertEquals(2, result[1]);
        assertEquals(3, result[2]);
    }

    @Test
    @DisplayName("Should handle empty array")
    void shouldHandleEmptyArray() {
        String[] input = {};
        Integer[] result = parser.parseArray(input);
        
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should handle null array")
    void shouldHandleNullArray() {
        Integer[] result = parser.parseArray(null);
        
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should throw exception on array parse failure")
    void shouldThrowOnArrayParseFailure() {
        String[] input = {"1", "invalid", "3"};
        
        assertThrows(TypeParseException.class, () -> parser.parseArray(input));
    }

    @Test
    @DisplayName("Should check supported types")
    void shouldCheckSupportedTypes() {
        assertTrue(parser.supports(Integer.class));
        assertTrue(parser.supports(int.class));
        assertFalse(parser.supports(String.class));
    }

    @Test
    @DisplayName("Should have default priority 0")
    void shouldHaveDefaultPriority() {
        assertEquals(0, parser.getPriority());
    }
    
    @Test
    @DisplayName("Should allow overriding priority")
    void shouldAllowOverridingPriority() {
        TypeParser<String> priorityParser = new TypeParser<String>() {
            @Override
            public Class<String> getPrimaryType() { return String.class; }
            
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(String.class); }
            
            @Override
            public String parse(String value) { return value; }
            
            @Override
            public int getPriority() { return 10; }
        };
        
        assertEquals(10, priorityParser.getPriority());
    }

    @Test
    @DisplayName("Should support subtypes")
    void shouldSupportSubtypes() {
        TypeParser<Number> numberParser = new TypeParser<Number>() {
            @Override
            public Class<Number> getPrimaryType() { return Number.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(Number.class); }
            @Override
            public Number parse(String value) { return null; }
        };
        
        assertTrue(numberParser.supports(Integer.class));
        assertTrue(numberParser.supports(Double.class));
        assertTrue(numberParser.supports(Number.class));
        assertFalse(numberParser.supports(String.class));
    }
}
