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
    
    @Test
    @DisplayName("Should return correct arg count")
    void shouldReturnCorrectArgCount() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{"arg1", "arg2", "arg3"})
                .build();
        
        assertEquals(3, context.getArgCount());
    }
    
    @Test
    @DisplayName("Should return zero for null rawArgs")
    void shouldReturnZeroForNullRawArgs() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(null)
                .build();
        
        assertEquals(0, context.getArgCount());
    }
    
    @Test
    @DisplayName("Should get argument at valid index")
    void shouldGetArgAtValidIndex() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{"first", "second", "third"})
                .build();
        
        assertEquals("first", context.getArg(0));
        assertEquals("second", context.getArg(1));
        assertEquals("third", context.getArg(2));
    }
    
    @Test
    @DisplayName("Should return null for negative index")
    void shouldReturnNullForNegativeIndex() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{"arg1"})
                .build();
        
        assertNull(context.getArg(-1));
    }
    
    @Test
    @DisplayName("Should return null for out of bounds index")
    void shouldReturnNullForOutOfBoundsIndex() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{"arg1", "arg2"})
                .build();
        
        assertNull(context.getArg(5));
    }
    
    @Test
    @DisplayName("Should return null for null rawArgs when getting arg")
    void shouldReturnNullForNullRawArgsWhenGettingArg() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(null)
                .build();
        
        assertNull(context.getArg(0));
    }
    
    @Test
    @DisplayName("Should get param by name")
    void shouldGetParamByName() {
        Map<String, String[]> params = new HashMap<>();
        params.put("name", new String[]{"value1", "value2"});
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build()
                .withParsedParams(params);
        
        String[] result = context.getParam("name");
        assertNotNull(result);
        assertEquals(2, result.length);
        assertEquals("value1", result[0]);
        assertEquals("value2", result[1]);
    }
    
    @Test
    @DisplayName("Should return null for non-existent param")
    void shouldReturnNullForNonExistentParam() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertNull(context.getParam("nonexistent"));
    }
    
    @Test
    @DisplayName("Should get single param value")
    void shouldGetSingleParamValue() {
        Map<String, String[]> params = new HashMap<>();
        params.put("key", new String[]{"singleValue"});
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build()
                .withParsedParams(params);
        
        assertEquals("singleValue", context.getParamValue("key"));
    }
    
    @Test
    @DisplayName("Should return first value when param has multiple values")
    void shouldReturnFirstValueWhenMultipleValues() {
        Map<String, String[]> params = new HashMap<>();
        params.put("multi", new String[]{"first", "second", "third"});
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build()
                .withParsedParams(params);
        
        assertEquals("first", context.getParamValue("multi"));
    }
    
    @Test
    @DisplayName("Should return null for non-existent param value")
    void shouldReturnNullForNonExistentParamValue() {
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        assertNull(context.getParamValue("nonexistent"));
    }
    
    @Test
    @DisplayName("Should return null for empty param values array")
    void shouldReturnNullForEmptyParamValues() {
        Map<String, String[]> params = new HashMap<>();
        params.put("empty", new String[]{});
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build()
                .withParsedParams(params);
        
        assertNull(context.getParamValue("empty"));
    }
    
    @Test
    @DisplayName("Should have default timestamp")
    void shouldHaveDefaultTimestamp() {
        long before = System.currentTimeMillis();
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build();
        
        long after = System.currentTimeMillis();
        
        assertTrue(context.getTimestamp() >= before);
        assertTrue(context.getTimestamp() <= after);
    }
    
    @Test
    @DisplayName("Should merge params with withParsedParams")
    void shouldMergeParamsWithParsedParams() {
        Map<String, String[]> initial = new HashMap<>();
        initial.put("key1", new String[]{"value1"});
        
        CommandContext context = CommandContext.builder()
                .sender(mockPlayer)
                .command(mockCommand)
                .alias("test")
                .rawArgs(new String[]{})
                .build()
                .withParsedParams(initial);
        
        Map<String, String[]> additional = new HashMap<>();
        additional.put("key2", new String[]{"value2"});
        
        CommandContext updated = context.withParsedParams(additional);
        
        // Updated should have both keys
        assertEquals(2, updated.getParsedParams().size());
        assertNotNull(updated.getParam("key1"));
        assertNotNull(updated.getParam("key2"));
    }
}
