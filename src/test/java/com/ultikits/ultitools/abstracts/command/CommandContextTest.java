package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CommandContext.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommandContext Tests")
class CommandContextTest {

    @Mock
    private Player mockPlayer;
    
    @Mock
    private CommandSender mockSender;
    
    @Mock
    private Command mockCommand;

    @BeforeEach
    void setUp() {
        lenient().when(mockCommand.getName()).thenReturn("test");
    }

    @Test
    @DisplayName("Should create context with builder")
    void shouldCreateWithBuilder() {
        String[] args = {"arg1", "arg2"};
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(args)
                .build();
        
        assertNotNull(context);
        assertEquals(mockPlayer, context.getSender());
        assertEquals(mockCommand, context.getCommand());
        assertEquals("test", context.getAlias());
        assertArrayEquals(args, context.getRawArgs());
    }

    @Test
    @DisplayName("Should detect player sender")
    void shouldDetectPlayerSender() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertTrue(context.isPlayer());
        assertEquals(mockPlayer, context.getPlayer());
    }

    @Test
    @DisplayName("Should detect non-player sender")
    void shouldDetectNonPlayerSender() {
        CommandContext context = CommandContext.builder()
                .sender(mockSender)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertFalse(context.isPlayer());
        assertNull(context.getPlayer());
    }

    @Test
    @DisplayName("Should return null method when not set")
    void shouldReturnNullMethodWhenNotSet() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertNull(context.getMatchedMethod());
        assertNull(context.getMatchedFormat());
    }

    @Test
    @DisplayName("Should update with matched method")
    void shouldUpdateWithMatchedMethod() throws NoSuchMethodException {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        Method method = String.class.getMethod("toString");
        CommandContext updated = context.withMatchedMethod(method, "format");
        
        // Original should be unchanged (immutable)
        assertNull(context.getMatchedMethod());
        
        // New context should have method
        assertEquals(method, updated.getMatchedMethod());
        assertEquals("format", updated.getMatchedFormat());
    }

    @Test
    @DisplayName("Should update with parsed params")
    void shouldUpdateWithParsedParams() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        Map<String, String[]> params = new HashMap<>();
        params.put("param1", new String[]{"value1"});
        params.put("param2", new String[]{"value2", "value3"});
        
        CommandContext updated = context.withParsedParams(params);
        
        // Original should be unchanged
        assertTrue(context.getParsedParams().isEmpty());
        
        // New context should have params
        assertEquals(2, updated.getParsedParams().size());
        assertArrayEquals(new String[]{"value1"}, updated.getParsedParams().get("param1"));
    }

    @Test
    @DisplayName("Should handle empty args")
    void shouldHandleEmptyArgs() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertEquals(0, context.getRawArgs().length);
        assertTrue(context.getParsedParams().isEmpty());
    }

    @Test
    @DisplayName("Parsed params from withParsedParams should be immutable")
    void parsedParamsShouldBeImmutable() {
        Map<String, String[]> params = new HashMap<>();
        params.put("key", new String[]{"value"});
        
        CommandContext baseContext = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        // Use withParsedParams which wraps in unmodifiable map
        CommandContext context = baseContext.withParsedParams(params);
        
        // Try to modify the map - should throw
        assertThrows(UnsupportedOperationException.class, () -> 
            context.getParsedParams().put("new", new String[]{})
        );
    }
}
