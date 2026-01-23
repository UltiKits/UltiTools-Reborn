package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
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
 * Unit tests for OnlinePlayersCompleter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OnlinePlayersCompleter Tests")
class OnlinePlayersCompleterTest {

    @Mock
    private Player mockRequester;

    @Mock
    private Player mockPlayer1;

    @Mock
    private Player mockPlayer2;

    @Mock
    private Player mockPlayer3;

    @Mock
    private Server mockServer;

    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        bukkitMock = mockStatic(Bukkit.class);
        
        // Set up player names
        when(mockRequester.getName()).thenReturn("Requester");
        when(mockPlayer1.getName()).thenReturn("Alice");
        when(mockPlayer2.getName()).thenReturn("Bob");
        when(mockPlayer3.getName()).thenReturn("Charlie");
        
        // Requester can see all players by default
        when(mockRequester.canSee(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    private void setupOnlinePlayers(Player... players) {
        Collection<Player> onlinePlayers = new ArrayList<>();
        for (Player player : players) {
            onlinePlayers.add(player);
        }
        bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor creates standard completer")
        void defaultConstructorCreatesstandardcompleter() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            
            setupOnlinePlayers(mockRequester, mockPlayer1, mockPlayer2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should include self and all visible players
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("Requester"));
            assertTrue(suggestions.contains("Alice"));
            assertTrue(suggestions.contains("Bob"));
        }

        @Test
        @DisplayName("Constructor with excludeSelf=true")
        void constructorExcludeselftrue() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(true, false);
            
            setupOnlinePlayers(mockRequester, mockPlayer1, mockPlayer2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should exclude self
            assertEquals(2, suggestions.size());
            assertFalse(suggestions.contains("Requester"));
            assertTrue(suggestions.contains("Alice"));
            assertTrue(suggestions.contains("Bob"));
        }

        @Test
        @DisplayName("Constructor with vanishedVisible=false")
        void constructorVanishedvisiblefalse() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(false, false);
            
            setupOnlinePlayers(mockRequester, mockPlayer1, mockPlayer2);
            
            // Requester cannot see mockPlayer2
            when(mockRequester.canSee(mockPlayer2)).thenReturn(false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should not include hidden player
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("Requester"));
            assertTrue(suggestions.contains("Alice"));
            assertFalse(suggestions.contains("Bob"));
        }

        @Test
        @DisplayName("Constructor with vanishedVisible=true")
        void constructorVanishedvisibletrue() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(false, true);
            
            setupOnlinePlayers(mockRequester, mockPlayer1, mockPlayer2);
            
            // Requester cannot see mockPlayer2
            when(mockRequester.canSee(mockPlayer2)).thenReturn(false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should include hidden player when vanishedVisible is true
            assertEquals(3, suggestions.size());
            assertTrue(suggestions.contains("Bob"));
        }

        @Test
        @DisplayName("Constructor with both options true")
        void constructorBothoptionstrue() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(true, true);
            
            setupOnlinePlayers(mockRequester, mockPlayer1, mockPlayer2);
            when(mockRequester.canSee(mockPlayer2)).thenReturn(false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should exclude self but include vanished
            assertEquals(2, suggestions.size());
            assertFalse(suggestions.contains("Requester"));
            assertTrue(suggestions.contains("Alice"));
            assertTrue(suggestions.contains("Bob"));
        }
    }

    @Nested
    @DisplayName("complete() Method Tests")
    class CompleteMethodTests {

        private OnlinePlayersCompleter completer;

        @BeforeEach
        void setUp() {
            completer = new OnlinePlayersCompleter();
        }

        @Test
        @DisplayName("complete() should filter by prefix")
        void completeFiltersbyprefix() {
            setupOnlinePlayers(mockPlayer1, mockPlayer2, mockPlayer3);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"a"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Alice"));
        }

