package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for WorldsCompleter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorldsCompleter Tests")
class WorldsCompleterTest {

    @Mock
    private World mockWorld1;

    @Mock
    private World mockWorld2;

    @Mock
    private World mockWorld3;

    @Mock
    private World mockNetherWorld;

    @Mock
    private World mockEndWorld;

    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        bukkitMock = mockStatic(Bukkit.class);
        
        // Set up world names and environments
        when(mockWorld1.getName()).thenReturn("world");
        when(mockWorld1.getEnvironment()).thenReturn(World.Environment.NORMAL);
        
        when(mockWorld2.getName()).thenReturn("creative");
        when(mockWorld2.getEnvironment()).thenReturn(World.Environment.NORMAL);
        
        when(mockWorld3.getName()).thenReturn("survival");
        when(mockWorld3.getEnvironment()).thenReturn(World.Environment.NORMAL);
        
        when(mockNetherWorld.getName()).thenReturn("world_nether");
        when(mockNetherWorld.getEnvironment()).thenReturn(World.Environment.NETHER);
        
        when(mockEndWorld.getName()).thenReturn("world_the_end");
        when(mockEndWorld.getEnvironment()).thenReturn(World.Environment.THE_END);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    private void setupWorlds(World... worlds) {
        List<World> worldList = new ArrayList<>();
        for (World world : worlds) {
            worldList.add(world);
        }
        bukkitMock.when(Bukkit::getWorlds).thenReturn(worldList);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor creates completer for all worlds")
        void defaultConstructor_createsAllWorldsCompleter() {
            WorldsCompleter completer = new WorldsCompleter();
            
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("world"));
            assertTrue(suggestions.contains("world_nether"));
            assertTrue(suggestions.contains("world_the_end"));
        }

        @Test
        @DisplayName("Constructor with environment filter for NORMAL")
        void constructor_environmentFilterNormal() {
            WorldsCompleter completer = new WorldsCompleter(World.Environment.NORMAL);
            
            setupWorlds(mockWorld1, mockWorld2, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("world"));
            assertTrue(suggestions.contains("creative"));
            assertFalse(suggestions.contains("world_nether"));
            assertFalse(suggestions.contains("world_the_end"));
        }

        @Test
        @DisplayName("Constructor with environment filter for NETHER")
        void constructor_environmentFilterNether() {
            WorldsCompleter completer = new WorldsCompleter(World.Environment.NETHER);
            
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("world_nether"));
        }

        @Test
        @DisplayName("Constructor with environment filter for THE_END")
        void constructor_environmentFilterTheEnd() {
            WorldsCompleter completer = new WorldsCompleter(World.Environment.THE_END);
            
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("world_the_end"));
        }

        @Test
        @DisplayName("Constructor with multiple environment filters")
        void constructor_multipleEnvironmentFilters() {
            WorldsCompleter completer = new WorldsCompleter(
                    World.Environment.NORMAL, World.Environment.NETHER);
            
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("world"));
            assertTrue(suggestions.contains("world_nether"));
            assertFalse(suggestions.contains("world_the_end"));
        }
    }

    @Nested
    @DisplayName("complete() Method Tests")
    class CompleteMethodTests {

        private WorldsCompleter completer;

        @BeforeEach
        void setUp() {
            completer = new WorldsCompleter();
        }

        @Test
        @DisplayName("complete() should filter by prefix")
        void complete_filtersByPrefix() {
            setupWorlds(mockWorld1, mockWorld2, mockWorld3);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"w"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("world"));
        }

        @Test
        @DisplayName("complete() should be case insensitive")
        void complete_caseInsensitive() {
            setupWorlds(mockWorld1, mockWorld2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"C"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("creative"));
        }

        @Test
        @DisplayName("complete() should return empty list when no match")
        void complete_returnsEmptyList_whenNoMatch() {
            setupWorlds(mockWorld1, mockWorld2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"xyz"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return sorted results")
        void complete_returnsSortedResults() {
            setupWorlds(mockWorld3, mockWorld1, mockWorld2); // survival, world, creative
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals("creative", suggestions.get(0));
            assertEquals("survival", suggestions.get(1));
            assertEquals("world", suggestions.get(2));
        }

        @Test
        @DisplayName("complete() should handle empty worlds list")
        void complete_handlesEmptyWorldsList() {
            setupWorlds();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should handle single world")
        void complete_handlesSingleWorld() {
            setupWorlds(mockWorld1);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("world"));
        }
    }

    @Nested
    @DisplayName("Environment Filtering Tests")
    class EnvironmentFilteringTests {

        @Test
        @DisplayName("Should filter NORMAL worlds only")
        void filtersNormalWorldsOnly() {
            WorldsCompleter completer = new WorldsCompleter(World.Environment.NORMAL);
            setupWorlds(mockWorld1, mockWorld2, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            for (String worldName : suggestions) {
                // Verify not nether or end
                assertFalse(worldName.contains("nether"));
                assertFalse(worldName.contains("end"));
            }
        }

        @Test
        @DisplayName("Should return all worlds when no environment filter")
        void returnsAllWorldsWithoutFilter() {
            WorldsCompleter completer = new WorldsCompleter();
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
        }

        @Test
        @DisplayName("Environment filter should work with prefix filter")
        void environmentFilterWorksWithPrefixFilter() {
            WorldsCompleter completer = new WorldsCompleter(World.Environment.NORMAL);
            
            when(mockWorld1.getName()).thenReturn("test_world");
            when(mockWorld2.getName()).thenReturn("test_creative");
            setupWorlds(mockWorld1, mockWorld2, mockNetherWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"test"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("test_world"));
            assertTrue(suggestions.contains("test_creative"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle worlds with similar names")
        void handlesWorldsWithSimilarNames() {
            WorldsCompleter completer = new WorldsCompleter();
            
            when(mockWorld1.getName()).thenReturn("world");
            when(mockWorld2.getName()).thenReturn("world2");
            when(mockWorld3.getName()).thenReturn("worldx");
            setupWorlds(mockWorld1, mockWorld2, mockWorld3);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"world"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(3, suggestions.size());
        }

        @Test
        @DisplayName("Should handle world names with underscores")
        void handlesWorldNamesWithUnderscores() {
            WorldsCompleter completer = new WorldsCompleter();
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"world_"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("world_nether"));
            assertTrue(suggestions.contains("world_the_end"));
        }

        @Test
        @DisplayName("Should handle exact world name match")
        void handlesExactWorldNameMatch() {
            WorldsCompleter completer = new WorldsCompleter();
            setupWorlds(mockWorld1);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"world"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("world"));
        }

        @Test
        @DisplayName("Should handle null args in context")
        void handlesNullArgs() {
            WorldsCompleter completer = new WorldsCompleter();
            setupWorlds(mockWorld1, mockWorld2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(null)
                    .currentArgIndex(0)
                    .build();
            
            // getCurrentInput() returns "" for null args
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
        }

        @Test
        @DisplayName("Should handle empty environment filter array")
        void handlesEmptyEnvironmentFilterArray() {
            WorldsCompleter completer = new WorldsCompleter(new World.Environment[0]);
            setupWorlds(mockWorld1, mockNetherWorld, mockEndWorld);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // With empty filter array (not null), no worlds should match
            assertTrue(suggestions.isEmpty());
        }
    }
}
