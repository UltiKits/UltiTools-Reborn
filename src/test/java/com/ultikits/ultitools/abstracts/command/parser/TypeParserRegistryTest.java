package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
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
    @DisplayName("Should parse Short correctly")
    void shouldParseShort() throws TypeParseException {
        Short result = registry.parse("123", Short.class);
        assertEquals((short) 123, result);
    }
    
    @Test
    @DisplayName("Should parse Byte correctly")
    void shouldParseByte() throws TypeParseException {
        Byte result = registry.parse("12", Byte.class);
        assertEquals((byte) 12, result);
    }

    @Test
    @DisplayName("Should check if parser exists")
    void shouldCheckParserExists() {
        assertTrue(registry.hasParser(String.class));
        assertTrue(registry.hasParser(Integer.class));
        assertTrue(registry.hasParser(Boolean.class));
        assertFalse(registry.hasParser(Object.class)); // Assuming no generic Object parser
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
    
    @Test
    @DisplayName("Should throw exception when parsing array with missing parser")
    void shouldThrowOnMissingParserForArray() {
        String[] values = {"test"};
        assertThrows(TypeParseException.class, () -> 
            registry.parseArray(values, Object.class)
        );
    }
    
    @Test
    @DisplayName("Should throw exception when parsing with missing parser")
    void shouldThrowOnMissingParser() {
        assertThrows(TypeParseException.class, () -> 
            registry.parse("test", Object.class)
        );
    }

    @Test
    @DisplayName("Should register and unregister custom parser")
    void shouldRegisterAndUnregisterCustomParser() {
        class CustomType {}
        TypeParser<CustomType> customParser = new TypeParser<CustomType>() {
            @Override
            public Class<CustomType> getPrimaryType() { return CustomType.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(CustomType.class); }
            @Override
            public CustomType parse(String value) { return new CustomType(); }
        };

        registry.register(customParser);
        assertTrue(registry.hasParser(CustomType.class));
        
        registry.unregister(customParser);
        assertFalse(registry.hasParser(CustomType.class));
    }
    
    @Test
    @DisplayName("Should respect parser priority")
    void shouldRespectParserPriority() throws TypeParseException {
        class PriorityType {}
        
        TypeParser<PriorityType> lowPriority = new TypeParser<PriorityType>() {
            @Override
            public Class<PriorityType> getPrimaryType() { return PriorityType.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(PriorityType.class); }
            @Override
            public PriorityType parse(String value) { return null; } // Should not be called
            @Override
            public int getPriority() { return 0; }
        };
        
        TypeParser<PriorityType> highPriority = new TypeParser<PriorityType>() {
            @Override
            public Class<PriorityType> getPrimaryType() { return PriorityType.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(PriorityType.class); }
            @Override
            public PriorityType parse(String value) { return new PriorityType(); }
            @Override
            public int getPriority() { return 10; }
        };
        
        registry.register(lowPriority);
        registry.register(highPriority);
        
        // Should use high priority parser
        PriorityType result = registry.parse("test", PriorityType.class);
        assertNotNull(result);
        
        registry.unregister(lowPriority);
        registry.unregister(highPriority);
    }
    
    @Test
    @DisplayName("Should find parser by supported type")
    void shouldFindParserBySupportedType() {
        // Integer parser supports int.class
        assertTrue(registry.hasParser(int.class));
        assertNotNull(registry.getParser(int.class));
    }

    @Test
    @DisplayName("Should throw NPE when registering null parser")
    void shouldThrowNPEOnRegisterNull() {
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    @DisplayName("Should handle unregistering unknown parser gracefully")
    void shouldHandleUnregisterUnknown() {
        TypeParser<Object> unknownParser = new TypeParser<Object>() {
            @Override
            public Class<Object> getPrimaryType() { return Object.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.emptyList(); }
            @Override
            public Object parse(String value) { return null; }
        };
        assertDoesNotThrow(() -> registry.unregister(unknownParser));
    }

    @Test
    @DisplayName("Should parse UUID correctly")
    void shouldParseUUID() throws TypeParseException {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        java.util.UUID result = registry.parse(uuid.toString(), java.util.UUID.class);
        assertEquals(uuid, result);
    }

    @Test
    @DisplayName("Should wrap exceptions in TypeParseException for built-in parsers")
    void shouldWrapExceptionsForBuiltInParsers() {
        TypeParseException exception = assertThrows(TypeParseException.class, () -> 
            registry.parse("invalid-int", Integer.class)
        );
        assertTrue(exception.getCause() instanceof NumberFormatException);
    }

    @Test
    @DisplayName("Should find parser by inheritance")
    void shouldFindParserByInheritance() {
        class Parent {}
        class Child extends Parent {}
        
        TypeParser<Parent> parentParser = new TypeParser<Parent>() {
            @Override
            public Class<Parent> getPrimaryType() { return Parent.class; }
            @Override
            public List<Class<?>> getSupportedTypes() { return Collections.singletonList(Parent.class); }
            @Override
            public Parent parse(String value) { return new Parent(); }
        };
        
        registry.register(parentParser);
        
        assertTrue(registry.hasParser(Child.class));
        assertEquals(parentParser, registry.getParser(Child.class));
        
        registry.unregister(parentParser);
    }
}