        @Test
        @DisplayName("complete() should be case insensitive")
        void completeCaseinsensitive() {
            setupOnlinePlayers(mockPlayer1, mockPlayer2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"A"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Alice"));
        }

        @Test
        @DisplayName("complete() should return empty list when no match")
        void completeReturnsemptylistWhennomatch() {
            setupOnlinePlayers(mockPlayer1, mockPlayer2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"xyz"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return sorted results")
        void completeReturnssortedresults() {
            setupOnlinePlayers(mockPlayer3, mockPlayer1, mockPlayer2); // Charlie, Alice, Bob
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals("Alice", suggestions.get(0));
            assertEquals("Bob", suggestions.get(1));
            assertEquals("Charlie", suggestions.get(2));
        }

        @Test
        @DisplayName("complete() should handle empty online players")
        void completeHandlesemptyonlineplayers() {
            setupOnlinePlayers();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should handle single player online")
        void completeHandlessingleplayeronline() {
            setupOnlinePlayers(mockRequester);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Requester"));
        }
    }

    @Nested
    @DisplayName("Visibility Tests")
    class VisibilityTests {

        @Test
        @DisplayName("Should hide vanished players by default")
        void hidesVanishedPlayersByDefault() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            setupOnlinePlayers(mockPlayer1, mockPlayer2);
            
            when(mockRequester.canSee(mockPlayer1)).thenReturn(true);
            when(mockRequester.canSee(mockPlayer2)).thenReturn(false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Alice"));
            assertFalse(suggestions.contains("Bob"));
        }

        @Test
        @DisplayName("Should show all players when vanishedVisible=true")
        void showsAllPlayersWhenVanishedVisible() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(false, true);
            setupOnlinePlayers(mockPlayer1, mockPlayer2);
            
            when(mockRequester.canSee(mockPlayer1)).thenReturn(true);
            when(mockRequester.canSee(mockPlayer2)).thenReturn(false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("Alice"));
            assertTrue(suggestions.contains("Bob"));
        }
    }

    @Nested
    @DisplayName("Self Exclusion Tests")
    class SelfExclusionTests {

        @Test
        @DisplayName("Should include self by default")
        void includesSelfByDefault() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            setupOnlinePlayers(mockRequester, mockPlayer1);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.contains("Requester"));
        }

        @Test
        @DisplayName("Should exclude self when excludeSelf=true")
        void excludesSelfWhenConfigured() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(true, false);
            setupOnlinePlayers(mockRequester, mockPlayer1);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertFalse(suggestions.contains("Requester"));
            assertTrue(suggestions.contains("Alice"));
        }

        @Test
        @DisplayName("Self exclusion should work with prefix filter")
        void selfExclusionWorksWithPrefixFilter() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter(true, false);
            setupOnlinePlayers(mockRequester, mockPlayer1);
            when(mockRequester.getName()).thenReturn("RequestPlayer");
            when(mockPlayer1.getName()).thenReturn("RandomUser");
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"r"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should only include RandomUser, not RequestPlayer
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("RandomUser"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle players with similar names")
        void handlesPlayersWithSimilarNames() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            
            when(mockPlayer1.getName()).thenReturn("Steve");
            when(mockPlayer2.getName()).thenReturn("Steven");
            when(mockPlayer3.getName()).thenReturn("Steward");
            setupOnlinePlayers(mockPlayer1, mockPlayer2, mockPlayer3);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"stev"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(2, suggestions.size());
            assertTrue(suggestions.contains("Steve"));
            assertTrue(suggestions.contains("Steven"));
            assertFalse(suggestions.contains("Steward"));
        }

        @Test
        @DisplayName("Should handle player names with special characters")
        void handlesPlayerNamesWithSpecialCharacters() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            
            when(mockPlayer1.getName()).thenReturn("Player_123");
            when(mockPlayer2.getName()).thenReturn("Player456");
            setupOnlinePlayers(mockPlayer1, mockPlayer2);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"player_"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Player_123"));
        }

        @Test
        @DisplayName("Should handle exact name match")
        void handlesExactNameMatch() {
            OnlinePlayersCompleter completer = new OnlinePlayersCompleter();
            setupOnlinePlayers(mockPlayer1);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .player(mockRequester)
                    .args(new String[]{"alice"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertEquals(1, suggestions.size());
            assertTrue(suggestions.contains("Alice"));
        }
    }
}
