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
        void getInstance_returnsSameInstance() {
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
        void playersConstant_isCorrect() {
            assertEquals("@players", TabCompletionManager.PLAYERS);
        }

        @Test
        @DisplayName("WORLDS constant should be correct")
        void worldsConstant_isCorrect() {
            assertEquals("@worlds", TabCompletionManager.WORLDS);
        }

        @Test
        @DisplayName("MATERIALS constant should be correct")
        void materialsConstant_isCorrect() {
            assertEquals("@materials", TabCompletionManager.MATERIALS);
        }

        @Test
        @DisplayName("BLOCKS constant should be correct")
        void blocksConstant_isCorrect() {
            assertEquals("@blocks", TabCompletionManager.BLOCKS);
        }

        @Test
        @DisplayName("ITEMS constant should be correct")
        void itemsConstant_isCorrect() {
            assertEquals("@items", TabCompletionManager.ITEMS);
        }

        @Test
        @DisplayName("BOOLEAN constant should be correct")
        void booleanConstant_isCorrect() {
            assertEquals("@boolean", TabCompletionManager.BOOLEAN);
        }

        @Test
        @DisplayName("TOGGLE constant should be correct")
        void toggleConstant_isCorrect() {
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
        void register_addsCustomCompleter() {
            TabCompleter customCompleter = context -> Arrays.asList("custom1", "custom2");
            
            manager.register("@custom", customCompleter);
            
            TabCompleter retrieved = manager.getCompleter("@custom");
            assertSame(customCompleter, retrieved);
        }

        @Test
        @DisplayName("register() should throw exception for null key")
        void register_throwsException_forNullKey() {
            TabCompleter completer = context -> Collections.emptyList();
            
            assertThrows(IllegalArgumentException.class, () -> 
                manager.register(null, completer));
        }

        @Test
        @DisplayName("register() should throw exception for null completer")
        void register_throwsException_forNullCompleter() {
            assertThrows(IllegalArgumentException.class, () -> 
                manager.register("@test", null));
        }

        @Test
        @DisplayName("register() should overwrite existing completer")
        void register_overwritesExistingCompleter() {
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
        void unregister_removesCompleter() {
            TabCompleter completer = context -> Collections.emptyList();
            manager.register("@test", completer);
            
            manager.unregister("@test");
            
            assertNull(manager.getCompleter("@test"));
        }

        @Test
        @DisplayName("unregister() should handle non-existent key")
        void unregister_handlesNonExistentKey() {
            // Should not throw
            assertDoesNotThrow(() -> manager.unregister("@nonexistent"));
        }

        @Test
        @DisplayName("unregister() can remove built-in completer")
        void unregister_canRemoveBuiltInCompleter() {
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
        void getCompleter_returnsRegisteredCompleter() {
            TabCompleter completer = context -> Collections.emptyList();
            manager.register("@test", completer);
            
            assertSame(completer, manager.getCompleter("@test"));
        }

        @Test
        @DisplayName("getCompleter() should return null for unknown key")
        void getCompleter_returnsNull_forUnknownKey() {
            assertNull(manager.getCompleter("@unknown"));
        }

        @Test
        @DisplayName("getCompleter() should return built-in completers")
        void getCompleter_returnsBuiltInCompleters() {
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
        void suggest_createsContextAndDelegates() {
            String[] args = {"test"};
            
            // This method creates a context internally
            List<String> suggestions = manager.suggest(mockPlayer, mockCommand, args);
            
            assertNotNull(suggestions);
        }

        @Test
        @DisplayName("suggest() should handle empty args")
        void suggest_handlesEmptyArgs() {
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
        void suggest_returnsEmptyList_forNullContext() {
            List<String> suggestions = manager.suggest((TabCompletionContext) null);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggest() should use built-in completer when parameter starts with @")
        void suggest_usesBuiltInCompleter_forAtPrefix() {
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
        void suggest_delegatesToMethodCompleter_whenNoAtParameter() {
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
        void suggest_returnsEmpty_whenAtCompleterNotFound() {
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
        void suggestWith_usesSpecifiedCompleter() {
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
        void suggestWith_returnsEmpty_forUnknownCompleter() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestWith(context, "@unknown");
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggestWith() should work with custom completers")
        void suggestWith_worksWithCustomCompleters() {
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
        void createContext_createsContextWithCorrectValues() {
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
        void createContext_handlesEmptyArgs() {
            String[] args = new String[0];
            
            TabCompletionContext context = manager.createContext(mockPlayer, mockCommand, args);
            
            assertEquals(0, context.getCurrentArgIndex());
            assertEquals("", context.getPartialArg());
        }

        @Test
        @DisplayName("createContext() should handle single arg")
        void createContext_handlesSingleArg() {
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
        void filterByInput_filtersByPrefix() {
            List<String> suggestions = Arrays.asList("apple", "apricot", "banana", "cherry");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "ap");
            
            assertEquals(2, filtered.size());
            assertTrue(filtered.contains("apple"));
            assertTrue(filtered.contains("apricot"));
        }

        @Test
        @DisplayName("filterByInput() should be case insensitive")
        void filterByInput_caseInsensitive() {
            List<String> suggestions = Arrays.asList("Apple", "APRICOT", "banana");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "AP");
            
            assertEquals(2, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return all when input is empty")
        void filterByInput_returnsAll_whenInputEmpty() {
            List<String> suggestions = Arrays.asList("a", "b", "c");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, "");
            
            assertEquals(3, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return all when input is null")
        void filterByInput_returnsAll_whenInputNull() {
            List<String> suggestions = Arrays.asList("a", "b", "c");
            
            List<String> filtered = TabCompletionManager.filterByInput(suggestions, null);
            
            assertEquals(3, filtered.size());
        }

        @Test
        @DisplayName("filterByInput() should return empty for null suggestions")
        void filterByInput_returnsEmpty_forNullSuggestions() {
            List<String> filtered = TabCompletionManager.filterByInput(null, "test");
            
            assertTrue(filtered.isEmpty());
        }

        @Test
        @DisplayName("filterByInput() should return empty for empty suggestions")
        void filterByInput_returnsEmpty_forEmptySuggestions() {
            List<String> filtered = TabCompletionManager.filterByInput(Collections.emptyList(), "test");
            
            assertTrue(filtered.isEmpty());
        }

        @Test
        @DisplayName("filterByInput() should return empty when no match")
        void filterByInput_returnsEmpty_whenNoMatch() {
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
        void suggestFirstArgs_filtersByInput() throws NoSuchMethodException {
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
        void suggestFirstArgs_returnsSortedResults() throws NoSuchMethodException {
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
        void suggestFirstArgs_returnsEmpty_forNullMappings() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = manager.suggestFirstArgs(null, context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("suggestFirstArgs() should return empty for empty mappings")
        void suggestFirstArgs_returnsEmpty_forEmptyMappings() {
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
}
