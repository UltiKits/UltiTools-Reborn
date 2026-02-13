package com.ultikits.plugins.kits.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KitDefinition")
class KitDefinitionTest {

    private KitDefinition kit;

    @BeforeEach
    void setUp() {
        kit = new KitDefinition();
    }

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("name should be null by default")
        void nameIsNull() {
            assertThat(kit.getName()).isNull();
        }

        @Test
        @DisplayName("displayName should be '&7Kit' by default")
        void displayNameDefault() {
            assertThat(kit.getDisplayName()).isEqualTo("&7Kit");
        }

        @Test
        @DisplayName("description should be empty list by default")
        void descriptionDefault() {
            assertThat(kit.getDescription()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("icon should be 'CHEST' by default")
        void iconDefault() {
            assertThat(kit.getIcon()).isEqualTo("CHEST");
        }

        @Test
        @DisplayName("price should be 0 by default")
        void priceDefault() {
            assertThat(kit.getPrice()).isEqualTo(0);
        }

        @Test
        @DisplayName("levelRequired should be 0 by default")
        void levelRequiredDefault() {
            assertThat(kit.getLevelRequired()).isEqualTo(0);
        }

        @Test
        @DisplayName("permission should be empty string by default")
        void permissionDefault() {
            assertThat(kit.getPermission()).isEmpty();
        }

        @Test
        @DisplayName("reBuyable should be false by default")
        void reBuyableDefault() {
            assertThat(kit.isReBuyable()).isFalse();
        }

        @Test
        @DisplayName("cooldown should be 0 by default")
        void cooldownDefault() {
            assertThat(kit.getCooldown()).isEqualTo(0);
        }

        @Test
        @DisplayName("playerCommands should be empty list by default")
        void playerCommandsDefault() {
            assertThat(kit.getPlayerCommands()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("consoleCommands should be empty list by default")
        void consoleCommandsDefault() {
            assertThat(kit.getConsoleCommands()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("items should be empty string by default")
        void itemsDefault() {
            assertThat(kit.getItems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Boolean Helpers")
    class BooleanHelpers {

        @Nested
        @DisplayName("isFree")
        class IsFree {

            @Test
            @DisplayName("returns true when price is 0")
            void trueWhenZero() {
                kit.setPrice(0);
                assertThat(kit.isFree()).isTrue();
            }

            @Test
            @DisplayName("returns true when price is negative")
            void trueWhenNegative() {
                kit.setPrice(-5.0);
                assertThat(kit.isFree()).isTrue();
            }

            @Test
            @DisplayName("returns false when price is positive")
            void falseWhenPositive() {
                kit.setPrice(100.0);
                assertThat(kit.isFree()).isFalse();
            }
        }

        @Nested
        @DisplayName("isOneTime")
        class IsOneTime {

            @Test
            @DisplayName("returns true when reBuyable is false")
            void trueWhenNotReBuyable() {
                kit.setReBuyable(false);
                assertThat(kit.isOneTime()).isTrue();
            }

            @Test
            @DisplayName("returns false when reBuyable is true")
            void falseWhenReBuyable() {
                kit.setReBuyable(true);
                assertThat(kit.isOneTime()).isFalse();
            }
        }

        @Nested
        @DisplayName("hasPermission")
        class HasPermission {

            @Test
            @DisplayName("returns false when permission is null")
            void falseWhenNull() {
                kit.setPermission(null);
                assertThat(kit.hasPermission()).isFalse();
            }

            @Test
            @DisplayName("returns false when permission is empty")
            void falseWhenEmpty() {
                kit.setPermission("");
                assertThat(kit.hasPermission()).isFalse();
            }

            @Test
            @DisplayName("returns true when permission is set")
            void trueWhenSet() {
                kit.setPermission("ultikits.kit.vip");
                assertThat(kit.hasPermission()).isTrue();
            }
        }

        @Nested
        @DisplayName("hasLevelRequirement")
        class HasLevelRequirement {

            @Test
            @DisplayName("returns false when levelRequired is 0")
            void falseWhenZero() {
                kit.setLevelRequired(0);
                assertThat(kit.hasLevelRequirement()).isFalse();
            }

            @Test
            @DisplayName("returns true when levelRequired is positive")
            void trueWhenPositive() {
                kit.setLevelRequired(10);
                assertThat(kit.hasLevelRequirement()).isTrue();
            }
        }

        @Nested
        @DisplayName("hasItems")
        class HasItems {

            @Test
            @DisplayName("returns false when items is null")
            void falseWhenNull() {
                kit.setItems(null);
                assertThat(kit.hasItems()).isFalse();
            }

            @Test
            @DisplayName("returns false when items is empty")
            void falseWhenEmpty() {
                kit.setItems("");
                assertThat(kit.hasItems()).isFalse();
            }

            @Test
            @DisplayName("returns true when items is non-empty")
            void trueWhenNonEmpty() {
                kit.setItems("base64encodeddata");
                assertThat(kit.hasItems()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("setName updates name")
        void setName() {
            kit.setName("starter");
            assertThat(kit.getName()).isEqualTo("starter");
        }

        @Test
        @DisplayName("setDisplayName updates displayName")
        void setDisplayName() {
            kit.setDisplayName("&aStarter Kit");
            assertThat(kit.getDisplayName()).isEqualTo("&aStarter Kit");
        }

        @Test
        @DisplayName("setIcon updates icon")
        void setIcon() {
            kit.setIcon("DIAMOND_SWORD");
            assertThat(kit.getIcon()).isEqualTo("DIAMOND_SWORD");
        }

        @Test
        @DisplayName("setPrice updates price")
        void setPrice() {
            kit.setPrice(99.99);
            assertThat(kit.getPrice()).isEqualTo(99.99);
        }

        @Test
        @DisplayName("setLevelRequired updates levelRequired")
        void setLevelRequired() {
            kit.setLevelRequired(5);
            assertThat(kit.getLevelRequired()).isEqualTo(5);
        }

        @Test
        @DisplayName("setPermission updates permission")
        void setPermission() {
            kit.setPermission("ultikits.kit.use");
            assertThat(kit.getPermission()).isEqualTo("ultikits.kit.use");
        }

        @Test
        @DisplayName("setReBuyable updates reBuyable")
        void setReBuyable() {
            kit.setReBuyable(true);
            assertThat(kit.isReBuyable()).isTrue();
        }

        @Test
        @DisplayName("setCooldown updates cooldown")
        void setCooldown() {
            kit.setCooldown(3600000L);
            assertThat(kit.getCooldown()).isEqualTo(3600000L);
        }

        @Test
        @DisplayName("setItems updates items")
        void setItems() {
            kit.setItems("serializedData");
            assertThat(kit.getItems()).isEqualTo("serializedData");
        }
    }

    @Nested
    @DisplayName("List Fields")
    class ListFields {

        @Test
        @DisplayName("description list is mutable")
        void descriptionMutable() {
            kit.getDescription().add("Line 1");
            kit.getDescription().add("Line 2");
            assertThat(kit.getDescription()).containsExactly("Line 1", "Line 2");
        }

        @Test
        @DisplayName("setDescription replaces list")
        void setDescription() {
            List<String> desc = Arrays.asList("New line");
            kit.setDescription(desc);
            assertThat(kit.getDescription()).containsExactly("New line");
        }

        @Test
        @DisplayName("playerCommands list is mutable")
        void playerCommandsMutable() {
            kit.getPlayerCommands().add("spawn");
            assertThat(kit.getPlayerCommands()).containsExactly("spawn");
        }

        @Test
        @DisplayName("setPlayerCommands replaces list")
        void setPlayerCommands() {
            List<String> cmds = Arrays.asList("cmd1", "cmd2");
            kit.setPlayerCommands(cmds);
            assertThat(kit.getPlayerCommands()).containsExactly("cmd1", "cmd2");
        }

        @Test
        @DisplayName("consoleCommands list is mutable")
        void consoleCommandsMutable() {
            kit.getConsoleCommands().add("give {player} diamond 1");
            assertThat(kit.getConsoleCommands()).containsExactly("give {player} diamond 1");
        }

        @Test
        @DisplayName("setConsoleCommands replaces list")
        void setConsoleCommands() {
            List<String> cmds = Arrays.asList("broadcast Welcome");
            kit.setConsoleCommands(cmds);
            assertThat(kit.getConsoleCommands()).containsExactly("broadcast Welcome");
        }
    }
}
