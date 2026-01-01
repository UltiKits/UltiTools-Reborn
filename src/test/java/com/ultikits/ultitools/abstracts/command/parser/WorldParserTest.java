package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for WorldParser.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorldParser Tests")
class WorldParserTest {

    @Mock
    private World mockWorld;
    
    @Mock
    private World mockNether;
    
    @Mock
    private World mockEnd;

    private MockedStatic<Bukkit> bukkitMock;
    private WorldParser parser;

    @BeforeEach
    void setUp() {
        parser = new WorldParser();
        bukkitMock = mockStatic(Bukkit.class);
        
        lenient().when(mockWorld.getName()).thenReturn("world");
        lenient().when(mockNether.getName()).thenReturn("world_nether");
        lenient().when(mockEnd.getName()).thenReturn("world_the_end");
    }

    @AfterEach
    void tearDown() {
        if (bukkitMock != null) {
            bukkitMock.close();
        }
    }

    @Nested
    @DisplayName("Parser Type Tests")
    class ParserTypeTests {
        
        @Test
        @DisplayName("Should return World class as primary type")
        void shouldReturnWorldClass() {
            assertEquals(World.class, parser.getPrimaryType());
        }
        
        @Test
        @DisplayName("Should support World in supported types")
        void shouldSupportWorld() {
            assertTrue(parser.getSupportedTypes().contains(World.class));
        }
    }

    @Nested
    @DisplayName("Parse Tests")
    class ParseTests {
        
        @Test
        @DisplayName("Should parse valid world name")
        void shouldParseValidWorldName() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            
            World result = parser.parse("world");
            
            assertNotNull(result);
            assertEquals(mockWorld, result);
        }
        
        @Test
        @DisplayName("Should throw exception for invalid world")
        void shouldThrowForInvalidWorld() {
            bukkitMock.when(() -> Bukkit.getWorld("invalid")).thenReturn(null);
            
            TypeParseException ex = assertThrows(TypeParseException.class, 
                    () -> parser.parse("invalid"));
            
            assertTrue(ex.getMessage().contains("invalid"));
        }
        
        @Test
        @DisplayName("Should parse nether world")
        void shouldParseNetherWorld() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mockNether);
            
            World result = parser.parse("world_nether");
            
            assertEquals(mockNether, result);
        }
        
        @Test
        @DisplayName("Should parse end world")
        void shouldParseEndWorld() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(mockEnd);
            
            World result = parser.parse("world_the_end");
            
            assertEquals(mockEnd, result);
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
        @DisplayName("Should parse array of worlds")
        void shouldParseArray() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            bukkitMock.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mockNether);
            
            String[] values = {"world", "world_nether"};
            World[] results = parser.parseArray(values);
            
            assertEquals(2, results.length);
            assertEquals(mockWorld, results[0]);
            assertEquals(mockNether, results[1]);
        }
        
        @Test
        @DisplayName("Should throw for array with invalid world")
        void shouldThrowForArrayWithInvalidWorld() {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            bukkitMock.when(() -> Bukkit.getWorld("invalid")).thenReturn(null);
            
            String[] values = {"world", "invalid"};
            
            assertThrows(TypeParseException.class, () -> parser.parseArray(values));
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Should handle world names with special characters")
        void shouldHandleSpecialCharacters() throws TypeParseException {
            World specialWorld = mock(World.class);
            bukkitMock.when(() -> Bukkit.getWorld("my-custom-world")).thenReturn(specialWorld);
            
            World result = parser.parse("my-custom-world");
            
            assertEquals(specialWorld, result);
        }
        
        @Test
        @DisplayName("Should handle world names with underscores")
        void shouldHandleUnderscores() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mockNether);
            
            World result = parser.parse("world_nether");
            
            assertEquals(mockNether, result);
        }
        
        @Test
        @DisplayName("Should handle world names with numbers")
        void shouldHandleNumbersInName() throws TypeParseException {
            World numberedWorld = mock(World.class);
            bukkitMock.when(() -> Bukkit.getWorld("world123")).thenReturn(numberedWorld);
            
            World result = parser.parse("world123");
            
            assertEquals(numberedWorld, result);
        }
    }
}
