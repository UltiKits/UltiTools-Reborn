package com.ultikits.plugins.menu.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ButtonDefinition Tests")
class ButtonDefinitionTest {

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaults() {
            ButtonDefinition button = new ButtonDefinition();

            assertThat(button.getId()).isNull();
            assertThat(button.getItem()).isEqualTo(Material.STONE);
            assertThat(button.getPosition()).isEqualTo(0);
            assertThat(button.getName()).isEqualTo("");
            assertThat(button.getLore()).isNotNull().isEmpty();
            assertThat(button.getPlayerCommands()).isNotNull().isEmpty();
            assertThat(button.getConsoleCommands()).isNotNull().isEmpty();
            assertThat(button.getPrice()).isEqualTo(0.0);
            assertThat(button.getOpenMenu()).isNull();
            assertThat(button.isCloseOnClick()).isTrue();
            assertThat(button.getPermission()).isNull();
            assertThat(button.getCustomModelData()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all fields")
        void shouldSetAndGetAllFields() {
            ButtonDefinition button = new ButtonDefinition();
            button.setId("test_btn");
            button.setItem(Material.DIAMOND_SWORD);
            button.setPosition(13);
            button.setName("&cSword");
            button.setLore(Arrays.asList("Line 1", "Line 2"));
            button.setPlayerCommands(Arrays.asList("cmd1", "cmd2"));
            button.setConsoleCommands(Arrays.asList("say hello"));
            button.setPrice(99.9);
            button.setOpenMenu("submenu");
            button.setCloseOnClick(false);
            button.setPermission("vip.use");
            button.setCustomModelData(42);

            assertThat(button.getId()).isEqualTo("test_btn");
            assertThat(button.getItem()).isEqualTo(Material.DIAMOND_SWORD);
            assertThat(button.getPosition()).isEqualTo(13);
            assertThat(button.getName()).isEqualTo("&cSword");
            assertThat(button.getLore()).containsExactly("Line 1", "Line 2");
            assertThat(button.getPlayerCommands()).containsExactly("cmd1", "cmd2");
            assertThat(button.getConsoleCommands()).containsExactly("say hello");
            assertThat(button.getPrice()).isEqualTo(99.9);
            assertThat(button.getOpenMenu()).isEqualTo("submenu");
            assertThat(button.isCloseOnClick()).isFalse();
            assertThat(button.getPermission()).isEqualTo("vip.use");
            assertThat(button.getCustomModelData()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode Tests")
    class EqualsTests {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            ButtonDefinition btn1 = new ButtonDefinition();
            btn1.setId("test");
            btn1.setPosition(5);

            ButtonDefinition btn2 = new ButtonDefinition();
            btn2.setId("test");
            btn2.setPosition(5);

            assertThat(btn1).isEqualTo(btn2);
            assertThat(btn1.hashCode()).isEqualTo(btn2.hashCode());
        }
    }
}
