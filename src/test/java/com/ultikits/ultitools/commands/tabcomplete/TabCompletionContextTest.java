package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for TabCompletionContext.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TabCompletionContext Tests")
class TabCompletionContextTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private Command mockCommand;

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create context with all fields")
        void builder_createsContextWithAllFields() throws NoSuchMethodException {
            Method testMethod = String.class.getMethod("toString");
            Object executor = new Object();
            String[] args = {"arg1", "arg2"};

            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .command(mockCommand)
                    .args(args)
                    .currentArgIndex(1)
                    .partialArg("arg2")
                    .matchedMethod(testMethod)
                    .parameterName("testParam")
                    .executorInstance(executor)
                    .build();

            assertEquals(mockPlayer, context.getPlayer());
            assertEquals(mockCommand, context.getCommand());
            assertArrayEquals(args, context.getArgs());
            assertEquals(1, context.getCurrentArgIndex());
            assertEquals("arg2", context.getPartialArg());
            assertEquals(testMethod, context.getMatchedMethod());
            assertEquals("testParam", context.getParameterName());
            assertEquals(executor, context.getExecutorInstance());
        }

        @Test
        @DisplayName("Builder should allow null values")
        void builder_allowsNullValues() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(null)
                    .command(null)
                    .args(null)
                    .build();

            assertNull(context.getPlayer());
            assertNull(context.getCommand());
            assertNull(context.getArgs());
        }

        @Test
        @DisplayName("Builder should create context with default values")
        void builder_defaultValues() {
            TabCompletionContext context = TabCompletionContext.builder().build();

            assertNull(context.getPlayer());
            assertNull(context.getCommand());
            assertNull(context.getArgs());
            assertEquals(0, context.getCurrentArgIndex());
            assertNull(context.getPartialArg());
            assertNull(context.getMatchedMethod());
            assertNull(context.getParameterName());
            assertNull(context.getExecutorInstance());
        }
    }

    @Nested
    @DisplayName("getCurrentInput() Tests")
    class GetCurrentInputTests {

        @Test
        @DisplayName("getCurrentInput should return current arg when within bounds")
        void getCurrentInput_returnsCurrentArg_whenWithinBounds() {
            String[] args = {"first", "second", "third"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(1)
                    .build();

            assertEquals("second", context.getCurrentInput());
        }

        @Test
        @DisplayName("getCurrentInput should return empty string when args is null")
        void getCurrentInput_returnsEmpty_whenArgsNull() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(null)
                    .currentArgIndex(0)
                    .build();

            assertEquals("", context.getCurrentInput());
        }

        @Test
        @DisplayName("getCurrentInput should return empty string when args is empty")
        void getCurrentInput_returnsEmpty_whenArgsEmpty() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[0])
                    .currentArgIndex(0)
                    .build();

            assertEquals("", context.getCurrentInput());
        }

        @Test
        @DisplayName("getCurrentInput should return empty string when index out of bounds")
        void getCurrentInput_returnsEmpty_whenIndexOutOfBounds() {
            String[] args = {"first", "second"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(5)
                    .build();

            assertEquals("", context.getCurrentInput());
        }

        @Test
        @DisplayName("getCurrentInput should return first arg when index is 0")
        void getCurrentInput_returnsFirstArg_whenIndexIsZero() {
            String[] args = {"hello", "world"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertEquals("hello", context.getCurrentInput());
        }

        @Test
        @DisplayName("getCurrentInput should return last arg correctly")
        void getCurrentInput_returnsLastArg() {
            String[] args = {"one", "two", "three"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(2)
                    .build();

            assertEquals("three", context.getCurrentInput());
        }
    }

    @Nested
    @DisplayName("inputStartsWith() Tests")
    class InputStartsWithTests {

        @Test
        @DisplayName("inputStartsWith should return true for matching prefix")
        void inputStartsWith_returnsTrue_forMatchingPrefix() {
            String[] args = {"hello"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertTrue(context.inputStartsWith("hel"));
            assertTrue(context.inputStartsWith("hello"));
            assertTrue(context.inputStartsWith("h"));
        }

        @Test
        @DisplayName("inputStartsWith should be case insensitive")
        void inputStartsWith_caseInsensitive() {
            String[] args = {"Hello"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertTrue(context.inputStartsWith("hel"));
            assertTrue(context.inputStartsWith("HEL"));
            assertTrue(context.inputStartsWith("HeL"));
        }

        @Test
        @DisplayName("inputStartsWith should return false for non-matching prefix")
        void inputStartsWith_returnsFalse_forNonMatchingPrefix() {
            String[] args = {"hello"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertFalse(context.inputStartsWith("world"));
            assertFalse(context.inputStartsWith("xyz"));
        }

        @Test
        @DisplayName("inputStartsWith should return true for empty prefix")
        void inputStartsWith_returnsTrue_forEmptyPrefix() {
            String[] args = {"hello"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertTrue(context.inputStartsWith(""));
        }

        @Test
        @DisplayName("inputStartsWith should handle empty input")
        void inputStartsWith_handlesEmptyInput() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[0])
                    .currentArgIndex(0)
                    .build();

            assertTrue(context.inputStartsWith(""));
            assertFalse(context.inputStartsWith("a"));
        }
    }

    @Nested
    @DisplayName("of() Static Factory Tests")
    class OfFactoryTests {

        @Test
        @DisplayName("of() should create context with correct fields")
        void of_createsContextWithCorrectFields() {
            String[] args = {"arg1", "arg2", "arg3"};

            TabCompletionContext context = TabCompletionContext.of(mockPlayer, mockCommand, args);

            assertEquals(mockPlayer, context.getPlayer());
            assertEquals(mockCommand, context.getCommand());
            assertArrayEquals(args, context.getArgs());
            assertEquals(2, context.getCurrentArgIndex()); // last index
            assertEquals("arg3", context.getPartialArg()); // last arg
        }

        @Test
        @DisplayName("of() should handle empty args")
        void of_handlesEmptyArgs() {
            String[] args = new String[0];

            TabCompletionContext context = TabCompletionContext.of(mockPlayer, mockCommand, args);

            assertEquals(mockPlayer, context.getPlayer());
            assertEquals(mockCommand, context.getCommand());
            assertArrayEquals(args, context.getArgs());
            assertEquals(0, context.getCurrentArgIndex());
            assertEquals("", context.getPartialArg());
        }

        @Test
        @DisplayName("of() should handle single arg")
        void of_handlesSingleArg() {
            String[] args = {"only"};

            TabCompletionContext context = TabCompletionContext.of(mockPlayer, mockCommand, args);

            assertEquals(0, context.getCurrentArgIndex());
            assertEquals("only", context.getPartialArg());
        }

        @Test
        @DisplayName("of() should set partial to last arg")
        void of_setsPartialToLastArg() {
            String[] args = {"first", "second", "partial"};

            TabCompletionContext context = TabCompletionContext.of(mockPlayer, mockCommand, args);

            assertEquals("partial", context.getPartialArg());
            assertEquals(2, context.getCurrentArgIndex());
        }
    }

    @Nested
    @DisplayName("Getter Tests")
    class GetterTests {

        @Test
        @DisplayName("All getters should return builder values")
        void allGetters_returnBuilderValues() throws NoSuchMethodException {
            Method method = Object.class.getMethod("toString");
            Object executor = "executor";
            String[] args = {"a", "b"};

            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .command(mockCommand)
                    .args(args)
                    .currentArgIndex(1)
                    .partialArg("b")
                    .matchedMethod(method)
                    .parameterName("param")
                    .executorInstance(executor)
                    .build();

            assertSame(mockPlayer, context.getPlayer());
            assertSame(mockCommand, context.getCommand());
            assertSame(args, context.getArgs());
            assertEquals(1, context.getCurrentArgIndex());
            assertEquals("b", context.getPartialArg());
            assertSame(method, context.getMatchedMethod());
            assertEquals("param", context.getParameterName());
            assertSame(executor, context.getExecutorInstance());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle args with special characters")
        void handlesArgsWithSpecialCharacters() {
            String[] args = {"hello@world", "test-arg", "arg_123"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertEquals("hello@world", context.getCurrentInput());
            assertTrue(context.inputStartsWith("hello@"));
        }

        @Test
        @DisplayName("Should handle unicode characters in args")
        void handlesUnicodeCharacters() {
            String[] args = {"你好", "世界"};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertEquals("你好", context.getCurrentInput());
            assertTrue(context.inputStartsWith("你"));
        }

        @Test
        @DisplayName("Should handle whitespace in args")
        void handlesWhitespace() {
            String[] args = {"  spaced  "};
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(args)
                    .currentArgIndex(0)
                    .build();

            assertEquals("  spaced  ", context.getCurrentInput());
            assertTrue(context.inputStartsWith("  spa"));
        }
    }
}
