package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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
 * Unit tests for LocationParser.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationParser Tests")
class LocationParserTest {

    @Mock
    private World mockWorld;

    private MockedStatic<Bukkit> bukkitMock;
    private LocationParser parser;

    @BeforeEach
    void setUp() {
        parser = new LocationParser();
        bukkitMock = mockStatic(Bukkit.class);
        lenient().when(mockWorld.getName()).thenReturn("world");
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
        @DisplayName("Should return Location class as primary type")
        void shouldReturnLocationClass() {
            assertEquals(Location.class, parser.getPrimaryType());
        }
        
        @Test
        @DisplayName("Should support Location in supported types")
        void shouldSupportLocation() {
            assertTrue(parser.getSupportedTypes().contains(Location.class));
        }
    }

    @Nested
    @DisplayName("Parse Coordinates Only Tests")
    class CoordinatesOnlyTests {
        
        @Test
        @DisplayName("Should parse x,y,z format with default world")
        void shouldParseXYZ() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            Location result = parser.parse("100,64,-200");
            
            assertNotNull(result);
            assertEquals(100.0, result.getX(), 0.001);
            assertEquals(64.0, result.getY(), 0.001);
            assertEquals(-200.0, result.getZ(), 0.001);
            assertEquals(mockWorld, result.getWorld());
        }
        
        @Test
        @DisplayName("Should parse decimal coordinates")
        void shouldParseDecimalCoordinates() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            Location result = parser.parse("100.5,64.25,-200.75");
            
            assertEquals(100.5, result.getX(), 0.001);
            assertEquals(64.25, result.getY(), 0.001);
            assertEquals(-200.75, result.getZ(), 0.001);
        }
        
        @Test
        @DisplayName("Should parse negative coordinates")
        void shouldParseNegativeCoordinates() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            Location result = parser.parse("-100,-64,-200");
            
            assertEquals(-100.0, result.getX(), 0.001);
            assertEquals(-64.0, result.getY(), 0.001);
            assertEquals(-200.0, result.getZ(), 0.001);
        }
        
        @Test
        @DisplayName("Should throw when no default world available")
        void shouldThrowWhenNoDefaultWorld() {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.emptyList());
            
            TypeParseException ex = assertThrows(TypeParseException.class,
                    () -> parser.parse("100,64,-200"));
            
            assertTrue(ex.getMessage().contains("No default world"));
        }
    }

    @Nested
    @DisplayName("Parse With World Tests")
    class WithWorldTests {
        
        @Test
        @DisplayName("Should parse world,x,y,z format")
        void shouldParseWorldXYZ() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            
            Location result = parser.parse("world,100,64,-200");
            
            assertNotNull(result);
            assertEquals(mockWorld, result.getWorld());
            assertEquals(100.0, result.getX(), 0.001);
            assertEquals(64.0, result.getY(), 0.001);
            assertEquals(-200.0, result.getZ(), 0.001);
        }
        
        @Test
        @DisplayName("Should throw for invalid world name")
        void shouldThrowForInvalidWorld() {
            bukkitMock.when(() -> Bukkit.getWorld("invalid")).thenReturn(null);
            
            TypeParseException ex = assertThrows(TypeParseException.class,
                    () -> parser.parse("invalid,100,64,-200"));
            
            assertTrue(ex.getMessage().contains("World"));
        }
    }

    @Nested
    @DisplayName("Parse With Rotation Tests")
    class WithRotationTests {
        
        @Test
        @DisplayName("Should parse world,x,y,z,yaw,pitch format")
        void shouldParseFullFormat() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            
            Location result = parser.parse("world,100,64,-200,90.0,45.0");
            
            assertNotNull(result);
            assertEquals(mockWorld, result.getWorld());
            assertEquals(100.0, result.getX(), 0.001);
            assertEquals(64.0, result.getY(), 0.001);
            assertEquals(-200.0, result.getZ(), 0.001);
            assertEquals(90.0, result.getYaw(), 0.001);
            assertEquals(45.0, result.getPitch(), 0.001);
        }
        
        @Test
        @DisplayName("Should parse negative rotation values")
        void shouldParseNegativeRotation() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            
            Location result = parser.parse("world,0,0,0,-180.0,-90.0");
            
            assertEquals(-180.0, result.getYaw(), 0.001);
            assertEquals(-90.0, result.getPitch(), 0.001);
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {
        
        @Test
        @DisplayName("Should throw for invalid format")
        void shouldThrowForInvalidFormat() {
            assertThrows(TypeParseException.class, () -> parser.parse("invalid"));
        }
        
        @Test
        @DisplayName("Should throw for non-numeric values")
        void shouldThrowForNonNumericValues() {
            assertThrows(TypeParseException.class, () -> parser.parse("abc,def,ghi"));
        }
        
        @Test
        @DisplayName("Should throw for too few parts")
        void shouldThrowForTooFewParts() {
            assertThrows(TypeParseException.class, () -> parser.parse("100,64"));
        }
        
        @Test
        @DisplayName("Should throw for five parts (invalid)")
        void shouldThrowForFiveParts() {
            assertThrows(TypeParseException.class, () -> parser.parse("world,100,64,-200,90"));
        }
        
        @Test
        @DisplayName("Should throw for too many parts")
        void shouldThrowForTooManyParts() {
            assertThrows(TypeParseException.class, 
                    () -> parser.parse("world,100,64,-200,90,45,extra"));
        }
    }

    @Nested
    @DisplayName("Array Parsing Tests")
    class ArrayParsingTests {
        
        @Test
        @DisplayName("Should parse array of locations")
        void shouldParseArrayOfLocations() throws TypeParseException {
            bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);
            
            String[] values = {"world,100,64,-200", "world,0,0,0"};
            Location[] results = parser.parseArray(values);
            
            assertEquals(2, results.length);
            assertEquals(100.0, results[0].getX(), 0.001);
            assertEquals(0.0, results[1].getX(), 0.001);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Should handle zero coordinates")
        void shouldHandleZeroCoordinates() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            Location result = parser.parse("0,0,0");
            
            assertEquals(0.0, result.getX(), 0.001);
            assertEquals(0.0, result.getY(), 0.001);
            assertEquals(0.0, result.getZ(), 0.001);
        }
        
        @Test
        @DisplayName("Should handle large coordinates")
        void shouldHandleLargeCoordinates() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            Location result = parser.parse("29999999,320,-29999999");
            
            assertEquals(29999999.0, result.getX(), 0.001);
            assertEquals(320.0, result.getY(), 0.001);
            assertEquals(-29999999.0, result.getZ(), 0.001);
        }
        
        @Test
        @DisplayName("Should handle spaces in input")
        void shouldHandleSpaces() throws TypeParseException {
            bukkitMock.when(Bukkit::getWorlds).thenReturn(java.util.Collections.singletonList(mockWorld));
            
            // Parser trims whitespace via parts[i].trim()
            Location result = parser.parse("100 , 64 , -200");
            
            assertEquals(100.0, result.getX(), 0.001);
            assertEquals(64.0, result.getY(), 0.001);
            assertEquals(-200.0, result.getZ(), 0.001);
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
}
