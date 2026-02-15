package com.ultikits.ultitools.abstracts.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.GameMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for GameModeParser.
 */
@DisplayName("GameModeParser Tests")
class GameModeParserTest {

    private GameModeParser parser;

    @BeforeEach
    void setUp() {
        parser = new GameModeParser();
    }

    @Test
    @DisplayName("Should return GameMode as primary type")
    void shouldReturnGameModeAsPrimaryType() {
        assertEquals(GameMode.class, parser.getPrimaryType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SURVIVAL", "survival", "Survival"})
    @DisplayName("Should parse SURVIVAL by name (case insensitive)")
    void shouldParseSurvivalByName(String input) throws TypeParseException {
        assertEquals(GameMode.SURVIVAL, parser.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATIVE", "creative", "Creative"})
    @DisplayName("Should parse CREATIVE by name (case insensitive)")
    void shouldParseCreativeByName(String input) throws TypeParseException {
        assertEquals(GameMode.CREATIVE, parser.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADVENTURE", "adventure", "Adventure"})
    @DisplayName("Should parse ADVENTURE by name (case insensitive)")
    void shouldParseAdventureByName(String input) throws TypeParseException {
        assertEquals(GameMode.ADVENTURE, parser.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPECTATOR", "spectator", "Spectator"})
    @DisplayName("Should parse SPECTATOR by name (case insensitive)")
    void shouldParseSpectatorByName(String input) throws TypeParseException {
        assertEquals(GameMode.SPECTATOR, parser.parse(input));
    }

    @Test
    @DisplayName("Should parse by number 0 = SURVIVAL")
    void shouldParseSurvivalByNumber() throws TypeParseException {
        assertEquals(GameMode.SURVIVAL, parser.parse("0"));
    }

    @Test
    @DisplayName("Should parse by number 1 = CREATIVE")
    void shouldParseCreativeByNumber() throws TypeParseException {
        assertEquals(GameMode.CREATIVE, parser.parse("1"));
    }

    @Test
    @DisplayName("Should parse by number 2 = ADVENTURE")
    void shouldParseAdventureByNumber() throws TypeParseException {
        assertEquals(GameMode.ADVENTURE, parser.parse("2"));
    }

    @Test
    @DisplayName("Should parse by number 3 = SPECTATOR")
    void shouldParseSpectatorByNumber() throws TypeParseException {
        assertEquals(GameMode.SPECTATOR, parser.parse("3"));
    }

    @Test
    @DisplayName("Should throw exception for invalid number")
    void shouldThrowForInvalidNumber() {
        TypeParseException exception = assertThrows(TypeParseException.class, 
            () -> parser.parse("4"));
        assertTrue(exception.getMessage().contains("Invalid GameMode number"));
    }

    @Test
    @DisplayName("Should throw exception for invalid name")
    void shouldThrowForInvalidName() {
        TypeParseException exception = assertThrows(TypeParseException.class, 
            () -> parser.parse("INVALID_MODE"));
        assertTrue(exception.getMessage().contains("Invalid GameMode"));
    }

    @Test
    @DisplayName("Should throw exception for empty value")
    void shouldThrowForEmptyValue() {
        assertThrows(TypeParseException.class, () -> parser.parse(""));
    }

    @Test
    @DisplayName("Should throw exception for null value")
    void shouldThrowForNullValue() {
        assertThrows(TypeParseException.class, () -> parser.parse(null));
    }

    @Test
    @DisplayName("Should parse array values")
    void shouldParseArrayValues() throws TypeParseException {
        String[] values = {"0", "CREATIVE", "2"};
        GameMode[] result = parser.parseArray(values);
        
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(GameMode.SURVIVAL, result[0]);
        assertEquals(GameMode.CREATIVE, result[1]);
        assertEquals(GameMode.ADVENTURE, result[2]);
    }
}
