package com.ultikits.ultitools.commands.tabcomplete;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for MaterialsCompleter.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialsCompleter Tests")
class MaterialsCompleterTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor should create completer for all materials")
        void defaultConstructorCreatesallmaterialscompleter() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should have many materials (Minecraft has hundreds)
            assertTrue(suggestions.size() > 100);
        }

        @Test
        @DisplayName("Constructor with blocksOnly=true")
        void constructorBlocksonlytrue() {
            MaterialsCompleter completer = new MaterialsCompleter(true, false);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Verify all suggestions are blocks
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isBlock(), "Material " + suggestion + " should be a block");
            }
        }

        @Test
        @DisplayName("Constructor with itemsOnly=true")
        void constructorItemsOnlyTrue() {
            MaterialsCompleter completer = new MaterialsCompleter(false, true);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Verify all suggestions are items
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isItem(), "Material " + suggestion + " should be an item");
            }
        }

        @Test
        @DisplayName("blocksOnly takes precedence over itemsOnly")
        void blocksOnlyTakesprecedence() {
            // When both are true, blocksOnly should be applied
            MaterialsCompleter completer = new MaterialsCompleter(true, true);
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // All should be blocks
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isBlock(), "Material " + suggestion + " should be a block");
            }
        }
    }

    @Nested
    @DisplayName("complete() Method Tests")
    class CompleteMethodTests {

        private MaterialsCompleter completer;

        @BeforeEach
        void setUp() {
            completer = new MaterialsCompleter();
        }

        @Test
        @DisplayName("complete() should filter by prefix")
        void completeFiltersByPrefix() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"dia"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should contain diamond-related materials
            assertTrue(suggestions.stream().anyMatch(s -> s.startsWith("DIAMOND")));
            // All should start with "DIA"
            for (String suggestion : suggestions) {
                assertTrue(suggestion.toLowerCase().startsWith("dia"));
            }
        }

        @Test
        @DisplayName("complete() should be case insensitive")
        void completeCaseinsensitive() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"DIA"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should find diamond materials even with uppercase input
            assertTrue(suggestions.stream().anyMatch(s -> s.startsWith("DIAMOND")));
        }

        @Test
        @DisplayName("complete() should exclude legacy materials")
        void completeExcludeslegacymaterials() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // None should start with LEGACY_
            for (String suggestion : suggestions) {
                assertFalse(suggestion.startsWith("LEGACY_"), 
                        "Should not contain legacy material: " + suggestion);
            }
        }

        @Test
        @DisplayName("complete() should return sorted results")
        void completeReturnssortedresults() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"stone"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Verify sorted
            for (int i = 0; i < suggestions.size() - 1; i++) {
                assertTrue(suggestions.get(i).compareTo(suggestions.get(i + 1)) <= 0);
            }
        }

        @Test
        @DisplayName("complete() should return empty list when no match")
        void completeReturnsemptylistWhennomatch() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"xyz_not_a_material"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.isEmpty());
        }

        @Test
        @DisplayName("complete() should return all when input is empty")
        void completeReturnsallWheninputempty() {
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.size() > 100);
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("blocksOnly() should create blocks-only completer")
        void blocksOnlyCreatesblocksonlycompleter() {
            MaterialsCompleter completer = MaterialsCompleter.blocksOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // All should be blocks
            assertFalse(suggestions.isEmpty());
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isBlock(), "Material " + suggestion + " should be a block");
            }
        }

        @Test
        @DisplayName("itemsOnly() should create items-only completer")
        void itemsOnlyCreatesItemsOnlyCompleter() {
            MaterialsCompleter completer = MaterialsCompleter.itemsOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{""})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // All should be items
            assertFalse(suggestions.isEmpty());
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isItem(), "Material " + suggestion + " should be an item");
            }
        }

        @Test
        @DisplayName("blocksOnly() should filter specific materials")
        void blocksOnlyFiltersspecificmaterials() {
            MaterialsCompleter completer = MaterialsCompleter.blocksOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"stone"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should contain stone blocks
            assertTrue(suggestions.contains("STONE"));
        }

        @Test
        @DisplayName("itemsOnly() should filter specific materials")
        void itemsOnlyFiltersspecificmaterials() {
            MaterialsCompleter completer = MaterialsCompleter.itemsOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"diamond"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should contain diamond items
            assertTrue(suggestions.stream().anyMatch(s -> s.contains("DIAMOND")));
        }
    }

    @Nested
    @DisplayName("Specific Material Tests")
    class SpecificMaterialTests {

        @Test
        @DisplayName("Should find STONE material")
        void findsStoneMaterial() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"stone"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.contains("STONE"));
        }

        @Test
        @DisplayName("Should find DIAMOND_SWORD material")
        void findsDiamondSwordMaterial() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"diamond_sw"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.contains("DIAMOND_SWORD"));
        }

        @Test
        @DisplayName("Should find AIR material")
        void findsAirMaterial() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"air"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.contains("AIR"));
        }

        @Test
        @DisplayName("Blocks completer should contain STONE")
        void blocksCompleterContainsstone() {
            MaterialsCompleter completer = MaterialsCompleter.blocksOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"stone"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.contains("STONE"));
        }

        @Test
        @DisplayName("Items completer should filter by item status")
        void itemsCompleterFiltersbyitemstatus() {
            MaterialsCompleter completer = MaterialsCompleter.itemsOnly();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"diamond"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should contain diamond items
            assertTrue(suggestions.stream().anyMatch(s -> s.contains("DIAMOND")));
            // All suggestions should be items
            for (String suggestion : suggestions) {
                Material material = Material.valueOf(suggestion);
                assertTrue(material.isItem(), "Material " + suggestion + " should be an item");
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null args in context")
        void handlesNullArgs() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(null)
                    .currentArgIndex(0)
                    .build();
            
            // getCurrentInput() returns "" for null args
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.size() > 100);
        }

        @Test
        @DisplayName("Should handle empty string args")
        void handlesEmptyStringArgs() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[0])
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            assertTrue(suggestions.size() > 100);
        }

        @Test
        @DisplayName("Should handle underscore in search")
        void handlesUnderscoreInSearch() {
            MaterialsCompleter completer = new MaterialsCompleter();
            
            TabCompletionContext context = TabCompletionContext.builder()
                    .args(new String[]{"oak_"})
                    .currentArgIndex(0)
                    .build();
            
            List<String> suggestions = completer.complete(context);
            
            // Should find materials like OAK_LOG, OAK_PLANKS, etc.
            assertTrue(suggestions.stream().anyMatch(s -> s.startsWith("OAK_")));
        }
    }
}
