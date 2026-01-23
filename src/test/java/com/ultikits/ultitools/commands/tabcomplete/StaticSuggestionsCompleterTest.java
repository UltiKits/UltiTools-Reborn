package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for StaticSuggestionsCompleter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaticSuggestionsCompleter Tests")
class StaticSuggestionsCompleterTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Varargs constructor should accept string array")
        void varargsConstructorAcceptsstringarray() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter("one", "two", "three");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("one"));
            assertTrue(suggestions.contains("two"));
            assertTrue(suggestions.contains("three"));
        }

        @Test
        @DisplayName("List constructor should accept list of strings")
        void listConstructorAcceptslistofstrings() {
            List<String> items = Arrays.asList("alpha", "beta", "gamma");
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(items);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("alpha"));
            assertTrue(suggestions.contains("beta"));
            assertTrue(suggestions.contains("gamma"));
        }

        @Test
        @DisplayName("Constructor with case sensitivity flag")
        void constructorWithcasesensitivity() {
            List<String> items = Arrays.asList("Test", "TEST", "test");
            StaticSuggestionsCompleter caseSensitive = new StaticSuggestionsCompleter(items, true);
            StaticSuggestionsCompleter caseInsensitive = new StaticSuggestionsCompleter(items, false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"te"})
                    .currentArgIndex(0)
                    .build();
            
            // Case sensitive should only match lowercase "test"
            List<String> sensitiveSuggestions = caseSensitive.complete(context);
            assertEquals(1, sensitiveSuggestions.size());
            assertEquals("test", sensitiveSuggestions.get(0));
            
            // Case insensitive should match all
            List<String> insensitiveSuggestions = caseInsensitive.complete(context);
            assertEquals(3, insensitiveSuggestions.size());
        }

        @Test
        @DisplayName("Empty varargs creates completer with no suggestions")
        void emptyVarargsCreatesemptycompleter() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("Empty list creates completer with no suggestions")
        void emptyListCreatesemptycompleter() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(Collections.emptyList());
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            assertTrue(suggestions.isEmpty());
        }
    }

    @Nested
    @DisplayName("complete() Method Tests")
    class CompleteMethodTests {

        private StaticSuggestionsCompleter completer;

        @BeforeEach
        void setUp() {
            completer = new StaticSuggestionsCompleter("apple", "apricot", "banana", "blueberry", "cherry");
        }

        @Test
        @DisplayName("complete() should return all suggestions when input is empty")
        void completeReturnsallsuggestionsWheninputempty() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(5, suggestions.size());
        }

        @Test
        @DisplayName("complete() should filter by prefix")
        void completeFiltersbyprefix() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"ap"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("apple"));
            assertTrue(suggestions.contains("apricot"));
        }

        @Test
        @DisplayName("complete() should be case insensitive by default")
        void completeCaseinsensitivebydefault() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"AP"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("apple"));
            assertTrue(suggestions.contains("apricot"));
        }

        @Test
        @DisplayName("complete() should return empty list when no match")
        void completeReturnsemptylistWhennomatch() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"xyz"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return sorted results")
        void completeReturnssortedresults() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"b"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertEquals("banana", suggestions.get(0));
            assertEquals("blueberry", suggestions.get(1));
        }

        @Test
        @DisplayName("complete() should handle single character input")
        void completeHandlessinglecharacterinput() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"c"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("cherry", suggestions.get(0));
        }

        @Test
        @DisplayName("complete() should handle exact match")
        void completeHandlesexactmatch() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"apple"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("apple", suggestions.get(0));
        }
    }

    @Nested
    @DisplayName("Case Sensitivity Tests")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Case sensitive completer should only match exact case")
        void caseSensitiveMatchesexactcase() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    Arrays.asList("Apple", "APPLE", "apple"), true);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"App"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("Apple", suggestions.get(0));
        }

        @Test
        @DisplayName("Case insensitive completer should match any case")
        void caseInsensitiveMatchesanycase() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    Arrays.asList("Apple", "APPLE", "apple"), false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"App"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
        }

        @Test
        @DisplayName("Case sensitive with lowercase input")
        void caseSensitiveLowercaseinput() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    Arrays.asList("Apple", "APPLE", "apple"), true);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"app"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("apple", suggestions.get(0));
        }

        @Test
        @DisplayName("Case sensitive with uppercase input")
        void caseSensitiveUppercaseinput() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    Arrays.asList("Apple", "APPLE", "apple"), true);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"APP"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("APPLE", suggestions.get(0));
        }
    }

    @Nested
    @DisplayName("forBoolean() Factory Tests")
    class ForBooleanTests {

        @Test
        @DisplayName("forBoolean() should return true and false suggestions")
        void forBooleanReturnstrueandfalse() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forBoolean();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("true"));
            assertTrue(suggestions.contains("false"));
        }

        @Test
        @DisplayName("forBoolean() should filter by t prefix")
        void forBooleanFiltersbytprefix() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forBoolean();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"t"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("true", suggestions.get(0));
        }

        @Test
        @DisplayName("forBoolean() should filter by f prefix")
        void forBooleanFiltersbyfprefix() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forBoolean();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"f"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("false", suggestions.get(0));
        }

        @Test
        @DisplayName("forBoolean() should be case insensitive")
        void forBooleanCaseinsensitive() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forBoolean();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"T"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("true", suggestions.get(0));
        }
    }

    @Nested
    @DisplayName("forToggle() Factory Tests")
    class ForToggleTests {

        @Test
        @DisplayName("forToggle() should return toggle suggestions")
        void forToggleReturnstogglesuggestions() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forToggle();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(4, suggestions.size());
            assertTrue(suggestions.contains("on"));
            assertTrue(suggestions.contains("off"));
            assertTrue(suggestions.contains("enable"));
            assertTrue(suggestions.contains("disable"));
        }

        @Test
        @DisplayName("forToggle() should filter by on/off")
        void forToggleFiltersbyonoff() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forToggle();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"o"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("on"));
            assertTrue(suggestions.contains("off"));
        }

        @Test
        @DisplayName("forToggle() should filter by enable/disable")
        void forToggleFiltersbyenabledisable() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forToggle();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"e"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("enable", suggestions.get(0));
        }

        @Test
        @DisplayName("forToggle() should filter by d prefix for disable")
        void forToggleFiltersbydprefix() {
            StaticSuggestionsCompleter completer = StaticSuggestionsCompleter.forToggle();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"d"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("disable", suggestions.get(0));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle suggestions with special characters")
        void handlesSpecialCharacters() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    "item-one", "item_two", "item.three");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"item"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
        }

        @Test
        @DisplayName("Should handle numeric suggestions")
        void handlesNumericSuggestions() {
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(
                    "123", "456", "789");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"1"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("123", suggestions.get(0));
        }

        @Test
        @DisplayName("Should not modify original list")
        void doesNotModifyOriginalList() {
            List<String> original = new ArrayList<>(Arrays.asList("a", "b", "c"));
            StaticSuggestionsCompleter completer = new StaticSuggestionsCompleter(original);
            
            // Modify original
            original.add("d");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            // Completer should still have original 3 items
            List<String> suggestions = completer.complete(context);
            assertEquals(3, suggestions.size());
        }
    }
}
