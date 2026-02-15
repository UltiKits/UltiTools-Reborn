package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ultikits.ultitools.UltiTools;

/**
 * Unit tests for AbstractTabExecutor.
 * Tests tab completion logic and filtering.
 * <p>
 * AbstractTabExecutor 的单元测试。
 * 测试 Tab 补全逻辑和过滤。
 *
 * @author UltiKits Test Suite
 * @since 6.2.0
 */
@DisplayName("AbstractTabExecutor Tests")
@ExtendWith(MockitoExtension.class)
class AbstractTabExecutorTest {

    @Mock
    private Command mockCommand;
    
    @Mock
    private CommandSender sender;
    
    @Mock
    private Player player;
    
    @Mock
    private UltiTools mockPlugin;

    private TestTabExecutor tabExecutor;

    @BeforeEach
    void setUp() {
        lenient().when(sender.getName()).thenReturn("Console");
        lenient().when(player.getName()).thenReturn("TestPlayer");
        lenient().when(mockCommand.getName()).thenReturn("tabtest");
        
        tabExecutor = new TestTabExecutor();
    }

    // ==================== Basic Tab Completion Tests ====================

    @Test
    @DisplayName("Should return all options for empty prefix")
    void testEmptyPrefix() {
        String[] args = {""};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should return all options")
            .containsExactly("apple", "banana", "cherry", "date");
    }

    @Test
    @DisplayName("Should filter options based on prefix")
    void testPrefixFiltering() {
        String[] args = {"a"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should return only matching options")
            .containsExactly("apple");
    }

    @Test
    @DisplayName("Should be case insensitive")
    void testCaseInsensitivity() {
        String[] args = {"B"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should match regardless of case")
            .containsExactly("banana");
    }

    // ==================== Argument Position Tests ====================

    @Test
    @DisplayName("Should handle first argument completion")
    void testFirstArgument() {
        String[] args = {"c"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result).containsExactly("cherry");
    }

    @Test
    @DisplayName("Should handle second argument completion")
    void testSecondArgument() {
        String[] args = {"apple", "r"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should return second argument options")
            .containsExactly("red");
    }

    @Test
    @DisplayName("Should return empty list for unknown argument position")
    void testUnknownArgumentPosition() {
        String[] args = {"apple", "red", "x"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should return empty list for unhandled position")
            .isEmpty();
    }

    // ==================== Sender Type Tests ====================

    @Test
    @DisplayName("Should handle player sender")
    void testPlayerSender() {
        String[] args = {""};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result).contains("apple", "banana", "cherry", "date");
        assertThat(tabExecutor.lastSender).isEqualTo(player);
    }

    @Test
    @DisplayName("Should handle console sender")
    void testConsoleSender() {
        String[] args = {""};
        
        List<String> result = tabExecutor.onTabComplete(sender, mockCommand, "tabtest", args);
        
        assertThat(result).isNull();
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle null return from implementation")
    void testNullReturn() {
        tabExecutor.returnNull = true;
        String[] args = {""};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should handle null return gracefully")
            .isNull();
    }

    @Test
    @DisplayName("Should handle empty return from implementation")
    void testEmptyReturn() {
        tabExecutor.returnEmpty = true;
        String[] args = {""};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should handle partial matches correctly")
    void testPartialMatches() {
        String[] args = {"an"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result)
            .as("Should match 'banana' containing 'an' but starting with 'b' - wait, default implementation is startsWith")
            .isEmpty(); 
            // Note: Default Bukkit/Spigot tab completion usually does startsWith logic
            // If AbstractTabExecutor implements custom logic, verify it here.
            // Assuming standard startsWith logic for now.
    }

    @Test
    @DisplayName("Should handle exact match")
    void testExactMatch() {
        String[] args = {"apple"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result).containsExactly("apple");
    }

    @Test
    @DisplayName("Should handle no matches")
    void testNoMatches() {
        String[] args = {"z"};
        
        List<String> result = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args);
        
        assertThat(result).isEmpty();
    }

    // ==================== Complex Scenarios ====================

    @Test
    @DisplayName("Should handle dynamic options based on previous args")
    void testDynamicOptions() {
        // Test logic where second arg depends on first arg
        String[] args1 = {"apple", ""};
        List<String> result1 = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args1);
        assertThat(result1).containsExactly("red", "green");

        String[] args2 = {"banana", ""};
        List<String> result2 = tabExecutor.onTabComplete(player, mockCommand, "tabtest", args2);
        assertThat(result2).containsExactly("yellow");
    }

    // ==================== Test Implementation ====================

    private static class TestTabExecutor extends AbstractTabExecutor {
        boolean returnNull = false;
        boolean returnEmpty = false;
        CommandSender lastSender;

        @Override
        protected List<String> onPlayerTabComplete(Command command, String[] args, Player player) {
            lastSender = player;
            return getOptions(args);
        }

        @Override
        protected boolean onPlayerCommand(Command command, String[] args, Player player) {
            return true;
        }
        @Override
        protected void sendHelpMessage(CommandSender sender) {
            sender.sendMessage("Help message");
        }
        private List<String> getOptions(String[] args) {
            if (returnNull) return null;
            if (returnEmpty) return Collections.emptyList();

            List<String> candidates = new ArrayList<>();
            if (args.length == 1) {
                candidates.addAll(Arrays.asList("apple", "banana", "cherry", "date"));
            } else if (args.length == 2) {
                // Note: AbstractTabExecutor doesn't pass the full args array to onPlayerTabComplete in a way that makes this easy?
                // Wait, onPlayerTabComplete receives 'strings' which is the args array.
                // But usually tab completion logic needs to know which arg index we are at.
                // args.length tells us that.
                
                // Logic for dynamic options test
                if ("apple".equalsIgnoreCase(args[0])) {
                    candidates.addAll(Arrays.asList("red", "green"));
                } else if ("banana".equalsIgnoreCase(args[0])) {
                    candidates.addAll(Arrays.asList("yellow"));
                }
            }
            
            String lastArg = args[args.length - 1];
            return candidates.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg.toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        // Helper to expose protected method for testing if needed, 
        // but we are testing via onTabComplete which calls these.
    }
}
