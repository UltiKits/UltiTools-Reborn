package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ultikits.ultitools.annotations.command.CmdParam;

/**
 * Unit tests for TabCompletionManager.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TabCompletionManager Tests")
class TabCompletionManagerTest {

    @Mock
    private Player mockPlayer;

    @Mock
    private Command mockCommand;

    private TabCompletionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Reset singleton for testing
        resetSingleton();
        manager = TabCompletionManager.getInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        resetSingleton();
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private void resetSingleton() throws Exception {
        Field instanceField = TabCompletionManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Nested
    @DisplayName("Singleton Tests")
    class SingletonTests {

        @Test
        @DisplayName("getInstance() should return same instance")
        void getInstanceReturnssameinstance() {
            TabCompletionManager instance1 = TabCompletionManager.getInstance();
            TabCompletionManager instance2 = TabCompletionManager.getInstance();
            
            assertSame(instance1, instance2);
        }

        @Test
        @DisplayName("getInstance() should be thread-safe")
        void getInstance_isThreadSafe() throws InterruptedException {
            final TabCompletionManager[] instances = new TabCompletionManager[10];
            Thread[] threads = new Thread[10];
            
            for (int i = 0; i < 10; i++) {
                final int index = i;
                threads[i] = new Thread(() -> {
                    instances[index] = TabCompletionManager.getInstance();
                });
            }
            
            for (Thread thread : threads) {
                thread.start();
            }
            
            for (Thread thread : threads) {
                thread.join();
            }
            
            // All should be same instance
            for (int i = 1; i < 10; i++) {
                assertSame(instances[0], instances[i]);
            }
        }
    }

    @Nested
    @DisplayName("Built-in Completer Constants Tests")
    class ConstantsTests {

        @Test
        @DisplayName("PLAYERS constant should be correct")
        void playersConstantIscorrect() {
            assertEquals("@players", TabCompletionManager.PLAYERS);
        }

        @Test
        @DisplayName("WORLDS constant should be correct")
        void worldsConstantIscorrect() {
            assertEquals("@worlds", TabCompletionManager.WORLDS);
        }

        @Test
        @DisplayName("MATERIALS constant should be correct")
        void materialsConstantIscorrect() {
            assertEquals("@materials", TabCompletionManager.MATERIALS);
        }

        @Test
        @DisplayName("BLOCKS constant should be correct")
        void blocksConstantIscorrect() {
            assertEquals("@blocks", TabCompletionManager.BLOCKS);
        }

        @Test
        @DisplayName("ITEMS constant should be correct")
        void itemsConstantIscorrect() {
            assertEquals("@items", TabCompletionManager.ITEMS);
        }

        @Test
        @DisplayName("BOOLEAN constant should be correct")
        void booleanConstantIscorrect() {
            assertEquals("@boolean", TabCompletionManager.BOOLEAN);
        }

        @Test
        @DisplayName("TOGGLE constant should be correct")
        void toggleConstantIscorrect() {
            assertEquals("@toggle", TabCompletionManager.TOGGLE);
        }
    }

    @Nested
    @DisplayName("Built-in Completers Tests")
    class BuiltInCompletersTests {

        @Test
        @DisplayName("Manager should have built-in PLAYERS completer")
        void hasPlayersCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.PLAYERS);
            assertNotNull(completer);
            assertTrue(completer instanceof OnlinePlayersCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in WORLDS completer")
        void hasWorldsCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.WORLDS);
            assertNotNull(completer);
            assertTrue(completer instanceof WorldsCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in MATERIALS completer")
        void hasMaterialsCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.MATERIALS);
            assertNotNull(completer);
            assertTrue(completer instanceof MaterialsCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in BLOCKS completer")
        void hasBlocksCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.BLOCKS);
            assertNotNull(completer);
            assertTrue(completer instanceof MaterialsCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in ITEMS completer")
        void hasItemsCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.ITEMS);
            assertNotNull(completer);
            assertTrue(completer instanceof MaterialsCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in BOOLEAN completer")
        void hasBooleanCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.BOOLEAN);
            assertNotNull(completer);
            assertTrue(completer instanceof StaticSuggestionsCompleter);
        }

        @Test
        @DisplayName("Manager should have built-in TOGGLE completer")
        void hasToggleCompleter() {
            TabCompleter completer = manager.getCompleter(TabCompletionManager.TOGGLE);
            assertNotNull(completer);
            assertTrue(completer instanceof StaticSuggestionsCompleter);
        }
    }

    @Nested
    @DisplayName("register() Method Tests")
    class RegisterMethodTests {

        @Test
        @DisplayName("register() should add custom completer")
        void registerAddscustomcompleter() {
            TabCompleter customCompleter = context -> Arrays.asList("custom1", "custom2");
            
            manager.register("@custom", customCompleter);
            
            TabCompleter retrieved = manager.getCompleter("@custom");
            assertSame(customCompleter, retrieved);
        }

        @Test
        @DisplayName("register() should throw exception for null key")
        void registerThrowsexceptionFornullkey() {
            TabCompleter completer = context -> Collections.emptyList();
            
            assertThrows(IllegalArgumentException.class, () -> 
                manager.register(null, completer));
        }

        @Test
        @DisplayName("register() should throw exception for null completer")
        void registerThrowsexceptionFornullcompleter() {
            assertThrows(IllegalArgumentException.class, () -> 
                manager.register("@test", null));
        }

        @Test
        @DisplayName("register() should overwrite existing completer")
        void registerOverwritesexistingcompleter() {
            TabCompleter completer1 = context -> Arrays.asList("a", "b");
            TabCompleter completer2 = context -> Arrays.asList("x", "y");
            
            manager.register("@test", completer1);
            manager.register("@test", completer2);
            
            assertSame(completer2, manager.getCompleter("@test"));
        }
    }

    @Nested
    @DisplayName("unregister() Method Tests")
    class UnregisterMethodTests {

        @Test
        @DisplayName("unregister() should remove completer")
        void unregisterRemovescompleter() {
            TabCompleter completer = context -> Collections.emptyList();
            manager.register("@test", completer);
            
            manager.unregister("@test");
            
            assertNull(manager.getCompleter("@test"));
        }

        @Test
        @DisplayName("unregister() should handle non-existent key")
        void unregisterHandlesnonexistentkey() {
            // Should not throw
            assertDoesNotThrow(() -> manager.unregister("@nonexistent"));
        }

        @Test
        @DisplayName("unregister() can remove built-in completer")
        void unregisterCanremovebuiltincompleter() {
            assertNotNull(manager.getCompleter(TabCompletionManager.BOOLEAN));
            
            manager.unregister(TabCompletionManager.BOOLEAN);
            
            assertNull(manager.getCompleter(TabCompletionManager.BOOLEAN));
        }
    }

    @Nested
    @DisplayName("getCompleter() Method Tests")
    class GetCompleterMethodTests {

        @Test
        @DisplayName("getCompleter() should return registered completer")
        void getCompleterReturnsregisteredcompleter() {
            TabCompleter completer = context -> Collections.emptyList();
            manager.register("@test", completer);
            
            assertSame(completer, manager.getCompleter("@test"));
        }

        @Test
        @DisplayName("getCompleter() should return null for unknown key")
        void getCompleterReturnsnullForunknownkey() {
            assertNull(manager.getCompleter("@unknown"));
        }

        @Test
        @DisplayName("getCompleter() should return built-in completers")
        void getCompleterReturnsbuiltincompleters() {
            assertNotNull(manager.getCompleter(TabCompletionManager.PLAYERS));
            assertNotNull(manager.getCompleter(TabCompletionManager.WORLDS));
            assertNotNull(manager.getCompleter(TabCompletionManager.MATERIALS));
        }
    }

    @Nested
    @DisplayName("suggest(Player, Command, String[]) Method Tests")
    class SuggestWithArgsMethodTests {

        @Test
        @DisplayName("suggest() should create context and delegate")
        void suggestCreatescontextanddelegates() {
            String[] args = {"test"};
            
            // This method creates a context internally
            List<String> suggestions = manager.suggest(mockPlayer, mockCommand, args);
            
            assertNotNull(suggestions);
        }

        @Test
        @DisplayName("suggest() should handle empty args")
        void suggestHandlesemptyargs() {
            String[] args = new String[0];
            
            List<String> suggestions = manager.suggest(mockPlayer, mockCommand, args);
            
            assertNotNull(suggestions);
        }
    }

    @Nested
    @DisplayName("suggest(TabCompletionContext) Method Tests")
    class SuggestWithContextMethodTests {

        @Test
        @DisplayName("suggest() should return empty list for null context")
        void suggestReturnsemptylistFornullcontext() {
            List<String> suggestions = manager.suggest((TabCompletionContext) null);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggest() should use built-in completer when parameter starts with @")
        void suggestUsesbuiltincompleterForatprefix() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .command(mockCommand)
                    .args(new String[]{"t"})
                    .currentArgIndex(0)
                    .parameterName("@boolean")
                    .build();
            
            List<String> suggestions = manager.suggest(context);
            
            assertEquals(1, suggestions.size());
            assertEquals("true", suggestions.get(0));
        }

        @Test
        @DisplayName("suggest() should delegate to method completer when no @ parameter")
        void suggestDelegatestomethodcompleterWhennoatparameter() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .command(mockCommand)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .parameterName("normalParam")
                    .build();
            
            // Without matched method, should return empty
            List<String> suggestions = manager.suggest(context);
            
            assertNotNull(suggestions);
        }

        @Test
        @DisplayName("suggest() should return empty when @ completer not found")
        void suggestReturnsemptyWhenatcompleternotfound() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockPlayer)
                    .command(mockCommand)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .parameterName("@nonexistent")
                    .build();
            
            // Falls through to method completer which returns empty
            List<String> suggestions = manager.suggest(context);
            
            assertNotNull(suggestions);
        }
    }

    @Nested
    @DisplayName("suggestWith() Method Tests")
    class SuggestWithMethodTests {

        @Test
        @DisplayName("suggestWith() should use specified completer")
        void suggestWithUsesspecifiedcompleter() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"t"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestWith(context, TabCompletionManager.BOOLEAN);
            
            assertEquals(1, suggestions.size());
            assertEquals("true", suggestions.get(0));
        }

        @Test
        @DisplayName("suggestWith() should return empty for unknown completer")
        void suggestWithReturnsemptyForunknowncompleter() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestWith(context, "@unknown");
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggestWith() should work with custom completers")
        void suggestWithWorkswithcustomcompleters() {
            manager.register("@custom", ctx -> Arrays.asList("opt1", "opt2", "opt3"));
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"opt"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestWith(context, "@custom");
            
            assertEquals(3, suggestions.size());
        }
    }

    @Nested
    @DisplayName("createContext() Method Tests")
    class CreateContextMethodTests {

        @Test
        @DisplayName("createContext() should create context with correct values")
        void createContextCreatescontextwithcorrectvalues() {
            String[] args = {"arg1", "arg2", "partial"};
            
            TabCompletionContext context = manager.createContext(mockPlayer, mockCommand, args);
            
            assertEquals(mockPlayer, context.getPlayer());
            assertEquals(mockCommand, context.getCommand());
            assertArrayEquals(args, context.getArgs());
            assertEquals(2, context.getCurrentArgIndex());
            assertEquals("partial", context.getPartialArg());
        }

        @Test
        @DisplayName("createContext() should handle empty args")
        void createContextHandlesemptyargs() {
            String[] args = new String[0];
            
            TabCompletionContext context = manager.createContext(mockPlayer, mockCommand, args);
            
            assertEquals(0, context.getCurrentArgIndex());
            assertEquals("", context.getPartialArg());
        }

        @Test
        @DisplayName("createContext() should handle single arg")
        void createContextHandlessinglearg() {
            String[] args = {"only"};
            
            TabCompletionContext context = manager.createContext(mockPlayer, mockCommand, args);
            
            assertEquals(0, context.getCurrentArgIndex());
            assertEquals("only", context.getPartialArg());
        }
    }

    @Nested
    @DisplayName("filterByInput() Static Method Tests")
    class FilterByInputMethodTests {

        @Test
        @DisplayName("filterByInput() should filter suggestions by prefix")
        void filterByInputFiltersbyprefix() {
            List<String> suggestions = Arrays.asList("apple", "apricot", "banana", "cherry");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "ap");
            
            assertEquals(2, filtered.size());
            assertTrue(filtered.contains("apple"));
            assertTrue(filtered.contains("apricot"));
        }

        @Test
        @DisplayName("filterByInput() should be case insensitive")
        void filterByInputCaseinsensitive() {
            List<String> suggestions = Arrays.asList("Apple", "APRICOT", "banana");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "AP");
            
            assertEquals(2, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return all when input is empty")
        void filterByInputReturnsallWheninputempty() {
            List<String> suggestions = Arrays.asList("a", "b", "c");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "");
            
            assertEquals(3, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return all when input is null")
        void filterByInputReturnsallWheninputnull() {
            List<String> suggestions = Arrays.asList("a", "b", "c");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, null);
            
            assertEquals(3, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return empty for null suggestions")
        void filterByInputReturnsemptyFornullsuggestions() {
            List<String> filtered = TabCompletionManager.filterByInput(null, "test");
            
            assertTrue(filtered.isEmpty());
        }

        @Test
        @DisplayName("filterByInput() should return empty for empty suggestions")
        void filterByInputReturnsemptyForemptysuggestions() {
            List<String> filtered = TabCompletionManager.filterByInput(Collections.emptyList(), "test");
            
            assertTrue(filtered.isEmpty());
        }

        @Test
        @DisplayName("filterByInput() should return empty when no match")
        void filterByInputReturnsemptyWhennomatch() {
            List<String> suggestions = Arrays.asList("apple", "banana", "cherry");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "xyz");
            
            assertTrue(filtered.isEmpty());
        }
    }

    @Nested
    @DisplayName("suggestFirstArgs() Method Tests")
    class SuggestFirstArgsMethodTests {

        @Test
        @DisplayName("suggestFirstArgs() should extract first args from mappings")
        void suggestFirstArgs_extractsFirstArgs() throws NoSuchMethodException {
            Map<String, Method> mappings = new HashMap<>();
            Method testMethod = String.class.getMethod("toString");
            mappings.put("add <player>", testMethod);
            mappings.put("remove <player>", testMethod);
            mappings.put("list", testMethod);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(mappings, context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("add"));
            assertTrue(suggestions.contains("remove"));
            assertTrue(suggestions.contains("list"));
        }

        @Test
        @DisplayName("suggestFirstArgs() should filter by current input")
        void suggestFirstArgsFiltersByInput() throws NoSuchMethodException {
            Map<String, Method> mappings = new HashMap<>();
            Method testMethod = String.class.getMethod("toString");
            mappings.put("add <player>", testMethod);
            mappings.put("addall", testMethod);
            mappings.put("remove <player>", testMethod);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"add"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(mappings, context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("add"));
            assertTrue(suggestions.contains("addall"));
        }

        @Test
        @DisplayName("suggestFirstArgs() should skip parameter placeholders")
        void suggestFirstArgs_skipsParameterPlaceholders() throws NoSuchMethodException {
            Map<String, Method> mappings = new HashMap<>();
            Method testMethod = String.class.getMethod("toString");
            mappings.put("<player>", testMethod);
            mappings.put("action", testMethod);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(mappings, context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("action"));
        }

        @Test
        @DisplayName("suggestFirstArgs() should return sorted results")
        void suggestFirstArgsReturnsSortedResults() throws NoSuchMethodException {
            Map<String, Method> mappings = new HashMap<>();
            Method testMethod = String.class.getMethod("toString");
            mappings.put("zebra", testMethod);
            mappings.put("apple", testMethod);
            mappings.put("mango", testMethod);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(mappings, context);
            
            assertEquals("apple", suggestions.get(0));
            assertEquals("mango", suggestions.get(1));
            assertEquals("zebra", suggestions.get(2));
        }

        @Test
        @DisplayName("suggestFirstArgs() should return empty for null mappings")
        void suggestFirstArgsReturnsemptyFornullmappings() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(null, context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggestFirstArgs() should return empty for empty mappings")
        void suggestFirstArgsReturnsemptyForemptymappings() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(Collections.emptyMap(), context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggestFirstArgs() should not include duplicates")
        void suggestFirstArgs_noDuplicates() throws NoSuchMethodException {
            Map<String, Method> mappings = new HashMap<>();
            Method testMethod = String.class.getMethod("toString");
            mappings.put("add <player>", testMethod);
            mappings.put("add <item>", testMethod);
            mappings.put("add", testMethod);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(mappings, context);
            
            // Should only have one "add"
            assertEquals(1, suggestions.stream().filter(s -> s.equals("add")).count());
        }
    }

    // ============================================================================================
    // 05-06 Task 1: resolveSuggestValue() and suggest(TabCompletionContext, String) -- dual notation
    // ============================================================================================

    /**
     * Fixture for {@link ResolveSuggestValueMethodTests}: two {@code @CmdParam}-annotated
     * parameters on one method, one with an ordinary display name and one whose DISPLAY NAME
     * itself starts with {@code @} (the wrong-attribute trap D-07 Pitfall 2 warns about).
     */
    private static class ResolveSuggestValueFixture {
        void mapping(@CmdParam(value = "target", suggest = "suggestTarget") String target,
                @CmdParam(value = "@trap", suggest = "suggestTrapMethod") String trap) {
            // Test fixture -- never invoked
        }
    }

    @Nested
    @DisplayName("resolveSuggestValue() Method Tests (05-06 dual notation)")
    class ResolveSuggestValueMethodTests {

        private Method fixtureMethod;

        @BeforeEach
        void locateFixtureMethod() throws NoSuchMethodException {
            fixtureMethod = ResolveSuggestValueFixture.class.getDeclaredMethod(
                    "mapping", String.class, String.class);
        }

        @Test
        @DisplayName("returns suggest() for the parameter whose value() matches parameterName")
        void returnsSuggestForMatchingParameter() {
            assertEquals("suggestTarget",
                    TabCompletionManager.resolveSuggestValue(fixtureMethod, "target"));
        }

        @Test
        @DisplayName("keys off suggest(), not the display name -- a display name starting with @ does not change the result")
        void resolvesByValueLookupNotDisplayNameContent() {
            assertEquals("suggestTrapMethod",
                    TabCompletionManager.resolveSuggestValue(fixtureMethod, "@trap"));
        }

        @Test
        @DisplayName("returns null when no @CmdParam on the method matches parameterName")
        void returnsNullWhenNoMatch() {
            assertNull(TabCompletionManager.resolveSuggestValue(fixtureMethod, "doesNotExist"));
        }

        @Test
        @DisplayName("returns null for a null method")
        void returnsNullForNullMethod() {
            assertNull(TabCompletionManager.resolveSuggestValue(null, "target"));
        }
    }

    /**
     * Fixture for {@link SuggestWithResolvedSuggestMethodTests}'s method-invocation fall-through
     * assertions -- a real suggestion method returning known content, so the fall-through path's
     * OUTPUT is asserted, not merely that it does not throw.
     */
    private static class MethodFallbackFixture {
        void mapping(@CmdParam(value = "thing", suggest = "suggestValues") String thing) {
            // Test fixture -- never invoked
        }

        List<String> suggestValues(Player player, Command command, String[] args) {
            return Arrays.asList("fallbackOne", "fallbackTwo");
        }
    }

    @Nested
    @DisplayName("suggest(TabCompletionContext, String) Method Tests (05-06 dual notation)")
    class SuggestWithResolvedSuggestMethodTests {

        @Test
        @DisplayName("resolvedSuggest starting with @ resolves through the registered completer")
        void resolvedSuggestAtPrefixResolvesThroughCompleter() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .partialArg("")
                    .build();

            List<String> suggestions = manager.suggest(context, TabCompletionManager.BOOLEAN);

            assertTrue(suggestions.contains("true"));
            assertTrue(suggestions.contains("false"));
        }

        @Test
        @DisplayName("an unregistered @key returns an empty list rather than throwing")
        void unregisteredAtKeyReturnsEmpty() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .partialArg("")
                    .build();

            List<String> suggestions = manager.suggest(context, "@doesNotExist");

            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("resolvedSuggest with no leading @ falls through to the method-invocation completer and returns its output")
        void resolvedSuggestWithoutAtFallsThroughToMethodCompleter() throws NoSuchMethodException {
            MethodFallbackFixture fixture = new MethodFallbackFixture();
            Method fixtureMethod = MethodFallbackFixture.class.getDeclaredMethod("mapping", String.class);
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .partialArg("")
                    .matchedMethod(fixtureMethod)
                    .parameterName("thing")
                    .executorInstance(fixture)
                    .build();

            List<String> suggestions = manager.suggest(context, "suggestValues");

            assertEquals(Arrays.asList("fallbackOne", "fallbackTwo"), suggestions);
        }

        @Test
        @DisplayName("a null resolvedSuggest falls through to the method-invocation completer")
        void nullResolvedSuggestFallsThrough() throws NoSuchMethodException {
            MethodFallbackFixture fixture = new MethodFallbackFixture();
            Method fixtureMethod = MethodFallbackFixture.class.getDeclaredMethod("mapping", String.class);
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .partialArg("")
                    .matchedMethod(fixtureMethod)
                    .parameterName("thing")
                    .executorInstance(fixture)
                    .build();

            List<String> suggestions = manager.suggest(context, null);

            assertEquals(Arrays.asList("fallbackOne", "fallbackTwo"), suggestions);
        }

        @Test
        @DisplayName("returns empty list for a null context regardless of resolvedSuggest")
        void nullContextReturnsEmpty() {
            List<String> suggestions = manager.suggest((TabCompletionContext) null, "@boolean");

            assertTrue(suggestions.isEmpty());
        }
    }


    // ============================================================================================
    // 05-06 Task 3: owner-scoped registration & unregisterByOwner() (D-08 / T-05-24)
    // ============================================================================================

    @Nested
    @DisplayName("Owner-scoped registration & unregisterByOwner() Tests (05-06 / D-08)")
    class OwnerScopedRegistrationTests {

        @Test
        @DisplayName("a completer registered during a scope is unregistered by unregisterByOwner for that owner")
        void scopedRegistrationIsSweptByOwner() {
            manager.beginRegistrationScope("moduleA");
            manager.register("@moduleAKey", ctx -> Collections.emptyList());
            manager.endRegistrationScope();

            int removed = manager.unregisterByOwner("moduleA");

            assertEquals(1, removed);
            assertNull(manager.getCompleter("@moduleAKey"));
        }

        @Test
        @DisplayName("unregisterByOwner for module A does not remove module B's completer")
        void ownerScopedSweepDoesNotTouchAnotherOwner() {
            manager.beginRegistrationScope("moduleA");
            manager.register("@moduleAKey2", ctx -> Collections.emptyList());
            manager.endRegistrationScope();

            manager.beginRegistrationScope("moduleB");
            manager.register("@moduleBKey", ctx -> Collections.emptyList());
            manager.endRegistrationScope();

            manager.unregisterByOwner("moduleA");

            assertNotNull(manager.getCompleter("@moduleBKey"));
        }

        @Test
        @DisplayName("a completer registered outside any scope (core) survives unregisterByOwner for any module")
        void unscopedCoreCompleterSurvivesAnyOwnerSweep() {
            manager.register("@coreFixtureKey", ctx -> Collections.emptyList());

            manager.unregisterByOwner("moduleA");
            manager.unregisterByOwner("anyOtherModule");

            assertNotNull(manager.getCompleter("@coreFixtureKey"));
        }

        @Test
        @DisplayName("after module A's sweep, module B can register the same key and have it resolve to B's completer")
        void keyCanBeReRegisteredByAnotherOwnerAfterSweep() {
            TabCompleter completerA = ctx -> Arrays.asList("fromA");
            TabCompleter completerB = ctx -> Arrays.asList("fromB");

            manager.beginRegistrationScope("moduleA");
            manager.register("@sharedKey", completerA);
            manager.endRegistrationScope();

            manager.unregisterByOwner("moduleA");
            assertNull(manager.getCompleter("@sharedKey"));

            manager.beginRegistrationScope("moduleB");
            manager.register("@sharedKey", completerB);
            manager.endRegistrationScope();

            assertSame(completerB, manager.getCompleter("@sharedKey"));
        }

        @Test
        @DisplayName("unregisterByOwner for a module that registered nothing is a no-op and throws nothing")
        void sweepingAnUnknownOwnerIsANoOp() {
            int removed = assertDoesNotThrow(() -> manager.unregisterByOwner("neverRegisteredModule"));

            assertEquals(0, removed);
        }

        @Test
        @DisplayName("unregisterByOwner(null) is a no-op")
        void sweepingNullOwnerIsANoOp() {
            assertEquals(0, manager.unregisterByOwner(null));
        }

        @Test
        @DisplayName("published singleton shape unchanged: getInstance/register/unregister behave identically for an ownership-unaware caller")
        void publishedShapeUnchangedForOwnershipUnawareCaller() {
            TabCompleter completer = ctx -> Arrays.asList("unaware");

            manager.register("@unawareKey", completer);
            assertSame(completer, manager.getCompleter("@unawareKey"));

            manager.unregister("@unawareKey");
            assertNull(manager.getCompleter("@unawareKey"));
        }
    }
}
