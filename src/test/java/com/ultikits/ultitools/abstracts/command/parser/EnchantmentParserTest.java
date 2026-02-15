package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for EnchantmentParser.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnchantmentParser Tests")
class EnchantmentParserTest {

    @Mock
    private Enchantment mockSharpness;

    @Mock
    private Enchantment mockProtection;

    @Mock
    private Enchantment mockFireAspect;

    private EnchantmentParser parser;

    @BeforeEach
    void setUp() {
        parser = new EnchantmentParser();
        lenient().when(mockSharpness.getName()).thenReturn("SHARPNESS");
        lenient().when(mockProtection.getName()).thenReturn("PROTECTION");
        lenient().when(mockFireAspect.getName()).thenReturn("FIRE_ASPECT");
    }

    @Nested
    @DisplayName("Parser Type Tests")
    class ParserTypeTests {
        
        @Test
        @DisplayName("Should return Enchantment class as primary type")
        void shouldReturnEnchantmentClass() {
            assertEquals(Enchantment.class, parser.getPrimaryType());
        }
        
        @Test
        @DisplayName("Should support Enchantment in supported types")
        void shouldSupportEnchantment() {
            assertTrue(parser.getSupportedTypes().contains(Enchantment.class));
        }
    }

    @Nested
    @DisplayName("Parse By Name Tests")
    class ParseByNameTests {
        
        @Test
        @DisplayName("Should parse by exact name using static getByName")
        void shouldParseByExactName() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("SHARPNESS")).thenReturn(mockSharpness);
                
                Enchantment result = parser.parse("SHARPNESS");
                
                assertEquals(mockSharpness, result);
            }
        }
        
        @Test
        @DisplayName("Should parse lowercase name as uppercase")
        void shouldParseLowercaseName() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("SHARPNESS")).thenReturn(mockSharpness);
                
                Enchantment result = parser.parse("sharpness");
                
                assertEquals(mockSharpness, result);
            }
        }
        
        @Test
        @DisplayName("Should parse mixed case name")
        void shouldParseMixedCaseName() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("PROTECTION")).thenReturn(mockProtection);
                
                Enchantment result = parser.parse("Protection");
                
                assertEquals(mockProtection, result);
            }
        }
    }

    @Nested
    @DisplayName("Partial Match Tests")
    class PartialMatchTests {
        
        @Test
        @DisplayName("Should find by partial match when exact match fails")
        void shouldFindByPartialMatch() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                // First, getByName returns null (no exact match)
                enchantMock.when(() -> Enchantment.getByName("FIRE")).thenReturn(null);
                // Then, values() returns all enchantments
                enchantMock.when(Enchantment::values).thenReturn(new Enchantment[]{
                        mockSharpness, mockProtection, mockFireAspect
                });
                
                Enchantment result = parser.parse("FIRE");
                
                assertEquals(mockFireAspect, result);
            }
        }
        
        @Test
        @DisplayName("Should find lowercase partial match")
        void shouldFindLowercasePartialMatch() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("SHARP")).thenReturn(null);
                enchantMock.when(Enchantment::values).thenReturn(new Enchantment[]{
                        mockSharpness, mockProtection
                });
                
                Enchantment result = parser.parse("sharp");
                
                assertEquals(mockSharpness, result);
            }
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Should throw for non-existent enchantment")
        void shouldThrowForNonExistent() {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("INVALID")).thenReturn(null);
                enchantMock.when(Enchantment::values).thenReturn(new Enchantment[]{
                        mockSharpness
                });
                
                TypeParseException ex = assertThrows(TypeParseException.class,
                        () -> parser.parse("INVALID"));
                
                assertTrue(ex.getMessage().contains("not found"));
            }
        }
        
        @Test
        @DisplayName("Should throw for empty string")
        void shouldThrowForEmptyString() {
            assertThrows(TypeParseException.class, () -> parser.parse(""));
        }
        
        @Test
        @DisplayName("Should throw for null")
        void shouldThrowForNull() {
            assertThrows(TypeParseException.class, () -> parser.parse(null));
        }
    }

    @Nested
    @DisplayName("Array Parsing Tests")
    class ArrayParsingTests {
        
        @Test
        @DisplayName("Should parse array of enchantments")
        void shouldParseArray() throws TypeParseException {
            try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
                enchantMock.when(() -> Enchantment.getByName("SHARPNESS")).thenReturn(mockSharpness);
                enchantMock.when(() -> Enchantment.getByName("PROTECTION")).thenReturn(mockProtection);
                
                String[] values = {"SHARPNESS", "PROTECTION"};
                Enchantment[] results = parser.parseArray(values);
                
                assertEquals(2, results.length);
                assertEquals(mockSharpness, results[0]);
                assertEquals(mockProtection, results[1]);
            }
        }
    }
}
